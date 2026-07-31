package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.cubic.glint.GlintControl;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.forms.renderers.BoneHierarchy;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.pose.UIBoneList;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Dedicated per-bone enchantment-layer editor, separate from the pose keyframe panel. */
public class UIGlintKeyframeFactory extends UIKeyframeFactory<GlintControls>
{
    public UIBoneList bones;
    public UICirculate mode;
    public UIColor glintColor;
    public UITrackpad speed;
    public UIPropTransform transform;

    private Form form;
    private PoseForm poseForm;
    private BoneHierarchy hierarchy = BoneHierarchy.EMPTY;
    private boolean syncing;

    public UIGlintKeyframeFactory(Keyframe<GlintControls> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        UIKeyframeSheet sheet = editor.getGraph().getSheet(keyframe);

        if (sheet != null && sheet.form instanceof PoseForm poseForm)
        {
            this.form = sheet.form;
            this.poseForm = poseForm;
            this.hierarchy = FormUtilsClient.getBoneHierarchy(this.form);
        }

        this.bones = new UIBoneList((selected) -> this.display());
        this.bones.list.h(UIConstants.LIST_ITEM_HEIGHT * 7);
        this.bones.setSource(this.hierarchy.getBoneIds(), this.hierarchy.getLabels(false), false);
        this.bones.onFiltered = (reset) ->
        {
            List<String> visible = this.bones.list.getList();

            if (!visible.isEmpty())
            {
                this.bones.list.setCurrentScroll(visible.get(0));
            }

            this.display();
        };

        this.mode = new UICirculate((c) -> this.edit((control) -> control.mode = c.getValue()));
        this.mode.addLabel(UIKeys.POSE_CONTEXT_GLINT_OFF);
        this.mode.addLabel(UIKeys.POSE_CONTEXT_GLINT_FULL);
        this.mode.addLabel(UIKeys.POSE_CONTEXT_GLINT_EDGE);
        this.mode.addLabel(UIKeys.POSE_CONTEXT_GLINT_VANILLA);
        this.mode.tooltip(UIKeys.POSE_CONTEXT_GLINT_TOOLTIP).h(UIConstants.CONTROL_HEIGHT);

        this.glintColor = new UIColor((color) -> this.edit((control) -> control.color.set(color)));
        this.glintColor.withAlpha().tooltip(UIKeys.POSE_CONTEXT_GLINT_COLOR_TOOLTIP);

        this.speed = new UITrackpad((value) -> this.edit((control) -> control.speed = value.floatValue()));
        this.speed.limit(-4D, 4D).increment(0.1D).values(0.1D, 0.05D, 0.5D);
        this.speed.tooltip(UIKeys.POSE_CONTEXT_GLINT_SPEED_TOOLTIP);

        this.transform = new UIGlintTransforms(this);
        this.transform.enableHotkeys();

        Consumer<Consumer<GlintControl>> applyChildren = (consumer) -> this.editBones(this.descendants(), consumer);

        this.mode.context((menu) -> menu.action(Icons.DOWNLOAD, UIKeys.POSE_CONTEXT_APPLY,
            () -> applyChildren.accept((control) -> control.mode = this.mode.getValue())));
        this.glintColor.context((menu) -> menu.action(Icons.DOWNLOAD, UIKeys.POSE_CONTEXT_APPLY,
            () -> applyChildren.accept((control) -> control.color.set(this.glintColor.picker.color.getARGBColor()))));
        this.speed.context((menu) -> menu.action(Icons.DOWNLOAD, UIKeys.POSE_CONTEXT_APPLY,
            () -> applyChildren.accept((control) -> control.speed = (float) this.speed.getValue())));

        UISection section = new UISection(UIKeys.POSE_CONTEXT_GLINT_LAYER);

        section.fields.add(
            UI.row(this.mode, this.glintColor),
            UI.labelRow(UIKeys.POSE_CONTEXT_GLINT_SPEED, this.speed),
            this.transform
        );
        section.context((menu) -> menu.action(Icons.DOWNLOAD, UIKeys.POSE_CONTEXT_APPLY, () ->
        {
            Transform source = this.transform.getTransform();

            if (source != null)
            {
                applyChildren.accept((control) -> control.transform.copy(source));
            }
        }));

        this.scroll.add(
            UI.label(UIKeys.FORMS_EDITOR_BONE),
            this.bones,
            section.marginTop(UIConstants.SECTION_GAP)
        );

        this.bones.filter(true);
    }

