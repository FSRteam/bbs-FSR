package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceDemand;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceListener;
import mchorse.bbs_mod.api.client.ui.BBSUiFrame;
import mchorse.bbs_mod.api.client.ui.BBSUiColoredMesh;
import mchorse.bbs_mod.api.client.ui.BBSUiMirrorListener;
import mchorse.bbs_mod.api.client.ui.BBSUiSessionInfo;
import mchorse.bbs_mod.api.client.ui.BBSUiSurfaceQuad;
import mchorse.bbs_mod.api.client.ui.BBSUiUnsupported;
import mchorse.bbs_mod.api.client.ui.BBSUiUnsupportedReason;
import mchorse.bbs_mod.api.client.ui.BBSUiVertex;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRegistry;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRuntime;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceLifecycleTest;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic lifecycle and painter-order checks for Replay surface placement. */
public final class BBSUiSurfacePlacementTest
{
    private BBSUiSurfacePlacementTest()
    {}

    public static void main(String[] args)
    {
        assertRecorderTeardownFailureIsolation();
        assertRecorderOpenFailureCleanup();
        assertRecorderOpenFailurePreservesReplacement();
        BBSRenderSurfaceLifecycleTest.run();

        List<BBSUiSessionInfo> opened = new ArrayList<>();
        List<BBSUiFrame> frames = new ArrayList<>();
        List<Long> closed = new ArrayList<>();
        BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder("surface-placement-test")
            .side(BBSAddonSide.CLIENT)
            .capability(BBSAddonCapability.CLIENT_UI)
            .capability(BBSAddonCapability.CLIENT_RENDER)
            .build();
        AtomicReference<BBSRenderSurfaceDemand> surfaceDemand = new AtomicReference<>(BBSRenderSurfaceDemand.none());

        BBSUiFrameRecorder.closeAllSessions();
        check(!BBSRenderSurfaceRuntime.hasDemand(EnumSet.of(BBSRenderSurfaceKind.WORLD_REPLAY)),
            "surface capture must stay disabled without subscribers");
        check(BBSRenderSurfaceRegistry.register(descriptor, new BBSRenderSurfaceListener()
        {
            @Override
            public BBSRenderSurfaceDemand demand()
            {
                return surfaceDemand.get();
            }

            @Override
            public void onFrame(mchorse.bbs_mod.api.client.render.BBSRenderSurfaceFrame frame)
            {}
        }).accepted(), "surface demand listener registration failed");
        check(!BBSRenderSurfaceRuntime.hasDemand(EnumSet.of(BBSRenderSurfaceKind.WORLD_REPLAY)),
            "an inactive subscriber must not enable capture");
        surfaceDemand.set(BBSRenderSurfaceDemand.mobile(EnumSet.of(BBSRenderSurfaceKind.WORLD_REPLAY)));
        await(() -> BBSRenderSurfaceRuntime.hasDemand(EnumSet.of(BBSRenderSurfaceKind.WORLD_REPLAY)),
            "active WORLD_REPLAY demand was not detected");
        check(!BBSRenderSurfaceRuntime.hasDemand(EnumSet.of(BBSRenderSurfaceKind.FILM_PREVIEW)),
            "unrequested surface kinds must not enable capture");

        check(BBSUiMirrorRegistry.register(descriptor, new BBSUiMirrorListener()
        {
            @Override
            public void onSessionOpened(BBSUiSessionInfo session)
            {
                opened.add(session);
            }

            @Override
            public void onFrame(BBSUiFrame frame)
            {
                frames.add(frame);
            }

            @Override
            public void onSessionClosed(long sessionId)
            {
                closed.add(sessionId);
            }
        }).accepted(), "surface placement listener registration failed");

        try
        {
            BBSUiFrameRecorder.publishStandaloneWorldReplayFrame(320, 180, 640, 360);
            awaitMirrorCallbacks("standalone Replay first frame callbacks did not drain");
            check(opened.size() == 1, "standalone Replay must open exactly one mirror session");
            check(frames.size() == 1, "standalone Replay must publish its first placement frame");

            long standaloneId = opened.get(0).sessionId();
            assertWorldReplayFrame(frames.get(0), standaloneId, 1L, 320, 180);

            BBSUiFrameRecorder.publishStandaloneWorldReplayFrame(480, 270, 960, 540);
            awaitMirrorCallbacks("standalone Replay resize callbacks did not drain");
            check(opened.size() == 1, "standalone Replay resize must retain the existing session");
            check(frames.size() == 2, "standalone Replay resize must publish another frame");
            assertWorldReplayFrame(frames.get(1), standaloneId, 2L, 480, 270);

            BBSUiFrameRecorder.closeStandaloneWorldReplaySession();
            awaitMirrorCallbacks("standalone Replay close callback did not drain");
            check(closed.equals(List.of(standaloneId)), "standalone Replay close must target only its own session");
            check(!BBSUiFrameRecorder.isSessionOpen(standaloneId), "standalone Replay session leaked after close");

            long realUiSession = BBSUiFrameRecorder.openSession(640, 360, 1280, 720);
            awaitMirrorCallbacks("real UI open callback did not drain");
            BBSUiFrameRecorder.closeStandaloneWorldReplaySession();
            check(BBSUiFrameRecorder.isSessionOpen(realUiSession), "standalone close must not close a real UIScreen session");
            check(BBSUiFrameRecorder.beginFrame(realUiSession, 640, 360), "real UI frame did not begin");
            BBSUiFrameRecorder.recordFullscreenSurface(BBSRenderSurfaceKind.WORLD_REPLAY, 640, 360);
            BBSUiFrameRecorder.recordQuad(new Matrix4f(), 4F, 4F, 10F, 10F, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff);
            BBSUiFrameRecorder.endFrame(0, 0F, 0F);
            awaitMirrorCallbacks("real UI surface frame callback did not drain");

            BBSUiFrame realFrame = frames.get(frames.size() - 1);
            check(realFrame.commands().size() == 2, "real UI placement frame must retain later UI commands");
            check(realFrame.commands().get(0) instanceof BBSUiSurfaceQuad, "WORLD_REPLAY must be first in painter order");

            check(BBSUiFrameRecorder.beginFrame(realUiSession, 640, 360), "atlas placement frame did not begin");
            BBSUiFrameRecorder.recordFullscreenSurface(BBSRenderSurfaceKind.MORPH_WORLD_PREVIEW, 640, 360);
            BBSUiFrameRecorder.recordFormPreviewAtlas(10, 20, 60, 80, 640, 360);
            BBSUiFrameRecorder.recordQuad(new Matrix4f(), 2F, 3F, 4F, 5F, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff);
            BBSUiFrameRecorder.endFrame(0, 0F, 0F);
            awaitMirrorCallbacks("atlas placement frame callback did not drain");

            BBSUiFrame atlasFrame = frames.get(frames.size() - 1);
            check(atlasFrame.commands().size() == 3, "morph/atlas placement must retain later UI commands");
            check(((BBSUiSurfaceQuad) atlasFrame.commands().get(0)).surfaceKind() == BBSRenderSurfaceKind.MORPH_WORLD_PREVIEW,
                "morph fullscreen surface must be painter-first");
            BBSUiSurfaceQuad atlas = (BBSUiSurfaceQuad) atlasFrame.commands().get(1);
            check(atlas.surfaceKind() == BBSRenderSurfaceKind.FORM_PREVIEW_ATLAS, "atlas surface kind mismatch");
            check(atlas.topLeft().x() == 10F && atlas.topLeft().y() == 20F
                    && atlas.bottomRight().x() == 70F && atlas.bottomRight().y() == 100F,
                "atlas rectangle mismatch");
            check(atlas.topLeft().v() == 0F && atlas.bottomLeft().v() == 1F,
                "atlas UV orientation must remain top-down");

            check(BBSUiFrameRecorder.beginFrame(realUiSession, 640, 360), "mesh diagnostic frame did not begin");
            BBSUiFrameRecorder.recordColoredMesh(new Matrix4f(), List.of(
                new BBSUiVertex(1F, 2F, 0xffff0000),
                new BBSUiVertex(3F, 4F, 0xff00ff00),
                new BBSUiVertex(5F, 6F, 0xff0000ff)
            ));
            BBSUiFrameRecorder.recordUnsupported(BBSUiUnsupportedReason.RAW_TEXTURE);
            BBSUiFrameRecorder.recordUnsupported(BBSUiUnsupportedReason.RAW_TEXTURE);
            BBSUiFrameRecorder.recordUnsupported(BBSUiUnsupportedReason.CUSTOM_SHADER);
            BBSUiFrameRecorder.endFrame(0, 0F, 0F);
            awaitMirrorCallbacks("mesh diagnostic frame callback did not drain");

            BBSUiFrame diagnosticFrame = frames.get(frames.size() - 1);
            check(diagnosticFrame.commands().get(0) instanceof BBSUiColoredMesh, "colored mesh lost painter order");
            List<BBSUiUnsupported> diagnostics = diagnosticFrame.commands().stream()
                .filter(BBSUiUnsupported.class::isInstance)
                .map(BBSUiUnsupported.class::cast)
                .toList();
            check(diagnostics.size() == 2, "unsupported reasons were not bounded and aggregated");
            check(diagnostics.stream().anyMatch(value -> value.reason() == BBSUiUnsupportedReason.RAW_TEXTURE && value.count() == 2),
                "raw texture diagnostic count mismatch");
            check(diagnostics.stream().anyMatch(value -> value.reason() == BBSUiUnsupportedReason.CUSTOM_SHADER && value.count() == 1),
                "custom shader diagnostic count mismatch");

            BBSUiFrameRecorder.closeSession(realUiSession);
            awaitMirrorCallbacks("real UI close callback did not drain");
        }
        finally
        {
            BBSUiFrameRecorder.closeAllSessions();
            awaitMirrorCallbacks("surface placement cleanup callbacks did not drain");
        }

        System.out.println("BBSUiSurfacePlacementTest: all tests passed");
    }

