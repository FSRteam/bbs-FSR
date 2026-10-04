package mchorse.bbs_mod.utils.sodium;

import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexSodiumConsumer;
import mchorse.bbs_mod.utils.colors.Color;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class SodiumUtils
{
    private static boolean savedBlockFaceCulling;
    private static boolean savedFogOcclusion;

    public static VertexConsumer createVertexBuffer(VertexConsumer b, Color color)
    {
        return new RecolorVertexSodiumConsumer(b, color);
    }

    public static void disablePointCameraCulling()
    {
        Object performance = performance();

        if (performance == null)
        {
            return;
        }

        savedBlockFaceCulling = getBoolean(performance, "useBlockFaceCulling");
        savedFogOcclusion = getBoolean(performance, "useFogOcclusion");
        setBoolean(performance, "useBlockFaceCulling", false);
        setBoolean(performance, "useFogOcclusion", false);
    }

    public static void restorePointCameraCulling()
    {
        Object performance = performance();

        if (performance == null)
        {
            return;
        }

        setBoolean(performance, "useBlockFaceCulling", savedBlockFaceCulling);
        setBoolean(performance, "useFogOcclusion", savedFogOcclusion);
    }

    private static Object performance()
    {
        try
        {
            Class<?> owner = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod");
            Method selected = null;

            for (Method candidate : owner.getDeclaredMethods())
            {
                if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                    && candidate.getParameterCount() == 0
                    && candidate.getReturnType().getName().contains("SodiumGameOptions"))
                {
                    selected = candidate;

                    break;
                }
            }

            if (selected == null)
            {
                return null;
            }

            Object options = selected.invoke(null);
            Field field = options.getClass().getField("performance");

            return field.get(options);
        }
        catch (Throwable ignored)
        {
            /* Sodium's options accessor changed between 0.6 and 0.8. Ortho is
             * optional; an incompatible Sodium must not crash camera rendering. */
            return null;
        }
    }

    private static boolean getBoolean(Object object, String name)
    {
        try
        {
            return object.getClass().getField(name).getBoolean(object);
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static void setBoolean(Object object, String name, boolean value)
    {
        try
        {
            object.getClass().getField(name).setBoolean(object, value);
        }
        catch (Throwable ignored)
        {}
    }
}
