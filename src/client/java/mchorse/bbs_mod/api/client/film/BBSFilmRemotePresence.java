package mchorse.bbs_mod.api.client.film;

import java.util.Objects;

/** One remote participant presence targeted at the receiver's local Film session/revision. */
public record BBSFilmRemotePresence(
    String participantId,
    String displayName,
    int argbColor,
    long serverSeq,
    BBSFilmPresence presence
)
{
    private static final int MAX_ID_UTF8_BYTES = 256;
    private static final int MAX_NAME_UTF8_BYTES = 256;

    public BBSFilmRemotePresence
    {
        participantId = BBSFilmCollaborationLimits.requireText(participantId, MAX_ID_UTF8_BYTES, "participantId");
        displayName = BBSFilmCollaborationLimits.requireText(displayName, MAX_NAME_UTF8_BYTES, "displayName");
        BBSFilmCollaborationLimits.requireRevision(serverSeq, "serverSeq");
        presence = Objects.requireNonNull(presence, "presence");
    }
}
