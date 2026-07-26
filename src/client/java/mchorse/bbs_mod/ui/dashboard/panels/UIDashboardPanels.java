package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.events.UIEvent;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

public class UIDashboardPanels extends UIElement
{
    private static final int TASKBAR_HEIGHT = 20;
    private static final int REVEAL_STRIP = 16;
    private static final long REVEAL_HOVER_MS = 350L;

    public List<UIDashboardPanel> panels = new ArrayList<>();
    public UIDashboardPanel panel;

    public UIElement taskBar;
    public UIElement pinned;
    public UIScrollView panelButtons;

    /** Whether the current panel owns its transient screen resources. */
    private boolean panelAppeared;

    private final UITween switchVeil = new UITween();
    private final UITween taskbarHide = new UITween();
    private long taskbarLastPointerOverMs = System.currentTimeMillis();
    private long revealStripEnteredMs;
    private int lastDashboardSlide = -1;

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
        int radius = BBSSettings.cornerWidget();

        if (radius > 0)
        {
            int line = BBSSettings.tabActiveLineColor();
            int fill = BBSSettings.tabActiveGradientColor();

            if (fill == 0)
            {
                fill = BBSSettings.primaryColor(Colors.A50);
            }

            batcher.roundedFrame(area.x, area.y, area.w, area.h, radius, 1F, line, fill);

            return;
        }

        int color = BBSSettings.accentColorRGB();
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
        this.taskBar.noCulling();
        this.taskBar.relative(this).y(1F, -TASKBAR_HEIGHT).w(1F).h(TASKBAR_HEIGHT);
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
                UIIcon button = (UIIcon) this.panelButtons.getChildren().get(i);
                boolean active = this.panel == this.panels.get(i);

                button.active(active);

                if (active && BBSSettings.cornerWidget() <= 0)
                {
                    renderHighlight(context.batcher, button.area, Direction.BOTTOM);
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
        this.resetTaskbarAutoHide();

        for (UIDashboardPanel panel : this.panels)
        {
            panel.open();
        }

        if (this.panel != null && !this.panelAppeared)
        {
            this.setPanelPlacement(this.panel);

            if (!this.panel.hasParent())
            {
                this.prepend(this.panel);
            }

            this.panel.appear();
            this.panelAppeared = true;
            this.panel.resize();
        }
    }

    public void close()
    {
        this.resetTaskbarAutoHide();

        if (this.panel != null && this.panelAppeared)
        {
            this.panel.disappear();
            this.panelAppeared = false;
        }

        for (UIDashboardPanel panel : this.panels)
        {
            panel.close();
        }
    }

    public void setPanel(UIDashboardPanel panel)
    {
        if (this.panel == panel)
        {
            return;
        }

        UIContext context = this.getContext();

        if (context == null)
        {
            this.setPanelNow(panel);
        }
        else
        {
            context.menu.runAfterCapturedMouseRelease(() -> this.setPanelNow(panel));
        }
    }

    private void setPanelNow(UIDashboardPanel panel)
    {
        UIDashboardPanel lastPanel = this.panel;

        if (lastPanel == panel)
        {
            return;
        }

        if (this.panel != null)
        {
            if (this.panelAppeared)
            {
                this.panel.disappear();
                this.panelAppeared = false;
            }

            this.panel.removeFromParent();
        }

        this.panel = panel;

        this.getEvents().emit(new PanelEvent(this, lastPanel, panel));

        if (this.panel != null)
        {
            this.setPanelPlacement(panel);

            this.prepend(this.panel);
            this.panel.appear();
            this.panelAppeared = true;
            this.panel.resize();

            /* Render-only switch veil: a base surface wash fading out over
             * the fresh panel; input goes through from frame one */
            if (lastPanel != null)
            {
                this.switchVeil.snap(1F);
                this.switchVeil.to(0F, UIMotions.panelSwitch());
            }
        }
    }

    private void setPanelPlacement(UIDashboardPanel panel)
    {
        this.setPanelPlacement(panel, Math.max(0, this.lastDashboardSlide));
    }

    private void setPanelPlacement(UIDashboardPanel panel, int slide)
    {
        panel.resetFlex().relative(this).y(0F, slide).w(1F).h(1F, -TASKBAR_HEIGHT);
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

    @Override
    public void render(UIContext context)
    {
        this.updateTaskbarAutoHide(context);
        super.render(context);

        /* Panel switch veil (render-only): fades out over the fresh panel */
        float veil = this.switchVeil.update();

        if (veil > 0F && this.panel != null)
        {
            this.panel.area.render(context.batcher, Colors.setA(BBSSettings.baseSurface(), veil));
        }
    }

    public void resetTaskbarAutoHide()
    {
        this.taskbarLastPointerOverMs = System.currentTimeMillis();
        this.revealStripEnteredMs = 0L;
        this.taskbarHide.snap(0F);
        this.applyTaskbarSlide(0);
    }

    private void updateTaskbarAutoHide(UIContext context)
    {
        if (!BBSSettings.dashboardAutoHideTaskbarEnabled())
        {
            if (this.taskbarHide.getValue() != 0F || this.lastDashboardSlide != 0)
            {
                this.resetTaskbarAutoHide();
            }

            return;
        }

        long now = System.currentTimeMillis();
        float target = this.taskbarHide.getTarget();

        if (UIOverlay.has(context))
        {
            this.taskbarLastPointerOverMs = now;
            this.revealStripEnteredMs = 0L;
            target = 0F;
        }
        else
        {
            boolean overBar = this.taskBar.area.isInside(context.mouseX, context.mouseY);
            boolean overReveal = context.mouseX >= this.area.x && context.mouseX < this.area.ex()
                && context.mouseY >= this.area.ey() - REVEAL_STRIP && context.mouseY < this.area.ey();

            if (overBar)
            {
                this.taskbarLastPointerOverMs = now;
                this.revealStripEnteredMs = 0L;
                target = 0F;
            }
            else if (overReveal)
            {
                this.taskbarLastPointerOverMs = now;

                if (this.revealStripEnteredMs == 0L)
                {
                    this.revealStripEnteredMs = now;
                }

                if (now - this.revealStripEnteredMs >= REVEAL_HOVER_MS)
                {
                    target = 0F;
                }
            }
            else
            {
                this.revealStripEnteredMs = 0L;

                if (now - this.taskbarLastPointerOverMs >= BBSSettings.dashboardTaskbarHideDelayMs())
                {
                    target = 1F;
                }
            }
        }

        this.taskbarHide.to(target, UIMotions.taskbarHide());

        float hidden = MathUtils.clamp(this.taskbarHide.update(), 0F, 1F);

        this.applyTaskbarSlide(Math.round(hidden * Math.max(0, this.area.h)));
    }

    private void applyTaskbarSlide(int slide)
    {
        slide = MathUtils.clamp(slide, 0, Math.max(0, this.area.h));

        if (slide == this.lastDashboardSlide)
        {
            return;
        }

        this.lastDashboardSlide = slide;
        this.taskBar.relative(this).y(1F, -TASKBAR_HEIGHT + slide).w(1F).h(TASKBAR_HEIGHT);

        if (this.panel != null)
        {
            this.setPanelPlacement(this.panel, slide);
        }

        this.resize();
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
