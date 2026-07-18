package mchorse.bbs_mod.client.film.collaboration;

/** Pure local CAS revision rule shared by local batches, remote batches and snapshots. */
final class BBSFilmCoreRevision
{
    private BBSFilmCoreRevision()
    {}

    static long next(long current)
    {
        if (current < 0 || current == Long.MAX_VALUE)
        {
            throw new IllegalStateException("Film core revision cannot advance");
        }

        return current + 1;
    }
}
