package mchorse.bbs_mod.api.client.film;

import java.util.List;
import java.util.Objects;

/**
 * Immutable semantic Film update. Path entries are atomic value ids: callers
 * must never join or split them, because an id may itself contain '/' or '.'.
 */
public record BBSFilmMutation(
    BBSFilmMutationKind kind,
    List<String> pathSegments,
    byte[] encodedBbsData,
    BBSFilmRefreshHint refreshHint
)
{
    public BBSFilmMutation
    {
        kind = Objects.requireNonNull(kind, "kind");
        List<String> checkedPath = Objects.requireNonNull(pathSegments, "pathSegments");

        BBSFilmCollaborationLimits.requirePath(checkedPath);
        pathSegments = List.copyOf(checkedPath);
        encodedBbsData = BBSFilmCollaborationLimits.copyEncoded(encodedBbsData, BBSFilmCollaborationLimits.MAX_MUTATION_BYTES, "encodedBbsData");
        refreshHint = Objects.requireNonNull(refreshHint, "refreshHint");
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
