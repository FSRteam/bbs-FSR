package mchorse.bbs_mod.utils.sodium;

import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexSodiumConsumer;
import mchorse.bbs_mod.utils.colors.Color;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumGameOptions;

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
        SodiumGameOptions.PerformanceSettings performance = SodiumClientMod.options().performance;

        savedBlockFaceCulling = performance.useBlockFaceCulling;
        savedFogOcclusion = performance.useFogOcclusion;
        performance.useBlockFaceCulling = false;
        performance.useFogOcclusion = false;
    }

    public static void restorePointCameraCulling()
    {
        SodiumGameOptions.PerformanceSettings performance = SodiumClientMod.options().performance;

        performance.useBlockFaceCulling = savedBlockFaceCulling;
        performance.useFogOcclusion = savedFogOcclusion;
    }
}
