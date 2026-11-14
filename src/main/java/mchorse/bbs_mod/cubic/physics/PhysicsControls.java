package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.cubic.chains.ChainControls;

/**
 * The physics track's keyframe value: {@link PhysicsControl} scalars keyed by the chain's root bone.
 */
public class PhysicsControls extends ChainControls<PhysicsControl, PhysicsControls>
{
    /** Typed entry points retain the method descriptors used by existing addons. */
    @Override
    public PhysicsControl get(String bone)
    {
        return super.get(bone);
    }

    @Override
    public PhysicsControls copy()
    {
        return super.copy();
    }

    public void copy(PhysicsControls other)
    {
        super.copy(other);
    }

    @Override
    protected PhysicsControls createControls()
    {
        return new PhysicsControls();
    }

    @Override
    protected PhysicsControl createControl()
    {
        return new PhysicsControl();
    }

    @Override
    protected String getDataKey()
    {
        return "physics";
    }
}
