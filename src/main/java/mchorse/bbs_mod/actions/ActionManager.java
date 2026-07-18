package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.utils.DataPath;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ActionManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-actions");
    private List<ActionPlayer> players = new ArrayList<>();
    private Map<ServerPlayer, ActionRecorder> recorders = new HashMap<>();
    private Map<ServerPlayer, ActionPlayer> recordingPlayers = new HashMap<>();
    private Map<ServerLevel, DamageControl> dc = new HashMap<>();
    private final FirstPersonStateLeaseRegistry<ServerPlayer> firstPersonLeases = new FirstPersonStateLeaseRegistry<>();
    private final Map<ServerPlayer, ActionPlayer> cloneRestoreRetries = new IdentityHashMap<>();

    public void reset()
    {
        boolean playerTeardownFailed = false;

        for (ActionPlayer player : List.copyOf(this.players))
        {
            try
            {
                player.stop();

                int index = this.indexOfPlayerIdentity(player);

                if (index >= 0)
                {
                    this.players.remove(index);
                }

                this.clearCloneRestoreRetry(player);
            }
            catch (RuntimeException | LinkageError e)
            {
                playerTeardownFailed = true;

                if (this.indexOfPlayerIdentity(player) < 0)
                {
                    this.players.add(player);
                }

                LOGGER.warn("[BBS-SEM] topic=action.reset phase=player_teardown result=retry film={}",
                    player.film == null ? "<unknown>" : player.film.getId(),
                    e);
            }
        }

        for (DamageControl damageControl : List.copyOf(this.dc.values()))
        {
            try
            {
                damageControl.restore();
            }
            catch (RuntimeException | LinkageError e)
            {
                LOGGER.warn("[BBS-SEM] topic=action.reset phase=damage_restore result=partial", e);
            }
        }

        this.recordingPlayers.entrySet().removeIf((entry) -> this.indexOfPlayerIdentity(entry.getValue()) < 0);
        this.recorders.entrySet().removeIf((entry) -> !this.recordingPlayers.containsKey(entry.getKey()));
        this.dc.clear();

        /* A failed restore still owns the exact player snapshot and lease. Keep
         * that runtime reachable so the next reset/tick can retry every field;
         * clearing the registry here would allow a second film to overwrite the
         * partially restored player state. */
        if (!playerTeardownFailed && this.players.isEmpty())
        {
            this.cloneRestoreRetries.clear();
            this.firstPersonLeases.clear();
        }
    }

    public void tick()
    {
        for (ActionPlayer player : List.copyOf(this.players))
        {
            if (this.indexOfPlayerIdentity(player) < 0)
            {
                continue;
            }

            boolean shouldStop;

            try
            {
                shouldStop = player.tick();
            }
            catch (RuntimeException | LinkageError e)
            {
                player.requestForcedStop();
                shouldStop = true;

                LOGGER.warn("[BBS-SEM] topic=action.tick phase=playback result=stop film={}",
                    player.film == null ? "<unknown>" : player.film.getId(), e);
            }

            if (!shouldStop)
            {
                continue;
            }

            if (player.type == PlayerType.RECORDING)
            {
                this.finishRecordingState(player);

                continue;
            }

            if (!this.tryTeardown(player, "natural"))
            {
                continue;
            }

            this.clearCloneRestoreRetry(player);

            this.notifyStop(player);

            try
            {
                ServerNetwork.releaseFilmSession(player.film.getId(), player.getServerPlayer());
            }
            catch (RuntimeException | LinkageError e)
            {
                LOGGER.warn("[BBS-SEM] topic=action.tick phase=session_release result=retry film={}",
                    player.film == null ? "<unknown>" : player.film.getId(), e);

                continue;
            }

            int index = this.indexOfPlayerIdentity(player);

            if (index >= 0)
            {
                this.players.remove(index);
            }
        }

        for (Map.Entry<ServerPlayer, ActionRecorder> entry : List.copyOf(this.recorders.entrySet()))
        {
            entry.getValue().tick(entry.getKey());
        }
    }

    /* Actions playback */

    public void syncData(String filmId, DataPath key, BaseType data)
    {
        for (ActionPlayer player : this.players)
        {
            if (player.film.getId().equals(filmId))
            {
                player.syncData(key, data);
            }
        }
    }

    public ActionPlayer getPlayer(String filmId)
    {
        for (ActionPlayer player : this.players)
        {
            if (player.film.getId().equals(filmId))
            {
                return player;
            }
        }

        return null;
    }

    public ActionPlayer getPlayer(String filmId, ServerPlayer owner)
    {
        if (owner == null)
        {
            return null;
        }

        for (ActionPlayer player : this.players)
        {
            if (player.film.getId().equals(filmId) && player.getServerPlayer() == owner)
            {
                return player;
            }
        }

        return null;
    }

    /**
     * Return the exact runtime that owns a recording, if one is currently
     * staged for the owner.  Callers use identity rather than film id so a
     * retry cannot tear down a newer runtime with the same canonical id.
     */
    @Nullable
    public ActionPlayer getRecordingPlayer(ServerPlayer owner)
    {
        return owner == null ? null : this.recordingPlayers.get(owner);
    }

    /** Return the recorder object currently pinned to the owner. */
    @Nullable
    public ActionRecorder getRecorder(ServerPlayer owner)
    {
        return owner == null ? null : this.recorders.get(owner);
    }

    public ActionPlayer play(ServerPlayer serverPlayer, ServerLevel level, Film film, int tick)
    {
        return this.play(serverPlayer, level, film, tick, 0, -1, PlayerType.NORMAL);
    }

    public ActionPlayer play(ServerPlayer serverPlayer, ServerLevel level, Film film, int tick, PlayerType type)
    {
        return this.play(serverPlayer, level, film, tick, 0, -1, type);
    }

    public ActionPlayer play(ServerPlayer serverPlayer, ServerLevel level, Film film, int tick, int countdown, int exception, PlayerType type)
    {
        ServerPlayer requester = type == PlayerType.TARGETED_COMMAND ? null : serverPlayer;
        boolean allowFirstPersonState = type == PlayerType.NORMAL
            && FilmActionAuthorityPolicy.isRequesterAuthorized(requester);

        return this.playAuthorized(
            serverPlayer,
            level,
            film,
            tick,
            countdown,
            exception,
            type,
            requester,
            allowFirstPersonState
        );
    }

    public ActionPlayer playAuthorized(
        ServerPlayer serverPlayer,
        ServerLevel level,
        Film film,
        int tick,
        PlayerType type,
        @Nullable ServerPlayer requester,
        boolean allowFirstPersonState
    )
    {
        return this.playAuthorized(serverPlayer, level, film, tick, 0, -1, type, requester, allowFirstPersonState);
    }

    public ActionPlayer playAuthorized(
        ServerPlayer serverPlayer,
        ServerLevel level,
        Film film,
        int tick,
        int countdown,
        int exception,
        PlayerType type,
        @Nullable ServerPlayer requester,
        boolean allowFirstPersonState
    )
    {
        if (level == null || film == null || type == null
            || !ActionPlayer.hasRequiredDeliveryTarget(type, serverPlayer))
        {
            return null;
        }

        boolean requesterAuthorized = FilmActionAuthorityPolicy.isRequesterAuthorized(requester, level.getServer());
        boolean appliesFirstPersonState = FilmActionAuthorityPolicy.canApplyFirstPersonState(
            allowFirstPersonState,
            type,
            serverPlayer != null,
            requesterAuthorized
        );
        float maxHealth = serverPlayer == null ? Float.NaN : serverPlayer.getMaxHealth();

        if ((allowFirstPersonState && !appliesFirstPersonState)
            || (FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized)
            || !FilmPlaybackPolicy.isPlaybackAllowed(film, maxHealth, appliesFirstPersonState))
        {
            return null;
        }

        FirstPersonStateLeaseRegistry.Lease<ServerPlayer> firstPersonLease = this.firstPersonLeases.create(serverPlayer);
        boolean initialFirstPersonState = appliesFirstPersonState
            && FilmPlaybackPolicy.findEnabledFirstPersonReplay(film) != null;

        if (initialFirstPersonState && !firstPersonLease.acquire())
        {
            return null;
        }

        ActionPlayer player = null;

        try
        {
            player = new ActionPlayer(
                serverPlayer,
                level,
                film,
                tick,
                countdown,
                exception,
                type,
                requester,
                appliesFirstPersonState,
                firstPersonLease,
                true
            );

            player.stopDamage = false;

            if (!player.initializeReplayEntities())
            {
                throw new IllegalStateException("Could not stage replay actors for film " + film.getId());
            }

            player.initializeFirstPersonState();
            this.trackDamage(level);
            player.stopDamage = true;
            this.players.add(player);

            return player;
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=action.play phase=construct result=reject film={}", film.getId(), e);

            if (player == null)
            {
                firstPersonLease.release();
            }
            else
            {
                player.requestForcedStop();

                if (!this.tryTeardown(player, "construct"))
                {
                    this.players.add(player);
                }
                else
                {
                    firstPersonLease.release();
                }
            }

            return null;
        }
    }

    /**
     * Stage a FILM_EDITOR replacement beside one exact owned runtime and only
     * remove the old instance after the caller's pre-commit work succeeds.
     * Constructor, seek, or packet-delivery failures therefore roll back the
     * staged replacement without selecting another same-film runtime.
     */
    @Nullable
    public ActionPlayer replaceFilmEditorExact(
        ActionPlayer expected,
        ServerPlayer owner,
        ServerLevel level,
        Film film,
        int tick,
        Consumer<ActionPlayer> beforeCommit
    )
    {
        if (expected == null
            || owner == null
            || level == null
            || film == null
            || beforeCommit == null
            || expected.getServerPlayer() != owner
            || expected.getLevel() != level
            || expected.film == null
            || !expected.film.getId().equals(film.getId())
            || expected.type == PlayerType.RECORDING
            || expected.type.isTargetedDelivery()
            || this.indexOfPlayerIdentity(expected) < 0)
        {
            return null;
        }

        ActionPlayer replacement = null;
        boolean committed = false;
        boolean expectedStopped = false;
        boolean replacementMapReady = false;

        try
        {
            replacement = this.play(owner, level, film, tick, PlayerType.FILM_EDITOR);

            if (replacement == null)
            {
                return null;
            }

            beforeCommit.accept(replacement);

            /* Prove that the staged c12 mapping can be sent while the old
             * runtime is still authoritative.  This keeps a failed restart
             * from discarding the only known actor map. */
            replacementMapReady = replacement.tryResendActors();

            if (!replacementMapReady)
            {
                return null;
            }

            if (!this.stopExact(expected, "replace"))
            {
                return null;
            }

            expectedStopped = true;
            /* The old runtime may have emitted a c12 update during teardown;
             * reassert the committed replacement after that identity change. */
            replacement.resendActors();
            committed = true;

            return replacement;
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=action.replace phase=stage result=reject film={}", film.getId(), e);

            return null;
        }
        finally
        {
            if (!committed)
            {
                if (replacement != null && !this.stopExact(replacement, "replace_rollback"))
                {
                    LOGGER.warn("[BBS-SEM] topic=action.replace phase=rollback result=partial film={}", film.getId());
                }

                /* The staged constructor publishes c12 actor ids. Reassert the
                 * still-owned old mapping after every failed stage/callback,
                 * but never resurrect an old map after its exact runtime was
                 * already torn down. */
                if (!expectedStopped)
                {
                    expected.resendActors();
                }
            }
        }
    }

    /**
     * Tear down one exact runtime instance.  Film ids are intentionally not
     * accepted here: a restart may have already installed a newer runtime
     * carrying the same id, and broad matching would remove that instance.
     */
    public boolean stopExact(ActionPlayer candidate, String phase)
    {
        if (candidate == null || this.indexOfPlayerIdentity(candidate) < 0)
        {
            return false;
        }

        if (candidate.type == PlayerType.RECORDING)
        {
            ActionRecorder recorder = this.getRecordingState(candidate);

            if (recorder != null)
            {
                return ServerNetwork.finishRecordingTerminal(
                    candidate.getServerPlayer(),
                    candidate.film.getId(),
                    recorder,
                    candidate.isForcedStop()
                        ? ServerNetwork.RecordingTerminal.SERVER_FORCED
                        : ServerNetwork.RecordingTerminal.LEGACY_MANUAL
                );
            }
        }

        if (!this.tryTeardown(candidate, phase))
        {
            return false;
        }

        this.clearCloneRestoreRetry(candidate);

        int index = this.indexOfPlayerIdentity(candidate);

        if (index < 0)
        {
            /* A teardown callback may have removed this exact object already;
             * never remove by the stale pre-teardown index. */
            return true;
        }

        this.players.remove(index);

        return true;
    }

    public boolean stopExact(ActionPlayer candidate)
    {
        return this.stopExact(candidate, "exact");
    }

    private int indexOfPlayerIdentity(ActionPlayer candidate)
    {
        for (int i = 0; i < this.players.size(); i++)
        {
            if (this.players.get(i) == candidate)
            {
                return i;
            }
        }

        return -1;
    }

    private void clearCloneRestoreRetry(ActionPlayer candidate)
    {
        this.cloneRestoreRetries.values().removeIf((pending) -> pending == candidate);
    }

    private boolean tryTeardown(ActionPlayer player, String phase)
    {
        player.requestStop();

        try
        {
            player.stop();
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=action.stop phase={} result=retry film={}",
                phase,
                player.film == null ? "<unknown>" : player.film.getId(),
                e);

            return false;
        }

        if (player.stopDamage)
        {
            try
            {
                this.stopDamage(player.getLevel());
                player.stopDamage = false;
            }
            catch (RuntimeException | LinkageError e)
            {
                LOGGER.warn("[BBS-SEM] topic=action.stop phase={} result=retry_damage film={}",
                    phase,
                    player.film == null ? "<unknown>" : player.film.getId(),
                    e);

                return false;
            }
        }

        return true;
    }

    private void notifyStop(ActionPlayer player)
    {
        /* Recording has no playback UI stop. Film editor keeps its natural
         * completion behavior, but a forced teardown must close its owner UI. */
        if (!player.type.shouldSendStopNotification(player.isForcedStop()))
        {
            return;
        }

        if (player.type == PlayerType.TARGETED_COMMAND)
        {
            this.trySendStop(player.getServerPlayer(), player.film.getId());
        }
        else if (player.type == PlayerType.NORMAL)
        {
            for (ServerPlayer observer : player.getLevel().getPlayers((next) -> true))
            {
                this.trySendStop(observer, player.film.getId());
            }
        }
        else if (player.type == PlayerType.FILM_EDITOR)
        {
            this.trySendStop(player.getServerPlayer(), player.film.getId());
        }
    }

    private void trySendStop(ServerPlayer player, String filmId)
    {
        if (player == null)
        {
            return;
        }

        try
        {
            ServerNetwork.sendStopFilm(player, filmId);
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=action.stop phase=notify result=drop film={}", filmId, e);
        }
    }

    @Nullable
    private ActionRecorder getRecordingState(ActionPlayer player)
    {
        ServerPlayer owner = player.getServerPlayer();

        if (owner == null || this.recordingPlayers.get(owner) != player)
        {
            return null;
        }

        ActionRecorder recorder = this.recorders.get(owner);

        return recorder != null && recorder.getFilm() == player.film ? recorder : null;
    }

    private boolean finishRecordingState(ActionPlayer player)
    {
        ActionRecorder recorder = this.getRecordingState(player);

        if (recorder == null)
        {
            return false;
        }

        return ServerNetwork.finishRecordingTerminal(
            player.getServerPlayer(),
            player.film.getId(),
            recorder,
            player.isForcedStop()
                ? ServerNetwork.RecordingTerminal.SERVER_FORCED
                : ServerNetwork.RecordingTerminal.LEGACY_MANUAL
        );
    }

    public void stop(String filmId)
    {
        this.stopMatching(filmId, null, false);
    }

    public boolean stop(String filmId, ServerPlayer owner)
    {
        return owner != null && this.stopMatching(filmId, owner, true) > 0;
    }

    public int stopAll(ServerPlayer owner)
    {
        if (owner == null)
        {
            return 0;
        }

        List<ActionPlayer> owned = new ArrayList<>();

        for (ActionPlayer player : this.players)
        {
            if (player.getServerPlayer() == owner)
            {
                owned.add(player);
            }
        }

        int stopped = 0;

        for (ActionPlayer player : owned)
        {
            if (this.stopExact(player, "disconnect"))
            {
                stopped += 1;

                continue;
            }

            /* Logout fires before the vanilla player save. Give restoration
             * one complete attempt, then terminate state ownership so a
             * permanently failing addon field cannot leak across reconnects. */
            this.clearCloneRestoreRetry(player);

            if (player.isFirstPersonStateApplied())
            {
                player.abandonFirstPersonState();
            }

            if (this.stopExact(player, "disconnect_terminal"))
            {
                stopped += 1;
            }
            else
            {
                LOGGER.warn("[BBS-SEM] topic=action.stop phase=disconnect_terminal result=retry film={}",
                    player.film == null ? "<unknown>" : player.film.getId());
            }
        }

        return stopped;
    }

    /**
     * Retire every runtime bound to a ServerPlayer that NeoForge is replacing.
     * A held first-person lease moves to the clone before any cached state is
     * restored, so code observing PlayerEvent.Clone cannot acquire a second
     * lease on the new identity while teardown is incomplete.
     */
    public void handlePlayerClone(ServerPlayer original, ServerPlayer replacement)
    {
        if (!isValidPlayerClone(original, replacement))
        {
            return;
        }

        for (ActionPlayer player : this.ownedPlayers(original))
        {
            player.requestForcedStop();

            if (player.isFirstPersonStateApplied())
            {
                if (!player.transferFirstPersonStateToClone(original, replacement))
                {
                    /* Never restore a snapshot into an identity whose lease is
                     * already owned. Drop the stale snapshot and tear down the
                     * old runtime instead of allowing two state owners. */
                    player.abandonFirstPersonState();
                    LOGGER.warn("[BBS-SEM] topic=action.clone phase=lease_transfer result=fail_closed film={}",
                        player.film == null ? "<unknown>" : player.film.getId());
                }
                else
                {
                    this.cloneRestoreRetries.put(replacement, player);

                    /* ClientboundRespawnPacket has not been sent yet while
                     * PlayerEvent.Clone runs inside restoreFrom(). Defer every
                     * inventory/menu/morph packet until PlayerRespawnEvent. */
                    continue;
                }
            }

            if (this.stopExact(player, "clone"))
            {
                this.cloneRestoreRetries.remove(replacement, player);
                this.notifyStop(player);
            }
        }
    }

    /**
     * PlayerRespawnEvent runs after the replacement is installed in the level
     * and player list. Start restoration in that stable phase and retain the
     * exact runtime, snapshot, and lease for subsequent tick retries until the
     * complete restore succeeds or the replacement disconnects.
     */
    public void handlePlayerRespawn(ServerPlayer replacement)
    {
        if (replacement == null)
        {
            return;
        }

        ActionPlayer pending = this.cloneRestoreRetries.get(replacement);

        if (pending == null)
        {
            for (ActionPlayer runtime : List.copyOf(this.players))
            {
                ServerPlayer original = runtime.getServerPlayer();

                if (isValidPlayerClone(original, replacement))
                {
                    this.handlePlayerClone(original, replacement);
                    pending = this.cloneRestoreRetries.get(replacement);

                    break;
                }
            }
        }

        if (pending != null && this.indexOfPlayerIdentity(pending) >= 0)
        {
            pending.requestForcedStop();

            if (this.stopExact(pending, "respawn"))
            {
                this.notifyStop(pending);

                return;
            }

            LOGGER.warn("[BBS-SEM] topic=action.clone phase=respawn_teardown result=retry film={}",
                pending.film == null ? "<unknown>" : pending.film.getId());
        }
    }

    /** Ordinary dimension travel keeps one ServerPlayer identity but retires
     * level-bound playback state before the next server tick. */
    public void handlePlayerChangedDimension(ServerPlayer player)
    {
        if (player == null)
        {
            return;
        }

        for (ActionPlayer runtime : this.ownedPlayers(player))
        {
            runtime.requestForcedStop();

            if (this.stopExact(runtime, "dimension"))
            {
                this.notifyStop(runtime);
            }
        }
    }

    private List<ActionPlayer> ownedPlayers(ServerPlayer owner)
    {
        List<ActionPlayer> owned = new ArrayList<>();

        for (ActionPlayer player : this.players)
        {
            if (player.getServerPlayer() == owner)
            {
                owned.add(player);
            }
        }

        return owned;
    }

    private static boolean isValidPlayerClone(ServerPlayer original, ServerPlayer replacement)
    {
        return original != null
            && replacement != null
            && original != replacement
            && original.getServer() != null
            && replacement.getServer() == original.getServer()
            && replacement.getUUID().equals(original.getUUID())
            && replacement.connection == original.connection;
    }

    private int stopMatching(String filmId, ServerPlayer owner, boolean requireOwner)
    {
        List<ActionPlayer> matches = new ArrayList<>();

        for (ActionPlayer next : this.players)
        {
            if (next.film.getId().equals(filmId)
                && (!requireOwner || next.getServerPlayer() == owner))
            {
                matches.add(next);
            }
        }

        int stopped = 0;

        for (ActionPlayer next : matches)
        {
            if (this.stopExact(next, "manual"))
            {
                stopped += 1;
            }
        }

        return stopped;
    }

    /* Actions recording */

    /** Preserve the legacy direct-addon JVM descriptor. */
    public void startRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)
    {
        this.tryStartRecording(film, entity, tick, countdown, replayId);
    }

    public boolean tryStartRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)
    {
        if (film == null || entity == null || this.recorders.containsKey(entity) || this.recordingPlayers.containsKey(entity))
        {
            return false;
        }

        ActionRecorder recorder;

        try
        {
            /* Construct the recorder before publishing a RECORDING runtime. A
             * factory failure therefore has nothing server-side to roll back. */
            recorder = new ActionRecorder(film, entity, 0, countdown);
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=action.recording phase=recorder_construct result=reject film={}", film.getId(), e);

            return false;
        }

        ActionPlayer play = this.play(entity, entity.serverLevel(), film, tick, countdown, replayId, PlayerType.RECORDING);

        if (play == null)
        {
            ActionPlayer recovery = this.findUntrackedRecordingPlayer(entity, film);

            if (recovery != null)
            {
                recovery.requestForcedStop();
                this.recorders.put(entity, recorder);
                this.recordingPlayers.put(entity, recovery);
            }

            return false;
        }

        /* Playback starts at the requested film cursor, while recorded clips
         * stay relative for the client-side merge at that cursor. */
        this.recorders.put(entity, recorder);
        this.recordingPlayers.put(entity, play);

        return true;
    }

    @Nullable
    private ActionPlayer findUntrackedRecordingPlayer(ServerPlayer owner, Film film)
    {
        for (int i = this.players.size() - 1; i >= 0; i--)
        {
            ActionPlayer player = this.players.get(i);

            if (player.type == PlayerType.RECORDING
                && player.getServerPlayer() == owner
                && player.film == film
                && this.recordingPlayers.get(owner) != player)
            {
                return player;
            }
        }

        return null;
    }

    public boolean hasRecording(ServerPlayer entity)
    {
        return entity != null && (this.recorders.containsKey(entity) || this.recordingPlayers.containsKey(entity));
    }

    public void addAction(ServerPlayer entity, Supplier<ActionClip> supplier)
    {
        ActionRecorder recorder = this.recorders.get(entity);

        if (recorder != null && supplier != null)
        {
            ActionClip actionClip = supplier.get();

            if (actionClip != null)
            {
                recorder.add(actionClip);
            }
        }
    }

    /**
     * Add an action only while the supplied recorder still owns the player's
     * active recording.  This keeps deferred or callback-based captures from
     * crossing into a replacement recording on the same player.
     */
    public boolean addActionExact(ServerPlayer entity, ActionRecorder expected, Supplier<ActionClip> supplier)
    {
        if (entity == null || expected == null || supplier == null || this.recorders.get(entity) != expected)
        {
            return false;
        }

        ActionClip actionClip = supplier.get();

        if (actionClip == null || this.recorders.get(entity) != expected)
        {
            return false;
        }

        expected.add(actionClip);

        return true;
    }

    public ActionRecorder stopRecording(ServerPlayer entity)
    {
        return this.stopRecordingExact(entity, this.getRecorder(entity));
    }

    @Nullable
    public ActionRecorder prepareRecordingTerminalExact(
        ServerPlayer entity,
        @Nullable ActionRecorder expected,
        boolean forced
    )
    {
        ActionRecorder recorder = entity == null ? null : this.recorders.get(entity);
        ActionPlayer recordingPlayer = entity == null ? null : this.recordingPlayers.get(entity);

        if (recorder == null
            || expected == null
            || recorder != expected
            || recordingPlayer == null
            || recordingPlayer.getServerPlayer() != entity
            || recorder.getFilm() != recordingPlayer.film)
        {
            return null;
        }

        try
        {
            recorder.prepareTerminal(forced);
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=action.recording phase=compose result=retry film={}",
                recorder.getFilm().getId(), e);

            return null;
        }

        if (!recorder.isTerminalTeardownComplete())
        {
            if (this.indexOfPlayerIdentity(recordingPlayer) >= 0
                && !this.tryTeardown(recordingPlayer, "recording_terminal"))
            {
                return null;
            }

            recorder.markTerminalTeardownComplete();
        }

        return recorder;
    }

    public boolean commitRecordingTerminalExact(ServerPlayer entity, @Nullable ActionRecorder expected)
    {
        return this.removeRecordingState(entity, expected, true);
    }

    public void abortRecordingOnDisconnect(ServerPlayer entity)
    {
        if (entity == null)
        {
            return;
        }

        ActionPlayer recordingPlayer = this.recordingPlayers.remove(entity);

        this.recorders.remove(entity);

        if (recordingPlayer == null)
        {
            return;
        }

        recordingPlayer.requestForcedStop();

        if (!this.tryTeardown(recordingPlayer, "recording_disconnect"))
        {
            LOGGER.warn("[BBS-SEM] topic=action.recording phase=disconnect result=discard film={}",
                recordingPlayer.film == null ? "<unknown>" : recordingPlayer.film.getId());
        }

        int index = this.indexOfPlayerIdentity(recordingPlayer);

        if (index >= 0)
        {
            this.players.remove(index);
        }

        this.clearCloneRestoreRetry(recordingPlayer);
    }

    /**
     * Stop only the recorder object supplied by the caller.  A stale c7/cancel
     * packet must not consume a recorder belonging to a newer recording on the
     * same player.
     */
    @Nullable
    public ActionRecorder stopRecordingExact(ServerPlayer entity, @Nullable ActionRecorder expected)
    {
        ActionRecorder recorder = this.prepareRecordingTerminalExact(entity, expected, false);

        if (recorder == null || !this.removeRecordingState(entity, recorder, false))
        {
            return null;
        }

        return recorder;
    }

    private boolean removeRecordingState(ServerPlayer entity, @Nullable ActionRecorder expected, boolean requireDelivery)
    {
        ActionRecorder recorder = entity == null ? null : this.recorders.get(entity);
        ActionPlayer recordingPlayer = entity == null ? null : this.recordingPlayers.get(entity);

        if (recorder == null
            || expected == null
            || recorder != expected
            || !recorder.isTerminalTeardownComplete()
            || (requireDelivery && !recorder.isTerminalDelivered()))
        {
            return false;
        }

        if (recordingPlayer != null)
        {
            int index = this.indexOfPlayerIdentity(recordingPlayer);

            if (index >= 0)
            {
                this.players.remove(index);
            }

            this.clearCloneRestoreRetry(recordingPlayer);
            this.recordingPlayers.remove(entity, recordingPlayer);
        }

        this.recorders.remove(entity, recorder);

        return true;
    }

    /* Damage control */

    public void trackDamage(ServerLevel world)
    {
        DamageControl damageControl = this.dc.get(world);

        if (damageControl == null)
        {
            this.dc.put(world, new DamageControl(world));
        }
        else
        {
            damageControl.nested += 1;
        }
    }

    public void stopDamage(ServerLevel world)
    {
        DamageControl damageControl = this.dc.get(world);

        if (damageControl != null)
        {
            if (damageControl.nested > 0)
            {
                damageControl.nested -= 1;
            }
            else
            {
                damageControl.restore();
                this.dc.remove(world);
            }
        }
    }

    public void resetDamage(ServerLevel world)
    {
        DamageControl dc = this.dc.remove(world);

        if (dc != null)
        {
            dc.restore();
        }
    }

    public void changedBlock(BlockPos pos, BlockState state, CompoundTag blockEntity)
    {
        for (DamageControl control : this.dc.values())
        {
            control.addBlock(pos, state, blockEntity);
        }
    }

    public void spawnedEntity(Entity entity)
    {
        for (DamageControl control : this.dc.values())
        {
            control.addEntity(entity);
        }
    }
}