    private static void assertRecorderTeardownFailureIsolation()
    {
        RuntimeException repeated = new IllegalStateException("repeated teardown failure");
        AtomicInteger repeatedSteps = new AtomicInteger();
        Throwable repeatedResult = captureFailure(() -> BBSUiFrameRecorder.runTeardownSteps(
            () ->
            {
                repeatedSteps.incrementAndGet();
                throw repeated;
            },
            () ->
            {
                repeatedSteps.incrementAndGet();
                throw repeated;
            },
            repeatedSteps::incrementAndGet
        ));

        check(repeatedResult == repeated, "recorder teardown did not preserve the first failure instance");
        check(repeatedResult.getSuppressed().length == 0, "recorder teardown self-suppressed the same failure");
        check(repeatedSteps.get() == 3, "repeated recorder teardown failure skipped a later cleanup step");

        RuntimeException runtimeFirst = new IllegalStateException("runtime teardown failure");
        Error errorSecond = new Error("error teardown failure");
        List<String> closeAllSteps = new ArrayList<>();
        Throwable runtimeFirstResult = captureFailure(() -> BBSUiFrameRecorder.runTeardownSteps(
            () ->
            {
                closeAllSteps.add("first session");
                throw runtimeFirst;
            },
            () ->
            {
                closeAllSteps.add("second session");
                throw errorSecond;
            },
            () -> closeAllSteps.add("standalone reset"),
            () -> closeAllSteps.add("asset reset")
        ));

        check(runtimeFirstResult == runtimeFirst, "recorder teardown replaced its RuntimeException primary failure");
        check(runtimeFirstResult.getSuppressed().length == 1
                && runtimeFirstResult.getSuppressed()[0] == errorSecond,
            "recorder teardown did not aggregate a later Error");
        check(closeAllSteps.equals(List.of("first session", "second session", "standalone reset", "asset reset")),
            "close-all recorder teardown failure skipped a later session or final reset");

        Error errorFirst = new Error("primary teardown error");
        RuntimeException runtimeSecond = new IllegalStateException("secondary teardown failure");
        AtomicInteger errorFirstSteps = new AtomicInteger();
        Throwable errorFirstResult = captureFailure(() -> BBSUiFrameRecorder.runTeardownSteps(
            () ->
            {
                errorFirstSteps.incrementAndGet();
                throw errorFirst;
            },
            () ->
            {
                errorFirstSteps.incrementAndGet();
                throw runtimeSecond;
            },
            errorFirstSteps::incrementAndGet
        ));

        check(errorFirstResult == errorFirst, "recorder teardown did not rethrow its primary Error");
        check(errorFirstResult.getSuppressed().length == 1
                && errorFirstResult.getSuppressed()[0] == runtimeSecond,
            "recorder teardown did not aggregate a later RuntimeException");
        check(errorFirstSteps.get() == 3, "primary recorder teardown Error skipped a later cleanup step");
    }

