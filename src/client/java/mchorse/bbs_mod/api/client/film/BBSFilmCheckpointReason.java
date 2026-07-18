package mchorse.bbs_mod.api.client.film;

/** Why a local semantic commit must fall back to a whole-Film checkpoint. */
public enum BBSFilmCheckpointReason
{
    TOO_MANY_MUTATIONS,
    PATH_LIMIT_EXCEEDED,
    VALUE_TOO_LARGE,
    BATCH_TOO_LARGE,
    ENCODE_FAILED
}
