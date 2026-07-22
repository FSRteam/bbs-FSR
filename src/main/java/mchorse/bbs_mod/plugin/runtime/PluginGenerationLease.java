package mchorse.bbs_mod.plugin.runtime;

import java.util.Objects;

/** An in-flight call lease bound to one immutable generation snapshot. */
public final class PluginGenerationLease<T> implements AutoCloseable
{
    private final PluginGenerationGate gate;
    private final PluginOwner owner;
    private volatile T contributions;
    private boolean closed;

    PluginGenerationLease(PluginGenerationGate gate, T contributions)
    {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.owner = gate.owner();
        this.contributions = contributions;
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public T contributions()
    {
        return this.contributions;
    }

    public PluginGenerationFence fence()
    {
        return this.gate.fence();
    }

    /** True only while this generation remains ACTIVE after the lease was acquired. */
    public boolean isCurrent()
    {
        return this.gate.isActive();
    }

    /** True after this generation stopped accepting new calls. */
    public boolean isFenced()
    {
        return !this.isCurrent();
    }

    public synchronized boolean isClosed()
    {
        return this.closed;
    }

    @Override
    public void close()
    {
        synchronized (this)
        {
            if (this.closed)
            {
                return;
            }

            this.closed = true;
            this.contributions = null;
        }

        this.gate.release();
    }
}
