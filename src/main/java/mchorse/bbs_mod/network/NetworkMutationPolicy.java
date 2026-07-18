package mchorse.bbs_mod.network;

import mchorse.bbs_mod.actions.FilmPlayerSettingsPolicy;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;

/** Pure numeric budgets applied before expensive or stateful server mutations. */
final class NetworkMutationPolicy
{
    static final int MAX_FILM_SEEK_STEPS = 20 * 60;
    static final int MAX_RECORDING_COUNTDOWN_TICKS = 20 * 30;
    static final double MIN_HORIZONTAL_POSITION = FilmPlaybackPolicy.MIN_HORIZONTAL_POSITION;
    static final double MAX_HORIZONTAL_POSITION = FilmPlaybackPolicy.MAX_HORIZONTAL_POSITION;
    static final double MIN_VERTICAL_POSITION = FilmPlaybackPolicy.MIN_VERTICAL_POSITION;
    static final double MAX_VERTICAL_POSITION = FilmPlaybackPolicy.MAX_VERTICAL_POSITION;

    public static boolean isFilmTickAllowed(int currentTick, int requestedTick, int duration, boolean restart)
    {
        if (requestedTick < 0 || duration < 0 || requestedTick > duration)
        {
            return false;
        }

        int from = restart ? 0 : currentTick;
        long work = Math.abs((long) requestedTick - from);

        return work <= MAX_FILM_SEEK_STEPS;
    }

    public static boolean isRecordingStartAllowed(
        int replayId,
        int tick,
        int countdown,
        int replayCount,
        int duration
    )
    {
        return replayCount > 0
            && replayId >= 0
            && replayId < replayCount
            && tick >= 0
            && duration >= 0
            && tick <= duration
            && countdown >= 0
            && countdown <= MAX_RECORDING_COUNTDOWN_TICKS;
    }

    public static boolean isTeleportAllowed(
        double x,
        double y,
        double z,
        float yaw,
        float bodyYaw,
        float pitch
    )
    {
        return FilmPlaybackPolicy.isPositionAllowed(x, y, z)
            && Float.isFinite(yaw)
            && Float.isFinite(bodyYaw)
            && Float.isFinite(pitch)
            && pitch >= -90F
            && pitch <= 90F;
    }

    public static boolean arePlayerSettingsAllowed(
        float hp,
        float maxHp,
        float hunger,
        int xpLevel,
        float xpProgress
    )
    {
        return FilmPlayerSettingsPolicy.isAllowed(hp, maxHp, hunger, xpLevel, xpProgress);
    }
}
