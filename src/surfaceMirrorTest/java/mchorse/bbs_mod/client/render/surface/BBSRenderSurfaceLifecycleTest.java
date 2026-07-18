package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceDemand;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceFrame;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceListener;

import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Pure-Java regressions for the asynchronous surface lifecycle boundaries. */
public final class BBSRenderSurfaceLifecycleTest
{
    private static final EnumSet<BBSRenderSurfaceKind> WORLD_REPLAY =
        EnumSet.of(BBSRenderSurfaceKind.WORLD_REPLAY);

    private BBSRenderSurfaceLifecycleTest()
    {}

    public static void run()
    {
        surfaceDemandAcceptsUpToOneHundredTwentyFps();
        phasePreservingPacingAvoidsDiscreteRenderUndersampling();
        readyPboSelectionAlwaysKeepsTheNewestSequence();
        pendingPboDoesNotCrossSession();
        publicFrameRejectsNonPositiveSequence();
        invalidationPublishesPendingBeforeReplacementEpoch();
        offThreadInvalidateCannotReviveEnteredCapture();
        queuedBeforeInvalidateCannotUseNewEpoch();
        callbackAdmissionLinearizesWithInvalidation();
        surfaceSequenceRestartIsUnambiguous();
        latestEncoderNeverRegresses();
        publishRechecksGenerationBetweenListeners();
        slowAndThrowingDemandAreIsolated();
        uiRegionSizingHonorsPixelAndAbsoluteBounds();
    }

    private static void surfaceDemandAcceptsUpToOneHundredTwentyFps()
    {
        BBSRenderSurfaceDemand maximum = new BBSRenderSurfaceDemand(WORLD_REPLAY, 1920, 1080, 120, 95);

        check(maximum.framesPerSecond() == 120, "120 FPS surface demand was not retained");
        check(new BBSRenderSurfaceDemand(WORLD_REPLAY, 320, 180, 1, 30).framesPerSecond() == 1,
            "API 2.0 compatibility no longer accepts a one FPS addon demand");
        check(BBSRenderSurfaceDemand.mobile(WORLD_REPLAY).framesPerSecond() == 30,
            "mobile helper must start at 30 FPS");
        check(BBSRenderSurfaceDemand.desktop(WORLD_REPLAY).framesPerSecond() == 60,
            "desktop helper must default to 60 FPS");
        expectIllegalArgument(
            () -> new BBSRenderSurfaceDemand(WORLD_REPLAY, 1920, 1080, 121, 95),
            "surface demand accepted more than 120 FPS"
        );
        expectIllegalArgument(
            () -> new BBSRenderSurfaceDemand(WORLD_REPLAY, 320, 180, 0, 30),
            "surface demand accepted zero FPS"
        );
    }

    private static void phasePreservingPacingAvoidsDiscreteRenderUndersampling()
    {
        for (int renderFps : new int[]{144, 165})
        {
            for (int targetFps : new int[]{30, 60, 120})
            {
                int captures = simulatedCaptureCount(renderFps, targetFps, 10);
                int expected = targetFps * 10;

                check(Math.abs(captures - expected) <= 2,
                    renderFps + " Hz pacing undersampled " + targetFps + " FPS: " + captures);
            }
        }

        long interval = 1_000_000_000L / 60L;
        long deadline = BBSRenderSurfaceRuntime.nextCaptureDeadline(0L, 0L, interval);
        long afterStall = BBSRenderSurfaceRuntime.nextCaptureDeadline(deadline, 1_000_000_000L, interval);

        check(afterStall > 1_000_000_000L && afterStall <= 1_000_000_000L + interval,
            "stalled pacing did not skip missed frames to one future deadline");
    }

    private static int simulatedCaptureCount(int renderFps, int targetFps, int seconds)
    {
        long renderInterval = 1_000_000_000L / renderFps;
        long captureInterval = 1_000_000_000L / targetFps;
        long deadline = 0L;
        boolean scheduled = false;
        int captures = 0;

        for (int frame = 0; frame < renderFps * seconds; frame++)
        {
            long now = frame * renderInterval;

            if (!scheduled || now - deadline >= 0L)
            {
                captures++;
                deadline = BBSRenderSurfaceRuntime.nextCaptureDeadline(scheduled ? deadline : now, now, captureInterval);
                scheduled = true;
            }
        }

        return captures;
    }

