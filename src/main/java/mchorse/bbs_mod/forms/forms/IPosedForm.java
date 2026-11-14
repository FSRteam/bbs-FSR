package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.settings.values.core.ValuePose;

/** The shared pose capability used by bone tracks, whole-pose tracks and gizmos. */
public interface IPosedForm
{
    public ValuePose getPose();

    public ValuePose getPoseOverlay();

    public boolean hasBoneTracks();
}
