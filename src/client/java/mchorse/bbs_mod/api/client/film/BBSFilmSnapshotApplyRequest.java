package mchorse.bbs_mod.api.client.film;

/**
 * A server-approved full snapshot targeted at the receiver's local session.
 * {@code expectedRevision} is the receiver's current core CAS revision, not a
 * server watermark. A successful snapshot apply advances it exactly once.
 */
public record BBSFilmSnapshotApplyRequest(
    long sessionId,
    long expectedRevision,
    long serverSeq,
    byte[] encodedBbsData
)
{
    public BBSFilmSnapshotApplyRequest
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(expectedRevision, "expectedRevision");
        BBSFilmCollaborationLimits.requireServerSeq(serverSeq);
        encodedBbsData = BBSFilmCollaborationLimits.copyEncoded(encodedBbsData, BBSFilmCollaborationLimits.MAX_SNAPSHOT_BYTES, "encodedBbsData");
    }

    @Override
    public byte[] encodedBbsData()
    {
        return this.encodedBbsData.clone();
    }

    public int encodedByteLength()
    {
        return this.encodedBbsData.length;
    }
}
