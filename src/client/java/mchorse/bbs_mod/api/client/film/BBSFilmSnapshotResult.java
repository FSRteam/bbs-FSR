package mchorse.bbs_mod.api.client.film;

import java.util.Objects;

public record BBSFilmSnapshotResult(
    BBSFilmCollaborationStatus status,
    BBSFilmSnapshot snapshot,
    String message
)
{
    public BBSFilmSnapshotResult
    {
        status = Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }

    public boolean successful()
    {
        return this.status == BBSFilmCollaborationStatus.OK && this.snapshot != null;
    }
}
