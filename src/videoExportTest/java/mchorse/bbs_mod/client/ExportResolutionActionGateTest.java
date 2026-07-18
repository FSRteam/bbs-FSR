package mchorse.bbs_mod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic checks for the render-pending/client-queued export race. */
public final class ExportResolutionActionGateTest
{
    private ExportResolutionActionGateTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertCancelWhilePending();
        assertCancelAfterWrapperQueued();
        assertReplacementFencesStaleWrapper();
        assertFailureCleanupRunsExactlyOnce();
    }

    private static void assertCancelWhilePending()
    {
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        ExportResolutionActionGate gate = gate();

        gate.schedule(() -> true, actions::incrementAndGet, cleanups::incrementAndGet);
        gate.cancelAll();

        check(gate.queuePending() == null, "cancelled pending action remained queueable");
        check(actions.get() == 0, "pending action ran during cancellation");
        check(cleanups.get() == 1, "pending cancellation did not clean up exactly once");
    }

    private static void assertCancelAfterWrapperQueued()
    {
        AtomicInteger actions = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        ExportResolutionActionGate gate = gate();

        gate.schedule(() -> true, actions::incrementAndGet, cleanups::incrementAndGet);
        ExportResolutionActionGate.Action wrapper = gate.queuePending();

        check(wrapper != null, "pending action was not handed to the client wrapper");
        gate.cancelAll();
        wrapper.runIfCurrent();

        check(actions.get() == 0, "wrapper queued before cancellation still ran its action");
        check(cleanups.get() == 1, "queued-wrapper cancellation cleaned up more than once");
    }

    private static void assertReplacementFencesStaleWrapper()
    {
        AtomicInteger firstActions = new AtomicInteger();
        AtomicInteger firstCleanups = new AtomicInteger();
        AtomicInteger secondActions = new AtomicInteger();
        AtomicInteger secondCleanups = new AtomicInteger();
        ExportResolutionActionGate gate = gate();

        gate.schedule(() -> true, firstActions::incrementAndGet, firstCleanups::incrementAndGet);
        ExportResolutionActionGate.Action stale = gate.queuePending();
        gate.schedule(() -> true, secondActions::incrementAndGet, secondCleanups::incrementAndGet);
        ExportResolutionActionGate.Action current = gate.queuePending();

        stale.runIfCurrent();
        current.runIfCurrent();

        check(firstActions.get() == 0, "replacement generation allowed the stale wrapper to run");
        check(firstCleanups.get() == 1, "replacement did not clean up the stale generation exactly once");
        check(secondActions.get() == 1, "current replacement generation did not run exactly once");
        check(secondCleanups.get() == 0, "successful replacement generation ran cancellation cleanup");
    }

    private static void assertFailureCleanupRunsExactlyOnce()
    {
        AtomicInteger cleanups = new AtomicInteger();
        List<ExportResolutionActionGate.FailureStage> failures = new ArrayList<>();
        ExportResolutionActionGate gate = new ExportResolutionActionGate((stage, failure) -> failures.add(stage));

        gate.schedule(
            () -> true,
            () ->
            {
                throw new IllegalStateException("boom");
            },
            cleanups::incrementAndGet
        );
        ExportResolutionActionGate.Action wrapper = gate.queuePending();

        wrapper.runIfCurrent();
        gate.cancelAll();
        wrapper.runIfCurrent();

        check(failures.equals(List.of(ExportResolutionActionGate.FailureStage.ACTION)),
            "action failure was not reported through the deterministic seam");
        check(cleanups.get() == 1, "failed/stale wrapper cleanup did not remain exactly-once");
    }

    private static ExportResolutionActionGate gate()
    {
        return new ExportResolutionActionGate((stage, failure) ->
        {
            throw new AssertionError("unexpected gate failure at " + stage, failure);
        });
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
