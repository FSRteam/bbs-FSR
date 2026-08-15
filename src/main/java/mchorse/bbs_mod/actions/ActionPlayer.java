package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.DataPath;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ActionPlayer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-action-player");

    public Film film;
    public int tick;
    public boolean playing = true;
    public int countdown;
    public int exception;
    public PlayerType type;

    public boolean syncing;
    public boolean stopDamage = true;
    private boolean pendingResync;

    private ServerPlayer serverPlayer;
    private final ServerPlayer requester;
    private final boolean allowFirstPersonState;
    private final FirstPersonStateLeaseRegistry.Lease<ServerPlayer> firstPersonLease;
    private ServerLevel level;
    private int duration;

    private Map<String, LivingEntity> actors = new HashMap<>();
    private final ActionRetirementQueue<LivingEntity> actorRetirements = new ActionRetirementQueue<>();
    private final Map<Replay, BreakProgressContext.Session> breakProgressSessions = new IdentityHashMap<>();

    /** Snapshot only the equipment the first-person replay is allowed to borrow. */
    private List<ItemStack> cachedHotbar = new ArrayList<>();
    private Map<EquipmentSlot, ItemStack> cachedEquipment = new EnumMap<>(EquipmentSlot.class);
    private IEntity firstPersonEntity;
    private Form cachedForm;
    private CompoundTag cachedFoodData;

    private float cacheHp;
    private int cacheXpLevel;
    private float cacheXpProgress;
    private int cacheTotalExperience;
    private int cachedSelectedSlot;
    private boolean firstPersonStateApplied;
    private boolean borrowedEquipment;
    private boolean restoreServerForm;
    private boolean currentTickActionApplied;
    private boolean actorRetirementWarningLogged;
    private boolean stopping;
    private boolean forcedStop;

    public ActionPlayer(ServerPlayer serverPlayer, ServerLevel level, Film film, int tick, int countdown, int exception, PlayerType type)
    {
        this(
            serverPlayer,
            level,
            film,
            tick,
            countdown,
            exception,
            type,
            type == PlayerType.TARGETED_COMMAND ? null : serverPlayer,
            type == PlayerType.NORMAL
                && FilmActionAuthorityPolicy.isRequesterAuthorized(
                    serverPlayer,
                    level == null ? null : level.getServer()
                )
        );
    }

    public ActionPlayer(
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
        this(
            serverPlayer,
            level,
            film,
            tick,
            countdown,
            exception,
            type,
            requester,
            allowFirstPersonState,
            FirstPersonStateLeaseRegistry.deniedLease()
        );
    }

    ActionPlayer(
        ServerPlayer serverPlayer,
        ServerLevel level,
        Film film,
        int tick,
        int countdown,
        int exception,
        PlayerType type,
        @Nullable ServerPlayer requester,
        boolean allowFirstPersonState,
        FirstPersonStateLeaseRegistry.Lease<ServerPlayer> firstPersonLease
    )
    {
        this(
            serverPlayer,
            level,
            film,
            tick,
            countdown,
            exception,
            type,
            requester,
            allowFirstPersonState,
            firstPersonLease,
            false
        );
    }

    ActionPlayer(
        ServerPlayer serverPlayer,
        ServerLevel level,
        Film film,
        int tick,
        int countdown,
        int exception,
        PlayerType type,
        @Nullable ServerPlayer requester,
        boolean allowFirstPersonState,
        FirstPersonStateLeaseRegistry.Lease<ServerPlayer> firstPersonLease,
        boolean deferReplayEntities
    )
    {
        if (!hasRequiredDeliveryTarget(type, serverPlayer))
        {
            throw new IllegalArgumentException("Targeted playback requires a delivery target");
        }

        this.level = level;
        this.film = film;
        this.tick = tick;
        this.countdown = countdown;
        this.exception = exception;
        this.type = type;

        this.serverPlayer = serverPlayer;
        this.requester = requester;
        this.firstPersonLease = firstPersonLease == null
            ? FirstPersonStateLeaseRegistry.deniedLease()
            : firstPersonLease;

        boolean requesterAuthorized = this.isRequesterAuthorized();
        boolean canApplyFirstPersonState = FilmActionAuthorityPolicy.canApplyFirstPersonState(
            allowFirstPersonState,
            type,
            serverPlayer != null,
            requesterAuthorized
        );
        float maxHealth = serverPlayer == null ? Float.NaN : serverPlayer.getMaxHealth();

        if ((allowFirstPersonState && !canApplyFirstPersonState)
            || (FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized)
            || !FilmPlaybackPolicy.isPlaybackAllowed(film, maxHealth, canApplyFirstPersonState))
        {
            throw new IllegalArgumentException("Film playback authority or input validation failed");
        }

        boolean initialFirstPersonState = canApplyFirstPersonState
            && FilmPlaybackPolicy.findEnabledFirstPersonReplay(film) != null;

        if (initialFirstPersonState && !this.firstPersonLease.isHeld())
        {
            throw new IllegalStateException("First-person playback must be created through ActionManager");
        }

        this.allowFirstPersonState = canApplyFirstPersonState;
        this.duration = film.camera.calculateDuration();

        if (!deferReplayEntities && !this.tryUpdateReplayEntities())
        {
            throw new IllegalStateException("Could not stage replay actors for film " + film.getId());
        }
    }

    static boolean hasRequiredDeliveryTarget(PlayerType type, @Nullable ServerPlayer serverPlayer)
    {
        return type == null || !type.isTargetedDelivery() || serverPlayer != null;
    }

    void initializeFirstPersonState()
    {
        Replay fpReplay = FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film);

        if (this.canApplyFirstPersonState() && fpReplay != null)
        {
            this.applyFirstPersonState(fpReplay);
        }
    }

    public static void applyFilmPlayerSettingsTo(ServerPlayer player, float hp, float hunger, int xpLevel, float xpProgress)
    {
        if (!FilmPlayerSettingsPolicy.isAllowed(hp, player.getMaxHealth(), hunger, xpLevel, xpProgress))
        {
            return;
        }

        player.setHealth(hp);
        player.getFoodData().setFoodLevel((int) hunger);
        player.setExperienceLevels(xpLevel);
        player.experienceProgress = xpProgress;
    }

    private void applyFirstPersonState(Replay fpReplay)
    {
        if (this.firstPersonStateApplied
            || !this.canApplyFirstPersonState()
            || fpReplay == null
            || !fpReplay.enabled.get()
            || !FilmPlayerSettingsPolicy.isAllowed(
                this.film.hp.get(),
                this.serverPlayer.getMaxHealth(),
                this.film.hunger.get(),
                this.film.xpLevel.get(),
                this.film.xpProgress.get()
            ))
        {
            return;
        }

        if (!this.firstPersonLease.acquire())
        {
            throw new IllegalStateException("First-person player state is already owned by another film");
        }

        try
        {
            this.cachedHotbar.clear();
            this.cachedEquipment.clear();
            this.cachedSelectedSlot = this.serverPlayer.getInventory().selected;

            for (int i = 0; i < ReplayKeyframes.HOTBAR_SIZE; i++)
            {
                this.cachedHotbar.add(this.serverPlayer.getInventory().getItem(i).copy());
            }

            for (EquipmentSlot slot : ReplayKeyframes.DRESS_SLOTS)
            {
                this.cachedEquipment.put(slot, this.serverPlayer.getItemBySlot(slot).copy());
            }

            this.firstPersonEntity = new MCEntity(this.serverPlayer);

            this.cachedForm = null;

            Morph morph = Morph.getMorph(this.serverPlayer);

            if (morph != null)
            {
                this.cachedForm = FormUtils.copy(morph.getForm());
            }

            this.cacheHp = this.serverPlayer.getHealth();
            this.cachedFoodData = new CompoundTag();
            this.serverPlayer.getFoodData().addAdditionalSaveData(this.cachedFoodData);
            this.cacheXpLevel = this.serverPlayer.experienceLevel;
            this.cacheXpProgress = this.serverPlayer.experienceProgress;
            this.cacheTotalExperience = this.serverPlayer.totalExperience;
            this.borrowedEquipment = true;
            this.firstPersonStateApplied = true;

            this.clearFirstPersonEquipment();
            this.applyFirstPersonFrame(fpReplay, this.tick);

            ServerNetwork.sendMorphToTracked(this.serverPlayer, fpReplay.form.get());
            applyFilmPlayerSettingsTo(this.serverPlayer, this.film.hp.get(), this.film.hunger.get(), this.film.xpLevel.get(), this.film.xpProgress.get());
        }
        catch (RuntimeException | LinkageError e)
        {
            if (this.firstPersonStateApplied)
            {
                try
                {
                    this.restoreFirstPersonState();
                }
                catch (RuntimeException | LinkageError restoreError)
                {
                    e.addSuppressed(restoreError);
                }
            }
            else
            {
                this.cachedHotbar.clear();
                this.cachedEquipment.clear();
                this.firstPersonEntity = null;
                this.cachedFoodData = null;
                this.firstPersonLease.release();
            }

            throw e;
        }
    }

    private void refreshFirstPersonState(Replay fpReplay)
    {
        if (!this.firstPersonStateApplied
            || !this.canApplyFirstPersonState()
            || !this.firstPersonLease.isHeld()
            || fpReplay == null
            || !fpReplay.enabled.get()
            || !fpReplay.fp.get()
            || FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film) != fpReplay)
        {
            throw new IllegalStateException("First-person player state is not owned by this replay runtime");
        }

        this.applyFirstPersonFrame(fpReplay, this.tick);
        ServerNetwork.sendMorphToTracked(this.serverPlayer, fpReplay.form.get());
        applyFilmPlayerSettingsTo(
            this.serverPlayer,
            this.film.hp.get(),
            this.film.hunger.get(),
            this.film.xpLevel.get(),
            this.film.xpProgress.get()
        );
    }

    /** Clear borrowed cells once when playback starts; empty migrated channels stay silent later. */
    private void clearFirstPersonEquipment()
    {
        if (this.serverPlayer == null)
        {
            return;
        }

        for (int i = 0; i < ReplayKeyframes.HOTBAR_SIZE; i++)
        {
            this.serverPlayer.getInventory().setItem(i, ItemStack.EMPTY);
        }

        for (EquipmentSlot slot : ReplayKeyframes.DRESS_SLOTS)
        {
            this.serverPlayer.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private void applyFirstPersonFrame(Replay replay, float tick)
    {
        if (this.firstPersonEntity == null || replay == null)
        {
            return;
        }

        int slot = replay.keyframes.getSelectedSlot(tick);

        if (this.serverPlayer.getInventory().selected != slot)
        {
            ServerNetwork.sendSelectedSlot(this.serverPlayer, slot);
        }

        replay.keyframes.applyEquipment(tick, this.firstPersonEntity);
    }

    /** Retained for binary compatibility with addons compiled against the original API. */
    public void updateReplayEntities()
    {
        if (!this.tryUpdateReplayEntities())
        {
            this.requestForcedStop();
        }
    }

    boolean initializeReplayEntities()
    {
        return this.tryUpdateReplayEntities();
    }

    private boolean tryUpdateReplayEntities()
    {
        if (!FilmPlaybackPolicy.areReplayInputsAllowed(this.film)
            || !this.retryActorRetirements("preflight"))
        {
            return false;
        }

        List<Replay> list = this.film.replays.getList();
        Map<String, LivingEntity> nextActors = new HashMap<>();
        List<LivingEntity> stagedActors = new ArrayList<>();

        try
        {
            for (int i = 0; i < list.size(); i++)
            {
                Replay replay = list.get(i);
                boolean isActor = replay.actor.get() || replay.fp.get();

                if (i == this.exception || !isActor || !replay.enabled.get())
                {
                    continue;
                }

                if (replay.fp.get() && this.serverPlayer != null)
                {
                    if (this.canApplyFirstPersonState() && this.firstPersonLease.isHeld())
                    {
                        nextActors.put(replay.getId(), this.serverPlayer);
                    }
                }
                else
                {
                    ActorEntity actor = new ActorEntity(BBSMod.ACTOR_ENTITY.get(), this.level);

                    stagedActors.add(actor);
                    actor.setReplayId(replay.getId());

                    if (this.type.isTargetedDelivery() && this.serverPlayer != null)
                    {
                        actor.setPlaybackAudience(this.serverPlayer.getUUID());
                    }

                    actor.setForm(FormUtils.copy(replay.form.get()));

                    if (!this.applySafely(actor, replay, this.tick, false))
                    {
                        this.rollbackStagedActors(stagedActors);

                        return false;
                    }

                    nextActors.put(replay.getId(), actor);
                }
            }

            for (LivingEntity entity : stagedActors)
            {
                if (!this.level.addFreshEntity(entity))
                {
                    this.rollbackStagedActors(stagedActors);

                    return false;
                }
            }

            reconcileBreakProgressSessions(this.breakProgressSessions, list);
        }
        catch (RuntimeException | LinkageError e)
        {
            Throwable rollbackFailure = this.retireDetachedActors(stagedActors);

            if (rollbackFailure != null)
            {
                ActionTeardown.append(e, rollbackFailure);
                this.recordActorRetirementFailure("rollback", rollbackFailure);
            }

            LOGGER.warn("[BBS-SEM] topic=film.actor_stage phase=stage result=rollback film={}", this.film.getId(), e);

            return false;
        }

        Map<String, LivingEntity> previousActors = this.actors;

        /* One reference swap is the commit point. A failed pre-commit stage
         * leaves the exact live map untouched; retirement happens afterward
         * and remains owned by actorRetirements until it succeeds. */
        this.actors = nextActors;

        Throwable retirementFailure = this.retireDetachedActors(previousActors.values());

        if (retirementFailure != null)
        {
            this.recordActorRetirementFailure("old_actor_teardown", retirementFailure);
        }

        this.resendActors();

        return true;
    }

    /** Reassert the current replay-to-entity map after a staged replacement. */
    public void resendActors()
    {
        this.tryResendActors();
    }

    /** Send the current c12 mapping and report whether every delivery ran. */
    public boolean tryResendActors()
    {
        try
        {
            if (this.type.isTargetedDelivery() && this.serverPlayer != null)
            {
                ServerNetwork.sendActors(this.serverPlayer, this.film.getId(), this.actors);
            }
            else
            {
                for (ServerPlayer player : this.level.getPlayers((next) -> true))
                {
                    ServerNetwork.sendActors(player, this.film.getId(), this.actors);
                }
            }

            return true;
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=film.actor_stage phase=notify result=partial film={}", this.film.getId(), e);

            return false;
        }
    }

    private void rollbackStagedActors(List<LivingEntity> actors)
    {
        Throwable failure = this.retireDetachedActors(actors);

        if (failure != null)
        {
            this.recordActorRetirementFailure("rollback", failure);
        }
    }

    private Throwable retireDetachedActors(Iterable<? extends LivingEntity> actors)
    {
        for (LivingEntity actor : actors)
        {
            if (!(actor instanceof Player))
            {
                this.actorRetirements.retain(actor);
            }
        }

        return this.actorRetirements.drain(LivingEntity::discard);
    }

    private boolean retryActorRetirements(String phase)
    {
        Throwable failure = this.actorRetirements.drain(LivingEntity::discard);

        if (failure != null)
        {
            this.recordActorRetirementFailure(phase, failure);

            return false;
        }

        if (this.actorRetirements.isEmpty())
        {
            this.actorRetirementWarningLogged = false;
        }

        return true;
    }

    private void recordActorRetirementFailure(String phase, Throwable failure)
    {
        if (!this.actorRetirementWarningLogged)
        {
            LOGGER.warn("[BBS-SEM] topic=film.actor_stage phase={} result=retry film={} pending={}",
                phase,
                this.film == null ? "<unknown>" : this.film.getId(),
                this.actorRetirements.size(),
                failure);
        }

        this.actorRetirementWarningLogged = true;
    }

    public ServerLevel getLevel()
    {
        return this.level;
    }

    public ServerPlayer getServerPlayer()
    {
        return this.serverPlayer;
    }

    /**
     * Rebind a held first-person snapshot to NeoForge's replacement
     * ServerPlayer before teardown. PlayerList invokes PlayerEvent.Clone while
     * the new player is initialized but before it is installed in the player
     * list, so the lease transfer must not depend on current-player lookup.
     */
    boolean transferFirstPersonStateToClone(ServerPlayer original, ServerPlayer replacement)
    {
        if (!this.firstPersonStateApplied
            || original == null
            || replacement == null
            || original == replacement
            || this.serverPlayer != original
            || original.getServer() == null
            || replacement.getServer() != original.getServer()
            || !replacement.getUUID().equals(original.getUUID())
            || replacement.connection != original.connection
            || !this.firstPersonLease.transfer(original, replacement))
        {
            return false;
        }

        this.serverPlayer = replacement;
        this.restoreServerForm = true;

        return true;
    }

    /** Terminal lifecycle fallback after restoration to a clone failed. */
    void abandonFirstPersonState()
    {
        this.firstPersonStateApplied = false;
        this.borrowedEquipment = false;
        this.restoreServerForm = false;
        this.cachedHotbar.clear();
        this.cachedEquipment.clear();
        this.firstPersonEntity = null;
        this.cachedForm = null;
        this.cachedFoodData = null;
        this.firstPersonLease.release();
    }

    @Nullable
    public ServerPlayer getRequester()
    {
        return this.requester;
    }

    public void apply(LivingEntity actor, Replay replay, float tick, boolean ticking)
    {
        if (!this.hasRuntimeAuthority())
        {
            this.requestForcedStop();

            return;
        }

        this.applySafely(actor, replay, tick, ticking);
    }

    private boolean applySafely(LivingEntity actor, Replay replay, float tick, boolean ticking)
    {
        double x = replay.keyframes.x.interpolate(tick);
        double y = replay.keyframes.y.interpolate(tick);
        double z = replay.keyframes.z.interpolate(tick);
        float yawHead = replay.keyframes.headYaw.interpolate(tick).floatValue();
        float yawBody = replay.keyframes.bodyYaw.interpolate(tick).floatValue();
        float pitch = replay.keyframes.pitch.interpolate(tick).floatValue();

        double vx = x - replay.keyframes.x.interpolate(tick - 1);
        double vy = y - replay.keyframes.y.interpolate(tick - 1);
        double vz = z - replay.keyframes.z.interpolate(tick - 1);
        float fall = replay.keyframes.fall.interpolate(tick).floatValue();

        if (vy == 0D)
        {
            vy = -ReplayKeyframes.GRAVITY_PROBE;
        }

        if (!FilmPlaybackPolicy.isPoseAllowed(x, y, z, yawHead, yawHead, yawBody, pitch)
            || !FilmPlaybackPolicy.isVelocityAllowed(vx, vy, vz)
            || !FilmPlaybackPolicy.isFallDistanceAllowed(fall))
        {
            return false;
        }

        Vec3 pos = actor.position();
        boolean grounded = replay.keyframes.grounded.interpolate(tick) > 0;

        if (ticking)
        {
            double dY = y - pos.y - (grounded ? ReplayKeyframes.GRAVITY_PROBE : 0D);

            actor.move(MoverType.SELF, new Vec3(x - pos.x, dY, z - pos.z));
        }

        actor.setPos(x, y, z);
        actor.setYRot(yawHead);
        actor.setYHeadRot(yawHead);
        actor.setXRot(pitch);
        actor.setYBodyRot(yawBody);
        actor.setShiftKeyDown(replay.keyframes.sneaking.interpolate(tick) > 0);
        actor.setOnGround(grounded);
        actor.setSprinting(replay.keyframes.sprinting.interpolate(tick) > 0);

        boolean actorProjection = actor instanceof ActorEntity;
        boolean isolatedProjection = actor instanceof ActorEntity actorEntity
            && actorEntity.isPlaybackWorldIsolated();
        boolean applyServerEquipment = shouldApplyServerActorEquipment(
            actorProjection,
            isolatedProjection,
            this.isRequesterAuthorized()
        );

        if (actor instanceof ServerPlayer player)
        {
            if (this.borrowedEquipment && player == this.serverPlayer)
            {
                this.applyFirstPersonFrame(replay, tick);
            }
        }
        else if (applyServerEquipment)
        {
            replay.keyframes.applyEquipment(tick, new MCEntity(actor));
        }
        else
        {
            actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            actor.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        }

        actor.setDeltaMovement(vx, vy, vz);
        actor.fallDistance = fall;

        return true;
    }

    static boolean shouldApplyServerActorEquipment(
        boolean actorProjection,
        boolean isolatedProjection,
        boolean requesterAuthorized
    )
    {
        return !isolatedProjection && (!actorProjection || requesterAuthorized);
    }

    public boolean tick()
    {
        if (this.stopping)
        {
            return true;
        }

        this.retryActorRetirements("tick");

        if (!this.hasRuntimeAuthority())
        {
            this.requestForcedStop();

            return true;
        }

        if (this.countdown > 0)
        {
            this.countdown -= 1;

            return false;
        }

        for (Map.Entry<String, LivingEntity> entry : this.actors.entrySet())
        {
            Replay replay = (Replay) this.film.replays.get(entry.getKey());

            if (replay != null)
            {
                this.apply(entry.getValue(), replay, this.tick, true);
            }
        }

        if (!this.playing)
        {
            return false;
        }

        if (this.tick >= 0)
        {
            if (this.currentTickActionApplied)
            {
                this.currentTickActionApplied = false;
            }
            else
            {
                this.applyAction();
            }
        }

        if (this.stopping)
        {
            return true;
        }

        this.tick += 1;

        boolean finished = this.type != PlayerType.RECORDING
            && !this.syncing
            && this.tick >= this.duration;

        if (finished)
        {
            this.requestStop();
        }

        return finished;
    }

    private void applyAction()
    {
        boolean authorityRequired = FilmActionAuthorityPolicy.requiresAdministrator(this.film);

        if (!this.hasRuntimeAuthority(authorityRequired))
        {
            this.requestForcedStop();

            return;
        }

        SuperFakePlayer fakePlayer = SuperFakePlayer.get(this.level);
        List<Replay> list = this.film.replays.getList();

        ActionCommandContext.withRequester(this.requester, () ->
            PlaybackActorActionContext.withActors(this.actors.values(), () ->
                ActionTarget.withReplayActors(this.actors, () ->
        {
            for (int i = 0; i < list.size(); i++)
            {
                Replay replay = list.get(i);

                if (i == this.exception)
                {
                    this.clearBreakProgress(replay);

                    continue;
                }

                if (!replay.enabled.get())
                {
                    this.clearBreakProgress(replay);

                    continue;
                }

                LivingEntity actor = this.actors.get(replay.getId());

                if (!this.hasRuntimeAuthority(authorityRequired))
                {
                    this.requestForcedStop();

                    break;
                }

                BreakProgressContext.Session breakProgressSession = this.breakProgressSessions.computeIfAbsent(
                    replay,
                    (key) -> BreakProgressContext.createSession()
                );
                boolean continued = BreakProgressContext.withSession(breakProgressSession, () ->
                {
                    return replay.applyActions(
                        actor,
                        fakePlayer,
                        this.film,
                        this.tick,
                        () -> this.hasRuntimeAuthority(authorityRequired)
                    );
                });

                if (!continued || !this.hasRuntimeAuthority(authorityRequired))
                {
                    this.requestForcedStop();

                    break;
                }
            }
        })));
    }

    public void syncData(DataPath key, BaseType data)
    {
        if (this.stopping || !this.hasRuntimeAuthority())
        {
            this.requestForcedStop();
            this.requestFullResync();

            return;
        }

        /* findRecursively (not getRecursively) so an unresolvable path doesn't
         * throw and abort the whole server task. */
        BaseValue baseValue = this.film.findRecursively(key);

        if (baseValue != null)
        {
            boolean requesterAuthorized = this.isRequesterAuthorized();

            if (!requesterAuthorized
                && !FilmActionAuthorityPolicy.isRawMutationAllowedForNonAdministrator(
                    this.film,
                    baseValue,
                    key,
                    data
                ))
            {
                this.requestFullResync();

                return;
            }

            BaseType previous = null;
            boolean refreshActors = baseValue == this.film
                || baseValue.getParent() == this.film.replays
                || (baseValue.getId().equals("form") && baseValue.getParent() instanceof Replay)
                || baseValue.getId().equals("actor")
                || baseValue.getId().equals("enabled")
                || baseValue.getId().equals("fp")
                || baseValue.getId().equals("replays");
            boolean stateWasApplied = this.firstPersonStateApplied;
            Replay previousFirstPerson = FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film);
            boolean firstPersonRefreshAttempted = false;
            int nextDuration = this.duration;

            try
            {
                previous = baseValue.toData();

                if (previous == null)
                {
                    this.requestFullResync();

                    return;
                }

                baseValue.fromData(data);

                boolean appliesFirstPersonState = FilmActionAuthorityPolicy.canApplyFirstPersonState(
                    this.allowFirstPersonState,
                    this.type,
                    this.serverPlayer != null,
                    requesterAuthorized
                );
                float maxHealth = this.serverPlayer == null ? Float.NaN : this.serverPlayer.getMaxHealth();
                Replay nextFirstPerson = FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film);

                if ((FilmActionAuthorityPolicy.requiresAdministrator(this.film) && !requesterAuthorized)
                    || (this.allowFirstPersonState && nextFirstPerson != null && !appliesFirstPersonState)
                    || !FilmPlaybackPolicy.isPlaybackAllowed(this.film, maxHealth, appliesFirstPersonState))
                {
                    baseValue.fromData(previous);
                    this.requestFullResync();

                    return;
                }

                if (appliesFirstPersonState && !stateWasApplied && nextFirstPerson != null)
                {
                    this.applyFirstPersonState(nextFirstPerson);
                }

                if (stateWasApplied && FilmPlaybackPolicy.affectsFirstPersonDisplay(
                    this.film,
                    previousFirstPerson,
                    nextFirstPerson,
                    baseValue
                ))
                {
                    firstPersonRefreshAttempted = true;
                    this.refreshFirstPersonState(nextFirstPerson);
                }

                nextDuration = this.film.camera.calculateDuration();

                if (refreshActors && !this.tryUpdateReplayEntities())
                {
                    throw new IllegalStateException("Could not refresh replay actors");
                }

                if (appliesFirstPersonState && stateWasApplied && nextFirstPerson == null)
                {
                    try
                    {
                        this.restoreFirstPersonState();
                    }
                    catch (RuntimeException | LinkageError restoreError)
                    {
                        /* The film/actor transition is already committed. Keep
                         * the restore flag set so stop() can retry every field. */
                        this.requestForcedStop();
                        this.requestFullResync();

                        return;
                    }
                }
            }
            catch (RuntimeException | LinkageError failure)
            {
                boolean mutationRolledBack = false;

                if (!stateWasApplied && this.firstPersonStateApplied)
                {
                    try
                    {
                        this.restoreFirstPersonState();
                    }
                    catch (RuntimeException | LinkageError restoreError)
                    {
                        failure.addSuppressed(restoreError);
                        this.requestForcedStop();
                    }
                }

                if (previous != null)
                {
                    try
                    {
                        baseValue.fromData(previous);
                        mutationRolledBack = true;
                    }
                    catch (RuntimeException | LinkageError rollbackError)
                    {
                        failure.addSuppressed(rollbackError);
                        this.requestForcedStop();
                    }
                }

                if (stateWasApplied && firstPersonRefreshAttempted && mutationRolledBack)
                {
                    try
                    {
                        this.refreshFirstPersonState(FilmPlaybackPolicy.findEnabledFirstPersonReplay(this.film));
                    }
                    catch (RuntimeException | LinkageError refreshRollbackError)
                    {
                        failure.addSuppressed(refreshRollbackError);
                        this.requestForcedStop();
                    }
                }

                this.requestFullResync();

                return;
            }

            this.pendingResync = false;
            this.duration = nextDuration;
        }
        else
        {
            this.requestFullResync();
        }
    }

    public void goTo(int tick)
    {
        this.seekTo(tick);
    }

    public void goTo(int from, int tick)
    {
        this.seekTo(tick);
    }

    /**
     * Reposition an editor runtime without replaying one-shot film actions.
     * Commands, chat, item/block mutations, damage and interactions execute
     * only from the natural forward {@link #tick()} path.
     */
    public void seekTo(int tick)
    {
        if (this.stopping || !this.hasRuntimeAuthority())
        {
            this.requestForcedStop();

            return;
        }

        this.tick = tick;

        for (Map.Entry<String, LivingEntity> entry : this.actors.entrySet())
        {
            Replay replay = (Replay) this.film.replays.get(entry.getKey());

            if (replay != null)
            {
                this.applySafely(entry.getValue(), replay, tick, false);
            }
        }

        this.currentTickActionApplied = tick >= 0;
    }

    public void stop()
    {
        this.requestStop();

        ActionTeardown.runAll(
            this::discardCurrentActors,
            this::restoreFirstPersonState,
            this::clearAllBreakProgressSessions
        );
    }

    private void requestFullResync()
    {
        if (!this.pendingResync && this.serverPlayer != null)
        {
            /* The client edited data the server couldn't safely apply. Ask it
             * to re-send the whole film, debounced per player. */
            this.pendingResync = true;

            ServerNetwork.requestFilmResync(this.serverPlayer, this.film.getId());
        }
    }

    private void restoreFirstPersonState()
    {
        if (!this.firstPersonStateApplied)
        {
            this.cachedHotbar.clear();
            this.cachedEquipment.clear();
            this.firstPersonEntity = null;
            this.borrowedEquipment = false;
            this.cachedForm = null;
            this.cachedFoodData = null;
            this.firstPersonLease.release();

            return;
        }

        if (!this.firstPersonLease.isHeld())
        {
            this.firstPersonStateApplied = false;
            this.borrowedEquipment = false;
            this.restoreServerForm = false;
            this.cachedHotbar.clear();
            this.cachedEquipment.clear();
            this.firstPersonEntity = null;
            this.cachedForm = null;
            this.cachedFoodData = null;
            this.firstPersonLease.release();

            return;
        }

        Throwable failure = null;
        for (int i = 0; i < this.cachedHotbar.size(); i++)
        {
            try
            {
                this.serverPlayer.getInventory().setItem(i, this.cachedHotbar.get(i).copy());
            }
            catch (RuntimeException | LinkageError e)
            {
                failure = ActionTeardown.append(failure, e);
            }
        }

        for (Map.Entry<EquipmentSlot, ItemStack> entry : this.cachedEquipment.entrySet())
        {
            try
            {
                this.serverPlayer.setItemSlot(entry.getKey(), entry.getValue().copy());
            }
            catch (RuntimeException | LinkageError e)
            {
                failure = ActionTeardown.append(failure, e);
            }
        }

        try
        {
            ServerNetwork.sendSelectedSlot(this.serverPlayer, this.cachedSelectedSlot);
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = ActionTeardown.append(failure, e);
        }

        float health = this.cacheHp;

        try
        {
            float maxHealth = this.serverPlayer.getMaxHealth();

            if (Float.isFinite(maxHealth) && maxHealth > 0F)
            {
                health = Math.max(0F, Math.min(this.cacheHp, maxHealth));
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = ActionTeardown.append(failure, e);
        }

        try
        {
            this.serverPlayer.setHealth(health);
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = ActionTeardown.append(failure, e);
        }

        try
        {
            if (this.cachedFoodData == null)
            {
                throw new IllegalStateException("Missing cached first-person food state");
            }

            this.serverPlayer.getFoodData().readAdditionalSaveData(this.cachedFoodData.copy());
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = ActionTeardown.append(failure, e);
        }

        try
        {
            this.serverPlayer.totalExperience = this.cacheTotalExperience;
            this.serverPlayer.experienceProgress = this.cacheXpProgress;
            this.serverPlayer.setExperienceLevels(this.cacheXpLevel);
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = ActionTeardown.append(failure, e);
        }

        try
        {
            if (this.restoreServerForm)
            {
                Morph morph = Morph.getMorph(this.serverPlayer);

                if (morph == null)
                {
                    throw new IllegalStateException("Replacement player has no morph state");
                }

                morph.setForm(FormUtils.copy(this.cachedForm));
            }

            ServerNetwork.sendMorphToTracked(this.serverPlayer, this.cachedForm);
        }
        catch (RuntimeException | LinkageError e)
        {
            failure = ActionTeardown.append(failure, e);
        }

        ActionTeardown.throwIfFailed(failure);

        this.firstPersonStateApplied = false;
        this.borrowedEquipment = false;
        this.restoreServerForm = false;
        this.cachedHotbar.clear();
        this.cachedEquipment.clear();
        this.firstPersonEntity = null;
        this.cachedForm = null;
        this.cachedFoodData = null;
        this.firstPersonLease.release();
    }

    private void discardCurrentActors()
    {
        Throwable failure = this.actorRetirements.drain(LivingEntity::discard);
        Iterator<Map.Entry<String, LivingEntity>> iterator = this.actors.entrySet().iterator();

        while (iterator.hasNext())
        {
            LivingEntity actor = iterator.next().getValue();

            if (actor instanceof Player)
            {
                iterator.remove();
                continue;
            }

            try
            {
                actor.discard();
                iterator.remove();
            }
            catch (RuntimeException | LinkageError e)
            {
                failure = ActionTeardown.append(failure, e);
            }
        }

        if (failure == null && this.actorRetirements.isEmpty())
        {
            this.actorRetirementWarningLogged = false;
        }

        ActionTeardown.throwIfFailed(failure);
    }

    private boolean canApplyFirstPersonState()
    {
        return FilmActionAuthorityPolicy.canApplyFirstPersonState(
            this.allowFirstPersonState,
            this.type,
            this.serverPlayer != null,
            this.isRequesterAuthorized()
        );
    }

    private boolean isRequesterAuthorized()
    {
        return this.level != null
            && FilmActionAuthorityPolicy.isRequesterAuthorized(this.requester, this.level.getServer());
    }

    private boolean hasRuntimeAuthority()
    {
        return this.hasRuntimeAuthority(FilmActionAuthorityPolicy.requiresAdministrator(this.film));
    }

    private boolean hasRuntimeAuthority(boolean authorityRequired)
    {
        return this.hasCurrentTarget() && FilmActionAuthorityPolicy.hasRuntimeAuthority(
            authorityRequired,
            this.firstPersonStateApplied,
            this.isRequesterAuthorized()
        );
    }

    private boolean hasCurrentTarget()
    {
        if (this.serverPlayer == null
            || this.level == null
            || this.serverPlayer.serverLevel() != this.level)
        {
            return false;
        }

        MinecraftServer server = this.level.getServer();

        return this.serverPlayer.getServer() == server
            && server.getPlayerList().getPlayer(this.serverPlayer.getUUID()) == this.serverPlayer;
    }

    private void clearBreakProgress(Replay replay)
    {
        BreakProgressContext.Session session = this.breakProgressSessions.get(replay);

        if (session != null)
        {
            session.clear();
        }
    }

    private void clearAllBreakProgressSessions()
    {
        Throwable failure = null;
        Iterator<BreakProgressContext.Session> iterator = this.breakProgressSessions.values().iterator();

        while (iterator.hasNext())
        {
            BreakProgressContext.Session session = iterator.next();

            try
            {
                session.clear();
                iterator.remove();
            }
            catch (RuntimeException | LinkageError e)
            {
                failure = ActionTeardown.append(failure, e);
            }
        }

        ActionTeardown.throwIfFailed(failure);
    }

    static void reconcileBreakProgressSessions(
        Map<Replay, BreakProgressContext.Session> sessions,
        List<? extends Replay> retained
    )
    {
        Throwable failure = null;
        Iterator<Map.Entry<Replay, BreakProgressContext.Session>> iterator = sessions.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<Replay, BreakProgressContext.Session> entry = iterator.next();
            Replay key = entry.getKey();
            boolean present = false;

            for (Replay candidate : retained)
            {
                if (candidate == key)
                {
                    present = true;

                    break;
                }
            }

            if (!present || !key.enabled.get())
            {
                try
                {
                    entry.getValue().clear();

                    if (!present)
                    {
                        iterator.remove();
                    }
                }
                catch (RuntimeException | LinkageError e)
                {
                    failure = ActionTeardown.append(failure, e);
                }
            }
        }

        ActionTeardown.throwIfFailed(failure);
    }

    void requestStop()
    {
        this.requestStop(false);
    }

    void requestForcedStop()
    {
        this.requestStop(true);
    }

    private void requestStop(boolean forced)
    {
        this.forcedStop |= forced;
        this.stopping = true;
    }

    boolean isForcedStop()
    {
        return this.forcedStop;
    }

    boolean isFirstPersonStateApplied()
    {
        return this.firstPersonStateApplied;
    }

    public void toggle()
    {
        this.playing = !this.playing;
    }
}
