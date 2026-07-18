package mchorse.bbs_mod.api.client.film;

import java.util.Objects;

public record BBSFilmApplyResult(
    BBSFilmCollaborationStatus status,
    long sessionId,
    long revision,
    int appliedCount,
    long serverSeq,
    String message
)
{
    public BBSFilmApplyResult
    {
        status = Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }

    public boolean applied()
    {
        return this.status == BBSFilmCollaborationStatus.OK;
    }
}
