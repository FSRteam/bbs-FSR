package mchorse.bbs_mod.ui.particles;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class UIParticleTabBar extends UIElement
{
    public static final int TAB_HEIGHT = 24;
    public static final Icon[] TAB_ICONS = {
        Icons.PARTICLE_TAB_FILE,
        Icons.PARTICLE_TAB_EMITTER,
        Icons.PARTICLE_TAB_MOTION,
        Icons.PARTICLE_TAB_APPEARANCE,
        Icons.PARTICLE_TAB_TIME,
        Icons.PARTICLE_TAB_EVENTS,
        Icons.PARTICLE_TAB_CURVES,
        Icons.VIDEO_CAMERA
    };

    public static final String[] TAB_IDS = {
        "file", "emitter", "motion", "appearance", "time", "events", "curves", "preview"
    };

    public static final IKey[] TAB_TOOLTIPS = {
        UIKeys.PARTICLE_TAB_FILE,
        UIKeys.PARTICLE_TAB_EMITTER,
        UIKeys.PARTICLE_TAB_MOTION,
        UIKeys.PARTICLE_TAB_APPEARANCE,
        UIKeys.PARTICLE_TAB_TIME,
        UIKeys.PARTICLE_TAB_EVENTS,
        UIKeys.PARTICLE_TAB_CURVES,
        UIKeys.PARTICLE_TAB_PREVIEW
    };

    private final List<UIIcon> tabs = new ArrayList<>();
    private int currentTab = 0;
    private final Consumer<Integer> callback;

    public UIParticleTabBar(Consumer<Integer> callback)
    {
        super();
        this.callback = callback;
        this.row(0).height(TAB_HEIGHT);

        for (int i = 0; i < TAB_ICONS.length; i++)
        {
            final int index = i;
            UIIcon tab = new UIIcon(TAB_ICONS[i], (b) -> this.selectTab(index));
            tab.wh(TAB_HEIGHT, TAB_HEIGHT);
            tab.tooltip(TAB_TOOLTIPS[i], Direction.BOTTOM);
            this.tabs.add(tab);
            this.add(tab);
        }

        this.tabs.get(this.currentTab).active(true);
    }

    public void selectTab(int index)
    {
        if (index < 0 || index >= this.tabs.size())
        {
            return;
        }

        this.tabs.get(this.currentTab).active(false);
        this.currentTab = index;
        this.tabs.get(this.currentTab).active(true);

        if (this.callback != null)
        {
            this.callback.accept(index);
        }
    }

    public int getCurrentTab()
    {
        return this.currentTab;
    }

    public String getCurrentTabId()
    {
        return TAB_IDS[this.currentTab];
    }

    public int getTabIndex(String tabId)
    {
        for (int i = 0; i < TAB_IDS.length; i++)
        {
            if (TAB_IDS[i].equals(tabId))
            {
                return i;
            }
        }
        return -1;
    }

    public void selectTabById(String tabId)
    {
        int index = this.getTabIndex(tabId);
        if (index >= 0)
        {
            this.selectTab(index);
        }
    }

    @Override
    public void render(UIContext context)
    {
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), Colors.A100);

        int y = this.area.y;
        int ey = this.area.ey();
        int hovered = this.area.isInside(context.mouseX, context.mouseY)
            ? (context.mouseX - this.area.x) / Math.max(1, TAB_HEIGHT) : -1;

        for (int i = 0; i < this.tabs.size(); i++)
        {
            UIIcon tab = this.tabs.get(i);
            boolean active = i == this.currentTab;
            boolean hover = i == hovered;

            if (active)
            {
                UIDashboardPanels.renderHighlight(context.batcher, tab.area, Direction.BOTTOM);
            }

            int iconColor = active ? Colors.WHITE : (hover ? Colors.LIGHTEST_GRAY : Colors.mulRGB(Colors.WHITE, 0.75F));
            context.batcher.icon(TAB_ICONS[i], iconColor, tab.area.mx(), tab.area.my(), 0.5F, 0.5F);

            if (hover && tab.tooltip != null)
            {
                context.tooltip.set(context, tab);
            }
        }
    }
}
