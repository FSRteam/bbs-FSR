package mchorse.bbs_mod.forms.renderers.utils;

import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;

public class FormColorBlend
{
    public static final float BRIGHTEN_STRENGTH = 8F;

    public enum BlendMode
    {
        MULTIPLY,
        BRIGHTEN
    }

    public static void blend(Color base, Color overlay, boolean additive)
    {
        blend(base, overlay, additive ? BlendMode.BRIGHTEN : BlendMode.MULTIPLY);
    }

    public static void blend(Color base, Color overlay, BlendMode mode)
    {
        if (base == null || overlay == null)
        {
            return;
        }

        float a = MathUtils.clamp(overlay.a, 0F, 1F);
        float r = MathUtils.clamp(overlay.r, 0F, 1F);
        float g = MathUtils.clamp(overlay.g, 0F, 1F);
        float b = MathUtils.clamp(overlay.b, 0F, 1F);

        if (mode == BlendMode.BRIGHTEN)
        {
            /*
             * Colors are applied as a texture multiplier in this pipeline. Brighten mode
             * intentionally allows values above 1.0 so the tint can lift the texture
             * toward white instead of only darkening it.
             */
            base.r *= 1F + r * a * BRIGHTEN_STRENGTH;
            base.g *= 1F + g * a * BRIGHTEN_STRENGTH;
            base.b *= 1F + b * a * BRIGHTEN_STRENGTH;
        }
        else
        {
            base.r *= r;
            base.g *= g;
            base.b *= b;
        }

        base.a *= a;
    }
}
