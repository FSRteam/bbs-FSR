package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compatibility call shape for historical FSR numerical probes. */
@Deprecated
final class ModelIKDlsApplier
{
    private ModelIKDlsApplier()
    {}

    public static void apply(IModel model, List<ModelIKCache.CompiledChain> chains, Map<String, ModelIKConfig.JointDoF> joints, Map<String, Vector3f> targets, Map<String, Vector3f> poles, Map<String, Float> targetWeights, Map<String, Float> poleWeights, Map<String, IKControl> controls)
    {
        apply(model, chains, joints, targets, poles, targetWeights, poleWeights, controls, null, null);
    }

    public static void apply(IModel model, List<ModelIKCache.CompiledChain> chains, Map<String, ModelIKConfig.JointDoF> joints, Map<String, Vector3f> targets, Map<String, Vector3f> poles, Map<String, Float> targetWeights, Map<String, Float> poleWeights, Map<String, IKControl> controls, List<ModelIKChainWorkspace> scratch, Map<String, BoneConstraint> limits)
    {
        Map<String, JointDoF> typed = new HashMap<>();

        if (joints != null)
        {
            joints.forEach((id, value) -> typed.put(id, ModelIKCache.fromLegacyJoint(value)));
        }

        ModelIKApplier.apply(model, chains, typed, targets, poles, targetWeights, poleWeights, controls, scratch, limits);
    }
}
