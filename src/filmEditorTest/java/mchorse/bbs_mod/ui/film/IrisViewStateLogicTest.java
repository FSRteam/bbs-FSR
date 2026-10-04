package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.utils.iris.IrisViewState;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import mchorse.bbs_mod.utils.iris.ViewSampleClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression checks for nested view scopes and their history contracts. */
public final class IrisViewStateLogicTest
{
    private IrisViewStateLogicTest()
    {}

    public static void main(String[] args)
    {
        runAll();
        System.out.println("IrisViewStateLogicTest: all tests passed");
    }

    public static void runAll()
    {
        try (IrisViewState.Scope outer = IrisViewState.enter("camera-a", true, 4L))
        {
            check("camera-a".equals(IrisViewState.activeViewId()), "outer view was not published");
            check(IrisViewState.shadersEnabled(), "outer shader mode was not published");
            check(IrisViewState.historyEpoch() == 4L, "outer history epoch was not published");

            try (IrisViewState.Scope inner = IrisViewState.enter("camera-b", false, 9L))
            {
                check("camera-b".equals(IrisViewState.activeViewId()), "nested view did not replace active view");
                check(!IrisViewState.shadersEnabled(), "nested shader mode was not isolated");
                check(IrisViewState.historyEpoch() == 9L, "nested history epoch was not isolated");
            }

            check("camera-a".equals(IrisViewState.activeViewId()), "outer view was not restored");
        }

        check(IrisViewState.activeViewId() == null, "view scope leaked after close");
        testPublicationAfterRestore();
        testFailedRestoreCannotPublish();
        testOutOfOrderCloseDoesNotLeak();
        testSampleClockTracksPublishedFrames();
        testExportClockIgnoresRenderSpeed();
        testPartialConstructionOnlyClosesOwnedResources();
        testNormalDestructionIsNotRepeated();
        testFatalCleanupFailureRemainsFatal();
        testRepeatedCleanupFailureDoesNotSuppressItself();
    }

    private static void testPublicationAfterRestore()
    {
        List<String> events = new ArrayList<>();
        IrisViewState.Scope scope = IrisViewState.enter("publish", true, 1L, false, null,
            () -> events.add("restore"), () -> events.add("publish"));

        expectFailure(scope::commit, "a still-active pass must not be published");
        scope.close();
        check(!scope.isCommitted(), "restoration must not imply image publication");
        scope.commit();
        scope.commit();
        scope.close();
        check(events.equals(List.of("restore", "publish")), "publish must follow restoration exactly once");
    }

    private static void testFailedRestoreCannotPublish()
    {
        AtomicInteger published = new AtomicInteger();
        IrisViewState.Scope scope = IrisViewState.enter("failed", true, 1L, false, null,
            () -> { throw new IllegalStateException("restore failed"); }, published::incrementAndGet);

        expectFailure(scope::close, "restore failure must propagate");
        expectFailure(scope::commit, "failed restoration must reject publication");
        check(published.get() == 0, "failed image was published");
        check(IrisViewState.current() == null, "failed restoration leaked the active scope");
    }

    private static void testOutOfOrderCloseDoesNotLeak()
    {
        IrisViewState.Scope outer = IrisViewState.enter("outer", true, 0L);
        IrisViewState.Scope inner = IrisViewState.enter("inner", true, 0L);

        expectFailure(outer::close, "closing a parent first must be rejected");
        inner.close();
        outer.close();
        check(IrisViewState.current() == null, "rejected close must remain recoverable");
    }

    private static void testSampleClockTracksPublishedFrames()
    {
        ViewSampleClock camera = new ViewSampleClock();
        ViewSampleClock other = new ViewSampleClock();
        ViewSampleClock.Sample first = camera.begin(1_000_000_000L, 1F / 60F);

        camera.publish(first);
        ViewSampleClock.Sample discarded = camera.begin(1_016_000_000L, 0F);
        ViewSampleClock.Sample second = camera.begin(1_050_000_000L, 0F);

        check(first.frameCounter() == 0 && second.frameCounter() == 1,
            "skipped and unpublished window frames must not skip TAA samples");
        check(Math.abs(second.frameTime() - 0.05F) < 0.00001F,
            "sample interval must start at the last published image");
        expectFailure(() -> camera.publish(discarded), "late publication must reject superseded samples");
        camera.publish(second);
        check(other.begin(5_000_000_000L, 0F).frameCounter() == 0, "cameras must have independent counters");
    }

