package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.client.render.multiview.FilmViewRenderer;
import mchorse.bbs_mod.client.render.multiview.RenderStateRestorer;
import mchorse.bbs_mod.client.render.multiview.SodiumViewAdapter;
import mchorse.bbs_mod.client.render.multiview.ViewBudgetScheduler;
import mchorse.bbs_mod.client.render.multiview.ViewGpuTiming;
import mchorse.bbs_mod.client.render.multiview.ViewPassContext;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.client.render.multiview.ViewTargetSize;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Behavioral checks for the production frame lifecycle, timing, publication and budget helpers. */
public final class MultiViewRenderBehaviorTest
{
    private static final long MILLIS = 1_000_000L;

    private MultiViewRenderBehaviorTest()
    {}

    public static void main(String[] args)
    {
        Runnable restore = HeadlessClientTestBootstrap.install();

        try
        {
            runAll();
        }
        finally
        {
            restore.run();
        }
        System.out.println("MultiViewRenderBehaviorTest: all tests passed");
    }

    public static void runAll()
    {
        consecutiveFramesPrepareExactlyOnce();
        failedFramesReleaseTheOuterGuard();
        cleanupRunsInReverseDespiteFailures();
        fatalCleanupAndRepeatedFailuresRemainSafe();
        cpuAndGpuCostsRemainSeparate();
        preparationDoesNotTrainSteadyRenderingCost();
        wholeFramesExcludeTheirAuxiliaryPasses();
        pendingGpuQueriesNeverBlockOrGetOverwritten();
        queryDeletionFailureStillReleasesEverySlot();
        camerasShareOneMeshingAllowance();
        sortingResultsArriveBeforeCompletionIsSignalled();
        viewRefreshRatesRemainDistinct();
        viewsCompeteForOneFrameBudget();
        editableBudgetsRemainSharedAndBounded();
        changedPerformancePreferencesReconsiderOnlyAuxiliaryQuality();
        coldViewsAndLostBaselinesRemainUnmeasured();
        minimumQualityKeepsItsCadenceAndReportsBudgetFailure();
        historyAndFailurePreserveThePublishedImage();
        cameraCutsAndExportFenceOnlyTheirOwnHistory();
        disposalAllowsAViewToWarmUpAgain();
        targetTiersAndResizeDebouncePreserveQuality();
        cameraContextsCopyPoseAndOwnProjection();
    }

    private static void consecutiveFramesPrepareExactlyOnce()
    {
        FilmViewRenderer renderer = new FilmViewRenderer();
        List<String> events = new ArrayList<>();

        for (int frame = 0; frame < 6; frame++)
        {
            boolean multiView = frame >= 3;
            renderer.renderFrame(() -> events.add("prepare"), () ->
            {
                if (multiView)
                {
                    events.add("left");
                    events.add("right");
                }
            }, () -> events.add("primary"));
        }

        check(renderer.getPreparedFrames() == 6L, "every single- and multi-view frame must prepare once");
        check(events.equals(List.of("prepare", "primary", "prepare", "primary", "prepare", "primary",
            "prepare", "left", "right", "primary", "prepare", "left", "right", "primary",
            "prepare", "left", "right", "primary")), "auxiliary rendering must precede the primary pass");

        renderer.renderFrame(() -> events.add("outer"), () -> renderer.renderFrame(
            () -> { throw new AssertionError("nested rendering prepared the scene twice"); },
            () -> { throw new AssertionError("nested rendering scheduled auxiliary views"); },
            () -> events.add("nested")), () -> events.add("last"));
        check(!renderer.isActive() && renderer.getPreparedFrames() == 7L, "nested rendering leaked the outer guard");
    }

    private static void failedFramesReleaseTheOuterGuard()
    {
        FilmViewRenderer renderer = new FilmViewRenderer();

        for (int stage = 0; stage < 3; stage++)
        {
            int failingStage = stage;
            IllegalStateException expected = new IllegalStateException("failed frame stage " + stage);
            Runnable[] actions = new Runnable[3];

            for (int index = 0; index < actions.length; index++)
            {
                int actionIndex = index;
                actions[index] = () ->
                {
                    if (actionIndex == failingStage)
                    {
                        throw expected;
                    }
                };
            }

            check(expect(IllegalStateException.class, () -> renderer.renderFrame(actions[0], actions[1], actions[2])) == expected,
                "the original frame failure must propagate");
            check(!renderer.isActive(), "failed preparation or rendering latched the outer frame");
            renderer.renderFrame(() -> {}, () -> {}, () -> {});
        }

        check(renderer.getPreparedFrames() == 6L, "a failed frame must not prevent preparation of the next frame");
    }

