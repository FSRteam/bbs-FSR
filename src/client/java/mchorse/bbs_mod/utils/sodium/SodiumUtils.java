package mchorse.bbs_mod.utils.sodium;

import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexSodiumConsumer;
import mchorse.bbs_mod.utils.colors.Color;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

public class SodiumUtils
{
    private static Method optionsAccessor;
    private static CullingState pointCameraCulling;

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
        try
        {
            if (optionsAccessor == null)
            {
                optionsAccessor = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod").getMethod("options");
            }

            /* Sodium 0.8 changed the return type from SodiumGameOptions to SodiumOptions. */
            Object options = optionsAccessor.invoke(null);
            Field field = options.getClass().getField("performance");

            return captureCameraCulling(field.get(options));
        }
        catch (InvocationTargetException failure)
        {
            if (failure.getCause() instanceof Error error)
            {
                throw error;
            }

            throw new IllegalStateException("Could not read Sodium camera culling settings", failure.getCause());
        }
        catch (ReflectiveOperationException | LinkageError ignored)
        {
            return null;
        }
    }

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
            try
            {
                this.facesField.setBoolean(this.performance, this.faces && !orthographic);
                this.fogField.setBoolean(this.performance, this.fog && !orthographic);
            }
            catch (IllegalAccessException failure)
            {
                throw new IllegalStateException("Could not restore Sodium camera culling settings", failure);
            }
        }
    }
}
