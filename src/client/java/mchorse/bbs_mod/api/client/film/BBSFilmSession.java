package mchorse.bbs_mod.api.client.film;

import java.util.Objects;

/**
 * Opaque identity and local CAS revision of the Film instance currently shown
 * by FSR. This revision is not a server collaboration revision or watermark.
 */
public record BBSFilmSession(long sessionId, String documentId, long revision)
{
    public BBSFilmSession
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);

        documentId = Objects.requireNonNull(documentId, "documentId");

        BBSFilmCollaborationLimits.requireRevision(revision, "revision");
    }
}
