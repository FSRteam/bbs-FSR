package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceDemand;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceFrame;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceListener;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceSubscription;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.plugin.runtime.PluginGenerationFence;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

public final class BBSRenderSurfaceRegistry
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-render-surface");
    private static final long DEMAND_REFRESH_NANOS = 50_000_000L;
    private static final long DEMAND_CACHE_MAX_AGE_NANOS = 1_000_000_000L;
    private static final long DEMAND_FAILURE_INITIAL_BACKOFF_NANOS = 250_000_000L;
    private static final long DEMAND_FAILURE_MAX_BACKOFF_NANOS = 30_000_000_000L;
    private static final long DEMAND_FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
    private static final long DEMAND_SLOW_NANOS = 10_000_000L;
    private static final long DEMAND_SLOW_LOG_INTERVAL_NANOS = 30_000_000_000L;
    private static final int DEMAND_WORKERS = 2;
    private static final int DEMAND_QUEUE_CAPACITY = 64;
    private static final Map<String, ListenerEntry> LISTENERS_BY_ADDON = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ListenerEntry> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<String, HotRoute> HOT_ROUTES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_REJECTION_LOG_NANOS = new AtomicLong();
    private static final ThreadPoolExecutor DEMAND_EXECUTOR = new ThreadPoolExecutor(
        DEMAND_WORKERS,
        DEMAND_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(DEMAND_QUEUE_CAPACITY),
        new DemandThreadFactory(),
        new ThreadPoolExecutor.AbortPolicy()
    );

    private BBSRenderSurfaceRegistry()
    {}

    public static BBSRegistrationResult register(BBSAddonDescriptor descriptor, BBSRenderSurfaceListener listener)
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();

        if (descriptor == null)
        {
            return BBSRegistrationResult.rejected(addonId, "addon descriptor is null");
        }

        if (!descriptor.capabilities().contains(BBSAddonCapability.CLIENT_RENDER))
        {
            return BBSRegistrationResult.rejected(addonId, "addon did not declare CLIENT_RENDER capability");
        }

        if (addonId == null || addonId.isBlank())
        {
            return BBSRegistrationResult.rejected("<blank>", "addon id is blank");
        }

        if (listener == null)
        {
            return BBSRegistrationResult.rejected(addonId, "render surface listener is null");
        }

        ListenerEntry entry = new ListenerEntry(addonId, listener);
        ListenerEntry existing = LISTENERS_BY_ADDON.putIfAbsent(addonId, entry);

        if (existing != null)
        {
            return BBSRegistrationResult.duplicate(addonId, existing.listener.getClass().getName());
        }

        LISTENERS.add(entry);
        scheduleDemandRefresh(entry);

        return BBSRegistrationResult.accepted(addonId);
    }

    /**
     * Stage a generation-owned listener without entering the permanent addon
     * registration map. The listener becomes routable only while its exact
     * generation fence is open.
     */
    public static BBSRenderSurfaceSubscription subscribe(
        PluginOwner owner,
        PluginGenerationFence generationFence,
        BBSRenderSurfaceListener listener
    )
    {
        String pluginId = owner == null ? "<unknown>" : owner.pluginId();

        if (owner == null)
        {
            return new HotSubscription(
                BBSRegistrationResult.rejected(pluginId, "plugin owner is null"),
                null
            );
        }

        if (generationFence == null)
        {
            return new HotSubscription(
                BBSRegistrationResult.rejected(pluginId, "plugin generation fence is null"),
                null
            );
        }

        if (!owner.equals(generationFence.owner()))
        {
            return new HotSubscription(
                BBSRegistrationResult.rejected(pluginId, "plugin owner does not match generation fence"),
                null
            );
        }

        if (listener == null)
        {
            return new HotSubscription(
                BBSRegistrationResult.rejected(pluginId, "render surface listener is null"),
                null
            );
        }

        HotRoute route;
        HotListenerEntry entry;
        boolean accepted;

        synchronized (HOT_ROUTES)
        {
            route = HOT_ROUTES.computeIfAbsent(pluginId, HotRoute::new);
            entry = new HotListenerEntry(route, owner, generationFence, listener);
            accepted = route.add(entry);
        }

        if (!accepted)
        {
            return new HotSubscription(
                BBSRegistrationResult.duplicate(pluginId, owner.toString()),
                null
            );
        }

        scheduleDemandRefresh(entry);

        return new HotSubscription(BBSRegistrationResult.accepted(pluginId), entry);
    }

    static BBSRenderSurfaceCapturePlan capturePlan(Set<BBSRenderSurfaceKind> availableKinds)
    {
        if (availableKinds.isEmpty() || (LISTENERS.isEmpty() && HOT_ROUTES.isEmpty()))
        {
            return null;
        }

        EnumSet<BBSRenderSurfaceKind> kinds = EnumSet.noneOf(BBSRenderSurfaceKind.class);
        int maxWidth = 0;
        int maxHeight = 0;
        int framesPerSecond = 0;
        int jpegQuality = 0;

        for (ListenerEntry entry : LISTENERS)
        {
            scheduleDemandRefresh(entry);
            BBSRenderSurfaceDemand demand = entry.demandSnapshot(System.nanoTime());

            if (!demand.isActive())
            {
                continue;
            }

            boolean contributes = false;

            for (BBSRenderSurfaceKind kind : demand.kinds())
            {
                if (availableKinds.contains(kind))
                {
                    kinds.add(kind);
                    contributes = true;
                }
            }

            if (contributes)
            {
                maxWidth = Math.max(maxWidth, demand.maxWidth());
                maxHeight = Math.max(maxHeight, demand.maxHeight());
                framesPerSecond = Math.max(framesPerSecond, demand.framesPerSecond());
                jpegQuality = Math.max(jpegQuality, demand.jpegQuality());
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            HotListenerEntry entry = route.current();

            if (entry == null)
            {
                continue;
            }

            scheduleDemandRefresh(entry);
            BBSRenderSurfaceDemand demand = entry.demandSnapshot(System.nanoTime());

            if (!entry.current() || !demand.isActive())
            {
                continue;
            }

            boolean contributes = false;

            for (BBSRenderSurfaceKind kind : demand.kinds())
            {
                if (availableKinds.contains(kind))
                {
                    kinds.add(kind);
                    contributes = true;
                }
            }

            if (contributes)
            {
                maxWidth = Math.max(maxWidth, demand.maxWidth());
                maxHeight = Math.max(maxHeight, demand.maxHeight());
                framesPerSecond = Math.max(framesPerSecond, demand.framesPerSecond());
                jpegQuality = Math.max(jpegQuality, demand.jpegQuality());
            }
        }

        if (kinds.isEmpty())
        {
            return null;
        }

        return new BBSRenderSurfaceCapturePlan(kinds, maxWidth, maxHeight, framesPerSecond, jpegQuality);
    }

    static void publish(
        BBSRenderSurfaceFrame frame,
        LongPredicate currentGeneration,
        LongPredicate callbackAdmission
    )
    {
        for (ListenerEntry entry : LISTENERS)
        {
            if (currentGeneration == null || !currentGeneration.test(frame.generation()))
            {
                return;
            }

            scheduleDemandRefresh(entry);
            BBSRenderSurfaceDemand demand = entry.demandSnapshot(System.nanoTime());

            if (!intersects(demand.kinds(), frame.kinds()))
            {
                continue;
            }

            /* Recheck after demand selection so a slow earlier listener can
             * never let a later listener start after session invalidation. */
            if (callbackAdmission == null || !callbackAdmission.test(frame.generation()))
            {
                return;
            }

            try
            {
                entry.listener.onFrame(frame);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-render-surface] frame listener failed for addon '{}'", entry.addonId, e);
            }
        }

        for (HotRoute route : HOT_ROUTES.values())
        {
            HotListenerEntry entry = route.current();

            if (entry == null)
            {
                continue;
            }

            if (currentGeneration == null || !currentGeneration.test(frame.generation()))
            {
                return;
            }

            scheduleDemandRefresh(entry);
            BBSRenderSurfaceDemand demand = entry.demandSnapshot(System.nanoTime());

            if (!entry.current() || !intersects(demand.kinds(), frame.kinds()))
            {
                continue;
            }

            if (callbackAdmission == null || !callbackAdmission.test(frame.generation()) || !entry.current())
            {
                return;
            }

            var lease = entry.generationFence.acquire();

            if (lease == null)
            {
                continue;
            }

            try (lease)
            {
                if (!entry.current())
                {
                    continue;
                }

                entry.listener.onFrame(frame);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error(
                    "[bbs-client-render-surface] frame listener failed for plugin generation '{}'",
                    entry.owner,
                    e
                );
            }
        }
    }

    private static void scheduleDemandRefresh(ListenerEntry entry)
    {
        long now = System.nanoTime();

        if (!entry.refreshDue(now) || !entry.refreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        try
        {
            DEMAND_EXECUTOR.execute(() -> refreshDemand(entry));
        }
        catch (RejectedExecutionException e)
        {
            entry.nextRefreshNanos = now + DEMAND_REFRESH_NANOS;
            entry.refreshInFlight.set(false);
            logExecutorRejection(now);
        }
    }

    private static void refreshDemand(ListenerEntry entry)
    {
        long started = System.nanoTime();

        try
        {
            BBSRenderSurfaceDemand demand = entry.listener.demand();

            if (demand == null)
            {
                throw new IllegalStateException("demand listener returned null");
            }

            long finished = System.nanoTime();

            entry.cachedDemand = demand;
            entry.lastSuccessNanos = finished;
            entry.failures = 0;
            entry.suppressedFailures = 0;
            entry.nextRefreshNanos = finished + DEMAND_REFRESH_NANOS;

            if (finished - started >= DEMAND_SLOW_NANOS && entry.slowLogDue(finished))
            {
                entry.nextSlowLogNanos = finished + DEMAND_SLOW_LOG_INTERVAL_NANOS;
                LOGGER.warn(
                    "[bbs-client-render-surface] slow demand listener for addon '{}' took {} ms; render capture used its cached value",
                    entry.addonId,
                    TimeUnit.NANOSECONDS.toMillis(finished - started)
                );
            }
        }
        catch (Exception | LinkageError e)
        {
            long finished = System.nanoTime();
            long backoff = entry.recordFailure();

            entry.cachedDemand = BBSRenderSurfaceDemand.none();
            entry.lastSuccessNanos = 0L;
            entry.nextRefreshNanos = finished + backoff;

            if (entry.failureLogDue(finished))
            {
                int suppressed = entry.suppressedFailures;

                entry.suppressedFailures = 0;
                entry.nextFailureLogNanos = finished + Math.max(DEMAND_FAILURE_LOG_INTERVAL_NANOS, backoff);
                LOGGER.error(
                    "[bbs-client-render-surface] demand listener failed for addon '{}'; retrying in {} ms ({} failures suppressed)",
                    entry.addonId,
                    TimeUnit.NANOSECONDS.toMillis(backoff),
                    suppressed,
                    e
                );
            }
            else
            {
                entry.suppressedFailures++;
            }
        }
        finally
        {
            entry.refreshInFlight.set(false);
        }
    }

    private static void scheduleDemandRefresh(HotListenerEntry entry)
    {
        long now = System.nanoTime();

        if (!entry.current() || !entry.refreshDue(now) || !entry.refreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        try
        {
            DEMAND_EXECUTOR.execute(() -> refreshDemand(entry));
        }
        catch (RejectedExecutionException e)
        {
            entry.nextRefreshNanos = now + DEMAND_REFRESH_NANOS;
            entry.refreshInFlight.set(false);
            logExecutorRejection(now);
        }
    }

    private static void refreshDemand(HotListenerEntry entry)
    {
        long started = System.nanoTime();
        var lease = entry.generationFence.acquire();

        if (lease == null)
        {
            entry.refreshInFlight.set(false);
            return;
        }

        try (lease)
        {
            if (!entry.current())
            {
                entry.clearDemand();
                return;
            }

            BBSRenderSurfaceDemand demand = entry.listener.demand();

            if (demand == null)
            {
                throw new IllegalStateException("demand listener returned null");
            }

            long finished = System.nanoTime();

            if (!entry.current())
            {
                entry.clearDemand();
                return;
            }

            entry.cachedDemand = demand;
            entry.lastSuccessNanos = finished;
            entry.failures = 0;
            entry.suppressedFailures = 0;
            entry.nextRefreshNanos = finished + DEMAND_REFRESH_NANOS;

            if (finished - started >= DEMAND_SLOW_NANOS && entry.slowLogDue(finished))
            {
                entry.nextSlowLogNanos = finished + DEMAND_SLOW_LOG_INTERVAL_NANOS;
                LOGGER.warn(
                    "[bbs-client-render-surface] slow demand listener for plugin generation '{}' took {} ms; render capture used its cached value",
                    entry.owner,
                    TimeUnit.NANOSECONDS.toMillis(finished - started)
                );
            }
        }
        catch (Exception | LinkageError e)
        {
            long finished = System.nanoTime();
            long backoff = entry.recordFailure();

            entry.clearDemand();
            entry.nextRefreshNanos = finished + backoff;

            if (entry.current() && entry.failureLogDue(finished))
            {
                int suppressed = entry.suppressedFailures;

                entry.suppressedFailures = 0;
                entry.nextFailureLogNanos = finished + Math.max(DEMAND_FAILURE_LOG_INTERVAL_NANOS, backoff);
                LOGGER.error(
                    "[bbs-client-render-surface] demand listener failed for plugin generation '{}'; retrying in {} ms ({} failures suppressed)",
                    entry.owner,
                    TimeUnit.NANOSECONDS.toMillis(backoff),
                    suppressed,
                    e
                );
            }
            else if (entry.current())
            {
                entry.suppressedFailures++;
            }
        }
        finally
        {
            entry.refreshInFlight.set(false);
        }
    }

    private static void logExecutorRejection(long now)
    {
        long previous = NEXT_REJECTION_LOG_NANOS.get();

        if ((previous == 0L || now - previous >= 0L)
            && NEXT_REJECTION_LOG_NANOS.compareAndSet(previous, now + DEMAND_FAILURE_LOG_INTERVAL_NANOS))
        {
            LOGGER.warn("[bbs-client-render-surface] bounded demand sampler queue is full; retaining cached demand");
        }
    }

    private static long failureBackoff(int failures)
    {
        long backoff = DEMAND_FAILURE_INITIAL_BACKOFF_NANOS;

        for (int i = 1; i < failures && backoff < DEMAND_FAILURE_MAX_BACKOFF_NANOS; i++)
        {
            backoff = Math.min(DEMAND_FAILURE_MAX_BACKOFF_NANOS, backoff * 2L);
        }

        return backoff;
    }

    private static boolean intersects(Set<BBSRenderSurfaceKind> a, Set<BBSRenderSurfaceKind> b)
    {
        for (BBSRenderSurfaceKind kind : a)
        {
            if (b.contains(kind))
            {
                return true;
            }
        }

        return false;
    }

    static boolean demandRefreshIdle(String addonId)
    {
        ListenerEntry entry = LISTENERS_BY_ADDON.get(addonId);

        return entry != null && !entry.refreshInFlight.get();
    }

    static int demandFailureCount(String addonId)
    {
        ListenerEntry entry = LISTENERS_BY_ADDON.get(addonId);

        return entry == null ? 0 : entry.failures;
    }

    static boolean demandRefreshIdle(PluginOwner owner)
    {
        HotRoute route = owner == null ? null : HOT_ROUTES.get(owner.pluginId());
        HotListenerEntry entry = route == null ? null : route.get(owner);

        return entry != null && !entry.refreshInFlight.get();
    }

    static int demandFailureCount(PluginOwner owner)
    {
        HotRoute route = owner == null ? null : HOT_ROUTES.get(owner.pluginId());
        HotListenerEntry entry = route == null ? null : route.get(owner);

        return entry == null ? 0 : entry.failures;
    }

    private static final class ListenerEntry
    {
        private final String addonId;
        private final BBSRenderSurfaceListener listener;
        private final AtomicBoolean refreshInFlight = new AtomicBoolean();
        private volatile BBSRenderSurfaceDemand cachedDemand = BBSRenderSurfaceDemand.none();
        private volatile long nextRefreshNanos;
        private volatile long lastSuccessNanos;
        private volatile int failures;
        private int suppressedFailures;
        private long nextFailureLogNanos;
        private long nextSlowLogNanos;

        private ListenerEntry(String addonId, BBSRenderSurfaceListener listener)
        {
            this.addonId = addonId;
            this.listener = listener;
        }

        private boolean refreshDue(long now)
        {
            return this.nextRefreshNanos == 0L || now - this.nextRefreshNanos >= 0L;
        }

        private BBSRenderSurfaceDemand demandSnapshot(long now)
        {
            long sampledAt = this.lastSuccessNanos;
            long age = now - sampledAt;

            return sampledAt != 0L && age >= 0L && age <= DEMAND_CACHE_MAX_AGE_NANOS
                ? this.cachedDemand
                : BBSRenderSurfaceDemand.none();
        }

        private long recordFailure()
        {
            this.failures = Math.min(31, this.failures + 1);

            return failureBackoff(this.failures);
        }

        private boolean failureLogDue(long now)
        {
            return this.nextFailureLogNanos == 0L || now - this.nextFailureLogNanos >= 0L;
        }

        private boolean slowLogDue(long now)
        {
            return this.nextSlowLogNanos == 0L || now - this.nextSlowLogNanos >= 0L;
        }
    }

    private static final class HotRoute
    {
        private final String pluginId;
        private final Map<Long, HotListenerEntry> generations = new ConcurrentHashMap<>();

        private HotRoute(String pluginId)
        {
            this.pluginId = pluginId;
        }

        private boolean add(HotListenerEntry entry)
        {
            return this.generations.putIfAbsent(entry.owner.generation(), entry) == null;
        }

        private HotListenerEntry get(PluginOwner owner)
        {
            HotListenerEntry entry = this.generations.get(owner.generation());

            return entry != null && entry.owner.equals(owner) ? entry : null;
        }

        private boolean isCurrent(HotListenerEntry entry)
        {
            return entry != null && this.current() == entry;
        }

        private HotListenerEntry current()
        {
            HotListenerEntry selected = null;

            for (HotListenerEntry candidate : this.generations.values())
            {
                if (!candidate.dispatchable())
                {
                    continue;
                }

                if (selected == null || candidate.owner.generation() > selected.owner.generation())
                {
                    selected = candidate;
                }
            }

            return selected;
        }

        private void remove(HotListenerEntry entry)
        {
            synchronized (HOT_ROUTES)
            {
                this.generations.remove(entry.owner.generation(), entry);

                if (this.generations.isEmpty())
                {
                    HOT_ROUTES.remove(this.pluginId, this);
                }
            }
        }
    }

    private static final class HotListenerEntry
    {
        private final HotRoute route;
        private final PluginOwner owner;
        private final PluginGenerationFence generationFence;
        private final BBSRenderSurfaceListener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean refreshInFlight = new AtomicBoolean();
        private volatile BBSRenderSurfaceDemand cachedDemand = BBSRenderSurfaceDemand.none();
        private volatile long nextRefreshNanos;
        private volatile long lastSuccessNanos;
        private volatile int failures;
        private int suppressedFailures;
        private long nextFailureLogNanos;
        private long nextSlowLogNanos;

        private HotListenerEntry(
            HotRoute route,
            PluginOwner owner,
            PluginGenerationFence generationFence,
            BBSRenderSurfaceListener listener
        )
        {
            this.route = route;
            this.owner = owner;
            this.generationFence = generationFence;
            this.listener = listener;
        }

        private boolean dispatchable()
        {
            return !this.closed.get() && this.generationFence.isOpen();
        }

        private boolean current()
        {
            return this.dispatchable() && this.route.isCurrent(this);
        }

        private boolean refreshDue(long now)
        {
            return this.nextRefreshNanos == 0L || now - this.nextRefreshNanos >= 0L;
        }

        private BBSRenderSurfaceDemand demandSnapshot(long now)
        {
            long sampledAt = this.lastSuccessNanos;
            long age = now - sampledAt;

            return this.current() && sampledAt != 0L && age >= 0L && age <= DEMAND_CACHE_MAX_AGE_NANOS
                ? this.cachedDemand
                : BBSRenderSurfaceDemand.none();
        }

        private long recordFailure()
        {
            this.failures = Math.min(31, this.failures + 1);

            return failureBackoff(this.failures);
        }

        private boolean failureLogDue(long now)
        {
            return this.nextFailureLogNanos == 0L || now - this.nextFailureLogNanos >= 0L;
        }

        private boolean slowLogDue(long now)
        {
            return this.nextSlowLogNanos == 0L || now - this.nextSlowLogNanos >= 0L;
        }

        private void clearDemand()
        {
            this.cachedDemand = BBSRenderSurfaceDemand.none();
            this.lastSuccessNanos = 0L;
        }

        private void close()
        {
            if (!this.closed.compareAndSet(false, true))
            {
                return;
            }

            this.route.remove(this);
            this.clearDemand();
        }
    }

    private static final class HotSubscription implements BBSRenderSurfaceSubscription
    {
        private final BBSRegistrationResult registration;
        private final HotListenerEntry entry;

        private HotSubscription(BBSRegistrationResult registration, HotListenerEntry entry)
        {
            this.registration = registration;
            this.entry = entry;
        }

        @Override
        public BBSRegistrationResult registration()
        {
            return this.registration;
        }

        @Override
        public boolean active()
        {
            return this.entry != null && this.entry.current();
        }

        @Override
        public void close()
        {
            if (this.entry != null)
            {
                this.entry.close();
            }
        }
    }

    private static final class DemandThreadFactory implements ThreadFactory
    {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(runnable, "bbs-surface-demand-" + this.sequence.incrementAndGet());

            thread.setDaemon(true);

            return thread;
        }
    }
}
