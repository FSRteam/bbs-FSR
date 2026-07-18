package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.client.ui.BBSUiOpenResult;
import mchorse.bbs_mod.api.client.ui.BBSUiOpenStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic remote Dashboard mailbox, lifecycle, and cancellation checks. */
public final class BBSUiOpenDispatcherTest
{
    private BBSUiOpenDispatcherTest()
    {}

    public static void main(String[] args)
    {
        runAll();
        System.out.println("BBSUiOpenDispatcherTest: all tests passed");
    }

    static void runAll()
    {
        runIsolated(BBSUiOpenDispatcherTest::assertAccessAndFixedTargetStatuses);
        runIsolated(BBSUiOpenDispatcherTest::assertBoundedDuplicateAdmission);
        runIsolated(BBSUiOpenDispatcherTest::assertCancellationPreventsOpen);
        runIsolated(BBSUiOpenDispatcherTest::assertResetLinearizesWithAdmission);
        runIsolated(BBSUiOpenDispatcherTest::assertAdmittedOpenLinearizesBeforeReset);
        runIsolated(BBSUiOpenDispatcherTest::assertStateChangeAndResetAreStale);
        runIsolated(BBSUiOpenDispatcherTest::assertReplayTransitionIsStaleButActiveReplayCanOpen);
        runIsolated(BBSUiOpenDispatcherTest::assertFailureAndShutdownComplete);
    }

    private static void assertAccessAndFixedTargetStatuses()
    {
        FakeTarget target = new FakeTarget();
        target.world = false;
        BBSUiOpenDispatcher.startForTesting(target);

        check(completed(BBSUiOpenDispatcher.requestDashboardOpen(null)).status() == BBSUiOpenStatus.REJECTED,
            "null descriptor was not rejected");
        check(completed(BBSUiOpenDispatcher.requestDashboardOpen(descriptor("no-capability", false))).status() ==
            BBSUiOpenStatus.REJECTED, "missing CLIENT_UI capability was not rejected");

        BBSAddonDescriptor addon = descriptor("status", true);
        CompletableFuture<BBSUiOpenResult> noWorld = BBSUiOpenDispatcher.requestDashboardOpen(addon);
        check(!noWorld.isDone(), "accepted no-world request completed off the client tick");
        BBSUiOpenDispatcher.tickForTesting(target);
        check(completed(noWorld).status() == BBSUiOpenStatus.NO_WORLD, "no-world status was not preserved");

        target.world = true;
        target.screen = BBSUiOpenDispatcher.ScreenState.OTHER;
        BBSUiOpenDispatcher.tickForTesting(target);
        CompletableFuture<BBSUiOpenResult> busy = BBSUiOpenDispatcher.requestDashboardOpen(addon);
        BBSUiOpenDispatcher.tickForTesting(target);
        check(completed(busy).status() == BBSUiOpenStatus.BUSY, "other screen was replaced by remote open");

        target.screen = BBSUiOpenDispatcher.ScreenState.DASHBOARD;
        BBSUiOpenDispatcher.tickForTesting(target);
        CompletableFuture<BBSUiOpenResult> already = BBSUiOpenDispatcher.requestDashboardOpen(addon);
        BBSUiOpenDispatcher.tickForTesting(target);
        check(completed(already).status() == BBSUiOpenStatus.ALREADY_OPEN && completed(already).opened(),
            "existing Dashboard was not idempotent");
        check(target.opens == 0, "existing Dashboard was opened twice");
    }

    private static void assertBoundedDuplicateAdmission()
    {
        FakeTarget target = new FakeTarget();
        BBSUiOpenDispatcher.startForTesting(target);
        BBSAddonDescriptor firstAddon = descriptor("duplicate", true);
        CompletableFuture<BBSUiOpenResult> first = BBSUiOpenDispatcher.requestDashboardOpen(firstAddon);

        check(completed(BBSUiOpenDispatcher.requestDashboardOpen(firstAddon)).status() == BBSUiOpenStatus.BUSY,
            "duplicate addon request was not rejected as busy");

        int capacity = BBSUiOpenDispatcher.pendingCapacityForTesting();
        List<CompletableFuture<BBSUiOpenResult>> accepted = new ArrayList<>();
        accepted.add(first);

        for (int index = 1; index < capacity; index++)
        {
            accepted.add(BBSUiOpenDispatcher.requestDashboardOpen(descriptor("capacity-" + index, true)));
        }

        check(BBSUiOpenDispatcher.pendingCountForTesting() == capacity,
            "open mailbox did not retain its advertised capacity");
        check(completed(BBSUiOpenDispatcher.requestDashboardOpen(descriptor("overflow", true))).status() ==
            BBSUiOpenStatus.BUSY, "open mailbox overflow was not rejected");

        BBSUiOpenDispatcher.reset();
        check(accepted.stream().allMatch((future) -> completed(future).status() == BBSUiOpenStatus.STALE),
            "reset did not resolve every accepted open request as stale");
    }

