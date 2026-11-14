package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.landing.ILandingHost;
import mchorse.bbs_mod.ui.dashboard.panels.landing.UILandingScreen;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabsHost;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UITabList;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.dashboard.panels.bar.UIPanelActionBar;
import mchorse.bbs_mod.ui.dashboard.panels.bar.UIPanelTopBar;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.utils.MathUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Shared editor frame: a measured top bar and content below its current visible edge. */
public abstract class UIEditorDashboardPanel extends UIDashboardPanel implements IUITabsHost, ILandingHost
{
    public final UIPanelTopBar topBar;
    public final UIElement editor;
    public final UITabList documentTabs;
    public UILandingScreen landing;

    protected float chromeHiddenAmount;
    protected boolean update;
    private final List<UIElement> contentLayers = new ArrayList<>();

    public UIEditorDashboardPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.onOpen(this::onPanelOpened);
        this.onAppear(this::onPanelAppeared);

        this.documentTabs = new UITabList(this)
        {
            @Override
            public void sync()
            {
                super.sync();
                UIEditorDashboardPanel.this.onTabsChanged();
            }
        };
        this.topBar = new UIPanelTopBar();
        this.topBar.relative(this).w(1F).h(UIPanelTopBar.HEIGHT);
        this.editor = this.layoutUnderTopBar(new UIElement());

        this.add(new UIRenderable(this::renderBackground), this.topBar, this.editor);
    }

    public UIPanelActionBar actions()
    {
        return this.topBar.actions;
    }

    protected void onTabsChanged()
    {}

    @Override
    public final void openTab(String id)
    {
        this.onTabsChanged();
        this.showTab(id);
        this.syncLanding();
    }

    protected abstract void showTab(String id);

    @Override
    public IKey getNewTabLabel()
    {
        return UIKeys.PANELS_TABS_NEW_TAB;
    }

    @Override
    public void pickData(String id)
    {
        this.documentTabs.pick(id);
    }

    protected <T extends UIElement> T layoutUnderTopBar(T element)
    {
        if (!this.contentLayers.contains(element))
        {
            this.contentLayers.add(element);
        }

        int top = UIPanelTopBar.HEIGHT - Math.round(UIPanelTopBar.HEIGHT * this.chromeHiddenAmount);

        element.relative(this).y(top).w(1F).h(1F, -top);

        return element;
    }

    @Override
    public void setDashboardChromeHiddenAmount(float amount)
    {
        this.chromeHiddenAmount = MathUtils.clamp(amount, 0F, 1F);
        this.topBar.y(-Math.round(UIPanelTopBar.HEIGHT * this.chromeHiddenAmount));

        for (UIElement element : this.contentLayers)
        {
            this.layoutUnderTopBar(element);
        }
    }

    @Override
    public boolean isDashboardChromeHovered(int mouseX, int mouseY)
    {
        return this.topBar.area.isInside(mouseX, mouseY);
    }

    private void onPanelOpened()
    {
        this.update = true;
    }

    private void onPanelAppeared()
    {
        if (this.update || (this.landing != null && this.landing.isVisible()))
        {
            this.update = false;
            this.requestNames();
        }
    }

    /** Mount after the document and manager owners have been initialized. */
    protected void mountLanding()
    {
        this.landing = new UILandingScreen(this);
        this.add(this.layoutUnderTopBar(this.landing));
        this.syncLanding();
    }

    public void syncLanding()
    {
        if (this.landing != null)
        {
            this.landing.setVisible(this.getLandingId() == null);
        }
    }

    protected abstract String getLandingId();

    @Override
    public Icon getTabIcon(String id)
    {
        return id == null ? Icons.SEARCH : Icons.FOLDER;
    }

    public void fillNames(Collection<String> names)
    {
        if (this.landing != null)
        {
            this.landing.fillNames(names);
        }
    }

    public abstract void requestNames();

    protected void renderBackground(UIContext context)
    {}
}
