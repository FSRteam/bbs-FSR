package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeGeometry;
import mchorse.bbs_mod.forms.forms.sound.SoundFalloff;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.renderers.sound.SoundGuideInteraction;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.SoundKeyframeFactory;
import mchorse.bbs_mod.utils.resources.LinkUtils;

import java.util.function.Consumer;

/** Property panel for the five grouped sound-form keyframe tracks. */
public class UISoundKeyframeFactory extends UIKeyframeFactory<SoundKeyframeValue>
{
    private final SoundKeyframeValue.Group group;
    private final AbstractSoundForm form;
    private UITrackpad shapeExtent;
    private UITrackpad shapeInner;
    private UITrackpad shapeOuter;
    private UITrackpad shapeGain;

    public UISoundKeyframeFactory(Keyframe<SoundKeyframeValue> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        SoundKeyframeFactory factory = (SoundKeyframeFactory) keyframe.getFactory();
        UIKeyframeSheet sheet = editor.getGraph().getSheet(keyframe);

        this.group = factory.getGroup();
        this.form = sheet != null && sheet.form instanceof AbstractSoundForm sound ? sound : null;

        switch (this.group)
        {
            case SOUND -> this.addSoundControls();
            case SHAPE -> this.addShapeControls();
            case VISUALIZATION -> this.addVisualizationControls();
            case FALLOFF -> this.addFalloffControls();
            case REFLECTIONS -> this.addReflectionControls();
        }

        if (this.group == SoundKeyframeValue.Group.SHAPE)
        {
            SoundGuideInteraction.bindKeyframePanel(this);
        }
    }

