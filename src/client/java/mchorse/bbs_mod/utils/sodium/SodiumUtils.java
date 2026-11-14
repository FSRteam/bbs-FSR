package mchorse.bbs_mod.utils.sodium;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexSodiumConsumer;
import mchorse.bbs_mod.utils.colors.Color;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public class SodiumUtils
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static Method optionsAccessor;
    private static CullingState pointCameraCulling;
    private static boolean cullingUnavailable;

    public static VertexConsumer createVertexBuffer(VertexConsumer b, Color color)
    {
        return new RecolorVertexSodiumConsumer(b, color);
    }

    public static void disablePointCameraCulling()
    {
        pointCameraCulling = captureCameraCulling();

        if (pointCameraCulling != null)
        {
            pointCameraCulling.apply(true);
        }
    }

    public static void restorePointCameraCulling()
    {
        if (pointCameraCulling != null)
        {
            pointCameraCulling.apply(false);
            pointCameraCulling = null;
        }
    }

    public static CullingState captureCameraCulling()
    {
        if (cullingUnavailable)
        {
            return null;
        }

        try
        {
            if (optionsAccessor == null)
            {
                optionsAccessor = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod").getMethod("options");
            }

            /* Sodium 0.8 changed the return type from SodiumGameOptions to SodiumOptions. */
            Object options = optionsAccessor.invoke(null);

            if (options == null)
            {
                return null;
            }

            Field field = options.getClass().getField("performance");
            Object performance = field.get(options);

            return performance == null ? null : captureCameraCulling(performance);
        }
        catch (InvocationTargetException failure)
        {
            disableCameraCulling(failure.getCause() == null ? failure : failure.getCause());
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure)
        {
            disableCameraCulling(failure);
        }

        return null;
    }

    /** Explicit settings capture retains diagnostics for callers that supply their own object. */
    public static CullingState captureCameraCulling(Object performance)
    {
        try
        {
            Field faces = performance.getClass().getField("useBlockFaceCulling");
            Field fog = performance.getClass().getField("useFogOcclusion");

            return new CullingState(performance, faces, fog, faces.getBoolean(performance), fog.getBoolean(performance));
        }
        catch (ReflectiveOperationException failure)
        {
            throw new IllegalStateException("Unsupported Sodium camera culling settings", failure);
        }
    }

    private static void disableCameraCulling(Throwable failure)
    {
        if (!cullingUnavailable)
        {
            cullingUnavailable = true;
            LOGGER.warn("[BBS] Sodium camera culling bridge is unavailable; disabling further overrides", failure);
        }
    }

    public static final class CullingState
    {
        private final Object performance;
        private final Field facesField;
        private final Field fogField;
        private final boolean faces;
        private final boolean fog;

        private CullingState(Object performance, Field facesField, Field fogField, boolean faces, boolean fog)
        {
            this.performance = performance;
            this.facesField = facesField;
            this.fogField = fogField;
            this.faces = faces;
            this.fog = fog;
        }

        public void apply(boolean orthographic)
        {
            boolean previousFaces = false;
            boolean previousFog = false;
            boolean captured = false;

            try
            {
                previousFaces = this.facesField.getBoolean(this.performance);
                previousFog = this.fogField.getBoolean(this.performance);
                captured = true;

                this.facesField.setBoolean(this.performance, this.faces && !orthographic);
                this.fogField.setBoolean(this.performance, this.fog && !orthographic);
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure)
            {
                /* Restore the incoming values even if only one write succeeded.
                 * Existing scope snapshots can still restore after the bridge is disabled. */
                if (captured)
                {
                    this.restoreValue(this.facesField, previousFaces, failure);
                    this.restoreValue(this.fogField, previousFog, failure);
                }

                disableCameraCulling(failure);
            }
        }

        private void restoreValue(Field field, boolean value, Throwable failure)
        {
            try
            {
                field.setBoolean(this.performance, value);
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError rollbackFailure)
            {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }
}