    private static void readyPboSelectionAlwaysKeepsTheNewestSequence()
    {
        check(selectedSequence(0L, 1L, 2L, 3L) == 3L,
            "ordered ready PBOs did not retain the newest sequence");
        check(selectedSequence(1L, 4L, 2L, 3L) == 4L,
            "ring-ordered ready PBOs replaced a newer sequence with an older slot");
        check(selectedSequence(4L, 2L, 3L) == 0L,
            "already delivered PBO sequences became selectable again");
    }

    private static long selectedSequence(long deliveredFloor, long... candidates)
    {
        long selected = 0L;

        for (long candidate : candidates)
        {
            if (GpuSurfaceReadback.shouldSelectSequence(candidate, deliveredFloor, selected))
            {
                selected = candidate;
            }
        }

        return selected;
    }

    private static void uiRegionSizingHonorsPixelAndAbsoluteBounds()
    {
        int[] narrowTall = BBSRenderSurfaceRuntime.fitUiRegion(300, 800, 960, 540);
        check(narrowTall[0] == 300 && narrowTall[1] == 800,
            "narrow/tall UI preview was needlessly reduced below its pixel budget");

        int[] tooWide = BBSRenderSurfaceRuntime.fitUiRegion(3000, 100, 960, 540);
        check(tooWide[0] <= BBSRenderSurfaceDemand.MAX_WIDTH && tooWide[1] > 0,
            "ultrawide UI preview exceeded the absolute wire width bound");

        int[] tooTall = BBSRenderSurfaceRuntime.fitUiRegion(100, 2000, 960, 540);
        check(tooTall[1] <= BBSRenderSurfaceDemand.MAX_HEIGHT && tooTall[0] > 0,
            "ultratall UI preview exceeded the absolute wire height bound");

        int[] overBudget = BBSRenderSurfaceRuntime.fitUiRegion(1200, 800, 960, 540);
        check((long) overBudget[0] * overBudget[1] <= 960L * 540L,
            "UI preview exceeded the negotiated pixel budget");
    }

    private static void pendingPboDoesNotCrossSession()
    {
        BBSRenderSurfaceStreamFence fence = new BBSRenderSurfaceStreamFence();
        long generationA = fence.beginStream();
        BBSRenderSurfaceStamp pendingA = fence.issue(generationA);

        check(GpuSurfaceReadback.belongsToCurrentStream(pendingA, fence::isCurrent),
            "fresh PBO work was rejected by its own stream");

        fence.invalidate();

        check(!GpuSurfaceReadback.belongsToCurrentStream(pendingA, fence::isCurrent),
            "pending PBO work crossed a surface/UI session fence");
        check(!fence.tryStartCallback(pendingA.generation()),
            "retired surface generation admitted a new listener callback");

        long generationB = fence.beginStream();
        BBSRenderSurfaceStamp pendingB = fence.issue(generationB);
        BBSRenderSurfaceFrame encodedA = frame(pendingA);
        BBSRenderSurfaceFrame encodedB = frame(pendingB);

        check(!fence.isCurrent(encodedA.generation()), "old encoded JPEG became publishable in the new session");
        check(fence.isCurrent(encodedB.generation()), "new encoded JPEG lost its publish generation");
    }

    private static void publicFrameRejectsNonPositiveSequence()
    {
        expectIllegalArgument(() -> new BBSRenderSurfaceFrame(
            WORLD_REPLAY, 1L, 0L, 1L, 1, 1, true, new byte[]{1}
        ), "zero surface sequence was accepted");
        expectIllegalArgument(() -> new BBSRenderSurfaceFrame(
            WORLD_REPLAY, 1L, -1L, 1L, 1, 1, true, new byte[]{1}
        ), "negative surface sequence was accepted");
    }

