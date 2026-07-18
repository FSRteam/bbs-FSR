package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.DamageActionClip;
import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import mchorse.bbs_mod.actions.types.blocks.BlockActionClip;
import mchorse.bbs_mod.actions.types.chat.ChatActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.actions.types.item.ItemDropActionClip;
import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.clips.overwrite.PathClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Server-runtime limits for a film before it can construct an action player,
 * spawn replay actors, or move the shared fake player used by action clips.
 */
public final class FilmPlaybackPolicy
{
    public static final int MAX_REPLAY_ENTRIES = 4_096;
    public static final int MAX_RUNTIME_REPLAYS = 256;
    public static final int MAX_RUNTIME_ACTORS = 256;
    public static final int MAX_CAMERA_CLIPS = 1_024;
    public static final int MAX_SIMULTANEOUS_CAMERA_CLIPS = 64;
    public static final int MAX_TOTAL_ACTION_CLIPS = 4_096;
    public static final int MAX_SIMULTANEOUS_ACTION_CLIPS = 256;
    public static final int MAX_ACTION_FREQUENCY_TICKS = 1_000;
    public static final int MIN_REPEATING_TEXT_ACTION_INTERVAL_TICKS = 20;
    public static final int MAX_CHAT_ACTION_CLIPS = 256;
    public static final int MAX_COMMAND_ACTION_CLIPS = 128;
    public static final int MAX_CHAT_MESSAGE_LENGTH = 1_024;
    public static final int MAX_COMMAND_LENGTH = 2_048;
    public static final double MAX_RELATIVE_ACTION_OFFSET = 1_024D;
    public static final int MAX_ACTION_TARGET_STRING_LENGTH = 255;
    public static final double MAX_ITEM_DROP_VELOCITY = 128D;
    public static final double MAX_REPLAY_VELOCITY = 64D;
    public static final float MAX_ACTION_DAMAGE = 1_024F;
    public static final float MAX_FALL_DISTANCE = 1_024F;
    public static final int MAX_FILM_DURATION_TICKS = 20 * 60 * 60 * 24;

    public static final double MIN_HORIZONTAL_POSITION = -30_000_000D;
    public static final double MAX_HORIZONTAL_POSITION = 30_000_000D;
    public static final double MIN_VERTICAL_POSITION = -20_000_000D;
    public static final double MAX_VERTICAL_POSITION = 20_000_000D;

    private static final int POSITION_XZ = 0;
    private static final int POSITION_Y = 1;
    private static final int VELOCITY = 2;
    private static final int ROTATION = 3;
    private static final int FLOAT_VALUE = 4;
    private static final int CAMERA_FOV = 5;
    private static final int REPLAY_VELOCITY = 6;
    private static final int FALL_DISTANCE = 7;

    public static boolean isPlaybackAllowed(Film film, float maxHealth, boolean appliesFirstPersonState)
    {
        if (film == null
            || !isCameraTimelineAllowed(film.camera)
            || !areReplayInputsAllowed(film))
        {
            return false;
        }

        return !appliesFirstPersonState
            || findEnabledFirstPersonReplay(film) == null
            || FilmPlayerSettingsPolicy.isAllowed(
                film.hp.get(),
                maxHealth,
                film.hunger.get(),
                film.xpLevel.get(),
                film.xpProgress.get()
            );
    }

    public static Replay findEnabledFirstPersonReplay(Film film)
    {
        if (film == null)
        {
            return null;
        }

        for (Replay replay : film.replays.getList())
        {
            if (replay != null && replay.enabled.get() && replay.fp.get())
            {
                return replay;
            }
        }

        return null;
    }

