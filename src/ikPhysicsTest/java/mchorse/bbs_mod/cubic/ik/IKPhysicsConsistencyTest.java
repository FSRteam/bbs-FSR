package mchorse.bbs_mod.cubic.ik;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig.BoneConstraint;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.ik.solver.IKTree;
import mchorse.bbs_mod.cubic.ik.solver.IKTreeSolver;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.render.CubicRenderer;
import mchorse.bbs_mod.cubic.render.ModelPivotFrames;
import mchorse.bbs_mod.cubic.render.ModelRotationBlender;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderSpace;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.joml.Matrices;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-light numerical regression gate for IK/physics consistency. */
public final class IKPhysicsConsistencyTest
{
    private static final float EPS = 1.0e-3F;

    public static void main(String[] args)
    {
        try
        {
            IKMarkerSourceTest.runAll();
        }
        catch (java.io.IOException e)
        {
            throw new RuntimeException("IK marker source contract could not be read", e);
        }

        testFormRenderingSimulationPolicy();
        testRendererCleanupOnEarlyReturnAndFailure();
        testControlSoftnessDefault();
        testProductionSoftAndHardReach();
        testChainWorkspaceReuseAndRebind();
        testProductionBreakExtensionContinuity();
        testSingleBoneChainPole();
        testPoleBindingLifecycle();
        testIKRotationOwnership();
        testRealPoleDelta();
        testCubicShortChainTipLimit();
        testBobjShortChainTipLimit();
        testCubicMultiBoneTipLimit();
        testBobjMultiBoneTipLimit();
        testPhysicsUsesConstrainedParentAndClampsTwist();
        testBobjPhysicsUsesConstrainedParentAndClampsTwist();

        System.out.println("IKPhysicsConsistencyTest: OK");
    }

    private static void testFormRenderingSimulationPolicy()
    {
        require(FormRenderType.fromModelMode(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) == FormRenderType.ITEM_FP,
            "first-person item mode lost its isolated render type");
        require(FormRenderType.fromModelMode(ItemDisplayContext.THIRD_PERSON_LEFT_HAND) == FormRenderType.ITEM_TP,
            "third-person item mode lost its isolated render type");
        require(FormRenderType.fromModelMode(ItemDisplayContext.GUI) == FormRenderType.ITEM_INVENTORY,
            "GUI item mode lost its inventory-local render type");
        require(FormRenderType.fromModelMode(ItemDisplayContext.GROUND) == FormRenderType.ITEM,
            "ground item mode incorrectly gained a world host");
        require(FormRenderType.fromModelMode(ItemDisplayContext.FIXED) == FormRenderType.ITEM,
            "fixed item mode incorrectly gained a world host");
        require(FormRenderType.fromModelMode(ItemDisplayContext.HEAD) == FormRenderType.ITEM,
            "head item mode incorrectly gained a world host");
        require(FormRenderType.fromModelMode(null) == FormRenderType.ITEM,
            "unknown item mode incorrectly gained a world host");

        for (FormRenderType type : FormRenderType.values())
        {
            boolean worldHost = type == FormRenderType.ENTITY || type == FormRenderType.MODEL_BLOCK;
            FormRenderingContext context = new FormRenderingContext()
                .set(type, null, new PoseStack(), 0, 0, 0F);

            require(type.hasWorldHost() == worldHost, type + " world-host classification is inconsistent");
            require(context.allowWorldTargetOverrides == worldHost,
                type + " world-target override policy is inconsistent");
            require(context.allowWorldCollisions == worldHost,
                type + " world-collision policy is inconsistent");
            require(context.renderSpace == (worldHost ? FormRenderSpace.ENTITY_LOCAL : FormRenderSpace.UI_LOCAL),
                type + " render space is inconsistent with its host policy");
            assertTranslation(context.world.last().pose(), 0F, 0F, 0F, type + " local semantic world");
        }

        /* Avoid the no-arg constructor's equipment bootstrap: this policy test
         * needs only a clock/transform carrier, not a live item registry. */
        StubEntity entity = new StubEntity((net.minecraft.world.level.Level) null);

        entity.setPrevX(2D);
        entity.setPrevY(4D);
        entity.setPrevZ(6D);
        entity.setPosition(4D, 8D, 10D);
        entity.setPrevBodyYaw(20F);
        entity.setBodyYaw(60F);

        FormRenderingContext entityContext = new FormRenderingContext()
            .set(FormRenderType.ENTITY, entity, new PoseStack(), 0, 0, 0.5F);

        require(entityContext.simulationOwner == entity, "world entity did not default to its own simulation history");
        assertTranslation(entityContext.world.last().pose(), 3F, 6F, 8F, "interpolated entity semantic world");

        Object previewOwner = new Object();
        FormRenderingContext preview = new FormRenderingContext()
            .set(FormRenderType.PREVIEW, entity, new PoseStack(), 0, 0, 0F)
            .simulationOwner(previewOwner);

        require(preview.simulationOwner == previewOwner, "preview did not retain its explicit simulation owner");
        require(!preview.allowWorldTargetOverrides && !preview.allowWorldCollisions,
            "preview inherited live world inputs from its clock entity");

        FormRenderingContext anchored = new FormRenderingContext()
            .set(FormRenderType.ENTITY, entity, new PoseStack(), 0, 0, 0F)
            .semanticWorldFromCameraRelative(new Matrix4f().translation(1F, 2F, 3F), 100D, 200D, 300D);

        assertTranslation(anchored.world.last().pose(), 101F, 202F, 303F,
            "camera-relative anchor semantic world");

        anchored.inUI();
        require(anchored.renderSpace == FormRenderSpace.UI_LOCAL, "UI render did not switch to local render space");
        require(!anchored.allowWorldTargetOverrides && !anchored.allowWorldCollisions,
            "UI render retained absolute world inputs");
    }