    private static Throwable captureFailure(Runnable action)
    {
        try
        {
            action.run();
        }
        catch (Throwable failure)
        {
            return failure;
        }

        throw new AssertionError("expected recorder lifecycle action to fail");
    }

    private static void assertRecorderOpenFailureCleanup()
    {
        RuntimeException openFailure = new IllegalStateException("registry attach failure");
        Error registryCloseFailure = new Error("registry close failure");
        AtomicInteger invalidations = new AtomicInteger();
        AtomicInteger registryCloses = new AtomicInteger();
        AtomicLong failedSession = new AtomicLong();
        Throwable result = captureFailure(() -> BBSUiFrameRecorder.openSession(
            320,
            180,
            640,
            360,
            () ->
            {
                if (invalidations.incrementAndGet() > 1)
                {
                    throw openFailure;
                }
            },
            session ->
            {
                failedSession.set(session.sessionId());
                throw openFailure;
            },
            sessionId ->
            {
                registryCloses.incrementAndGet();
                throw registryCloseFailure;
            }
        ));

        check(result == openFailure, "recorder open rollback replaced the registry attach failure");
        check(result.getSuppressed().length == 1 && result.getSuppressed()[0] == registryCloseFailure,
            "recorder open rollback did not aggregate registry close Error exactly once");
        check(invalidations.get() == 2, "recorder open rollback did not attempt surface invalidation");
        check(registryCloses.get() == 1, "recorder open rollback did not attempt registry close");
        check(!BBSUiFrameRecorder.isSessionOpen(failedSession.get()),
            "recorder open rollback retained its failed session state");
    }

