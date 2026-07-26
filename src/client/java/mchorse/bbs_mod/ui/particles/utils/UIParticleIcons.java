package mchorse.bbs_mod.ui.particles.utils;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.components.appearance.CameraFacing;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcons;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public final class UIParticleIcons
{
    private UIParticleIcons()
    {}

    public static void addMaterialModes(UIIcons icons)
    {
        icons.add(Icons.SQUARE, UIKeys.SNOWSTORM_GENERAL_PARTICLES_OPAQUE);
        icons.add(Icons.DROP, UIKeys.SNOWSTORM_GENERAL_PARTICLES_ALPHA);
        icons.add(Icons.FADING, UIKeys.SNOWSTORM_GENERAL_PARTICLES_BLEND);
        icons.add(Icons.LIGHT, UIKeys.SNOWSTORM_GENERAL_PARTICLES_ADD);
    }

    public static void addTextureModes(UIIcons icons)
    {
        icons.add(Icons.IMAGE, UIKeys.SNOWSTORM_APPEARANCE_REGULAR);
        icons.add(Icons.FILM, UIKeys.SNOWSTORM_APPEARANCE_ANIMATED);
        icons.add(Icons.FULLSCREEN, UIKeys.SNOWSTORM_APPEARANCE_FULL);
    }

    public static void addFacingModes(UIIcons icons)
    {
        for (CameraFacing facing : CameraFacing.values())
        {
            icons.add(facingIcon(facing), IKey.raw(facing.id));
        }
    }

    public static void addDirectionModes(UIIcons icons)
    {
        icons.add(Icons.ALL_DIRECTIONS, UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_DERIVE);
        icons.add(Icons.CODE, UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_CUSTOM);
    }

    public static void addColorModes(UIIcons icons)
    {
        icons.add(Icons.COLOR, UIKeys.SNOWSTORM_LIGHTING_SOLID);
        icons.add(Icons.CODE, UIKeys.SNOWSTORM_LIGHTING_EXPRESSION);
        icons.add(Icons.FADING, UIKeys.SNOWSTORM_LIGHTING_GRADIENT);
    }

    private static Icon facingIcon(CameraFacing facing)
    {
        return switch (facing)
        {
            case ROTATE_XYZ -> Icons.ALL_DIRECTIONS;
            case ROTATE_Y -> Icons.ORBIT;
            case LOOKAT_XYZ -> Icons.LOOKING;
            case LOOKAT_Y -> Icons.CAMERA;
            case LOOKAT_DIRECTION -> Icons.ARROW_RIGHT;
            case DIRECTION_X, EMITTER_TRANSFORM_YZ -> Icons.X;
            case DIRECTION_Y, EMITTER_TRANSFORM_XZ -> Icons.Y;
            case DIRECTION_Z, EMITTER_TRANSFORM_XY -> Icons.Z;
        };
    }
}
