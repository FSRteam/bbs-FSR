package mchorse.bbs_mod.ui.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.ui.themes.UITheme;
import mchorse.bbs_mod.ui.themes.UIThemeDecoration;

/**
 * Draws a theme's root backdrop (background texture with its fill mode and
 * dim veil) and its decoration stickers. Both are pure rendering overlays:
 * they never take part in hit testing. Draw order is background, dim,
 * decorations, then the main UI on top.
 */
public class UIThemeBackdrop
{
    private UIThemeBackdrop()
    {}

    /**
     * Draw a background texture (or flat color when the texture is null)
     * using the current theme's fill mode, then the dim veil.
     */
    public static void renderBackground(UIRenderingContext context, Link background, int color, int w, int h)
    {
        UITheme theme = ThemeManager.current();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (background == null)
        {
            context.batcher.box(0, 0, w, h, color);
        }
        else
        {
            Texture texture = context.getTextures().getTexture(background);

            switch (theme.backgroundMode)
            {
                case COVER -> renderCover(context, texture, color, w, h);
                case TILE -> renderTile(context, texture, color, w, h);
                default -> context.batcher.texturedBox(texture, color, 0, 0, w, h, 0, 0, w, h, w, h);
            }
        }

        if (theme.backgroundDim > 0F)
        {
            context.batcher.box(0, 0, w, h, ((int) (theme.backgroundDim * 255F)) << 24);
        }
    }

    private static void renderCover(UIRenderingContext context, Texture texture, int color, int w, int h)
    {
        if (texture.width <= 0 || texture.height <= 0)
        {
            return;
        }

        float scale = Math.max(w / (float) texture.width, h / (float) texture.height);
        float srcW = w / scale;
        float srcH = h / scale;
        float u1 = (texture.width - srcW) / 2F;
        float v1 = (texture.height - srcH) / 2F;

        context.batcher.texturedBox(texture, color, 0, 0, w, h, u1, v1, u1 + srcW, v1 + srcH, texture.width, texture.height);
    }

    private static void renderTile(UIRenderingContext context, Texture texture, int color, int w, int h)
    {
        if (texture.width <= 0 || texture.height <= 0)
        {
            return;
        }

        for (int y = 0; y < h; y += texture.height)
        {
            for (int x = 0; x < w; x += texture.width)
            {
                float tileW = Math.min(texture.width, w - x);
                float tileH = Math.min(texture.height, h - y);

                context.batcher.texturedBox(texture, color, x, y, tileW, tileH, 0, 0, tileW, tileH, texture.width, texture.height);
            }
        }
    }

    /** Draw the current theme's decoration stickers (no-op without any). */
    public static void renderDecorations(UIRenderingContext context, int w, int h)
    {
        UITheme theme = ThemeManager.current();

        if (theme.decorations.isEmpty())
        {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        for (int i = 0; i < theme.decorations.size(); i++)
        {
            UIThemeDecoration decoration = theme.decorations.get(i);
            Texture texture = context.getTextures().getTexture(decoration.texture);

            if (texture.width <= 0 || texture.height <= 0)
            {
                continue;
            }

            float dw = texture.width * decoration.scale;
            float dh = texture.height * decoration.scale;
            float x = decoration.anchor.x * (w - dw) + decoration.offsetX;
            float y = decoration.anchor.y * (h - dh) + decoration.offsetY;
            int color = ((int) (decoration.opacity * 255F)) << 24 | 0xffffff;

            context.batcher.texturedBox(texture, color, x, y, dw, dh, 0, 0, texture.width, texture.height, texture.width, texture.height);
        }
    }
}
