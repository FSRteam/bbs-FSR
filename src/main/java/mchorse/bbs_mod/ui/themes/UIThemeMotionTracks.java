package mchorse.bbs_mod.ui.themes;

/**
 * Optional track orchestration of a motion entry: per-property "from" values
 * animated toward the resting state (alpha 1, scale 1, offset 0). Built once
 * at parse time; sampling is pure arithmetic with zero lookups.
 *
 * <p>Application order is fixed: translate(x, y), then scale around the
 * touch point's anchor, then alpha. Enter plays from -> rest, exit plays the
 * same tracks in reverse (rest -> from).
 */
public class UIThemeMotionTracks
{
    /** The v1 default overlay look: slight scale up with a fade. */
    public static final UIThemeMotionTracks PRESET_SCALE = new UIThemeMotionTracks(0F, 0.95F, 0F, 0F);
    public static final UIThemeMotionTracks PRESET_SLIDE_RIGHT = new UIThemeMotionTracks(0F, 1F, 24F, 0F);
    public static final UIThemeMotionTracks PRESET_SLIDE_UP = new UIThemeMotionTracks(0F, 1F, 0F, 8F);
    public static final UIThemeMotionTracks PRESET_FADE = new UIThemeMotionTracks(0F, 1F, 0F, 0F);

    public final float alphaFrom;
    public final float scaleFrom;
    public final float xFrom;
    public final float yFrom;

    public UIThemeMotionTracks(float alphaFrom, float scaleFrom, float xFrom, float yFrom)
    {
        this.alphaFrom = alphaFrom;
        this.scaleFrom = scaleFrom;
        this.xFrom = xFrom;
        this.yFrom = yFrom;
    }

    /* Sampling: factor 0 = "from", factor 1 = resting state */

    public float alphaAt(float factor)
    {
        return this.alphaFrom + (1F - this.alphaFrom) * factor;
    }

    public float scaleAt(float factor)
    {
        return this.scaleFrom + (1F - this.scaleFrom) * factor;
    }

    public float xAt(float factor)
    {
        return this.xFrom * (1F - factor);
    }

    public float yAt(float factor)
    {
        return this.yFrom * (1F - factor);
    }
}
