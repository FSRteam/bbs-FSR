package mchorse.bbs_mod.ui.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelContent;
import mchorse.bbs_mod.client.dashboard.DashboardPanelContribution;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.function.BooleanSupplier;

final class UIExtensionDashboardPanel extends UIDashboardPanel
{
    private final DashboardPanelContribution contribution;
    private final BBSDashboardPanelContent content;

    UIExtensionDashboardPanel(UIDashboard dashboard, DashboardPanelContribution contribution) throws Exception
    {
        super(dashboard);

        this.contribution = contribution;
        this.content = contribution.factory().create();

        if (this.content == null)
        {
            throw new IllegalStateException("Dashboard panel factory returned null content");
        }

        UIElement root = this.content.root();

        if (root == null)
        {
            throw new IllegalStateException("Dashboard panel content returned a null root");
        }
        if (root.hasParent())
        {
            throw new IllegalStateException("Dashboard panel content root already has a parent");
        }

        root.full(this);
        this.add(root);
    }

    DashboardPanelContribution contribution()
    {
        return this.contribution;
    }

    @Override
    public boolean needsBackground()
    {
        return this.query("needs-background", this.content::needsBackground, true);
    }

    @Override
    public boolean canToggleVisibility()
    {
        return this.query("toggle-visibility", this.content::canToggleVisibility, true);
    }

    @Override
    public boolean canPause()
    {
        return this.query("pause", this.content::canPause, true);
    }

    @Override
    public boolean canRefresh()
    {
        return this.query("refresh", this.content::canRefresh, true);
    }

    @Override
    public void appear()
    {
        this.invoke("appear", this.content::onAppear);
    }

    @Override
    public void disappear()
    {
        this.invoke("disappear", this.content::onDisappear);
    }

    @Override
    public void open()
    {
        this.invoke("open", this.content::onOpen);
    }

    @Override
    public void close()
    {
        this.invoke("close", this.content::onClose);
    }

    @Override
    public void update()
    {
        this.invoke("update", this.content::onUpdate);
    }

    private void invoke(String phase, Runnable callback)
    {
        try
        {
            callback.run();
        }
        catch (Throwable error)
        {
            this.contribution.reportFailure(phase, error);
        }
    }

    private boolean query(String phase, BooleanSupplier callback, boolean fallback)
    {
        try
        {
            return callback.getAsBoolean();
        }
        catch (Throwable error)
        {
            this.contribution.reportFailure(phase, error);
            return fallback;
        }
    }
}
