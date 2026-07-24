package mchorse.bbs_mod.ui.utils.motion;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.ui.themes.UITheme;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;

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

        return speed <= 0F ? 0 : Math.round(spec.duration / speed);
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
}
