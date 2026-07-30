package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;

public class UISoundConeFormPanel extends UIAbstractSoundFormPanel<SoundConeForm>
{
    public UITrackpad range;
    public UITrackpad outerAngle;
    public UITrackpad innerAngle;
    public UITrackpad outerGain;

    public UISoundConeFormPanel(UIForm editor)
    {
        super(editor);

        this.range = new UITrackpad((v) -> this.form.range.set(v.floatValue()));
        this.range.tooltip(UIKeys.FORMS_EDITORS_SOUND_RANGE_TOOLTIP);

        this.outerAngle = new UITrackpad((v) ->
        {
            this.form.outerAngle.set(v.floatValue());

            /* Keep the inner cone inside the outer one, and show it moving —
             * the acoustics clamp this too, so leaving the field stale would
             * make the panel disagree with what is heard */
            if (this.form.innerAngle.get() > v.floatValue())
            {
                this.form.innerAngle.set(v.floatValue());
                this.innerAngle.setValue(v.floatValue());
            }
        });
        this.outerAngle.tooltip(UIKeys.FORMS_EDITORS_SOUND_OUTER_ANGLE);

        this.innerAngle = new UITrackpad((v) ->
        {
            float clamped = Math.min(v.floatValue(), this.form.outerAngle.get());

            this.form.innerAngle.set(clamped);

            if (clamped != v.floatValue())
            {
                this.innerAngle.setValue(clamped);
            }
        });
        this.innerAngle.tooltip(UIKeys.FORMS_EDITORS_SOUND_INNER_ANGLE_TOOLTIP);

        this.outerGain = new UITrackpad((v) -> this.form.outerGain.set(v.floatValue()));
        this.outerGain.tooltip(UIKeys.FORMS_EDITORS_SOUND_OUTER_GAIN_TOOLTIP);

        this.addSourceOptions();
        this.options.add(UI.label(UIKeys.FORMS_EDITORS_SOUND_SHAPE).marginTop(UIConstants.SECTION_GAP),
            this.range, UI.row(this.innerAngle, this.outerAngle), this.outerGain);
        this.addFalloffOptions();
        this.addReflectionOptions();
        this.addVisualizationOptions();
    }

    @Override
    public void startEdit(SoundConeForm form)
    {
        super.startEdit(form);

        this.range.setValue(form.range.get());
        this.range.limit(form.range);
        this.outerAngle.setValue(form.outerAngle.get());
        this.outerAngle.limit(form.outerAngle);
        this.innerAngle.setValue(form.innerAngle.get());
        this.innerAngle.limit(form.innerAngle);
        this.outerGain.setValue(form.outerGain.get());
        this.outerGain.limit(form.outerGain);
    }

    @Override
    public void syncShapeFromGuide(AbstractSoundForm form)
    {
        if (form == this.form && form instanceof SoundConeForm cone)
        {
            this.range.setValue(cone.range.get());
            this.outerAngle.setValue(cone.outerAngle.get());
            this.innerAngle.setValue(cone.innerAngle.get());
        }
    }
}
