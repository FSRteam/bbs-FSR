package mchorse.bbs_mod.ui.dashboard.panels.bar;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabs;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UIDataTabs;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/** Shares the top edge between document tabs and editor actions. */
public class UIPanelTopBar extends UIElement
{
    public static final int HEIGHT = UIDataTabs.TABS_HEIGHT_PX;

    public final UIPanelActionBar actions = new UIPanelActionBar();
    private UIDataTabs tabs;
    private int tabsRightInset;

    public UIPanelTopBar()
    {
        this.actions.relative(this).x(1F).h(HEIGHT).anchorX(1F);
        this.add(this.actions);
    }

    public UIDataTabs enableTabs(IUITabs host)
    {
        if (this.tabs == null)
        {
            this.tabs = new UIDataTabs(host);
            this.tabs.full(this);
            this.addBefore(this.actions, this.tabs);
        }

        return this.tabs;
    }

    public void setTabsRightInset(int inset)
    {
        this.tabsRightInset = inset;
    }

    @Override
    protected void afterResizeApplied()
    {
        int width = this.actions.getContentWidth();

        this.actions.w(width);

        if (this.tabs != null)
        {
            this.tabs.setRightInsetPx(width + this.tabsRightInset);
        }

        super.afterResizeApplied();
    }

    @Override
    public void render(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.chromeSurface());
        super.render(context);
    }
}
