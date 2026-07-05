package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.utils.IWorldTransformProvider;
import org.joml.Matrix4f;

/**
 * World-transform source for the model form editor's pose editor. There is no scene here, so "world"
 * is the form preview's own space — stable, since the preview root doesn't move. It hands back the
 * selected pose bone's full matrix in that space via {@link UIModelForm#getOriginMatrix}, the same
 * sample the pose gizmo drag uses; because {@code ModelFormRenderer.collectMatrices} re-applies the
 * live {@code form.pose} when it builds the matrices, a nudge to the pose shows up in the next sample
 * (what the world paste's finite differences rely on).
 */
public class FormBoneWorldProvider implements IWorldTransformProvider
{
    private final UIModelForm form;

    public FormBoneWorldProvider(UIModelForm form)
    {
        this.form = form;
    }

    @Override
    public boolean getWorldMatrix(Matrix4f out)
    {
        UIFormEditor editor = this.form.editor;
        String bone = this.form.modelPanel.poseEditor.getGroup();

        if (editor == null || bone == null || bone.isEmpty())
        {
            return false;
        }

        Matrix4f matrix = this.form.getOriginMatrix(editor.getSamplingTick());

        if (matrix == null)
        {
            return false;
        }

        out.set(matrix);

        return true;
    }
}