    private static void assertRecorderOpenFailurePreservesReplacement()
    {
        RuntimeException openFailure = new IllegalStateException("superseded registry attach failure");
        AtomicInteger invalidations = new AtomicInteger();
        AtomicLong failedSession = new AtomicLong();
        AtomicLong replacementSession = new AtomicLong();
        AtomicLong failedRegistryClose = new AtomicLong();
        AtomicLong replacementRegistryClose = new AtomicLong();
        AtomicReference<Throwable> result = new AtomicReference<>();
        CountDownLatch registryCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistryClose = new CountDownLatch(1);
        CountDownLatch failedOpenFinished = new CountDownLatch(1);
        Thread failedOpen = new Thread(() ->
        {
            try
            {
                result.set(captureFailure(() -> BBSUiFrameRecorder.openSession(
                    320,
                    180,
                    640,
                    360,
                    invalidations::incrementAndGet,
                    session ->
                    {
                        failedSession.set(session.sessionId());
                        throw openFailure;
                    },
                    sessionId ->
                    {
                        failedRegistryClose.set(sessionId);
                        registryCloseEntered.countDown();
                        await(releaseRegistryClose, "failed recorder registry close did not resume");
                    }
                )));
            }
            finally
            {
                failedOpenFinished.countDown();
            }
        }, "ui-recorder-failed-open-probe");

        failedOpen.setDaemon(true);
        failedOpen.start();
        await(registryCloseEntered, "failed recorder open did not enter registry cleanup");

        replacementSession.set(BBSUiFrameRecorder.openSession(
            640,
            360,
            1280,
            720,
            invalidations::incrementAndGet,
            replacement -> {},
            replacementRegistryClose::set
        ));
        releaseRegistryClose.countDown();
        await(failedOpenFinished, "failed recorder open rollback did not finish");

        check(result.get() == openFailure, "superseded recorder open did not preserve its attach failure");
        check(!BBSUiFrameRecorder.isSessionOpen(failedSession.get()),
            "superseded recorder open retained its failed session");
        check(BBSUiFrameRecorder.isSessionOpen(replacementSession.get()),
            "failed recorder open rollback removed its replacement session");
        check(invalidations.get() == 2,
            "failed recorder open rollback invalidated the replacement surface generation");
        check(failedRegistryClose.get() == failedSession.get() && replacementRegistryClose.get() == 0L,
            "failed recorder open rollback closed the replacement registry session");

        BBSUiFrameRecorder.closeSession(
            replacementSession.get(),
            invalidations::incrementAndGet,
            replacementRegistryClose::set
        );

        check(!BBSUiFrameRecorder.isSessionOpen(replacementSession.get()),
            "replacement recorder session leaked after the regression probe");
        check(invalidations.get() == 3 && replacementRegistryClose.get() == replacementSession.get(),
            "replacement recorder session did not retain its own teardown ownership");
    }

