package mchorse.bbs_mod.ui.framework.elements.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;

import java.util.List;

/**
 * The column of category icons next to a timeline. When the editor gets too short for a single stack,
 * the buttons wrap into enough columns to stay above the pinned bottom toggles.
 */
public class UITimelineCategoryBar extends UIElement
{
    public static final int BUTTON_SIZE = 20;

    /** Height reserved at the bottom of the bar by the owner's pinned toggles. */
    private final int bottomSpace;

    public UITimelineCategoryBar(int bottomSpace)
    {
        this.bottomSpace = bottomSpace;
        this.w(BUTTON_SIZE).h(1F);
    }

    /** Width needed to keep every category reachable above the pinned actions. */
    public int getWidthForHeight(int height)
    {
        int rows = Math.max(1, (height - this.bottomSpace) / BUTTON_SIZE);
        int count = this.getChildren(UIIcon.class).size();
        int columns = Math.max(1, (count + rows - 1) / rows);

        return columns * BUTTON_SIZE;
    }

    @Override
    protected void afterResizeApplied()
    {
        List<UIIcon> buttons = this.getChildren(UIIcon.class);
        int columns = Math.max(1, this.area.w / BUTTON_SIZE);

        for (int i = 0; i < buttons.size(); i++)
        {
            buttons.get(i)
                .relative(this)
                .x((i % columns) * BUTTON_SIZE)
                .y((i / columns) * BUTTON_SIZE)
                .wh(BUTTON_SIZE, BUTTON_SIZE);
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.chromeSurface());

        super.render(context);
    }
}
