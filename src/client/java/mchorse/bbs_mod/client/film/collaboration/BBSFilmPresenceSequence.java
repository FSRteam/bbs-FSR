package mchorse.bbs_mod.client.film.collaboration;

/** Presence is a per-participant watermark: repeated snapshots may share a server sequence. */
final class BBSFilmPresenceSequence
{
    private BBSFilmPresenceSequence()
    {}

    static boolean accepts(long lastSeen, long incoming)
    {
        return incoming >= 0 && (lastSeen < 0 || incoming >= lastSeen);
    }

    static boolean acceptsAfterClear(long clearWatermark, long incoming)
    {
        return incoming >= 0 && (clearWatermark < 0 || incoming > clearWatermark);
    }
}
