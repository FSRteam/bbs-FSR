package mchorse.bbs_mod.camera.clips.misc;

import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.iris.ShaderCurves;

public class CurveClientClip extends CurveClip
{
    public CurveClientClip()
    {}

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        super.applyClip(context, position);

        String s = this.key.get();

        if (s.startsWith("curve/"))
        {
            ShaderCurves.ShaderVariable variable = ShaderCurves.variableMap.get(s.substring("curve/".length()));

            if (variable != null)
            {
                variable.value = this.channel.interpolate(context.relativeTick + context.transition).floatValue();
            }
        }
    }
}