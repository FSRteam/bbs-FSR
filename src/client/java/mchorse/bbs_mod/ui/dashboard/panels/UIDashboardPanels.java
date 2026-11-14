package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.events.UIEvent;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.framework.tooltips.LabelTooltip;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
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
    public static final Direction[] SIDES = {Direction.BOTTOM, Direction.TOP, Direction.LEFT, Direction.RIGHT};
    private static final int REVEAL_STRIP = 16;
    private static final long REVEAL_HOVER_MS = 350L;

    public List<UIDashboardPanel> panels = new ArrayList<>();
    public UIDashboardPanel panel;

    public UIElement taskBar;
    public UIElement pinned;
    public UIScrollView panelButtons;

    /** Whether the current panel owns its transient screen resources. */
    private boolean panelAppeared;
    private boolean opened;

    private final UITween switchVeil = new UITween();
    private final UITween taskbarHide = new UITween();
    private long taskbarLastPointerOverMs = System.currentTimeMillis();
    private long revealStripEnteredMs;
    private int lastDashboardSlide = -1;
    private Direction side = Direction.BOTTOM;

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
        batcher.highlight(area, direction);
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

        this.taskBar.add(new UIRenderable(this::renderBackground), this.pinned, this.panelButtons);
        this.taskBar.context(this::fillSideMenu);
        this.add(this.taskBar);
    }

    public static Direction getSettingsSide()
    {
        int index = BBSSettings.taskbarSide == null ? 0 : BBSSettings.taskbarSide.get();

        return SIDES[MathUtils.clamp(index, 0, SIDES.length - 1)];
    }

    public static IKey[] getSideLabels()
    {
        return new IKey[] {UIKeys.DASHBOARD_TASKBAR_SIDE_BOTTOM, UIKeys.DASHBOARD_TASKBAR_SIDE_TOP,
            UIKeys.DASHBOARD_TASKBAR_SIDE_LEFT, UIKeys.DASHBOARD_TASKBAR_SIDE_RIGHT};
    }

    public Direction getSide()
    {
        return this.side;
    }

    public void setSide(Direction side)
    {
        if (this.side == side)
        {
            return;
        }

        this.side = side;
        this.taskBar.resetFlex().relative(this);
        this.pinned.resetFlex().relative(this.taskBar);
        this.panelButtons.resetFlex().relative(this.pinned);

        if (side.isVertical())
        {
            this.pinned.h(TASKBAR_HEIGHT).row(0).resize();
            this.panelButtons.x(1F, 5).h(TASKBAR_HEIGHT).wTo(this.taskBar.area, 1F).row(0).scroll();
            this.panelButtons.scroll.direction = ScrollDirection.HORIZONTAL;
        }
        else
        {
            this.pinned.w(TASKBAR_HEIGHT).column(0).vertical();
            this.panelButtons.y(1F, 5).w(TASKBAR_HEIGHT).hTo(this.taskBar.area, 1F).column(0).vertical().scroll();
            this.panelButtons.scroll.direction = ScrollDirection.VERTICAL;
        }

        for (UIIcon icon : this.taskBar.getChildren(UIIcon.class))
        {
            if (icon.tooltip instanceof LabelTooltip tooltip)
            {
                tooltip.direction = side.opposite();
            }
        }

        for (UIIcon icon : this.panelButtons.getChildren(UIIcon.class))
        {
            icon.highlight(() -> icon.isActive() && BBSSettings.cornerWidget() <= 0, side);
        }

        this.panelButtons.scroll.setScroll(0);
        int slide = Math.max(0, this.lastDashboardSlide);

        this.lastDashboardSlide = -1;
        this.applyTaskbarSlide(slide);
    }

    private void fillSideMenu(ContextMenuManager menu)
    {
        IKey[] labels = getSideLabels();

        for (int i = 0; i < SIDES.length; i++)
        {
            int index = i;
            Direction side = SIDES[i];

            menu.action(switch (side)
            {
                case TOP -> Icons.ARROW_UP;
                case LEFT -> Icons.ARROW_LEFT;
                case RIGHT -> Icons.ARROW_RIGHT;
                default -> Icons.ARROW_DOWN;
            }, labels[i], this.side == side, () -> BBSSettings.taskbarSide.set(index));
        }
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
        this.opened = true;

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

        this.opened = false;
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

        if (this.panel != null && this.opened)
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
        int slide = MathUtils.clamp(Math.max(0, this.lastDashboardSlide), 0, TASKBAR_HEIGHT);

        this.setPanelPlacement(panel, TASKBAR_HEIGHT - slide);
        panel.setDashboardChromeHiddenAmount(slide / (float) TASKBAR_HEIGHT);
    }

    private void setPanelPlacement(UIDashboardPanel panel, int inset)
    {
        panel.resetFlex().relative(this).w(1F).h(1F);

        switch (this.side)
        {
            case TOP -> panel.y(inset).h(1F, -inset);
            case LEFT -> panel.x(inset).w(1F, -inset);
            case RIGHT -> panel.w(1F, -inset);
            default -> panel.h(1F, -inset);
        }
    }

    public UIIcon registerPanel(UIDashboardPanel panel, IKey tooltip, Icon icon)
    {
        return this.registerPanel(this.panels.size(), panel, tooltip, icon);
    }

    public UIIcon replacePanel(UIDashboardPanel current, UIDashboardPanel replacement, IKey tooltip, Icon icon)
    {
        int index = this.panels.indexOf(current);

        if (index < 0)
        {
            return this.registerPanel(replacement, tooltip, icon);
        }

        UIIcon oldButton = (UIIcon) this.panelButtons.getChildren().get(index);
        UIIcon newButton = this.createPanelButton(replacement, tooltip, icon);
        boolean selected = this.panel == current;

        this.panelButtons.addBefore(oldButton, newButton);
        this.panels.set(index, replacement);

        if (selected)
        {
            if (this.panelAppeared)
            {
                current.disappear();
                this.panelAppeared = false;
            }

            current.removeFromParent();

            if (this.opened)
            {
                current.close();
                replacement.open();
            }

            this.panel = replacement;
            this.getEvents().emit(new PanelEvent(this, current, replacement));

            if (this.opened)
            {
                this.setPanelPlacement(replacement);
                this.prepend(replacement);
                replacement.appear();
                this.panelAppeared = true;
                replacement.resize();
            }
        }
        else
        {
            current.removeFromParent();

            if (this.opened)
            {
                current.close();
                replacement.open();
            }
        }

        oldButton.removeFromParent();

        return newButton;
    }

    public boolean removePanel(UIDashboardPanel removed, UIDashboardPanel preferredFallback)
    {
        int index = this.panels.indexOf(removed);

        if (index < 0)
        {
            return false;
        }

        UIIcon button = (UIIcon) this.panelButtons.getChildren().get(index);
        boolean selected = this.panel == removed;
        UIDashboardPanel fallback = this.findFallback(removed, preferredFallback);

        if (selected)
        {
            if (this.panelAppeared)
            {
                removed.disappear();
                this.panelAppeared = false;
            }

            removed.removeFromParent();

            if (this.opened)
            {
                removed.close();
            }

            this.panel = fallback;
            this.getEvents().emit(new PanelEvent(this, removed, fallback));

            if (fallback != null && this.opened)
            {
                this.setPanelPlacement(fallback);
                this.prepend(fallback);
                fallback.appear();
                this.panelAppeared = true;
                fallback.resize();
            }
        }
        else
        {
            removed.removeFromParent();

            if (this.opened)
            {
                removed.close();
            }
        }

        this.panels.remove(index);
        button.removeFromParent();

        return true;
    }

    private UIIcon registerPanel(int index, UIDashboardPanel panel, IKey tooltip, Icon icon)
    {
        UIIcon button = this.createPanelButton(panel, tooltip, icon);

        if (index >= this.panels.size())
        {
            this.panels.add(panel);
            this.panelButtons.add(button);
        }
        else
        {
            UIIcon next = (UIIcon) this.panelButtons.getChildren().get(index);

            this.panels.add(index, panel);
            this.panelButtons.addBefore(next, button);
        }

        if (this.opened)
        {
            panel.open();
        }

        return button;
    }

    private UIIcon createPanelButton(UIDashboardPanel panel, IKey tooltip, Icon icon)
    {
        UIIcon button = new UIIcon(icon, (b) -> this.setPanel(panel))
        {
            @Override
            public void render(UIContext context)
            {
                this.active(UIDashboardPanels.this.panel == panel);
                super.render(context);
            }
        };

        button.highlight(() -> this.panel == panel && BBSSettings.cornerWidget() <= 0, this.side);
        button.tooltip(tooltip, this.side.opposite());

        return button;
    }

    private UIDashboardPanel findFallback(UIDashboardPanel removed, UIDashboardPanel preferred)
    {
        if (preferred != null && preferred != removed && this.panels.contains(preferred))
        {
            return preferred;
        }

        for (UIDashboardPanel panel : this.panels)
        {
            if (panel != removed)
            {
                return panel;
            }
        }

        return null;
    }

    protected void renderBackground(UIContext context)
    {
        Area area = this.taskBar.area;
        Area a = this.pinned.area;

        context.batcher.box(area.x, area.y, area.ex(), area.ey(), BBSSettings.chromeSurface());
        if (this.side.isVertical())
        {
            context.batcher.box(a.ex() + 2, a.y + 3, a.ex() + 3, a.ey() - 3, BBSSettings.color(BBSSettings.dividerColor(), Colors.A50));
        }
        else
        {
            context.batcher.box(a.x + 3, a.ey() + 2, a.ex() - 3, a.ey() + 3, BBSSettings.color(BBSSettings.dividerColor(), Colors.A50));
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.setSide(getSettingsSide());
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
            boolean overBar = this.taskBar.area.isInside(context.mouseX, context.mouseY)
                || (this.panel != null && this.panel.isDashboardChromeHovered(context.mouseX, context.mouseY));
            boolean insideX = context.mouseX >= this.area.x && context.mouseX < this.area.ex();
            boolean overTopReveal = insideX && context.mouseY >= this.area.y
                && context.mouseY < this.area.y + REVEAL_STRIP;
            boolean overBottomReveal = insideX && context.mouseY >= this.area.ey() - REVEAL_STRIP
                && context.mouseY < this.area.ey();
            boolean insideY = context.mouseY >= this.area.y && context.mouseY < this.area.ey();
            boolean overSideReveal = insideY && switch (this.side)
            {
                case LEFT -> context.mouseX >= this.area.x && context.mouseX < this.area.x + REVEAL_STRIP;
                case RIGHT -> context.mouseX >= this.area.ex() - REVEAL_STRIP && context.mouseX < this.area.ex();
                case TOP -> overTopReveal;
                default -> overBottomReveal;
            };
            boolean overReveal = overTopReveal || overSideReveal;

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

        this.applyTaskbarSlide(Math.round(hidden * TASKBAR_HEIGHT));
    }

    private void applyTaskbarSlide(int slide)
    {
        slide = MathUtils.clamp(slide, 0, TASKBAR_HEIGHT);

        if (slide == this.lastDashboardSlide)
        {
            return;
        }

        this.lastDashboardSlide = slide;
        this.taskBar.resetFlex().relative(this);

        switch (this.side)
        {
            case TOP -> this.taskBar.y(-slide).w(1F).h(TASKBAR_HEIGHT);
            case LEFT -> this.taskBar.x(-slide).w(TASKBAR_HEIGHT).h(1F);
            case RIGHT -> this.taskBar.x(1F, -TASKBAR_HEIGHT + slide).w(TASKBAR_HEIGHT).h(1F);
            default -> this.taskBar.y(1F, -TASKBAR_HEIGHT + slide).w(1F).h(TASKBAR_HEIGHT);
        }

        if (this.panel != null)
        {
            this.setPanelPlacement(this.panel, TASKBAR_HEIGHT - slide);
            this.panel.setDashboardChromeHiddenAmount(slide / (float) TASKBAR_HEIGHT);
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
