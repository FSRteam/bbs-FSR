package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.utils.interps.IInterp;

/**
 * A single motion entry of a UI theme (one animation touch point).
 */
public class UIThemeMotion
{
    public static final float DEFAULT_RESPONSE = 0.3F;
    public static final float DEFAULT_DAMPING = 0.9F;

    public enum MotionType
    {
        EASE,
        SPRING
    }

    public final boolean enabled;

    /** Base duration in milliseconds, before speed multipliers are applied. */
    public final int duration;
    public final IInterp easing;
    public final MotionType type;
    public final float response;
    public final float damping;

    /** Optional track orchestration; null keeps the touch point's built-in transform. */
    public final UIThemeMotionTracks tracks;

    /**
     * Entry-local scale magnitude for the touch points that use one
     * (hover_scale, press). 1 = no scaling, matching the disabled look.
     */
    public final float scale;

    public UIThemeMotion(boolean enabled, int duration, IInterp easing)
    {
        this(enabled, duration, easing, MotionType.EASE, DEFAULT_RESPONSE, DEFAULT_DAMPING);
    }

    public UIThemeMotion(boolean enabled, int duration, IInterp easing, MotionType type, float response, float damping)
    {
        this(enabled, duration, easing, type, response, damping, null);
    }

    public UIThemeMotion(boolean enabled, int duration, IInterp easing, MotionType type, float response, float damping, UIThemeMotionTracks tracks)
    {
        this(enabled, duration, easing, type, response, damping, tracks, 1F);
    }

    public UIThemeMotion(boolean enabled, int duration, IInterp easing, MotionType type, float response, float damping, UIThemeMotionTracks tracks, float scale)
    {
        this.enabled = enabled;
        this.duration = duration;
        this.easing = easing;
        this.type = type == null ? MotionType.EASE : type;
        this.response = response;
        this.damping = damping;
        this.tracks = tracks;
        this.scale = scale;
    }
}
