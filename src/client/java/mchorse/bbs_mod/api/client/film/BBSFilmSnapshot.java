package mchorse.bbs_mod.api.client.film;

/** Encoded BBS Film data captured at one local core CAS revision. */
public record BBSFilmSnapshot(long sessionId, long revision, byte[] encodedBbsData)
{
    public BBSFilmSnapshot
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(revision, "revision");
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