    private static void testRendererCleanupOnEarlyReturnAndFailure()
    {
        TrackingForm form = createFormWithSettings(TrackingForm::new);

        TrackingRenderer renderer = new TrackingRenderer(form);
        FormRenderingContext context = new FormRenderingContext()
            .set(FormRenderType.PREVIEW, null, new PoseStack(), 0x12345678, 0, 0F);

        form.visible.set(false);
        renderer.render(context);

        require(form.applied == 1 && form.unapplied == 1,
            "invisible-form early return leaked applied animation state");
        require(renderer.renderCalls == 0, "invisible form reached render3D");
        require(context.light == 0x12345678, "invisible-form early return changed packed light");

        form.visible.set(true);
        form.lighting.set(0F);
        form.transform.get().translate.set(1F, 2F, 3F);
        renderer.fail = true;
        boolean failed = false;

        try
        {
            renderer.render(context);
        }
        catch (ExpectedRenderFailure e)
        {
            failed = true;
        }

        require(failed, "test renderer did not exercise the exceptional cleanup path");
        require(form.applied == 2 && form.unapplied == 2,
            "render failure leaked applied animation state");
        require(context.light == 0x12345678, "render failure leaked modified packed light");
        assertTranslation(context.stack.last().pose(), 0F, 0F, 0F, "render stack after failure");
        assertTranslation(context.world.last().pose(), 0F, 0F, 0F, "semantic stack after failure");

        form.failApply = true;
        renderer.fail = false;
        failed = false;

        try
        {
            renderer.render(context);
        }
        catch (ExpectedRenderFailure e)
        {
            failed = true;
        }

        require(failed, "test form did not exercise the applyStates failure path");
        require(form.applied == 3 && form.unapplied == 3,
            "applyStates failure did not run the matching state cleanup");
        require(context.light == 0x12345678, "applyStates failure changed packed light");
        assertTranslation(context.stack.last().pose(), 0F, 0F, 0F, "render stack after applyStates failure");
        assertTranslation(context.world.last().pose(), 0F, 0F, 0F, "semantic stack after applyStates failure");
    }

    private static void testControlSoftnessDefault()
    {
        IKControl control = new IKControl();

        control.fromData(new MapType());
        require(close(IKControl.DEFAULT_SOFTNESS, 0.05F), "legacy API 2.0 softness constant changed");
        require(close(IKControl.HARD_REACH_DEFAULT_SOFTNESS, 0F), "hard-reach runtime softness default changed");
        require(close(control.softness, ModelIKConfig.DEFAULT_SOFTNESS), "missing IKControl softness must use hard-IK default");
        require(close(control.softness, 0F), "IKControl and ModelIKConfig softness defaults diverged");
    }

    /**
     * Production soft-reach. The applier runs every effector goal through
     * {@link IKTreeSolver#softGoal} before the solve, so the asymptotic-reach
     * promise is asserted on the production preprocessor: a hard goal (softness
     * 0) clamps to the exact chain length, an explicit softness eases in short
     * of it, and an in-reach goal is left alone either way. Re-points the
     * hard/soft reach coverage that the deleted direct solver carried.
     */
    private static void testProductionSoftAndHardReach()
    {
        Vector3f root = new Vector3f();
        Vector3f beyond = new Vector3f(3F, 0F, 0F);
        Vector3f hard = IKTreeSolver.softGoal(root, 2F, beyond, 0F);

        require(close(hard.distance(root), 2F), "hard soft-reach did not clamp to the exact chain length");

        Vector3f soft = IKTreeSolver.softGoal(root, 2F, beyond, 0.2F);
        float softReach = soft.distance(root);

        require(softReach < 2F - 1.0e-4F && softReach > 1.9F, "explicit soft IK no longer preserves asymptotic reach");

        Vector3f inside = new Vector3f(1.5F, 0F, 0F);

        require(close(IKTreeSolver.softGoal(root, 2F, inside, 0F).distance(root), 1.5F),
            "hard soft-reach pushed an in-reach goal outwards");
        require(close(IKTreeSolver.softGoal(root, 2F, inside, 0.2F).distance(root), 1.5F),
            "an explicit softness disturbed an in-reach goal");
    }

