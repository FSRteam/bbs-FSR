package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import org.lwjgl.opengl.GL11;

public final class UIArt
{
    public static final Link ROUNDED_RECT_MASK = Link.assets("textures/gui/rounded_rect_mask.png");
    public static final Link FILLED_CIRCLE_MASK = Link.assets("textures/gui/filled_circle_mask.png");

    private UIArt()
    {}

    public static Texture roundedRectMask()
    {
        return getMask(ROUNDED_RECT_MASK);
    }

    public static Texture filledCircleMask()
    {
        return getMask(FILLED_CIRCLE_MASK);
    }

    private static Texture getMask(Link link)
    {
        Texture mask = BBSModClient.getTextures().getTexture(link, GL11.GL_LINEAR, true);
        Texture error = BBSModClient.getTextures().getError();

        return mask != null && mask.isValid() && mask != error && mask.width > 0 && mask.height > 0 ? mask : null;
    }
}
