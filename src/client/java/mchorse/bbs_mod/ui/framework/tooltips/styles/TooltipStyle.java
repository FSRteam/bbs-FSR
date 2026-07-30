package mchorse.bbs_mod.ui.framework.tooltips.styles;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;

public abstract class TooltipStyle
{
    public static final TooltipStyle LIGHT = new LightTooltipStyle();
    public static final TooltipStyle DARK = new DarkTooltipStyle();

    public static TooltipStyle get()
    {
        int style = BBSSettings.tooltipStyle == null ? 0 : BBSSettings.tooltipStyle.get();

        if (style == 1)
        {
            return DARK;
        }

        if (style == 2)
        {
            return LIGHT;
        }

        return BBSSettings.isLightTheme() ? LIGHT : DARK;
    }

    public abstract void renderBackground(UIContext context, Area area);

    public abstract int getTextColor();

    public abstract int getForegroundColor();
}
