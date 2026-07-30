package mchorse.bbs_mod.ui.utils.motion;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;

/**
 * Resolves the interpolation selected by the user for all UI motion entries.
 * The value stores a stable {@link Interpolations#MAP} key so the settings UI can reuse the
 * standard interpolation picker instead of maintaining a second, incomplete curve list.
 */
public final class UIMotionEasings
{
    private UIMotionEasings()
    {}

    public static boolean overridesTheme()
    {
        return BBSSettings.motionEasing != null;
    }

    public static IInterp selected()
    {
        String key = BBSSettings.motionEasing == null ? Interpolations.SINE_OUT.getKey() : BBSSettings.motionEasing.get();

        return Interpolations.MAP.getOrDefault(key, Interpolations.SINE_OUT);
    }

    public static IInterp resolve(IInterp themed)
    {
        return overridesTheme() ? selected() : themed;
    }
}