    private static void assertWorldReplayFrame(
        BBSUiFrame frame,
        long expectedSession,
        long expectedSequence,
        int expectedWidth,
        int expectedHeight
    )
    {
        check(frame.sessionId() == expectedSession, "placement frame session changed unexpectedly");
        check(frame.sequence() == expectedSequence, "placement frame sequence is not session-local and monotonic");
        check(frame.width() == expectedWidth && frame.height() == expectedHeight, "placement frame logical size mismatch");
        check(frame.commands().size() == 1, "standalone Replay frame must contain exactly one command");
        check(frame.commands().get(0) instanceof BBSUiSurfaceQuad, "standalone Replay frame lacks a surface quad");

        BBSUiSurfaceQuad surface = (BBSUiSurfaceQuad) frame.commands().get(0);
        check(surface.surfaceKind() == BBSRenderSurfaceKind.WORLD_REPLAY, "standalone surface kind mismatch");
        check(surface.topLeft().x() == 0F && surface.topLeft().y() == 0F, "surface top-left mismatch");
        check(surface.bottomRight().x() == expectedWidth && surface.bottomRight().y() == expectedHeight, "surface bounds mismatch");
        check(surface.topLeft().u() == 0F && surface.topLeft().v() == 0F, "surface top-left UV must be top-down");
        check(surface.bottomRight().u() == 1F && surface.bottomRight().v() == 1F, "surface bottom-right UV must be top-down");
        check(surface.tint() == 0xffffffff, "surface tint mismatch");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, String message)
    {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3L);

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

    private static void await(CountDownLatch latch, String message)
    {
        try
        {
            check(latch.await(2L, java.util.concurrent.TimeUnit.SECONDS), message);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new AssertionError(message, e);
        }
    }

    private static void awaitMirrorCallbacks(String message)
    {
        try
        {
            check(BBSUiMirrorRegistry.awaitCallbacksForTests(2_000L), message);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new AssertionError(message, e);
        }
    }
}
