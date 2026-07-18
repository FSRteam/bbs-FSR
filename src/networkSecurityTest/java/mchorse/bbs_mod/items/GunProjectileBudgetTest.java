package mchorse.bbs_mod.items;

import java.util.UUID;

public final class GunProjectileBudgetTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("GunProjectileBudgetTest passed");
    }

    public static void runAll()
    {
        testOwnerAndGlobalCaps();
        testReleaseIsExactlyOnce();
        testOwnerAndResetCleanup();
        testLateCleanupAttachmentAfterCancellation();
        testCleanupFailureDoesNotLeakOtherReservations();
    }

    private static void testOwnerAndGlobalCaps()
    {
        GunProjectileBudget budget = new GunProjectileBudget(2, 3);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        check(budget.tryReserve(alice) != null, "first owner reservation was rejected");
        check(budget.tryReserve(alice) != null, "exact per-owner cap was rejected");
        check(budget.tryReserve(alice) == null, "per-owner active projectile cap was bypassed");
        check(budget.tryReserve(bob) != null, "second owner could not use remaining global capacity");
        check(budget.tryReserve(bob) == null, "global active projectile cap was bypassed");
        check(budget.getActiveGlobal() == 3, "global active count drifted from reservations");

        budget.reset();
    }

    private static void testReleaseIsExactlyOnce()
    {
        GunProjectileBudget budget = new GunProjectileBudget(2, 4);
        UUID owner = UUID.randomUUID();
        GunProjectileBudget.Lease lease = budget.tryReserve(owner);
        int[] cleanups = new int[1];

        check(lease != null, "reservation was rejected");
        lease.attachCleanup(() -> cleanups[0] += 1);

        lease.close();
        lease.close();

        check(budget.getActive(owner) == 0, "duplicate entity removal corrupted owner count");
        check(budget.getActiveGlobal() == 0, "duplicate entity removal corrupted global count");
        check(cleanups[0] == 0, "natural entity removal recursively invoked cancellation cleanup");
    }

    private static void testOwnerAndResetCleanup()
    {
        GunProjectileBudget budget = new GunProjectileBudget(2, 4);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        int[] cleanups = new int[1];
        GunProjectileBudget.Lease aliceLease = budget.tryReserve(alice);
        GunProjectileBudget.Lease bobLease = budget.tryReserve(bob);

        aliceLease.attachCleanup(() -> cleanups[0] += 1);
        bobLease.attachCleanup(() -> cleanups[0] += 1);
        budget.clearOwner(alice);

        check(cleanups[0] == 1, "logout did not discard the owner's active projectile exactly once");
        check(budget.getActive(alice) == 0, "logout retained the owner's reservation");
        check(budget.getActive(bob) == 1, "logout cleared another owner's projectile");

        aliceLease.close();
        check(cleanups[0] == 1, "post-cancel entity removal repeated logout cleanup");

        budget.reset();

        check(cleanups[0] == 2, "server reset did not discard remaining projectiles");
        check(budget.getActiveGlobal() == 0, "server reset retained active reservations");
    }

    private static void testLateCleanupAttachmentAfterCancellation()
    {
        GunProjectileBudget budget = new GunProjectileBudget(1, 1);
        UUID owner = UUID.randomUUID();
        GunProjectileBudget.Lease lease = budget.tryReserve(owner);
        int[] cleanups = new int[1];

        budget.clearOwner(owner);
        lease.attachCleanup(() -> cleanups[0] += 1);

        check(cleanups[0] == 1, "cancellation racing entity attachment leaked the projectile");
        check(!lease.isActive(), "cancelled reservation remained active");

        boolean duplicateRejected = false;

        try
        {
            lease.attachCleanup(() -> cleanups[0] += 1);
        }
        catch (IllegalStateException e)
        {
            duplicateRejected = true;
        }

        check(duplicateRejected, "cancelled lease accepted a second late cleanup attachment");
        check(cleanups[0] == 1, "cancelled lease executed cleanup more than once");
    }

    private static void testCleanupFailureDoesNotLeakOtherReservations()
    {
        GunProjectileBudget budget = new GunProjectileBudget(2, 2);
        UUID owner = UUID.randomUUID();
        GunProjectileBudget.Lease failing = budget.tryReserve(owner);
        GunProjectileBudget.Lease healthy = budget.tryReserve(owner);
        int[] healthyCleanups = new int[1];

        failing.attachCleanup(() ->
        {
            throw new IllegalStateException("expected cleanup failure");
        });
        healthy.attachCleanup(() -> healthyCleanups[0] += 1);

        budget.clearOwner(owner);

        check(healthyCleanups[0] == 1, "one cleanup failure prevented later owner cleanup");
        check(budget.getActive(owner) == 0, "cleanup failure retained owner reservations");
        check(budget.getActiveGlobal() == 0, "cleanup failure retained global reservations");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
