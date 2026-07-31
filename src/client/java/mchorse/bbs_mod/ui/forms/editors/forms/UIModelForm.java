package mchorse.bbs_mod.ui.forms.editors.forms;

import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.panels.UIActionsFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelConstraintsFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelIKFormPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIModelPhysicsFormPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public class UIModelForm extends UIPoseForm<ModelForm>
{
    public UIModelFormPanel modelPanel;

    public UIModelForm()
    {
        this.modelPanel = new UIModelFormPanel(this);
        this.setupPosePanel(this.modelPanel);
        this.defaultPanel = this.modelPanel;

        this.registerPanel(this.defaultPanel, UIKeys.FORMS_EDITORS_MODEL_POSE, Icons.POSE);
        this.registerPanel(new UIModelIKFormPanel(this), UIKeys.FORMS_EDITORS_MODEL_IK, Icons.LIMB);
        this.registerPanel(new UIModelPhysicsFormPanel(this), UIKeys.FORMS_EDITORS_MODEL_PHYSICS_TITLE, Icons.DROP);
        this.registerPanel(new UIModelConstraintsFormPanel(this), UIKeys.FORMS_EDITORS_MODEL_CONSTRAINTS_TITLE, Icons.LOCKED);
        this.registerPanel(new UIActionsFormPanel(this), UIKeys.FORMS_EDITORS_ACTIONS_TITLE, Icons.MORE);
        this.registerDefaultPanels();

        this.defaultPanel.keys().register(Keys.FORMS_PICK_TEXTURE, () ->
        {
            if (this.view != this.modelPanel)
            {
                this.setPanel(this.modelPanel);
            }

            this.modelPanel.pick.clickItself();
        });
    }

    @Override
    public boolean toggleBoneSelection(String bone)
    {
        /* IK, physics and constraint panels own their own single-bone lists. A
         * viewport click must update the active property panel before falling
         * back to the pose editor's multi-selection; otherwise Ctrl-clicking a
         * model bone silently changes the hidden pose list while the visible
         * procedural panel keeps showing the previous bone's properties. */
        if (this.view != null && this.view != this.modelPanel && this.view.pickBoneInList(bone))
        {
            return true;
        }

        return super.toggleBoneSelection(bone);
    }

    @Override
    public void pickBoneFromViewport(String bone, Class<?> preferredPanel)
    {
        /* Use the live panel instance first. UIFormEditor may rebuild the form
         * editor while resolving the picked form, and class-based restoration is
         * only a fallback; the visible model panel is the authoritative target. */
        if (this.view != null && this.view.pickBoneInList(bone))
        {
            return;
        }

        super.pickBoneFromViewport(bone, preferredPanel);
    }

    @Override
    protected String getTransformBoneOverride()
    {
        return this.view == null || this.view == this.modelPanel ? "" : this.view.getSelectedBone();
    }
}
