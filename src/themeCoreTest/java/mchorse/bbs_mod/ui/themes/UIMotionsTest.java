package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.interps.Interpolations;

/**
 * Executable regressions for the motion layer basics: snap semantics,
 * disabled short-circuit, duration composition and tween settling.
 * Runs without Minecraft; ThemeManager falls back to code-level dark
 * defaults when no asset provider is present.
 */
public final class UIMotionsTest
{
    private UIMotionsTest()
    {}

    public static void main(String[] args)
    {
        testDurationComposition();
        testSnapAndDisabled();
        testTweenProgression();

        System.out.println("UIMotionsTest: all tests passed");
    }

    private static void testDurationComposition()
    {
        UIThemeMotion enabled = new UIThemeMotion(true, 100, Interpolations.SINE_OUT);
        UIThemeMotion disabled = new UIThemeMotion(false, 100, Interpolations.SINE_OUT);

        /* No settings registered in this environment: user switches default
         * to enabled/1x, the theme is the code-level dark default */
        assertTrue(UIMotions.enabled(), "motion enabled by default");
        assertEquals(100, UIMotions.duration(enabled), "1x speed keeps duration");
        assertEquals(0, UIMotions.duration(disabled), "disabled entry has zero duration");
        assertEquals(0, UIMotions.duration(null), "null entry has zero duration");

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

    private static void assertEquals(Object expected, Object actual, String message)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
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
