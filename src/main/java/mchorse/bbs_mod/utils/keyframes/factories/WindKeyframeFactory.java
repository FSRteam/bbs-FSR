package mchorse.bbs_mod.utils.keyframes.factories;

import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

/**
 * Keyframe value holding the global {@link WindControl}. Unlike the IK and physics tracks (which key a
 * map of per-chain controls), the wind is a single object, so this interpolates one control directly.
 */
public class WindKeyframeFactory implements IKeyframeFactory<WindControl>
{
    private WindControl i = new WindControl();

    @Override
    public WindControl fromData(BaseType data)
    {
        WindControl control = new WindControl();

        if (data.isMap())
        {
            control.fromData(data.asMap());
        }

        return control;
    }

    @Override
    public BaseType toData(WindControl value)
    {
        return value.toData();
    }

    @Override
    public WindControl createEmpty()
    {
        return new WindControl();
    }

    @Override
    public WindControl copy(WindControl value)
    {
        return value.copy();
    }

    @Override
    public WindControl interpolate(Keyframe<WindControl> preA, Keyframe<WindControl> a, Keyframe<WindControl> b, Keyframe<WindControl> postB, IInterp interpolation, float x)
    {
        if (interpolation.has(Interpolations.AUTO) || interpolation.has(Interpolations.AUTO_CLAMPED))
        {
            boolean clamped = interpolation.has(Interpolations.AUTO_CLAMPED);

            this.i.autoLerp(preA.getValue(), a.getValue(), b.getValue(), postB.getValue(), preA.getTick(), a.getTick(), b.getTick(), postB.getTick(), clamped, x);

            return this.i;
        }

        return IKeyframeFactory.super.interpolate(preA, a, b, postB, interpolation, x);
    }

    @Override
    public WindControl interpolate(WindControl preA, WindControl a, WindControl b, WindControl postB, IInterp interpolation, float x)
    {
        this.i.lerp(preA, a, b, postB, interpolation, x);

        return this.i;
    }
}
