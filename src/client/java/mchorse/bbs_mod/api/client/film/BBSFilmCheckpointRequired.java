package mchorse.bbs_mod.api.client.film;

import java.util.Objects;

/**
 * Bounded notification that one committed local batch cannot be represented
 * by normal mutations and must be published as a whole-Film checkpoint.
 */
public record BBSFilmCheckpointRequired(
    long sessionId,
    long revision,
    long localOpId,
    BBSFilmCheckpointReason reason
)
{
    public BBSFilmCheckpointRequired
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(revision, "revision");
        BBSFilmCollaborationLimits.requireRevision(localOpId, "localOpId");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