    private static void offThreadInvalidateCannotReviveEnteredCapture()
    {
        BBSRenderSurfaceLifecycleFence lifecycle = new BBSRenderSurfaceLifecycleFence();
        CountDownLatch oldCaptureEntered = new CountDownLatch(1);
        CountDownLatch releaseOldCapture = new CountDownLatch(1);
        CountDownLatch oldCaptureFinished = new CountDownLatch(1);
        AtomicInteger captureStarts = new AtomicInteger();
        Thread oldCapture = new Thread(() ->
        {
            long enteredEpoch = lifecycle.captureEpoch();

            oldCaptureEntered.countDown();
            await(releaseOldCapture, 2L);

            lifecycle.runIfCurrent(enteredEpoch, captureStarts::incrementAndGet);

            oldCaptureFinished.countDown();
        }, "surface-old-capture-probe");

        oldCapture.setDaemon(true);
        oldCapture.start();
        check(await(oldCaptureEntered, 2L), "old capture did not enter its lifecycle epoch");

        long teardownEpoch = lifecycle.invalidate(() -> {});
        long newEpoch = lifecycle.captureEpoch();

        check(!lifecycle.isCurrent(newEpoch), "new capture started before pending teardown completed");

        releaseOldCapture.countDown();
        check(await(oldCaptureFinished, 2L), "old capture did not leave its lifecycle fence");
        check(captureStarts.get() == 0, "old capture revived itself after off-thread invalidation");

        lifecycle.apply(teardownEpoch, () -> {});
        check(lifecycle.isCurrent(newEpoch), "fresh capture stayed blocked after matching teardown");
        check(lifecycle.runIfCurrent(newEpoch, captureStarts::incrementAndGet),
            "fresh capture could not start after matching teardown");
        check(captureStarts.get() == 1, "fresh capture did not become the only admitted capture");
    }

    private static void invalidationPublishesPendingBeforeReplacementEpoch()
    {
        BBSRenderSurfaceLifecycleFence lifecycle = new BBSRenderSurfaceLifecycleFence();
        CountDownLatch fenceWorkEntered = new CountDownLatch(1);
        CountDownLatch releaseFenceWork = new CountDownLatch(1);
        CountDownLatch invalidationFinished = new CountDownLatch(1);
        AtomicLong teardownEpoch = new AtomicLong();
        Thread invalidator = new Thread(() ->
        {
            teardownEpoch.set(lifecycle.invalidate(() ->
            {
                fenceWorkEntered.countDown();
                await(releaseFenceWork, 2L);
            }));
            invalidationFinished.countDown();
        }, "surface-invalidation-publication-probe");

        invalidator.setDaemon(true);
        invalidator.start();
        check(await(fenceWorkEntered, 2L), "invalidation did not enter fenced work");

        long replacementEpoch = lifecycle.captureEpoch();

        check(!lifecycle.isCurrent(replacementEpoch),
            "replacement epoch became capture-ready before invalidation work completed");

        releaseFenceWork.countDown();
        check(await(invalidationFinished, 2L), "invalidation publication did not finish");
        check(!lifecycle.isCurrent(replacementEpoch),
            "replacement epoch became ready before matching render teardown");

        CountDownLatch teardownEntered = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        CountDownLatch teardownFinished = new CountDownLatch(1);
        AtomicInteger teardownCalls = new AtomicInteger();
        Thread teardown = new Thread(() ->
        {
            lifecycle.apply(teardownEpoch.get(), () ->
            {
                teardownCalls.incrementAndGet();
                teardownEntered.countDown();
                await(releaseTeardown, 2L);
            });
            teardownFinished.countDown();
        }, "surface-render-teardown-probe");

        teardown.setDaemon(true);
        teardown.start();
        check(await(teardownEntered, 2L), "matching render teardown did not start");
        check(!lifecycle.isCurrent(replacementEpoch),
            "replacement epoch became ready while matching teardown was still running");

        releaseTeardown.countDown();
        check(await(teardownFinished, 2L), "matching render teardown did not finish");
        check(lifecycle.isCurrent(replacementEpoch),
            "replacement epoch did not become ready after matching render teardown");
        lifecycle.apply(teardownEpoch.get(), teardownCalls::incrementAndGet);
        check(teardownCalls.get() == 1, "matching render teardown was not idempotent");
    }

