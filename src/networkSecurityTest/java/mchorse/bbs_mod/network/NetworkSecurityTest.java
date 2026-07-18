package mchorse.bbs_mod.network;

import mchorse.bbs_mod.actions.FilmPlayerSettingsPolicyTest;
import mchorse.bbs_mod.items.GunPropertiesPolicyTest;
import mchorse.bbs_mod.items.GunProjectileBudgetTest;
import mchorse.bbs_mod.items.GunRuntimeWiringTest;
import mchorse.bbs_mod.actions.FilmPlaybackPolicyTest;
import mchorse.bbs_mod.actions.FilmActionAuthorityPolicyTest;
import mchorse.bbs_mod.actions.FilmRawPreflightTest;
import mchorse.bbs_mod.actions.ActionRuntimeAuthorityWiringTest;
import mchorse.bbs_mod.actions.ActionManagerCompatibilityTest;
import mchorse.bbs_mod.actions.ActionRetirementQueueTest;
import mchorse.bbs_mod.actions.FirstPersonStateLeaseRegistryTest;
import mchorse.bbs_mod.actions.FirstPersonInventoryProjectionTest;
import mchorse.bbs_mod.network.compat.AddonBrokerServerBudgetTest;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class NetworkSecurityTest
{
    public static void main(String[] args)
    {
        bootstrapStandaloneMinecraftRuntime();

        PacketCrusherTest.runAll();
        PacketCrusherLegacyAbiSourceTest.runAll();
        NetworkDataDecoderTest.runAll();
        NetworkFilmKeyTest.runAll();
        ServerFilmRuntimeAuthoritySourceTest.runAll();
        ServerModelBlockMutationSourceTest.runAll();
        ServerPlayerFormFactoryGateSourceTest.runAll();
        ServerTypedFactoryFailureSourceTest.runAll();
        ServerSharedFormAuthoritySourceTest.runAll();
        ClientTransportIdentitySourceTest.runAll();
        NetworkSeekBudgetTest.runAll();
        NetworkZoomSessionsTest.runAll();
        NetworkDirectActionGateTest.runAll();
        AddonBrokerServerBudgetTest.runAll();
        FilmPlayerSettingsPolicyTest.runAll();
        FilmActionAuthorityPolicyTest.runAll();
        FilmRawPreflightTest.runAll();
        ActionRuntimeAuthorityWiringTest.runAll();
        ActionManagerCompatibilityTest.runAll();
        ActionRetirementQueueTest.runAll();
        FirstPersonStateLeaseRegistryTest.runAll();
        FirstPersonInventoryProjectionTest.runAll();
        FilmPlaybackPolicyTest.runAll();
        GunPropertiesPolicyTest.runAll();
        GunProjectileBudgetTest.runAll();
        GunRuntimeWiringTest.runAll();
        testMutationSessionOwnershipAndExpiry();
        testMutationSessionConnectionIsolation();
        NetworkMutationSessionsTest.runAll();
        testCallbackResetDoesNotReuseQueuedIds();
        testConnectionGenerationRejectsQueuedOldWork();
        NetworkConnectionGateScopeTest.runAll();
        testMutationNumericBudgets();

        System.out.println("NetworkSecurityTest passed");
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

    private static void testMutationSessionOwnershipAndExpiry()
    {
        AtomicLong now = new AtomicLong(100L);
        NetworkMutationSessions sessions = new NetworkMutationSessions(now::get);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();
        long blockA = 11L;
        long blockB = 12L;

        check(!sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA), "an interaction must establish the model-block session");

        sessions.openModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA);

        check(sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA), "the opening interaction must authorize its owner, dimension, and block");
        check(!sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockB), "a lease for block A must not authorize a forged save for nearby block B");
        check(!sessions.hasModelBlockSession(bob, bobConnection, "minecraft:overworld", blockA), "another player must not inherit the session");
        check(!sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:the_nether", blockA), "the same coordinates in another dimension must invalidate the session");
        check(!sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA), "returning to the old dimension must not revive an invalidated session");

        sessions.openModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA);

        now.addAndGet(NetworkMutationSessions.MODEL_BLOCK_IDLE_TIMEOUT_NANOS - 1L);
        check(sessions.refreshModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA), "valid activity must refresh the idle lease");

        now.addAndGet(NetworkMutationSessions.MODEL_BLOCK_IDLE_TIMEOUT_NANOS);
        check(!sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA), "an idle session must expire at the configured boundary");

        sessions.openModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA);
        sessions.clearOwner(alice, aliceConnection);
        check(!sessions.hasModelBlockSession(alice, aliceConnection, "minecraft:overworld", blockA), "disconnect must clear the exact model-block lease");

        sessions.claimFilm("shots/intro", alice, aliceConnection, "minecraft:overworld");
        sessions.claimFilm("shots/bob", bob, bobConnection, "minecraft:the_nether");

        check(sessions.ownsFilm("shots/intro", alice, aliceConnection, "minecraft:overworld"), "the film starter must own its mutation session");
        check(!sessions.claimFilm("shots/intro", bob, bobConnection, "minecraft:overworld"), "a second player replaced the active film owner");
        check(!sessions.ownsFilm("shots/intro", bob, bobConnection, "minecraft:overworld"), "another player must not control the film");
        check(!sessions.ownsFilm("shots/intro", alice, aliceConnection, "minecraft:the_nether"), "film control must remain in its starting dimension");

        check(sessions.claimRecording("shots/intro", alice, aliceConnection, "minecraft:overworld", 2, 40), "the film owner could not establish its recording session");
        check(!sessions.claimRecording("shots/intro", alice, aliceConnection, "minecraft:overworld", 3, 80), "a duplicate recording session replaced the first request metadata");
        check(!sessions.claimRecording("shots/intro", bob, bobConnection, "minecraft:overworld", 2, 40), "a non-owner established a recording session");

        NetworkMutationSessions.RecordingSession recording = sessions.getRecording(alice, aliceConnection);

        check(recording != null && recording.filmId().equals("shots/intro") && recording.replayId() == 2 && recording.tick() == 40,
            "recording session did not preserve server-validated response metadata");

        sessions.claimFilm("shots/finished", alice, aliceConnection, "minecraft:overworld");
        check(!sessions.claimFilm("shots/finished", bob, bobConnection, "minecraft:overworld"), "an active film session was stolen");
        sessions.releaseFilm("shots/finished", alice, aliceConnection);
        check(sessions.claimFilm("shots/finished", bob, bobConnection, "minecraft:overworld"), "a naturally completed film session could not be claimed by the next player");

        List<String> released = sessions.clearOwner(alice, aliceConnection);

        check(released.equals(List.of("shots/intro")), "disconnect must identify the owner's running films for teardown");
        check(!sessions.ownsFilm("shots/intro", alice, aliceConnection, "minecraft:overworld"), "disconnect must release film ownership");
        check(sessions.getRecording(alice, aliceConnection) == null, "disconnect must release the owner's recording session");
        check(sessions.ownsFilm("shots/bob", bob, bobConnection, "minecraft:the_nether"), "disconnect must not affect another owner");
    }

    private static void testCallbackResetDoesNotReuseQueuedIds()
    {
        ManagerCallbackRegistry callbacks = new ManagerCallbackRegistry();
        int oldId = callbacks.register((data) -> {});

        check(callbacks.size() == 1, "registration must retain the callback");

        callbacks.reset();

        check(callbacks.size() == 0, "disconnect reset must clear pending callbacks");

        int newId = callbacks.register((data) -> {});

        check(newId != oldId, "a reconnect must not immediately reuse an id held by queued old-connection work");
        check(callbacks.remove(oldId) == null, "an old response must not consume the new callback");
        check(callbacks.size() == 1, "the new callback must survive an old response");
        check(callbacks.remove(newId) != null, "the current response must resolve the current callback");
        check(callbacks.register(null) == -1, "fire-and-forget requests must not retain a null callback");
    }

    private static void testMutationSessionConnectionIsolation()
    {
        NetworkMutationSessions sessions = new NetworkMutationSessions();
        UUID owner = UUID.randomUUID();
        Object oldConnection = new Object();
        Object newConnection = new Object();
        String dimension = "minecraft:overworld";

        sessions.openModelBlockSession(owner, oldConnection, dimension, 10L);
        sessions.openModelBlockSession(owner, newConnection, dimension, 20L);
        sessions.claimFilm("shots/old", owner, oldConnection, dimension);

        check(!sessions.hasModelBlockSession(owner, oldConnection, dimension, 20L),
            "a replaced connection inherited the new model-block lease");
        check(sessions.hasModelBlockSession(owner, newConnection, dimension, 20L),
            "the current connection lost its model-block lease");

        sessions.clearOwner(owner, oldConnection);

        check(sessions.hasModelBlockSession(owner, newConnection, dimension, 20L),
            "queued old-connection cleanup cleared the current model-block lease");
        check(!sessions.hasFilm("shots/old"),
            "old-connection cleanup retained its own film lease");

        check(sessions.claimFilm("shots/shared", owner, newConnection, dimension),
            "the current connection could not claim a released film");
        check(sessions.claimRecording("shots/shared", owner, newConnection, dimension, 1, 5),
            "the current connection could not claim recording ownership");

        sessions.releaseFilm("shots/shared", owner, oldConnection);
        sessions.releaseRecording(owner, oldConnection);
        sessions.clearOwner(owner, oldConnection);

        check(sessions.ownsFilm("shots/shared", owner, newConnection, dimension),
            "a delayed old runtime release cleared the current film lease");
        check(sessions.getRecording(owner, newConnection) != null,
            "a delayed old runtime release cleared the current recording lease");
        check(sessions.getRecording(owner, oldConnection) == null,
            "the old connection read the current recording lease");
    }

    private static void testConnectionGenerationRejectsQueuedOldWork()
    {
        NetworkConnectionGate gate = new NetworkConnectionGate();
        UUID first = gate.snapshot();
        int[] mutations = new int[1];
        Runnable queuedOldConnectionWork = () ->
        {
            if (gate.isCurrent(first))
            {
                mutations[0] += 1;
            }
        };

        UUID rotated = gate.rotate();

        check(rotated.equals(first), "rotation did not atomically return the retired generation");
        queuedOldConnectionWork.run();

        check(mutations[0] == 0, "queued old-connection work passed the reconnect generation fence");

        UUID second = gate.snapshot();

        if (gate.isCurrent(second))
        {
            mutations[0] += 1;
        }

        check(mutations[0] == 1, "the current connection generation was incorrectly rejected");
        check(!gate.isCurrent(first), "the previous generation became current again");
    }

    private static void testMutationNumericBudgets()
    {
        check(NetworkMutationPolicy.isFilmTickAllowed(100, 200, 1_000, false), "a bounded in-range seek was rejected");
        check(!NetworkMutationPolicy.isFilmTickAllowed(0, Integer.MAX_VALUE, Integer.MAX_VALUE, false), "an in-duration INT_MAX seek bypassed the absolute work budget");
        check(!NetworkMutationPolicy.isFilmTickAllowed(0, Integer.MIN_VALUE, Integer.MAX_VALUE, false), "a negative extreme seek was accepted");
        check(!NetworkMutationPolicy.isFilmTickAllowed(0, NetworkMutationPolicy.MAX_FILM_SEEK_STEPS + 1, Integer.MAX_VALUE, true), "restart bypassed the absolute seek budget");
        check(!NetworkMutationPolicy.isFilmTickAllowed(0, 101, 100, false), "a seek beyond film duration was accepted");

        check(NetworkMutationPolicy.isRecordingStartAllowed(0, 100, 30, 1, 200), "valid recording start parameters were rejected");
        check(!NetworkMutationPolicy.isRecordingStartAllowed(-1, 100, 30, 1, 200), "negative replay index was accepted");
        check(!NetworkMutationPolicy.isRecordingStartAllowed(1, 100, 30, 1, 200), "out-of-range replay index was accepted");
        check(!NetworkMutationPolicy.isRecordingStartAllowed(0, -1, 30, 1, 200), "negative recording tick was accepted");
        check(!NetworkMutationPolicy.isRecordingStartAllowed(0, 201, 30, 1, 200), "recording tick beyond film duration was accepted");
        check(!NetworkMutationPolicy.isRecordingStartAllowed(0, 100, -1, 1, 200), "negative recording countdown was accepted");
        check(!NetworkMutationPolicy.isRecordingStartAllowed(0, 100, NetworkMutationPolicy.MAX_RECORDING_COUNTDOWN_TICKS + 1, 1, 200), "oversized recording countdown was accepted");

        check(NetworkMutationPolicy.isTeleportAllowed(1D, 64D, -1D, 180F, -180F, 90F), "valid teleport parameters were rejected");
        check(!NetworkMutationPolicy.isTeleportAllowed(Double.MAX_VALUE, 64D, 0D, 0F, 0F, 0F), "finite out-of-world X was accepted");
        check(!NetworkMutationPolicy.isTeleportAllowed(0D, NetworkMutationPolicy.MAX_VERTICAL_POSITION, 0D, 0F, 0F, 0F), "exclusive vertical world boundary was accepted");
        check(!NetworkMutationPolicy.isTeleportAllowed(0D, 64D, NetworkMutationPolicy.MAX_HORIZONTAL_POSITION, 0F, 0F, 0F), "exclusive horizontal world boundary was accepted");
        check(!NetworkMutationPolicy.isTeleportAllowed(0D, 64D, 0D, 0F, 0F, 90.1F), "out-of-range pitch was accepted");
        check(!NetworkMutationPolicy.isTeleportAllowed(0D, 64D, 0D, Float.NaN, 0F, 0F), "non-finite yaw was accepted");

        check(NetworkMutationPolicy.arePlayerSettingsAllowed(20F, 20F, 20F, 30, 0.5F), "valid film player settings were rejected");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(Float.NaN, 20F, 20F, 30, 0.5F), "NaN health was accepted");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(Float.POSITIVE_INFINITY, 20F, 20F, 30, 0.5F), "infinite health was accepted");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(21F, 20F, 20F, 30, 0.5F), "health above max was accepted");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(20F, 20F, -1F, 30, 0.5F), "negative hunger was accepted");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(20F, 20F, 20F, -1, 0.5F), "negative XP level was accepted");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(20F, 20F, 20F, 30, Float.NaN), "NaN XP progress was accepted");
        check(!NetworkMutationPolicy.arePlayerSettingsAllowed(20F, 20F, 20F, 30, 1.1F), "XP progress above one was accepted");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
