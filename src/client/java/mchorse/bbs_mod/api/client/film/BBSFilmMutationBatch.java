package mchorse.bbs_mod.api.client.film;

import java.util.List;
import java.util.Objects;

/**
 * One atomic local commit or one server-ordered remote commit. A successfully
 * applied batch advances the core Film revision exactly once, regardless of
 * how many mutations it contains. {@code baseRevision} is the receiving local
 * Film instance's CAS revision; addons must keep their server collaboration
 * revision separately. For remote applies, {@code serverSeq} is this addon's
 * continuous server-ordered semantic sequence within the core Film session;
 * it is deliberately independent from raw wire sequencing.
 */
public record BBSFilmMutationBatch(
    long sessionId,
    long baseRevision,
    long localOpId,
    long serverSeq,
    List<BBSFilmMutation> mutations
)
{
    /** Use when a batch has not yet been assigned a server sequence. */
    public static final long NO_SERVER_SEQUENCE = -1L;

    public BBSFilmMutationBatch
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(baseRevision, "baseRevision");

        if (localOpId < 0)
        {
            throw new IllegalArgumentException("localOpId must not be negative");
        }

        BBSFilmCollaborationLimits.requireServerSeq(serverSeq);
        List<BBSFilmMutation> checkedMutations = Objects.requireNonNull(mutations, "mutations");

        if (checkedMutations.isEmpty() || checkedMutations.size() > BBSFilmCollaborationLimits.MAX_MUTATIONS)
        {
            throw new IllegalArgumentException("mutations must contain 1.." + BBSFilmCollaborationLimits.MAX_MUTATIONS + " entries");
        }

        mutations = List.copyOf(checkedMutations);

        long totalBytes = 0;

        for (BBSFilmMutation mutation : mutations)
        {
            totalBytes += Objects.requireNonNull(mutation, "mutation").encodedByteLength();

            if (totalBytes > BBSFilmCollaborationLimits.MAX_BATCH_BYTES)
            {
                throw new IllegalArgumentException("mutation batch exceeds its encoded data limit");
            }
        }
    }
}
