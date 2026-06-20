package mchorse.bbs_mod.utils.pose;

import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.presets.DataManager;

public class ModelConstraintsManager extends DataManager
{
    public static final ModelConstraintsManager INSTANCE = new ModelConstraintsManager();

    @Override
    protected Link getFile(String group)
    {
        return Link.assets(ModelManager.MODELS_PREFIX + group + "/constraints_presets.json");
    }
}
