package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.function.Consumer;

public class UITransformKeyframeFactory extends UIKeyframeFactory<Transform>
{
    public UIPropTransform transform;

    public UITransformKeyframeFactory(Keyframe<Transform> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.transform = new UIPoseTransforms(this);
        this.transform.enableHotkeys();
        this.transform.setTransform(keyframe.getValue());

        this.scroll.add(this.transform);
    }

    public static class UIPoseTransforms extends UIKeyframePropTransform
    {
        private UITransformKeyframeFactory editor;

        public UIPoseTransforms(UITransformKeyframeFactory editor)
        {
            this.editor = editor;
        }

        @Override
        protected void applyToSelection(Consumer<Transform> consumer)
        {
            apply(this.editor.editor, this.editor.keyframe, consumer);
        }

        @Override
        protected void applyDuringRecording(int tick, Consumer<Transform> consumer)
        {
            applyRecording(this.editor.editor, this.editor.keyframe, tick, consumer);
        }

        @Override
        protected Transform getRecordedTransform(int tick)
        {
            UIKeyframeSheet sheet = this.editor.editor.getGraph().getSheet(this.editor.keyframe);
            Keyframe<Transform> recorded = UIReplaysEditorUtils.ensureKeyframe(sheet, tick);

            return recorded == null ? null : recorded.getValue();
        }

        public static void applyRecording(UIKeyframes editor, Keyframe keyframe, int tick, Consumer<Transform> consumer)
        {
            UIReplaysEditorUtils.forEachRecordedKeyframe(editor, keyframe, tick, (recorded) ->
            {
                Transform transform = (Transform) recorded.getValue();

                recorded.preNotify();
                consumer.accept(transform);
                recorded.postNotify();
            });
        }

        public static void apply(UIKeyframes editor, Keyframe keyframe, Consumer<Transform> consumer)
        {
            UIReplaysEditorUtils.forEachSelectedKeyframe(editor, keyframe, (selected) ->
            {
                Transform transform = (Transform) selected.getValue();

                selected.preNotify();
                consumer.accept(transform);
                selected.postNotify();
            });
        }
    }
}
