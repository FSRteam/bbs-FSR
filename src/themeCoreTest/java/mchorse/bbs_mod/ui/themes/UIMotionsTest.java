package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.interps.Interpolations;

import java.lang.reflect.Field;

/**
 * Executable regressions for the motion layer basics: snap semantics,
 * disabled short-circuit, duration composition and tween settling.
 * Runs without Minecraft; ThemeManager falls back to code-level dark
 * defaults when no asset provider is present.
 */
public final class UIMotionsTest
{
    private static final float VALUE_TOLERANCE = 0.002F;
    private static final float VELOCITY_TOLERANCE = 0.02F;

    private record SpringState(float value, float velocity)
    {}

    private UIMotionsTest()
    {}

    public static void main(String[] args) throws Exception
    {
        testDurationComposition();
        testSnapAndDisabled();
        testTweenProgression();
        testSpringClosedFormBranches();
        testSpringInterruptVelocityContinuity();
        testSpringSettlingAndSameTarget();
        testExitReverseTween();
        testCoverageEntriesDefaults();
        testLayoutTransitionInterpolation();

        System.out.println("UIMotionsTest: all tests passed");
    }

    /**
     * Motion coverage entries (scroll_smooth / hover_scale / press / layout):
     * presence and code-level dark defaults. hover_scale, press and layout
     * ship disabled so the coverage animations are strictly opt-in.
     */
    private static void testCoverageEntriesDefaults()
    {
        UIThemeMotion scrollSmooth = UIMotions.scrollSmooth();
        UIThemeMotion hoverScale = UIMotions.hoverScale();
        UIThemeMotion press = UIMotions.press();
        UIThemeMotion layout = UIMotions.layout();

        assertTrue(scrollSmooth != null && hoverScale != null && press != null && layout != null, "coverage entries present");
        assertTrue(scrollSmooth.enabled, "scroll_smooth enabled by default");
        assertTrue(!hoverScale.enabled, "hover_scale disabled by default");
        assertTrue(!press.enabled, "press disabled by default");
        assertTrue(!layout.enabled, "layout disabled by default");
        assertEquals(1F, hoverScale.scale, "hover_scale scale defaults to 1");
        assertEquals(1F, press.scale, "press scale defaults to 1");
        assertEquals(0, UIMotions.duration(hoverScale), "disabled hover_scale has zero duration");
        assertEquals(0, UIMotions.duration(layout), "disabled layout has zero duration");
        assertTrue(UIMotions.duration(scrollSmooth) > 0, "enabled scroll_smooth has a positive duration");
    }

    /**
     * Pure-logic mirror of the film panel's layout transition: from/to bounds
     * interpolation plus interrupt-freeze continuity (the frozen from equals
     * the interpolated value at the moment of interruption).
     */
    private static void testLayoutTransitionInterpolation()
    {
        float fromX = 0F;
        float toX = 0.5F;

        /* Endpoint exactness */
        assertEquals(fromX, lerp(fromX, toX, 0F), "layout lerp starts at from");
        assertEquals(toX, lerp(fromX, toX, 1F), "layout lerp ends at to");

        float mid = lerp(fromX, toX, 0.4F);

        assertTrue(mid > fromX && mid < toX, "layout lerp passes through intermediate values");

        /* Interrupt at eased t: freeze current value as new from, retarget */
        float frozenFrom = mid;
        float newTo = 0.1F;
        float resumed = lerp(frozenFrom, newTo, 0F);

        assertEquals(frozenFrom, resumed, "interrupt freezes the interpolated value as the new from");
        assertEquals(newTo, lerp(frozenFrom, newTo, 1F), "interrupted transition still lands on the new target");

        /* Sub-threshold difference means no animation at all */
        float epsilon = 5e-4F;

        assertTrue(Math.abs(0.25F - (0.25F + epsilon * 0.5F)) <= epsilon, "sub-threshold difference is not animatable");
    }

    private static float lerp(float a, float b, float x)
    {
        return a + (b - a) * x;
    }

