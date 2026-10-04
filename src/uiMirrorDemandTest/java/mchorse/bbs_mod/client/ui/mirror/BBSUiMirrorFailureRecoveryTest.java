package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.client.ui.BBSUiFrame;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorListener;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorSubscription;
import mchorse.bbs_mod.api.client.ui.BBSUiSessionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Failure-first regressions for the silent-stall defects: a bare {@code Error}
 * escaping a frame callback latched the capture gate and killed the handoff
 * worker forever, and a mid-frame failure in the standalone world-replay
 * publisher leaked {@code ACTIVE_FRAME} and disabled capture on the render
 * thread forever. All scenarios must fail before those fixes and pass after.
 */
public final class BBSUiMirrorFailureRecoveryTest
{
    private static final long CALLBACK_TIMEOUT_SECONDS = 3L;

    private BBSUiMirrorFailureRecoveryTest()
    {}

    public static void run() throws Exception
    {
        List<String> failures = new ArrayList<>();

        runScenario(failures, "bareErrorDoesNotLatchCaptureGate", BBSUiMirrorFailureRecoveryTest::bareErrorDoesNotLatchCaptureGate);
        runScenario(failures, "fatalErrorRethrowsAndWorkerRevives", BBSUiMirrorFailureRecoveryTest::fatalErrorRethrowsAndWorkerRevives);
        runScenario(failures, "standaloneReplayFailureDoesNotLeakActiveFrame", BBSUiMirrorFailureRecoveryTest::standaloneReplayFailureDoesNotLeakActiveFrame);

        if (!failures.isEmpty())
        {
            throw new AssertionError("BBSUiMirrorFailureRecoveryTest failures:\n - " + String.join("\n - ", failures));
        }

        System.out.println("BBSUiMirrorFailureRecoveryTest: all scenarios passed");
    }

