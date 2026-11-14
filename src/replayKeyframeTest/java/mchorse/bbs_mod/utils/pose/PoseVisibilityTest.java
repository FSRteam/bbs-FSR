package mchorse.bbs_mod.utils.pose;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicMatrixRenderer;
import mchorse.bbs_mod.cubic.render.CubicRenderer;
import mchorse.bbs_mod.cubic.render.ICubicRenderer;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.utils.interps.Interpolations;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Standalone sanity checks; run main with the test runtime classpath. */
public class PoseVisibilityTest
{
    public static void main(String[] args)
    {
        PoseTransform hidden = new PoseTransform();
        hidden.visible = false;

        check(!hidden.isDefault(), "A visibility-only edit must survive pose pruning");
        check(hidden.contentHash() != new PoseTransform().contentHash(), "Visibility must invalidate cached poses");

        Pose pose = new Pose();
        pose.transforms.put("parent", hidden);
        check(!pose.copy().get("parent").visible, "Copy must retain hidden bones");
        Pose loaded = new Pose();
        loaded.fromData(pose.toData());
        check(!loaded.get("parent").visible && loaded.equals(pose), "Visibility must round-trip");

        hidden.fromData(new MapType());
        check(hidden.visible, "Old poses without visibility must load as visible");
        hidden.visible = false;
        hidden.identity();
        check(hidden.visible && hidden.isDefault(), "Reset must restore visibility");

        PoseTransform shown = new PoseTransform();
        hidden.visible = false;
        PoseTransform result = new PoseTransform();

        for (float x : new float[] {0F, 0.5F, 0.999F, 1F})
        {
            result.lerp(shown, shown, hidden, hidden, Interpolations.LINEAR, x);
            check(result.visible == (x < 1F), "Linear keys must hold visibility until the next key");
            result.autoLerp(hidden, hidden, shown, shown, -10F, 0F, 10F, 20F, false, x);
            check(result.visible == (x >= 1F), "Auto keys must hold visibility in the opposite direction");
        }

        result.copy(shown);
        result.add(hidden);
        result.add(shown);
        check(!result.visible, "Neutral additive layers must not re-enable a hidden bone");

        Model model = new Model(null);
        ModelGroup parent = new ModelGroup("parent");
        ModelGroup child = new ModelGroup("child");
        parent.children.add(child);
        model.topGroups.add(parent);
        model.initialize();
        model.applyPose(pose);
        check(!parent.isVisible() && child.isVisible(), "Hiding a bone must leave its children visible");
        parent.visible = true;
        check(!parent.isVisible(), "CEM/model visibility must not override the pose");
        model.resetPose();
        check(parent.isVisible(), "Visibility must not leak into the next pose");
        parent.visible = false;
        model.applyPose(new Pose());
        check(!parent.isVisible(), "A neutral pose must respect model visibility");

        testOverlayVisibility();
        testHiddenBoneMatrices();

        System.out.println("Pose visibility checks passed");
    }

    private static void testOverlayVisibility()
    {
        ModelForm form = new ModelForm();
        ModelFormRenderer renderer = new ModelFormRenderer(form);
        PoseTransform overlay = form.poseOverlay.get().getOrCreate("hand");

        overlay.visible = false;
        check(!renderer.getPose().get("hand").visible, "Pose overlays must hide bones");
        overlay.fix = 0.5F;
        check(!renderer.getPose().get("hand").visible, "Fixed pose overlays must retain visibility");
        overlay.visible = true;

        ValuePose additional = new ValuePose("pose_overlay_1", new Pose());
        additional.get().getOrCreate("hand").visible = false;
        form.additionalOverlays.add(additional);
        check(!renderer.getPose().get("hand").visible, "Additional pose tracks must hide bones");
        form.pose.get().getOrCreate("hand").visible = false;
        additional.get().get("hand").visible = true;
        check(!renderer.getPose().get("hand").visible, "Neutral overlays must not re-enable a hidden base bone");
    }

    private static void testHiddenBoneMatrices()
    {
        Model model = new Model(null);
        ModelGroup parent = new ModelGroup("parent");
        ModelGroup child = new ModelGroup("child");
        parent.children.add(child);
        model.topGroups.add(parent);
        model.initialize();
        parent.current.translate.set(16F, 8F, 0F);
        parent.current.rotate.set(0F, 35F, 0F);
        parent.offset = new Vector3f(0.25F, 0.5F, -0.75F);
        parent.poseVisible = false;
        child.current.translate.set(0F, 16F, 0F);
        PoseStack expected = new PoseStack();
        ICubicRenderer expectedRenderer = (builder, stack, group, currentModel) -> false;
        expectedRenderer.applyGroupTransformations(expected, parent);
        Matrix4f parentMatrix = new Matrix4f(expected.last().pose());
        expectedRenderer.applyGroupTransformations(expected, child);
        CubicMatrixRenderer renderer = new CubicMatrixRenderer(model);
        CubicRenderer.processRenderModel(renderer, null, new PoseStack(), model);
        check(parentMatrix.equals(renderer.matrices.get(parent.index), 1E-6F),
            "A hidden stretched bone must retain its evaluated attachment matrix");
        check(expected.last().pose().equals(renderer.matrices.get(child.index), 1E-6F),
            "Visible children must inherit their hidden parent's evaluated transform");
        Vector3f origin = renderer.origins.get(parent.index).getTranslation(new Vector3f());
        check(origin.equals(-0.75F, 1F, -0.75F), "Bone origins must include the IK stretch offset");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
