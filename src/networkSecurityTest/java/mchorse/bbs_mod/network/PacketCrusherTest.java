package mchorse.bbs_mod.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public class PacketCrusherTest
{
    private static final ResourceLocation CHANNEL_A = ResourceLocation.fromNamespaceAndPath("bbs", "crusher_test_a");
    private static final ResourceLocation CHANNEL_B = ResourceLocation.fromNamespaceAndPath("bbs", "crusher_test_b");
    private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final IBufferReceiver NOOP = (bytes, buf) -> {};

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        testLegacyPublicAbiFailsClosed();
        testOwnerAndChannelScopeTransferIdentity();
        testConnectionIdentityScopes();
        testConnectionCleanupIsolation();
        testPlayerReplacementCleanupReleasesOnlyRetiredCapacity();
        testLegacyReceiveKeepsUuidValueScope();
        testOwnerAndGlobalTransferLimits();
        testOwnerAndGlobalByteLimits();
        testIdleExpiryAndIdReuse();
        testMalformedWarningRateLimit();
        testMalformedFramesReleaseState();
        testClearOwnerAndResetAreIdempotent();
        testCompletionCleanupPrecedesReceiver();
        testEmptyPayloadSentinel();
        testExactTransferBoundaryAndSendRejection();
        testSendChunkCountAtExactBoundary();
    }

    private static void testLegacyPublicAbiFailsClosed()
    {
        try
        {
            Constructor<PacketCrusher> constructor = PacketCrusher.class.getConstructor();
            Method receive = PacketCrusher.class.getMethod("receive", FriendlyByteBuf.class, IBufferReceiver.class);

            check(Modifier.isPublic(constructor.getModifiers()), "PacketCrusher public no-arg constructor ABI was removed");
            check(Modifier.isPublic(receive.getModifiers()) && receive.getReturnType() == void.class,
                "PacketCrusher legacy receive ABI was removed or changed");
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("PacketCrusher legacy public ABI is not reflectively available", e);
        }

        TestCrusher crusher = crusher(new MutableClock(), 1, 1, PacketCrusher.BUFFER_SIZE, PacketCrusher.BUFFER_SIZE, 100L);
        FriendlyByteBuf buf = frame(73, 0, 1, new byte[]{0x73});
        AtomicInteger completions = new AtomicInteger();
        int readerIndex = buf.readerIndex();

        try
        {
            crusher.receive(buf, (bytes, packetBuf) -> completions.incrementAndGet());

            check(completions.get() == 0, "legacy unscoped receive delivered a payload");
            check(buf.readerIndex() == readerIndex, "legacy unscoped receive consumed attacker-controlled bytes");
            check(crusher.clearOwner(OWNER_A) == 0, "legacy unscoped receive retained transfer state");
        }
        finally
        {
            buf.release();
        }
    }

    private static void testOwnerAndChannelScopeTransferIdentity()
    {
        TestCrusher crusher = crusher(new MutableClock(), 8, 8, 8L * PacketCrusher.BUFFER_SIZE, 8L * PacketCrusher.BUFFER_SIZE, 100L);
        byte[] a = filledChunk((byte) 0x1a);
        byte[] b = filledChunk((byte) 0x2b);
        byte[] c = filledChunk((byte) 0x3c);
        byte[][] results = new byte[3][];

        deliver(crusher, OWNER_A, CHANNEL_A, frame(7, 0, 2, a), NOOP);
        deliver(crusher, OWNER_B, CHANNEL_A, frame(7, 0, 2, b), NOOP);
        deliver(crusher, OWNER_A, CHANNEL_B, frame(7, 0, 2, c), NOOP);

        deliver(crusher, OWNER_B, CHANNEL_A, frameWithSuffix(7, 1, 2, new byte[]{0x22}, 22), (bytes, buf) ->
        {
            results[1] = bytes;
            check(buf.readInt() == 22, "final-chunk suffix must remain readable by the logical packet handler");
        });
        deliver(crusher, OWNER_A, CHANNEL_B, frameWithSuffix(7, 1, 2, new byte[]{0x33}, 33), (bytes, buf) ->
        {
            results[2] = bytes;
            check(buf.readInt() == 33, "channel B suffix was not preserved");
        });
        deliver(crusher, OWNER_A, CHANNEL_A, frameWithSuffix(7, 1, 2, new byte[]{0x11}, 11), (bytes, buf) ->
        {
            results[0] = bytes;
            check(buf.readInt() == 11, "channel A suffix was not preserved");
        });

        check(Arrays.equals(results[0], append(a, (byte) 0x11)), "owner A/channel A transfer was contaminated");
        check(Arrays.equals(results[1], append(b, (byte) 0x22)), "owner B/channel A transfer was contaminated");
        check(Arrays.equals(results[2], append(c, (byte) 0x33)), "owner A/channel B transfer was contaminated");
    }

    private static void testConnectionIdentityScopes()
    {
        TestCrusher crusher = crusher(
            new MutableClock(),
            2,
            2,
            2L * (PacketCrusher.BUFFER_SIZE + 1L),
            2L * (PacketCrusher.BUFFER_SIZE + 1L),
            100L
        );
        Object oldConnection = new String("equal-connection-value");
        Object newConnection = new String("equal-connection-value");
        byte[] oldChunk = filledChunk((byte) 0x41);
        byte[] newChunk = filledChunk((byte) 0x52);
        byte[][] results = new byte[2][];

        check(oldConnection != newConnection && oldConnection.equals(newConnection),
            "connection scope test did not use distinct identities with equal values");
        deliver(crusher, OWNER_A, oldConnection, CHANNEL_A, frame(70, 0, 2, oldChunk), NOOP);
        deliver(crusher, OWNER_A, newConnection, CHANNEL_A, frame(70, 0, 2, newChunk), NOOP);
        deliver(crusher, OWNER_A, newConnection, CHANNEL_A, frame(70, 1, 2, new byte[]{0x53}),
            (bytes, buf) -> results[1] = bytes);
        deliver(crusher, OWNER_A, oldConnection, CHANNEL_A, frame(70, 1, 2, new byte[]{0x42}),
            (bytes, buf) -> results[0] = bytes);

        check(Arrays.equals(results[0], append(oldChunk, (byte) 0x42)), "old connection transfer was contaminated by its replacement");
        check(Arrays.equals(results[1], append(newChunk, (byte) 0x53)), "replacement connection transfer was contaminated by the old connection");
    }

    private static void testConnectionCleanupIsolation()
    {
        TestCrusher crusher = crusher(new MutableClock(), 2, 2, 2L * PacketCrusher.BUFFER_SIZE, 2L * PacketCrusher.BUFFER_SIZE, 100L);
        Object oldConnection = new Object();
        Object newConnection = new Object();
        AtomicInteger oldCompletions = new AtomicInteger();
        AtomicInteger newCompletions = new AtomicInteger();

        deliver(crusher, OWNER_A, oldConnection, CHANNEL_A, frame(71, 0, 2, filledChunk((byte) 0x61)), NOOP);
        deliver(crusher, OWNER_A, newConnection, CHANNEL_A, frame(71, 0, 2, filledChunk((byte) 0x72)), NOOP);

        check(crusher.clearConnection(OWNER_A, oldConnection) == 1, "old connection cleanup did not remove its transfer");
        check(crusher.clearConnection(OWNER_A, oldConnection) == 0, "old connection cleanup was not idempotent");
        check(crusher.clearConnection(OWNER_A, null) == 0, "null connection cleanup should be a no-op");

        deliver(crusher, OWNER_A, oldConnection, CHANNEL_A, frame(71, 1, 2, new byte[]{0x62}),
            (bytes, buf) -> oldCompletions.incrementAndGet());
        deliver(crusher, OWNER_A, newConnection, CHANNEL_A, frame(71, 1, 2, new byte[]{0x73}),
            (bytes, buf) -> newCompletions.incrementAndGet());

        check(oldCompletions.get() == 0, "a retired connection completed after connection-scoped cleanup");
        check(newCompletions.get() == 1, "old connection cleanup removed the replacement connection transfer");
    }

    private static void testPlayerReplacementCleanupReleasesOnlyRetiredCapacity()
    {
        NetworkConnectionGate gate = new NetworkConnectionGate();
        Object transport = new Object();
        Object oldPlayer = new Object();
        Object newPlayer = new Object();
        NetworkConnectionGate.Scope oldScope = gate.capture(transport, oldPlayer, transport, oldPlayer);
        TestCrusher crusher = crusher(
            new MutableClock(),
            1,
            1,
            PacketCrusher.BUFFER_SIZE + 1L,
            PacketCrusher.BUFFER_SIZE + 1L,
            100L
        );
        AtomicInteger oldCompletions = new AtomicInteger();
        AtomicInteger newCompletions = new AtomicInteger();

        check(oldScope != null, "replacement cleanup test could not bind its old player scope");

        deliver(
            crusher,
            oldScope.generation(),
            oldScope.transferIdentity(),
            CHANNEL_A,
            frame(74, 0, 2, filledChunk((byte) 0x41)),
            NOOP
        );

        NetworkConnectionGate.Scope replacement = gate.replacePlayer(
            transport,
            oldPlayer,
            newPlayer,
            transport,
            newPlayer
        );

        check(replacement != null && replacement.playerReplaced(),
            "replacement cleanup test did not rotate the player scope");
        check(replacement.retiredTransferIdentity() == oldScope.transferIdentity(),
            "replacement cleanup did not return the exact retired transfer token");
        check(crusher.clearConnection(replacement.generation(), replacement.retiredTransferIdentity()) == 1,
            "replacement cleanup did not immediately release retired transfer capacity");

        /* A callback that captured the old scope before replacement can reach
         * PacketCrusher after cleanup. Its retired token must not recreate a
         * transfer or consume the only owner slot. */
        deliver(
            crusher,
            oldScope.generation(),
            oldScope.transferIdentity(),
            CHANNEL_A,
            frame(75, 0, 2, filledChunk((byte) 0x43)),
            (bytes, buf) -> oldCompletions.incrementAndGet()
        );
        deliver(
            crusher,
            replacement.generation(),
            replacement.transferIdentity(),
            CHANNEL_A,
            frame(74, 0, 2, filledChunk((byte) 0x52)),
            NOOP
        );
        deliver(
            crusher,
            oldScope.generation(),
            oldScope.transferIdentity(),
            CHANNEL_A,
            frame(74, 1, 2, new byte[]{0x42}),
            (bytes, buf) -> oldCompletions.incrementAndGet()
        );
        deliver(
            crusher,
            replacement.generation(),
            replacement.transferIdentity(),
            CHANNEL_A,
            frame(74, 1, 2, new byte[]{0x53}),
            (bytes, buf) -> newCompletions.incrementAndGet()
        );

        check(oldCompletions.get() == 0, "retired transfer completed after player replacement cleanup");
        check(newCompletions.get() == 1, "retired transfer cleanup removed or contaminated the new transfer");
    }

    private static void testLegacyReceiveKeepsUuidValueScope()
    {
        TestCrusher crusher = crusher(new MutableClock(), 1, 1, PacketCrusher.BUFFER_SIZE + 1L, PacketCrusher.BUFFER_SIZE + 1L, 100L);
        UUID equalOwner = UUID.fromString(OWNER_A.toString());
        byte[] first = filledChunk((byte) 0x21);
        byte[][] result = new byte[1][];

        deliver(crusher, OWNER_A, CHANNEL_A, frame(72, 0, 2, first), NOOP);
        deliver(crusher, equalOwner, CHANNEL_A, frame(72, 1, 2, new byte[]{0x22}), (bytes, buf) -> result[0] = bytes);

        check(OWNER_A != equalOwner && OWNER_A.equals(equalOwner), "legacy UUID value-scope test did not use a distinct equal instance");
        check(Arrays.equals(result[0], append(first, (byte) 0x22)), "legacy receive stopped using UUID value semantics");
    }

    private static void testOwnerAndGlobalTransferLimits()
    {
        TestCrusher ownerLimited = crusher(new MutableClock(), 1, 2, 2L * PacketCrusher.BUFFER_SIZE, 4L * PacketCrusher.BUFFER_SIZE, 100L);
        AtomicInteger completions = new AtomicInteger();

        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(1, 0, 2, filledChunk((byte) 1)), NOOP);
        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(2, 0, 2, filledChunk((byte) 2)), NOOP);
        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(2, 1, 2, new byte[]{2}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 0, "per-owner transfer limit admitted a second in-flight transfer");

        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(1, 1, 2, new byte[]{1}), (bytes, buf) -> completions.incrementAndGet());
        complete(ownerLimited, OWNER_A, CHANNEL_A, 2, (byte) 2, completions);
        check(completions.get() == 2, "completed transfer did not release the per-owner slot");

        TestCrusher globallyLimited = crusher(new MutableClock(), 1, 1, 2L * PacketCrusher.BUFFER_SIZE, 2L * PacketCrusher.BUFFER_SIZE, 100L);
        completions.set(0);
        deliver(globallyLimited, OWNER_A, CHANNEL_A, frame(3, 0, 2, filledChunk((byte) 3)), NOOP);
        deliver(globallyLimited, OWNER_B, CHANNEL_A, frame(3, 0, 2, filledChunk((byte) 4)), NOOP);
        deliver(globallyLimited, OWNER_B, CHANNEL_A, frame(3, 1, 2, new byte[]{4}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 0, "global transfer limit admitted another owner's transfer");

        deliver(globallyLimited, OWNER_A, CHANNEL_A, frame(3, 1, 2, new byte[]{3}), (bytes, buf) -> completions.incrementAndGet());
        complete(globallyLimited, OWNER_B, CHANNEL_A, 3, (byte) 4, completions);
        check(completions.get() == 2, "completed transfer did not release the global slot");
    }

    private static void testOwnerAndGlobalByteLimits()
    {
        TestCrusher ownerLimited = crusher(
            new MutableClock(),
            2,
            4,
            PacketCrusher.BUFFER_SIZE + 1L,
            2L * (PacketCrusher.BUFFER_SIZE + 1L),
            100L
        );
        AtomicInteger completions = new AtomicInteger();

        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(1, 0, 2, filledChunk((byte) 1)), NOOP);
        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(2, 0, 2, filledChunk((byte) 2)), NOOP);
        deliver(ownerLimited, OWNER_B, CHANNEL_A, frame(1, 0, 2, filledChunk((byte) 3)), NOOP);
        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(2, 1, 2, new byte[]{2}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 0, "per-owner byte limit admitted excess buffered data");

        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(1, 1, 2, new byte[]{1}), (bytes, buf) -> completions.incrementAndGet());
        deliver(ownerLimited, OWNER_B, CHANNEL_A, frame(1, 1, 2, new byte[]{3}), (bytes, buf) -> completions.incrementAndGet());
        complete(ownerLimited, OWNER_A, CHANNEL_A, 2, (byte) 2, completions);
        check(completions.get() == 3, "per-owner buffered bytes were not released after completion");

        TestCrusher globallyLimited = crusher(
            new MutableClock(),
            2,
            4,
            PacketCrusher.BUFFER_SIZE + 1L,
            PacketCrusher.BUFFER_SIZE + 1L,
            100L
        );
        completions.set(0);
        deliver(globallyLimited, OWNER_A, CHANNEL_A, frame(4, 0, 2, filledChunk((byte) 4)), NOOP);
        deliver(globallyLimited, OWNER_B, CHANNEL_A, frame(4, 0, 2, filledChunk((byte) 5)), NOOP);
        deliver(globallyLimited, OWNER_B, CHANNEL_A, frame(4, 1, 2, new byte[]{5}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 0, "global byte limit admitted excess buffered data");

        deliver(globallyLimited, OWNER_A, CHANNEL_A, frame(4, 1, 2, new byte[]{4}), (bytes, buf) -> completions.incrementAndGet());
        complete(globallyLimited, OWNER_B, CHANNEL_A, 4, (byte) 5, completions);
        check(completions.get() == 2, "global buffered bytes were not released after completion");
    }

    private static void testIdleExpiryAndIdReuse()
    {
        MutableClock ownerClock = new MutableClock();
        TestCrusher ownerLimited = crusher(
            ownerClock,
            1,
            2,
            2L * PacketCrusher.BUFFER_SIZE,
            4L * PacketCrusher.BUFFER_SIZE,
            10L
        );
        AtomicInteger ownerCompletions = new AtomicInteger();

        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(8, 0, 2, filledChunk((byte) 8)), NOOP);
        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(9, 0, 2, filledChunk((byte) 9)), NOOP);
        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(9, 1, 2, new byte[]{9}), (bytes, buf) -> ownerCompletions.incrementAndGet());
        check(ownerCompletions.get() == 0, "pre-timeout owner capacity admitted another transfer");

        ownerClock.advance(9L);
        check(ownerLimited.expireIdleTransfers() == 0, "tick expiry removed a transfer before its idle timeout");
        ownerClock.advance(1L);
        check(ownerLimited.expireIdleTransfers() == 1, "tick expiry did not release the timed-out owner slot");
        check(ownerLimited.expireIdleTransfers() == 0, "tick expiry owner cleanup was not idempotent");
        complete(ownerLimited, OWNER_A, CHANNEL_A, 9, (byte) 9, ownerCompletions);
        check(ownerCompletions.get() == 1, "tick expiry did not restore per-owner capacity");

        deliver(ownerLimited, OWNER_A, CHANNEL_A, frame(8, 1, 2, new byte[]{8}), (bytes, buf) -> ownerCompletions.incrementAndGet());
        check(ownerCompletions.get() == 1, "an expired transfer accepted its old trailing chunk");
        complete(ownerLimited, OWNER_A, CHANNEL_A, 8, (byte) 8, ownerCompletions);
        check(ownerCompletions.get() == 2, "expired transfer id could not be safely reused");

        MutableClock globalClock = new MutableClock();
        TestCrusher globallyLimited = crusher(
            globalClock,
            1,
            1,
            2L * PacketCrusher.BUFFER_SIZE,
            2L * PacketCrusher.BUFFER_SIZE,
            10L
        );
        AtomicInteger globalCompletions = new AtomicInteger();

        deliver(globallyLimited, OWNER_A, CHANNEL_A, frame(10, 0, 2, filledChunk((byte) 10)), NOOP);
        deliver(globallyLimited, OWNER_B, CHANNEL_A, frame(10, 0, 2, filledChunk((byte) 11)), NOOP);
        deliver(globallyLimited, OWNER_B, CHANNEL_A, frame(10, 1, 2, new byte[]{11}), (bytes, buf) -> globalCompletions.incrementAndGet());
        check(globalCompletions.get() == 0, "pre-timeout global capacity admitted another owner");

        globalClock.advance(10L);
        check(globallyLimited.expireIdleTransfers() == 1, "tick expiry did not release the timed-out global slot");
        complete(globallyLimited, OWNER_B, CHANNEL_A, 10, (byte) 11, globalCompletions);
        check(globalCompletions.get() == 1, "tick expiry did not restore global capacity");
    }

    private static void testMalformedWarningRateLimit()
    {
        PacketCrusher.WarningLimiter limiter = new PacketCrusher.WarningLimiter(
            PacketCrusher.DROP_WARNING_BURST,
            PacketCrusher.DROP_WARNING_WINDOW_NANOS
        );

        for (int i = 0; i < PacketCrusher.DROP_WARNING_BURST; i++)
        {
            check(limiter.acquire(0L) == PacketCrusher.WarningDecision.DETAIL, "warning burst lost a bounded diagnostic");
        }

        check(limiter.acquire(0L) == PacketCrusher.WarningDecision.LIMIT_NOTICE, "warning limiter omitted its suppression notice");

        for (int i = 0; i < 100; i++)
        {
            check(limiter.acquire(0L) == PacketCrusher.WarningDecision.SUPPRESS, "malformed warning flood escaped the bounded window");
        }

        check(
            limiter.acquire(PacketCrusher.DROP_WARNING_WINDOW_NANOS - 1L) == PacketCrusher.WarningDecision.SUPPRESS,
            "warning limiter reset before its window elapsed"
        );
        check(
            limiter.acquire(PacketCrusher.DROP_WARNING_WINDOW_NANOS) == PacketCrusher.WarningDecision.DETAIL,
            "warning limiter did not restore diagnostics for a new window"
        );

        limiter.reset();
        check(limiter.acquire(0L) == PacketCrusher.WarningDecision.DETAIL, "warning limiter reset did not restore diagnostics");
    }

    private static void testMalformedFramesReleaseState()
    {
        TestCrusher crusher = crusher(new MutableClock(), 2, 2, 2L * PacketCrusher.BUFFER_SIZE, 2L * PacketCrusher.BUFFER_SIZE, 100L);
        AtomicInteger completions = new AtomicInteger();

        deliver(crusher, OWNER_A, CHANNEL_A, frame(10, 0, 2, filledChunk((byte) 10)), NOOP);
        deliver(crusher, OWNER_A, CHANNEL_A, claimedFrame(10, 1, 2, 1, new byte[0]), (bytes, buf) -> completions.incrementAndGet());
        deliver(crusher, OWNER_A, CHANNEL_A, frame(10, 1, 2, new byte[]{10}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 0, "truncated frame left a completable transfer behind");
        complete(crusher, OWNER_A, CHANNEL_A, 10, (byte) 10, completions);

        deliver(crusher, OWNER_A, CHANNEL_A, frame(11, 0, 2, filledChunk((byte) 11)), NOOP);
        deliver(crusher, OWNER_A, CHANNEL_A, frame(11, 0, 2, filledChunk((byte) 12)), NOOP);
        deliver(crusher, OWNER_A, CHANNEL_A, frame(11, 1, 2, new byte[]{11}), (bytes, buf) -> completions.incrementAndGet());

        deliver(crusher, OWNER_A, CHANNEL_A, frame(12, 0, 3, filledChunk((byte) 12)), NOOP);
        deliver(crusher, OWNER_A, CHANNEL_A, frame(12, 2, 3, new byte[]{12}), (bytes, buf) -> completions.incrementAndGet());

        check(completions.get() == 1, "duplicate or out-of-order chunks leaked a completed payload");
        complete(crusher, OWNER_A, CHANNEL_A, 11, (byte) 11, completions);
        complete(crusher, OWNER_A, CHANNEL_A, 12, (byte) 12, completions);
        check(completions.get() == 3, "malformed-frame cleanup did not release transfer capacity for reuse");
    }

    private static void testClearOwnerAndResetAreIdempotent()
    {
        TestCrusher crusher = crusher(new MutableClock(), 3, 4, 3L * PacketCrusher.BUFFER_SIZE, 4L * PacketCrusher.BUFFER_SIZE, 100L);
        AtomicInteger completions = new AtomicInteger();

        deliver(crusher, OWNER_A, CHANNEL_A, frame(1, 0, 2, filledChunk((byte) 1)), NOOP);
        deliver(crusher, OWNER_A, CHANNEL_B, frame(1, 0, 2, filledChunk((byte) 2)), NOOP);
        deliver(crusher, OWNER_B, CHANNEL_A, frame(1, 0, 2, filledChunk((byte) 3)), NOOP);

        check(crusher.clearOwner(OWNER_A) == 2, "disconnect cleanup did not remove every owner scope");
        check(crusher.clearOwner(OWNER_A) == 0, "disconnect cleanup was not idempotent");
        check(crusher.clearOwner(null) == 0, "null disconnect cleanup should be a no-op");

        deliver(crusher, OWNER_A, CHANNEL_A, frame(1, 1, 2, new byte[]{1}), (bytes, buf) -> completions.incrementAndGet());
        deliver(crusher, OWNER_B, CHANNEL_A, frame(1, 1, 2, new byte[]{3}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 1, "owner cleanup removed another owner's transfer or retained its own");

        deliver(crusher, OWNER_B, CHANNEL_A, frame(2, 0, 2, filledChunk((byte) 4)), NOOP);
        crusher.reset();
        crusher.reset();
        deliver(crusher, OWNER_B, CHANNEL_A, frame(2, 1, 2, new byte[]{4}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 1, "global reset retained an incomplete transfer");
    }

    private static void testCompletionCleanupPrecedesReceiver()
    {
        TestCrusher crusher = crusher(new MutableClock(), 1, 1, PacketCrusher.BUFFER_SIZE, PacketCrusher.BUFFER_SIZE, 100L);
        boolean thrown = false;

        try
        {
            deliver(crusher, OWNER_A, CHANNEL_A, frame(20, 0, 1, new byte[]{20}), (bytes, buf) ->
            {
                throw new ExpectedFailure();
            });
        }
        catch (ExpectedFailure e)
        {
            thrown = true;
        }

        check(thrown, "test receiver did not throw as expected");

        AtomicInteger completions = new AtomicInteger();
        deliver(crusher, OWNER_A, CHANNEL_A, frame(20, 0, 1, new byte[]{20}), (bytes, buf) -> completions.incrementAndGet());
        check(completions.get() == 1, "receiver failure retained transfer state or capacity");
    }

    private static void testEmptyPayloadSentinel()
    {
        TestCrusher crusher = crusher(new MutableClock(), 1, 1, PacketCrusher.BUFFER_SIZE, PacketCrusher.BUFFER_SIZE, 100L);
        boolean[] receivedNull = new boolean[1];

        deliver(crusher, OWNER_A, CHANNEL_A, frame(30, 0, 1, new byte[]{69}), (bytes, buf) -> receivedNull[0] = bytes == null);
        check(receivedNull[0], "empty-payload sentinel wire behavior changed");
    }

    private static void testExactTransferBoundaryAndSendRejection()
    {
        TestCrusher crusher = crusher(
            new MutableClock(),
            1,
            1,
            PacketCrusher.MAX_TRANSFER_BYTES,
            PacketCrusher.MAX_TRANSFER_BYTES,
            100L
        );
        int total = (PacketCrusher.MAX_TRANSFER_BYTES + PacketCrusher.BUFFER_SIZE - 1) / PacketCrusher.BUFFER_SIZE;
        int remaining = PacketCrusher.MAX_TRANSFER_BYTES;
        byte[] fullChunk = filledChunk((byte) 0x5a);
        AtomicInteger completions = new AtomicInteger();

        for (int index = 0; index < total; index++)
        {
            int size = Math.min(PacketCrusher.BUFFER_SIZE, remaining);
            byte[] payload = size == PacketCrusher.BUFFER_SIZE ? fullChunk : new byte[size];

            if (size != PacketCrusher.BUFFER_SIZE)
            {
                Arrays.fill(payload, (byte) 0x6b);
            }

            deliver(crusher, OWNER_A, CHANNEL_A, frame(40, index, total, payload), (bytes, buf) ->
            {
                check(bytes.length == PacketCrusher.MAX_TRANSFER_BYTES, "exact 16 MiB transfer length changed");
                check(bytes[0] == (byte) 0x5a && bytes[bytes.length - 1] == (byte) 0x6b, "exact-limit payload was corrupted");
                completions.incrementAndGet();
            });
            remaining -= size;
        }

        check(completions.get() == 1, "exact 16 MiB receive transfer was rejected");
        check(crusher.clearOwner(OWNER_A) == 0, "exact-limit completion retained state");

        int impossibleTotal = total + 1;

        deliver(crusher, OWNER_A, CHANNEL_A, frame(41, 0, impossibleTotal, fullChunk), NOOP);
        check(crusher.clearOwner(OWNER_A) == 0, "declared transfer above 16 MiB was admitted");

        boolean rejected = false;

        try
        {
            crusher.send((Player) null, CHANNEL_A, new byte[PacketCrusher.MAX_TRANSFER_BYTES + 1], null);
        }
        catch (IllegalArgumentException e)
        {
            rejected = true;
        }

        check(rejected, "send side emitted a payload every receiver must reject");
    }

    private static void testSendChunkCountAtExactBoundary()
    {
        SendCaptureCrusher crusher = new SendCaptureCrusher(
            new MutableClock(),
            new PacketCrusher.Limits(
                1,
                1,
                PacketCrusher.MAX_TRANSFER_BYTES,
                PacketCrusher.MAX_TRANSFER_BYTES,
                100L
            )
        );
        int expectedTotal = (PacketCrusher.MAX_TRANSFER_BYTES + PacketCrusher.BUFFER_SIZE - 1)
            / PacketCrusher.BUFFER_SIZE;

        crusher.send((Player) null, CHANNEL_A, new byte[PacketCrusher.MAX_TRANSFER_BYTES], null);

        check(crusher.count == expectedTotal, "send emitted an incorrect number of exact-limit chunks");
        check(crusher.firstTotal == expectedTotal, "send advertised an incorrect exact-limit total");
        check(crusher.lastIndex == expectedTotal - 1, "send skipped the exact-limit final chunk index");
        check(crusher.lastSize == PacketCrusher.MAX_TRANSFER_BYTES
            - (long) (expectedTotal - 1) * PacketCrusher.BUFFER_SIZE,
            "send advertised an incorrect exact-limit final chunk size");
    }

    private static void complete(TestCrusher crusher, UUID owner, ResourceLocation channel, int id, byte value, AtomicInteger completions)
    {
        deliver(crusher, owner, channel, frame(id, 0, 2, filledChunk(value)), NOOP);
        deliver(crusher, owner, channel, frame(id, 1, 2, new byte[]{value}), (bytes, buf) -> completions.incrementAndGet());
    }

    private static TestCrusher crusher(MutableClock clock, int ownerTransfers, int globalTransfers, long ownerBytes, long globalBytes, long timeout)
    {
        return new TestCrusher(clock, new PacketCrusher.Limits(ownerTransfers, globalTransfers, ownerBytes, globalBytes, timeout));
    }

    private static byte[] filledChunk(byte value)
    {
        byte[] bytes = new byte[PacketCrusher.BUFFER_SIZE];

        Arrays.fill(bytes, value);

        return bytes;
    }

    private static byte[] append(byte[] bytes, byte value)
    {
        byte[] result = Arrays.copyOf(bytes, bytes.length + 1);

        result[bytes.length] = value;

        return result;
    }

    private static FriendlyByteBuf frame(int id, int index, int total, byte[] payload)
    {
        return claimedFrame(id, index, total, payload.length, payload);
    }

    private static FriendlyByteBuf frameWithSuffix(int id, int index, int total, byte[] payload, int suffix)
    {
        FriendlyByteBuf buf = frame(id, index, total, payload);

        buf.writeInt(suffix);

        return buf;
    }

    private static FriendlyByteBuf claimedFrame(int id, int index, int total, int claimedSize, byte[] payload)
    {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());

        buf.writeInt(id);
        buf.writeInt(index);
        buf.writeInt(total);
        buf.writeInt(claimedSize);
        buf.writeBytes(payload);

        return buf;
    }

    private static void deliver(TestCrusher crusher, UUID owner, ResourceLocation channel, FriendlyByteBuf buf, IBufferReceiver receiver)
    {
        try
        {
            crusher.receive(owner, channel, buf, receiver);
        }
        finally
        {
            buf.release();
        }
    }

    private static void deliver(
        TestCrusher crusher,
        UUID owner,
        Object connectionIdentity,
        ResourceLocation channel,
        FriendlyByteBuf buf,
        IBufferReceiver receiver
    )
    {
        try
        {
            crusher.receive(owner, connectionIdentity, channel, buf, receiver);
        }
        finally
        {
            buf.release();
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TestCrusher extends PacketCrusher
    {
        private TestCrusher(LongSupplier nanoClock, Limits limits)
        {
            super(nanoClock, limits);
        }

        @Override
        protected void sendBuffer(Player entity, ResourceLocation identifier, FriendlyByteBuf buf)
        {}
    }

    private static final class SendCaptureCrusher extends PacketCrusher
    {
        private int count;
        private int firstTotal = -1;
        private int lastIndex = -1;
        private int lastSize = -1;

        private SendCaptureCrusher(LongSupplier nanoClock, Limits limits)
        {
            super(nanoClock, limits);
        }

        @Override
        protected void sendBuffer(Player entity, ResourceLocation identifier, FriendlyByteBuf buf)
        {
            int index = buf.getInt(buf.readerIndex() + Integer.BYTES);
            int total = buf.getInt(buf.readerIndex() + Integer.BYTES * 2);
            int size = buf.getInt(buf.readerIndex() + Integer.BYTES * 3);

            this.count += 1;
            this.firstTotal = this.firstTotal < 0 ? total : this.firstTotal;
            this.lastIndex = index;
            this.lastSize = size;
        }
    }

    private static final class MutableClock implements LongSupplier
    {
        private long now;

        @Override
        public long getAsLong()
        {
            return this.now;
        }

        private void advance(long amount)
        {
            this.now += amount;
        }
    }

    private static final class ExpectedFailure extends RuntimeException
    {}
}
