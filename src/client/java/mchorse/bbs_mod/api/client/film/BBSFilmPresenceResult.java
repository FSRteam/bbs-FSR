package mchorse.bbs_mod.api.client.film;

import java.util.Objects;

public record BBSFilmPresenceResult(
    BBSFilmCollaborationStatus status,
    long sessionId,
    long revision,
    String participantId,
    long serverSeq,
    String message
)
{
    public BBSFilmPresenceResult
    {
        status = Objects.requireNonNull(status, "status");
        participantId = participantId == null ? "" : participantId;
        message = message == null ? "" : message;
    }

    public boolean accepted()
    {
        return this.status == BBSFilmCollaborationStatus.OK;
    }
}