    private static void testPartialConstructionOnlyClosesOwnedResources()
    {
        List<String> events = new ArrayList<>();

        ViewResourceOwner.track(new Object(), () -> events.add("primary"));
        ViewResourceOwner owner = ViewResourceOwner.begin();
        ViewResourceOwner.track(new Object(), () -> events.add("texture"));
        ViewResourceOwner.track(new Object(), () ->
        {
            events.add("broken shader");
            throw new IllegalStateException("shader cleanup failed");
        });
        expectFailure(owner::close, "cleanup errors must be observable");
        check(events.equals(List.of("broken shader", "texture")),
            "failed construction must still close earlier resources and preserve the primary pipeline");

        try (ViewResourceOwner next = ViewResourceOwner.begin())
        {
            ViewResourceOwner.track(new Object(), () -> events.add("next"));
        }

        check(events.get(events.size() - 1).equals("next"), "failed cleanup leaked the construction scope");
    }

    private static void testExportClockIgnoresRenderSpeed()
    {
        ViewSampleClock clock = new ViewSampleClock();
        float step = ViewSampleClock.exportFrameTime(120D, true);
        ViewSampleClock.Sample first = clock.beginFixedStep(1_000_000_000L, step);

        clock.publish(first);
        ViewSampleClock.Sample slow = clock.beginFixedStep(8_000_000_000L, step);
        check(Math.abs(slow.frameTime() - 1F / 120F) < 0.000001F,
            "offline shader time must use seconds per film sample, not wall time or Minecraft ticks");
        clock.publish(slow);
        ViewSampleClock.Sample held = clock.beginFixedStep(10_000_000_000L, ViewSampleClock.exportFrameTime(120D, false));
        check(held.frameTime() == 0F && held.frameCounter() == 2,
            "held output frames may refine history without advancing animation time");
        check(ViewSampleClock.exportFrameTime(Double.NaN, true) == 0F,
            "invalid capture timing must never reach shader uniforms");
    }

    private static void testFatalCleanupFailureRemainsFatal()
    {
        List<String> events = new ArrayList<>();
        ViewResourceOwner owner = ViewResourceOwner.begin();
        OutOfMemoryError fatal = new OutOfMemoryError("simulated native allocation failure");

        ViewResourceOwner.track(new Object(), () -> events.add("remaining"));
        ViewResourceOwner.track(new Object(), () -> { throw fatal; });
        ViewResourceOwner.track(new Object(), () -> { throw new IllegalStateException("ordinary cleanup failure"); });

        try
        {
            owner.close();
            throw new AssertionError("fatal cleanup failure was swallowed");
        }
        catch (OutOfMemoryError actual)
        {
            check(actual == fatal && actual.getSuppressed().length == 1,
                "fatal failure must retain earlier cleanup diagnostics");
        }

        check(events.equals(List.of("remaining")), "cleanup must still attempt remaining owned resources");
    }

    private static void testRepeatedCleanupFailureDoesNotSuppressItself()
    {
        AtomicInteger remaining = new AtomicInteger();
        ViewResourceOwner owner = ViewResourceOwner.begin();
        AssertionError failure = new AssertionError("same native cleanup failure");

        ViewResourceOwner.track(new Object(), remaining::incrementAndGet);
        ViewResourceOwner.track(new Object(), () -> { throw failure; });
        ViewResourceOwner.track(new Object(), () -> { throw failure; });

        try
        {
            owner.close();
            throw new AssertionError("cleanup failure was swallowed");
        }
        catch (AssertionError actual)
        {
            check(actual == failure && actual.getSuppressed().length == 0,
                "repeated cleanup failures must not attempt self-suppression");
        }

        check(remaining.get() == 1, "repeated failures must not prevent remaining resource cleanup");
        check(!ViewResourceOwner.isCapturing(), "failed cleanup must end the construction scope");
        owner.close();
    }

    private static void testNormalDestructionIsNotRepeated()
    {
        AtomicInteger deleted = new AtomicInteger();
        Object resource = new Object();
        ViewResourceOwner owner = ViewResourceOwner.begin();

        ViewResourceOwner.track(resource, deleted::incrementAndGet);
        owner.finishConstruction();
        deleted.incrementAndGet();
        ViewResourceOwner.released(resource);
        owner.close();
        owner.close();
        check(deleted.get() == 1, "resource ownership must never delete a reused GL name twice");
    }

    private static void expectFailure(Runnable action, String message)
    {
        try
        {
            action.run();
        }
        catch (IllegalStateException expected)
        {
            return;
        }

        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
