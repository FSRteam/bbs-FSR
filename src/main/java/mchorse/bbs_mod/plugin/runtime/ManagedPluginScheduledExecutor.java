package mchorse.bbs_mod.plugin.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Scheduled counterpart of {@link ManagedPluginExecutor}. */
public final class ManagedPluginScheduledExecutor extends ManagedPluginExecutor
    implements ScheduledExecutorService
{
    private final ScheduledExecutorService scheduledDelegate;

    ManagedPluginScheduledExecutor(
        PluginOwner owner,
        PluginGenerationFence fence,
        ClassLoader callbackClassLoader,
        ScheduledExecutorService delegate,
        Duration closeTimeout,
        PluginCallbackErrorHandler errorHandler
    )
    {
        super(owner, fence, callbackClassLoader, delegate, closeTimeout, errorHandler);
        this.scheduledDelegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
    {
        this.ensureScheduledAccepting();
        return this.scheduledDelegate.schedule(this.wrapScheduled(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
    {
        this.ensureScheduledAccepting();
        return this.scheduledDelegate.schedule(this.wrapSubmitted(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command,
        long initialDelay,
        long period,
        TimeUnit unit
    )
    {
        this.ensureScheduledAccepting();
        return this.scheduledDelegate.scheduleAtFixedRate(
            this.wrapScheduled(command),
            initialDelay,
            period,
            unit
        );
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command,
        long initialDelay,
        long delay,
        TimeUnit unit
    )
    {
        this.ensureScheduledAccepting();
        return this.scheduledDelegate.scheduleWithFixedDelay(
            this.wrapScheduled(command),
            initialDelay,
            delay,
            unit
        );
    }

    private void ensureScheduledAccepting()
    {
        if (this.isShutdown() || !this.fence().isOpen())
        {
            throw new RejectedExecutionException(
                "Plugin scheduler is not accepting work for " + this.owner()
            );
        }
    }
}
