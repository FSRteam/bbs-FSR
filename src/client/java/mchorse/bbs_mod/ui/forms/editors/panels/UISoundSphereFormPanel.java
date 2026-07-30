package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

public class UISoundSphereFormPanel extends UIAbstractSoundFormPanel<SoundSphereForm>
{
    public UITrackpad radius;

    public UISoundSphereFormPanel(UIForm editor)
    {
        super(editor);

        this.radius = new UITrackpad((v) -> this.form.radius.set(v.floatValue()));
        this.radius.tooltip(UIKeys.FORMS_EDITORS_SOUND_RADIUS_TOOLTIP);

        this.addSourceOptions();
        this.options.add(UI.label(UIKeys.FORMS_EDITORS_SOUND_SHAPE).marginTop(UIConstants.SECTION_GAP), this.radius);
        this.addFalloffOptions();
        this.addReflectionOptions();
        this.addVisualizationOptions();
    }

    @Override
    public void startEdit(SoundSphereForm form)
    {
        super.startEdit(form);

        this.radius.setValue(form.radius.get());
        this.radius.limit(form.radius);
    }

    @Override
    public void syncShapeFromGuide(AbstractSoundForm form)
    {
        if (form == this.form && form instanceof SoundSphereForm sphere)
        {
            this.radius.setValue(sphere.radius.get());
        }
    }
}
