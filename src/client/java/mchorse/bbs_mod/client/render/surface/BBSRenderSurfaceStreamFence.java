package mchorse.bbs_mod.client.render.surface;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-lifetime sequence allocator plus a generation fence for asynchronous
 * GPU readback and JPEG work.
 */
final class BBSRenderSurfaceStreamFence
{
    private static final AtomicLong CLIENT_SEQUENCE = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();

    long beginStream()
    {
        return incrementPositive(this.generation, "surface generation exhausted");
    }

    long invalidate()
    {
        return incrementPositive(this.generation, "surface generation exhausted");
    }

    BBSRenderSurfaceStamp issue(long activeGeneration)
    {
        if (!this.isCurrent(activeGeneration))
        {
            throw new IllegalStateException("surface stream generation is stale");
        }

        return new BBSRenderSurfaceStamp(
            activeGeneration,
            incrementPositive(CLIENT_SEQUENCE, "surface sequence exhausted")
        );
    }

    boolean isCurrent(long candidate)
    {
        return candidate > 0L && this.generation.get() == candidate;
    }

    /** Atomically linearizes listener callback admission against invalidate(). */
    boolean tryStartCallback(long candidate)
    {
        return candidate > 0L && this.generation.compareAndSet(candidate, candidate);
    }

    long currentGeneration()
    {
        return this.generation.get();
    }

    private static long incrementPositive(AtomicLong value, String exhaustedMessage)
    {
        while (true)
        {
            long previous = value.get();

            if (previous == Long.MAX_VALUE)
            {
                throw new IllegalStateException(exhaustedMessage);
            }

            long next = previous + 1L;

            if (value.compareAndSet(previous, next))
            {
                return next;
            }
        }
    }
}
