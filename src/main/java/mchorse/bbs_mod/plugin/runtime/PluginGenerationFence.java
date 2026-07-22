package mchorse.bbs_mod.plugin.runtime;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * A non-owning view of a generation's admission fence.
 *
 * <p>It is intentionally cheap to copy into a future/mailbox callback.  The
 * fence is open only while the generation is ACTIVE; after a root swap old
 * asynchronous work can observe the closed fence and avoid writing to the new
 * generation.</p>
 */
public final class PluginGenerationFence
{
    private final PluginGenerationGate gate;

    PluginGenerationFence(PluginGenerationGate gate)
    {
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public PluginOwner owner()
    {
        return this.gate.owner();
    }

    public boolean isOpen()
    {
        return this.gate.isActive();
    }

    public boolean isClosed()
    {
        return !this.isOpen();
    }

    public void requireOpen()
    {
        if (!this.isOpen())
        {
            throw new IllegalStateException("Plugin generation fence is closed for " + this.owner());
        }
    }

    /** Acquire a counted callback lease, or {@code null} after fencing. */
    public PluginGenerationLease<Void> acquire()
    {
        return this.gate.acquire();
    }

    public boolean runIfOpen(Runnable callback)
    {
        Objects.requireNonNull(callback, "callback");

        PluginGenerationLease<Void> lease = this.acquire();

        if (lease == null)
        {
            return false;
        }

        try (lease)
        {
            callback.run();
            return true;
        }
    }

    public <T> T callIfOpen(Supplier<T> callback, T fallback)
    {
        Objects.requireNonNull(callback, "callback");
        PluginGenerationLease<Void> lease = this.acquire();

        if (lease == null)
        {
            return fallback;
        }

        try (lease)
        {
            return callback.get();
        }
    }

    public <T> T callIfOpen(Callable<T> callback) throws Exception
    {
        Objects.requireNonNull(callback, "callback");
        PluginGenerationLease<Void> lease = this.acquire();

        if (lease == null)
        {
            return null;
        }

        try (lease)
        {
            return callback.call();
        }
    }
}
