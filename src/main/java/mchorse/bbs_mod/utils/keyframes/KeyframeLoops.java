package mchorse.bbs_mod.utils.keyframes;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueList;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared operations for channel loop blocks. */
public final class KeyframeLoops
{
    private KeyframeLoops() {}

    public static KeyframeLoop create(List<KeyframeChannel<?>> channels, float start, float sourceEnd)
    {
        if (channels == null || channels.isEmpty() || !Float.isFinite(start) || !Float.isFinite(sourceEnd) || sourceEnd <= start)
        {
            return null;
        }

        if (!canCreate(channels, start, sourceEnd)) return null;

        KeyframeLoop loop = new KeyframeLoop(UUID.randomUUID().toString(), start, sourceEnd, sourceEnd);
        loop = loop.withEnd(Math.min(sourceEnd + loop.period(), maxEnd(channels, loop)));
        for (KeyframeChannel<?> channel : channels) channel.putLoop(loop);
        return loop;
    }

    public static boolean canCreate(List<KeyframeChannel<?>> channels, float start, float sourceEnd)
    {
        if (channels == null || channels.isEmpty() || !Float.isFinite(start) || !Float.isFinite(sourceEnd) || sourceEnd <= start) return false;

        for (KeyframeChannel<?> channel : channels)
        {
            boolean hasKey = false;
            for (Keyframe<?> key : channel.getKeyframes())
            {
                hasKey |= key.getTick() >= start && key.getTick() <= sourceEnd;
            }
            if (!hasKey) return false;
            for (KeyframeLoop loop : channel.getLoops())
            {
                if (start <= channel.getLoopEnd(loop) && sourceEnd >= loop.start()) return false;
            }
        }
        return true;
    }

    public static float maxEnd(List<KeyframeChannel<?>> channels, KeyframeLoop loop)
    {
        float end = Float.MAX_VALUE;
        for (KeyframeChannel<?> channel : channels)
        {
            for (Keyframe<?> key : channel.getKeyframes())
            {
                if (key.getTick() > loop.sourceEnd()) end = Math.min(end, key.getTick());
            }
            for (KeyframeLoop other : channel.getLoops())
            {
                if (!other.id().equals(loop.id()) && other.start() > loop.start()) end = Math.min(end, other.start());
            }
        }
        return end;
    }

    public static void resize(List<KeyframeChannel<?>> channels, String id, float end)
    {
        if (channels == null || channels.isEmpty() || !Float.isFinite(end)) return;
        KeyframeLoop loop = channels.get(0).getLoop(id);
        if (loop == null) return;
        KeyframeLoop resized = loop.withEnd(Math.min(end, maxEnd(channels, loop)));
        for (KeyframeChannel<?> channel : channels) channel.putLoop(resized);
    }

    public static void remove(List<KeyframeChannel<?>> channels, String id)
    {
        if (channels == null) return;
        for (KeyframeChannel<?> channel : channels) channel.removeLoop(id);
    }

    public static List<KeyframeChannel<?>> members(KeyframeChannel<?> channel, String id)
    {
        List<KeyframeChannel<?>> result = new ArrayList<>();
        if (channel == null) return result;
        BaseValue scope = channel;
        while (scope.getParent() != null)
        {
            /* A copied replay/clip/state owns its loops independently of the source. */
            if (scope instanceof Clip || (scope != channel && scope.getParent() instanceof ValueList<?>)) break;
            scope = scope.getParent();
        }
        collect(scope, id, result);
        return result;
    }

    private static void collect(BaseValue value, String id, List<KeyframeChannel<?>> result)
    {
        if (value instanceof KeyframeChannel<?> channel)
        {
            if (channel.getLoop(id) != null) result.add(channel);
        }
        else if (value instanceof BaseValueGroup group)
        {
            for (BaseValue child : group.getAll()) collect(child, id, result);
        }
    }

    /** Move the source keys and loop together, stopping at neighbouring content. */
    public static void move(List<KeyframeChannel<?>> channels, String id, float delta)
    {
        if (channels == null || channels.isEmpty() || !Float.isFinite(delta)) return;
        KeyframeLoop loop = channels.get(0).getLoop(id);
        if (loop == null) return;
        float min = -loop.start();
        float max = Float.MAX_VALUE;
        for (KeyframeChannel<?> channel : channels)
        {
            for (Keyframe<?> key : channel.getKeyframes())
            {
                float tick = key.getTick();
                if (tick < loop.start()) min = Math.max(min, Math.nextUp(tick) - loop.start());
                else if (tick > loop.sourceEnd()) max = Math.min(max, tick - loop.end());
            }
            for (KeyframeLoop other : channel.getLoops())
            {
                if (other.id().equals(id)) continue;
                if (other.start() < loop.start()) min = Math.max(min, Math.nextUp(other.end()) - loop.start());
                else max = Math.min(max, Math.nextDown(other.start()) - loop.end());
            }
        }
        delta = Math.max(min, Math.min(max, delta));
        if (delta == 0F) return;
        for (KeyframeChannel<?> channel : channels)
        {
            for (Keyframe<?> key : channel.getKeyframes())
            {
                if (loop.containsSource(key.getTick())) key.setTick(key.getTick() + delta);
            }
            channel.putLoop(loop.move(delta));
            channel.sort();
        }
    }
}
