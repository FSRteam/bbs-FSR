package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BBSUiMirrorRuntime
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-ui-lifecycle");

    private BBSUiMirrorRuntime()
    {}

    public static void reset()
    {
        runStep("reset render surfaces", () -> BBSRenderSurfaceRuntime.reset());
        runStep("reset UI open requests", () -> BBSUiOpenDispatcher.reset());
        runStep("release UI input", () -> BBSUiInputDispatcher.reset());
        runStep("close UI mirror sessions", () -> BBSUiFrameRecorder.closeAllSessions());
    }

    public static void shutdown()
    {
        runStep("stop UI open requests", () -> BBSUiOpenDispatcher.shutdown());
        runStep("release UI input", () -> BBSUiInputDispatcher.reset());
        runStep("close UI mirror sessions", () -> BBSUiFrameRecorder.closeAllSessions());
        runStep("stop render surfaces", () -> BBSRenderSurfaceRuntime.shutdown());
    }

    private static void runStep(String step, Runnable runnable)
    {
        try
        {
            runnable.run();
        }
        catch (RuntimeException | Error exception)
        {
            LOGGER.error("[bbs-client-ui] failed to {}; continuing lifecycle teardown", step, exception);
        }
    }
}
