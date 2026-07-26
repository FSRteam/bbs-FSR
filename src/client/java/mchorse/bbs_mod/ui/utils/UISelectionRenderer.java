package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.colors.Colors;

public final class UISelectionRenderer
{
    private UISelectionRenderer()
    {}

    public static void renderMarquee(UIContext context, int x1, int y1, int x2, int y2)
    {
        int fill = BBSSettings.selectionFillColor();
        int outline = BBSSettings.selectionOutlineColor();

        if (fill == 0 && outline == 0)
        {
            context.batcher.normalizedBox(x1, y1, x2, y2, BBSSettings.accentOverlay(Colors.A25));

            return;
        }

        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);

        if (left == right || top == bottom)
        {
            return;
        }

        if (fill != 0)
        {
            context.batcher.box(left, top, right, bottom, fill);
        }

        if (outline != 0)
        {
            context.batcher.outline(left, top, right, bottom, outline);
        }
    }
}