    private static void cleanupRunsInReverseDespiteFailures()
    {
        List<String> events = new ArrayList<>();
        RenderStateRestorer restorations = new RenderStateRestorer();
        IllegalStateException first = new IllegalStateException("matrix cleanup");
        IllegalArgumentException second = new IllegalArgumentException("target cleanup");
        restorations.add(() -> { events.add("target"); throw second; });
        restorations.add(() -> { events.add("matrix"); throw first; });
        restorations.add(() -> events.add("camera"));

        check(expect(IllegalStateException.class, restorations::close) == first, "the first cleanup failure was lost");
        check(events.equals(List.of("camera", "matrix", "target")), "every cleanup must run in reverse registration order");
        check(first.getSuppressed().length == 1 && first.getSuppressed()[0] == second, "later cleanup failure was not retained");
        restorations.close();
        check(events.size() == 3, "closing an already restored scope must be harmless");
    }

    private static void fatalCleanupAndRepeatedFailuresRemainSafe()
    {
        RenderStateRestorer restorations = new RenderStateRestorer();
        List<String> events = new ArrayList<>();
        OutOfMemoryError fatal = new OutOfMemoryError("simulated native allocation failure");
        IllegalStateException ordinary = new IllegalStateException("earlier cleanup failed");
        restorations.add(() -> events.add("remaining"));
        restorations.add(() -> { throw fatal; });
        restorations.add(() -> { throw ordinary; });

        check(expect(OutOfMemoryError.class, restorations::close) == fatal, "fatal cleanup must not be suppressed by an ordinary failure");
        check(fatal.getSuppressed().length == 1 && fatal.getSuppressed()[0] == ordinary,
            "fatal cleanup must preserve the preceding failure");
        check(events.equals(List.of("remaining")), "fatal cleanup must still attempt other restorations");

        RenderStateRestorer repeated = new RenderStateRestorer();
        repeated.add(() -> { throw ordinary; });
        repeated.add(() -> { throw ordinary; });
        check(expect(IllegalStateException.class, repeated::close) == ordinary,
            "the same failure instance must not trigger self-suppression");

        RenderStateRestorer errors = new RenderStateRestorer();
        AssertionError assertion = new AssertionError("renderer cleanup contract");
        errors.add(() -> { throw assertion; });
        errors.add(() -> { throw new IllegalStateException("ordinary"); });
        check(expect(AssertionError.class, errors::close) == assertion, "an Error must retain its error classification");
    }

    private static void cpuAndGpuCostsRemainSeparate()
    {
        TimingFixture fixture = new TimingFixture();
        ViewGpuTiming.Spec shaded = new ViewGpuTiming.Spec(640, 360, true);
        ViewGpuTiming.Spec plain = new ViewGpuTiming.Spec(320, 180, false);
        fixture.sample(shaded, 3 * MILLIS, 7 * MILLIS);
        check(fixture.timing.getLastNanos() == 3 * MILLIS, "CPU cost must use the CPU clock");
        check(fixture.timing.getLastGpuNanos() == 7 * MILLIS, "GPU cost must use GPU timestamp differences");
        check(fixture.timing.getEstimatedNanos(shaded) == 7 * MILLIS, "overlapping CPU/GPU costs must not be added");
        fixture.sample(plain, 11 * MILLIS, 2 * MILLIS);
        check(fixture.timing.getEstimatedNanos(plain) == 11 * MILLIS, "CPU-bound submissions must remain visible");
        check(fixture.timing.getEstimatedNanos(shaded) == 7 * MILLIS, "changing target size/backend must not mix estimates");
        fixture.timing.close();
    }

    private static void pendingGpuQueriesNeverBlockOrGetOverwritten()
    {
        TimingFixture fixture = new TimingFixture();
        fixture.queries.resultsReady = false;
        ViewGpuTiming.Spec spec = new ViewGpuTiming.Spec(640, 360, true);

        for (int sample = 0; sample < 12; sample++)
        {
            fixture.sample(spec, MILLIS, 2 * MILLIS);
        }

        check(fixture.queries.created == 16, "a full query ring must stay bounded at eight timestamp pairs");
        check(fixture.queries.timestamped == 16, "pending queries must never be reused before their results are ready");
        check(fixture.queries.read == 0, "unready GPU query results must never be read");
        check(!fixture.timing.hasGpuSample(spec) && fixture.timing.hasSample(spec), "CPU timing must continue while GPU queries are pending");
        fixture.queries.resultsReady = true;
        fixture.timing.poll();
        check(fixture.queries.read == 16 && fixture.timing.hasGpuSample(spec), "ready results should drain without another render");
        fixture.sample(spec, MILLIS, 2 * MILLIS);
        check(fixture.queries.created == 16 && fixture.queries.timestamped == 18, "completed query slots should be reused");
        fixture.timing.close();
        check(fixture.queries.deleted.size() == 16, "all query names must be deleted on disposal");
    }

