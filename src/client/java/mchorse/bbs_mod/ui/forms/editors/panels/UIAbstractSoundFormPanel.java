package mchorse.bbs_mod.ui.forms.editors.panels;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundFalloff;
import mchorse.bbs_mod.forms.renderers.sound.SoundGuideInteraction;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.editors.forms.UIForm;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * Everything the sphere and cone sound panels share: source, falloff,
 * reflections and visualization. Only the emission shape differs.
 *
 * <p>Subclasses call the {@code add*Options} methods from their own
 * constructor, in the order they want the groups laid out, and slot their
 * shape controls in between. Doing it that way rather than calling an abstract
 * hook from this constructor avoids the usual Java trap where the subclass's
 * fields are still null when the base constructor runs.</p>
 */
public abstract class UIAbstractSoundFormPanel <T extends AbstractSoundForm> extends UIFormPanel<T>
{
    /* Source */
    public UIButton pickAudio;
    public UIToggle playing;
    public UITrackpad volume;
    public UITrackpad pitch;
    public UIToggle looping;
    public UITrackpad startOffset;
    public UIButton preview;

    /* Falloff */
    public UICirculate falloff;
    public UITrackpad refDistance;
    public UITrackpad rolloff;
    public UITrackpad airAbsorption;

    /* Reflections */
    public UIToggle reflections;
    public UITrackpad reflectionCount;
    public UITrackpad reflectionDecay;
    public UIToggle blockReflections;
    public UIToggle entityReflections;
    public UIToggle passThroughBlocks;
    public UIToggle passThroughEntities;

    /* Visualization */
    public UIToggle showGuide;
    public UIColor guideColor;

    public UIAbstractSoundFormPanel(UIForm editor)
    {
        super(editor);

        this.pickAudio = new UIButton(UIKeys.FORMS_EDITORS_SOUND_AUDIO, (b) ->
        {
            UISoundOverlayPanel panel = new UISoundOverlayPanel((l) -> this.form.audio.set(l), this.getContext());

            UIOverlay.addOverlay(this.getContext(), panel.set(this.form.audio.get()));
        });

        this.playing = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_PLAYING, (b) -> this.form.playing.set(b.getValue()));
        this.playing.tooltip(UIKeys.FORMS_EDITORS_SOUND_PLAYING_TOOLTIP);

