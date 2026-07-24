package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.utils.interps.IInterp;

/**
 * A single motion entry of a UI theme (one animation touch point).
 */
public class UIThemeMotion
{
    public final boolean enabled;

    /** Base duration in milliseconds, before speed multipliers are applied. */
    public final int duration;
    public final IInterp easing;

    public UIThemeMotion(boolean enabled, int duration, IInterp easing)
    {
        this.enabled = enabled;
        this.duration = duration;
        this.easing = easing;
    }
}
