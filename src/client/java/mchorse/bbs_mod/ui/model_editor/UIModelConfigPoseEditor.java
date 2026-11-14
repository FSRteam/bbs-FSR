package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIModelPoseEditor;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.pose.Pose;
import java.util.ArrayList;
import java.util.List;

/** Shares the form pose editor's selection and delta edits with the model config's undo owner. */
public class UIModelConfigPoseEditor extends UIModelPoseEditor
{
    public static final int WIDE_WIDTH = (UIConstants.VALUE_WIDTH + 80) * 2;

    private ValuePose valuePose;
    private BaseType gestureBefore;
    private String poseGroup = "";

    public UIModelConfigPoseEditor()
    {}

    @Override
    public void setValuePose(ValuePose valuePose)
    {
        if (this.valuePose != valuePose && this.transform.isEditing())
        {
            this.transform.rejectChanges();
        }

        this.valuePose = valuePose;
        super.setValuePose(valuePose);
    }

    @Override
    public void poseOnly()
    {
        super.poseOnly();
        this.buildLayout(false);
    }

    public void buildLayout(boolean wide)
    {
        this.removeAll();

        if (wide)
        {
            this.add(UI.row(UI.column(this.boneVisible, this.transform), UI.column(this.groups).expand()).expand());
        }
        else
        {
            this.add(this.groups, this.boneVisible, this.transform);
        }
    }

    @Override
    protected boolean stretchesBoneList()
    {
        return true;
    }

    public UIModelConfigPoseEditor(ValuePose valuePose)
    {
        this.setValuePose(valuePose);
    }

    @Override
    public void setPose(Pose pose, String group)
    {
        this.poseGroup = group;
        super.setPose(pose, group);
    }

    @Override
    protected UIPropTransform createTransformEditor()
    {
        return super.createTransformEditor().callbacks(
            () -> { if (this.gestureBefore == null) this.valuePose.preNotify(); },
            () -> { if (this.gestureBefore == null) this.valuePose.postNotify(); },
            null
        ).gestureCallbacks(() -> this.gestureBefore = this.valuePose.toData().copy(), this::finishGesture);
    }

    private void finishGesture(boolean accept)
    {
        if (this.gestureBefore == null) return;
        BaseType before = this.gestureBefore;
        BaseType after = this.valuePose.toData().copy();
        List<String> selected = new ArrayList<>(this.groups.list.getCurrent());
        this.valuePose.fromData(before);
        if (accept && !before.equals(after))
        {
            this.valuePose.preNotify(IValueListener.FLAG_UNMERGEABLE);
            this.valuePose.fromData(after);
            this.valuePose.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }
        this.gestureBefore = null;
        this.setPose(this.valuePose.get(), this.poseGroup);
        for (int i = 0; i < selected.size(); i++) this.selectBone(selected.get(i), i != 0);
    }

}
