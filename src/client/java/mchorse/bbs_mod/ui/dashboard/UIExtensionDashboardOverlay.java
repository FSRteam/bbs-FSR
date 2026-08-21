package mchorse.bbs_mod.ui.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlayContent;
import mchorse.bbs_mod.client.dashboard.DashboardOverlayContribution;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;

final class UIExtensionDashboardOverlay extends UIElement
{
    private final DashboardOverlayContribution contribution;
    private final BBSDashboardOverlayContent content;
    private boolean opened;
    private boolean mounted;

    UIExtensionDashboardOverlay(UIElement host, DashboardOverlayContribution contribution) throws Exception
    {
        this.contribution = contribution;
        this.content = contribution.factory().create();

        if (this.content == null)
        {
            throw new IllegalStateException("Dashboard overlay factory returned null content");
        }

        UIElement root = this.content.root();

        if (root == null)
        {
            throw new IllegalStateException("Dashboard overlay content returned a null root");
        }
        if (root.hasParent())
        {
            throw new IllegalStateException("Dashboard overlay content root already has a parent");
        }

        this.full(host);
        this.eventPropagataion(EventPropagation.PASS);
        root.full(this);
        root.eventPropagataion(EventPropagation.PASS);
        this.add(root);
        this.setVisible(contribution.visible());
        host.add(this);
        this.mounted = true;
        this.invoke("mounted", this.content::onMounted);

        if (this.isVisible())
        {
            this.invoke("shown", this.content::onShown);
        }
    }

    DashboardOverlayContribution contribution()
    {
        return this.contribution;
    }

    void open()
    {
        if (!this.opened)
        {
            this.opened = true;
            this.invoke("open", this.content::onDashboardOpen);
        }
    }

    void close()
    {
        if (this.opened)
        {
            this.opened = false;
            this.invoke("close", this.content::onDashboardClose);
        }
    }

    void setAddonVisible(boolean visible)
    {
        if (!this.mounted || this.isVisible() == visible)
        {
            return;
        }

        this.setVisible(visible);
        this.invoke(visible ? "shown" : "hidden", visible ? this.content::onShown : this.content::onHidden);
    }

    void unmount()
    {
        if (!this.mounted)
        {
            return;
        }

        this.close();

        if (this.isVisible())
        {
            this.setVisible(false);
            this.invoke("hidden", this.content::onHidden);
        }

        this.invoke("unmounted", this.content::onUnmounted);
        this.mounted = false;
        this.removeFromParent();
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
}
