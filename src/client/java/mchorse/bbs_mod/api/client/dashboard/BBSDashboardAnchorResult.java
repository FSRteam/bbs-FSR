package mchorse.bbs_mod.api.client.dashboard;

import java.util.Objects;

/** Screen-space snapshot of one semantic Dashboard control anchor. */
public record BBSDashboardAnchorResult(
    BBSDashboardAnchorStatus status,
    String anchorId,
    int x,
    int y,
    int width,
    int height,
    boolean visible,
    boolean hittable,
    String message
)
{
    public BBSDashboardAnchorResult
    {
        Objects.requireNonNull(status, "status");
        anchorId = anchorId == null ? "" : anchorId;
        width = Math.max(0, width);
        height = Math.max(0, height);
        message = message == null ? "" : message;
    }

    public boolean available()
    {
        return this.status == BBSDashboardAnchorStatus.AVAILABLE;
    }
}
