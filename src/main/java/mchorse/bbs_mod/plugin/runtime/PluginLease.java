package mchorse.bbs_mod.plugin.runtime;

import java.util.Objects;

/**
 * An owner-tagged, exactly-once cleanup handle.
 *
 * <p>The cleanup reference is discarded before it is invoked.  A failed or
 * recursive close therefore cannot execute plugin cleanup twice or keep a
 * retired generation reachable through this lease.</p>
 */
public final class PluginLease implements AutoCloseable
{
    private final PluginOwner owner;
    private final String description;
    private AutoCloseable cleanup;
    private boolean closed;

    private PluginLease(PluginOwner owner, String description, AutoCloseable cleanup)
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.description = requireDescription(description);
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public static PluginLease of(PluginOwner owner, String description, AutoCloseable cleanup)
    {
        return new PluginLease(owner, description, cleanup);
    }

    public static PluginLease of(PluginOwner owner, AutoCloseable cleanup)
    {
        Objects.requireNonNull(cleanup, "cleanup");
        String description = cleanup.getClass().getSimpleName();

        return new PluginLease(owner, description.isBlank() ? "resource" : description, cleanup);
    }

    public static PluginLease noop(PluginOwner owner, String description)
    {
        return new PluginLease(owner, description, () -> {});
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public String description()
    {
        return this.description;
    }

    public synchronized boolean isClosed()
    {
        return this.closed;
    }

    @Override
    public void close()
    {
        AutoCloseable cleanup;

        synchronized (this)
        {
            if (this.closed)
            {
                return;
            }

            this.closed = true;
            cleanup = this.cleanup;
            this.cleanup = null;
        }

        try
        {
            cleanup.close();
        }
        catch (Throwable throwable)
        {
            PluginFailures.throwIfPresent(this.owner, "closing " + this.description, throwable);
        }
    }

    private static String requireDescription(String description)
    {
        if (description == null || description.isBlank())
        {
            throw new IllegalArgumentException("Plugin contribution description is required");
        }

        return description;
    }
}