    /**
     * Decide whether a committed film mutation changes the display currently
     * projected onto a real first-person player. Replay selection is identity
     * based: two structurally equal replay values are still different runtime
     * owners and switching between them must refresh the projection.
     */
    static boolean affectsFirstPersonDisplay(
        Film film,
        Replay previousFirstPerson,
        Replay nextFirstPerson,
        BaseValue mutation
    )
    {
        if (film == null || nextFirstPerson == null || mutation == null)
        {
            return false;
        }

        return previousFirstPerson != nextFirstPerson
            || mutation == film
            || mutation == film.replays
            || mutation == film.inventory
            || mutation == nextFirstPerson
            || mutation == nextFirstPerson.form
            || mutation == film.hp
            || mutation == film.hunger
            || mutation == film.xpLevel
            || mutation == film.xpProgress;
    }

    /** Validate everything that can create/move replay actors or fake players. */
    public static boolean areReplayInputsAllowed(Film film)
    {
        try
        {
            return areReplayInputsAllowedUnchecked(film);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean areReplayInputsAllowedUnchecked(Film film)
    {
        if (film == null || !isReplayCountAllowed(film.replays.getList().size()))
        {
            return false;
        }

        long totalActionClips = 0L;
        int runtimeReplays = 0;
        int runtimeActors = 0;
        int enabledFirstPersonReplays = 0;
        int chatActions = 0;
        int commandActions = 0;
        List<Integer> actionTicks = new ArrayList<>();
        List<Integer> actionDurations = new ArrayList<>();

        for (Replay replay : film.replays.getList())
        {
            if (replay == null
                || !areReplayTransformsAllowed(replay.keyframes)
                || !isClipTimelineAllowed(replay.actions, MAX_TOTAL_ACTION_CLIPS))
            {
                return false;
            }

            totalActionClips = saturatingAdd(totalActionClips, replay.actions.size());

            if (replay.enabled.get())
            {
                runtimeReplays += 1;

                if (!isRuntimeReplayCountAllowed(runtimeReplays))
                {
                    return false;
                }
            }

            if (replay.enabled.get() && (replay.actor.get() || replay.fp.get()))
            {
                runtimeActors += 1;

                if (!isRuntimeActorCountAllowed(runtimeActors))
                {
                    return false;
                }
            }

            if (replay.enabled.get() && replay.fp.get() && ++enabledFirstPersonReplays > 1)
            {
                return false;
            }

            if (replay.enabled.get())
            {
                for (Clip clip : replay.actions.get())
                {
                    if (!(clip instanceof ActionClip action)
                        || action.frequency.get() < 0
                        || action.frequency.get() > MAX_ACTION_FREQUENCY_TICKS
                        || !isActionInputAllowed(action))
                    {
                        return false;
                    }

                    if (action instanceof ChatActionClip)
                    {
                        chatActions += 1;

                        if (chatActions > MAX_CHAT_ACTION_CLIPS)
                        {
                            return false;
                        }
                    }

                    if (action instanceof CommandActionClip)
                    {
                        commandActions += 1;

                        if (commandActions > MAX_COMMAND_ACTION_CLIPS)
                        {
                            return false;
                        }
                    }

                    if (action.enabled.get())
                    {
                        actionTicks.add(action.tick.get());
                        actionDurations.add(action.frequency.get() == 0 ? 1 : action.duration.get());
                    }
                }
            }

            if (!isTotalActionClipCountAllowed(totalActionClips))
            {
                return false;
            }
        }

        int[] ticks = new int[actionTicks.size()];
        int[] durations = new int[actionDurations.size()];

        for (int i = 0; i < ticks.length; i++)
        {
            ticks[i] = actionTicks.get(i);
            durations[i] = actionDurations.get(i);
        }

        return isActionScheduleAllowed(ticks, durations);
    }

    public static boolean isReplayCountAllowed(int replayCount)
    {
        return replayCount >= 0 && replayCount <= MAX_REPLAY_ENTRIES;
    }

    public static boolean isTotalActionClipCountAllowed(long clipCount)
    {
        return clipCount >= 0L && clipCount <= MAX_TOTAL_ACTION_CLIPS;
    }

    public static boolean isRuntimeActorCountAllowed(int actorCount)
    {
        return actorCount >= 0 && actorCount <= MAX_RUNTIME_ACTORS;
    }

    public static boolean isRuntimeReplayCountAllowed(int replayCount)
    {
        return replayCount >= 0 && replayCount <= MAX_RUNTIME_REPLAYS;
    }

    public static boolean isSimultaneousActionCountAllowed(int actionCount)
    {
        return actionCount >= 0 && actionCount <= MAX_SIMULTANEOUS_ACTION_CLIPS;
    }

    /** Conservative overlap bound for enabled action clips' active intervals. */
    public static boolean isActionScheduleAllowed(int[] ticks, int[] durations)
    {
        return isOverlapScheduleAllowed(
            ticks,
            durations,
            MAX_TOTAL_ACTION_CLIPS,
            MAX_SIMULTANEOUS_ACTION_CLIPS
        );
    }

    public static boolean isCameraScheduleAllowed(int[] ticks, int[] durations)
    {
        return isOverlapScheduleAllowed(
            ticks,
            durations,
            MAX_CAMERA_CLIPS,
            MAX_SIMULTANEOUS_CAMERA_CLIPS
        );
    }

    private static boolean isOverlapScheduleAllowed(
        int[] ticks,
        int[] durations,
        int maximumEntries,
        int maximumActive
    )
    {
        if (ticks == null
            || durations == null
            || ticks.length != durations.length
            || ticks.length > maximumEntries)
        {
            return false;
        }

        long[] boundaries = new long[ticks.length * 2];

        for (int i = 0; i < ticks.length; i++)
        {
            if (!isClipRangeAllowed(ticks[i], durations[i]))
            {
                return false;
            }

            long end = (long) ticks[i] + durations[i];

            /* End sorts before start at the same tick for [start, end). */
            boundaries[i * 2] = ((long) ticks[i] << 1) | 1L;
            boundaries[i * 2 + 1] = end << 1;
        }

        Arrays.sort(boundaries);

        int active = 0;

        for (long boundary : boundaries)
        {
            active += (boundary & 1L) == 0L ? -1 : 1;

            if (active < 0 || active > maximumActive)
            {
                return false;
            }
        }

        return active == 0;
    }

    public static boolean isClipRangeAllowed(int tick, int duration)
    {
        if (tick < 0 || duration <= 0)
        {
            return false;
        }

        long end = (long) tick + duration;

        return end <= MAX_FILM_DURATION_TICKS;
    }

    public static boolean isPositionAllowed(double x, double y, double z)
    {
        return Double.isFinite(x)
            && Double.isFinite(y)
            && Double.isFinite(z)
            && x >= MIN_HORIZONTAL_POSITION
            && x < MAX_HORIZONTAL_POSITION
            && z >= MIN_HORIZONTAL_POSITION
            && z < MAX_HORIZONTAL_POSITION
            && y >= MIN_VERTICAL_POSITION
            && y < MAX_VERTICAL_POSITION;
    }

    public static boolean isRotationAllowed(double rotation)
    {
        return Double.isFinite(rotation) && Math.abs(rotation) <= Float.MAX_VALUE;
    }

    public static boolean isCameraFovAllowed(double fov)
    {
        return Double.isFinite(fov) && fov > 0D && fov < 180D;
    }

    public static boolean isVelocityAllowed(double x, double y, double z)
    {
        return isReplayVelocityComponentAllowed(x)
            && isReplayVelocityComponentAllowed(y)
            && isReplayVelocityComponentAllowed(z);
    }

    public static boolean isFallDistanceAllowed(float fallDistance)
    {
        return Float.isFinite(fallDistance)
            && fallDistance >= 0F
            && fallDistance <= MAX_FALL_DISTANCE;
    }

    public static boolean isBlockPositionAllowed(int x, int y, int z)
    {
        return isPositionAllowed(x, y, z);
    }

    public static boolean isItemDropInputAllowed(
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        boolean relative
    )
    {
        boolean positionAllowed = relative
            ? Double.isFinite(x)
                && Double.isFinite(y)
                && Double.isFinite(z)
                && Math.abs(x) <= MAX_RELATIVE_ACTION_OFFSET
                && Math.abs(y) <= MAX_RELATIVE_ACTION_OFFSET
                && Math.abs(z) <= MAX_RELATIVE_ACTION_OFFSET
            : isPositionAllowed(x, y, z);

        return positionAllowed
            && isBoundedVelocity(velocityX)
            && isBoundedVelocity(velocityY)
            && isBoundedVelocity(velocityZ);
    }

    public static boolean isEntityInteractionOffsetAllowed(double x, double y, double z)
    {
        return Double.isFinite(x)
            && Double.isFinite(y)
            && Double.isFinite(z)
            && Math.abs(x) <= MAX_RELATIVE_ACTION_OFFSET
            && Math.abs(y) <= MAX_RELATIVE_ACTION_OFFSET
            && Math.abs(z) <= MAX_RELATIVE_ACTION_OFFSET;
    }

    /** Validate the stable identity snapshot without resolving a live entity. */
    public static boolean isActionTargetAllowed(ActionTarget target)
    {
        if (target == null)
        {
            return false;
        }

        String uuid = target.uuid.get();
        String entityType = target.entityType.get();
        String replayId = target.replayId.get();

        if (uuid == null || entityType == null || replayId == null
            || uuid.length() > MAX_ACTION_TARGET_STRING_LENGTH
            || entityType.length() > MAX_ACTION_TARGET_STRING_LENGTH
            || replayId.length() > MAX_ACTION_TARGET_STRING_LENGTH
            || !isPositionAllowed(target.position.get().x, target.position.get().y, target.position.get().z))
        {
            return false;
        }

        if (!uuid.isEmpty())
        {
            try
            {
                UUID parsed = UUID.fromString(uuid);

                if (!parsed.toString().equalsIgnoreCase(uuid))
                {
                    return false;
                }
            }
            catch (IllegalArgumentException e)
            {
                return false;
            }
        }

        return entityType.isEmpty() || ResourceLocation.tryParse(entityType) != null;
    }

    public static boolean isEntityInteractionInputAllowed(EntityInteractionActionClip action)
    {
        if (action == null || !action.target.isPresent() || !isActionTargetAllowed(action.target))
        {
            return false;
        }

        if (!action.interactAt.get())
        {
            return true;
        }

        var point = action.location.get();

        return isEntityInteractionOffsetAllowed(point.x, point.y, point.z);
    }

    public static boolean isDamageAllowed(float damage)
    {
        return Float.isFinite(damage) && damage >= 0F && damage <= MAX_ACTION_DAMAGE;
    }

    public static boolean isChatActionAllowed(String message, int frequency)
    {
        return message != null
            && message.length() <= MAX_CHAT_MESSAGE_LENGTH
            && isTextActionFrequencyAllowed(frequency);
    }

    public static boolean isCommandActionAllowed(String command, int frequency)
    {
        return command != null
            && command.length() <= MAX_COMMAND_LENGTH
            && command.indexOf('\0') < 0
            && command.indexOf('\r') < 0
            && command.indexOf('\n') < 0
            && isTextActionFrequencyAllowed(frequency);
    }

    public static boolean isTextActionFrequencyAllowed(int frequency)
    {
        return frequency == 0
            || (frequency >= MIN_REPEATING_TEXT_ACTION_INTERVAL_TICKS
                && frequency <= MAX_ACTION_FREQUENCY_TICKS);
    }

    public static boolean isPoseAllowed(
        double x,
        double y,
        double z,
        double yaw,
        double headYaw,
        double bodyYaw,
        double pitch
    )
    {
        return isPositionAllowed(x, y, z)
            && isRotationAllowed(yaw)
            && isRotationAllowed(headYaw)
            && isRotationAllowed(bodyYaw)
            && isRotationAllowed(pitch);
    }

    /** Estimate one destination-sampling seek without replaying intermediate actions. */
    public static long estimateSeekWork(long tickSteps, int replayCount, long totalActionClips)
    {
        if (tickSteps < 0L || replayCount < 0 || totalActionClips < 0L)
        {
            return Long.MAX_VALUE;
        }

        if (tickSteps == 0L)
        {
            return 0L;
        }

        long perRequest = saturatingAdd(1L, replayCount);

        perRequest = saturatingAdd(perRequest, totalActionClips);

        return perRequest;
    }

    public static long estimateSeekWork(Film film, long tickSteps)
    {
        if (film == null)
        {
            return Long.MAX_VALUE;
        }

        return estimateSeekWork(tickSteps, film.replays.getList().size(), countActionClips(film));
    }

    public static long countActionClips(Film film)
    {
        if (film == null)
        {
            return Long.MAX_VALUE;
        }

        long total = 0L;

        for (Replay replay : film.replays.getList())
        {
            if (replay == null)
            {
                return Long.MAX_VALUE;
            }

            total = saturatingAdd(total, replay.actions.size());
        }

        return total;
    }

    private static boolean isClipTimelineAllowed(Clips clips, int maxClips)
    {
        try
        {
            if (clips == null || clips.size() > maxClips)
            {
                return false;
            }

            for (Clip clip : clips.get())
            {
                if (clip == null
                    || !isClipRangeAllowed(clip.tick.get(), clip.duration.get())
                    || !isFiniteClipData(clip.toData(), 0)
                    || (clip instanceof CameraClip && !areCameraClipInputsAllowed(clip)))
                {
                    return false;
                }
            }

            return true;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static boolean isCameraTimelineAllowed(Clips clips)
    {
        if (!isClipTimelineAllowed(clips, MAX_CAMERA_CLIPS))
        {
            return false;
        }

        List<Integer> ticks = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        for (Clip clip : clips.get())
        {
            if (clip.enabled.get())
            {
                ticks.add(clip.isGlobal() ? 0 : clip.tick.get());
                durations.add(clip.isGlobal() ? MAX_FILM_DURATION_TICKS : clip.duration.get());
            }
        }

        return isCameraScheduleAllowed(toIntArray(ticks), toIntArray(durations));
    }

    private static boolean isActionInputAllowed(ActionClip action)
    {
        if (action instanceof BlockActionClip block
            && !isBlockPositionAllowed(block.x.get(), block.y.get(), block.z.get()))
        {
            return false;
        }

        if (action instanceof ItemDropActionClip drop
            && !isItemDropInputAllowed(
                drop.posX.get(),
                drop.posY.get(),
                drop.posZ.get(),
                drop.velocityX.get(),
                drop.velocityY.get(),
                drop.velocityZ.get(),
                drop.relative.get()
            ))
        {
            return false;
        }

        if (action instanceof DamageActionClip damage && !isDamageAllowed(damage.damage.get()))
        {
            return false;
        }

        if (action instanceof AttackActionClip attack && !isDamageAllowed(attack.damage.get()))
        {
            return false;
        }

        if (action instanceof AttackActionClip attack
            && attack.target.isPresent()
            && !isActionTargetAllowed(attack.target))
        {
            return false;
        }

        if (action instanceof EntityInteractionActionClip interaction
            && !isEntityInteractionInputAllowed(interaction))
        {
            return false;
        }

        if (action instanceof ChatActionClip chat
            && !isChatActionAllowed(chat.message.get(), chat.frequency.get()))
        {
            return false;
        }

        return !(action instanceof CommandActionClip command)
            || isCommandActionAllowed(command.command.get(), command.frequency.get());
    }

    private static int[] toIntArray(List<Integer> values)
    {
        int[] output = new int[values.size()];

        for (int i = 0; i < output.length; i++)
        {
            output[i] = values.get(i);
        }

        return output;
    }

    private static boolean isBoundedVelocity(double value)
    {
        return Double.isFinite(value) && Math.abs(value) <= MAX_ITEM_DROP_VELOCITY;
    }

    private static boolean areCameraClipInputsAllowed(Clip clip)
    {
        if (clip instanceof IdleClip idle && !isCameraPoseAllowed(idle.position.get()))
        {
            return false;
        }

        if (clip instanceof PathClip path)
        {
            for (int i = 0; i < path.points.size(); i++)
            {
                if (!isCameraPoseAllowed(path.points.get(i)))
                {
                    return false;
                }
            }
        }

        if (clip instanceof KeyframeClip keyframes)
        {
            return isChannelAllowed(keyframes.x, POSITION_XZ)
                && isChannelAllowed(keyframes.y, POSITION_Y)
                && isChannelAllowed(keyframes.z, POSITION_XZ)
                && isChannelAllowed(keyframes.yaw, ROTATION)
                && isChannelAllowed(keyframes.pitch, ROTATION)
                && isChannelAllowed(keyframes.roll, ROTATION)
                && isChannelAllowed(keyframes.fov, keyframes.additive.get() ? FLOAT_VALUE : CAMERA_FOV)
                && isChannelAllowed(keyframes.distance, VELOCITY);
        }

        return true;
    }

    public static boolean isCameraPoseAllowed(Position position)
    {
        return position != null
            && isPositionAllowed(position.point.x, position.point.y, position.point.z)
            && isRotationAllowed(position.angle.yaw)
            && isRotationAllowed(position.angle.pitch)
            && isRotationAllowed(position.angle.roll)
            && isCameraFovAllowed(position.angle.fov);
    }

    private static boolean isFiniteClipData(BaseType data, int depth)
    {
        if (data == null || depth > 64)
        {
            return false;
        }

        if (data.getTypeId() == BaseType.TYPE_FLOAT || data.getTypeId() == BaseType.TYPE_DOUBLE)
        {
            double value = data.asNumeric().doubleValue();

            return Double.isFinite(value) && Math.abs(value) <= MAX_HORIZONTAL_POSITION;
        }

        if (data instanceof MapType map)
        {
            for (Map.Entry<String, BaseType> entry : map)
            {
                if (!isFiniteClipData(entry.getValue(), depth + 1))
                {
                    return false;
                }
            }
        }
        else if (data instanceof ListType list)
        {
            for (BaseType value : list)
            {
                if (!isFiniteClipData(value, depth + 1))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean areReplayTransformsAllowed(ReplayKeyframes keyframes)
    {
        return keyframes != null
            && isChannelAllowed(keyframes.x, POSITION_XZ)
            && isChannelAllowed(keyframes.y, POSITION_Y)
            && isChannelAllowed(keyframes.z, POSITION_XZ)
            && isChannelAllowed(keyframes.vX, REPLAY_VELOCITY)
            && isChannelAllowed(keyframes.vY, REPLAY_VELOCITY)
            && isChannelAllowed(keyframes.vZ, REPLAY_VELOCITY)
            && isChannelAllowed(keyframes.yaw, ROTATION)
            && isChannelAllowed(keyframes.pitch, ROTATION)
            && isChannelAllowed(keyframes.headYaw, ROTATION)
            && isChannelAllowed(keyframes.bodyYaw, ROTATION)
            && isChannelAllowed(keyframes.fall, FALL_DISTANCE);
    }

    private static boolean isChannelAllowed(KeyframeChannel<Double> channel, int kind)
    {
        if (channel == null)
        {
            return false;
        }

        float previousTick = -Float.MAX_VALUE;
        Double previousValue = null;

        for (Keyframe<Double> keyframe : channel.getKeyframes())
        {
            if (keyframe == null
                || keyframe.getValue() == null
                || !isKeyframeMetadataAllowed(keyframe)
                || keyframe.getTick() < previousTick
                || !isChannelValueAllowed(keyframe.getValue(), kind))
            {
                return false;
            }

            if ((kind == POSITION_XZ || kind == POSITION_Y)
                && previousValue != null
                && !isReplayVelocityComponentAllowed(keyframe.getValue() - previousValue))
            {
                return false;
            }

            previousTick = keyframe.getTick();
            previousValue = keyframe.getValue();
        }

        return true;
    }

    private static boolean isKeyframeMetadataAllowed(Keyframe<?> keyframe)
    {
        Interpolation interpolation = keyframe.getInterpolation();

        return Float.isFinite(keyframe.getTick())
            && keyframe.getTick() >= 0F
            && keyframe.getTick() <= MAX_FILM_DURATION_TICKS
            && Float.isFinite(keyframe.getDuration())
            && keyframe.getDuration() >= 0F
            && keyframe.getDuration() <= MAX_FILM_DURATION_TICKS
            && Float.isFinite(keyframe.lx)
            && Float.isFinite(keyframe.ly)
            && Float.isFinite(keyframe.rx)
            && Float.isFinite(keyframe.ry)
            && isFiniteList(keyframe.lx_m)
            && isFiniteList(keyframe.ly_m)
            && isFiniteList(keyframe.rx_m)
            && isFiniteList(keyframe.ry_m)
            && interpolation != null
            && interpolation.getInterp() != null
            && isInterpolationArgumentAllowed(interpolation.getV1())
            && isInterpolationArgumentAllowed(interpolation.getV2())
            && isInterpolationArgumentAllowed(interpolation.getV3())
            && isInterpolationArgumentAllowed(interpolation.getV4());
    }

    private static boolean isChannelValueAllowed(double value, int kind)
    {
        if (kind == POSITION_XZ)
        {
            return Double.isFinite(value) && value >= MIN_HORIZONTAL_POSITION && value < MAX_HORIZONTAL_POSITION;
        }

        if (kind == POSITION_Y)
        {
            return Double.isFinite(value) && value >= MIN_VERTICAL_POSITION && value < MAX_VERTICAL_POSITION;
        }

        if (kind == VELOCITY)
        {
            return isVelocityComponentAllowed(value);
        }

        if (kind == REPLAY_VELOCITY)
        {
            return isReplayVelocityComponentAllowed(value);
        }

        if (kind == FALL_DISTANCE)
        {
            return value >= -Float.MAX_VALUE
                && value <= Float.MAX_VALUE
                && isFallDistanceAllowed((float) value);
        }

        if (kind == CAMERA_FOV)
        {
            return isCameraFovAllowed(value);
        }

        return isRotationAllowed(value);
    }

    private static boolean isVelocityComponentAllowed(double value)
    {
        return Double.isFinite(value) && Math.abs(value) <= MAX_HORIZONTAL_POSITION;
    }

    private static boolean isReplayVelocityComponentAllowed(double value)
    {
        return Double.isFinite(value) && Math.abs(value) <= MAX_REPLAY_VELOCITY;
    }

    private static boolean isInterpolationArgumentAllowed(double value)
    {
        return Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE;
    }

    private static boolean isFiniteList(List<Float> values)
    {
        if (values == null)
        {
            return true;
        }

        for (Float value : values)
        {
            if (value == null || !Float.isFinite(value))
            {
                return false;
            }
        }

        return true;
    }

    private static long saturatingAdd(long a, long b)
    {
        if (a < 0L || b < 0L || a > Long.MAX_VALUE - b)
        {
            return Long.MAX_VALUE;
        }

        return a + b;
    }

    private static long saturatingMultiply(long a, long b)
    {
        if (a < 0L || b < 0L || (a != 0L && b > Long.MAX_VALUE / a))
        {
            return Long.MAX_VALUE;
        }

        return a * b;
    }

    private FilmPlaybackPolicy()
    {}
}
