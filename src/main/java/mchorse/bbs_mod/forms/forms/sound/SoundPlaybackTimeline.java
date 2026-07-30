package mchorse.bbs_mod.forms.forms.sound;

import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.List;
import java.util.function.Predicate;

/** Timeline calculations shared by live playback and dependency-light tests. */
public final class SoundPlaybackTimeline
{
    private SoundPlaybackTimeline()
    {}

    /**
     * Find the beginning of the active {@code true} run in a step-valued
     * playing channel. Boolean keyframes extend their first value backwards,
     * so an initially-true track is considered active from film tick zero.
     */
    public static float findActivationTick(KeyframeChannel<Boolean> channel, float tick, boolean active)
    {
        return findActivationTick(channel, tick, active, Boolean.TRUE::equals);
    }

    /** A grouped sound track wins only when it contains keyframes; empty editor sheets preserve legacy Films. */
    public static float findSoundActivationTick(KeyframeChannel<SoundKeyframeValue> grouped,
        KeyframeChannel<Boolean> legacy, float tick, boolean active)
    {
        if (grouped != null && !grouped.isEmpty())
        {
            return findActivationTick(grouped, tick, active, value -> value != null && value.playing);
        }

        return findActivationTick(legacy, tick, active);
    }

    /** Variant used by the grouped sound track's embedded playing flag. */
    public static <T> float findActivationTick(KeyframeChannel<T> channel, float tick,
        boolean active, Predicate<T> isActive)
    {
        if (!active)
        {
            return tick;
        }

        if (channel == null || channel.isEmpty())
        {
            return 0F;
        }

        List<Keyframe<T>> keyframes = channel.getKeyframes();
        int activeIndex = -1;

        for (int i = 0; i < keyframes.size(); i++)
        {
            if (keyframes.get(i).getTick() > tick)
            {
                break;
            }

            activeIndex = i;
        }

        if (activeIndex < 0)
        {
            return isActive.test(keyframes.get(0).getValue()) ? 0F : tick;
        }

        if (!isActive.test(keyframes.get(activeIndex).getValue()))
        {
            return tick;
        }

        while (activeIndex > 0 && isActive.test(keyframes.get(activeIndex - 1).getValue()))
        {
            activeIndex -= 1;
        }

        return Math.max(0F, keyframes.get(activeIndex).getTick());
    }

    public static float clipSeconds(float tick, float activationTick, float startOffset)
    {
        return Math.max(0F, startOffset) + Math.max(0F, tick - activationTick) / 20F;
    }

    /** Preserve a negative reflection delay; only an already-arrived looping voice may wrap. */
    public static float wrapLoopingSeconds(float seconds, float duration)
    {
        if (seconds < 0F || duration <= 0F)
        {
            return seconds;
        }

        float wrapped = seconds % duration;

        return wrapped < 0F ? wrapped + duration : wrapped;
    }

    /**
     * Project raw timeline seconds into one voice's loop period. Negative raw
     * seconds stay absent so delayed reflections cannot wrap in early.
     */
    public static float projectLoopingSeconds(float seconds, float duration,
        boolean looping, float interval)
    {
        return projectLoopingSeconds(seconds, 0F, duration, looping, interval);
    }

    /**
     * Project a voice after separating its clip offset from elapsed timeline
     * time. A start offset is always a position inside the clip, never inside
     * the configured silence window.
     */
    public static float projectLoopingSeconds(float seconds, float startOffset,
        float duration, boolean looping, float interval)
    {
        return projectLoopingSeconds(seconds, startOffset, duration, looping, interval, 1F);
    }

    /**
     * Convert one voice's timeline position to a media offset. Audio duration
     * is expressed in media seconds, while the interval is expressed in
     * timeline seconds, so pitch changes the audible portion of each cycle
     * but never changes the requested silence duration.
     */
    public static float projectLoopingSeconds(float seconds, float startOffset,
        float duration, boolean looping, float interval, float pitch)
    {
        float offset = Math.max(0F, startOffset);
        float elapsed = seconds - offset;

        if (elapsed < 0F || !Float.isFinite(elapsed) || !Float.isFinite(offset)
            || pitch <= 0F || !Float.isFinite(pitch))
        {
            return Float.NaN;
        }

        float rate = pitch;

        if (!looping || duration <= 0F || !Float.isFinite(duration))
        {
            return offset + elapsed * rate;
        }

        float gap = Float.isNaN(interval) ? 0F : Math.max(0F, interval);
        float clipOffset = wrapLoopingSeconds(offset, duration);
        float firstAudibleDuration = (duration - clipOffset) / rate;

        if (gap == 0F)
        {
            return wrapLoopingSeconds(clipOffset + elapsed * rate, duration);
        }

        if (elapsed < firstAudibleDuration)
        {
            return clipOffset + elapsed * rate;
        }

        elapsed -= firstAudibleDuration;

        if (elapsed < gap)
        {
            return Float.NaN;
        }

        elapsed -= gap;

        float audibleDuration = duration / rate;
        float period = audibleDuration + gap;
        float phase = Float.isInfinite(period) ? elapsed : elapsed % period;

        if (phase < 0F)
        {
            phase += period;
        }

        return phase < audibleDuration ? phase * rate : Float.NaN;
    }

    /** Native OpenAL looping is reserved for the zero-gap seamless case. */
    public static boolean usesNativeLooping(boolean looping, float interval)
    {
        return looping && !(interval > 0F);
    }

    /**
     * Normal forward playback lets OpenAL advance naturally. Pausing,
     * scrubbing, reverse playback, a loop wrap, or a fresh trigger requires an
     * explicit seek to the timeline-derived position.
     */
    public static boolean shouldSeek(float previousTick, float tick, boolean wasActive,
        boolean active, boolean transportPlaying)
    {
        if (!active)
        {
            return false;
        }

        if (!wasActive || !Float.isFinite(previousTick) || !transportPlaying)
        {
            return true;
        }

        float delta = tick - previousTick;

        return delta < -0.01F || delta > 1.25F;
    }
}