    /**
     * The production per-chain workspace contract. {@link ModelIKChainWorkspace}
     * is what replaced the deleted direct solver's scratch allocator, so what a
     * warm frame must now guarantee is that the controller calibration baseline is
     * REUSED — no rebind, no redundant recompute, no manufactured pose — while the
     * chain rides the controller's DELTA, that a cold workspace binds where the
     * controller now is, and that the workspace-less call shape the render path
     * itself uses still solves. Re-points the warm-workspace coverage the deleted
     * solver carried.
     */
    private static void testChainWorkspaceReuseAndRebind()
    {
        CubicMultiFixture fixture = cubicMultiFixture(false);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();

        apply(fixture.model, chain, workspace, null, Collections.emptyMap());

        require(workspace.bindingValid, "first production solve did not open the calibration baseline");
        require(!workspace.bindPoleValid && !workspace.boundPoleEnabled,
            "a poleless chain claimed a pole baseline");
        require(fixture.root.orient == null && fixture.mid.orient == null && fixture.tip.orient == null,
            "binding the calibration baseline manufactured a pose");

        Vector3f bindTarget = new Vector3f(workspace.bindTargetLocal);
        Quaternionf bindRotation = new Quaternionf(workspace.bindTargetRotationLocal);

        /* Nothing moved: a warm workspace must be reused, never re-bound, and the
         * redundant recompute must be skipped — no solve, no pose. */
        apply(fixture.model, chain, workspace, null, Collections.emptyMap());

        require(workspace.bindingValid, "a warm no-op frame closed the calibration baseline");
        require(workspace.bindTargetLocal.equals(bindTarget) && workspace.bindTargetRotationLocal.equals(bindRotation),
            "a warm no-op frame re-bound the controller baseline");
        require(close(workspace.controllerDeltaWorld.length(), 0F),
            "a warm no-op frame invented a non-zero controller delta");
        require(fixture.root.orient == null && fixture.mid.orient == null,
            "a warm no-op frame re-solved the chain");

        /* Moving the controller binds nothing new: the chain rides the DELTA. */
        fixture.target.current.translate.add(0F, 16F, 0F);
        apply(fixture.model, chain, workspace, null, Collections.emptyMap());

        require(workspace.bindingValid, "moving the controller invalidated the calibration baseline");
        require(workspace.bindTargetLocal.equals(bindTarget),
            "moving the controller re-bound the baseline instead of riding its delta");
        require(workspace.controllerDeltaWorld.length() > EPS,
            "moving the controller produced no calibration delta");
        require(fixture.root.orient != null && fixture.mid.orient != null,
            "moving the bound controller produced no procedural pose");

        /* A COLD workspace binds where the controller now is and starts from a zero
         * delta: cold and warm must never disagree about what "moved" means. */
        fixture.model.resetPose();
        fixture.target.current.translate.add(0F, 16F, 0F);

        ModelIKChainWorkspace cold = new ModelIKChainWorkspace();

        apply(fixture.model, chain, cold, null, Collections.emptyMap());

        require(cold.bindingValid, "a cold workspace on a displaced controller did not bind");
        require(close(cold.controllerDeltaWorld.length(), 0F), "a cold workspace did not start from a zero delta");
        require(fixture.root.orient == null && fixture.mid.orient == null,
            "a cold workspace replayed a controller delta it never saw");

        /* The render path passes no workspace at all; that must still solve. */
        applyWithoutWorkspace(fixture.model, chain);

        require(fixture.root.orient != null, "the workspace-less render path did not solve");
    }

