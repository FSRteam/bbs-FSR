package mchorse.bbs_mod.forms;

import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;

/** Compatibility name for addons using the original category visibility API. */
public class VisibilityManager extends CategoryPreferences
{
    public ValueBoolean get(String id)
    {
        return this.visible(id);
    }

    public ValueBoolean get(String id, boolean defaultValue)
    {
        return this.visible(id, defaultValue);
    }
}