    private static void preparationDoesNotTrainSteadyRenderingCost()
    {
        TimingFixture fixture = new TimingFixture();
        ViewGpuTiming.Spec spec = new ViewGpuTiming.Spec(320, 180, true);
        fixture.queries.blockedThrough = 2;
        fixture.timing.begin(spec);
        fixture.timing.markPreparation();
        fixture.cpu.nanos += 2_000 * MILLIS;
        fixture.gpu.nanos += 2_000 * MILLIS;
        fixture.timing.end();
        fixture.timing.poll();
        check(fixture.timing.wasLastSamplePreparation() && fixture.timing.getLastPreparationCpuNanos() == 2_000 * MILLIS,
            "allocation and compilation latency must remain observable");
        check(!fixture.timing.hasSample(spec) && !fixture.timing.hasGpuSample(spec)
            && fixture.timing.getEstimatedNanos(spec) == 0L,
            "preparation must not train the steady-state rendering budget");

        fixture.sample(spec, MILLIS, 2 * MILLIS);
        check(!fixture.timing.wasLastSamplePreparation() && fixture.timing.getEstimatedNanos(spec) == 2 * MILLIS,
            "the first steady sample must be usable while preparation timestamps are delayed");
        check(fixture.timing.getLastPreparationGpuNanos() == -1L, "unready preparation timestamps must remain unmeasured");
        fixture.queries.blockedThrough = 0;
        fixture.timing.poll();
        check(fixture.timing.getLastPreparationGpuNanos() == 2_000 * MILLIS
            && fixture.timing.getEstimatedNanos(spec) == 2 * MILLIS,
            "late startup GPU results must be reported without poisoning the steady estimate");
        fixture.timing.close();
    }

    private static void wholeFramesExcludeTheirAuxiliaryPasses()
    {
        for (int count = 0; count <= 3; count++)
        {
            TimingFixture fixture = new TimingFixture();
            ViewGpuTiming.Spec primary = new ViewGpuTiming.Spec(1280, 720, true);
            ViewGpuTiming.Spec secondary = new ViewGpuTiming.Spec(320, 180, false);
            List<ViewGpuTiming> children = new ArrayList<>();
            fixture.timing.begin(primary);
            fixture.cpu.nanos += 2 * MILLIS;
            fixture.gpu.nanos += 3 * MILLIS;

            for (int index = 0; index < count; index++)
            {
                ViewGpuTiming child = new ViewGpuTiming(fixture.cpu, fixture.queries);
                children.add(child);
                child.begin(secondary);
                fixture.cpu.nanos += 11 * MILLIS;
                fixture.gpu.nanos += 17 * MILLIS;
                child.end();
                fixture.timing.exclude(child.getLastSample());
            }

            fixture.cpu.nanos += 5 * MILLIS;
            fixture.gpu.nanos += 7 * MILLIS;
            fixture.timing.end();
            fixture.timing.poll();
            check(fixture.timing.getLastNanos() == (7L + 11L * count) * MILLIS
                && fixture.timing.getLastGpuNanos() == (10L + 17L * count) * MILLIS,
                "raw frame measurements must retain all submitted work");

            if (count > 0)
            {
                check(!fixture.timing.hasGpuSample(primary) && fixture.timing.getEstimatedNanos(primary) == 7 * MILLIS,
                    "the frame must wait for child GPU timestamps while its CPU baseline excludes their submissions");
            }

            for (ViewGpuTiming child : children)
            {
                child.poll();
            }

            fixture.timing.poll();
            check(fixture.timing.hasGpuSample(primary) && fixture.timing.getEstimatedNanos(primary) == 10 * MILLIS,
                "the complete primary-frame baseline must not inflate when more auxiliary views are rendered");
            fixture.timing.close();

            for (ViewGpuTiming child : children)
            {
                child.close();
            }
        }
    }

    private static void queryDeletionFailureStillReleasesEverySlot()
    {
        TimingFixture fixture = new TimingFixture();
        fixture.queries.resultsReady = false;
        fixture.sample(new ViewGpuTiming.Spec(320, 180, false), MILLIS, MILLIS);
        fixture.sample(new ViewGpuTiming.Spec(640, 360, false), MILLIS, MILLIS);
        fixture.queries.failDeletion = 3;
        expect(IllegalStateException.class, fixture.timing::close);
        check(fixture.queries.deleted.size() == 4, "one failed query deletion must not skip the remaining query names");
        fixture.timing.close();
        check(fixture.queries.deleted.size() == 4, "failed cleanup must not later delete a reused query name");
    }

