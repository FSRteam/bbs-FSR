package mchorse.bbs_mod.plugin.runtime;

import java.time.Duration;
import java.util.Objects;

/** Immutable contribution snapshot plus the mutable admission gate for a generation. */
public final class ActivePluginGeneration<T> implements AutoCloseable
{
    private final PluginOwner owner;
    private volatile T contributions;
    private final PluginGenerationGate gate;

    public ActivePluginGeneration(PluginOwner owner, T contributions)
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.contributions = contributions;
        this.gate = new PluginGenerationGate(owner);
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public T contributions()
    {
        return this.contributions;
    }

    public PluginGenerationGate gate()
    {
        return this.gate;
    }

    public PluginGenerationFence fence()
    {
        return this.gate.fence();
    }

    public PluginGenerationGate.State state()
    {
        return this.gate.state();
    }

    public void activate()
    {
        this.gate.activate();
    }

    public boolean beginDrain()
    {
        return this.gate.beginDrain();
    }

    public boolean awaitDrained(Duration timeout) throws InterruptedException
    {
        return this.gate.awaitDrained(timeout);
    }

    public void retire()
    {
        this.gate.close();
        this.contributions = null;
    }

    @Override
    public void close()
    {
        this.retire();
    }

    public PluginGenerationLease<T> acquire()
    {
        return this.gate.acquire(this.contributions);
    }
}
