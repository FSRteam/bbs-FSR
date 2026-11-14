package mchorse.bbs_mod.cubic;

import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.utils.pose.Pose;
import java.util.Map;

public interface IModelInstance
{
    public IModel getModel();

    public Pose getSneakingPose();

    public Animations getAnimations();

    default Map<String, String> getProceduralBones()
    {
        return Map.of();
    }

    default Pose getDefaultPose()
    {
        return null;
    }
}
