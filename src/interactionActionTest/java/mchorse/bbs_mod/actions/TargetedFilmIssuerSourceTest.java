package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.entity.PlaybackActorDamageIsolationSourceTest;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.LoadingModList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Source and policy regressions for command-triggered per-target playback. */
public final class TargetedFilmIssuerSourceTest
{
    private static final Path BBS_COMMANDS = Path.of(
        "src/main/java/mchorse/bbs_mod/BBSCommands.java"
    );
    private static final Path SERVER_NETWORK = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );
    private static final Path ACTION_PLAYER = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java"
    );
    private static final Path ACTION_MANAGER = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/ActionManager.java"
    );
    private static final Path ACTOR_ENTITY = Path.of(
        "src/main/java/mchorse/bbs_mod/entity/ActorEntity.java"
    );
    private static final Path PLAYBACK_ACTOR_ACTION_CONTEXT = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/PlaybackActorActionContext.java"
    );
    private static final Path ATTACK_ACTION = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/types/AttackActionClip.java"
    );
    private static final Path DAMAGE_ACTION = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/types/DamageActionClip.java"
    );
    private static final Path ENTITY_INTERACTION_ACTION = Path.of(
        "src/main/java/mchorse/bbs_mod/actions/types/EntityInteractionActionClip.java"
    );

    private TargetedFilmIssuerSourceTest()
    {}

    public static void main(String[] args) throws Exception
    {
        bootstrapStandaloneMinecraftRuntime();
        runAll();
        System.out.println("TargetedFilmIssuerSourceTest passed");
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    public static void runAll() throws Exception
    {
        productionEntryKeepsIssuerSeparateFromTarget();
        targetedPlaybackRequiresDeliveryTarget();
        targetedActorsArePrivateWorldProjections();
        PlaybackActorDamageIsolationSourceTest.runAll();
        playbackActorActionScopeRestoresAndUsesIdentity();
        missingIssuerFailsClosedForEffectfulFilms();
        requesterScopeRestoresAfterFailureAndIsolatesThreads();
    }

    private static void productionEntryKeepsIssuerSeparateFromTarget() throws IOException
    {
        Path root = findProjectRoot();
        String commands = compact(Files.readString(root.resolve(BBS_COMMANDS)));
        String network = compact(Files.readString(root.resolve(SERVER_NETWORK)));

        check(commands.contains(
                "ServerPlayer requester = source.getSource().getEntity() instanceof ServerPlayer player ? player : null;"),
            "targeted film command no longer derives its issuer from the real command source");
        check(commands.contains(
                "for (ServerPlayer target : players) { ServerNetwork.sendPlayFilm(target, requester, filmId, withCamera); }"),
            "targeted film command replaced or dropped the issuer while iterating ordinary targets");
        check(network.contains(
                "public static void sendPlayFilm(ServerPlayer player, String filmId, boolean withCamera) "
                    + "{ sendPlayFilm(player, (ServerPlayer) null, filmId, withCamera); }"),
            "legacy targeted playback no longer defaults to a missing requester");
        check(network.contains(
                "boolean requesterAuthorized = AuthorizedCommandExecutor.isAuthorized(requester, player.getServer());")
                && network.contains("boolean allowFirstPersonState = requesterAuthorized;"),
            "targeted playback no longer revalidates issuer authority against the target server");
        check(network.contains(
                "actions.playAuthorized( player, player.serverLevel(), film, 0, PlayerType.TARGETED_COMMAND, "
                    + "requester, allowFirstPersonState )"),
            "targeted playback no longer passes target and issuer separately to ActionManager");
    }

    private static void targetedActorsArePrivateWorldProjections() throws IOException
    {
        Path root = findProjectRoot();
        String actionPlayer = compact(Files.readString(root.resolve(ACTION_PLAYER)));
        String actor = compact(Files.readString(root.resolve(ACTOR_ENTITY)));
        String actorActionContext = compact(Files.readString(root.resolve(PLAYBACK_ACTOR_ACTION_CONTEXT)));
        String attack = compact(Files.readString(root.resolve(ATTACK_ACTION)));
        String damage = compact(Files.readString(root.resolve(DAMAGE_ACTION)));
        String entityInteraction = compact(Files.readString(root.resolve(ENTITY_INTERACTION_ACTION)));
        int audienceAssignment = actionPlayer.indexOf(
            "actor.setPlaybackAudience(this.serverPlayer.getUUID());"
        );
        int worldInsertion = actionPlayer.indexOf("this.level.addFreshEntity(entity)");

        check(audienceAssignment >= 0 && worldInsertion > audienceAssignment,
            "targeted actor entered the shared world before its audience boundary was installed");
        check(actor.contains(
                "return this.isPlaybackVisibleTo(player) && super.broadcastToPlayer(player);"),
            "targeted actor tracking no longer filters non-audience clients");
        check(actor.contains("this.noPhysics = isolated;")
                && actor.contains("this.setNoGravity(isolated);")
                && actor.contains("this.setInvulnerable(isolated);"),
            "targeted actor no longer disables shared-world physics and damage state");
        check(actor.contains("builder.define(PLAYBACK_WORLD_ISOLATED, false);")
                && actor.contains("this.entityData.set(PLAYBACK_WORLD_ISOLATED, isolated);")
                && actor.contains("this.noPhysics = this.isPlaybackWorldIsolated();"),
            "targeted actor isolation no longer reaches the intended client");
        check(actor.contains(
                "public boolean isSpectator() { return this.isPlaybackWorldIsolated() || super.isSpectator(); }"),
            "targeted actor no longer opts out of public entity selectors");
        check(actor.contains(
                "public boolean isPickable() { return !this.isPlaybackWorldIsolated() && super.isPickable(); }"),
            "targeted actor became available to ray or projectile picking");
        check(actor.contains(
                "public boolean isPushable() { return !this.isPlaybackWorldIsolated() && super.isPushable(); }"),
            "targeted actor became pushable in the public world");
        check(actor.contains(
                "private boolean isPlaybackActionAuthorized() { return this.isPlaybackWorldIsolated() "
                    + "&& PlaybackActorActionContext.isAuthorizedFor(this); }"),
            "targeted actor actions no longer require both isolation and the authorized owner scope");
        check(actor.contains(
                "public boolean isAttackable() { return this.isPlaybackActionAuthorized() "
                    + "|| (!this.isPlaybackWorldIsolated() && super.isAttackable()); }"),
            "authorized recorded attacks cannot address their private replay actor target");
        check(actor.contains(
                "public boolean isIgnoringBlockTriggers() { return this.isPlaybackWorldIsolated() || super.isIgnoringBlockTriggers(); }"),
            "targeted actor can trigger pressure plates or tripwires");
        check(actor.contains(
                "public boolean shouldBeSaved() { return !this.isPlaybackWorldIsolated() && super.shouldBeSaved(); }"),
            "targeted actor can leak into the persistent world save");

        int tickStart = actor.indexOf("public void tick()");
        int pickupStart = actor.indexOf("/* Pickup items */", tickStart);
        int isolationGuard = actor.indexOf(
            "if (!this.level().isClientSide && this.isPlaybackWorldIsolated())",
            tickStart
        );
        int projectionTick = actor.indexOf("this.tickPlaybackProjectionServer();", isolationGuard);
        int isolationReturn = actor.indexOf("return;", projectionTick);
        int ordinaryTick = actor.indexOf("super.tick();", isolationReturn);

        check(tickStart >= 0
                && isolationGuard > tickStart
                && projectionTick > isolationGuard
                && isolationReturn > projectionTick
                && ordinaryTick > isolationReturn
                && pickupStart > ordinaryTick,
            "targeted actor can still consume shared-world item entities");

        int actionScope = actionPlayer.indexOf(
            "PlaybackActorActionContext.withActors(this.actors.values(),"
        );
        int applyActions = actionPlayer.indexOf("replay.applyActions(", actionScope);

        check(actionScope >= 0 && applyActions > actionScope,
            "recorded actions no longer run inside their ActionPlayer actor-identity scope");
        check(actorActionContext.contains("Collections.newSetFromMap(new IdentityHashMap<>())")
                && actorActionContext.contains("withIdentities(actors, runnable);")
                && actorActionContext.contains("return isScoped(actor) && ActionCommandContext.isAuthorizedFor(actor);")
                && actorActionContext.contains("finally"),
            "private actor action capability lost identity, live requester authority, or exception cleanup");
        check(attack.contains("entity.isAttackable()") && attack.contains("entity.hurt("),
            "explicit AttackActionClip no longer passes through the scoped actor attack/damage gate");
        check(damage.contains("actor.hurt("),
            "DamageActionClip no longer passes through the scoped actor damage gate");
        check(entityInteraction.contains("player.interactOn(entity, hand)")
                && entityInteraction.contains("CommonHooks.onInteractEntityAt(player, entity, location, hand)"),
            "EntityInteractionActionClip no longer executes its recorded entity interaction paths");
    }

    private static void targetedPlaybackRequiresDeliveryTarget() throws IOException
    {
        check(!ActionPlayer.hasRequiredDeliveryTarget(PlayerType.TARGETED_COMMAND, null),
            "targeted playback accepted a missing delivery target");
        check(ActionPlayer.hasRequiredDeliveryTarget(PlayerType.NORMAL, null)
                && ActionPlayer.hasRequiredDeliveryTarget(PlayerType.FILM_EDITOR, null)
                && ActionPlayer.hasRequiredDeliveryTarget(PlayerType.RECORDING, null),
            "non-targeted playback lost its existing nullable-player contract");

        Path root = findProjectRoot();
        String manager = compact(Files.readString(root.resolve(ACTION_MANAGER)));
        String player = compact(Files.readString(root.resolve(ACTION_PLAYER)));

        check(manager.contains("!ActionPlayer.hasRequiredDeliveryTarget(type, serverPlayer)"),
            "ActionManager.playAuthorized no longer rejects a missing targeted delivery target");
        check(player.contains("if (!hasRequiredDeliveryTarget(type, serverPlayer)) "
                + "{ throw new IllegalArgumentException(\"Targeted playback requires a delivery target\"); }"),
            "direct ActionPlayer construction can silently downgrade targeted playback to a public actor");
    }

    private static void playbackActorActionScopeRestoresAndUsesIdentity() throws Exception
    {
        EqualIdentity first = new EqualIdentity(1);
        EqualIdentity second = new EqualIdentity(2);

        check(first.equals(second), "identity-scope regression tokens no longer collide by value");
        check(!PlaybackActorActionContext.isScoped(first),
            "private actor action scope leaked into an unscoped thread");

        PlaybackActorActionContext.withIdentities(java.util.List.of(first), () ->
        {
            check(PlaybackActorActionContext.isScoped(first),
                "private actor owner identity was not installed");
            check(!PlaybackActorActionContext.isScoped(second),
                "equal but unowned actor identity inherited the capability");

            try
            {
                PlaybackActorActionContext.withIdentities(java.util.List.of(second), () ->
                {
                    check(!PlaybackActorActionContext.isScoped(first),
                        "nested actor scope retained its parent capability");
                    check(PlaybackActorActionContext.isScoped(second),
                        "nested actor scope did not install its own capability");

                    throw new ExpectedScopeFailure();
                });

                throw new AssertionError("throwing actor scope did not propagate its failure");
            }
            catch (ExpectedScopeFailure ignored)
            {}

            check(PlaybackActorActionContext.isScoped(first),
                "throwing nested actor scope did not restore its parent");
            check(!PlaybackActorActionContext.isScoped(second),
                "throwing nested actor scope leaked its identity");
        });

        check(!PlaybackActorActionContext.isScoped(first)
                && !PlaybackActorActionContext.isScoped(second),
            "private actor action scope leaked after completion");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstThread = playbackActorScopeThread(first, second, ready, release, failure);
        Thread secondThread = playbackActorScopeThread(second, first, ready, release, failure);

        firstThread.start();
        secondThread.start();

        boolean bothReady = ready.await(5L, TimeUnit.SECONDS);

        release.countDown();
        firstThread.join(5_000L);
        secondThread.join(5_000L);

        check(bothReady, "concurrent actor scopes did not become ready");
        check(!firstThread.isAlive() && !secondThread.isAlive(),
            "concurrent actor scope test did not terminate");

        if (failure.get() != null)
        {
            throw new AssertionError("private actor scopes leaked across playback threads", failure.get());
        }
    }

    private static void missingIssuerFailsClosedForEffectfulFilms()
    {
        Film visual = filmWith(new SwipeActionClip());
        Film effectful = filmWith(new CommandActionClip());

        check(FilmActionAuthorityPolicy.hasRequiredAuthority(visual, null),
            "console/null issuer could not start a purely visual targeted film");
        check(!FilmActionAuthorityPolicy.hasRequiredAuthority(effectful, null),
            "console/null issuer started an effectful targeted film");
        check(FilmActionAuthorityPolicy.canApplyFirstPersonState(
                true, PlayerType.TARGETED_COMMAND, true, true),
            "an authorized issuer could not apply targeted state to an ordinary target");
        check(!FilmActionAuthorityPolicy.canApplyFirstPersonState(
                true, PlayerType.TARGETED_COMMAND, true, false),
            "a missing or unauthorized issuer applied targeted first-person state");
    }

    private static void requesterScopeRestoresAfterFailureAndIsolatesThreads() throws Exception
    {
        ServerPlayer first = allocateWithoutConstructor(ServerPlayer.class);
        ServerPlayer second = allocateWithoutConstructor(ServerPlayer.class);

        check(ActionCommandContext.currentRequester() == null,
            "command requester leaked into an unscoped test thread");

        ActionCommandContext.withRequester(first, () ->
        {
            check(ActionCommandContext.currentRequester() == first,
                "outer command requester scope was not applied");

            try
            {
                ActionCommandContext.withRequester(second, () ->
                {
                    check(ActionCommandContext.currentRequester() == second,
                        "nested command requester scope reused its parent");

                    throw new ExpectedScopeFailure();
                });

                throw new AssertionError("throwing requester scope did not propagate its failure");
            }
            catch (ExpectedScopeFailure ignored)
            {}

            check(ActionCommandContext.currentRequester() == first,
                "throwing nested requester scope did not restore its parent");
        });

        check(ActionCommandContext.currentRequester() == null,
            "command requester scope leaked after completion");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstThread = requesterThread(first, ready, release, failure);
        Thread secondThread = requesterThread(second, ready, release, failure);

        firstThread.start();
        secondThread.start();

        boolean bothReady = ready.await(5L, TimeUnit.SECONDS);

        release.countDown();
        firstThread.join(5_000L);
        secondThread.join(5_000L);

        check(bothReady, "concurrent requester scopes did not become ready");
        check(!firstThread.isAlive() && !secondThread.isAlive(),
            "concurrent requester scope test did not terminate");

        if (failure.get() != null)
        {
            throw new AssertionError("concurrent requester scopes leaked across playback threads", failure.get());
        }
    }

    private static Thread requesterThread(
        ServerPlayer requester,
        CountDownLatch ready,
        CountDownLatch release,
        AtomicReference<Throwable> failure
    )
    {
        return new Thread(() ->
        {
            try
            {
                ActionCommandContext.withRequester(requester, () ->
                {
                    ready.countDown();

                    try
                    {
                        check(release.await(5L, TimeUnit.SECONDS),
                            "concurrent requester scope timed out");
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();

                        throw new AssertionError("concurrent requester scope was interrupted", e);
                    }

                    check(ActionCommandContext.currentRequester() == requester,
                        "concurrent requester scope observed another playback issuer");
                });
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        }, "bbs-requester-scope-test");
    }

    private static Thread playbackActorScopeThread(
        Object owned,
        Object foreign,
        CountDownLatch ready,
        CountDownLatch release,
        AtomicReference<Throwable> failure
    )
    {
        return new Thread(() ->
        {
            try
            {
                PlaybackActorActionContext.withIdentities(java.util.List.of(owned), () ->
                {
                    ready.countDown();

                    try
                    {
                        check(release.await(5L, TimeUnit.SECONDS),
                            "concurrent actor scope timed out");
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();

                        throw new AssertionError("concurrent actor scope was interrupted", e);
                    }

                    check(PlaybackActorActionContext.isScoped(owned),
                        "concurrent actor scope lost its owner");
                    check(!PlaybackActorActionContext.isScoped(foreign),
                        "concurrent actor scope observed another playback owner");
                });
            }
            catch (Throwable t)
            {
                failure.compareAndSet(null, t);
            }
        }, "bbs-playback-actor-scope-test");
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) throws Exception
    {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");

        field.setAccessible(true);

        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static Film filmWith(mchorse.bbs_mod.utils.clips.Clip clip)
    {
        Film film = new Film();
        Replay replay = film.replays.addReplay();

        replay.actions.addClip(clip);

        return film;
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(BBS_COMMANDS)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(BBS_COMMANDS)))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class ExpectedScopeFailure extends RuntimeException
    {}

    private record EqualIdentity(int id)
    {
        @Override
        public boolean equals(Object object)
        {
            return object instanceof EqualIdentity;
        }

        @Override
        public int hashCode()
        {
            return 0;
        }
    }
}
