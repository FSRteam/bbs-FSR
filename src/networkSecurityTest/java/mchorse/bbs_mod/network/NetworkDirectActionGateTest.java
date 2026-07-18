package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class NetworkDirectActionGateTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("NetworkDirectActionGateTest passed");
    }

    static void runAll()
    {
        testChannelsHaveIndependentCredit();
        testShareFormSustainedRate();
        testReplacementConnectionUsesIdentityScope();
        testRefillAndClockRollback();
        testExactDisconnectResetAndIdleCleanup();
        testConnectionCapacityIsBounded();
        testAnimationTriggerValidation();
        testInvalidArgumentsAndLimits();
        testServerWiringGuardsWorldActions();
    }

    private static void testChannelsHaveIndependentCredit()
    {
        AtomicLong now = new AtomicLong();
        NetworkDirectActionGate gate = gate(now, 2, 1, 3, 1, 1_000L, 10_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "the first teleport token was rejected");
        check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "the second teleport token was rejected");
        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "teleport exceeded its independent burst");

        for (int i = 0; i < 3; i++)
        {
            check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.ANIMATION_TRIGGER),
                "teleport debt leaked into the animation-trigger channel");
        }

        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.ANIMATION_TRIGGER),
            "animation trigger exceeded its independent burst");

        for (int i = 0; i < 8; i++)
        {
            check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.PAUSE_FILM),
                "teleport or animation debt leaked into the pause-film channel");
        }

        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.PAUSE_FILM),
            "pause film exceeded its independent 8-transition burst");

        for (int i = 0; i < 2; i++)
        {
            check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.FILM_START),
                "other direct-action debt leaked into the film-start channel");
            check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.RECORDING_START),
                "other direct-action debt leaked into the recording-start channel");
        }

        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.FILM_START),
            "film start exceeded its independent low-frequency burst");
        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.RECORDING_START),
            "recording start exceeded its independent low-frequency burst");

        for (int i = 0; i < 4; i++)
        {
            check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
                "other direct-action debt leaked into the share-form channel");
        }

        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
            "share form exceeded its independent low-frequency burst");
        check(gate.size() == 1 && gate.bucketCount() == 6,
            "one exact connection did not retain six independent channel buckets");
    }

    private static void testShareFormSustainedRate()
    {
        AtomicLong now = new AtomicLong();
        NetworkDirectActionGate gate = gate(now, 1, 1, 1, 1, 100L, 10_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        for (int i = 0; i < 4; i++)
        {
            check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
                "share-form burst rejected token " + i);
        }

        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
            "share-form burst admitted a fifth request");
        now.set(99L);
        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
            "share-form budget refilled before one full period");
        now.set(100L);
        check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
            "share-form budget did not refill one request at the boundary");
        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.SHARE_FORM),
            "share-form sustained rate exceeded one request per period");
    }

    private static void testReplacementConnectionUsesIdentityScope()
    {
        AtomicLong now = new AtomicLong();
        NetworkDirectActionGate gate = gate(now, 1, 1, 1, 1, 1_000L, 10_000L, 8);
        UUID owner = UUID.randomUUID();
        EqualConnection oldConnection = new EqualConnection("same-owner");
        EqualConnection replacementConnection = new EqualConnection("same-owner");

        check(oldConnection != replacementConnection && oldConnection.equals(replacementConnection),
            "the replacement fixture must be equal but non-identical");
        check(gate.tryAcquire(owner, oldConnection, NetworkDirectActionGate.Channel.TELEPORT),
            "the old connection could not spend its token");
        check(!gate.tryAcquire(owner, oldConnection, NetworkDirectActionGate.Channel.TELEPORT),
            "the old connection exceeded its token budget");
        check(gate.tryAcquire(owner, replacementConnection, NetworkDirectActionGate.Channel.TELEPORT),
            "the replacement connection inherited the old connection's debt");

        check(gate.clearConnection(owner, oldConnection) == 1,
            "old logout did not remove its exact bucket");
        check(gate.size() == 1, "old logout cleared the replacement connection");
        check(!gate.tryAcquire(owner, replacementConnection, NetworkDirectActionGate.Channel.TELEPORT),
            "old logout refilled the replacement connection's spent token");
    }

    private static void testRefillAndClockRollback()
    {
        AtomicLong now = new AtomicLong(1_000L);
        NetworkDirectActionGate gate = gate(now, 1, 1, 1, 1, 100L, 10_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "the initial token was rejected");

        now.set(1_099L);
        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "a fractional refill granted a complete token");

        now.set(900L);
        check(!gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "clock rollback granted fresh credit");

        now.set(1_100L);
        check(gate.tryAcquire(owner, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "the exact refill boundary did not restore one token");
    }

    private static void testExactDisconnectResetAndIdleCleanup()
    {
        AtomicLong now = new AtomicLong();
        NetworkDirectActionGate gate = gate(now, 2, 1, 2, 1, 100L, 500L, 8);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();

        gate.tryAcquire(alice, aliceConnection, NetworkDirectActionGate.Channel.TELEPORT);
        gate.tryAcquire(alice, aliceConnection, NetworkDirectActionGate.Channel.ANIMATION_TRIGGER);
        gate.tryAcquire(bob, bobConnection, NetworkDirectActionGate.Channel.TELEPORT);

        check(gate.clearConnection(alice, new Object()) == 0,
            "an equal owner with a foreign connection cleared Alice's state");
        check(gate.clearConnection(alice, aliceConnection) == 2,
            "exact disconnect did not report both channel buckets");
        check(gate.size() == 1, "exact disconnect removed another connection");

        now.set(499L);
        check(gate.expireIdle() == 0, "idle cleanup ran before the exact boundary");
        now.set(500L);
        check(gate.expireIdle() == 1 && gate.size() == 0,
            "idle cleanup did not release the remaining connection at the boundary");

        gate.tryAcquire(bob, bobConnection, NetworkDirectActionGate.Channel.TELEPORT);
        gate.reset();
        check(gate.size() == 0 && gate.bucketCount() == 0,
            "reset retained direct-action state");
    }

    private static void testConnectionCapacityIsBounded()
    {
        AtomicLong now = new AtomicLong();
        NetworkDirectActionGate gate = gate(now, 1, 1, 1, 1, 100L, 500L, 2);

        check(gate.tryAcquire(UUID.randomUUID(), new Object(), NetworkDirectActionGate.Channel.TELEPORT),
            "the first connection was rejected");
        check(gate.tryAcquire(UUID.randomUUID(), new Object(), NetworkDirectActionGate.Channel.TELEPORT),
            "the second connection was rejected");
        check(!gate.tryAcquire(UUID.randomUUID(), new Object(), NetworkDirectActionGate.Channel.TELEPORT),
            "the global connection-state bound was bypassed");

        now.set(500L);
        check(gate.tryAcquire(UUID.randomUUID(), new Object(), NetworkDirectActionGate.Channel.TELEPORT),
            "an idle slot was not reclaimed before capacity admission");
        check(gate.size() == 1,
            "capacity admission did not expire every idle connection at the boundary");
    }

    private static void testAnimationTriggerValidation()
    {
        String maximum = "a".repeat(NetworkDirectActionGate.MAX_ANIMATION_TRIGGER_LENGTH);
        String chineseMaximum = "\u4e2d".repeat(85) + "a";
        String emojiMaximum = "\ud83d\ude00".repeat(64);

        check(NetworkDirectActionGate.isAnimationTriggerAllowed(""),
            "the compatibility-preserving empty state id was rejected");
        check(NetworkDirectActionGate.isAnimationTriggerAllowed("idle/state-1"),
            "an ordinary animation state id was rejected");
        check(NetworkDirectActionGate.isAnimationTriggerAllowed(maximum),
            "the exact animation trigger length boundary was rejected");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed(maximum + "a"),
            "an oversized animation trigger was accepted");
        check(NetworkDirectActionGate.isAnimationTriggerAllowed(chineseMaximum),
            "an exact 256-byte Chinese animation trigger was rejected");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed(chineseMaximum + "b"),
            "a 257-byte Chinese animation trigger was accepted");
        check(NetworkDirectActionGate.isAnimationTriggerAllowed(emojiMaximum),
            "an exact 256-byte emoji animation trigger was rejected");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed(emojiMaximum + "a"),
            "a 257-byte emoji animation trigger was accepted");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed(null),
            "a null animation trigger was accepted");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed("bad\0state"),
            "a NUL-bearing animation trigger was accepted");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed("bad\rstate"),
            "a CR-bearing animation trigger was accepted");
        check(!NetworkDirectActionGate.isAnimationTriggerAllowed("bad\nstate"),
            "an LF-bearing animation trigger was accepted");
    }

    private static void testInvalidArgumentsAndLimits()
    {
        AtomicLong now = new AtomicLong();
        NetworkDirectActionGate gate = gate(now, 1, 1, 1, 1, 100L, 500L, 2);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(!gate.tryAcquire(null, connection, NetworkDirectActionGate.Channel.TELEPORT),
            "a missing owner created a bucket");
        check(!gate.tryAcquire(owner, null, NetworkDirectActionGate.Channel.TELEPORT),
            "a missing connection created a bucket");
        check(!gate.tryAcquire(owner, connection, null),
            "a missing channel created a bucket");
        check(gate.clearConnection(null, connection) == 0
                && gate.clearConnection(owner, null) == 0,
            "invalid exact cleanup removed state");

        expectInvalid(() -> gate(now, 0, 1, 1, 1, 100L, 500L, 2),
            "zero channel capacity was accepted");
        expectInvalid(() -> gate(now, 1, 0, 1, 1, 100L, 500L, 2),
            "zero channel refill was accepted");
        expectInvalid(() -> gate(now, 1, 1, 1, 1, 0L, 500L, 2),
            "zero refill period was accepted");
        expectInvalid(() -> gate(now, 1, 1, 1, 1, 100L, 0L, 2),
            "zero idle timeout was accepted");
        expectInvalid(() -> gate(now, 1, 1, 1, 1, 100L, 500L, 0),
            "zero connection bound was accepted");
    }

    private static void testServerWiringGuardsWorldActions()
    {
        String source;

        try
        {
            source = Files.readString(Path.of("src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"))
                .replaceAll("\\s+", " ");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not read ServerNetwork direct-action wiring", e);
        }

        check(source.contains("private static final NetworkDirectActionGate directActionGate = new NetworkDirectActionGate()"),
            "ServerNetwork has no shared direct-action gate");
        check(source.contains("directActionGate.reset()")
                && source.contains("directActionGate.expireIdle()")
                && source.contains("directActionGate.clearConnection(playerId, player)"),
            "direct-action budgets are not wired into reset, idle expiry, and exact logout cleanup");
        assertOrdered(source,
            "private static void handleTeleportPlayer",
            "if (!isCurrentConnection(server, player)",
            "NetworkDirectActionGate.Channel.TELEPORT",
            "NetworkMutationPolicy.isTeleportAllowed(x, y, z, yaw, bodyYaw, pitch)",
            "level.getWorldBorder().isWithinBounds(pos)",
            "level.isOutsideBuildHeight(pos)",
            "Level.isInSpawnableBounds(pos)",
            "player.teleportTo(x, y, z)");
        assertOrdered(source,
            "private static void handleAnimationStateTriggerPacket",
            "buf.readUtf(NetworkDirectActionGate.MAX_ANIMATION_TRIGGER_LENGTH)",
            "NetworkDirectActionGate.isAnimationTriggerAllowed(string)",
            "server.execute(",
            "!isCurrentConnection(server, player)",
            "!PermissionUtils.arePanelsAllowed(server, player)",
            "NetworkDirectActionGate.Channel.ANIMATION_TRIGGER",
            "NetworkCompat.sendToPlayersTrackingEntity",
            "morph.getForm().playState(string)");
        assertOrdered(source,
            "private static void handleSharedFormPacket",
            "server.execute(",
            "!isCurrentConnection(server, player)",
            "ServerPlayer otherPlayer = server.getPlayerList().getPlayer(playerUuid)",
            "otherPlayer.serverLevel() == player.serverLevel()",
            "player.distanceToSqr(otherPlayer) <= MAX_SHARED_FORM_DISTANCE_SQR",
            "PermissionUtils.arePanelsAllowed(server, otherPlayer)",
            "NetworkDirectActionGate.Channel.SHARE_FORM",
            "sendSharedForm(otherPlayer, data)");
    }

    private static NetworkDirectActionGate gate(
        AtomicLong now,
        long teleportCapacity,
        long teleportRefill,
        long triggerCapacity,
        long triggerRefill,
        long refillPeriod,
        long idleTimeout,
        int maxConnections
    )
    {
        return new NetworkDirectActionGate(
            now::get,
            new NetworkDirectActionGate.Limits(
                new NetworkDirectActionGate.ChannelLimit(teleportCapacity, teleportRefill),
                new NetworkDirectActionGate.ChannelLimit(triggerCapacity, triggerRefill),
                refillPeriod,
                idleTimeout,
                maxConnections
            )
        );
    }

    private static void expectInvalid(Runnable runnable, String message)
    {
        boolean rejected = false;

        try
        {
            runnable.run();
        }
        catch (IllegalArgumentException exception)
        {
            rejected = true;
        }

        check(rejected, message);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int index = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, index + 1);

            check(next >= 0, "missing ServerNetwork marker: " + marker);
            check(next > index, "ServerNetwork marker is out of order: " + marker);
            index = next;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private record EqualConnection(String id)
    {}
}
