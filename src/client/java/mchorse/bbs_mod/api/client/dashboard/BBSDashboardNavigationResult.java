package mchorse.bbs_mod.api.client.dashboard;

import java.util.Objects;

/** Immutable result completed after the requested panel switch has taken effect. */
public record BBSDashboardNavigationResult(
    BBSDashboardNavigationStatus status,
    String panelId,
    String message
)
{
    public BBSDashboardNavigationResult
    {
        Objects.requireNonNull(status, "status");
        panelId = panelId == null ? "" : panelId;
        message = message == null ? "" : message;
    }

    public boolean navigated()
    {
        return this.status == BBSDashboardNavigationStatus.NAVIGATED
            || this.status == BBSDashboardNavigationStatus.ALREADY_ACTIVE;
    }
}