    private void display()
    {
        String bone = this.primaryBone();
        boolean enabled = bone != null;

        this.mode.setEnabled(enabled);
        this.glintColor.setEnabled(enabled);
        this.speed.setEnabled(enabled);
        this.transform.setEnabled(enabled);

        this.syncing = true;

        try
        {
            GlintControl control = enabled ? this.displayControl(bone) : GlintControl.DEFAULT;

            this.mode.setValue((int) control.mode);
            this.glintColor.setColor(control.color.getARGBColor());
            this.speed.setValue(control.speed);
            this.transform.setTransform(enabled ? control.transform : null);
        }
        finally
        {
            this.syncing = false;
        }
    }

    private GlintControl displayControl(String bone)
    {
        GlintControl control = this.keyframe.getValue().controls.get(bone);

        return control == null ? this.baseControl(bone) : control;
    }

    private GlintControl baseControl(String bone)
    {
        GlintControl control = new GlintControl();

        if (this.poseForm != null)
        {
            control.copy(this.poseForm.getPose().getOriginalValue().get(bone));
        }

        return control;
    }

    private String primaryBone()
    {
        List<String> selected = this.bones.list.getCurrent();

        return selected.isEmpty() ? null : selected.get(0);
    }

    private List<String> selectedBones()
    {
        return new ArrayList<>(this.bones.list.getCurrent());
    }

    private List<String> descendants()
    {
        LinkedHashSet<String> descendants = new LinkedHashSet<>();

        for (String bone : this.selectedBones())
        {
            for (BoneHierarchy.Bone child : this.hierarchy.getDescendants(bone))
            {
                descendants.add(child.id());
            }
        }

        return new ArrayList<>(descendants);
    }

    private void edit(Consumer<GlintControl> consumer)
    {
        if (!this.syncing)
        {
            this.editBones(this.selectedBones(), consumer);
        }
    }

    private void editBones(List<String> boneNames, Consumer<GlintControl> consumer)
    {
        if (boneNames.isEmpty())
        {
            return;
        }

        UIReplaysEditorUtils.forEachSelectedKeyframe(this.editor, this.keyframe, (selected) ->
        {
            GlintControls controls = (GlintControls) selected.getValue();

            selected.preNotify();

            for (String bone : boneNames)
            {
                boolean fresh = !controls.controls.containsKey(bone);
                GlintControl control = controls.get(bone);

                if (fresh)
                {
                    control.copy(this.baseControl(bone));
                }

                consumer.accept(control);
            }

            selected.postNotify();
        });
    }

    private static class UIGlintTransforms extends UIKeyframePropTransform
    {
        private final UIGlintKeyframeFactory editor;

        public UIGlintTransforms(UIGlintKeyframeFactory editor)
        {
            this.editor = editor;
        }

        @Override
        protected void applyToSelection(Consumer<Transform> consumer)
        {
            this.editor.edit((control) -> consumer.accept(control.transform));
        }

        @Override
        protected void applyDuringRecording(int tick, Consumer<Transform> consumer)
        {
            List<String> bones = this.editor.selectedBones();

            UIReplaysEditorUtils.forEachRecordedKeyframe(this.editor.editor, this.editor.keyframe, tick, (recorded) ->
            {
                GlintControls controls = (GlintControls) recorded.getValue();

                recorded.preNotify();

                for (String bone : bones)
                {
                    consumer.accept(controls.get(bone).transform);
                }

                recorded.postNotify();
            });
        }

        @Override
        protected Transform getRecordedTransform(int tick)
        {
            UIKeyframeSheet sheet = this.editor.editor.getGraph().getSheet(this.editor.keyframe);
            Keyframe<GlintControls> recorded = UIReplaysEditorUtils.ensureKeyframe(sheet, tick);
            String bone = this.editor.primaryBone();

            return recorded == null || bone == null ? null : recorded.getValue().get(bone).transform;
        }
    }
}
