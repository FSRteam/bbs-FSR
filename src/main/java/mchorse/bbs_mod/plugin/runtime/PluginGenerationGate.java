package mchorse.bbs_mod.plugin.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Serial state gate and in-flight call counter for one plugin generation. */
public final class PluginGenerationGate implements AutoCloseable
{
    public enum State
    {
        STAGED,
        ACTIVE,
        DRAINING,
        CLOSED
    }

    private final PluginOwner owner;
    private final PluginGenerationFence fence;
    private final Object monitor = new Object();
    private State state = State.STAGED;
    private int activeCalls;

    public PluginGenerationGate(PluginOwner owner)
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.fence = new PluginGenerationFence(this);
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public PluginGenerationFence fence()
    {
        return this.fence;
    }

    public State state()
    {
        synchronized (this.monitor)
        {
            return this.state;
        }
    }

    public boolean isActive()
    {
        synchronized (this.monitor)
        {
            return this.state == State.ACTIVE;
        }
    }

    public boolean isDraining()
    {
        synchronized (this.monitor)
        {
            return this.state == State.DRAINING;
        }
    }

    public boolean isClosed()
    {
        synchronized (this.monitor)
        {
            return this.state == State.CLOSED;
        }
    }

    public int activeCalls()
    {
        synchronized (this.monitor)
        {
            return this.activeCalls;
        }
    }

    public void activate()
    {
        synchronized (this.monitor)
        {
            if (this.state == State.ACTIVE)
            {
                return;
            }

            if (this.state != State.STAGED)
            {
                throw new IllegalStateException("Cannot activate " + this.owner + " from " + this.state);
            }

            this.state = State.ACTIVE;
        }
    }

    /** Refuse new calls while allowing already acquired leases to finish. */
    public boolean beginDrain()
    {
        synchronized (this.monitor)
        {
            if (this.state == State.DRAINING)
            {
                return false;
            }

            if (this.state == State.CLOSED)
            {
                return false;
            }

            this.state = State.DRAINING;
            this.monitor.notifyAll();
            return true;
        }
    }

    public <T> PluginGenerationLease<T> acquire(T contributions)
    {
        synchronized (this.monitor)
        {
            if (this.state != State.ACTIVE)
            {
                return null;
            }

            this.activeCalls += 1;
            return new PluginGenerationLease<>(this, contributions);
        }
    }

    public PluginGenerationLease<Void> acquire()
    {
        return this.acquire(null);
    }

    public boolean awaitDrained(Duration timeout) throws InterruptedException
    {
        Objects.requireNonNull(timeout, "timeout");

        if (timeout.isNegative())
        {
            throw new IllegalArgumentException("Drain timeout cannot be negative");
        }

        long nanos = timeout.toNanos();

        synchronized (this.monitor)
        {
            long deadline = System.nanoTime() + nanos;

            while (this.activeCalls != 0)
            {
                if (nanos <= 0L)
                {
                    return false;
                }

                TimeUnit.NANOSECONDS.timedWait(this.monitor, nanos);
                nanos = deadline - System.nanoTime();
            }

            return true;
        }
    }

    public boolean drain(Duration timeout) throws InterruptedException
    {
        this.beginDrain();
        return this.awaitDrained(timeout);
    }

    /**
     * Complete logical retirement.  The caller must have drained all in-flight
     * calls; no forced counter reset is provided.
     */
    @Override
    public void close()
    {
        synchronized (this.monitor)
        {
            if (this.state == State.CLOSED)
            {
                return;
            }

            if (this.state == State.ACTIVE)
            {
                this.state = State.DRAINING;
            }

            if (this.activeCalls != 0)
            {
                throw new IllegalStateException(
                    "Cannot close " + this.owner + " with " + this.activeCalls + " active calls"
                );
            }

            this.state = State.CLOSED;
            this.monitor.notifyAll();
        }
    }

    void release()
    {
        synchronized (this.monitor)
        {
            if (this.activeCalls <= 0)
            {
                throw new IllegalStateException("Generation lease underflow for " + this.owner);
            }

            this.activeCalls -= 1;

            if (this.activeCalls == 0)
            {
                this.monitor.notifyAll();
            }
        }
    }
}
