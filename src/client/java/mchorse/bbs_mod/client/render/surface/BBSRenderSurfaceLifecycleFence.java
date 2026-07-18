package mchorse.bbs_mod.client.render.surface;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Separates external UI/session invalidation from internal capture-stream
 * generations. A pending teardown prevents an already-entered capture from
 * restarting itself with old source pixels after an off-thread invalidation.
 */
final class BBSRenderSurfaceLifecycleFence
{
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong pendingTeardownEpoch = new AtomicLong();

    long captureEpoch()
    {
        return this.epoch.get();
    }

    boolean isCurrent(long expectedEpoch)
    {
        return this.pendingTeardownEpoch.get() == 0L && this.epoch.get() == expectedEpoch;
    }

    synchronized boolean runIfCurrent(long expectedEpoch, Runnable action)
    {
        if (!this.isCurrent(expectedEpoch))
        {
            return false;
        }

        action.run();

        return true;
    }

    synchronized boolean getIfCurrent(long expectedEpoch, BooleanSupplier action)
    {
        return this.isCurrent(expectedEpoch) && action.getAsBoolean();
    }

    synchronized long invalidate(Runnable fenceWork)
    {
        long previousEpoch = this.epoch.get();

        if (previousEpoch == Long.MAX_VALUE)
        {
            throw new IllegalStateException("surface lifecycle epoch exhausted");
        }

        long teardownEpoch = previousEpoch + 1L;

        /* Publish pending first. A lock-free capture then observes either the
         * old epoch (which becomes stale) or a non-zero teardown marker; it can
         * never observe the replacement epoch as ready before teardown. */
        this.pendingTeardownEpoch.set(-teardownEpoch);
        this.epoch.set(teardownEpoch);

        try
        {
            fenceWork.run();
        }
        finally
        {
            this.pendingTeardownEpoch.compareAndSet(-teardownEpoch, teardownEpoch);
        }

        return teardownEpoch;
    }

    void applyPending(Runnable teardown)
    {
        long teardownEpoch = this.pendingTeardownEpoch.get();

        if (teardownEpoch > 0L)
        {
            this.apply(teardownEpoch, teardown);
        }
    }

    synchronized void apply(long teardownEpoch, Runnable teardown)
    {
        if (teardownEpoch <= 0L || this.pendingTeardownEpoch.get() != teardownEpoch)
        {
            return;
        }

        try
        {
            teardown.run();
        }
        finally
        {
            this.pendingTeardownEpoch.compareAndSet(teardownEpoch, 0L);
        }
    }
}
