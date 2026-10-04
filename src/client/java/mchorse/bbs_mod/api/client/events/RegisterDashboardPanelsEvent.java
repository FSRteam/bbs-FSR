package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;

/**
 * Posted on the client once a dashboard has been built — both the editor's and the one a film is
 * played in, since each builds its own.
 *
 * <p>The dashboard is the finished object rather than a registry: what an addon does here is drop
 * a panel into a place that exists, and the panel must survive the dashboard being rebuilt from
 * scratch the next time one is opened. Look at the dashboard's own panels to decide where.</p>
 */
public class RegisterDashboardPanelsEvent
{
    public final UIDashboard dashboard;

    public RegisterDashboardPanelsEvent(UIDashboard dashboard)
    {
        this.dashboard = dashboard;
    }
}
