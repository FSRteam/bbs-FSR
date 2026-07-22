package mchorse.bbs_mod.film;

/** Persistent, additive observer for F4/F6 world export generations. */
public interface WorldVideoExportListener
{
    default void onStarted(WorldVideoExportSnapshot snapshot)
    {}

    default void onFinished(WorldVideoExportSnapshot snapshot, VideoExportResult result)
    {}
}