    private static void assertCancellationPreventsOpen()
    {
        FakeTarget target = new FakeTarget();
        BBSUiOpenDispatcher.startForTesting(target);
        CompletableFuture<BBSUiOpenResult> future =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("cancel", true));

        check(future.cancel(false), "queued open future did not accept cancellation");
        BBSUiOpenDispatcher.tickForTesting(target);
        check(target.opens == 0, "cancelled queued request still opened the Dashboard");
        check(BBSUiOpenDispatcher.pendingCountForTesting() == 0, "cancelled request remained in the mailbox");
    }

    private static void assertResetLinearizesWithAdmission()
    {
        FakeTarget target = new FakeTarget();
        BBSUiOpenDispatcher.startForTesting(target);
        AtomicReference<CompletableFuture<BBSUiOpenResult>> result = new AtomicReference<>();
        Thread requester = new Thread(() -> result.set(
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("reset-admission-race", true))
        ), "open-admission-race-test");

        synchronized (BBSUiOpenDispatcher.admissionLockForTesting())
        {
            requester.start();
            long deadline = System.nanoTime() + 2_000_000_000L;

            while (requester.getState() != Thread.State.BLOCKED && System.nanoTime() - deadline < 0L)
            {
                Thread.onSpinWait();
            }

            check(requester.getState() == Thread.State.BLOCKED,
                "request thread did not reach the admission lock");
            BBSUiOpenDispatcher.reset();
        }

        try
        {
            requester.join(2_000L);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining admission race requester", exception);
        }

        check(!requester.isAlive(), "admission race requester did not finish");
        check(completed(result.get()).status() == BBSUiOpenStatus.STALE,
            "request crossing reset admission was not stale");

        BBSUiOpenDispatcher.startForTesting(target);
        BBSUiOpenDispatcher.tickForTesting(target);
        check(target.opens == 0, "pre-reset request crossed lifecycle and opened the Dashboard");
    }

    private static void assertAdmittedOpenLinearizesBeforeReset()
    {
        FakeTarget target = new FakeTarget();
        target.openEntered = new CountDownLatch(1);
        target.openRelease = new CountDownLatch(1);
        BBSUiOpenDispatcher.startForTesting(target);
        CompletableFuture<BBSUiOpenResult> future =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("admitted-reset-race", true));
        Thread ticker = new Thread(() -> BBSUiOpenDispatcher.tickForTesting(target),
            "open-admitted-tick-test");
        Thread resetter = new Thread(BBSUiOpenDispatcher::reset, "open-admitted-reset-test");

        ticker.start();
        await(target.openEntered, "native open did not reach its side-effect gate");
        resetter.start();
        awaitBlocked(resetter, "reset entered between admission and native open");
        target.openRelease.countDown();
        join(ticker, "admitted tick");
        join(resetter, "admitted reset");

        check(completed(future).status() == BBSUiOpenStatus.OPENED,
            "reset overtook an already-linearized native open");
        check(target.opens == 1, "linearized admitted request did not open exactly once");
        check(BBSUiOpenDispatcher.pendingCountForTesting() == 0,
            "admitted/reset race left mailbox work behind");
    }

    private static void assertStateChangeAndResetAreStale()
    {
        FakeTarget target = new FakeTarget();
        BBSUiOpenDispatcher.startForTesting(target);
        CompletableFuture<BBSUiOpenResult> changed =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("screen-change", true));
        target.screen = BBSUiOpenDispatcher.ScreenState.OTHER;
        BBSUiOpenDispatcher.tickForTesting(target);

        check(completed(changed).status() == BBSUiOpenStatus.STALE,
            "screen transition before drain was not stale");
        check(target.opens == 0, "stale screen request opened the Dashboard");

        target.screen = BBSUiOpenDispatcher.ScreenState.NONE;
        BBSUiOpenDispatcher.tickForTesting(target);
        CompletableFuture<BBSUiOpenResult> reset =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("reset", true));
        BBSUiOpenDispatcher.reset();
        check(completed(reset).status() == BBSUiOpenStatus.STALE,
            "lifecycle reset did not complete pending open as stale");
    }

    private static void assertReplayTransitionIsStaleButActiveReplayCanOpen()
    {
        FakeTarget target = new FakeTarget();
        BBSUiOpenDispatcher.startForTesting(target);
        CompletableFuture<BBSUiOpenResult> changed =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("replay-change", true));
        target.replay = true;
        BBSUiOpenDispatcher.tickForTesting(target);
        check(completed(changed).status() == BBSUiOpenStatus.STALE,
            "Replay lifecycle transition before drain was not stale");

        CompletableFuture<BBSUiOpenResult> active =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("replay-active", true));
        BBSUiOpenDispatcher.tickForTesting(target);
        check(completed(active).status() == BBSUiOpenStatus.OPENED && target.opens == 1,
            "active world Replay prevented opening its native Dashboard controls");
    }

    private static void assertFailureAndShutdownComplete()
    {
        FakeTarget target = new FakeTarget();
        BBSUiOpenDispatcher.startForTesting(target);
        target.failure = new IllegalStateException("boom");
        CompletableFuture<BBSUiOpenResult> failed =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("failure", true));
        BBSUiOpenDispatcher.tickForTesting(target);
        check(completed(failed).status() == BBSUiOpenStatus.FAILED,
            "native open exception did not become FAILED");

        target.failure = null;
        target.screen = BBSUiOpenDispatcher.ScreenState.NONE;
        BBSUiOpenDispatcher.tickForTesting(target);
        CompletableFuture<BBSUiOpenResult> stopping =
            BBSUiOpenDispatcher.requestDashboardOpen(descriptor("stopping", true));
        BBSUiOpenDispatcher.shutdown();
        check(completed(stopping).status() == BBSUiOpenStatus.STALE,
            "shutdown did not resolve pending open as stale");
        check(completed(BBSUiOpenDispatcher.requestDashboardOpen(descriptor("after-stop", true))).status() ==
            BBSUiOpenStatus.STALE, "request after shutdown was not stale");
    }

    private static BBSAddonDescriptor descriptor(String addonId, boolean capability)
    {
        BBSAddonDescriptor.Builder builder = BBSAddonDescriptor.builder(addonId).side(BBSAddonSide.CLIENT);

        if (capability)
        {
            builder.capability(BBSAddonCapability.CLIENT_UI);
        }

        return builder.build();
    }

    private static BBSUiOpenResult completed(CompletableFuture<BBSUiOpenResult> future)
    {
        check(future.isDone(), "open future is still pending");
        check(!future.isCompletedExceptionally(), "open future completed exceptionally");

        return future.join();
    }

    private static void await(CountDownLatch latch, String message)
    {
        try
        {
            check(latch.await(2L, TimeUnit.SECONDS), message);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    private static void awaitBlocked(Thread thread, String message)
    {
        long deadline = System.nanoTime() + 2_000_000_000L;

        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() - deadline < 0L)
        {
            Thread.onSpinWait();
        }

        check(thread.getState() == Thread.State.BLOCKED, message);
    }

    private static void join(Thread thread, String label)
    {
        try
        {
            thread.join(2_000L);
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining " + label, exception);
        }

        check(!thread.isAlive(), label + " thread did not finish");
    }

    private static void runIsolated(Runnable assertion)
    {
        BBSUiOpenDispatcher.resetForTesting();

        try
        {
            assertion.run();
        }
        finally
        {
            BBSUiOpenDispatcher.resetForTesting();
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class FakeTarget implements BBSUiOpenDispatcher.OpenTarget
    {
        private boolean world = true;
        private boolean replay;
        private BBSUiOpenDispatcher.ScreenState screen = BBSUiOpenDispatcher.ScreenState.NONE;
        private RuntimeException failure;
        private CountDownLatch openEntered;
        private CountDownLatch openRelease;
        private int opens;

        @Override
        public boolean hasWorld()
        {
            return this.world;
        }

        @Override
        public BBSUiOpenDispatcher.ScreenState screenState()
        {
            return this.screen;
        }

        @Override
        public boolean worldReplayActive()
        {
            return this.replay;
        }

        @Override
        public void openDashboard()
        {
            if (this.failure != null)
            {
                throw this.failure;
            }
            if (this.openEntered != null)
            {
                this.openEntered.countDown();
                await(this.openRelease, "native open release was not signaled");
            }

            this.opens++;
            this.screen = BBSUiOpenDispatcher.ScreenState.DASHBOARD;
        }
    }
}