    private void addSoundControls()
    {
        SoundKeyframeValue value = this.keyframe.getValue();
        UIButton audio = new UIButton(UIKeys.FORMS_EDITORS_SOUND_AUDIO, (b) ->
        {
            UISoundOverlayPanel panel = new UISoundOverlayPanel((link) ->
                this.edit((v) -> v.audio = LinkUtils.copy(link)), this.getContext());

            UIOverlay.addOverlay(this.getContext(), panel.set(value.audio));
        });
        UIToggle playing = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_PLAYING, (b) -> this.edit((v) -> v.playing = b.getValue()));
        UITrackpad volume = new UITrackpad((v) -> this.edit((s) -> s.volume = v.floatValue()));
        UITrackpad pitch = new UITrackpad((v) -> this.edit((s) -> s.pitch = v.floatValue()));
        UIToggle looping = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_LOOPING, (b) -> this.edit((v) -> v.looping = b.getValue()));
        UITrackpad startOffset = new UITrackpad((v) -> this.edit((s) -> s.startOffset = v.floatValue()));

        playing.setValue(value.playing);
        looping.setValue(value.looping);
        volume.setValue(value.volume);
        pitch.setValue(value.pitch);
        startOffset.setValue(value.startOffset);

        if (this.form != null)
        {
            volume.limit(this.form.volume);
            pitch.limit(this.form.pitch);
            startOffset.limit(this.form.startOffset);
        }

        this.scroll.add(UI.column(
            audio,
            playing,
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_VOLUME, volume).marginTop(UIConstants.SECTION_GAP),
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_PITCH, pitch),
            looping,
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_START_OFFSET, startOffset).marginTop(UIConstants.SECTION_GAP)
        ));
    }

    private void addShapeControls()
    {
        SoundKeyframeValue value = this.keyframe.getValue();
        UITrackpad extent = new UITrackpad((v) -> this.edit((s) -> s.extent = v.floatValue()));

        this.shapeExtent = extent;

        extent.setValue(value.extent);

        if (this.form instanceof SoundSphereForm sphere)
        {
            extent.limit(sphere.radius);
            this.scroll.add(UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_RADIUS, extent));

            return;
        }

        if (this.form instanceof SoundConeForm cone)
        {
            UITrackpad inner = new UITrackpad((v) -> this.edit((s) ->
                s.innerAngle = SoundConeGeometry.clampInnerAngle(v.floatValue(), s.outerAngle)));
            UITrackpad outer = new UITrackpad((v) ->
            {
                this.edit((s) ->
                {
                    s.outerAngle = v.floatValue();
                    s.innerAngle = SoundConeGeometry.clampInnerAngle(s.innerAngle, s.outerAngle);
                });
                inner.setValue(this.keyframe.getValue().innerAngle);
            });
            UITrackpad gain = new UITrackpad((v) -> this.edit((s) -> s.outerGain = v.floatValue()));

            this.shapeInner = inner;
            this.shapeOuter = outer;
            this.shapeGain = gain;

            extent.limit(cone.range);
            inner.limit(cone.innerAngle);
            outer.limit(cone.outerAngle);
            gain.limit(cone.outerGain);
            inner.setValue(value.innerAngle);
            outer.setValue(value.outerAngle);
            gain.setValue(value.outerGain);

            this.scroll.add(UI.column(
                UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_RANGE, extent),
                UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_INNER_ANGLE, inner).marginTop(UIConstants.SECTION_GAP),
                UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_OUTER_ANGLE, outer),
                UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_OUTER_GAIN, gain)
            ));
        }
    }

    /** Keep the open shape-keyframe panel aligned with a world-guide drag. */
    public void syncShapeFromGuide(Keyframe<SoundKeyframeValue> keyframe, SoundKeyframeValue value)
    {
        if (this.keyframe != keyframe || this.group != SoundKeyframeValue.Group.SHAPE || this.shapeExtent == null)
        {
            return;
        }

        this.shapeExtent.setValue(value.extent);

        if (this.shapeInner != null)
        {
            this.shapeInner.setValue(value.innerAngle);
            this.shapeOuter.setValue(value.outerAngle);
            this.shapeGain.setValue(value.outerGain);
        }
    }

    private void addVisualizationControls()
    {
        SoundKeyframeValue value = this.keyframe.getValue();
        UIToggle guide = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_SHOW_GUIDE, (b) -> this.edit((v) -> v.showGuide = b.getValue()));
        UIColor color = new UIColor((c) -> this.edit((v) -> v.guideColor = Color.rgba(c))).withAlpha();

        guide.setValue(value.showGuide);
        color.setColor(value.guideColor.getARGBColor());
        this.scroll.add(UI.column(guide,
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_GUIDE_COLOR, color).marginTop(UIConstants.SECTION_GAP)));
    }

    private void addFalloffControls()
    {
        SoundKeyframeValue value = this.keyframe.getValue();
        UICirculate falloff = new UICirculate((b) -> this.edit((v) -> v.falloff = SoundFalloff.values()[b.getValue()].id));
        UITrackpad refDistance = new UITrackpad((v) -> this.edit((s) -> s.refDistance = v.floatValue()));
        UITrackpad rolloff = new UITrackpad((v) -> this.edit((s) -> s.rolloff = v.floatValue()));
        UITrackpad absorption = new UITrackpad((v) -> this.edit((s) -> s.airAbsorption = v.floatValue()));

        falloff.addLabel(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_INVERSE);
        falloff.addLabel(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_LINEAR);
        falloff.addLabel(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_EXPONENTIAL);
        falloff.setValue(SoundFalloff.fromId(value.falloff).ordinal());
        refDistance.setValue(value.refDistance);
        rolloff.setValue(value.rolloff);
        absorption.setValue(value.airAbsorption);

        if (this.form != null)
        {
            refDistance.limit(this.form.refDistance);
            rolloff.limit(this.form.rolloff);
            absorption.limit(this.form.airAbsorption);
        }

        this.scroll.add(UI.column(
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_FALLOFF_MODEL, falloff),
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_REF_DISTANCE, refDistance).marginTop(UIConstants.SECTION_GAP),
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_ROLLOFF, rolloff),
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_AIR_ABSORPTION, absorption)
        ));
    }

    private void addReflectionControls()
    {
        SoundKeyframeValue value = this.keyframe.getValue();
        UIToggle enabled = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_REFLECTIONS_ENABLED, (b) -> this.edit((v) -> v.reflections = b.getValue()));
        UITrackpad count = new UITrackpad((v) -> this.edit((s) -> s.reflectionCount = v.intValue()));
        UITrackpad decay = new UITrackpad((v) -> this.edit((s) -> s.reflectionDecay = v.floatValue()));
        UIToggle blocks = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_BLOCK_REFLECTIONS, (b) -> this.edit((v) -> v.blockReflections = b.getValue()));
        UIToggle entities = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_ENTITY_REFLECTIONS, (b) -> this.edit((v) -> v.entityReflections = b.getValue()));
        UIToggle throughBlocks = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_PASS_THROUGH_BLOCKS, (b) -> this.edit((v) -> v.passThroughBlocks = b.getValue()));
        UIToggle throughEntities = new UIToggle(UIKeys.FORMS_EDITORS_SOUND_PASS_THROUGH_ENTITIES, (b) -> this.edit((v) -> v.passThroughEntities = b.getValue()));

        enabled.setValue(value.reflections);
        blocks.setValue(value.blockReflections);
        entities.setValue(value.entityReflections);
        throughBlocks.setValue(value.passThroughBlocks);
        throughEntities.setValue(value.passThroughEntities);
        count.integer().setValue(value.reflectionCount);
        decay.setValue(value.reflectionDecay);

        if (this.form != null)
        {
            count.limit(this.form.reflectionCount);
            decay.limit(this.form.reflectionDecay);
        }

        this.scroll.add(UI.column(
            enabled,
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_REFLECTION_COUNT, count).marginTop(UIConstants.SECTION_GAP),
            UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_REFLECTION_DECAY, decay),
            blocks,
            entities,
            throughBlocks,
            throughEntities
        ));
    }

    private void edit(Consumer<SoundKeyframeValue> consumer)
    {
        UIReplaysEditorUtils.forEachSelectedKeyframe(this.editor, this.keyframe, (Keyframe<SoundKeyframeValue> selected) ->
        {
            selected.preNotify();
            consumer.accept(selected.getValue());
            selected.postNotify();
        });
    }
}
