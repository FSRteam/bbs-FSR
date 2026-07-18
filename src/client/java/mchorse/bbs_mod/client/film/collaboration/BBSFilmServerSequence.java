package mchorse.bbs_mod.client.film.collaboration;

/** Pure server-order rule: a snapshot may establish a watermark, then no gaps. */
final class BBSFilmServerSequence
{
    private BBSFilmServerSequence()
    {}

    static boolean accepts(long lastApplied, long incoming)
    {
        return incoming >= 0 && (lastApplied < 0 || incoming == lastApplied + 1);
    }

    /** An authoritative snapshot may establish or repair any non-stale watermark. */
    static boolean acceptsSnapshot(long lastApplied, long incoming)
    {
        return incoming >= 0 && (lastApplied < 0 || incoming >= lastApplied);
    }
}
