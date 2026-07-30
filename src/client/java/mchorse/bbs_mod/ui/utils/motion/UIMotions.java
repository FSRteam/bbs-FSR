package mchorse.bbs_mod.ui.utils.motion;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.ui.themes.UITheme;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;

/**
 * Static helpers that combine a theme's motion entries with the user-level
 * switches. Zero allocation: entries come straight off the resolved
 * {@link UITheme}, and durations are computed on demand.
 */
public class UIMotions
{
    private UIMotions()
    {}

    /** User-level switch AND theme-level switch. */
    public static boolean enabled()
    {
        if (BBSSettings.motionEnabled != null && !BBSSettings.motionEnabled.get())
        {
            return false;
        }

        return ThemeManager.current().motionEnabled;
    }

    /**
     * Effective duration of a motion entry in milliseconds:
     * {@code duration / (theme speed x user speed)}. Returns 0 (= snap
     * instantly) when motion is disabled at any level.
     */
    public static int duration(UIThemeMotion spec)
    {
        if (spec == null || !spec.enabled || !enabled())
        {
            return 0;
        }

        float speed = ThemeManager.current().motionSpeed * userSpeed();

        if (speed <= 0F)
        {
            return 0;
        }

        if (spec.type == UIThemeMotion.MotionType.SPRING)
        {
            float response = response(spec);

            return response <= 0F ? 0 : Math.max(1, Math.round(response * 1000F));
        }

        return Math.round(spec.duration / speed);
    }

    /** Effective easing for every motion entry, including spring-authored theme entries. */
    public static IInterp easing(UIThemeMotion spec)
    {
        IInterp themed = spec == null || spec.easing == null ? Interpolations.SINE_OUT : spec.easing;

        return UIMotionEasings.resolve(themed);
    }

    /**
     * Selecting an interpolation is an explicit global override. Spring-authored entries then
     * use their response as an ease duration so the chosen curve visibly affects every theme.
     */
    public static UIThemeMotion.MotionType type(UIThemeMotion spec)
    {
        if (spec == null)
        {
            return UIThemeMotion.MotionType.EASE;
        }

        return UIMotionEasings.overridesTheme() ? UIThemeMotion.MotionType.EASE : spec.type;
    }

    public static float response(UIThemeMotion spec)    {
        if (spec == null || !spec.enabled || !enabled())
        {
            return 0F;
        }

        float speed = ThemeManager.current().motionSpeed * userSpeed();

        return speed <= 0F ? 0F : spec.response / speed;
    }

    private static float userSpeed()
    {
        return BBSSettings.motionSpeed == null ? 1F : BBSSettings.motionSpeed.get();
    }

    public static UIThemeMotion overlay()
    {
        return ThemeManager.current().overlay;
    }

    public static UIThemeMotion panelSwitch()
    {
        return ThemeManager.current().panelSwitch;
    }

    public static UIThemeMotion hover()
    {
        return ThemeManager.current().hover;
    }

    public static UIThemeMotion notification()
    {
        return ThemeManager.current().notification;
    }

    public static UIThemeMotion contextMenu()
    {
        return ThemeManager.current().contextMenu;
    }

    public static UIThemeMotion scrollbar()
    {
        return ThemeManager.current().scrollbar;
    }

    public static UIThemeMotion scrollSmooth()
    {
        return ThemeManager.current().scrollSmooth;
    }

    public static UIThemeMotion hoverScale()
    {
        return ThemeManager.current().hoverScale;
    }

    public static UIThemeMotion press()
    {
        return ThemeManager.current().press;
    }

    public static UIThemeMotion layout()
    {
        return ThemeManager.current().layout;
    }

    public static UIThemeMotion toggle()
    {
        return ThemeManager.current().toggle;
    }

    public static UIThemeMotion dragFollow()
    {
        return ThemeManager.current().dragFollow;
    }

    public static UIThemeMotion taskbarHide()
    {
        return ThemeManager.current().taskbarHide;
    }
}