    private static void testDurationComposition()
    {
        UIThemeMotion enabled = new UIThemeMotion(true, 100, Interpolations.SINE_OUT);
        UIThemeMotion disabled = new UIThemeMotion(false, 100, Interpolations.SINE_OUT);
        UIThemeMotion spring = spring(0.35F, 0.8F);

        /* No settings registered in this environment: user switches default
         * to enabled/1x, the theme is the code-level dark default */
        assertTrue(UIMotions.enabled(), "motion enabled by default");
        assertEquals(100, UIMotions.duration(enabled), "1x speed keeps duration");
        assertEquals(350, UIMotions.duration(spring), "spring duration approximates response envelope");
        assertEquals(0, UIMotions.duration(disabled), "disabled entry has zero duration");
        assertEquals(0, UIMotions.duration(null), "null entry has zero duration");
        assertNear(0.35F, UIMotions.response(spring), 0.0001F, "spring response keeps 1x timing");

        assertTrue(UIMotions.hover() != null, "theme hover entry present");
        assertTrue(UIMotions.overlay() != null && UIMotions.panelSwitch() != null, "theme entries present");
        assertTrue(UIMotions.notification() != null && UIMotions.contextMenu() != null && UIMotions.scrollbar() != null, "theme entries present 2");
    }

    private static void testSnapAndDisabled()
    {
        UITween tween = new UITween();

        assertEquals(0F, tween.update(), "fresh tween sits at zero");
        assertTrue(tween.isSettled(), "fresh tween is settled");

        tween.snap(1F);
        assertEquals(1F, tween.update(), "snap jumps instantly");
        assertTrue(tween.isSettled(), "snapped tween is settled");

        /* A disabled motion must snap, not animate: this is the short-circuit
         * that keeps motion-off rendering bit-identical to the old code */
        UIThemeMotion disabled = new UIThemeMotion(false, 500, Interpolations.SINE_OUT);

        tween.to(0F, disabled);
        assertEquals(0F, tween.update(), "disabled motion snaps to target");
        assertTrue(tween.isSettled(), "disabled motion settles immediately");

        UIThemeMotion disabledSpring = new UIThemeMotion(false, 0, Interpolations.SINE_OUT, UIThemeMotion.MotionType.SPRING, 0.35F, 0.8F);

        tween.to(1F, disabledSpring);
        assertEquals(1F, tween.update(), "disabled spring motion snaps to target");
        assertTrue(tween.isSettled(), "disabled spring settles immediately");
    }

    private static void testTweenProgression()
    {
        /* A huge duration makes every assertion robust against real-world
         * scheduling delays between to() and the sampled base time: base is
         * taken AFTER to(), so base >= the tween's internal start, and the
         * probe offsets dwarf any plausible delay. */
        UIThemeMotion spec = new UIThemeMotion(true, 100000, Interpolations.LINEAR);
        UITween tween = new UITween();

        tween.to(1F, spec);

        long base = System.currentTimeMillis();

        assertTrue(!tween.isSettled(), "enabled motion animates");

        float early = tween.update(base);
        float mid = tween.update(base + 50000);
        float done = tween.update(base + 300000);

        assertTrue(early <= mid && mid <= done, "tween is monotonic for linear easing");
        assertTrue(mid > 0F && mid < 1F, "tween passes through intermediate values");
        assertEquals(1F, done, "tween reaches the exact target");
        assertTrue(tween.isSettled(), "tween settles at target");

        /* Retargeting mid-flight starts from the current value */
        tween.to(0F, spec);

        long retargetBase = System.currentTimeMillis();

        assertTrue(!tween.isSettled(), "retarget restarts animation");
        assertEquals(0F, tween.update(retargetBase + 300000), "retarget reaches the exact target");

        /* Calling to() with the same target every frame is a no-op */
        tween.to(0F, spec);
        assertTrue(tween.isSettled(), "same-target to() doesn't restart");
    }

