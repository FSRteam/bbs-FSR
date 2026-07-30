package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.panels.UIMobFormPanel;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class UIMobForm extends UIPoseForm<MobForm>
{
    public UIMobFormPanel mobPanel;

    public UIMobForm()
    {
        super();

        this.mobPanel = new UIMobFormPanel(this);
        this.setupPosePanel(this.mobPanel);
        this.defaultPanel = this.mobPanel;

        this.registerPanel(this.defaultPanel, UIKeys.FORMS_EDITORS_MOB_TITLE, Icons.MORPH);
        this.registerDefaultPanels();
    }

    @Override
    public Matrix3f getTranslateJacobian(UIPropTransform transform, float transition)
    {
        String bone = this.getPoseEditor().groups.list.getCurrentFirst();

        if (!this.editsModelPartTranslate(transform) || bone == null || bone.isEmpty())
        {
            return null;
        }

        MatrixCacheEntry entry = this.getMatrixEntry(transition, this.bonePath());
        Matrix4f origin = entry.origin();

        return origin == null ? null : GizmoDrag.computeModelPartTranslateJacobian(origin);
    }

    /**
     * Only the pose editor's transform drives a vanilla ModelPart; the form's own transform stays in
     * blocks. Resolved through the pose editor directly rather than
     * {@link #getEditableTransform()}, which switches the visible panel as a side effect.
     */
    @Override
    public boolean editsModelPartTranslate(UIPropTransform transform)
    {
        return transform != null && transform == this.getPoseEditor().transform;
    }
}