    private static void callbackAdmissionLinearizesWithInvalidation()
    {
        BBSRenderSurfaceStreamFence fence = new BBSRenderSurfaceStreamFence();
        long generation = fence.beginStream();
        CountDownLatch admissionReady = new CountDownLatch(1);
        CountDownLatch attemptAdmission = new CountDownLatch(1);
        CountDownLatch admissionFinished = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        Thread callback = new Thread(() ->
        {
            admissionReady.countDown();
            await(attemptAdmission, 2L);

            if (fence.tryStartCallback(generation))
            {
                admitted.incrementAndGet();
            }

            admissionFinished.countDown();
        }, "surface-callback-admission-probe");

        callback.setDaemon(true);
        callback.start();
        check(await(admissionReady, 2L), "callback admission probe did not become ready");

        fence.invalidate();
        attemptAdmission.countDown();

        check(await(admissionFinished, 2L), "callback admission probe did not finish");
        check(admitted.get() == 0, "listener callback was admitted after generation invalidation");
    }

    private static void queuedBeforeInvalidateCannotUseNewEpoch()
    {
        BBSRenderSurfaceLifecycleFence lifecycle = new BBSRenderSurfaceLifecycleFence();
        long queuedEpoch = lifecycle.captureEpoch();
        CountDownLatch requestQueued = new CountDownLatch(1);
        CountDownLatch executeQueuedRequest = new CountDownLatch(1);
        CountDownLatch requestFinished = new CountDownLatch(1);
        AtomicInteger captureStarts = new AtomicInteger();
        Thread queuedRequest = new Thread(() ->
        {
            requestQueued.countDown();
            await(executeQueuedRequest, 2L);
            lifecycle.runIfCurrent(queuedEpoch, captureStarts::incrementAndGet);
            requestFinished.countDown();
        }, "surface-queued-capture-probe");

        queuedRequest.setDaemon(true);
        queuedRequest.start();
        check(await(requestQueued, 2L), "old capture request was not queued");

        long teardownEpoch = lifecycle.invalidate(() -> {});
        long replacementEpoch = lifecycle.captureEpoch();

        check(!lifecycle.isCurrent(replacementEpoch), "capture request was accepted during pending teardown");
        lifecycle.apply(teardownEpoch, () -> {});
        executeQueuedRequest.countDown();

        check(await(requestFinished, 2L), "queued old capture request did not finish");
        check(captureStarts.get() == 0, "queued old capture request reused the replacement epoch");
        check(lifecycle.runIfCurrent(replacementEpoch, captureStarts::incrementAndGet),
            "fresh request could not use the replacement epoch");
        check(captureStarts.get() == 1, "only a fresh request may start after queued invalidation");
    }

    private static void surfaceSequenceRestartIsUnambiguous()
    {
        BBSRenderSurfaceStreamFence fence = new BBSRenderSurfaceStreamFence();
        long firstGeneration = fence.beginStream();
        BBSRenderSurfaceStamp beforeStop = fence.issue(firstGeneration);

        fence.invalidate();

        long secondGeneration = fence.beginStream();
        BBSRenderSurfaceStamp afterRestart = fence.issue(secondGeneration);

        check(afterRestart.generation() > beforeStop.generation(),
            "surface restart did not advance its explicit generation");
        check(afterRestart.sequence() > beforeStop.sequence(),
            "surface restart reset the client-lifetime sequence");
    }

    private static void latestEncoderNeverRegresses()
    {
        AtomicLong highest = new AtomicLong();

        check(StbJpegSurfaceEncoder.advanceSequence(highest, 4L), "new encoder sequence was rejected");
        check(!StbJpegSurfaceEncoder.advanceSequence(highest, 2L), "older PBO replaced a newer encoder frame");
        check(!StbJpegSurfaceEncoder.advanceSequence(highest, 3L), "out-of-order PBO regressed the encoder slot");
        check(StbJpegSurfaceEncoder.advanceSequence(highest, 5L), "newest encoder sequence was not accepted");
    }

