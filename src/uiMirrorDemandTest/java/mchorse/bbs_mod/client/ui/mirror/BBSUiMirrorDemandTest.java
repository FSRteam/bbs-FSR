package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.client.ui.BBSUiAssetBytes;
import mchorse.bbs_mod.api.client.ui.BBSUiAssetRef;
import mchorse.bbs_mod.api.client.ui.BBSUiCursor;
import mchorse.bbs_mod.api.client.ui.BBSUiCursorShape;
import mchorse.bbs_mod.api.client.ui.BBSUiFrame;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorListener;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorSubscription;
import mchorse.bbs_mod.api.client.ui.BBSUiSessionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic demand, ordering, backpressure, and listener-isolation checks. */
public final class BBSUiMirrorDemandTest
{
    private static final long CALLBACK_TIMEOUT_SECONDS = 3L;

    private BBSUiMirrorDemandTest()
    {}

    public static void main(String[] args) throws Exception
    {
        resetState();

        try
        {
            uiMirrorDemandZeroOneZero();
            slowAndThrowingListenerLifecycle();
        }
        finally
        {
            resetState();
        }

        System.out.println("BBSUiMirrorDemandTest: all tests passed");
    }

    private static void uiMirrorDemandZeroOneZero() throws Exception
    {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger frameConversions = new AtomicInteger();
        AtomicInteger assets = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch frames = new CountDownLatch(2);
        CountDownLatch assetDelivered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        Set<String> callbackThreads = ConcurrentHashMap.newKeySet();
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-demand-zero-one-zero"),
            new BBSUiMirrorListener()
            {
                @Override
                public void onSessionOpened(BBSUiSessionInfo session)
                {
                    callbackThreads.add(Thread.currentThread().getName());
                    opens.incrementAndGet();
                    opened.countDown();
                }

                @Override
                public void onAssetAvailable(BBSUiAssetBytes asset)
                {
                    callbackThreads.add(Thread.currentThread().getName());
                    assets.incrementAndGet();
                    assetDelivered.countDown();
                }

                @Override
                public void onFrame(BBSUiFrame frame)
                {
                    callbackThreads.add(Thread.currentThread().getName());
                    frameConversions.incrementAndGet();
                    sequences.add(frame.sequence());
                    frames.countDown();
                }

                @Override
                public void onSessionClosed(long sessionId)
                {
                    callbackThreads.add(Thread.currentThread().getName());
                    closes.incrementAndGet();
                    closed.countDown();
                }
            }
        );

        check(subscription.registration().accepted(), "demand subscription registration failed");
        check(!subscription.active(), "demand subscription must start inactive");
        check(!BBSUiMirrorRegistry.hasActiveDemand(), "inactive registration enabled capture");

        long sessionId = BBSUiFrameRecorder.openSession(320, 180, 640, 360);

        await(opened, "inactive subscription did not receive ordered session open");
        check(opens.get() == 1, "session open callback was duplicated");
        check(!BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "zero-viewer beginFrame must be false");

        BBSUiAssetBytes inactiveAsset = asset("inactive-asset");

        check(!BBSUiMirrorRegistry.needsAsset(inactiveAsset.asset().id()), "inactive listener requested an asset read");
        check(BBSUiMirrorRegistry.publishAsset(inactiveAsset), "inactive asset publication should be an inert success");
        check(BBSUiMirrorRegistry.awaitCallbacksForTests(1_000L), "inactive lifecycle callback did not settle");
        check(assets.get() == 0, "zero-viewer asset conversion ran");
        check(frameConversions.get() == 0, "zero-viewer frame conversion ran");

        subscription.setActive(true);
        check(subscription.active(), "0->1 viewer transition was not visible");
        check(BBSUiMirrorRegistry.hasActiveDemand(), "active viewer did not enable capture");
        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "active viewer frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 12F, 18F);

        BBSUiAssetBytes activeAsset = asset("active-asset");

        check(BBSUiMirrorRegistry.needsAsset(activeAsset.asset().id()), "active listener did not request a missing asset");
        check(BBSUiMirrorRegistry.publishAsset(activeAsset), "active asset handoff was rejected");
        await(assetDelivered, "active asset callback was not delivered");

