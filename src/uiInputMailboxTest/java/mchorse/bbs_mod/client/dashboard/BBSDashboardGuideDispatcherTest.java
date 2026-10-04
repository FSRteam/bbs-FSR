package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchorResult;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchorStatus;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardNavigationResult;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardNavigationStatus;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Deterministic client-thread dispatch checks for Dashboard guide requests. */
public final class BBSDashboardGuideDispatcherTest
{
    private BBSDashboardGuideDispatcherTest() {}

    public static void runAll()
    {
        rejectsInvalidAccessWithoutScheduling();
        completesOnlyAfterClientExecution();
        convertsTargetFailuresToTypedResults();
    }

    private static void rejectsInvalidAccessWithoutScheduling()
    {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        BBSDashboardNavigationResult missing = BBSDashboardGuideDispatcher.navigateForTesting(
            null, "film", executor, target
        ).join();
        BBSDashboardNavigationResult noCapability = BBSDashboardGuideDispatcher.navigateForTesting(
            BBSAddonDescriptor.builder("guide_no_capability").build(), "film", executor, target
        ).join();

        check(missing.status() == BBSDashboardNavigationStatus.REJECTED,
            "null descriptor was not rejected");
        check(noCapability.status() == BBSDashboardNavigationStatus.REJECTED,
            "missing CLIENT_UI capability was not rejected");
        check(executor.pending() == 0 && target.navigationCalls == 0,
            "rejected guide request reached the client executor");
    }

    private static void completesOnlyAfterClientExecution()
    {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        BBSAddonDescriptor descriptor = descriptor("guide_dispatch");
        CompletableFuture<BBSDashboardNavigationResult> navigation =
            BBSDashboardGuideDispatcher.navigateForTesting(descriptor, "morphing", executor, target);
        CompletableFuture<BBSDashboardAnchorResult> anchor =
            BBSDashboardGuideDispatcher.resolveAnchorForTesting(
                descriptor, "dashboard.panel_button.morphing", executor, target
            );

        check(!navigation.isDone() && !anchor.isDone(),
            "accepted guide request completed before client execution");
        check(executor.pending() == 2, "accepted guide requests were not scheduled");

        executor.runNext();
        check(navigation.join().status() == BBSDashboardNavigationStatus.NAVIGATED,
            "navigation result was not preserved");
        check(target.navigationCalls == 1, "navigation target was not invoked exactly once");
        check(!anchor.isDone(), "anchor request completed before its client turn");

        executor.runNext();
        BBSDashboardAnchorResult resolved = anchor.join();
        check(resolved.status() == BBSDashboardAnchorStatus.AVAILABLE && resolved.hittable(),
            "anchor result was not preserved");
        check(target.anchorCalls == 1, "anchor target was not invoked exactly once");
    }

    private static void convertsTargetFailuresToTypedResults()
    {
        QueueExecutor executor = new QueueExecutor();
        FakeTarget target = new FakeTarget();
        target.failure = new IllegalStateException("expected guide failure");
        CompletableFuture<BBSDashboardNavigationResult> navigation =
            BBSDashboardGuideDispatcher.navigateForTesting(
                descriptor("guide_failure"), "missing", executor, target
            );

        executor.runNext();
        check(navigation.join().status() == BBSDashboardNavigationStatus.FAILED,
            "target exception escaped instead of becoming FAILED");
        check(!navigation.isCompletedExceptionally(), "guide failure completed exceptionally");
    }

    private static BBSAddonDescriptor descriptor(String addonId)
    {
        return BBSAddonDescriptor.builder(addonId)
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class QueueExecutor implements Executor
    {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command)
        {
            this.tasks.addLast(command);
        }

        private int pending()
        {
            return this.tasks.size();
        }

        private void runNext()
        {
            Runnable task = this.tasks.pollFirst();

            check(task != null, "client executor had no pending task");
            task.run();
        }
    }

    private static final class FakeTarget implements BBSDashboardGuideDispatcher.GuideTarget
    {
        private RuntimeException failure;
        private int navigationCalls;
        private int anchorCalls;

        @Override
        public BBSDashboardNavigationResult navigate(String panelId)
        {
            this.navigationCalls++;

            if (this.failure != null)
            {
                throw this.failure;
            }

            return new BBSDashboardNavigationResult(
                BBSDashboardNavigationStatus.NAVIGATED,
                panelId,
                "switched"
            );
        }

        @Override
        public BBSDashboardAnchorResult resolveAnchor(String anchorId)
        {
            this.anchorCalls++;

            if (this.failure != null)
            {
                throw this.failure;
            }

            return new BBSDashboardAnchorResult(
                BBSDashboardAnchorStatus.AVAILABLE,
                anchorId,
                10,
                20,
                30,
                40,
                true,
                true,
                "resolved"
            );
        }
    }
}
