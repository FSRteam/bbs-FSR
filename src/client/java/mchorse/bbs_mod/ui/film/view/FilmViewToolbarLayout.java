package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/** Keeps preview buttons at their normal size when a dock becomes too narrow for one row. */
public final class FilmViewToolbarLayout
{
    public static void fit(UIElement preview, UIElement icons, UIElement tools)
    {
        icons.relative(preview).x(0.5F).y(1F).anchor(0.5F, 1F);
        tools.relative(preview).x(1F, -4).y(4).anchor(1F, 0F);
        fitRow(icons, preview.area.w);
        fitRow(tools, preview.area.w - 8);

        if (icons.getFlex().getH() + tools.getFlex().getH() + 4 <= preview.area.h)
        {
            return;
        }

        int buttonWidth = 0;
        int buttonHeight = 0;

        for (UIElement toolbar : new UIElement[] {icons, tools})
        {
            for (IUIElement child : toolbar.getChildren())
            {
                if (child instanceof UIElement element)
                {
                    buttonWidth = Math.max(buttonWidth, element.getFlex().getW());
                    buttonHeight = Math.max(buttonHeight, element.getFlex().getH());
                }
            }
        }

        if (buttonWidth <= 0 || buttonHeight <= 0)
        {
            return;
        }

        int columns = Math.max(1, preview.area.w / buttonWidth);
        int toolCount = tools.getChildren().size();
        int toolColumns = Math.max(1, Math.min(columns, toolCount));
        int iconColumns = columns - toolCount % columns;
        int packedWidth = columns * buttonWidth;

        /* Share the unused cells of the tools' last row without reparenting any buttons. */
        tools.w(toolColumns * buttonWidth).grid(0).items(toolColumns);
        icons.w(iconColumns * buttonWidth).grid(0).items(iconColumns);

        int packedHeight = toolCount / columns * buttonHeight + icons.getFlex().getH();

        tools.x(0.5F, -packedWidth / 2).y(1F, -packedHeight).anchorX(0F);
        icons.x(0.5F, packedWidth / 2).anchorX(1F);
    }

    private static void fitRow(UIElement toolbar, int availableWidth)
    {
        int buttonWidth = 0;
        int count = 0;

        for (IUIElement child : toolbar.getChildren())
        {
            if (child instanceof UIElement element)
            {
                buttonWidth = Math.max(buttonWidth, element.getFlex().getW());
                count++;
            }
        }

        if (buttonWidth <= 0 || count == 0)
        {
            return;
        }

        int columns = Math.max(1, Math.min(count, availableWidth / buttonWidth));

        if (columns == count)
        {
            toolbar.w(0).row(0).resize();
        }
        else
        {
            /* Fixed column count computes the new height before bottom anchoring, in one resize. */
            toolbar.w(columns * buttonWidth).grid(0).items(columns);
        }
    }

    private FilmViewToolbarLayout()
    {}
}