    /**
     * The production replacement for the deleted solver's straight-restore /
     * bend-hysteresis band. {@link IKTreeSolver} deliberately dropped the old
     * distance gate on the two-segment analytic pre-pose, because it produced a
     * measured 5.6-degree root snap at the 0.999-of-reach line. What must hold now
     * is CONTINUITY across that line, plus the pre-pose's guards: the analytic
     * branch fires for a straight chain with a pole-side goal, and the token branch
     * declines once the goal is at or past full extension. Re-points the
     * straight-restore coverage the deleted solver carried onto its successor.
     */
    private static void testProductionBreakExtensionContinuity()
    {
        float reach = 32F;
        CubicMultiFixture fixture = cubicMultiFixture(false);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), true, true);
        float[] fractions = new float[101];

        for (int i = 0; i < fractions.length; i++)
        {
            fractions[i] = 0.99F + i * 0.00009F;
        }
        Vector3f previous = null;
        float maxJump = 0F;

        for (float fraction : fractions)
        {
            fixture.model.resetPose();

            ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();

            apply(fixture.model, chain, workspace, null, Collections.emptyMap());
            fixture.target.current.translate.set(0F, -reach * fraction, 0F);
            apply(fixture.model, chain, workspace, null, Collections.emptyMap());

            require(fixture.root.orient != null,
                "the production solve produced no root orientation at " + fraction + " of reach");

            Vector3f euler = Matrices.toEulerZYXDegrees(fixture.root.orient);

            if (previous != null)
            {
                maxJump = Math.max(maxJump, Math.max(Math.abs(euler.x - previous.x),
                    Math.max(Math.abs(euler.y - previous.y), Math.abs(euler.z - previous.z))));
            }

            previous = euler;
        }

        require(maxJump < 1F,
            "the production root orientation jumped " + maxJump + " degrees across the extension line");

        System.out.println("  break-extension continuity: max root step " + maxJump + " degrees");

        /* The analytic branch: a straight chain, an in-reach goal off the pole side. */
        IKTree anchored = straightTwoSegmentTree();

        anchored.effectors[0].goal.set(0F, -1.5F, 0F);

        require(IKTreeSolver.breakExtension(anchored, 0, poleOn(new Vector3f(1F, -1F, 0F))),
            "the analytic pre-pose declined a straight chain with an in-reach pole-side goal");
        require(offAxis(anchored) > EPS,
            "the analytic pre-pose left the elbow sitting on the chain axis");

        /* The token branch: no pole, in-reach goal — it must still leave the axis. */
        IKTree folded = straightTwoSegmentTree();

        folded.effectors[0].goal.set(0F, -1.5F, 0F);

        require(IKTreeSolver.breakExtension(folded, 0, null),
            "the token path declined a straight chain with an in-reach goal");
        require(offAxis(folded) > EPS,
            "the token path folded the elbow without leaving the chain axis");

        /* The straight-at-full-extension guard: nothing left for the token path to do. */
        IKTree extended = straightTwoSegmentTree();

        extended.effectors[0].goal.set(0F, -2F, 0F);

        require(!IKTreeSolver.breakExtension(extended, 0, null),
            "the token path fired on a chain already at full extension");
    }

    /** A straight, fully movable two-segment production tree: reach 2 along -Y. */
    private static IKTree straightTwoSegmentTree()
    {
        IKTree tree = new IKTree(2, 1);

        tree.joints[0].startPosition.set(0F, 0F, 0F);
        tree.joints[1].startPosition.set(0F, -1F, 0F);
        tree.parentIndex[0] = -1;
        tree.parentIndex[1] = 0;
        tree.effector(0, 1).startPosition.set(0F, -2F, 0F);
        tree.effectors[0].goal.set(0F, -2F, 0F);
        tree.forward();

        return tree;
    }

    private static IKTreeSolver.Pole poleOn(Vector3f polePoint)
    {
        return new IKTreeSolver.Pole(0, polePoint, 0F, new Vector3f(0F, 0F, 1F));
    }

    /**
     * How far the solved chain sits off its straight root-to-effector line: the
     * larger of the interior joint's and the effector's perpendicular offsets.
     * A joint's OWN channel angles move only its descendants (a joint's position
     * rides its nearest captured ancestor's delta), so a fold at the elbow shows
     * up at the effector, while a fold at the root shows up at both.
     */
    private static float offAxis(IKTree tree)
    {
        Vector3f root = tree.joints[0].position;
        Vector3f direction = new Vector3f(tree.effectors[0].startPosition).sub(root).normalize();
        Vector3f elbow = new Vector3f(tree.joints[1].position).sub(root);
        Vector3f effector = new Vector3f(tree.effectors[0].position).sub(root);

        return Math.max(elbow.cross(direction).length(), effector.cross(direction).length());
    }

    /**
     * R5-1, upstream <code>a5d011c38</code>: the pole on a single-bone chain.
     * {@code restChain} used to refuse any chain with fewer than three bones, so
     * the rest geometry a pole needs never loaded and a pole on a one-bone chain
     * was silently dead. It now loads with a NULL elbow, which splits the two pole
     * sources exactly as intended: an AUTHORED pole target needs only the chain
     * axis and its own rest spot, so it works and is the only handle on the bone's
     * roll — while the AUTO (virtual) pole needs the elbow, since that is what
     * tells it which side the knee bulges, and still declines.
     *
     * <p>Observable difference: with a two-bone chain (root → tip) the pole is the
     * only directed bone's own segment, so its twist must change the ROOT's
     * orientation while leaving the tip where it is — a pole that re-aimed the
     * chain instead of rolling it would move the tip and be a different, wrong fix.
     */
    private static void testSingleBoneChainPole()
    {
        CubicFixture fixture = cubicFixture();
        ModelIKCache.CompiledChain noPole = compiledChain(List.of("root", "tip"), false, false);
        ModelIKCache.CompiledChain poled = compiledChain(List.of("root", "tip"), true, false);

        Vector3f moved = new Vector3f(4F, -13F, 0F);

        /* The pole is calibrated to ZERO TWIST in rest pose, so leaving the pole bone
         * alone would prove nothing on any chain length. Turn it a quarter turn about
         * the chain axis: a pole that works must roll the bone, a dead one must not. */
        Vector3f chainAxis = new Vector3f(0F, -1F, 0F);
        Vector3f restPole = new Vector3f(16F, -8F, 0F);
        Quaternionf quarterTurn = new Quaternionf().fromAxisAngleRad(chainAxis.x, chainAxis.y, chainAxis.z, (float) (Math.PI / 2D));
        Vector3f movedPole = quarterTurn.transform(restPole);

        ModelIKChainWorkspace plainWorkspace = new ModelIKChainWorkspace();
        ModelIKChainWorkspace poledWorkspace = new ModelIKChainWorkspace();

        apply(fixture.model, noPole, plainWorkspace, null, Collections.emptyMap());
        apply(fixture.model, poled, poledWorkspace, null, Collections.emptyMap());

        require(plainWorkspace.bindingValid && poledWorkspace.bindingValid,
            "the single-bone chains did not bind their calibration baselines");

        Quaternionf plainRoot;
        Vector3f plainTip;

        fixture.target.current.translate.set(moved.x, moved.y, moved.z);
        apply(fixture.model, noPole, plainWorkspace, null, Collections.emptyMap());

        require(fixture.root.orient != null, "the poleless single-bone chain produced no pose");

        plainRoot = new Quaternionf(fixture.root.orient);
        plainTip = new Vector3f(collectedPosition(fixture.model, "tip"));

        fixture.model.resetPose();
        fixture.target.current.translate.set(moved.x, moved.y, moved.z);
        applyPole(fixture.model, poled, poledWorkspace, movedPole);

        require(fixture.root.orient != null, "the poled single-bone chain produced no pose");
        require(!sameRotation(plainRoot, fixture.root.orient),
            "an authored pole on a single-bone chain changed nothing: the pole is still dead there");
        require(plainTip.distance(collectedPosition(fixture.model, "tip")) <= 0.01F,
            "the single-bone pole moved the tip: it re-aimed the chain instead of rolling it");
    }

    /** The solved position of one bone, read the way the debug overlay reads it. */
    private static Vector3f collectedPosition(IModel model, String bone)
    {
        Map<String, CubicRenderer.PivotFrame> frames = new HashMap<>();

        ModelPivotFrames.collect(model, Set.of(bone), frames, null, true);

        CubicRenderer.PivotFrame frame = frames.get(bone);

        require(frame != null, "no pivot frame was collected for " + bone);

        return new Vector3f(frame.position());
    }

    private static void testPoleBindingLifecycle()
    {
        CubicFixture fixture = cubicFixture();
        ModelIKCache.CompiledChain chain = compiledChain(false);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();
        IKControl control = new IKControl();
        Map<String, IKControl> controls = new HashMap<>();

        control.pole = false;
        controls.put("tip", control);
        apply(fixture.model, chain, workspace, controls, Collections.emptyMap());

        require(workspace.bindingValid && !workspace.boundPoleEnabled && !workspace.bindPoleValid, "target-only bind state is invalid");

        control.pole = true;
        apply(fixture.model, chain, workspace, controls, Collections.emptyMap());

        require(workspace.bindingValid && workspace.boundPoleEnabled && workspace.bindPoleValid, "pole false-to-true did not rebind pole baseline");

        control.enabled = false;
        apply(fixture.model, chain, workspace, controls, Collections.emptyMap());
        require(!workspace.bindingValid, "disabled IK retained stale calibration");

        control.enabled = true;
        apply(fixture.model, chain, workspace, controls, Collections.emptyMap());
        require(workspace.bindingValid, "re-enabled IK did not bind again");

        control.weight = 0F;
        apply(fixture.model, chain, workspace, controls, Collections.emptyMap());
        require(!workspace.bindingValid, "zero-weight IK retained stale calibration");

        control.weight = 1F;
        apply(fixture.model, chain, workspace, controls, Collections.emptyMap());
        require(workspace.bindingValid, "IK did not rebind when weight became effective again");
    }

    /**
     * A bound pole drives the production bend plane by the signed relative angular
     * delta the animator applied to the pole bone, not by its absolute position: the
     * first frame is silent, and equal-and-opposite pole turns about the chain axis
     * must land on different poses. Re-points the pole-delta coverage the deleted
     * applier's stored bend normal carried onto the production pose.
     */
    private static void testRealPoleDelta()
    {
        CubicMultiFixture fixture = cubicMultiFixture(true);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), true, false);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();

        apply(fixture.model, chain, workspace, null, Collections.emptyMap());

        require(workspace.bindingValid && workspace.bindPoleValid, "pole chain did not establish a real bind baseline");
        require(fixture.root.orient == null && fixture.mid.orient == null,
            "bound pole manufactured a pose change without controller movement");

        /* The production frames for the chain, exactly as the applier captures them. */
        Map<String, CubicRenderer.PivotFrame> frames = new HashMap<>();

        ModelPivotFrames.collect(fixture.model, Set.of("root", "mid", "tip", "target", "pole"), frames, null);

        Vector3f root = new Vector3f(frames.get("root").position());
        Vector3f axis = new Vector3f(frames.get("tip").position()).sub(root).normalize();
        Vector3f boundPole = new Vector3f(frames.get("pole").position());
        Quaternionf quarterTurn = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, (float) (Math.PI / 2D));
        Vector3f movedPole = quarterTurn.transform(boundPole.sub(root)).add(root);

        applyPole(fixture.model, chain, workspace, movedPole);

        require(fixture.root.orient != null && fixture.mid.orient != null,
            "moving a bound pole did not produce a procedural pose");
        require(workspace.bindingValid, "moving a bound pole invalidated the calibration baseline");

        /* The pole twist is a ROOT-joint rotation about the root→goal line, so the
         * sign of the pole's turn shows up on the root's own solved orientation. */
        Quaternionf plusRoot = new Quaternionf(fixture.root.orient);
        Quaternionf plusMid = new Quaternionf(fixture.mid.orient);

        Vector3f oppositePole = new Quaternionf(quarterTurn).invert().transform(new Vector3f(boundPole).sub(root)).add(root);

        fixture.model.resetPose();

        ModelIKChainWorkspace oppositeWorkspace = new ModelIKChainWorkspace();

        apply(fixture.model, chain, oppositeWorkspace, null, Collections.emptyMap());
        applyPole(fixture.model, chain, oppositeWorkspace, oppositePole);

        require(fixture.root.orient != null && fixture.mid.orient != null,
            "the opposite pole turn produced no pose to compare");
        require(!sameRotation(plusRoot, fixture.root.orient) || !sameRotation(plusMid, fixture.mid.orient),
            "equal and opposite pole turns landed on the same pose: the signed delta was ignored");
    }

    private static void testIKRotationOwnership()
    {
        CubicFixture fixture = cubicFixture();
        ModelForm form = createFormWithSettings(ModelForm::new);
        ModelIKConfig.Chain chain = new ModelIKConfig.Chain(
            "tip", "target", 0, true, "pole", 0F, 0F, 1F, true, false, false, false
        );

        form.ik.set(ModelIKIO.toData(new ModelIKConfig(List.of(chain), Collections.emptyMap())));

        require(ModelIKRuntime.isRotationConstrained(fixture.model, form, "root"),
            "an enabled IK chain must own its directed parent rotation");
        require(!ModelIKRuntime.isRotationConstrained(fixture.model, form, "tip"),
            "a tip without tipRotation must remain FK-rotatable");

        IKControl override = new IKControl();
        override.enabled = false;
        form.ikControlOverrides.put("tip", override);

        require(!ModelIKRuntime.isRotationConstrained(fixture.model, form, "root"),
            "a film-disabled IK chain must release FK rotation");

        form.ikControlOverrides.clear();
        ModelIKConfig.Chain rotatingTip = new ModelIKConfig.Chain(
            "tip", "target", 0, true, "pole", 0F, 0F, 1F, true, true, false, false
        );

        form.ik.set(ModelIKIO.toData(new ModelIKConfig(List.of(rotatingTip), Collections.emptyMap())));

        require(ModelIKRuntime.isRotationConstrained(fixture.model, form, "tip"),
            "tipRotation must transfer tip rotation ownership to IK");
    }

    private static <T> T createFormWithSettings(java.util.function.Supplier<T> factory)
    {
        ValueInt oldOverlayCount = BBSSettings.recordingPoseTransformOverlays;

        if (oldOverlayCount == null)
        {
            BBSSettings.recordingPoseTransformOverlays = new ValueInt("test_pose_transform_overlays", 0);
        }

        try
        {
            return factory.get();
        }
        finally
        {
            if (oldOverlayCount == null)
            {
                BBSSettings.recordingPoseTransformOverlays = null;
            }
        }
    }

    /**
     * Production effector-limit contract, single-bone cubic. The applier clamps the
     * solver's DIRECTED bones to the constraint stack ({@code applyBoneConstraint}
     * feeds each joint's limits into {@code IKJoint.clampLimits}), and hands the
     * EFFECTOR bone to the "tip follows target" snap, which writes the controller's
     * world orientation as-is. So a {@code BoneConstraint} on the effector bone is
     * NOT enforced by the applier: the constraint stack clamps the model BEFORE IK
     * runs and nothing re-clamps the effector after it.
     *
     * <p>That was not true of the removed direct solver, which clamped the effector
     * too ({@code ModelIKApplier.clampFinalOrientation}). Pinning the production
     * contract here keeps the difference visible instead of silently lost with the
     * deleted assertions; enforcing the effector limit is a real behaviour change
     * and deliberately out of R5-0's scope.
     */
    private static void testCubicShortChainTipLimit()
    {
        CubicFixture fixture = cubicFixture();
        ModelIKCache.CompiledChain chain = compiledChain(false);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();
        Map<String, BoneConstraint> limits = Map.of(
            "root", limit(-25F, 25F),
            "tip", limit(-5F, 5F)
        );

        apply(fixture.model, chain, workspace, null, limits);

        fixture.target.current.translate.add(16F, 0F, 0F);
        fixture.target.current.rotate.z = 90F;
        apply(fixture.model, chain, workspace, null, limits);

        require(fixture.root.orient != null, "single-bone cubic chain skipped quaternion reconstruction");
        require(fixture.tip.orient != null, "cubic tipRotation did not write a tip quaternion");
        assertWithin(fixture.root.orient, 25F, "cubic root final limit");
        assertEffectorFollowsController(fixture.tip.orient, "cubic single-bone effector");
    }

    /** BOBJ twin of {@link #testCubicShortChainTipLimit}: same contract, bind-matrix flavour. */
    private static void testBobjShortChainTipLimit()
    {
        BobjFixture fixture = bobjFixture();
        ModelIKCache.CompiledChain chain = compiledChain(false);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();
        Map<String, BoneConstraint> limits = Map.of(
            "root", limit(-25F, 25F),
            "tip", limit(-5F, 5F)
        );

        apply(fixture.model, chain, workspace, null, limits);

        fixture.target.transform.translate.add(1F, 0F, 0F);
        fixture.target.transform.rotate.z = (float) Math.toRadians(90F);
        apply(fixture.model, chain, workspace, null, limits);

        require(fixture.root.orient != null, "single-bone BOBJ chain skipped quaternion reconstruction");
        require(fixture.tip.orient != null, "BOBJ tipRotation did not write a tip quaternion");
        assertWithin(fixture.root.orient, 25F, "BOBJ root final limit");
        assertEffectorFollowsController(fixture.tip.orient, "BOBJ single-bone effector");
    }

    /** Multi-bone cubic twin of {@link #testCubicShortChainTipLimit}: the interior bones are clamped, the effector is not. */
    private static void testCubicMultiBoneTipLimit()
    {
        CubicMultiFixture fixture = cubicMultiFixture(false);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();
        Map<String, BoneConstraint> limits = Map.of(
            "root", limit(-20F, 20F),
            "mid", limit(-15F, 15F),
            "tip", limit(-5F, 5F)
        );

        apply(fixture.model, chain, workspace, null, limits);

        fixture.target.current.translate.add(16F, 0F, 0F);
        fixture.target.current.rotate.z = 90F;
        apply(fixture.model, chain, workspace, null, limits);

        require(fixture.root.orient != null && fixture.mid.orient != null,
            "multi-bone cubic solve skipped directed-bone quaternions");
        require(fixture.tip.orient != null, "multi-bone cubic tipRotation skipped the effector quaternion");
        assertWithin(fixture.root.orient, 20F, "multi-bone cubic root final limit");
        assertWithin(fixture.mid.orient, 15F, "multi-bone cubic child final limit");
        assertEffectorFollowsController(fixture.tip.orient, "multi-bone cubic effector");
    }

    /** Multi-bone BOBJ twin of {@link #testCubicMultiBoneTipLimit}. */
    private static void testBobjMultiBoneTipLimit()
    {
        BobjMultiFixture fixture = bobjMultiFixture();
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        ModelIKChainWorkspace workspace = new ModelIKChainWorkspace();
        Map<String, BoneConstraint> limits = Map.of(
            "root", limit(-20F, 20F),
            "mid", limit(-15F, 15F),
            "tip", limit(-5F, 5F)
        );

        apply(fixture.model, chain, workspace, null, limits);

        fixture.target.transform.translate.add(1F, 0F, 0F);
        fixture.target.transform.rotate.z = (float) Math.toRadians(90F);
        apply(fixture.model, chain, workspace, null, limits);

        require(fixture.root.orient != null && fixture.mid.orient != null,
            "multi-bone BOBJ solve skipped directed-bone quaternions");
        require(fixture.tip.orient != null, "multi-bone BOBJ tipRotation skipped the effector quaternion");
        assertWithin(fixture.root.orient, 20F, "multi-bone BOBJ root final limit");
        assertWithin(fixture.mid.orient, 15F, "multi-bone BOBJ child final limit");
        assertEffectorFollowsController(fixture.tip.orient, "multi-bone BOBJ effector");
    }

    private static void testPhysicsUsesConstrainedParentAndClampsTwist()
    {
        CubicFixture fixture = cubicFixture();
        Vector3f[] positions = {
            new Vector3f(0F, 0F, 0F),
            new Vector3f(1F, 0F, 0F),
            new Vector3f(1F, -1F, 0F)
        };
        ModelRotationBlender.Workspace workspace = new ModelRotationBlender.Workspace();

        ModelRotationBlender.applyWeightedRotations(
            fixture.model,
            new Quaternionf(),
            List.of("root", "tip"),
            positions,
            1F,
            Map.of("root", limit(0F, 0F)),
            workspace
        );

        assertWithin(fixture.root.orient, 0F, "physics root final limit");
        assertWithin(fixture.tip.orient, EPS, "physics child decomposed against an unclamped parent frame");

        fixture.model.resetPose();
        fixture.tip.current.rotate.y = 90F;

        ModelRotationBlender.applyWeightedRotations(
            fixture.model,
            new Quaternionf(),
            List.of("root", "tip"),
            positions,
            1F,
            Map.of(
                "root", limit(0F, 0F),
                "tip", new BoneConstraint(true, -180F, -10F, -180F, 180F, 10F, 180F)
            ),
            workspace
        );

        Vector3f tipEuler = Matrices.toEulerZYXDegrees(fixture.tip.orient);
        require(tipEuler.y >= -10F - EPS && tipEuler.y <= 10F + EPS, "physics swing+twist escaped the final quaternion limit");
    }

    private static void testBobjPhysicsUsesConstrainedParentAndClampsTwist()
    {
        BobjFixture fixture = bobjFixture();
        Vector3f[] positions = {
            new Vector3f(0F, 0F, 0F),
            new Vector3f(1F, 0F, 0F),
            new Vector3f(1F, -1F, 0F)
        };
        ModelRotationBlender.Workspace workspace = new ModelRotationBlender.Workspace();

        ModelRotationBlender.applyWeightedRotations(
            fixture.model,
            new Quaternionf(),
            List.of("root", "tip"),
            positions,
            1F,
            Map.of("root", limit(0F, 0F)),
            workspace
        );

        assertWithin(fixture.root.orient, 0F, "BOBJ physics root final limit");
        assertWithin(fixture.tip.orient, EPS, "BOBJ physics child decomposed against an unclamped parent frame");

        fixture.model.resetPose();
        fixture.tip.transform.rotate.y = (float) Math.toRadians(90F);

        ModelRotationBlender.applyWeightedRotations(
            fixture.model,
            new Quaternionf(),
            List.of("root", "tip"),
            positions,
            1F,
            Map.of(
                "root", limit(0F, 0F),
                "tip", new BoneConstraint(true, -180F, -10F, -180F, 180F, 10F, 180F)
            ),
            workspace
        );

        Vector3f tipEuler = Matrices.toEulerZYXDegrees(fixture.tip.orient);
        require(tipEuler.y >= -10F - EPS && tipEuler.y <= 10F + EPS,
            "BOBJ physics swing+twist escaped the final quaternion limit");
    }

    /**
     * One production solve: no DoF overrides, no film target/pole positions, just
     * the optional {@code ik} control overrides and the constraint stack, so the
     * tip-limit and pole-lifecycle tests observe exactly what the render path's
     * applier writes to {@code orient}.
     */
    private static void apply(IModel model, ModelIKCache.CompiledChain chain, ModelIKChainWorkspace workspace, Map<String, IKControl> controls, Map<String, BoneConstraint> limits)
    {
        ModelIKDlsApplier.apply(
            model,
            List.of(chain),
            null,
            null,
            null,
            null,
            null,
            controls,
            List.of(workspace),
            limits
        );
    }

    /** The render path's own call shape: {@code ModelIKRuntime} passes no workspace. */
    private static void applyWithoutWorkspace(IModel model, ModelIKCache.CompiledChain chain)
    {
        ModelIKDlsApplier.apply(
            model,
            List.of(chain),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Collections.emptyMap()
        );
    }

    /** One production solve driven by a film pole override. */
    private static void applyPole(IModel model, ModelIKCache.CompiledChain chain, ModelIKChainWorkspace workspace, Vector3f pole)
    {
        ModelIKDlsApplier.apply(
            model,
            List.of(chain),
            null,
            null,
            Map.of("pole", pole),
            null,
            null,
            null,
            List.of(workspace),
            Collections.emptyMap()
        );
    }

    private static boolean sameRotation(Quaternionf a, Quaternionf b)
    {
        float dot = Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);

        return dot >= 1F - EPS;
    }

    private static ModelIKCache.CompiledChain compiledChain(boolean pole)
    {
        return compiledChain(List.of("root", "tip"), pole, true);
    }

    private static ModelIKCache.CompiledChain compiledChain(List<String> ids, boolean pole, boolean tipRotation)
    {
        Set<String> wanted = new HashSet<>(ids);

        wanted.add("target");
        wanted.add("pole");

        return new ModelIKCache.CompiledChain(
            ids.get(ids.size() - 1), "target", pole, "pole", 0F, 0F, 1F, tipRotation, false,
            ids, ids, null, Set.copyOf(wanted), 0
        );
    }

    private static CubicFixture cubicFixture()
    {
        Model model = new Model(new MolangParser());
        ModelGroup root = group("root", 0F, 0F, 0F);
        ModelGroup tip = group("tip", 0F, -16F, 0F);
        ModelGroup target = group("target", 0F, -16F, 0F);
        ModelGroup pole = group("pole", 16F, -8F, 0F);

        root.children.add(tip);
        model.topGroups.add(root);
        model.topGroups.add(target);
        model.topGroups.add(pole);
        model.initialize();
        model.resetPose();

        return new CubicFixture(model, root, tip, target);
    }

    private static CubicMultiFixture cubicMultiFixture(boolean bent)
    {
        Model model = new Model(new MolangParser());
        ModelGroup root = group("root", 0F, 0F, 0F);
        ModelGroup mid = group("mid", 0F, -16F, 0F);
        ModelGroup tip = group("tip", bent ? 8F : 0F, bent ? -30F : -32F, 0F);
        ModelGroup target = group("target", bent ? 8F : 0F, bent ? -30F : -32F, 0F);
        ModelGroup pole = group("pole", 16F, -16F, 0F);

        root.children.add(mid);
        mid.children.add(tip);
        model.topGroups.add(root);
        model.topGroups.add(target);
        model.topGroups.add(pole);
        model.initialize();
        model.resetPose();

        return new CubicMultiFixture(model, root, mid, tip, target, pole);
    }

    private static ModelGroup group(String id, float x, float y, float z)
    {
        ModelGroup group = new ModelGroup(id);
        group.initial.translate.set(x, y, z);

        return group;
    }

    private static BobjFixture bobjFixture()
    {
        BOBJArmature armature = new BOBJArmature("test");
        BOBJBone root = bone(0, "root", "", 0F, 0F, 0F);
        BOBJBone tip = bone(1, "tip", "root", 0F, -1F, 0F);
        BOBJBone target = bone(2, "target", "", 0F, -1F, 0F);
        BOBJBone pole = bone(3, "pole", "", 1F, -0.5F, 0F);

        armature.addBone(root);
        armature.addBone(tip);
        armature.addBone(target);
        armature.addBone(pole);
        armature.initArmature();

        BOBJModel model = new BOBJModel(armature, new ArrayList<>(), false);
        model.resetPose();

        return new BobjFixture(model, root, tip, target);
    }

    private static BobjMultiFixture bobjMultiFixture()
    {
        BOBJArmature armature = new BOBJArmature("test-multi");
        BOBJBone root = bone(0, "root", "", 0F, 0F, 0F);
        BOBJBone mid = bone(1, "mid", "root", 0F, -1F, 0F);
        BOBJBone tip = bone(2, "tip", "mid", 0F, -2F, 0F);
        BOBJBone target = bone(3, "target", "", 0F, -2F, 0F);
        BOBJBone pole = bone(4, "pole", "", 1F, -1F, 0F);

        armature.addBone(root);
        armature.addBone(mid);
        armature.addBone(tip);
        armature.addBone(target);
        armature.addBone(pole);
        armature.initArmature();

        BOBJModel model = new BOBJModel(armature, new ArrayList<>(), false);
        model.resetPose();

        return new BobjMultiFixture(model, root, mid, tip, target, pole);
    }

    private static BOBJBone bone(int index, String name, String parent, float x, float y, float z)
    {
        return new BOBJBone(index, name, parent, new Matrix4f().translation(x, y, z));
    }

    private static BoneConstraint limit(float min, float max)
    {
        return new BoneConstraint(true, min, min, min, max, max, max);
    }

    private static void assertTranslation(Matrix4f matrix, float x, float y, float z, String label)
    {
        Vector3f translation = matrix.getTranslation(new Vector3f());

        require(close(translation.x, x) && close(translation.y, y) && close(translation.z, z),
            label + " has wrong translation: " + translation);
    }

    /**
     * The applier's effector contract: with {@code tipRotation} on, the effector bone
     * is written from the controller's world orientation by the tip-follow snap, and
     * NOT clamped by the applier. Every fixture here turns its controller by 90
     * degrees, so a solved effector that came out at rest means the snap never ran,
     * and one that stayed inside a 5-degree limit would mean the applier had started
     * clamping the effector — which it does not, and which would change behaviour.
     */
    private static void assertEffectorFollowsController(Quaternionf effector, String label)
    {
        require(effector != null, label + " was never written");
        require(!sameRotation(effector, new Quaternionf()),
            label + " stayed at rest despite a 90-degree controller turn: the tip snap did not run");
    }

    private static void assertWithin(Quaternionf quaternion, float limit, String label)
    {
        require(quaternion != null, label + " did not produce an orientation");
        Vector3f euler = Matrices.toEulerZYXDegrees(quaternion);
        float allowed = Math.abs(limit) + EPS;

        require(Math.abs(euler.x) <= allowed && Math.abs(euler.y) <= allowed && Math.abs(euler.z) <= allowed,
            label + " exceeded range: " + euler);
    }

    private static boolean close(float a, float b)
    {
        return Math.abs(a - b) <= EPS;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TrackingForm extends Form
    {
        private int applied;
        private int unapplied;
        private boolean failApply;

        @Override
        public void applyStates(float transition)
        {
            this.applied += 1;

            if (this.failApply)
            {
                throw new ExpectedRenderFailure();
            }
        }

        @Override
        public void unapplyStates()
        {
            this.unapplied += 1;
        }
    }

    private static final class TrackingRenderer extends FormRenderer<TrackingForm>
    {
        private int renderCalls;
        private boolean fail;

        private TrackingRenderer(TrackingForm form)
        {
            super(form);
        }

        @Override
        protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
        {}

        @Override
        protected void render3D(FormRenderingContext context)
        {
            this.renderCalls += 1;

            if (this.fail)
            {
                throw new ExpectedRenderFailure();
            }
        }
    }

    private static final class ExpectedRenderFailure extends RuntimeException
    {
    }

    private record CubicFixture(Model model, ModelGroup root, ModelGroup tip, ModelGroup target)
    {
    }

    private record CubicMultiFixture(Model model, ModelGroup root, ModelGroup mid, ModelGroup tip, ModelGroup target, ModelGroup pole)
    {
    }

    private record BobjFixture(BOBJModel model, BOBJBone root, BOBJBone tip, BOBJBone target)
    {
    }

    private record BobjMultiFixture(BOBJModel model, BOBJBone root, BOBJBone mid, BOBJBone tip, BOBJBone target, BOBJBone pole)
    {
    }
}