    private static void publishRechecksGenerationBetweenListeners()
    {
        String firstAddonId = "surface-publish-fence-first";
        String secondAddonId = "surface-publish-fence-second";
        AtomicReference<BBSRenderSurfaceDemand> firstDemand = new AtomicReference<>(
            BBSRenderSurfaceDemand.mobile(WORLD_REPLAY)
        );
        AtomicReference<BBSRenderSurfaceDemand> secondDemand = new AtomicReference<>(
            BBSRenderSurfaceDemand.mobile(WORLD_REPLAY)
        );
        CountDownLatch demandsSampled = new CountDownLatch(2);
        CountDownLatch firstListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        CountDownLatch publishFinished = new CountDownLatch(1);
        AtomicInteger secondListenerCalls = new AtomicInteger();

        check(BBSRenderSurfaceRegistry.register(descriptor(firstAddonId), new BBSRenderSurfaceListener()
        {
            @Override
            public BBSRenderSurfaceDemand demand()
            {
                demandsSampled.countDown();

                return firstDemand.get();
            }

            @Override
            public void onFrame(BBSRenderSurfaceFrame frame)
            {
                firstListenerEntered.countDown();
                await(releaseFirstListener, 2L);
            }
        }).accepted(), "first publish-fence listener registration failed");
        check(BBSRenderSurfaceRegistry.register(descriptor(secondAddonId), new BBSRenderSurfaceListener()
        {
            @Override
            public BBSRenderSurfaceDemand demand()
            {
                demandsSampled.countDown();

                return secondDemand.get();
            }

            @Override
            public void onFrame(BBSRenderSurfaceFrame frame)
            {
                secondListenerCalls.incrementAndGet();
            }
        }).accepted(), "second publish-fence listener registration failed");

        check(await(demandsSampled, 2L), "publish-fence demands were not sampled");
        await(() -> BBSRenderSurfaceRegistry.demandRefreshIdle(firstAddonId)
                && BBSRenderSurfaceRegistry.demandRefreshIdle(secondAddonId),
            "publish-fence demands did not finish caching");
        await(() -> BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY) != null,
            "publish-fence listeners did not become active");

        AtomicLong currentGeneration = new AtomicLong(11L);
        BBSRenderSurfaceFrame frame = frame(new BBSRenderSurfaceStamp(11L, 1L));
        Thread publisher = new Thread(() ->
        {
            try
            {
                BBSRenderSurfaceRegistry.publish(
                    frame,
                    value -> currentGeneration.get() == value,
                    value -> currentGeneration.compareAndSet(value, value)
                );
            }
            finally
            {
                publishFinished.countDown();
            }
        }, "surface-generation-publish-probe");

        publisher.setDaemon(true);
        publisher.start();
        check(await(firstListenerEntered, 2L), "first publish-fence listener did not start");

        currentGeneration.incrementAndGet();
        releaseFirstListener.countDown();

        check(await(publishFinished, 2L), "generation-fenced publish did not finish");
        check(secondListenerCalls.get() == 0,
            "listener callback began after its surface generation was invalidated");

