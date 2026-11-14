package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.IPosedForm;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.ui.forms.editors.panels.widgets.UIModelPoseEditor;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.joml.Vector3f;

/** Exercises the public pose capability without requiring a model or mob implementation. */
public final class PosedFormParityTest
{
    public static void runAll()
    {
        int previous = BBSSettings.recordingPoseOverlays.get();
        try
        {
            BBSSettings.recordingPoseOverlays.set(1);
            AddonPosedForm form = new AddonPosedForm();
            form.syncOverlayTracks();
            check(form.additionalOverlays.size() == 1 && form.get("pose_overlay0") == form.additionalOverlays.getFirst(),
                "The shared form owner did not create an addon pose overlay");
            form.pose.get().getOrCreate("arm").rotate.set(0.1F, 0.2F, 0.3F);
            form.overlay.get().getOrCreate("arm").rotate.set(0.4F, 0.5F, 0.6F);
            form.additionalOverlays.getFirst().get().getOrCreate("arm").rotate.set(0.7F, 0.8F, 0.9F);
            check(FormUtils.additivePoseRotationBase(form.pose, "arm").equals(new Vector3f(1.1F, 1.3F, 1.5F), 1.0E-6F),
                "The additive gizmo base omitted an addon form's pose overlays");

            form.pose.get().getOrCreate("arm").translate.x = 2F;
            FormProperties properties = new FormProperties("properties");
            KeyframeChannel<PoseTransform> bones = properties.getOrCreate(TrackId.bone("", "arm"));
            PoseTransform contribution = new PoseTransform();
            contribution.translate.x = 3F;
            bones.insert(0F, contribution);
            properties.apply(TrackContext.of(form), 0F, 1F);
            properties.apply(TrackContext.of(form), 0F, 1F);
            check(form.pose.get().get("arm").translate.x == 5F
                && form.pose.getOriginalValue().get("arm").translate.x == 2F,
                "Bone playback ignored an addon pose capability or accumulated its previous frame");

            ValuePose value = new ValuePose("pose", new Pose());
            PoseTransform bone = value.get().getOrCreate("arm");
            int[] before = {0};
            int[] after = {0};
            value.preCallback((changed, flag) -> before[0]++);
            value.postCallback((changed, flag) -> after[0]++);
            PoseEditor editor = new PoseEditor();
            editor.setValuePose(value);
            editor.editMaterial(bone);
            check(!bone.visible && bone.overlay.getARGBColor() == 0x80123456 && bone.lighting == 0.35F,
                "The model pose editor did not write its bone material fields");
            check(before[0] == 3 && after[0] == 3,
                "Bone visibility, tint or lighting edits bypassed undo and live pose notifications");
        }
        finally
        {
            BBSSettings.recordingPoseOverlays.set(previous);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class PoseEditor extends UIModelPoseEditor
    {
        private void editMaterial(PoseTransform bone)
        {
            this.setVisible(bone, false);
            this.setOverlay(bone, 0x80123456);
            this.setGlow(bone, 0.35F);
        }
    }

    private static final class AddonPosedForm extends Form implements IPosedForm
    {
        private final ValuePose pose = new ValuePose("pose", new Pose());
        private final ValuePose overlay = new ValuePose("pose_overlay", new Pose());

        private AddonPosedForm()
        {
            this.add(this.pose);
            this.add(this.overlay);
        }

        public ValuePose getPose() { return this.pose; }
        public ValuePose getPoseOverlay() { return this.overlay; }
        public boolean hasBoneTracks() { return true; }
    }
}