        subscription.setActive(false);
        check(!subscription.active(), "1->0 viewer transition was not visible");
        check(!BBSUiMirrorRegistry.hasActiveDemand(), "zero viewers left capture enabled");
        check(!BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "zero-viewer capture resumed unexpectedly");
        BBSUiMirrorRegistry.publish(frame(sessionId, 99L));
        BBSUiMirrorRegistry.publishAsset(asset("post-zero-asset"));
        check(BBSUiMirrorRegistry.awaitCallbacksForTests(1_000L), "zero-viewer queue did not settle");
        check(frameConversions.get() == 1, "queued/late frame crossed the 1->0 demand fence");
        check(assets.get() == 1, "queued/late asset crossed the 1->0 demand fence");

        subscription.setActive(true);
        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "reactivated viewer frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 20F, 30F);
        await(frames, "active/reactivated frames were not delivered");
        check(sequences.equals(List.of(1L, 2L)), "reactivation reused a stale queued frame generation: " + sequences);

        subscription.setActive(false);
        subscription.close();
        subscription.close();
        await(closed, "final subscription close callback was dropped");
        check(closes.get() == 1, "subscription close was not idempotent");
        check(BBSUiMirrorRegistry.awaitSubscriptionClosedForTests(subscription, 2_000L), "subscription worker leaked after close");
        check(callbackThreads.size() == 1, "callbacks for one listener were not serial: " + callbackThreads);
        check(callbackThreads.stream().noneMatch(name -> name.equals(Thread.currentThread().getName())),
            "addon callback ran on the calling/render thread");