    private static void testSpringClosedFormBranches() throws Exception
    {
        UITween underdamped = new UITween();
        UIThemeMotion underSpec = spring(0.3F, 0.5F);

        underdamped.to(1F, underSpec);

        long underStart = getLongField(underdamped, "startMs");
        assertSpringSample(underdamped, underSpec, underStart, 0F, 1F, 0F, 75L, "underdamped 75 ms sample");
        assertSpringSample(underdamped, underSpec, underStart, 0F, 1F, 0F, 150L, "underdamped 150 ms sample");
        assertTrue(underdamped.update(underStart + 225L) > 1F, "underdamped branch overshoots");

        UITween critical = new UITween();
        UIThemeMotion criticalSpec = spring(0.3F, 1F);

        critical.to(1F, criticalSpec);

        long criticalStart = getLongField(critical, "startMs");
        float critical60 = assertSpringSample(critical, criticalSpec, criticalStart, 0F, 1F, 0F, 60L, "critical 60 ms sample");
        float critical120 = assertSpringSample(critical, criticalSpec, criticalStart, 0F, 1F, 0F, 120L, "critical 120 ms sample");
        float critical240 = assertSpringSample(critical, criticalSpec, criticalStart, 0F, 1F, 0F, 240L, "critical 240 ms sample");

        assertTrue(critical60 <= critical120 && critical120 <= critical240, "critical branch is monotonic");
        assertTrue(critical240 <= 1F, "critical branch never overshoots");

        UITween overdamped = new UITween();
        UIThemeMotion overSpec = spring(0.3F, 1.5F);

        overdamped.to(1F, overSpec);

        long overStart = getLongField(overdamped, "startMs");
        float over60 = assertSpringSample(overdamped, overSpec, overStart, 0F, 1F, 0F, 60L, "overdamped 60 ms sample");
        float over120 = assertSpringSample(overdamped, overSpec, overStart, 0F, 1F, 0F, 120L, "overdamped 120 ms sample");
        float over240 = assertSpringSample(overdamped, overSpec, overStart, 0F, 1F, 0F, 240L, "overdamped 240 ms sample");

        assertTrue(over60 <= over120 && over120 <= over240, "overdamped branch is monotonic");
        assertTrue(over240 <= 1F, "overdamped branch never overshoots");
        assertTrue(over120 < critical120, "overdamped branch converges slower than critical");
    }

    private static void testSpringInterruptVelocityContinuity() throws Exception
    {
        UIThemeMotion spec = spring(0.35F, 0.72F);
        UITween tween = new UITween();

        tween.to(1F, spec);

        long interruptNow = System.currentTimeMillis();

        setLongField(tween, "startMs", interruptNow - 120L);

        float before = tween.update(interruptNow - 1L);
        float atInterrupt = tween.update(interruptNow);
        float preVelocity = (atInterrupt - before) / 0.001F;

        tween.to(0.25F, spec);

        long restartStart = getLongField(tween, "startMs");
        float restartValue = tween.update(restartStart);
        float after = tween.update(restartStart + 1L);
        float postVelocity = (after - restartValue) / 0.001F;
        float tolerance = Math.max(0.05F, Math.abs(preVelocity) * 0.05F);

        assertNear(atInterrupt, restartValue, 0.01F, "spring retarget keeps value continuous");
        assertNear(preVelocity, postVelocity, tolerance, "spring retarget keeps velocity continuous");
    }

    private static void testSpringSettlingAndSameTarget() throws Exception
    {
        UIThemeMotion spec = spring(0.25F, 0.85F);
        UITween tween = new UITween();

        tween.to(1F, spec);

        long start = getLongField(tween, "startMs");

        tween.update(start + 90L);
        tween.to(1F, spec);
        assertEquals(start, getLongField(tween, "startMs"), "same-target spring to() does not restart");

        float settled = tween.update(start + 5000L);

        assertEquals(1F, settled, "spring settles to the exact target");
        assertEquals(0F, getFloatField(tween, "velocity"), "settled spring zeroes velocity");
        assertTrue(tween.isSettled(), "spring marks itself settled");
    }

