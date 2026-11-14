package mchorse.bbs_mod.utils.keyframes.factories;

import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.cubic.ik.IKControls;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

public class IKKeyframeFactory extends ChainKeyframeFactory<IKControl, IKControls>
{
    @Override
    public IKControls createEmpty()
    {
        return new IKControls();
    }

    /* Keep the typed descriptors exposed by the pre-refactor factories. */
    @Override
    public IKControls fromData(BaseType data)
    {
        return super.fromData(data);
    }

    @Override
    public BaseType toData(IKControls value)
    {
        return super.toData(value);
    }

    @Override
    public IKControls copy(IKControls value)
    {
        return super.copy(value);
    }

    @Override
    public IKControls interpolate(Keyframe<IKControls> preA, Keyframe<IKControls> a, Keyframe<IKControls> b, Keyframe<IKControls> postB, IInterp interpolation, float x)
    {
        return super.interpolate(preA, a, b, postB, interpolation, x);
    }

    @Override
    public IKControls interpolate(IKControls preA, IKControls a, IKControls b, IKControls postB, IInterp interpolation, float x)
    {
        return super.interpolate(preA, a, b, postB, interpolation, x);
    }
}
