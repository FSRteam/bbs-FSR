package mchorse.bbs_mod.ui.utils.motion;

import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;

/**
 * Render-only tween between 0 and 1 (or any two values) driven by a theme's
 * {@link UIThemeMotion} entry. When motion is disabled at any level, the
 * tween snaps instantly, so callers can branch on {@link #isSettled()} to
 * short-circuit straight into the pre-animation code path.
 *
 * <p>RED LINE: this class must only ever influence drawing (offsets, scale,
 * alpha, color mixing). Never hit testing, event dispatch or element
 * lifecycle.
 */
public class UITween
{
    private float value;
    private float start;
    private float target;
    private long startMs;
    private int durationMs;
    private IInterp easing = Interpolations.SINE_OUT;

    public UITween()
    {}

    public UITween(float initial)
    {
        this.snap(initial);
    }

    /** Jump to a value with no animation. */
    public void snap(float value)
    {
        this.value = value;
        this.start = value;
        this.target = value;
        this.durationMs = 0;
    }

    /**
     * Start (or continue) tweening toward the target. Calling this every
     * frame is fine: an already-matching target is a no-op. A disabled or
     * zero-duration motion snaps immediately.
     */
    public void to(float target, UIThemeMotion spec)
    {
        if (this.target == target)
        {
            return;
        }

        int duration = UIMotions.duration(spec);

        if (duration <= 0)
        {
            this.snap(target);

            return;
        }

        this.start = this.value;
        this.target = target;
        this.startMs = System.currentTimeMillis();
        this.durationMs = duration;
        this.easing = spec.easing == null ? Interpolations.SINE_OUT : spec.easing;
    }

    /** Advance to the current time and return the tweened value. */
    public float update()
    {
        return this.update(System.currentTimeMillis());
    }

    public float update(long nowMs)
    {
        if (this.value != this.target)
        {
            float progress = this.durationMs <= 0 ? 1F : MathUtils.clamp((nowMs - this.startMs) / (float) this.durationMs, 0F, 1F);

            this.value = progress >= 1F ? this.target : this.easing.interpolate(this.start, this.target, progress);
        }

        return this.value;
    }

    public float getValue()
    {
        return this.value;
    }

    public float getTarget()
    {
        return this.target;
    }

    /**
     * True when the tween has reached its target. Callers use this to take
     * the exact pre-animation code path (bit-identical rendering) instead of
     * evaluating interpolated values.
     */
    public boolean isSettled()
    {
        return this.value == this.target;
    }
}
