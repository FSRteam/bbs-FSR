package mchorse.bbs_mod.api.client.film;

/** Server-ordered removal of one remote participant from the local Film overlay. */
public record BBSFilmPresenceClearRequest(
    long sessionId,
    long expectedRevision,
    long serverSeq,
    String participantId
)
{
    public BBSFilmPresenceClearRequest
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(expectedRevision, "expectedRevision");
        BBSFilmCollaborationLimits.requireRevision(serverSeq, "serverSeq");
        participantId = BBSFilmCollaborationLimits.requireText(participantId, 256, "participantId");
    }
}
