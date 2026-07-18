package mchorse.bbs_mod.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Active-projectile reservations keyed by the authenticated player who used
 * the gun. A lease is attached to the spawned entity and released by its
 * terminal removal path. Logout/reset cancellation can additionally invoke a
 * late-attached discard callback without double releasing the reservation.
 */
public final class GunProjectileBudget
{
    public static final int MAX_ACTIVE_PER_OWNER = 256;
    public static final int MAX_ACTIVE_GLOBAL = 4_096;
    public static final GunProjectileBudget RUNTIME = new GunProjectileBudget(
        MAX_ACTIVE_PER_OWNER,
        MAX_ACTIVE_GLOBAL
    );

    private final int ownerLimit;
    private final int globalLimit;
    private final Map<UUID, Set<Lease>> activeByOwner = new HashMap<>();
    private int active;

    public GunProjectileBudget()
    {
        this(MAX_ACTIVE_PER_OWNER, MAX_ACTIVE_GLOBAL);
    }

    public GunProjectileBudget(int ownerLimit, int globalLimit)
    {
        if (ownerLimit <= 0 || globalLimit < ownerLimit)
        {
            throw new IllegalArgumentException("Gun projectile limits are invalid");
        }

        this.ownerLimit = ownerLimit;
        this.globalLimit = globalLimit;
    }

    public synchronized Lease tryReserve(UUID owner)
    {
        if (owner == null || this.active >= this.globalLimit)
        {
            return null;
        }

        Set<Lease> leases = this.activeByOwner.computeIfAbsent(owner, (key) -> new HashSet<>());

        if (leases.size() >= this.ownerLimit)
        {
            return null;
        }

        Lease lease = new Lease(this, owner);

        leases.add(lease);
        this.active += 1;

        return lease;
    }

    public void clearOwner(UUID owner)
    {
        List<Lease> leases;

        synchronized (this)
        {
            Set<Lease> active = this.activeByOwner.get(owner);

            if (active == null || active.isEmpty())
            {
                return;
            }

            leases = new ArrayList<>(active);
        }

        for (Lease lease : leases)
        {
            lease.cancel();
        }
    }

    public void reset()
    {
        List<Lease> leases = new ArrayList<>();

        synchronized (this)
        {
            for (Set<Lease> ownerLeases : this.activeByOwner.values())
            {
                leases.addAll(ownerLeases);
            }
        }

        for (Lease lease : leases)
        {
            lease.cancel();
        }
    }

    public synchronized int getActive(UUID owner)
    {
        Set<Lease> leases = this.activeByOwner.get(owner);

        return leases == null ? 0 : leases.size();
    }

    public synchronized int getActiveGlobal()
    {
        return this.active;
    }

    private synchronized void release(Lease lease)
    {
        Set<Lease> leases = this.activeByOwner.get(lease.owner);

        if (leases == null || !leases.remove(lease))
        {
            return;
        }

        this.active -= 1;

        if (leases.isEmpty())
        {
            this.activeByOwner.remove(lease.owner);
        }
    }

    public static final class Lease implements AutoCloseable
    {
        private enum State
        {
            ACTIVE,
            CLOSED,
            CANCELLED
        }

        private final GunProjectileBudget budget;
        private final UUID owner;
        private State state = State.ACTIVE;
        private Runnable cleanup;

        private Lease(GunProjectileBudget budget, UUID owner)
        {
            this.budget = budget;
            this.owner = owner;
        }

        /** Attach the entity discard action before it is added to the world. */
        public void attachCleanup(Runnable cleanup)
        {
            if (cleanup == null)
            {
                throw new IllegalArgumentException("Gun projectile cleanup is required");
            }

            boolean cancelImmediately = false;

            synchronized (this)
            {
                if (this.cleanup != null)
                {
                    throw new IllegalStateException("Gun projectile cleanup is already attached");
                }

                /* Record the attachment in every terminal state as well. A
                 * cancellation can race attachment, but it must not allow a
                 * second late attachment to execute cleanup twice. */
                this.cleanup = cleanup;

                if (this.state == State.ACTIVE)
                {
                    return;
                }
                else if (this.state == State.CANCELLED)
                {
                    cancelImmediately = true;
                }
            }

            if (cancelImmediately)
            {
                runCleanup(cleanup);
            }
        }

        public synchronized boolean isActive()
        {
            return this.state == State.ACTIVE;
        }

        @Override
        public void close()
        {
            synchronized (this)
            {
                if (this.state != State.ACTIVE)
                {
                    return;
                }

                this.state = State.CLOSED;
            }

            this.budget.release(this);
        }

        private void cancel()
        {
            Runnable cleanup;

            synchronized (this)
            {
                if (this.state != State.ACTIVE)
                {
                    return;
                }

                this.state = State.CANCELLED;
                cleanup = this.cleanup;
            }

            this.budget.release(this);

            if (cleanup != null)
            {
                runCleanup(cleanup);
            }
        }

        private static void runCleanup(Runnable cleanup)
        {
            try
            {
                cleanup.run();
            }
            catch (RuntimeException ignored)
            {}
        }
    }
}
