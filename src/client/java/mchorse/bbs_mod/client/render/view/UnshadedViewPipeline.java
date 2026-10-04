package mchorse.bbs_mod.client.render.view;

import mchorse.bbs_mod.utils.iris.IrisStateSnapshot;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;

/** Vanilla drawing while preserving the shared, already compiled chunk format. */
public final class UnshadedViewPipeline extends VanillaRenderingPipeline
{
    private UnshadedViewPipeline()
    {}

    public static UnshadedViewPipeline create()
    {
        Runnable restore = ((IrisStateSnapshot) WorldRenderingSettings.INSTANCE).bbs$captureState();

        try
        {
            return new UnshadedViewPipeline();
        }
        finally
        {
            restore.run();
        }
    }
}
