package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.function.Consumer;

public class UIPoseTransformKeyframeFactory extends UIKeyframeFactory<PoseTransform>
{
    public UISliderTrackpad fix;
    public UIColor color;
    public UIToggle lighting;
    public UISliderTrackpad glow;
    public UIColor overlay;
    public UIToggle boneVisible;
    public UIPropTransform transform;

    public UIPoseTransformKeyframeFactory(Keyframe<PoseTransform> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.transform = new UIPoseTransforms(this);
        this.transform.enableHotkeys();
        this.transform.setTransform(keyframe.getValue());

        this.keys().register(Keys.TRANSFORMATIONS_TOGGLE_FIX, this::toggleFix).category(UIKeys.TRANSFORMS_KEYS_CATEGORY);

        this.fix = new UISliderTrackpad((v) ->
        {
            if (this.transform.getTransform() instanceof PoseTransform)
            {
                UIPoseTransforms.apply(editor, keyframe, (poseT) -> poseT.fix = v.floatValue());
            }
        });
        this.fix.limit(0D, 1D).increment(1D).values(0.1, 0.05D, 0.2D);
        this.fix.tooltip(UIKeys.POSE_CONTEXT_FIX_TOOLTIP);
        this.fix.setValue(keyframe.getValue().fix);

        this.color = new UIColor((c) ->
        {
            if (this.transform.getTransform() instanceof PoseTransform)
            {
                UIPoseTransforms.apply(editor, keyframe, (poseT) -> poseT.color.set(c));
            }
        });
        this.color.withAlpha();
        this.color.setColor(keyframe.getValue().color.getARGBColor());

        this.lighting = new UIToggle(UIKeys.FORMS_EDITORS_GENERAL_LIGHTING, (b) ->
        {
            if (this.transform.getTransform() instanceof PoseTransform)
            {
                UIPoseTransforms.apply(editor, keyframe, (poseT) -> poseT.lighting = b.getValue() ? 0F : 1F);
            }
        });
        this.lighting.h(UIConstants.CONTROL_HEIGHT);
        this.lighting.setValue(keyframe.getValue().lighting == 0F);

        this.glow = new UISliderTrackpad((v) -> UIPoseTransforms.apply(editor, keyframe, (p) -> p.lighting = v.floatValue()));
        this.glow.limit(0D, 1D).increment(0.1D);
        this.glow.setValue(keyframe.getValue().lighting);
        this.overlay = new UIColor((c) -> UIPoseTransforms.apply(editor, keyframe, (p) -> p.overlay.set(c))).withAlpha();
        this.overlay.setColor(keyframe.getValue().overlay.getARGBColor());
        this.boneVisible = new UIToggle(UIKeys.FORMS_EDITORS_GENERAL_VISIBLE, keyframe.getValue().visible,
            (b) -> UIPoseTransforms.apply(editor, keyframe, (p) -> p.visible = b.getValue()));

        /* Same labelRow grid as the pose editor, which this panel mirrors. */
        this.scroll.add(
            UI.labelRow(UIKeys.POSE_CONTEXT_FIX, this.fix),
            UI.labelRow(UIKeys.FORMS_EDITORS_MATERIAL_COLOR, this.color),
            UI.labelRow(UIKeys.FORMS_EDITORS_MATERIAL_OVERLAY, this.overlay),
            UI.labelRow(UIKeys.POSE_CONTEXT_GLOW, this.glow), this.boneVisible,
            this.transform
        );
    }

    @Override
    public void update()
    {
        super.update();
        PoseTransform pose = this.getDisplayValue();
        this.fix.setValue(pose.fix);
        this.color.setColor(pose.color.getARGBColor());
        this.glow.setValue(pose.lighting);
        this.overlay.setColor(pose.overlay.getARGBColor());
        this.boneVisible.setValue(pose.visible);
    }

    private void toggleFix()
    {
        if (!(this.transform.getTransform() instanceof PoseTransform))
        {
            return;
        }
        float next = this.fix.getValue() >= 0.5F ? 0F : 1F;
        this.fix.setValue(next);
        UIPoseTransforms.apply(this.editor, this.keyframe, (poseT) -> poseT.fix = next);
    }

    public static class UIPoseTransforms extends UIKeyframePropTransform
    {
        private UIPoseTransformKeyframeFactory editor;

        public UIPoseTransforms(UIPoseTransformKeyframeFactory editor)
        {
            this.editor = editor;
        }

        @Override
        protected void applyToSelection(Consumer<Transform> consumer)
        {
            apply(this.editor.editor, this.editor.keyframe, (poseT) -> consumer.accept(poseT));
        }

        @Override
        protected void applyDuringRecording(int tick, Consumer<Transform> consumer)
        {
            applyRecording(this.editor.editor, this.editor.keyframe, tick, (poseT) -> consumer.accept(poseT));
        }

        @Override
        protected Transform getRecordedTransform(int tick)
        {
            UIKeyframeSheet sheet = this.editor.editor.getGraph().getSheet(this.editor.keyframe);
            Keyframe<PoseTransform> recorded = UIReplaysEditorUtils.ensureKeyframe(sheet, tick);

            return recorded == null ? null : recorded.getValue();
        }

        @Override
        protected Transform getAutoKeyTransform(float tick)
        {
            UIKeyframeSheet sheet = this.editor.editor.getGraph().getSheet(this.editor.keyframe);
            PoseTransform sampled = (PoseTransform) UIReplaysEditorUtils.sampleKeyframeValue(sheet, tick);
            return sampled == null ? null : sampled;
        }

        public static void applyRecording(UIKeyframes editor, Keyframe keyframe, int tick, Consumer<PoseTransform> consumer)
        {
            UIReplaysEditorUtils.forEachRecordedKeyframe(editor, keyframe, tick, (recorded) ->
            {
                PoseTransform transform = (PoseTransform) recorded.getValue();

                recorded.preNotify();
                consumer.accept(transform);
                recorded.postNotify();
            });
        }

        public static void apply(UIKeyframes editor, Keyframe keyframe, Consumer<PoseTransform> consumer)
        {
            UIReplaysEditorUtils.forEachSelectedKeyframe(editor, keyframe, (selected) ->
            {
                PoseTransform transform = (PoseTransform) selected.getValue();

                selected.preNotify();
                consumer.accept(transform);
                selected.postNotify();
            });
        }
    }
}
