package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/** Compatibility name for integrations compiled against the earlier sidebar editor frame. */
public abstract class UISidebarDashboardPanel extends UIEditorDashboardPanel
{
    public final UIElement iconBar;

    public UISidebarDashboardPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.iconBar = this.actions();
    }
}