    private static void viewRefreshRatesRemainDistinct()
    {
        ViewRenderState active = measuredView("active", 320, 180, 200_000L, 200_000L);
        ViewRenderState idle = measuredView("idle", 320, 180, 200_000L, 200_000L);
        idle.setRequestedRefreshRate(15);
        active.publishAt(1L, null, null, null, 200_000L, 1_000 * MILLIS);
        idle.publishAt(1L, null, null, null, 200_000L, 1_000 * MILLIS);
        ViewBudgetScheduler scheduler = new ViewBudgetScheduler();
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.shouldRender(active, 1_034 * MILLIS), "30 Hz view should refresh after 34 ms");
        check(!scheduler.shouldRender(idle, 1_034 * MILLIS), "15 Hz view should not refresh after only 34 ms");
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.shouldRender(idle, 1_067 * MILLIS), "15 Hz view should refresh after 67 ms");
        active.dispose();
        idle.dispose();
    }

    private static void camerasShareOneMeshingAllowance()
    {
        Object renderer = new Object();
        SodiumViewAdapter.beginFrame();

        try
        {
            Class<?> collectorType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector");
            Class<?> jobType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob");
            Method collect = SodiumViewAdapter.class.getMethod("meshingCollector", Object.class, int.class, int.class, Consumer.class);
            Method hasBudget = collectorType.getMethod("hasBudgetFor", int.class, boolean.class);
            Method submit = collectorType.getMethod("addSubmittedJob", jobType);
            Consumer<Object> results = result -> {};
            Object first = collect.invoke(null, renderer, 20, 1, results);
            check((Boolean) hasBudget.invoke(first, 10, false), "the first camera should receive the frame's meshing budget");
            submit.invoke(first, fakeChunkJob(jobType, false));
            submit.invoke(first, fakeChunkJob(jobType, false));
            Object second = collect.invoke(null, renderer, 20, 1, results);
            check(first == second && !(Boolean) hasBudget.invoke(second, 10, false),
                "another camera must not receive another full terrain rebuild allowance");
            check((Boolean) hasBudget.invoke(second, 1, false), "exhausting rebuild capacity must not spend low-effort capacity");
            SodiumViewAdapter.endFrame();
            SodiumViewAdapter.beginFrame();
            Object nextFrame = collect.invoke(null, renderer, 20, 1, results);
            check(nextFrame != first && (Boolean) hasBudget.invoke(nextFrame, 10, false),
                "a new outer frame must receive its own meshing allowance");
        }
        catch (ReflectiveOperationException failure)
        {
            throw new AssertionError("Sodium's runtime collector contract changed", failure);
        }
        finally
        {
            SodiumViewAdapter.endFrame();
        }
    }

    private static void sortingResultsArriveBeforeCompletionIsSignalled()
    {
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch allowDelivery = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try
        {
            Class<?> collectorType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector");
            Class<?> jobType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJob");
            Class<?> builderType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder");
            Class<?> resultType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult");
            Method finish = collectorType.getMethod("onJobFinished", resultType);
            Method await = collectorType.getMethod("awaitCompletion", builderType);
            Consumer<Object> results = result ->
            {
                delivering.countDown();

                try
                {
                    check(allowDelivery.await(2L, TimeUnit.SECONDS), "sorting result delivery was never released");
                    published.set(true);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("sorting result delivery was interrupted", interrupted);
                }
            };
            Object collector = SodiumViewAdapter.class.getMethod("sortingCollector", Consumer.class).invoke(null, results);
            collectorType.getMethod("addSubmittedJob", jobType).invoke(collector, fakeChunkJob(jobType, true));
            Thread worker = new Thread(() ->
            {
                try
                {
                    finish.invoke(collector, new Object[] {null});
                }
                catch (Throwable error)
                {
                    failure.compareAndSet(null, error);
                }
            }, "test-sort-delivery");
            Thread renderer = new Thread(() ->
            {
                try
                {
                    await.invoke(collector, new Object[] {null});
                    check(published.get(), "sorting completion must not precede result delivery");
                }
                catch (Throwable error)
                {
                    failure.compareAndSet(null, error);
                }
                finally
                {
                    completed.countDown();
                }
            }, "test-sort-await");
            worker.setDaemon(true);
            renderer.setDaemon(true);
            worker.start();
            check(delivering.await(2L, TimeUnit.SECONDS), "sorting worker did not start result delivery");
            renderer.start();
            check(!completed.await(50L, TimeUnit.MILLISECONDS), "a view must wait until the sorting result is available for upload");
            allowDelivery.countDown();
            check(completed.await(2L, TimeUnit.SECONDS), "sorting completion remained blocked after result delivery");
            worker.join(2_000L);
            renderer.join(2_000L);
            check(!worker.isAlive() && !renderer.isAlive() && failure.get() == null,
                "sorting completion must release both workers without failure: " + failure.get());
        }
        catch (ReflectiveOperationException | InterruptedException error)
        {
            if (error instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }

            throw new AssertionError("Sodium's sorting completion contract changed", error);
        }
        finally
        {
            allowDelivery.countDown();
        }
    }

    private static Object fakeChunkJob(Class<?> jobType, boolean started)
    {
        return Proxy.newProxyInstance(jobType.getClassLoader(), new Class<?>[] {jobType}, (proxy, method, args) ->
            switch (method.getName())
            {
                case "getEffort" -> 10;
                case "isStarted" -> started;
                case "isCancelled" -> false;
                case "setCancelled" -> null;
                default -> throw new AssertionError("collector checks must not execute terrain jobs");
            });
    }

    private static void viewsCompeteForOneFrameBudget()
    {
        ViewRenderState first = measuredView("first", 320, 180, 1_300_000L, MILLIS);
        ViewRenderState second = measuredView("second", 320, 180, 1_300_000L, MILLIS);
        ViewRenderState primary = new ViewRenderState("primary", 1920, 1080, true, new TimingFixture().timing);
        primary.setStatus(ViewRenderState.Status.LIVE);
        ViewBudgetScheduler scheduler = new ViewBudgetScheduler();
        long now = 4_000 * MILLIS;
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.shouldRender(first, now), "the first affordable view should receive budget");
        scheduler.recordAt(first, 1_300_000L, now);
        check(scheduler.getRemainingNanos() == 700_000L, "the first view must consume the shared frame allowance");
        check(!scheduler.shouldRender(second, now), "the second view must not receive a separate full budget");
        check(!scheduler.shouldRender(primary, now) && primary.getStatus() == ViewRenderState.Status.LIVE,
            "the secondary scheduler must never throttle or hide the primary");
        first.publishAt(1L, null, null, null, MILLIS, now);
        List<ViewRenderState> ordered = new ArrayList<>(List.of(first, second));
        ordered.sort(scheduler.priority(now));
        check(ordered.getFirst() == second, "an older view must get priority over the one just refreshed");
        first.requestRefresh(now);
        ordered.sort(scheduler.priority(now));
        check(ordered.getFirst() == first, "active interaction must receive temporary refresh priority");
        first.dispose();
        second.dispose();
        primary.dispose();
    }

    private static void coldViewsAndLostBaselinesRemainUnmeasured()
    {
        ViewRenderState first = new ViewRenderState("cold-a", 320, 180, false, new TimingFixture().timing);
        ViewRenderState second = new ViewRenderState("cold-b", 320, 180, false, new TimingFixture().timing);
        ViewBudgetScheduler scheduler = new ViewBudgetScheduler();
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.shouldRender(first, 4_000 * MILLIS), "one cold spec should receive a calibration pass");
        check(!scheduler.shouldRender(second, 4_000 * MILLIS), "a cold second view must wait for a later frame");
        check(first.getStatus() == ViewRenderState.Status.WARMING_UP, "a calibration pass is not a measured live result");
        ViewRenderState measured = measuredView("measured", 320, 180, MILLIS, MILLIS);
        scheduler.beginFrame(0L, false);
        check(scheduler.shouldRender(measured, 4_000 * MILLIS), "losing a baseline should still allow bounded calibration");
        check(measured.getStatus() == ViewRenderState.Status.WARMING_UP, "resource reset must invalidate the previous baseline classification");
        first.dispose();
        second.dispose();
        measured.dispose();
    }

    private static void editableBudgetsRemainSharedAndBounded()
    {
        ViewBudgetScheduler scheduler = new ViewBudgetScheduler();
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.getFrameBudgetNanos() == 2 * MILLIS, "the default allowance must retain the 20 percent setting");
        scheduler.setBudgetFraction(0.5D);
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.getFrameBudgetNanos() == 5 * MILLIS, "an edited percentage must change the total auxiliary allowance");
        ViewRenderState first = measuredView("budget-first", 320, 180, 3 * MILLIS, 3 * MILLIS);
        ViewRenderState second = measuredView("budget-second", 320, 180, 3 * MILLIS, 3 * MILLIS);
        long now = 4_000 * MILLIS;
        check(scheduler.shouldRender(first, now), "the enlarged allowance must admit a previously oversized pass");
        scheduler.recordAt(first, 3 * MILLIS, now);
        check(!scheduler.shouldRender(second, now) && scheduler.getRemainingNanos() == 2 * MILLIS,
            "editing the percentage must not turn one shared budget into a separate allowance for every view");
        scheduler.setBudgetFraction(4D);
        scheduler.beginFrame(0L, false);
        check(scheduler.getFrameBudgetNanos() == 20 * MILLIS,
            "the maximum preference must apply to the retained baseline while new GPU results are pending");
        scheduler.setBudgetFraction(-1D);
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.getFrameBudgetNanos() == MILLIS / 2,
            "the configured allowance must respect its minimum percentage");
        scheduler.setBudgetFraction(Double.NaN);
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.getFrameBudgetNanos() == 2 * MILLIS, "invalid persisted percentages must return to the default");
        first.dispose();
        second.dispose();
    }

    private static void changedPerformancePreferencesReconsiderOnlyAuxiliaryQuality()
    {
        ViewRenderState view = measuredView("preferences", 1280, 720, 5 * MILLIS, 6 * MILLIS);
        ViewGpuTiming.Spec fullSize = view.timingSpec(view.desiredSize());
        view.syncPerformancePreferences(0.2D, 0);
        view.publishAt(7L, null, null, null, 5 * MILLIS, 10 * MILLIS);
        view.changeQuality(0.25F, 20 * MILLIS);
        view.changeRefreshRate(15, 20 * MILLIS);
        long degradedEpoch = view.getHistoryEpoch();
        view.syncPerformancePreferences(0.2D, 0);
        check(view.getResolutionScale() == 0.25F && view.getRefreshRate() == 15 && view.getHistoryEpoch() == degradedEpoch,
            "ordinary frame synchronization must not erase an active budget downgrade");
        view.syncPerformancePreferences(0.5D, 0);
        check(view.getResolutionScale() == 1F && view.getRefreshRate() == 30
            && view.targetSize(21 * MILLIS).equals(new ViewTargetSize(1280, 720)),
            "changing the budget must reconsider the preferred size and requested refresh rate");
        check(view.hasFrame() && view.getSampleFrameId() == 7L && view.getTiming().getEstimatedNanos(fullSize) == 6 * MILLIS,
            "preference edits must preserve the last published image and reusable measured cost");
        view.changeQuality(0.25F, 22 * MILLIS);
        view.setRequestedSize(640, 360);
        view.syncPerformancePreferences(0.5D, 640);
        check(view.targetSize(23 * MILLIS).equals(new ViewTargetSize(640, 360)) && view.getResolutionScale() == 1F,
            "choosing a new preferred width must not inherit the old size's reduction factor");
        long preferredEpoch = view.getHistoryEpoch();
        view.changeRefreshRate(15, 24 * MILLIS);
        view.syncPerformancePreferences(1D, 640);
        check(view.getRefreshRate() == 30 && view.getHistoryEpoch() == preferredEpoch,
            "restoring cadence without changing dimensions must preserve camera history");

        ViewRenderState primary = new ViewRenderState("primary", 1920, 1080, true, new TimingFixture().timing);
        primary.publishAt(8L, null, null, null, MILLIS, 10 * MILLIS);
        long primaryEpoch = primary.getHistoryEpoch();
        primary.syncPerformancePreferences(0.05D, 320);
        check(primary.getRequestedWidth() == 1920 && primary.getRequestedHeight() == 1080
            && primary.getHistoryEpoch() == primaryEpoch && !primary.isRefreshRequested(),
            "auxiliary preferences must not change primary or export state");
        view.dispose();
        primary.dispose();
    }

    private static void minimumQualityKeepsItsCadenceAndReportsBudgetFailure()
    {
        ViewRenderState view = measuredView("expensive", 320, 180, 5 * MILLIS, 6 * MILLIS);
        ViewBudgetScheduler scheduler = new ViewBudgetScheduler();
        long start = 4_000 * MILLIS;
        view.publishAt(1L, null, null, null, 5 * MILLIS, start);
        view.changeRefreshRate(30, start);
        long historyEpoch = view.getHistoryEpoch();
        ViewGpuTiming.Spec spec = view.timingSpec(view.desiredSize());
        int rendered = 0;
        long lastSubmission = start;

        for (int frame = 1; frame <= 120; frame++)
        {
            long now = start + frame * 1_000_000_000L / 120;
            view.requestRefresh(now);
            scheduler.beginFrame(10 * MILLIS, true);

            if (scheduler.shouldRender(view, now))
            {
                check(now - lastSubmission >= 1_000_000_000L / 15,
                    "interaction and quality cooldown must not bypass the 15 Hz fallback cadence");
                check(view.getStatus() == ViewRenderState.Status.BUDGET_UNSATISFIED,
                    "an oversized pass must remain an explicit budget failure");
                lastSubmission = now;
                rendered++;
                view.publishAt(frame + 1L, null, null, null, 5 * MILLIS, now + 5 * MILLIS);
                scheduler.recordAt(view, 5 * MILLIS, now);
            }
        }

        check(rendered == 15 && view.getRefreshRate() == 15,
            "a minimum-quality preview must deliver 15 updates per second even with a 5 ms pass, not rare probes or 30 Hz fallback");
        check(view.getStatus() == ViewRenderState.Status.BUDGET_UNSATISFIED,
            "maintaining usable cadence must not claim that the frame budget was met");
        check(view.getHistoryEpoch() == historyEpoch && view.getTiming().getEstimatedNanos(spec) == 6 * MILLIS,
            "refresh-only degradation must preserve camera history and measured GPU cost");
        long resumed = start + 1_500 * MILLIS;
        scheduler.beginFrame(10 * MILLIS, true);
        check(scheduler.shouldRender(view, resumed), "a late outer frame must still refresh the overdue preview once");
        view.publishAt(200L, null, null, null, 5 * MILLIS, resumed + 5 * MILLIS);
        scheduler.beginFrame(10 * MILLIS, true);
        check(!scheduler.shouldRender(view, resumed + MILLIS), "missed intervals must not trigger a burst of catch-up passes");
        view.dispose();
    }

    private static void historyAndFailurePreserveThePublishedImage()
    {
        ViewRenderState state = new ViewRenderState("published", 640, 360, false, new TimingFixture().timing);
        Camera camera = new Camera();
        camera.position.set(30_000_000.25D, 64.125D, -30_000_000.75D);
        camera.rotation.set(0.1F, 0.2F, 0.3F);
        Matrix4f view = new Matrix4f().rotateY(0.5F);
        Matrix4f projection = new Matrix4f().perspective(1F, 16F / 9F, 0.05F, 512F);
        Matrix4f expectedView = new Matrix4f(view);
        Matrix4f expectedProjection = new Matrix4f(projection);
        state.publishAt(17L, view, projection, camera, MILLIS, 100 * MILLIS);
        view.zero();
        projection.zero();
        camera.position.zero();
        state.getLastView().zero();
        long epoch = state.getHistoryEpoch();
        state.invalidateHistory();
        IllegalStateException failure = new IllegalStateException("draw failed");
        state.failAt(failure, 2 * MILLIS, 150 * MILLIS);
        check(state.hasFrame() && state.getSampleFrameId() == 17L, "history reset and draw failure must preserve the displayed sample");
        check(state.getLastRenderedNanos() == 100 * MILLIS && state.getImageAgeNanos(160 * MILLIS) == 60 * MILLIS,
            "failed rendering must not advance the displayed image timestamp");
        check(state.getLastView().equals(expectedView) && state.getLastProjection().equals(expectedProjection),
            "published picking matrices must be copied and retained through failures");
        check(state.getCamera().position.x == 30_000_000.25D && state.getCamera().rotation.z == 0.3F,
            "camera publication must preserve double positions and roll independently of the evaluator");
        check(state.getHistoryEpoch() > epoch && state.getFailure() == failure && state.getStatus() == ViewRenderState.Status.FAILED,
            "failed history must remain observable");
        state.dispose();
    }

    private static void cameraCutsAndExportFenceOnlyTheirOwnHistory()
    {
        Film film = new Film();
        String first = film.addCamera("First", new Position()).cameraId.get();
        String second = film.addCamera("Second", new Position()).cameraId.get();
        film.setCameraCut(0, first);
        film.setCameraCut(10, second);
        ViewRenderState output = new ViewRenderState("output", 1920, 1080, true, new TimingFixture().timing);
        ViewRenderState fixed = new ViewRenderState("fixed", 640, 360, false, new TimingFixture().timing);

        output.syncCameraSource(film, 9, second, true, false);
        fixed.syncCameraSource(film, 9, first, false, false);
        output.publishAt(1L, null, null, null, MILLIS, MILLIS);
        fixed.publishAt(1L, null, null, null, MILLIS, MILLIS);
        long outputEpoch = output.getHistoryEpoch();
        long fixedEpoch = fixed.getHistoryEpoch();
        output.syncCameraSource(film, 10, first, true, false);
        fixed.syncCameraSource(film, 10, first, false, false);
        check(output.getHistoryEpoch() == outputEpoch + 1 && output.isRefreshRequested(),
            "crossing an output cut must reset history even when the editor is bound to another camera");
        check(fixed.getHistoryEpoch() == fixedEpoch && !fixed.isRefreshRequested(),
            "an output cut must not reset a preview fixed to an unchanged camera");
        check(output.hasFrame() && output.getSampleFrameId() == 1L,
            "cut invalidation must preserve the previous displayed frame until replacement succeeds");

        outputEpoch = output.getHistoryEpoch();
        output.syncCameraSource(film, 11, second, true, false);
        check(output.getHistoryEpoch() == outputEpoch,
            "changing the editor binding during export must not change output history");
        output.syncCameraSource(film, 9, second, true, false);
        check(output.getHistoryEpoch() == outputEpoch + 1, "backward cuts must also reset output history");

        fixed.syncCameraSource(film, 10, null, false, false);
        long freeEpoch = fixed.getHistoryEpoch();
        fixed.syncCameraSource(film, 11, null, false, false);
        check(fixed.getHistoryEpoch() == freeEpoch, "ordinary free-view sampling must preserve history");
        fixed.syncCameraSource(film, 11, null, false, true);
        check(fixed.getHistoryEpoch() == freeEpoch + 1, "switching projection modes must reset history");
        fixed.syncCameraSource(new Film(), 11, null, false, true);
        check(fixed.getHistoryEpoch() == freeEpoch + 2, "a different Film must not inherit the previous Film's history");
        output.dispose();
        fixed.dispose();
    }

    private static void disposalAllowsAViewToWarmUpAgain()
    {
        ViewRenderState view = measuredView("reopen", 1280, 720, MILLIS, MILLIS);
        view.changeQuality(0.25F, 20 * MILLIS);
        view.publishAt(8L, null, null, null, MILLIS, 30 * MILLIS);
        view.failAt(new IllegalStateException("retired"), MILLIS, 40 * MILLIS);
        view.dispose();
        check(!view.hasFrame() && view.getFailure() == null && view.getLastAttemptNanos() == 0L,
            "reopened views must not inherit a retired frame or failure retry delay");
        check(view.getStatus() == ViewRenderState.Status.WARMING_UP && view.getResolutionScale() == 1F,
            "reopened views must warm up at their requested quality");
        check(view.targetSize(50 * MILLIS).equals(new ViewTargetSize(1280, 720)), "retired resize debounce state must be cleared");
    }

    private static void targetTiersAndResizeDebouncePreserveQuality()
    {
        ViewTargetSize landscape = ViewTargetSize.select(20_000, 11_250, 1F);
        ViewTargetSize portrait = ViewTargetSize.select(2160, 3840, 1F);
        check(landscape.width() <= 2048 && landscape.height() <= 2048 && portrait.width() <= 2048 && portrait.height() <= 2048,
            "preview allocations must respect the maximum side for either aspect");
        check(Math.abs(landscape.width() - landscape.height() * 16D / 9D) <= 1D,
            "resolution tiers must preserve the camera output aspect");
        check(ViewTargetSize.select(1920, 1080, 0.1F).equals(new ViewTargetSize(320, 180)),
            "adaptive quality must stop at the agreed minimum");
        check(ViewTargetSize.select(120, 60, 1F).equals(new ViewTargetSize(360, 180))
            && ViewTargetSize.select(160, 90, 1F).equals(new ViewTargetSize(320, 180)),
            "tiny panes must downscale a minimum-size offscreen image instead of allocating an unusable monitor");

        ViewRenderState state = new ViewRenderState("resize", 1920, 1080, false, new TimingFixture().timing);
        ViewTargetSize first = state.targetSize(10 * MILLIS);
        state.setRequestedSize(1280, 720);
        check(state.targetSize(20 * MILLIS).equals(first) && state.targetSize(519 * MILLIS).equals(first),
            "a dock resize must not reallocate targets during the debounce interval");
        check(state.targetSize(520 * MILLIS).equals(new ViewTargetSize(1280, 720)), "stable layout dimensions must eventually be accepted");
        state.changeQuality(0.25F, 521 * MILLIS);
        check(state.targetSize(521 * MILLIS).equals(new ViewTargetSize(320, 180)), "an explicit budget downgrade should apply immediately");
        state.dispose();
    }

    private static void cameraContextsCopyPoseAndOwnProjection()
    {
        ViewRenderState view = new ViewRenderState("camera", 640, 360, false, new TimingFixture().timing);
        Camera source = new Camera();
        source.position.set(30_000_000.125D, 65D, 0D);
        ViewPassContext context = ViewPassContext.open(view, source, 640, 360, 8F);

        try (context)
        {
            source.position.zero();
            check(context.camera().position.x == 30_000_000.125D && context.orthographic(), "view context must own a precise pose copy");
            Matrix4f projection = context.projection(512F);
            check(Math.abs(projection.m11() / projection.m00() - 16F / 9F) < 0.00001F,
                "orthographic projection must use this target's aspect");
            expect(IllegalStateException.class, () -> ViewPassContext.open(view, source, 1, 1, -1F));
            check(ViewPassContext.current() == context, "rejected nesting must preserve the active camera");
        }

        context.close();
        check(ViewPassContext.current() == null, "camera context leaked after close");
        view.dispose();
    }

    private static ViewRenderState measuredView(String id, int width, int height, long cpuNanos, long gpuNanos)
    {
        TimingFixture fixture = new TimingFixture();
        ViewRenderState view = new ViewRenderState(id, width, height, false, fixture.timing);
        fixture.sample(view.timingSpec(view.desiredSize()), cpuNanos, gpuNanos);
        return view;
    }

    private static <T extends Throwable> T expect(Class<T> type, Runnable action)
    {
        try
        {
            action.run();
        }
        catch (Throwable failure)
        {
            if (type.isInstance(failure))
            {
                return type.cast(failure);
            }

            throw new AssertionError("Unexpected failure type", failure);
        }

        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class ManualClock implements LongSupplier
    {
        private long nanos;

        @Override
        public long getAsLong()
        {
            return this.nanos;
        }
    }

    private static final class TimingFixture
    {
        private final ManualClock cpu = new ManualClock();
        private final ManualClock gpu = new ManualClock();
        private final FakeQueries queries = new FakeQueries(this.gpu);
        private final ViewGpuTiming timing = new ViewGpuTiming(this.cpu, this.queries);

        private void sample(ViewGpuTiming.Spec spec, long cpuNanos, long gpuNanos)
        {
            this.timing.begin(spec);
            this.cpu.nanos += cpuNanos;
            this.gpu.nanos += gpuNanos;
            check(this.timing.end() == cpuNanos, "submission timer must use elapsed CPU time");
            this.timing.poll();
        }
    }

    private static final class FakeQueries implements ViewGpuTiming.Queries
    {
        private final ManualClock clock;
        private final Map<Integer, Long> timestamps = new HashMap<>();
        private final List<Integer> deleted = new ArrayList<>();
        private boolean resultsReady = true;
        private int blockedThrough;
        private int created;
        private int timestamped;
        private int read;
        private int failDeletion = -1;

        private FakeQueries(ManualClock clock)
        {
            this.clock = clock;
        }

        @Override
        public boolean supported()
        {
            return true;
        }

        @Override
        public int create()
        {
            return ++this.created;
        }

        @Override
        public void timestamp(int query)
        {
            this.timestamped++;
            this.timestamps.put(query, this.clock.nanos);
        }

        @Override
        public boolean ready(int query)
        {
            return this.resultsReady && query > this.blockedThrough && this.timestamps.containsKey(query);
        }

        @Override
        public long result(int query)
        {
            check(this.ready(query), "blocking GPU-result access was attempted");
            this.read++;
            return this.timestamps.get(query);
        }

        @Override
        public void delete(int query)
        {
            this.deleted.add(query);
            this.timestamps.remove(query);

            if (query == this.failDeletion)
            {
                throw new IllegalStateException("query deletion failed");
            }
        }
    }
}
