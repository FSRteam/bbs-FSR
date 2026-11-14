package mchorse.bbs_mod.utils.keyframes.factories;

import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.PhysicsControls;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

public class PhysicsKeyframeFactory extends ChainKeyframeFactory<PhysicsControl, PhysicsControls>
{
    @Override
    public PhysicsControls createEmpty()
    {
        return new PhysicsControls();
    }

    /* Keep the typed descriptors exposed by the pre-refactor factories. */
    @Override
    public PhysicsControls fromData(BaseType data)
    {
        return super.fromData(data);
    }

    @Override
    public BaseType toData(PhysicsControls value)
    {
        return super.toData(value);
    }

    @Override
    public PhysicsControls copy(PhysicsControls value)
    {
        return super.copy(value);
    }

    @Override
    public PhysicsControls interpolate(Keyframe<PhysicsControls> preA, Keyframe<PhysicsControls> a, Keyframe<PhysicsControls> b, Keyframe<PhysicsControls> postB, IInterp interpolation, float x)
    {
        return super.interpolate(preA, a, b, postB, interpolation, x);
    }

    @Override
    public PhysicsControls interpolate(PhysicsControls preA, PhysicsControls a, PhysicsControls b, PhysicsControls postB, IInterp interpolation, float x)
    {
        return super.interpolate(preA, a, b, postB, interpolation, x);
    }
}
