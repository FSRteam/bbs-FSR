package mchorse.bbs_mod.forms.renderers.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import org.joml.Matrix4f;

/** Render-thread pose epoch, preserving FSR simulation and semantic-placement cache keys. */
public final class RenderFrame
{
    private static long epoch;
    private static final FormFrameCache CACHE = new FormFrameCache();

    public static long getEpoch()
    {
        return epoch;
    }

    public static boolean isEnabled()
    {
        return BBSSettings.framePoseCache == null || BBSSettings.framePoseCache.get();
    }

    public static void nextFrame()
    {
        invalidate();
    }

    public static void invalidate()
    {
        epoch += 1;
        CACHE.clear();
    }

    public static MatrixCache collect(Form form, IEntity entity, float transition)
    {
        return collect(form, entity, null, null, false, false, transition);
    }

    public static MatrixCache collect(Form form, IEntity entity, Object simulationOwner, Matrix4f semanticBase,
        boolean allowWorldTargetOverrides, boolean allowWorldCollisions, float transition)
    {
        if (!isEnabled())
        {
            return FormUtilsClient.getRenderer(form).collectMatrices(entity, simulationOwner, semanticBase,
                allowWorldTargetOverrides, allowWorldCollisions, transition);
        }

        return CACHE.collect(form, entity, simulationOwner, semanticBase,
            allowWorldTargetOverrides, allowWorldCollisions, transition);
    }
}