        BBSUiFrameRecorder.closeSession(sessionId);
        resetState();
    }

    private static void slowAndThrowingListenerLifecycle() throws Exception
    {
        latestFrameWinsOnSerialHandoff();
        slowListenerDoesNotBlockAndKeepsClose();
        throwingListenerBacksOffAndKeepsClose();
    }

    private static void latestFrameWinsOnSerialHandoff() throws Exception
    {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch firstFrameEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstFrame = new CountDownLatch(1);
        CountDownLatch twoFrames = new CountDownLatch(2);
        CountDownLatch closed = new CountDownLatch(1);
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inCallback = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-demand-latest-wins"),
            new BBSUiMirrorListener()
            {
                @Override
                public void onSessionOpened(BBSUiSessionInfo session)
                {
                    opened.countDown();
                }

                @Override
                public void onFrame(BBSUiFrame frame)
                {
                    int concurrent = inCallback.incrementAndGet();

                    maxConcurrent.accumulateAndGet(concurrent, Math::max);

                    try
                    {
                        sequences.add(frame.sequence());

                        if (sequences.size() == 1)
                        {
                            firstFrameEntered.countDown();
                            awaitUninterruptibly(releaseFirstFrame);
                        }
                    }
                    finally
                    {
                        inCallback.decrementAndGet();
                        twoFrames.countDown();
                    }
                }

                @Override
                public void onSessionClosed(long sessionId)
                {
                    closed.countDown();
                }
            }
        );

        subscription.setActive(true);
        long sessionId = BBSUiFrameRecorder.openSession(320, 180, 640, 360);

        await(opened, "latest-wins session did not open");
        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "latest-wins first frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 0F, 0F);
        await(firstFrameEntered, "latest-wins first callback did not start");

        for (long sequence = 2L; sequence <= 20L; sequence++)
        {
            BBSUiMirrorRegistry.publish(frame(sessionId, sequence));
        }

        releaseFirstFrame.countDown();
        await(twoFrames, "latest frame was not delivered after the blocked callback");
        check(sequences.equals(List.of(1L, 20L)), "frame handoff was not latest-wins: " + sequences);
        check(maxConcurrent.get() == 1, "listener callbacks overlapped");

        BBSUiFrameRecorder.closeSession(sessionId);
        await(closed, "latest-wins close callback was dropped");
        subscription.close();
        check(BBSUiMirrorRegistry.awaitSubscriptionClosedForTests(subscription, 2_000L), "latest-wins worker leaked");
        resetState();
    }

    private static void slowListenerDoesNotBlockAndKeepsClose() throws Exception
    {
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-demand-slow-listener"),
            new BBSUiMirrorListener()
            {
                @Override
                public void onSessionOpened(BBSUiSessionInfo session)
                {
                    events.add("open");
                    openEntered.countDown();
                    awaitUninterruptibly(releaseOpen);
                }

                @Override
                public void onFrame(BBSUiFrame frame)
                {
                    events.add("frame");
                }

                @Override
                public void onSessionClosed(long sessionId)
                {
                    events.add("close");
                    closed.countDown();
                }
            }
        );

        subscription.setActive(true);
        long sessionId = BBSUiFrameRecorder.openSession(320, 180, 640, 360);

        await(openEntered, "slow open callback did not start");
        Thread.sleep(75L);
        check(!BBSUiMirrorRegistry.hasActiveDemand(), "slow listener was not quarantined off the render capture path");

        BBSUiFrameRecorder.closeSession(sessionId);
        releaseOpen.countDown();
        await(closed, "slow listener quarantine dropped the final close");
        check(events.equals(List.of("open", "close")), "slow lifecycle order was corrupted: " + events);

        subscription.close();
        check(BBSUiMirrorRegistry.awaitSubscriptionClosedForTests(subscription, 2_000L), "slow listener worker leaked");
        resetState();
    }

    private static void throwingListenerBacksOffAndKeepsClose() throws Exception
    {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch frameAttempted = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger frameAttempts = new AtomicInteger();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-demand-throwing-listener"),
            new BBSUiMirrorListener()
            {
                @Override
                public void onSessionOpened(BBSUiSessionInfo session)
                {
                    events.add("open");
                    opened.countDown();
                }

                @Override
                public void onFrame(BBSUiFrame frame)
                {
                    events.add("frame");
                    frameAttempts.incrementAndGet();
                    frameAttempted.countDown();
                    throw new IllegalStateException("deterministic listener failure");
                }

                @Override
                public void onSessionClosed(long sessionId)
                {
                    events.add("close");
                    closed.countDown();
                }
            }
        );

        subscription.setActive(true);
        long sessionId = BBSUiFrameRecorder.openSession(320, 180, 640, 360);

        await(opened, "throwing-listener session did not open");
        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "throwing-listener frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 0F, 0F);
        await(frameAttempted, "throwing frame callback was not attempted");
        awaitNoActiveDemand("throwing listener did not enter backoff");
        check(!BBSUiMirrorRegistry.hasActiveDemand(), "throwing listener did not enter backoff");

        for (int i = 0; i < 20; i++)
        {
            check(!BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "backoff allowed per-frame exception churn");
        }

        check(frameAttempts.get() == 1, "throwing listener churned once per frame");
        BBSUiFrameRecorder.closeSession(sessionId);
        await(closed, "throwing listener backoff dropped the final close");
        check(events.equals(List.of("open", "frame", "close")), "throwing lifecycle order was corrupted: " + events);

        subscription.close();
        check(BBSUiMirrorRegistry.awaitSubscriptionClosedForTests(subscription, 2_000L), "throwing listener worker leaked");
        resetState();
    }

    private static BBSAddonDescriptor descriptor(String id)
    {
        return BBSAddonDescriptor.builder(id)
            .side(BBSAddonSide.CLIENT)
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();
    }

    private static BBSUiAssetBytes asset(String id)
    {
        return new BBSUiAssetBytes(
            new BBSUiAssetRef(id, 1, 1),
            "image/png",
            "sha256-" + id,
            new byte[] {1, 2, 3}
        );
    }

    private static BBSUiFrame frame(long sessionId, long sequence)
    {
        return new BBSUiFrame(
            sessionId,
            sequence,
            System.nanoTime(),
            320,
            180,
            new BBSUiCursor(0F, 0F, BBSUiCursorShape.DEFAULT),
            List.of(),
            false
        );
    }

    private static void resetState()
    {
        BBSUiFrameRecorder.closeAllSessions();
        BBSUiMirrorRegistry.resetForTests();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException
    {
        check(latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
    }

    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        boolean interrupted = false;

        while (true)
        {
            try
            {
                latch.await();
                break;
            }
            catch (InterruptedException e)
            {
                interrupted = true;
            }
        }

        if (interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitNoActiveDemand(String message) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS);

        while (BBSUiMirrorRegistry.hasActiveDemand() && System.nanoTime() - deadline < 0L)
        {
            Thread.sleep(1L);
        }

        check(!BBSUiMirrorRegistry.hasActiveDemand(), message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