    /**
     * A bare {@link Error} (not {@link LinkageError}) thrown by an addon frame
     * callback must be accounted and quarantined like any other callback
     * failure. It must not leave {@code callbackStartedNanos} latched, which
     * would keep {@code hasActiveDemand()} false forever and stall all capture.
     */
    private static void bareErrorDoesNotLatchCaptureGate() throws Exception
    {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch failingFrame = new CountDownLatch(1);
        CountDownLatch recoveredFrame = new CountDownLatch(1);
        AtomicInteger frameCalls = new AtomicInteger();
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-failure-bare-error"),
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
                    if (frameCalls.incrementAndGet() == 1)
                    {
                        failingFrame.countDown();

                        throw new AssertionError("deterministic bare Error from addon frame callback");
                    }

                    recoveredFrame.countDown();
                }
            }
        );

        check(subscription.registration().accepted(), "bare-error subscription was rejected");
        subscription.setActive(true);

        long sessionId = BBSUiFrameRecorder.openSession(320, 180, 640, 360);

        await(opened, "bare-error session open callback missing");
        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "bare-error first frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 0F, 0F);
        await(failingFrame, "bare-error frame callback was not attempted");

        awaitCondition(
            () -> !BBSUiMirrorRegistry.hasActiveDemand(),
            "bare Error did not close the capture gate at all"
        );
        awaitCondition(
            BBSUiMirrorRegistry::hasActiveDemand,
            "capture gate did not recover after a bare Error escaped the frame callback"
        );
        check(
            BBSUiMirrorRegistry.diagnostics().callbackErrors() >= 1L,
            "bare Error was not accounted as a callback error: " + BBSUiMirrorRegistry.diagnostics()
        );

        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "post-recovery frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 0F, 0F);
        await(recoveredFrame, "post-recovery frame was not delivered to the listener");

        subscription.close();
        BBSUiFrameRecorder.closeSession(sessionId);
    }

    /**
     * A manually constructed {@link StackOverflowError} must be accounted,
     * reset the callback bookkeeping, and then rethrow, killing the worker.
     * The drain recovery must record the death and let a fresh worker deliver
     * subsequent frames.
     */
    private static void fatalErrorRethrowsAndWorkerRevives() throws Exception
    {
        long baselineRestarts = BBSUiMirrorRegistry.diagnostics().workerRestarts();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch failingFrame = new CountDownLatch(1);
        CountDownLatch revivedFrame = new CountDownLatch(1);
        AtomicInteger frameCalls = new AtomicInteger();
        Set<String> frameThreads = ConcurrentHashMap.newKeySet();
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-failure-fatal-error"),
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
                    frameThreads.add(Thread.currentThread().getName());

                    if (frameCalls.incrementAndGet() == 1)
                    {
                        failingFrame.countDown();

                        /* Constructed, not caused by recursion; the worker thread must die. */
                        throw new StackOverflowError("synthetic fatal error");
                    }

                    revivedFrame.countDown();
                }
            }
        );

        check(subscription.registration().accepted(), "fatal-error subscription was rejected");
        subscription.setActive(true);

        long sessionId = BBSUiFrameRecorder.openSession(320, 180, 640, 360);

        await(opened, "fatal-error session open callback missing");
        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "fatal-error first frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 0F, 0F);
        await(failingFrame, "fatal-error frame callback was not attempted");

        awaitCondition(
            () -> !BBSUiMirrorRegistry.hasActiveDemand(),
            "fatal Error did not close the capture gate at all"
        );
        awaitCondition(
            BBSUiMirrorRegistry::hasActiveDemand,
            "capture gate did not recover after a fatal Error killed the worker"
        );
        awaitCondition(
            () -> BBSUiMirrorRegistry.diagnostics().workerRestarts() > baselineRestarts,
            "worker death was not recorded as a restart: " + BBSUiMirrorRegistry.diagnostics()
        );

        check(BBSUiFrameRecorder.beginFrame(sessionId, 320, 180), "post-revival frame did not begin");
        BBSUiFrameRecorder.endFrame(0, 0F, 0F);
        await(revivedFrame, "frame was not delivered after the worker died");
        check(frameThreads.size() >= 2, "worker thread did not actually restart: " + frameThreads);

        subscription.close();
        BBSUiFrameRecorder.closeSession(sessionId);
    }

    /**
     * A failure between {@code beginFrame} and {@code endFrame} in the
     * standalone world-replay publisher must not leak {@code ACTIVE_FRAME}:
     * the next frame on the same thread must begin and publish normally.
     */
    private static void standaloneReplayFailureDoesNotLeakActiveFrame() throws Exception
    {
        CountDownLatch frameDelivered = new CountDownLatch(1);
        BBSUiMirrorSubscription subscription = BBSUiMirrorRegistry.subscribe(
            descriptor("ui-failure-standalone-leak"),
            frame -> frameDelivered.countDown()
        );

        check(subscription.registration().accepted(), "standalone-leak subscription was rejected");
        subscription.setActive(true);

        boolean thrown = false;

        try
        {
            BBSUiFrameRecorder.publishStandaloneWorldReplayFrame(320, 180, 640, 360, () ->
            {
                throw new IllegalStateException("deterministic standalone replay frame failure");
            });
        }
        catch (IllegalStateException expected)
        {
            thrown = true;
        }

        check(thrown, "injected standalone replay failure did not propagate");

        BBSUiFrameRecorder.publishStandaloneWorldReplayFrame(320, 180, 640, 360);

        BBSUiFrameRecorder.Diagnostics diagnostics = BBSUiFrameRecorder.diagnostics();

        check(!diagnostics.activeFrameLeaked(), "ACTIVE_FRAME leaked after a mid-frame failure: " + diagnostics);
        check(diagnostics.frameAlreadyActive() == 0L, "beginFrame saw a leaked active frame: " + diagnostics);
        await(frameDelivered, "follow-up standalone replay frame was not published after the failure");

        BBSUiFrameRecorder.closeStandaloneWorldReplaySession();
        subscription.close();
    }

    private static void runScenario(List<String> failures, String name, ThrowingScenario scenario)
    {
        resetState();

        try
        {
            scenario.run();
        }
        catch (Throwable failure)
        {
            failures.add(name + " -> " + failure);
        }
        finally
        {
            resetState();
        }
    }

    private static BBSAddonDescriptor descriptor(String id)
    {
        return BBSAddonDescriptor.builder(id)
            .side(BBSAddonSide.CLIENT)
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();
    }

    private static void resetState()
    {
        BBSUiFrameRecorder.closeAllSessions();
        BBSUiMirrorRegistry.resetForTests();
        BBSUiFrameRecorder.resetDiagnosticsForTests();
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException
    {
        check(latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS);

        while (!condition.getAsBoolean())
        {
            if (System.nanoTime() - deadline >= 0L)
            {
                throw new AssertionError(message);
            }

            Thread.sleep(5L);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingScenario
    {
        void run() throws Exception;
    }
}
