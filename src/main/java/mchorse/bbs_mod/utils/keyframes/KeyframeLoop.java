package mchorse.bbs_mod.utils.keyframes;

import mchorse.bbs_mod.data.types.MapType;

/** A finite repeated source interval in a keyframe channel. */
public record KeyframeLoop(String id, float start, float sourceEnd, float end)
{
    public boolean isValid()
    {
        return id != null && !id.isEmpty() && Float.isFinite(start) && Float.isFinite(sourceEnd)
            && Float.isFinite(end) && sourceEnd > start && end >= sourceEnd
            && Float.isFinite(this.period()) && Float.isFinite(end - start);
    }

    public float period() { return sourceEnd - start; }

    public float passes() { return period() <= 0F ? 1F : (end - start) / period(); }

    public boolean containsSource(float tick) { return tick >= start && tick <= sourceEnd; }

    public float sourceTick(float tick)
    {
        if (tick <= sourceEnd) return Math.max(start, tick);
        double offset = ((double) Math.min(tick, end) - start) % period();
        return offset == 0D ? sourceEnd : (float) (start + offset);
    }

    public KeyframeLoop withEnd(float value) { return new KeyframeLoop(id, start, sourceEnd, Math.max(sourceEnd, value)); }
    public KeyframeLoop move(float delta) { return new KeyframeLoop(id, start + delta, sourceEnd + delta, end + delta); }

    public MapType toData()
    {
        MapType data = new MapType();
        data.putString("id", id);
        data.putFloat("start", start);
        data.putFloat("source_end", sourceEnd);
        data.putFloat("end", end);
        return data;
    }

    public static KeyframeLoop fromData(MapType data)
    {
        return new KeyframeLoop(data.getString("id"), data.getFloat("start"), data.getFloat("source_end"), data.getFloat("end"));
    }
}
