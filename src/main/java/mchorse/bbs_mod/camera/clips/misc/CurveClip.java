package mchorse.bbs_mod.camera.clips.misc;

import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.ValueString;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.HashMap;
import java.util.Map;

public class CurveClip extends CameraClip
{
    public final ValueString key = new ValueString("key", "sun_rotation");
    public final KeyframeChannel<Double> channel = new KeyframeChannel<>("channel", KeyframeFactories.DOUBLE);

    public static Map<String, Double> getValues(ClipContext context)
    {
        if (context.clipData.containsKey("curve_data"))
        {
            return (Map<String, Double>) context.clipData.get("curve_data");
        }

        Map<String, Double> data = new HashMap<>();

        context.clipData.put("curve_data", data);

        return data;
    }

    public CurveClip()
    {
        this.add(this.key);
        this.add(this.channel);
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        Map<String, Double> values = getValues(context);
        String key = this.key.get();

        values.put(key, this.channel.interpolate(context.relativeTick + context.transition));
    }

    @Override
    protected Clip create()
    {
        return new CurveClip();
    }
}