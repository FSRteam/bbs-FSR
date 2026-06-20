package mchorse.bbs_mod.utils.pose;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.presets.DataManager;

public class ModelPhysicsManager extends DataManager
{
    public static final ModelPhysicsManager INSTANCE = new ModelPhysicsManager();

    @Override
    protected Link getFile(String group)
    {
        return Link.assets(ModelManager.MODELS_PREFIX + group + "/physics_presets.json");
    }
}