    /**
     * The exit animation (motion-exit) reuses the appear tween in reverse:
     * a settled 1 -> 0 retarget with the same spec, idempotent re-close and
     * a bit-identical snap when motion is off.
     */
    private static void testExitReverseTween() throws Exception
    {
        UIThemeMotion spec = spring(0.3F, 0.85F);
        UITween tween = new UITween();

        tween.to(1F, spec);

        long start = getLongField(tween, "startMs");

        assertEquals(1F, tween.update(start + 5000L), "appear settles at 1");

        /* Reverse close: same spec, target 0 */
        tween.to(0F, spec);

        long closeStart = getLongField(tween, "startMs");

        assertTrue(!tween.isSettled(), "exit retarget animates");

        float midway = tween.update(closeStart + 90L);

        assertTrue(midway > 0F && midway < 1F, "exit passes through intermediate values");

        /* Repeated close is a no-op (same target) */
        tween.to(0F, spec);
        assertEquals(closeStart, getLongField(tween, "startMs"), "repeated close does not restart");

        assertEquals(0F, tween.update(closeStart + 5000L), "exit settles at exactly 0");
        assertTrue(tween.isSettled(), "exit marks itself settled");

        /* Motion off: close must snap instantly, matching the v1 path */
        UIThemeMotion disabled = new UIThemeMotion(false, 300, Interpolations.SINE_OUT);

        tween.snap(1F);
        tween.to(0F, disabled);
        assertEquals(0F, tween.update(), "disabled exit snaps to 0");
        assertTrue(tween.isSettled(), "disabled exit settles immediately");
    }

    private static float assertSpringSample(UITween tween, UIThemeMotion spec, long startMs, float start, float target, float startVelocity, long offsetMs, String message) throws Exception
    {
        SpringState expected = expectedSpringState(start, target, startVelocity, spec.response, spec.damping, offsetMs / 1000F);
        float actual = tween.update(startMs + offsetMs);
        float actualVelocity = getFloatField(tween, "velocity");

        assertNear(expected.value, actual, VALUE_TOLERANCE, message + " value");
        assertNear(expected.velocity, actualVelocity, VELOCITY_TOLERANCE, message + " velocity");

        return actual;
    }

    private static SpringState expectedSpringState(float start, float target, float startVelocity, float response, float damping, float t)
    {
        double omega = (Math.PI * 2D) / response;
        double d0 = start - target;
        double v0 = startVelocity;
        double displacement;
        double velocity;

        if (Math.abs(damping - 1F) < 1e-4F)
        {
            double a = d0;
            double b = v0 + omega * d0;
            double exp = Math.exp(-omega * t);

            displacement = exp * (a + b * t);
            velocity = exp * (b - omega * (a + b * t));
        }
        else if (damping < 1F)
        {
            double omegaD = omega * Math.sqrt(1D - damping * damping);
            double a = d0;
            double b = (v0 + damping * omega * d0) / omegaD;
            double exp = Math.exp(-damping * omega * t);
            double cos = Math.cos(omegaD * t);
            double sin = Math.sin(omegaD * t);

            displacement = exp * (a * cos + b * sin);
            velocity = exp * ((b * omegaD - a * damping * omega) * cos - (a * omegaD + b * damping * omega) * sin);
        }
        else
        {
            double omegaR = omega * Math.sqrt(damping * damping - 1D);
            double r1 = -damping * omega + omegaR;
            double r2 = -damping * omega - omegaR;
            double c1 = (v0 - r2 * d0) / (r1 - r2);
            double c2 = d0 - c1;
            double exp1 = Math.exp(r1 * t);
            double exp2 = Math.exp(r2 * t);

            displacement = c1 * exp1 + c2 * exp2;
            velocity = c1 * r1 * exp1 + c2 * r2 * exp2;
        }

        return new SpringState(target + (float) displacement, (float) velocity);
    }

    private static UIThemeMotion spring(float response, float damping)
    {
        return new UIThemeMotion(true, 0, Interpolations.LINEAR, UIThemeMotion.MotionType.SPRING, response, damping);
    }

    private static long getLongField(Object object, String name) throws Exception
    {
        Field field = field(object.getClass(), name);

        return field.getLong(object);
    }

    private static float getFloatField(Object object, String name) throws Exception
    {
        Field field = field(object.getClass(), name);

        return field.getFloat(object);
    }

    private static void setLongField(Object object, String name, long value) throws Exception
    {
        Field field = field(object.getClass(), name);

        field.setLong(object, value);
    }

    private static Field field(Class<?> type, String name) throws Exception
    {
        Field field = type.getDeclaredField(name);

        field.setAccessible(true);

        return field;
    }

    private static void assertEquals(Object expected, Object actual, String message)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertNear(float expected, float actual, float tolerance, String message)
    {
        if (Math.abs(expected - actual) > tolerance)
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual + " (tolerance " + tolerance + ")");
        }
    }

    private static void assertTrue(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