        firstDemand.set(BBSRenderSurfaceDemand.none());
        secondDemand.set(BBSRenderSurfaceDemand.none());
        await(() -> BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY) == null,
            "publish-fence listener demand did not return to none");
    }

    private static void slowAndThrowingDemandAreIsolated()
    {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicReference<BBSRenderSurfaceDemand> slowValue = new AtomicReference<>(
            BBSRenderSurfaceDemand.mobile(WORLD_REPLAY)
        );

        check(BBSRenderSurfaceRegistry.register(descriptor("surface-slow-demand-test"), new BBSRenderSurfaceListener()
        {
            @Override
            public BBSRenderSurfaceDemand demand()
            {
                slowEntered.countDown();

                try
                {
                    releaseSlow.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();

                    return BBSRenderSurfaceDemand.none();
                }

                return slowValue.get();
            }

            @Override
            public void onFrame(BBSRenderSurfaceFrame frame)
            {}
        }).accepted(), "slow surface demand listener registration failed");

        await(() -> slowEntered.getCount() == 0L, "slow demand sampler did not start");

        CountDownLatch renderProbeDone = new CountDownLatch(1);
        AtomicReference<BBSRenderSurfaceCapturePlan> probedPlan = new AtomicReference<>();
        Thread renderProbe = new Thread(() ->
        {
            probedPlan.set(BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY));
            renderProbeDone.countDown();
        }, "surface-demand-render-probe");

        renderProbe.setDaemon(true);
        renderProbe.start();

        boolean renderProbeCompleted = await(renderProbeDone, 1L);

        if (!renderProbeCompleted)
        {
            /* Make a failing synchronous implementation releasable so the test
             * process can still terminate and report the assertion. */
            releaseSlow.countDown();
        }

        check(renderProbeCompleted, "slow addon demand blocked the render-side snapshot read");
        check(probedPlan.get() == null, "unsampled slow demand leaked into render capture");
        releaseSlow.countDown();
        await(() -> BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY) != null,
            "completed slow demand was not cached");

        slowValue.set(BBSRenderSurfaceDemand.none());
        await(() -> BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY) == null,
            "inactive demand did not replace the cached active demand");

        String throwingAddonId = "surface-throwing-demand-test";
        CountDownLatch throwingCalled = new CountDownLatch(1);
        AtomicInteger throwingCalls = new AtomicInteger();

        check(BBSRenderSurfaceRegistry.register(descriptor(throwingAddonId), new BBSRenderSurfaceListener()
        {
            @Override
            public BBSRenderSurfaceDemand demand()
            {
                throwingCalls.incrementAndGet();
                throwingCalled.countDown();

                throw new IllegalStateException("expected demand failure");
            }

            @Override
            public void onFrame(BBSRenderSurfaceFrame frame)
            {}
        }).accepted(), "throwing surface demand listener registration failed");

        await(() -> throwingCalled.getCount() == 0L, "throwing demand sampler did not start");
        await(() -> BBSRenderSurfaceRegistry.demandRefreshIdle(throwingAddonId)
                && BBSRenderSurfaceRegistry.demandFailureCount(throwingAddonId) == 1,
            "throwing demand did not complete its first backed-off refresh");
        check(BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY) == null,
            "throwing demand enabled capture");

        for (int i = 0; i < 100; i++)
        {
            BBSRenderSurfaceRegistry.capturePlan(WORLD_REPLAY);
        }

        check(throwingCalls.get() <= 2,
            "throwing demand bypassed failure backoff and flooded the sampler");
    }

    private static BBSRenderSurfaceFrame frame(BBSRenderSurfaceStamp stamp)
    {
        return new BBSRenderSurfaceFrame(
            WORLD_REPLAY,
            stamp.generation(),
            stamp.sequence(),
            1L,
            1,
            1,
            true,
            new byte[]{1}
        );
    }

    private static BBSAddonDescriptor descriptor(String id)
    {
        return BBSAddonDescriptor.builder(id)
            .side(BBSAddonSide.CLIENT)
            .capability(BBSAddonCapability.CLIENT_RENDER)
            .build();
    }

    private static void await(BooleanSupplier condition, String message)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);

        while (!condition.getAsBoolean())
        {
            if (System.nanoTime() - deadline >= 0L)
            {
                throw new AssertionError(message);
            }

            try
            {
                Thread.sleep(5L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();

                throw new AssertionError(message, e);
            }
        }
    }

    private static boolean await(CountDownLatch latch, long seconds)
    {
        try
        {
            return latch.await(seconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new AssertionError("interrupted while awaiting surface test latch", e);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalArgument(Runnable action, String message)
    {
        try
        {
            action.run();

            throw new AssertionError(message);
        }
        catch (IllegalArgumentException expected)
        {
            /* Expected. */
        }
    }
}
