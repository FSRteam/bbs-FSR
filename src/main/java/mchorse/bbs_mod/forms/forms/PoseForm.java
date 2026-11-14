package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;

/**
 * Form capability for editable poses and per-bone animation tracks.
 */
public interface PoseForm extends IPosedForm
{
    public ValueBoolean getBoneTracks();

    @Override
    default boolean hasBoneTracks()
    {
        return this.getBoneTracks().get();
    }
}
