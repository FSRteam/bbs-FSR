package mchorse.bbs_mod.api.client.film;

/**
 * Observe one provider semantic sequence that intentionally has no Film
 * mutation to apply, such as this client's own server-ordered broadcast.
 */
public record BBSFilmServerSequenceObserveRequest(
    long sessionId,
    long expectedRevision,
    long serverSeq
)
{
    public BBSFilmServerSequenceObserveRequest
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(expectedRevision, "expectedRevision");
        BBSFilmCollaborationLimits.requireRevision(serverSeq, "serverSeq");
    }
}
