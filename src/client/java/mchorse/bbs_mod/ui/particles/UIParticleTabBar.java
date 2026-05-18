package mchorse.bbs_mod.ui.particles;

import mchorse.bbs_mod.l10n.keys.IKey;
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
        Icons.PARTICLE_TAB_QUICK_SETUP,
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
        "quickSetup", "file", "emitter", "motion", "appearance", "time", "events", "curves", "preview"
    };

    private final List<UIIcon> tabs = new ArrayList<>();
    private int currentTab = 1;
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
            tab.activeColor(Colors.ACTIVE);
            tab.tooltip(IKey.constant(TAB_IDS[i]), Direction.BOTTOM);
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
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), Colors.CONTROL_BAR);
        super.render(context);

        // Selected tab bottom highlight bar (2px)
        UIIcon selected = this.tabs.get(this.currentTab);
        context.batcher.box(selected.area.x, this.area.ey() - 2, selected.area.ex(), this.area.ey(), Colors.ACTIVE);
    }
}
