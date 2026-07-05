package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.events.UIEvent;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

public class UIDashboardPanels extends UIElement
{
    public List<UIDashboardPanel> panels = new ArrayList<>();
    public UIDashboardPanel panel;

    public UIElement taskBar;
    public UIElement pinned;
    public UIScrollView panelButtons;

    /**
     * @deprecated Kept for backward compatibility. Use {@link #renderHighlight(Batcher2D, Area, Direction)}
     * with {@link Direction#BOTTOM}.
     */
    @Deprecated
    public static void renderHighlight(Batcher2D batcher, Area area)
    {
        renderHighlight(batcher, area, Direction.BOTTOM);
    }

    /**
     * @deprecated Kept for backward compatibility. Use {@link #renderHighlight(Batcher2D, Area, Direction)}
     * with {@link Direction#RIGHT}.
     */
    @Deprecated
    public static void renderHighlightHorizontal(Batcher2D batcher, Area area)
    {
        renderHighlight(batcher, area, Direction.RIGHT);
    }

    /**
     * Render a selection highlight on one edge of the area: a solid color bar on the {@code direction}
     * side, fading into a gradient towards the opposite edge.
     */
    public static void renderHighlight(Batcher2D batcher, Area area, Direction direction)
    {
        int color = BBSSettings.primaryColor.get();
        int bar = Colors.A100 | color;
        int near = Colors.A75 | color;
        int far = color;
        int t = 2;

        switch (direction)
        {
            case TOP:
                batcher.box(area.x, area.y, area.ex(), area.y + t, bar);
                batcher.gradientVBox(area.x, area.y + t, area.ex(), area.ey(), near, far);
                break;
            case BOTTOM:
                batcher.box(area.x, area.ey() - t, area.ex(), area.ey(), bar);
                batcher.gradientVBox(area.x, area.y, area.ex(), area.ey() - t, far, near);
                break;
            case LEFT:
                batcher.box(area.x, area.y, area.x + t, area.ey(), bar);
                batcher.gradientHBox(area.x + t, area.y, area.ex(), area.ey(), near, far);
                break;
            case RIGHT:
                batcher.box(area.ex() - t, area.y, area.ex(), area.ey(), bar);
                batcher.gradientHBox(area.x, area.y, area.ex() - t, area.ey(), far, near);
                break;
        }
    }

    public UIDashboardPanels()
    {
        this.taskBar = new UIElement();
        this.taskBar.relative(this).y(1F, -20).w(1F).h(20);
        this.pinned = new UIElement();
        this.pinned.relative(this.taskBar).h(20).row(0).resize();
        this.panelButtons = new UIScrollView(ScrollDirection.HORIZONTAL);
        this.panelButtons.relative(this.pinned).x(1F, 5).h(20).wTo(this.taskBar.area, 1F).column(0).scroll();
        this.panelButtons.scroll.cancelScrolling().noScrollbar();
        this.panelButtons.scroll.scrollSpeed = 5;
        this.panelButtons.preRender((context) ->
        {
            for (int i = 0, c = this.panels.size(); i < c; i++)
            {
                if (this.panel == this.panels.get(i))
                {
                    renderHighlight(context.batcher, ((UIIcon) this.panelButtons.getChildren().get(i)).area, Direction.BOTTOM);
                }
            }
        });

        this.taskBar.add(new UIRenderable(this::renderBackground), this.pinned, this.panelButtons);
        this.add(this.taskBar);
    }

    public <T> T getPanel(Class<T> clazz)
    {
        for (UIDashboardPanel panel : this.panels)
        {
            if (panel.getClass() == clazz)
            {
                return (T) panel;
            }
        }

        return null;
    }

    public boolean isFlightSupported()
    {
        return this.panel instanceof IFlightSupported;
    }

    public void open()
    {
        for (UIDashboardPanel panel : this.panels)
        {
            panel.open();
        }
    }

    public void close()
    {
        for (UIDashboardPanel panel : this.panels)
        {
            panel.close();
        }
    }

    public void setPanel(UIDashboardPanel panel)
    {
        UIDashboardPanel lastPanel = this.panel;

        if (lastPanel == panel)
        {
            return;
        }

        if (this.panel != null)
        {
            this.panel.disappear();
            this.panel.removeFromParent();
        }

        this.panel = panel;

        this.getEvents().emit(new PanelEvent(this, lastPanel, panel));

        if (this.panel != null)
        {
            this.setPanelPlacement(panel);

            this.prepend(this.panel);
            this.panel.appear();
            this.panel.resize();
        }
    }

    private void setPanelPlacement(UIDashboardPanel panel)
    {
        panel.resetFlex().relative(this).w(1F).h(1F, -20);
    }

    public UIIcon registerPanel(UIDashboardPanel panel, IKey tooltip, Icon icon)
    {
        UIIcon button = new UIIcon(icon, (b) -> this.setPanel(panel));

        button.tooltip(tooltip, Direction.TOP);

        this.panels.add(panel);
        this.panelButtons.add(button);

        return button;
    }

    protected void renderBackground(UIContext context)
    {
        Area area = this.taskBar.area;
        Area a = this.pinned.area;

        context.batcher.box(area.x, area.y, area.ex(), area.ey(), BBSSettings.chromeSurface());
        context.batcher.box(a.ex() + 2, a.y + 3, a.ex() + 3, a.ey() - 3, BBSSettings.color(BBSSettings.dividerColor(), Colors.A50));
    }

    public static class PanelEvent extends UIEvent<UIDashboardPanels>
    {
        public final UIDashboardPanel lastPanel;
        public final UIDashboardPanel panel;

        public PanelEvent(UIDashboardPanels element, UIDashboardPanel lastPanel, UIDashboardPanel panel)
        {
            super(element);

            this.lastPanel = lastPanel;
            this.panel = panel;
        }
    }
}
