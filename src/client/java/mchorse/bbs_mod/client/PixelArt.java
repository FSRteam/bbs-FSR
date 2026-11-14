package mchorse.bbs_mod.client;

import mchorse.bbs_mod.BBSSettings;
import net.minecraft.client.renderer.ShaderInstance;

/** Selects seam smoothing programs for BBS UI drawn at fractional scale. */
public class PixelArt
{
    private static boolean drawingUI;

    public static boolean isEnabled()
    {
        return BBSSettings.pixelArtSmoothing.get();
    }

    /** World text shares vanilla's programs, so only BBS UI may replace them. */
    public static void setDrawingUI(boolean drawing)
    {
        drawingUI = drawing;
    }

    /** Null retains vanilla's program, including after optional shader failure. */
    public static ShaderInstance getTextProgram(boolean intensity)
    {
        if (!drawingUI || !isEnabled())
        {
            return null;
        }

        return intensity ? BBSShaders.getPixelArtTextIntensityProgram() : BBSShaders.getPixelArtTextProgram();
    }
}
