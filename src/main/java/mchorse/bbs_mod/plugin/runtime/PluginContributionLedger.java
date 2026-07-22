package mchorse.bbs_mod.plugin.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reverse-order ownership ledger for one plugin generation.
 *
 * <p>Registration is serialized with close.  A late registration is closed
 * immediately and rejected, so a race with generation retirement cannot leak
 * a resource.  Closing snapshots the entries before invoking user code and
 * therefore does not hold the ledger monitor while callbacks run.</p>
 */
public final class PluginContributionLedger implements AutoCloseable
{
    public enum State
    {
        OPEN,
        SEALED,
        CLOSING,
        CLOSED
    }

    private final PluginOwner owner;
    private final Object monitor = new Object();
    private final List<PluginLease> entries = new ArrayList<>();
    private State state = State.OPEN;
    private Thread closingThread;
    private Throwable closeFailure;

    public PluginContributionLedger(PluginOwner owner)
    {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public State state()
    {
        synchronized (this.monitor)
        {
            return this.state;
        }
    }

    public boolean isAccepting()
    {
        synchronized (this.monitor)
        {
            return this.state == State.OPEN;
        }
    }

    /** Stop structural registrations while allowing existing entries to drain. */
    public void seal()
    {
        synchronized (this.monitor)
        {
            if (this.state == State.OPEN)
            {
                this.state = State.SEALED;
            }
        }
    }

    public PluginLease own(AutoCloseable cleanup)
    {
        Objects.requireNonNull(cleanup, "cleanup");
        return this.register(PluginLease.of(this.owner, cleanup));
    }

    public PluginLease own(String description, AutoCloseable cleanup)
    {
        PluginLease lease = PluginLease.of(this.owner, description, cleanup);

        try
        {
            this.register(lease);
        }
        catch (RuntimeException exception)
        {
            /* register() closes a rejected lease before reporting the race. */
            throw exception;
        }

        return lease;
    }

    public PluginLease register(PluginLease lease)
    {
        Objects.requireNonNull(lease, "lease");

        if (!this.owner.equals(lease.owner()))
        {
            throw new IllegalArgumentException("Lease owner does not match ledger owner");
        }

        boolean accepted;

        synchronized (this.monitor)
        {
            accepted = this.state == State.OPEN;

            if (accepted)
            {
                this.entries.add(lease);
                return lease;
            }
        }

        Throwable rejection = new IllegalStateException(
            "Plugin contribution ledger " + this.owner + " is " + this.state()
        );

        try
        {
            lease.close();
        }
        catch (Throwable cleanupFailure)
        {
            rejection.addSuppressed(cleanupFailure);
        }

        throw (RuntimeException) rejection;
    }

    public int registeredCount()
    {
        synchronized (this.monitor)
        {
            return this.entries.size();
        }
    }

    public int openCount()
    {
        synchronized (this.monitor)
        {
            int count = 0;

            for (PluginLease entry : this.entries)
            {
                if (!entry.isClosed())
                {
                    count += 1;
                }
            }

            return count;
        }
    }

    /** The first cleanup failure, if a close attempt has completed. */
    public Throwable closeFailure()
    {
        synchronized (this.monitor)
        {
            return this.closeFailure;
        }
    }

    @Override
    public void close()
    {
        List<PluginLease> snapshot;

        synchronized (this.monitor)
        {
            if (this.state == State.CLOSED)
            {
                PluginFailures.throwIfPresent(this.owner, "closing the contribution ledger", this.closeFailure);
                return;
            }

            if (this.state == State.CLOSING)
            {
                if (this.closingThread == Thread.currentThread())
                {
                    return;
                }

                this.awaitCloseCompletion();
                PluginFailures.throwIfPresent(this.owner, "closing the contribution ledger", this.closeFailure);
                return;
            }

            this.state = State.CLOSING;
            this.closingThread = Thread.currentThread();
            snapshot = new ArrayList<>(this.entries);
            this.entries.clear();
        }

        Throwable failure = null;

        for (int index = snapshot.size() - 1; index >= 0; index -= 1)
        {
            try
            {
                snapshot.get(index).close();
            }
            catch (Throwable throwable)
            {
                failure = PluginFailures.append(failure, throwable);
            }
        }

        synchronized (this.monitor)
        {
            this.closeFailure = failure;
            this.state = State.CLOSED;
            this.closingThread = null;
            this.monitor.notifyAll();
        }

        PluginFailures.throwIfPresent(this.owner, "closing the contribution ledger", failure);
    }

    private void awaitCloseCompletion()
    {
        boolean interrupted = false;

        while (this.state == State.CLOSING)
        {
            try
            {
                this.monitor.wait();
            }
            catch (InterruptedException exception)
            {
                interrupted = true;
            }
        }

        if (interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }
}
