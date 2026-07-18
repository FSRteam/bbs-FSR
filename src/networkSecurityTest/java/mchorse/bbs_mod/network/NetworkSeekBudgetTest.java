package mchorse.bbs_mod.network;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class NetworkSeekBudgetTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("NetworkSeekBudgetTest passed");
    }

    static void runAll()
    {
        testAlternatingSeeksConsumeCumulativeCredit();
        testConnectionAggregatePreventsFilmSwitchBypass();
        testReplacementConnectionUsesIdentityScope();
        testGlobalWorkBudgetPreventsConnectionSwitchBypass();
        testCompletedPayloadThroughputBudget();
        testClockRollbackDoesNotGrantCredit();
        testCapacityExpiryAndConnectionCleanup();
        testBroadOwnerCleanupRemainsAvailable();
        testInvalidRequestsDoNotAllocateBuckets();
        testLegacyOwnerApiRemainsAvailable();
    }

    private static void testAlternatingSeeksConsumeCumulativeCredit()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 1_000L, 4, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(budget.tryConsume(owner, connection, "shots/intro", 10L), "the initial bounded seek was rejected");
        check(!budget.tryConsume(owner, connection, "shots/intro", 1L), "an immediate alternating seek bypassed the cumulative budget");

        now.addAndGet(50L);

        check(budget.tryConsume(owner, connection, "shots/intro", 5L), "elapsed time did not refill proportional seek credit");
        check(!budget.tryConsume(owner, connection, "shots/intro", 1L), "a request consumed more credit than the partial refill supplied");

        now.addAndGet(50L);

        check(budget.tryConsume(owner, connection, "shots/intro", 5L), "the remainder of the refill period was not credited");
    }

    private static void testConnectionAggregatePreventsFilmSwitchBypass()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 1_000L, 4, 8);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();

        check(budget.tryConsume(alice, aliceConnection, "shots/a", 10L), "the first film did not receive its initial budget");
        check(!budget.tryConsume(alice, aliceConnection, "shots/b", 1L), "switching films bypassed the connection's aggregate budget");
        check(budget.tryConsume(bob, bobConnection, "shots/a", 10L), "one connection's budget leaked into another owner's bucket");

        now.addAndGet(100L);

        check(budget.tryConsume(alice, aliceConnection, "shots/b", 10L), "the aggregate connection bucket did not refill after one period");
    }

    private static void testReplacementConnectionUsesIdentityScope()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = new NetworkSeekBudget(
            now::get,
            new NetworkSeekBudget.Limits(2L, 2L, 20L, 20L, 100L, 1_000L, 2, 8)
        );
        UUID owner = UUID.randomUUID();
        EqualConnection oldConnection = new EqualConnection("same-owner");
        EqualConnection replacementConnection = new EqualConnection("same-owner");

        check(oldConnection != replacementConnection && oldConnection.equals(replacementConnection),
            "the replacement fixture must be equal but non-identical");
        check(budget.tryConsume(owner, oldConnection, "shots/a", 2L),
            "the old connection could not spend its seek credit");
        check(!budget.tryConsume(owner, oldConnection, "shots/a", 1L),
            "the old connection exceeded its seek budget");
        check(budget.tryConsume(owner, replacementConnection, "shots/a", 2L),
            "the replacement connection inherited the old connection's debt");

        check(budget.clearConnection(owner, oldConnection) == 1,
            "old logout did not remove its exact seek bucket");
        check(budget.size() == 1,
            "old logout cleared the replacement connection's seek bucket");
        check(!budget.tryConsume(owner, replacementConnection, "shots/a", 1L),
            "old logout cleared the replacement connection's debt");
        check(budget.clearConnection(owner, oldConnection) == 0,
            "exact connection cleanup was not idempotent");
    }

    private static void testClockRollbackDoesNotGrantCredit()
    {
        AtomicLong now = new AtomicLong(100L);
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 1_000L, 4, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(budget.tryConsume(owner, connection, "shots/clock", 10L), "the initial seek was rejected");

        now.set(50L);

        check(!budget.tryConsume(owner, connection, "shots/clock", 1L), "a clock rollback granted fresh credit");

        now.set(100L);

        check(!budget.tryConsume(owner, connection, "shots/clock", 1L), "clock recovery counted an already-observed interval twice");

        now.set(110L);

        check(budget.tryConsume(owner, connection, "shots/clock", 1L), "new monotonic time after recovery did not refill credit");
    }

    private static void testCompletedPayloadThroughputBudget()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = new NetworkSeekBudget(
            now::get,
            new NetworkSeekBudget.Limits(16L, 4L, 32L, 8L, 100L, 1_000L, 2, 8)
        );
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();
        Object charlieConnection = new Object();

        check(budget.tryConsume(alice, aliceConnection, "s4", 16L), "the first completed payload was rejected");
        check(!budget.tryConsume(alice, aliceConnection, "s4", 1L), "sequential completed payloads bypassed the connection throughput budget");
        check(budget.tryConsume(bob, bobConnection, "s4", 16L), "the second connection's allowed global share was rejected");
        check(!budget.tryConsume(charlie, charlieConnection, "s4", 1L), "switching connections bypassed completed-payload global throughput");

        now.addAndGet(25L);

        check(budget.tryConsume(alice, aliceConnection, "s4", 1L), "completed-payload credit did not recover proportionally");
        check(budget.clearConnection(alice, aliceConnection) == 1, "disconnect did not release exact completed-payload accounting");
    }

    private static void testGlobalWorkBudgetPreventsConnectionSwitchBypass()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 1_000L, 4, 8);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();
        Object charlieConnection = new Object();

        check(budget.tryConsume(alice, aliceConnection, "shots/a", 10L), "the first connection's global allocation was rejected");
        check(budget.tryConsume(bob, bobConnection, "shots/b", 10L), "the second connection's global allocation was rejected");
        check(!budget.tryConsume(charlie, charlieConnection, "shots/c", 1L), "switching connections bypassed the global seek-work budget");

        now.addAndGet(50L);

        check(budget.tryConsume(charlie, charlieConnection, "shots/c", 10L), "global seek work did not refill proportionally");
    }

    private static void testCapacityExpiryAndConnectionCleanup()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 50L, 1, 2);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID charlie = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();
        Object charlieConnection = new Object();

        check(budget.tryConsume(alice, aliceConnection, "shots/a", 1L), "the first bucket was rejected");
        check(!budget.tryConsume(alice, aliceConnection, "shots/b", 1L), "the per-connection film bucket limit was bypassed");
        check(budget.tryConsume(bob, bobConnection, "shots/b", 1L), "the second global bucket was rejected");
        check(!budget.tryConsume(charlie, charlieConnection, "shots/c", 1L), "the global film bucket limit was bypassed");
        check(budget.size() == 2, "rejected capacity requests changed retained bucket accounting");

        now.set(49L);

        check(budget.expireIdle() == 0, "a bucket expired before the idle boundary");

        now.set(50L);

        check(budget.expireIdle() == 2, "idle buckets were not released at the exact boundary");
        check(budget.tryConsume(charlie, charlieConnection, "shots/c", 1L), "expired capacity was not reusable");
        check(budget.clearConnection(charlie, charlieConnection) == 1, "disconnect cleanup did not remove the connection's film bucket");
        check(budget.size() == 0, "disconnect cleanup left retained seek state");

        budget.reset();

        check(budget.size() == 0, "server reset did not clear seek budgets");
    }

    private static void testBroadOwnerCleanupRemainsAvailable()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 1_000L, 4, 8);
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        Object firstConnection = new Object();
        Object secondConnection = new Object();
        Object otherConnection = new Object();

        check(budget.tryConsume(owner, firstConnection, "shots/a", 1L),
            "the owner's first exact connection was rejected");
        check(budget.tryConsume(owner, secondConnection, "shots/b", 1L),
            "the owner's replacement connection was rejected");
        check(budget.tryConsume(otherOwner, otherConnection, "shots/c", 1L),
            "the unrelated owner's connection was rejected");
        check(budget.clearOwner(owner) == 2,
            "broad owner cleanup did not remove every exact connection");
        check(budget.size() == 1,
            "broad owner cleanup removed an unrelated connection");
        check(budget.clearConnection(otherOwner, otherConnection) == 1 && budget.size() == 0,
            "exact cleanup did not release the remaining connection");
    }

    private static void testInvalidRequestsDoNotAllocateBuckets()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 10, 10, 100L, 1_000L, 4, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(!budget.tryConsume(null, connection, "shots/a", 1L), "a missing owner was accepted");
        check(!budget.tryConsume(owner, null, "shots/a", 1L), "a missing connection was accepted");
        check(!budget.tryConsume(owner, connection, null, 1L), "a missing film id was accepted");
        check(!budget.tryConsume(owner, connection, " ", 1L), "a blank film id was accepted");
        check(!budget.tryConsume(owner, connection, "shots/a", -1L), "negative work was accepted");
        check(!budget.tryConsume(owner, connection, "shots/a", 11L), "work above bucket capacity was accepted");
        check(budget.tryConsume(owner, connection, "shots/a", 0L), "a zero-work seek should be a valid no-op");
        check(budget.clearConnection(null, connection) == 0
                && budget.clearConnection(owner, null) == 0,
            "invalid exact cleanup removed state");
        check(budget.size() == 0, "invalid or zero-work requests allocated retained buckets");
    }

    private static void testLegacyOwnerApiRemainsAvailable()
    {
        AtomicLong now = new AtomicLong();
        NetworkSeekBudget budget = budget(now, 2, 2, 100L, 1_000L, 2, 4);
        UUID owner = UUID.randomUUID();

        check(budget.tryConsume(owner, "shots/legacy", 2L),
            "the UUID-only compatibility API rejected its initial budget");
        check(!budget.tryConsume(owner, "shots/legacy", 1L),
            "the UUID-only compatibility API lost cumulative debt");
        check(budget.clearOwner(owner) == 1 && budget.size() == 0,
            "owner cleanup did not include UUID-only compatibility state");
    }

    private static NetworkSeekBudget budget(
        AtomicLong now,
        long capacity,
        long refill,
        long refillPeriod,
        long idleTimeout,
        int ownerBuckets,
        int globalBuckets
    )
    {
        return new NetworkSeekBudget(
            now::get,
            new NetworkSeekBudget.Limits(capacity, refill, refillPeriod, idleTimeout, ownerBuckets, globalBuckets)
        );
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
