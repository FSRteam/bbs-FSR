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
    private static final float CRITICAL_DAMPING_EPSILON = 1e-4F;
    private static final float SETTLE_DISTANCE_EPSILON = 0.001F;
    private static final float SETTLE_VELOCITY_EPSILON = 0.01F;
    private static final long RETARGET_REUSE_WINDOW_MS = 16L;

    private float value;
    private float velocity;
    private float start;
    private float target;
    private float startVelocity;
    private long startMs;
    private int durationMs;
    private float responseSec = UIThemeMotion.DEFAULT_RESPONSE;
    private float damping = UIThemeMotion.DEFAULT_DAMPING;
    private IInterp easing = Interpolations.SINE_OUT;
    private UIThemeMotion.MotionType type = UIThemeMotion.MotionType.EASE;
    private long lastSampleMs;
    private boolean settled = true;

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
        this.velocity = 0F;
        this.start = value;
        this.target = value;
        this.startVelocity = 0F;
        this.startMs = 0L;
        this.durationMs = 0;
        this.responseSec = UIThemeMotion.DEFAULT_RESPONSE;
        this.damping = UIThemeMotion.DEFAULT_DAMPING;
        this.easing = Interpolations.SINE_OUT;
        this.type = UIThemeMotion.MotionType.EASE;
        this.lastSampleMs = 0L;
        this.settled = true;
    }

    /**
     * Start (or continue) tweening toward the target. Calling this every
     * frame is fine: an already-matching target is a no-op. A disabled or
     * zero-duration motion snaps immediately.
     */
    public void to(float target, UIThemeMotion spec)
    {
        UIThemeMotion.MotionType nextType = UIMotions.type(spec);
        int duration = UIMotions.duration(spec);
        float response = nextType == UIThemeMotion.MotionType.SPRING ? UIMotions.response(spec) : 0F;

        if ((nextType == UIThemeMotion.MotionType.SPRING ? response <= 0F : duration <= 0))
        {
            this.snap(target);

            return;
        }

        if (this.target == target)
        {
            return;
        }

        long currentMs = System.currentTimeMillis();
        long nowMs = currentMs;

        if (!this.settled && this.lastSampleMs > 0L && currentMs - this.lastSampleMs <= RETARGET_REUSE_WINDOW_MS)
        {
            nowMs = this.lastSampleMs;
        }

        this.update(nowMs);

        this.start = this.value;
        this.target = target;
        this.startVelocity = this.velocity;
        this.startMs = nowMs;
        this.durationMs = duration;
        this.easing = UIMotions.easing(spec);
        this.responseSec = nextType == UIThemeMotion.MotionType.SPRING ? response : UIThemeMotion.DEFAULT_RESPONSE;
        this.damping = nextType == UIThemeMotion.MotionType.SPRING ? spec.damping : UIThemeMotion.DEFAULT_DAMPING;
        this.type = nextType;
        this.settled = false;
    }

    /** Advance to the current time and return the tweened value. */
    public float update()
    {
        return this.update(System.currentTimeMillis());
    }

    public float update(long nowMs)
    {
        if (this.settled)
        {
            return this.value;
        }

        if (this.type == UIThemeMotion.MotionType.SPRING)
        {
            this.updateSpring(nowMs);
        }
        else
        {
            this.updateEase(nowMs);
        }

        this.lastSampleMs = nowMs;

        return this.value;
    }

    private void updateEase(long nowMs)
    {
        float progress = this.durationMs <= 0 ? 1F : MathUtils.clamp((nowMs - this.startMs) / (float) this.durationMs, 0F, 1F);

        if (progress >= 1F)
        {
            this.value = this.target;
            this.velocity = 0F;
            this.startVelocity = 0F;
            this.settled = true;

            return;
        }

        float current = this.easing.interpolate(this.start, this.target, progress);

        this.velocity = easeVelocity(nowMs, progress, current);
        this.value = current;
    }

    private float easeVelocity(long nowMs, float progress, float current)
    {
        if (progress <= 0F)
        {
            return 0F;
        }

        long previousMs = Math.max(this.startMs, nowMs - 1L);

        if (previousMs == nowMs)
        {
            return 0F;
        }

        float previousProgress = MathUtils.clamp((previousMs - this.startMs) / (float) this.durationMs, 0F, 1F);
        float previous = previousProgress <= 0F ? this.start : this.easing.interpolate(this.start, this.target, previousProgress);

        return (current - previous) / ((nowMs - previousMs) / 1000F);
    }

    private void updateSpring(long nowMs)
    {
        double t = Math.max(0D, (nowMs - this.startMs) / 1000D);
        double omega = (Math.PI * 2D) / this.responseSec;
        double d0 = this.start - this.target;
        double v0 = this.startVelocity;
        double displacement;
        double velocity;

        if (Math.abs(this.damping - 1F) < CRITICAL_DAMPING_EPSILON)
        {
            double a = d0;
            double b = v0 + omega * d0;
            double exp = Math.exp(-omega * t);

            displacement = exp * (a + b * t);
            velocity = exp * (b - omega * (a + b * t));
        }
        else if (this.damping < 1F)
        {
            double omegaD = omega * Math.sqrt(1D - this.damping * this.damping);
            double a = d0;
            double b = (v0 + this.damping * omega * d0) / omegaD;
            double exp = Math.exp(-this.damping * omega * t);
            double cos = Math.cos(omegaD * t);
            double sin = Math.sin(omegaD * t);

            displacement = exp * (a * cos + b * sin);
            velocity = exp * ((b * omegaD - a * this.damping * omega) * cos - (a * omegaD + b * this.damping * omega) * sin);
        }
        else
        {
            double omegaR = omega * Math.sqrt(this.damping * this.damping - 1D);
            double r1 = -this.damping * omega + omegaR;
            double r2 = -this.damping * omega - omegaR;
            double c1 = (v0 - r2 * d0) / (r1 - r2);
            double c2 = d0 - c1;
            double exp1 = Math.exp(r1 * t);
            double exp2 = Math.exp(r2 * t);

            displacement = c1 * exp1 + c2 * exp2;
            velocity = c1 * r1 * exp1 + c2 * r2 * exp2;
        }

        float distance = (float) displacement;
        float speed = (float) velocity;

        if (Math.abs(distance) < SETTLE_DISTANCE_EPSILON && Math.abs(speed) < SETTLE_VELOCITY_EPSILON)
        {
            this.snap(this.target);

            return;
        }

        this.value = this.target + distance;
        this.velocity = speed;
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
        return this.settled;
    }
}
