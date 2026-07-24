package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.resources.Link;

/**
 * A single decorative sticker of a UI theme: a texture anchored to one of
 * nine screen positions, drawn after the background (and its dim veil) but
 * before the main UI. Decorations are pure rendering: they never take part
 * in hit testing.
 */
public class UIThemeDecoration
{
    public enum Anchor
    {
        TOP_LEFT(0F, 0F),
        TOP(0.5F, 0F),
        TOP_RIGHT(1F, 0F),
        LEFT(0F, 0.5F),
        CENTER(0.5F, 0.5F),
        RIGHT(1F, 0.5F),
        BOTTOM_LEFT(0F, 1F),
        BOTTOM(0.5F, 1F),
        BOTTOM_RIGHT(1F, 1F);

        /** Normalized screen position this anchor pins the texture to. */
        public final float x;
        public final float y;

        Anchor(float x, float y)
        {
            this.x = x;
            this.y = y;
        }
    }

    public final Link texture;
    public final Anchor anchor;
    public final int offsetX;
    public final int offsetY;
    public final float scale;
    public final float opacity;

    public UIThemeDecoration(Link texture, Anchor anchor, int offsetX, int offsetY, float scale, float opacity)
    {
        this.texture = texture;
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.scale = scale;
        this.opacity = opacity;
    }
}