        this.volume = new UITrackpad((v) -> this.form.volume.set(v.floatValue()));
        this.volume.tooltip(UIKeys.FORMS_EDITORS_SOUND_VOLUME);
        this.pitch = new UITrackpad((v) -> this.form.pitch.set(v.floatValue()));
        this.pitch.tooltip(UIKeys.FORMS_EDITORS_SOUND_PITCH);
        this.looping = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_LOOPING, (b) -> this.form.looping.set(b.getValue()));
        this.startOffset = new UITrackpad((v) -> this.form.startOffset.set(v.floatValue()));
        this.startOffset.tooltip(UIKeys.FORMS_EDITORS_SOUND_START_OFFSET);

        this.preview = new UIButton(UIKeys.FORMS_EDITORS_SOUND_PREVIEW, (b) -> this.previewAudio());

        this.falloff = new UICirculate((b) -> this.form.falloff.set(SoundFalloff.values()[b.getValue()].id));
        this.falloff.addLabel(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_INVERSE);
        this.falloff.addLabel(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_LINEAR);
        this.falloff.addLabel(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_EXPONENTIAL);

        this.refDistance = new UITrackpad((v) -> this.form.refDistance.set(v.floatValue()));
        this.refDistance.tooltip(UIKeys.FORMS_EDITORS_SOUND_REF_DISTANCE_TOOLTIP);
        this.rolloff = new UITrackpad((v) -> this.form.rolloff.set(v.floatValue()));
        this.rolloff.tooltip(UIKeys.FORMS_EDITORS_SOUND_ROLLOFF);
        this.airAbsorption = new UITrackpad((v) -> this.form.airAbsorption.set(v.floatValue()));
        this.airAbsorption.tooltip(UIKeys.FORMS_EDITORS_SOUND_AIR_ABSORPTION);

        this.reflections = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_REFLECTIONS_ENABLED, (b) -> this.form.reflections.set(b.getValue()));
        this.reflectionCount = new UITrackpad((v) -> this.form.reflectionCount.set(v.intValue()));
        this.reflectionCount.integer();
        this.reflectionCount.tooltip(UIKeys.FORMS_EDITORS_SOUND_REFLECTION_COUNT);
        this.reflectionDecay = new UITrackpad((v) -> this.form.reflectionDecay.set(v.floatValue()));
        this.reflectionDecay.tooltip(UIKeys.FORMS_EDITORS_SOUND_REFLECTION_DECAY_TOOLTIP);
        this.blockReflections = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_BLOCK_REFLECTIONS,
            (b) -> this.form.blockReflections.set(b.getValue()));
        this.entityReflections = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_ENTITY_REFLECTIONS,
            (b) -> this.form.entityReflections.set(b.getValue()));
        this.passThroughBlocks = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_PASS_THROUGH_BLOCKS,
            (b) -> this.form.passThroughBlocks.set(b.getValue()));
        this.passThroughEntities = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_PASS_THROUGH_ENTITIES,
            (b) -> this.form.passThroughEntities.set(b.getValue()));

        this.showGuide = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_SHOW_GUIDE, (b) -> this.form.showGuide.set(b.getValue()));
        this.guideColor = new UIColor((v) -> this.form.guideColor.set(Color.rgba(v))).withAlpha();
    }

    protected void addSourceOptions()
    {
        this.options.add(UI.label(UIKeys.FORMS_EDITORS_SOUND_SOURCE), this.pickAudio, this.playing,
            UI.row(this.volume, this.pitch), this.looping, this.startOffset, this.preview);
    }

    protected void addFalloffOptions()
    {
        this.options.add(UI.label(UIKeys.FORMS_EDITORS_SOUND_FALLOFF).marginTop(UIConstants.SECTION_GAP),
            this.falloff, this.refDistance, this.rolloff, this.airAbsorption);
    }

    protected void addReflectionOptions()
    {
        this.options.add(UI.label(UIKeys.FORMS_EDITORS_SOUND_REFLECTIONS).marginTop(UIConstants.SECTION_GAP),
            this.reflections, this.reflectionCount, this.reflectionDecay,
            this.blockReflections, this.entityReflections, this.passThroughBlocks, this.passThroughEntities);
    }

    protected void addVisualizationOptions()
    {
        this.options.add(UI.label(UIKeys.FORMS_EDITORS_SOUND_VISUALIZATION).marginTop(UIConstants.SECTION_GAP),
            this.showGuide, this.guideColor);
    }

    /**
     * Plays the clip flat, with no distance or directional attenuation.
     *
     * <p>The editor preview has no world position and no listener, so there is
     * nothing to attenuate against — this answers "is this the right clip?",
     * not "how will it sound in the scene". Spatial behaviour has to be checked
     * with the form placed in the world.</p>
     */
    protected void previewAudio()
    {
        Link link = this.form == null ? null : this.form.audio.get();

        if (link != null && !link.path.isEmpty())
        {
            BBSModClient.getSounds().restartUnique(link);
        }
    }

    /** Refresh only shape controls after an in-viewport guide drag. */
    public void syncShapeFromGuide(AbstractSoundForm form)
    {}

    /** The guide drag may outlive a panel switch; only its original Form may receive the commit. */
    public boolean isEditingForm(AbstractSoundForm form)
    {
        return this.form == form;
    }

    @Override
    public void startEdit(T form)
    {
        super.startEdit(form);
        SoundGuideInteraction.bindFormPanel(this);

        /* Limits come from the fields themselves, so the panel cannot drift out
         * of sync with the ranges declared on the form */
        this.playing.setValue(form.playing.get());
        this.volume.setValue(form.volume.get());
        this.volume.limit(form.volume);
        this.pitch.setValue(form.pitch.get());
        this.pitch.limit(form.pitch);
        this.looping.setValue(form.looping.get());
        this.startOffset.setValue(form.startOffset.get());
        this.startOffset.limit(form.startOffset);

        this.falloff.setValue(SoundFalloff.fromId(form.falloff.get()).ordinal());
        this.refDistance.setValue(form.refDistance.get());
        this.refDistance.limit(form.refDistance);
        this.rolloff.setValue(form.rolloff.get());
        this.rolloff.limit(form.rolloff);
        this.airAbsorption.setValue(form.airAbsorption.get());
        this.airAbsorption.limit(form.airAbsorption);

        this.reflections.setValue(form.reflections.get());
        this.reflectionCount.setValue(form.reflectionCount.get());
        this.reflectionCount.limit(form.reflectionCount);
        this.reflectionDecay.setValue(form.reflectionDecay.get());
        this.reflectionDecay.limit(form.reflectionDecay);
        this.blockReflections.setValue(form.blockReflections.get());
        this.entityReflections.setValue(form.entityReflections.get());
        this.passThroughBlocks.setValue(form.passThroughBlocks.get());
        this.passThroughEntities.setValue(form.passThroughEntities.get());

        this.showGuide.setValue(form.showGuide.get());
        this.guideColor.setColor(form.guideColor.get().getARGBColor());
    }
}
