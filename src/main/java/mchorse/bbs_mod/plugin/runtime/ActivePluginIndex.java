package mchorse.bbs_mod.plugin.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-root active generation index.
 *
 * <p>All capability adapters should read this root once and then acquire a
 * generation lease.  Replacement updates one immutable map reference, so a
 * reader never observes broker/event/resource maps from different generations
 * or an intermediate empty route.</p>
 */
public final class ActivePluginIndex<T> implements AutoCloseable
{
    private final AtomicReference<Root<T>> root = new AtomicReference<>(Root.empty());
    private final Object commitMonitor = new Object();
    private boolean closed;

    public Root<T> snapshot()
    {
        return this.root.get();
    }

    public boolean isClosed()
    {
        synchronized (this.commitMonitor)
        {
            return this.closed;
        }
    }

    public ActivePluginGeneration<T> active(String pluginId)
    {
        if (pluginId == null)
        {
            return null;
        }

        return this.root.get().generations().get(pluginId);
    }

    /**
     * Acquire from the same root snapshot used by the route lookup.  If a
     * concurrent swap starts draining that snapshot, retry against the new
     * root instead of returning a transient empty route.
     */
    public PluginGenerationLease<T> acquire(String pluginId)
    {
        if (pluginId == null)
        {
            return null;
        }

        synchronized (this.commitMonitor)
        {
            ActivePluginGeneration<T> generation = this.root.get().generations().get(pluginId);

            return generation == null ? null : generation.acquire();
        }
    }

    public PluginGenerationLease<T> acquire(PluginOwner owner)
    {
        Objects.requireNonNull(owner, "owner");

        synchronized (this.commitMonitor)
        {
            ActivePluginGeneration<T> generation = this.root.get().generations().get(owner.pluginId());

            if (generation == null || !generation.owner().equals(owner))
            {
                return null;
            }

            return generation.acquire();
        }
    }

    /**
     * Atomically publish a candidate and start draining the incumbent.  The
     * candidate is activated before publication, but it is unreachable until
     * the single root reference is replaced.
     */
    public ActivePluginGeneration<T> replace(ActivePluginGeneration<T> candidate)
    {
        return this.replace(null, candidate);
    }

    /** Replace only if the expected incumbent is still the active generation. */
    public ActivePluginGeneration<T> replace(
        PluginOwner expectedIncumbent,
        ActivePluginGeneration<T> candidate
    )
    {
        Objects.requireNonNull(candidate, "candidate");
        PluginOwner owner = candidate.owner();

        synchronized (this.commitMonitor)
        {
            this.ensureOpen();

            Root<T> previousRoot = this.root.get();
            ActivePluginGeneration<T> incumbent = previousRoot.generations().get(owner.pluginId());

            if (expectedIncumbent != null
                && (incumbent == null || !expectedIncumbent.equals(incumbent.owner())))
            {
                throw new IllegalStateException(
                    "Active generation changed for " + owner.pluginId()
                );
            }

            if (incumbent == candidate)
            {
                return incumbent;
            }

            if (incumbent != null && incumbent.owner().equals(owner))
            {
                throw new IllegalArgumentException("Generation already active for " + owner);
            }

            if (incumbent != null && owner.generation() <= incumbent.owner().generation())
            {
                throw new IllegalArgumentException(
                    "Replacement generation must increase for " + owner.pluginId()
                );
            }

            candidate.activate();

            Map<String, ActivePluginGeneration<T>> generations =
                new LinkedHashMap<>(previousRoot.generations());
            generations.put(owner.pluginId(), candidate);
            if (incumbent != null)
            {
                incumbent.beginDrain();
            }

            this.root.set(new Root<>(previousRoot.revision() + 1L, generations));

            return incumbent;
        }
    }

    public ActivePluginGeneration<T> remove(String pluginId)
    {
        return this.remove(pluginId, null);
    }

    /** Remove only the expected owner, protecting a newer generation. */
    public ActivePluginGeneration<T> remove(String pluginId, PluginOwner expectedOwner)
    {
        Objects.requireNonNull(pluginId, "pluginId");

        synchronized (this.commitMonitor)
        {
            this.ensureOpen();

            Root<T> previousRoot = this.root.get();
            ActivePluginGeneration<T> incumbent = previousRoot.generations().get(pluginId);

            if (incumbent == null)
            {
                return null;
            }

            if (expectedOwner != null && !expectedOwner.equals(incumbent.owner()))
            {
                return null;
            }

            Map<String, ActivePluginGeneration<T>> generations =
                new LinkedHashMap<>(previousRoot.generations());
            generations.remove(pluginId);
            this.root.set(new Root<>(previousRoot.revision() + 1L, generations));
            incumbent.beginDrain();

            return incumbent;
        }
    }

    @Override
    public void close()
    {
        List<ActivePluginGeneration<T>> generations;

        synchronized (this.commitMonitor)
        {
            if (this.closed)
            {
                return;
            }

            this.closed = true;
            generations = new ArrayList<>(this.root.get().generations().values());
            this.root.set(new Root<>(this.root.get().revision() + 1L, Map.of()));

            for (ActivePluginGeneration<T> generation : generations)
            {
                generation.beginDrain();
            }
        }

        Throwable failure = null;

        for (ActivePluginGeneration<T> generation : generations)
        {
            try
            {
                generation.retire();
            }
            catch (Throwable throwable)
            {
                failure = PluginFailures.append(failure, throwable);
            }
        }

        if (failure != null)
        {
            PluginFailures.throwIfPresent("Failed to close the active plugin index", failure);
        }
    }

    private void ensureOpen()
    {
        if (this.closed)
        {
            throw new IllegalStateException("Active plugin index is closed");
        }
    }

    /** Immutable root value exchanged by the index. */
    public record Root<T>(long revision, Map<String, ActivePluginGeneration<T>> generations)
    {
        public Root
        {
            if (revision < 0L)
            {
                throw new IllegalArgumentException("Root revision cannot be negative");
            }

            Objects.requireNonNull(generations, "generations");
            generations = Map.copyOf(generations);
        }

        private static <T> Root<T> empty()
        {
            return new Root<>(0L, Map.of());
        }
    }
}
