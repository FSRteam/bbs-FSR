package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.ui.BBSUiAssetBytes;
import mchorse.bbs_mod.api.client.ui.BBSUiFrame;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorListener;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorSubscription;
import mchorse.bbs_mod.api.client.ui.BBSUiSessionInfo;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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
import java.util.concurrent.locks.LockSupport;

/**
 * Owns UI mirror registrations and the render-thread-safe handoff boundary.
 * No addon callback is invoked by a render, client, or asset-reader thread.
 */
public final class BBSUiMirrorRegistry
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-ui-mirror");
    private static final int MAX_PENDING_CALLBACKS = 64;
    private static final long SLOW_CALLBACK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
    private static final long INITIAL_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long MAX_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final long PROBLEM_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final Object SESSION_LOCK = new Object();
    private static final Map<String, ListenerEntry> LISTENERS_BY_ADDON = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ListenerEntry> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
    private static volatile BBSUiSessionInfo currentSession;

    private BBSUiMirrorRegistry()
    {}

    /** Compatibility registration: old API 2.0 consumers remain permanently active. */
    public static BBSRegistrationResult register(BBSAddonDescriptor descriptor, BBSUiMirrorListener listener)
    {
        return registerEntry(descriptor, listener, true).result;
    }

    /** Runtime-demand registration used by viewers which come and go. */
    public static BBSUiMirrorSubscription subscribe(BBSAddonDescriptor descriptor, BBSUiMirrorListener listener)
    {
        Registration registration = registerEntry(descriptor, listener, false);

        return new Subscription(registration.result, registration.entry);
    }

    private static Registration registerEntry(
        BBSAddonDescriptor descriptor,
        BBSUiMirrorListener listener,
        boolean initiallyActive
    )
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();

        if (descriptor == null)
        {
            return Registration.rejected(BBSRegistrationResult.rejected(addonId, "addon descriptor is null"));
        }

        if (!descriptor.capabilities().contains(BBSAddonCapability.CLIENT_UI))
        {
            return Registration.rejected(BBSRegistrationResult.rejected(addonId, "addon did not declare CLIENT_UI capability"));
        }

        if (addonId == null || addonId.isBlank())
        {
            return Registration.rejected(BBSRegistrationResult.rejected("<blank>", "addon id is blank"));
        }

        if (listener == null)
        {
            return Registration.rejected(BBSRegistrationResult.rejected(addonId, "UI mirror listener is null"));
        }

        ListenerEntry entry = new ListenerEntry(addonId, listener, initiallyActive);

        synchronized (SESSION_LOCK)
        {
            ListenerEntry existing = LISTENERS_BY_ADDON.putIfAbsent(addonId, entry);

            if (existing != null)
            {
                entry.forceShutdown();

                return Registration.rejected(BBSRegistrationResult.duplicate(addonId, existing.listener.getClass().getName()));
            }

            LISTENERS.add(entry);

            if (currentSession != null)
            {
                entry.offerSessionOpened(currentSession);
            }
        }

        return new Registration(BBSRegistrationResult.accepted(addonId), entry);
    }

    /** Retained internal name now reports active capture demand, not registrations. */
    public static boolean hasListeners()
    {
        return hasActiveDemand();
    }

    public static boolean hasActiveDemand()
    {
        long now = System.nanoTime();

        for (ListenerEntry entry : LISTENERS)
        {
            if (entry.captureEnabled(now))
            {
                return true;
            }
        }

        return false;
    }

    static boolean needsAsset(String assetId)
    {
        long now = System.nanoTime();

        for (ListenerEntry entry : LISTENERS)
        {
            if (entry.captureEnabled(now) && entry.needsAsset(assetId))
            {
                return true;
            }
        }

        return false;
    }

    static boolean publishAsset(BBSUiAssetBytes asset)
    {
        boolean accepted = true;
        long now = System.nanoTime();

        for (ListenerEntry entry : LISTENERS)
        {
            if (entry.captureEnabled(now) && !entry.offerAsset(asset))
            {
                accepted = false;
            }
        }

        return accepted;
    }

    static void resetAssets()
    {
        for (ListenerEntry entry : LISTENERS)
        {
            entry.resetAssets();
        }
    }

    static void openSession(BBSUiSessionInfo session)
    {
        synchronized (SESSION_LOCK)
        {
            currentSession = session;

            for (ListenerEntry entry : LISTENERS)
            {
                entry.offerSessionOpened(session);
            }
        }
    }

    static void updateSession(BBSUiSessionInfo session)
    {
        synchronized (SESSION_LOCK)
        {
            BBSUiSessionInfo current = currentSession;

            if (current != null && current.sessionId() == session.sessionId())
            {
                currentSession = session;
            }
        }
    }

    static void publish(BBSUiFrame frame)
    {
        synchronized (SESSION_LOCK)
        {
            BBSUiSessionInfo current = currentSession;

            if (current == null || current.sessionId() != frame.sessionId())
            {
                return;
            }

            for (ListenerEntry entry : LISTENERS)
            {
                entry.offerFrame(frame);
            }
        }
    }

    static void closeSession(long sessionId)
    {
        synchronized (SESSION_LOCK)
        {
            BBSUiSessionInfo current = currentSession;

            if (current == null || current.sessionId() != sessionId)
            {
                return;
            }

            currentSession = null;

            for (ListenerEntry entry : LISTENERS)
            {
                entry.offerSessionClosed(sessionId, false);
            }
        }
    }

    private static void unregister(ListenerEntry entry)
    {
        synchronized (SESSION_LOCK)
        {
            if (!entry.closeRegistration())
            {
                return;
            }

            LISTENERS_BY_ADDON.remove(entry.addonId, entry);
            LISTENERS.remove(entry);
            entry.deactivateAndDropDemandWork();

            if (currentSession != null)
            {
                entry.offerSessionClosed(currentSession.sessionId(), true);
            }

            entry.shutdownAfterDrain();
        }
    }

    /** Deterministic test seam; production code never waits for addon callbacks. */
    static boolean awaitCallbacksForTests(long timeoutMillis) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        for (ListenerEntry entry : LISTENERS)
        {
            long remaining = deadline - System.nanoTime();

            if (remaining <= 0L || !entry.awaitIdle(remaining))
            {
                return false;
            }
        }

        return true;
    }

    /** Deterministic proof that a closed demand subscription released its worker. */
    static boolean awaitSubscriptionClosedForTests(
        BBSUiMirrorSubscription subscription,
        long timeoutMillis
    ) throws InterruptedException
    {
        if (!(subscription instanceof Subscription owned) || owned.entry == null)
        {
            return true;
        }

        return owned.entry.executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Clears the process-global registry between deterministic test cases. */
    static void resetForTests()
    {
        List<ListenerEntry> entries;

        synchronized (SESSION_LOCK)
        {
            currentSession = null;
            entries = new ArrayList<>(LISTENERS);
            LISTENERS.clear();
            LISTENERS_BY_ADDON.clear();
        }

        for (ListenerEntry entry : entries)
        {
            entry.forceShutdown();
        }
    }

    private static long failureBackoff(int failures)
    {
        long backoff = INITIAL_BACKOFF_NANOS;

        for (int i = 1; i < failures && backoff < MAX_BACKOFF_NANOS; i++)
        {
            backoff = Math.min(MAX_BACKOFF_NANOS, backoff * 2L);
        }

        return backoff;
    }

    private static final class Registration
    {
        private final BBSRegistrationResult result;
        private final ListenerEntry entry;

        private Registration(BBSRegistrationResult result, ListenerEntry entry)
        {
            this.result = result;
            this.entry = entry;
        }

        private static Registration rejected(BBSRegistrationResult result)
        {
            return new Registration(result, null);
        }
    }

    private static final class Subscription implements BBSUiMirrorSubscription
    {
        private final BBSRegistrationResult registration;
        private final ListenerEntry entry;

        private Subscription(BBSRegistrationResult registration, ListenerEntry entry)
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
            return this.entry != null && this.entry.active();
        }

        @Override
        public void setActive(boolean active)
        {
            if (this.entry != null)
            {
                this.entry.setActive(active);
            }
        }

        @Override
        public void close()
        {
            if (this.entry != null)
            {
                unregister(this.entry);
            }
        }
    }

    private static final class ListenerEntry
    {
        private final String addonId;
        private final BBSUiMirrorListener listener;
        private final AtomicBoolean requestedActive;
        private final AtomicBoolean registrationClosed = new AtomicBoolean();
        private final Set<String> deliveredAssets = ConcurrentHashMap.newKeySet();
        private final Object lock = new Object();
        private final ArrayDeque<Delivery> pending = new ArrayDeque<>();
        private final Map<String, Long> pendingAssets = new HashMap<>();
        private final ThreadPoolExecutor executor;
        private FrameDelivery latestFrame;
        private boolean drainScheduled;
        private boolean shutdownAfterDrain;
        private long assetGeneration = 1L;
        private int failures;
        private int suppressedProblems;
        private long nextProblemLogNanos;
        private long slowMarkedStartNanos;
        private volatile long callbackStartedNanos;
        private volatile String callbackPhase;
        private volatile long quarantineUntilNanos;

        private ListenerEntry(String addonId, BBSUiMirrorListener listener, boolean initiallyActive)
        {
            this.addonId = addonId;
            this.listener = listener;
            this.requestedActive = new AtomicBoolean(initiallyActive);
            this.executor = new ThreadPoolExecutor(
                0,
                1,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ListenerThreadFactory(addonId),
                new ThreadPoolExecutor.AbortPolicy()
            );
            this.executor.allowCoreThreadTimeOut(true);
        }

        private boolean active()
        {
            return !this.registrationClosed.get() && this.requestedActive.get();
        }

        private void setActive(boolean active)
        {
            if (this.registrationClosed.get())
            {
                return;
            }

            boolean previous = this.requestedActive.getAndSet(active);

            if (previous && !active)
            {
                this.deactivateAndDropDemandWork();
            }
        }

        private boolean closeRegistration()
        {
            return this.registrationClosed.compareAndSet(false, true);
        }

        private boolean captureEnabled(long now)
        {
            if (!this.active())
            {
                return false;
            }

            long started = this.callbackStartedNanos;

            if (started != 0L && now - started >= SLOW_CALLBACK_NANOS)
            {
                this.markSlowCallback(started, this.callbackPhase, now - started, now);

                return false;
            }

            return now - this.quarantineUntilNanos >= 0L;
        }

        private boolean needsAsset(String assetId)
        {
            synchronized (this.lock)
            {
                return this.active()
                    && !this.deliveredAssets.contains(assetId)
                    && !this.pendingAssets.containsKey(assetId);
            }
        }

        private boolean offerAsset(BBSUiAssetBytes asset)
        {
            String assetId = asset.asset().id();

            synchronized (this.lock)
            {
                if (!this.active() || System.nanoTime() - this.quarantineUntilNanos < 0L)
                {
                    return true;
                }

                if (this.deliveredAssets.contains(assetId) || this.pendingAssets.containsKey(assetId))
                {
                    return true;
                }

                if (this.pending.size() >= MAX_PENDING_CALLBACKS && !this.removeFirstFrameLocked())
                {
                    return false;
                }

                long generation = this.assetGeneration;

                this.pendingAssets.put(assetId, generation);
                this.pending.addLast(new AssetDelivery(assetId, generation, asset));
                this.scheduleDrainLocked();

                return true;
            }
        }

        private void offerFrame(BBSUiFrame frame)
        {
            long now = System.nanoTime();

            if (!this.captureEnabled(now))
            {
                return;
            }

            synchronized (this.lock)
            {
                if (!this.active() || now - this.quarantineUntilNanos < 0L)
                {
                    return;
                }

                if (this.latestFrame != null && this.latestFrame.frame.sessionId() == frame.sessionId())
                {
                    this.latestFrame.frame = frame;

                    return;
                }

                if (this.pending.size() >= MAX_PENDING_CALLBACKS && !this.removeFirstFrameLocked())
                {
                    return;
                }

                FrameDelivery delivery = new FrameDelivery(frame);

                this.latestFrame = delivery;
                this.pending.addLast(delivery);
                this.scheduleDrainLocked();
            }
        }

        private void offerSessionOpened(BBSUiSessionInfo session)
        {
            this.offerLifecycle(new SessionOpenedDelivery(session), false);
        }

        private void offerSessionClosed(long sessionId, boolean finalClose)
        {
            this.offerLifecycle(new SessionClosedDelivery(sessionId), finalClose);
        }

        private void offerLifecycle(Delivery delivery, boolean finalClose)
        {
            boolean overflow = false;

            synchronized (this.lock)
            {
                this.latestFrame = null;

                if (delivery instanceof SessionClosedDelivery closed && this.hasQueuedCloseLocked(closed.sessionId))
                {
                    return;
                }

                while (this.pending.size() >= MAX_PENDING_CALLBACKS && this.removeFirstDemandDeliveryLocked())
                {}

                if (this.pending.size() >= MAX_PENDING_CALLBACKS)
                {
                    this.coalesceUndeliveredLifecyclePairLocked();
                }

                if (this.pending.size() >= MAX_PENDING_CALLBACKS)
                {
                    overflow = true;
                }
                else
                {
                    this.pending.addLast(delivery);

                    if (finalClose)
                    {
                        this.shutdownAfterDrain = true;
                    }

                    this.scheduleDrainLocked();
                }
            }

            if (overflow)
            {
                this.recordProblem("lifecycle queue", null, 0L, System.nanoTime());
            }
        }

        private void deactivateAndDropDemandWork()
        {
            this.requestedActive.set(false);

            synchronized (this.lock)
            {
                this.assetGeneration++;
                this.deliveredAssets.clear();
                this.dropDemandDeliveriesLocked();
                this.lock.notifyAll();
            }
        }

        private void resetAssets()
        {
            synchronized (this.lock)
            {
                this.assetGeneration++;
                this.deliveredAssets.clear();

                Iterator<Delivery> iterator = this.pending.iterator();

                while (iterator.hasNext())
                {
                    Delivery delivery = iterator.next();

                    if (delivery instanceof AssetDelivery asset)
                    {
                        iterator.remove();
                        this.removePendingAssetLocked(asset);
                    }
                }

                this.lock.notifyAll();
            }
        }

        private void shutdownAfterDrain()
        {
            synchronized (this.lock)
            {
                this.shutdownAfterDrain = true;

                if (!this.drainScheduled && this.pending.isEmpty())
                {
                    this.executor.shutdown();
                }
            }
        }

        private void forceShutdown()
        {
            this.registrationClosed.set(true);
            this.requestedActive.set(false);

            synchronized (this.lock)
            {
                this.pending.clear();
                this.pendingAssets.clear();
                this.latestFrame = null;
                this.shutdownAfterDrain = true;
                this.lock.notifyAll();
            }

            this.executor.shutdownNow();
        }

        private void scheduleDrainLocked()
        {
            if (this.drainScheduled)
            {
                return;
            }

            this.drainScheduled = true;

            try
            {
                this.executor.execute(this::drain);
            }
            catch (RejectedExecutionException e)
            {
                this.drainScheduled = false;
                this.recordProblem("handoff executor", e, 0L, System.nanoTime());
                this.lock.notifyAll();
            }
        }

        private void drain()
        {
            while (true)
            {
                Delivery delivery;
                long delay;

                synchronized (this.lock)
                {
                    if (this.pending.isEmpty())
                    {
                        this.drainScheduled = false;
                        this.lock.notifyAll();

                        if (this.shutdownAfterDrain)
                        {
                            this.executor.shutdown();
                        }

                        return;
                    }

                    /* A terminal close is reliable lifecycle work, not frame
                     * churn: deliver it as soon as preceding callbacks finish
                     * even while frame/asset delivery is in backoff. */
                    delay = this.pending.peekFirst() instanceof SessionClosedDelivery
                        ? 0L
                        : this.quarantineUntilNanos - System.nanoTime();

                    if (delay <= 0L)
                    {
                        delivery = this.pending.removeFirst();

                        if (delivery == this.latestFrame)
                        {
                            this.latestFrame = null;
                        }
                    }
                    else
                    {
                        delivery = null;
                    }
                }

                if (delivery == null)
                {
                    LockSupport.parkNanos(delay);

                    continue;
                }

                if (delivery.demandWork() && !this.active())
                {
                    this.finishSkipped(delivery);

                    continue;
                }

                this.deliver(delivery);
            }
        }

        private void deliver(Delivery delivery)
        {
            long started = System.nanoTime();
            Throwable failure = null;

            this.callbackPhase = delivery.phase();
            this.callbackStartedNanos = started;

            try
            {
                delivery.invoke(this.listener);
            }
            catch (Exception | LinkageError e)
            {
                failure = e;
            }

            long finished = System.nanoTime();
            long elapsed = finished - started;

            this.callbackStartedNanos = 0L;
            this.callbackPhase = null;

            if (delivery instanceof AssetDelivery asset)
            {
                this.finishAsset(asset, failure == null);
            }

            if (failure != null)
            {
                this.recordProblem(delivery.phase(), failure, elapsed, finished);
            }
            else if (elapsed >= SLOW_CALLBACK_NANOS)
            {
                this.markSlowCallback(started, delivery.phase(), elapsed, finished);
            }
            else
            {
                this.recordFastSuccess();
            }

            synchronized (this.lock)
            {
                this.lock.notifyAll();
            }
        }

        private void finishAsset(AssetDelivery asset, boolean callbackSucceeded)
        {
            synchronized (this.lock)
            {
                Long pendingGeneration = this.pendingAssets.get(asset.assetId);

                if (pendingGeneration != null && pendingGeneration == asset.generation)
                {
                    this.pendingAssets.remove(asset.assetId);
                }

                if (callbackSucceeded
                    && this.active()
                    && this.assetGeneration == asset.generation)
                {
                    this.deliveredAssets.add(asset.assetId);
                }
            }
        }

        private void finishSkipped(Delivery delivery)
        {
            synchronized (this.lock)
            {
                if (delivery instanceof AssetDelivery asset)
                {
                    this.removePendingAssetLocked(asset);
                }

                this.lock.notifyAll();
            }
        }

        private void recordFastSuccess()
        {
            synchronized (this.lock)
            {
                this.failures = 0;
                this.suppressedProblems = 0;
                this.quarantineUntilNanos = 0L;
            }
        }

        private void markSlowCallback(long started, String phase, long elapsed, long now)
        {
            synchronized (this.lock)
            {
                if (this.slowMarkedStartNanos == started)
                {
                    return;
                }

                this.slowMarkedStartNanos = started;
            }

            this.recordProblem(phase == null ? "callback" : phase + " (slow)", null, elapsed, now);
        }

        private void recordProblem(String phase, Throwable failure, long elapsed, long now)
        {
            long backoff;
            int suppressed;
            boolean log;

            synchronized (this.lock)
            {
                this.failures = Math.min(31, this.failures + 1);
                backoff = failureBackoff(this.failures);
                this.quarantineUntilNanos = Math.max(this.quarantineUntilNanos, now + backoff);
                log = this.nextProblemLogNanos == 0L || now - this.nextProblemLogNanos >= 0L;
                suppressed = this.suppressedProblems;

                if (log)
                {
                    this.nextProblemLogNanos = now + Math.max(PROBLEM_LOG_INTERVAL_NANOS, backoff);
                    this.suppressedProblems = 0;
                }
                else
                {
                    this.suppressedProblems++;
                }

                this.dropDemandDeliveriesLocked();
            }

            if (!log)
            {
                return;
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsed);
            long backoffMillis = TimeUnit.NANOSECONDS.toMillis(backoff);

            if (failure == null)
            {
                LOGGER.warn(
                    "[bbs-client-ui-mirror] quarantined {} listener for addon '{}' after {} ms; retrying in {} ms ({} problems suppressed)",
                    phase,
                    this.addonId,
                    elapsedMillis,
                    backoffMillis,
                    suppressed
                );
            }
            else
            {
                LOGGER.warn(
                    "[bbs-client-ui-mirror] quarantined {} listener for addon '{}'; retrying in {} ms ({} problems suppressed)",
                    phase,
                    this.addonId,
                    backoffMillis,
                    suppressed,
                    failure
                );
            }
        }

        private void dropDemandDeliveriesLocked()
        {
            Iterator<Delivery> iterator = this.pending.iterator();

            while (iterator.hasNext())
            {
                Delivery delivery = iterator.next();

                if (!delivery.demandWork())
                {
                    continue;
                }

                iterator.remove();

                if (delivery == this.latestFrame)
                {
                    this.latestFrame = null;
                }

                if (delivery instanceof AssetDelivery asset)
                {
                    this.removePendingAssetLocked(asset);
                }
            }
        }

        private boolean removeFirstDemandDeliveryLocked()
        {
            Iterator<Delivery> iterator = this.pending.iterator();

            while (iterator.hasNext())
            {
                Delivery delivery = iterator.next();

                if (!delivery.demandWork())
                {
                    continue;
                }

                iterator.remove();

                if (delivery == this.latestFrame)
                {
                    this.latestFrame = null;
                }

                if (delivery instanceof AssetDelivery asset)
                {
                    this.removePendingAssetLocked(asset);
                }

                return true;
            }

            return false;
        }

        private boolean removeFirstFrameLocked()
        {
            Iterator<Delivery> iterator = this.pending.iterator();

            while (iterator.hasNext())
            {
                Delivery delivery = iterator.next();

                if (delivery instanceof FrameDelivery)
                {
                    iterator.remove();

                    if (delivery == this.latestFrame)
                    {
                        this.latestFrame = null;
                    }

                    return true;
                }
            }

            return false;
        }

        private boolean hasQueuedCloseLocked(long sessionId)
        {
            for (Delivery delivery : this.pending)
            {
                if (delivery instanceof SessionClosedDelivery closed && closed.sessionId == sessionId)
                {
                    return true;
                }
            }

            return false;
        }

        private void coalesceUndeliveredLifecyclePairLocked()
        {
            List<Delivery> deliveries = new ArrayList<>(this.pending);

            for (int i = 0; i + 1 < deliveries.size(); i++)
            {
                Delivery first = deliveries.get(i);
                Delivery second = deliveries.get(i + 1);

                if (first instanceof SessionOpenedDelivery opened
                    && second instanceof SessionClosedDelivery closed
                    && opened.session.sessionId() == closed.sessionId)
                {
                    deliveries.remove(i + 1);
                    deliveries.remove(i);
                    this.pending.clear();
                    this.pending.addAll(deliveries);

                    return;
                }
            }
        }

        private void removePendingAssetLocked(AssetDelivery asset)
        {
            Long generation = this.pendingAssets.get(asset.assetId);

            if (generation != null && generation == asset.generation)
            {
                this.pendingAssets.remove(asset.assetId);
            }
        }

        private boolean awaitIdle(long timeoutNanos) throws InterruptedException
        {
            long deadline = System.nanoTime() + timeoutNanos;

            synchronized (this.lock)
            {
                while (this.drainScheduled || this.callbackStartedNanos != 0L || !this.pending.isEmpty())
                {
                    long remaining = deadline - System.nanoTime();

                    if (remaining <= 0L)
                    {
                        return false;
                    }

                    TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
                }

                return true;
            }
        }
    }

    private interface Delivery
    {
        String phase();

        boolean demandWork();

        void invoke(BBSUiMirrorListener listener);
    }

    private static final class SessionOpenedDelivery implements Delivery
    {
        private final BBSUiSessionInfo session;

        private SessionOpenedDelivery(BBSUiSessionInfo session)
        {
            this.session = session;
        }

        @Override
        public String phase()
        {
            return "open";
        }

        @Override
        public boolean demandWork()
        {
            return false;
        }

        @Override
        public void invoke(BBSUiMirrorListener listener)
        {
            listener.onSessionOpened(this.session);
        }
    }

    private static final class SessionClosedDelivery implements Delivery
    {
        private final long sessionId;

        private SessionClosedDelivery(long sessionId)
        {
            this.sessionId = sessionId;
        }

        @Override
        public String phase()
        {
            return "close";
        }

        @Override
        public boolean demandWork()
        {
            return false;
        }

        @Override
        public void invoke(BBSUiMirrorListener listener)
        {
            listener.onSessionClosed(this.sessionId);
        }
    }

    private static final class AssetDelivery implements Delivery
    {
        private final String assetId;
        private final long generation;
        private final BBSUiAssetBytes asset;

        private AssetDelivery(String assetId, long generation, BBSUiAssetBytes asset)
        {
            this.assetId = assetId;
            this.generation = generation;
            this.asset = asset;
        }

        @Override
        public String phase()
        {
            return "asset";
        }

        @Override
        public boolean demandWork()
        {
            return true;
        }

        @Override
        public void invoke(BBSUiMirrorListener listener)
        {
            listener.onAssetAvailable(this.asset);
        }
    }

    private static final class FrameDelivery implements Delivery
    {
        private BBSUiFrame frame;

        private FrameDelivery(BBSUiFrame frame)
        {
            this.frame = frame;
        }

        @Override
        public String phase()
        {
            return "frame";
        }

        @Override
        public boolean demandWork()
        {
            return true;
        }

        @Override
        public void invoke(BBSUiMirrorListener listener)
        {
            listener.onFrame(this.frame);
        }
    }

    private static final class ListenerThreadFactory implements ThreadFactory
    {
        private final String addonId;

        private ListenerThreadFactory(String addonId)
        {
            this.addonId = addonId.replaceAll("[^a-zA-Z0-9_.-]", "_");
        }

        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(
                runnable,
                "bbs-ui-mirror-" + this.addonId + "-" + WORKER_SEQUENCE.incrementAndGet()
            );

            thread.setDaemon(true);

            return thread;
        }
    }
}
