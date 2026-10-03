package mchorse.bbs_mod.cubic.ik;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.ik.solver.IKTree;
import mchorse.bbs_mod.cubic.ik.solver.IKTreeSolver;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.render.CubicRenderer;
import mchorse.bbs_mod.cubic.render.ModelPivotFrames;
import mchorse.bbs_mod.cubic.render.ModelRotationBlender;
import mchorse.bbs_mod.data.types.BaseType;
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
        testEffectorTracksControllerAndIsNotClamped();
        testPhysicsUsesConstrainedParentAndClampsTwist();
        testBobjPhysicsUsesConstrainedParentAndClampsTwist();
        testSquashSerializationContract();
        testCompileForwardsTheChainBehaviourFlags();
        testStretchAndSquashAreIndependent();

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
            "tip", "target", 0, true, "pole", 0F, 0F, 1F, true, false, false, false, false
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
            "tip", "target", 0, true, "pole", 0F, 0F, 1F, true, true, false, false, false
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
     * too ({@code ModelIKApplier.clampFinalOrientation}) — but R5-0 established that
     * whole solver was reachable only from this source set, so its clamp never ran in
     * the game and these four tests used to be green by exercising it. The behaviour
     * pinned here is what upstream {@code da84e5f8f} does as well ("tip orientation
     * wins", see {@link #testEffectorTracksControllerAndIsNotClamped}), so this is the
     * migration target, not a gap: re-adding a clamp would be a divergence from
     * upstream, and would break the exact parent cancellation the tip follow relies on.
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
        assertEffectorFollowsController(fixture.tip.orient, 5F, "cubic single-bone effector");
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
        assertEffectorFollowsController(fixture.tip.orient, 5F, "BOBJ single-bone effector");
    }

    /** Multi-bone cubic twin of {@link #testCubicShortChainTipLimit}: the interior bones clamp in the solver, the effector deliberately does not. */
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
        assertEffectorFollowsController(fixture.tip.orient, 5F, "multi-bone cubic effector");
    }

    /**
     * R5-0b (task-22): the effector follows the controller one-for-one, and is
     * deliberately NOT clamped to its own constraint.
     *
     * <p>This is the contract the deleted {@code ModelIKApplier} did not have: that
     * file clamped the FINAL local quaternion of every bone it wrote, the tip
     * included ({@code clampFinalOrientation}, called on the tip at the pre-deletion
     * {@code :476/:507/:700/:957}). R5-0 proved that whole solver unreachable outside
     * this source set, so the clamp was never on the production path — and the four
     * {@code *TipLimit} tests above used to be green by testing it. The production
     * applier instead hands a {@code BoneConstraint} to the solver as joint-space
     * limits ({@code ModelIKDlsApplier.applyGroup:605-608}), which can only reach the
     * tree's NODES — the node set is every work id but the LAST, because a bone's own
     * angles move only its descendants — so the effector is point, never variable,
     * and its authored range is ignored. That is not a migration gap: upstream
     * {@code da84e5f8f} has no effector clamp either, and its tip writer documents the
     * intent as "tip orientation wins" ({@code ModelIKApplier.java:995-1004}). R5-0
     * showed the two files are the same code, so this test pins the UPSTREAM
     * behaviour, and its whole purpose is to stop someone from helpfully "fixing" it.
     *
     * <p>WHY upstream can get away with it — and why a clamp makes things WORSE: the
     * tip writer sets {@code tipLocal = tipParent⁻¹ · tipTarget}, so the drawn tip is
     * {@code parent · parent⁻¹ · tipTarget = tipTarget} and the parent frame cancels
     * EXACTLY, by construction. The effector therefore tracks the controller's
     * rotation one-for-one no matter what the chain underneath does, including across
     * whatever discontinuities the solve has. Clamping {@code tipLocal} destroys that
     * cancellation and hands the drawn tip the parent's own motion instead. Measured
     * while a clamp was briefly in place (same sweep, ±5° tip limit): the unclamped
     * tip advanced at most 1.001°/controller-degree, while the clamped tip jumped
     * 37.85° in a single controller degree — a parent bone's basin flip that the
     * cancellation had been covering.
     *
     * <p>The sweep drives the render path's own call shape (no workspace), because that
     * is the branch where the contract is stated most literally:
     * {@code ModelIKDlsApplier.resolveChain:470-473} takes
     * {@code tipTarget = targetFrame.worldRotation()} — the controller's world rotation,
     * read from the very frame map the applier builds. So the check is not "does the tip
     * track a formula the test inferred", it is "does the drawn tip equal the same
     * quaternion the applier was handed as the controller".
     */
    private static void testEffectorTracksControllerAndIsNotClamped()
    {
        CubicMultiFixture fixture = cubicMultiFixture(false);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        Map<String, BoneConstraint> limits = Map.of(
            "root", limit(-20F, 20F),
            "mid", limit(-15F, 15F),
            "tip", limit(-5F, 5F)
        );

        float tipLimit = 5F;
        float maxTrackingError = 0F;
        float maxStep = 0F;
        float maxDirectedViolation = 0F;
        float maxEffectorViolation = 0F;
        int samples = 0;
        Quaternionf previous = null;

        for (int deg = -180; deg <= 180; deg++)
        {
            fixture.model.resetPose();
            fixture.target.current.translate.add(16F, 0F, 0F);
            fixture.target.current.rotate.z = deg;
            applyWithoutWorkspace(fixture.model, chain, limits);

            require(fixture.root.orient != null && fixture.mid.orient != null && fixture.tip.orient != null,
                "the effector-tracking sweep did not solve at controller " + deg);

            /* The DIRECTED bones ARE limited: the in-solver limit path is the one place a
             * constraint is honoured, and it is exact to float precision. That also makes the
             * sweep non-vacuous — a solve that ignored the stack entirely would leave the tip
             * tracking for the wrong reason. */
            maxDirectedViolation = Math.max(maxDirectedViolation, Math.max(
                violationDegrees(fixture.root.orient, 20F),
                violationDegrees(fixture.mid.orient, 15F)));

            /* The effector is NOT limited: its authored range is ignored, by design. Read on
             * the LOCAL quaternion, which is where a clamp would have bitten. */
            maxEffectorViolation = Math.max(maxEffectorViolation, violationDegrees(fixture.tip.orient, tipLimit));

            /* Drawn tip vs the controller, both through the applier's own frame collection. */
            Quaternionf tip = worldRotationOf(fixture.model, "tip");
            Quaternionf controller = worldRotationOf(fixture.model, "target");

            maxTrackingError = Math.max(maxTrackingError, angleBetweenDegrees(tip, controller));

            if (previous != null)
            {
                maxStep = Math.max(maxStep, angleBetweenDegrees(previous, tip));
            }

            previous = tip;
            samples++;
        }

        System.out.println("  effector-tracking sweep (" + samples + " controller angles, tip limit +/-" + tipLimit + " degrees):");
        System.out.println("    directed-bone residual violation (root/mid): " + maxDirectedViolation + " degrees");
        System.out.println("    effector violation of its own constraint:    " + maxEffectorViolation + " degrees");
        System.out.println("    drawn tip vs controller, worst difference:   " + maxTrackingError + " degrees");
        System.out.println("    drawn-tip step per controller degree:        " + maxStep + " degrees");

        /* The directed bones honour the constraint stack ... */
        require(maxDirectedViolation <= EPS,
            "the directed bones' in-solver limits were not exact: " + maxDirectedViolation + " degrees over");

        /* ... while the effector does not, which is upstream's documented intent. A clamp
         * would drive this to ~0, so this is the assertion that fails the moment someone
         * re-adds one. The bound is far below the ~174 degrees this sweep measures and far
         * above the float noise of a clamped pose, so it is not a threshold tuned to the
         * current numbers. */
        require(maxEffectorViolation > 45F,
            "the effector obeyed its own +/-" + tipLimit + "-degree constraint (worst violation "
                + maxEffectorViolation + " degrees): something is clamping the effector, which upstream's "
                + "'tip orientation wins' does not do, and which breaks the exact parent cancellation "
                + "the tip follow relies on");

        /* The consequence of that missing clamp, pinned two ways. First the contract itself:
         * the drawn tip IS the controller. A clamp on tipLocal would leave the drawn tip at
         * parent·clamp(...), i.e. off the controller by however far the parent had swung —
         * tens of degrees here, not the float tolerance this allows. */
        require(maxTrackingError <= 1F,
            "the drawn tip is not the controller: worst difference " + maxTrackingError + " degrees");

        /* Second, the kinematic consequence: one degree of controller becomes one degree of
         * tip, so the effector never tears. Both bounds are generous relative to the ~0.002
         * degree that acos noise alone produces at this scale, and both are exceeded by an
         * order of magnitude the moment a clamp comes back. */
        require(maxStep <= 1.5F,
            "the drawn tip tore between adjacent controller degrees: " + maxStep + " degrees");
    }

    /** How far past a symmetric ZYX degree limit an orientation sits, on its worst axis. */
    private static float violationDegrees(Quaternionf orientation, float limit)
    {
        Vector3f euler = Matrices.toEulerZYXDegrees(orientation);

        return Math.max(0F, Math.max(Math.abs(euler.x), Math.max(Math.abs(euler.y), Math.abs(euler.z))) - limit);
    }

    /**
     * A bone's drawn world rotation, from an INDEPENDENT collection — deliberately the same
     * call the applier makes ({@code ModelIKDlsApplier:191}, the four-argument overload), so
     * that what is compared here is the same quantity the applier read. {@code applyStretch}
     * stays false for that reason: the applier's frames are the unstretched ones.
     */
    private static Quaternionf worldRotationOf(IModel model, String bone)
    {
        Map<String, CubicRenderer.PivotFrame> frames = new HashMap<>();

        ModelPivotFrames.collect(model, Set.of(bone), frames, null);

        CubicRenderer.PivotFrame frame = frames.get(bone);

        require(frame != null, "no pivot frame was collected for " + bone);

        return new Quaternionf(frame.worldRotation());
    }

    private static float angleBetweenDegrees(Quaternionf a, Quaternionf b)
    {
        float dot = Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);

        return (float) Math.toDegrees(2D * Math.acos(Math.min(1D, dot)));
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
        assertEffectorFollowsController(fixture.tip.orient, 5F, "multi-bone BOBJ effector");
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
     * The chain's squash flag travels under upstream's key name, {@code "squash"} —
     * not {@code "ik_squash"}, which names the value-tree entry a bone carries and
     * lives in a different layer. The key is asserted as a literal because the point
     * of that spelling is that data written here loads upstream.
     *
     * <p>Run for BOTH data shapes `ModelIKIO.fromData` accepts: the wrapped one
     * {@code toData} writes ({@code {chains: …}}), and the NON-wrapped legacy shape
     * where the map of tip entries IS the root. They are separate code paths — the
     * wrapped one also decides {@code defaultClassic} — so covering one says nothing
     * about the other.
     */
    private static void testSquashSerializationContract()
    {
        ModelIKConfig.Chain squashed = new ModelIKConfig.Chain("tip", "target", 0, true, "pole", 0F, 0F, 1F, true, false, false, false, true);
        ModelIKConfig.Chain plain = new ModelIKConfig.Chain("tip", "target", 0, true, "pole", 0F, 0F, 1F, true, false, false, false, false);

        /* Wrapped, the shape toData writes. */
        MapType wrapped = ModelIKIO.toData(new ModelIKConfig(List.of(squashed), Collections.emptyMap()));
        MapType wrappedEntry = wrapped.getMap("chains").getMap("tip");

        require(wrappedEntry.has("squash"), "squash=true was not written under the key \"squash\"");
        require(wrappedEntry.getBool("squash", false), "squash=true was written as false");
        assertSquashRoundTrip(wrapped, "wrapped", true);

        MapType wrappedPlain = ModelIKIO.toData(new ModelIKConfig(List.of(plain), Collections.emptyMap()));
        MapType wrappedPlainEntry = wrappedPlain.getMap("chains").getMap("tip");

        require(wrappedPlainEntry.has("target"),
            "the squash=false fixture did not serialize its chain at all, so the sparse check below is vacuous");
        require(!wrappedPlainEntry.has("squash"),
            "squash=false wrote the key anyway, so a chain that never used the feature no longer serializes sparsely");
        assertSquashRoundTrip(wrappedPlain, "wrapped", false);

        /* Non-wrapped: the chains map itself is the root, which is the legacy shape. */
        MapType flat = new MapType();
        flat.put("tip", wrappedEntry);
        MapType flatPlain = new MapType();
        flatPlain.put("tip", wrappedPlainEntry);

        require(flat.has("tip", BaseType.TYPE_MAP),
            "the non-wrapped fixture is not shaped as a flat map of tip entries, so this half of the check is vacuous");

        assertSquashRoundTrip(flat, "non-wrapped", true);
        assertSquashRoundTrip(flatPlain, "non-wrapped", false);
    }

    /** `fromData` must read {@code squash} back as {@code expected} from this data shape. */
    private static void assertSquashRoundTrip(MapType data, String shape, boolean expected)
    {
        ModelIKConfig reloaded = ModelIKIO.fromData(data);

        require(reloaded != null && reloaded.chains() != null && reloaded.chains().size() == 1,
            "a chain carrying squash=" + expected + " did not survive a " + shape + " toData/fromData round trip");
        require(reloaded.chains().get(0).squash() == expected,
            "the " + shape + " path read squash back as " + reloaded.chains().get(0).squash()
                + " where the data said " + expected);
    }

    /**
     * {@code ModelIKCache.compile} copies the three behaviour flags out of a {@code Chain} and
     * into a {@code CompiledChain} — and the two records do NOT list them in the same order:
     *
     * <pre>
     *   ModelIKConfig.Chain  : (…, stretch, classic, squash)   // classic BEFORE squash
     *   ModelIKCache.CompiledChain : (…, stretch, squash, classic)   // squash BEFORE classic
     * </pre>
     *
     * All three are {@code boolean}, so swapping any two of them still compiles: the compiler
     * cannot tell, and nothing else in this file would notice, because every other
     * {@code CompiledChain} here is built by hand and never travels through {@code compile()}.
     * The one runtime path that does go through it ({@code isRotationConstrained}) reads the
     * joint degrees of freedom, not these flags. That is exactly the shape of a
     * "looks wired up, but breaking it goes green" hole, so this asserts the forwarding itself.
     *
     * <p>The triple is asserted as a whole rather than flag by flag: with {@code (stretch,
     * squash, classic) = (false, true, false)} every one of the six permutations of the flags
     * puts the single {@code true} on a different component, so no mis-ordered pair can pass.
     */
    private static void testCompileForwardsTheChainBehaviourFlags()
    {
        CubicFixture fixture = cubicFixture();
        ModelIKConfig.Chain chain = new ModelIKConfig.Chain(
            "tip", "target", 0, true, "pole", 0F, 0F, 1F, true, false, false, false, true
        );
        MapType data = ModelIKIO.toData(new ModelIKConfig(List.of(chain), Collections.emptyMap()));

        /* Read the config back the same way the cache does, so the fixture is known to carry
         * squash=true in the shape compile() will see. */
        require(ModelIKIO.fromData(data).chains().get(0).squash(),
            "the compile() fixture lost squash=true before it reached the cache, so this check is vacuous");

        ModelIKCache.Compiled compiled = ModelIKCache.getFromData(fixture.model, data);

        require(compiled != null && compiled.chains() != null && compiled.chains().size() == 1,
            "compile() did not produce exactly one chain for a chain that satisfies every filter, "
                + "so the flags below cannot be inspected");

        ModelIKCache.CompiledChain forwarded = compiled.chains().get(0);

        require(!forwarded.stretch() && forwarded.squash() && !forwarded.classic(),
            "compile() did not forward the chain's behaviour flags in order: compiled as (stretch="
                + forwarded.stretch() + ", squash=" + forwarded.squash() + ", classic=" + forwarded.classic()
                + ") where the config said (stretch=false, squash=true, classic=false). "
                + "Chain is (…, stretch, classic, squash) but CompiledChain is "
                + "(…, stretch, squash, classic) — the two records order these booleans differently");
    }

    /**
     * Stretch and squash answer to opposite halves of the gap and nothing else. The table is
     * built so that EXACTLY ONE of the two flags is on in every cell: with both on, a broken
     * implementation that reads the wrong flag would still telescope and the cell would pass
     * without testing anything. The all-off column is the negative control — nothing may move,
     * in either direction.
     *
     * <p>Both solvers are covered, and the classic two-bone path is driven directly rather than
     * through the applier: `ClassicLimbSolver.apply` falls back to the core solver when it
     * declines, and a fall back produces the same offsets, so going through the applier could
     * not tell the two apart. Each classic cell asserts the return value instead (see
     * {@link #telescopeLength}), which is what rules out measuring the core solver twice.
     */
    private static void testStretchAndSquashAreIndependent()
    {
        for (boolean classic : new boolean[] {false, true})
        {
            String path = classic ? "classic two-bone" : "core";

            require(telescopeLength(classic, true, false, false) > TELESCOPE_MIN,
                path + ": stretch no longer telescopes a chain that fell SHORT of its controller, so the "
                    + "feature lost the half it was written for");
            require(telescopeLength(classic, false, true, false) <= TELESCOPE_MIN,
                path + ": squash telescoped a chain that fell SHORT — squash must not answer to a shortfall");

            require(telescopeLength(classic, false, true, true) > TELESCOPE_MIN,
                path + ": squash no longer folds in a chain that OVERSHOT its controller, so a near goal "
                    + "still leaves the tip swung past it");
            require(telescopeLength(classic, true, false, true) <= TELESCOPE_MIN,
                path + ": stretch folded in a chain that OVERSHOT — stretch must only answer to a shortfall");

            /* Negative control: with both boxes off the chain may not telescope in EITHER
             * direction. It must not go red under any of the production mutations (they only
             * move which flag answers to which half), and it going red would mean the gate
             * leaks an offset for a chain that asked for neither. */
            for (boolean overshoot : new boolean[] {false, true})
            {
                require(telescopeLength(classic, false, false, overshoot) <= TELESCOPE_MIN,
                    path + ": a chain with BOTH stretch and squash off still telescoped on "
                        + (overshoot ? "an overshoot" : "a shortfall") + " input");
            }
        }
    }

    /** Anything a solve writes is metres/units of a limb, so this is far above float noise and far below a real shift. */
    private static final float TELESCOPE_MIN = 1.0e-4F;

    /**
     * One solve on {@link #unequalMultiFixture()}: the length of the largest telescope offset it wrote,
     * or {@code 0} when it wrote none. Every cell of the table — including the all-off control, whose
     * whole claim is "nothing happened" — asserts here that the solve actually ran and wrote an
     * orientation, so a silent no-op cannot be read as "correctly did not telescope".
     */
    private static float telescopeLength(boolean classic, boolean stretch, boolean squash, boolean overshoot)
    {
        CubicMultiFixture fixture = unequalMultiFixture();
        List<String> workIds = List.of("root", "mid", "tip");
        Map<String, CubicRenderer.PivotFrame> frames = new HashMap<>();

        ModelPivotFrames.collect(fixture.model, Set.of("root", "mid", "tip", "target", "pole"), frames, null);

        Vector3f root = new Vector3f(frames.get("root").position());
        Vector3f radial = new Vector3f(frames.get("tip").position()).sub(root);
        float first = root.distance(frames.get("mid").position());
        float second = frames.get("mid").position().distance(frames.get("tip").position());
        float near = 0.25F * radial.length();
        float far = 3F * radial.length();

        /* Guards on the INPUT geometry, not on the production classification: the near
         * target has to sit inside the band a two-bone chain cannot fold onto, and the
         * far one outside its reach. A chain can only fold down to the DIFFERENCE of its
         * segment lengths, so these two numbers are what make an overshoot exist at all —
         * without them the "overshoot" case could quietly be an ordinary shortfall and the
         * table would pass while testing nothing. */
        require(near < Math.abs(first - second),
            "fixture guard: the near target (" + near + ") is not inside the chain's unreachable fold band ("
                + Math.abs(first - second) + "), so the overshoot case would not overshoot");
        require(far > first + second,
            "fixture guard: the far target (" + far + ") is not beyond the chain's reach ("
                + (first + second) + "), so the shortfall case would not fall short");

        Vector3f target = new Vector3f(root).fma(overshoot ? 0.25F : 3F, radial);

        if (classic)
        {
            require(ClassicLimbSolver.eligible(workIds),
                "the unequal two-bone fixture is not eligible for the classic solver, so this case would "
                    + "silently measure the core solver");
            require(ClassicLimbSolver.apply(fixture.model, workIds, frames, target, null,
                    new Vector3f(frames.get("pole").position()), 0F, 0F, 1F, stretch, squash),
                "the classic solver declined a fixture it must be able to solve, so this case would "
                    + "silently measure the core solver");
        }
        else
        {
            applyToTarget(fixture.model, chainWithStretchAndSquash(stretch, squash), target);
        }

        float length = 0F;

        for (String bone : new String[] {"mid", "tip"})
        {
            Vector3f offset = fixture.model.getGroup(bone) == null ? null : fixture.model.getGroup(bone).offset;

            if (offset != null)
            {
                length = Math.max(length, offset.length());
            }
        }

        /* The solve ran -- on the classic path this is the branch that would otherwise be
         * invisible, since its failure mode is to hand the chain back to the core solver. The
         * return value above is asserted for that; this is the second, independent witness that
         * an orientation reached the bones, and it is what makes a 0 reading mean "the gate
         * declined" rather than "nothing was solved at all". */
        require(fixture.model.getGroup("mid").orient != null || fixture.model.getGroup("tip").orient != null,
            "no orientation was written for the " + (classic ? "classic" : "core") + " solve, so this cell's "
                + "offset reading (" + length + ") says nothing about the gate");

        System.out.println("  " + (classic ? "classic" : "core   ")
            + " | target " + String.format(java.util.Locale.ROOT, "%.5f", target.distance(root))
            + " of reach " + String.format(java.util.Locale.ROOT, "%.5f", first + second)
            + " (fold floor " + String.format(java.util.Locale.ROOT, "%.5f", Math.abs(first - second)) + ")"
            + " | stretch=" + stretch + " squash=" + squash + " " + (overshoot ? "OVERSHOOT" : "SHORT   ")
            + " -> telescope " + String.format(java.util.Locale.ROOT, "%.5f", length));

        return length;
    }

    /**
     * The applier's production call shape for a chain driven by a resolved controller
     * position — the film's target override — with no workspace, which is what makes
     * the target absolute instead of a bind-relative delta.
     */
    private static void applyToTarget(IModel model, ModelIKCache.CompiledChain chain, Vector3f target)
    {
        ModelIKDlsApplier.apply(
            model,
            List.of(chain),
            null,
            Map.of("target", new Vector3f(target)),
            null,
            null,
            null,
            null,
            (List<ModelIKChainWorkspace>) null,
            Collections.emptyMap()
        );
    }

    private static ModelIKCache.CompiledChain chainWithStretchAndSquash(boolean stretch, boolean squash)
    {
        /* "target" must be in the wanted set even though this solve drives the controller by
         * an override: the applier still reads the target bone's frame, and a chain whose
         * target frame is missing is refused before it ever reaches the solve. */
        Set<String> wanted = new HashSet<>(List.of("root", "mid", "tip", "target", "pole"));

        return new ModelIKCache.CompiledChain(
            "tip", "target", true, "pole", 0F, 0F, 1F, false, stretch, squash, false,
            List.of("root", "mid", "tip"), List.of("root", "mid", "tip"), null, Set.copyOf(wanted), 0
        );
    }

    /**
     * A two-bone chain whose bones differ a great deal in length. That difference IS the
     * test fixture: a chain can only fold down to the difference of its segment lengths,
     * so a long/short pair leaves a wide band of goals the tip cannot reach without
     * swinging past them — the only place an overshoot exists at all. A near-equal pair
     * folds to almost nothing and has no such band.
     *
     * <p>Cubic pivots are ABSOLUTE model-space points (the renderer derives a bone's vector
     * from the pivot difference), so the tip is placed at its own absolute spot, not at an
     * offset from its parent.
     */
    private static CubicMultiFixture unequalMultiFixture()
    {
        Model model = new Model(new MolangParser());
        ModelGroup root = group("root", 0F, 0F, 0F);
        ModelGroup mid = group("mid", 0F, -16F, 0F);
        ModelGroup tip = group("tip", 0F, -17F, 0F);
        ModelGroup target = group("target", 0F, -17F, 0F);
        ModelGroup pole = group("pole", 16F, -8F, 0F);

        root.children.add(mid);
        mid.children.add(tip);
        model.topGroups.add(root);
        model.topGroups.add(target);
        model.topGroups.add(pole);
        model.initialize();
        model.resetPose();

        return new CubicMultiFixture(model, root, mid, tip, target, pole);
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
        applyWithoutWorkspace(model, chain, Collections.emptyMap());
    }

    /**
     * The render path's call shape with a constraint stack attached. Because no workspace is
     * passed, {@code ModelIKDlsApplier.resolveChain:470-473} takes the controller's world
     * rotation directly instead of the bind-delta branch, which makes the "tip wins" contract
     * directly observable.
     */
    private static void applyWithoutWorkspace(IModel model, ModelIKCache.CompiledChain chain, Map<String, BoneConstraint> limits)
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
            limits
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

        /* All 16 components explicit: this helper exercises the tip/orientation path, so it
         * deliberately leaves stretch, squash and classic OFF (both via the solver flags and
         * via the compiled chain). */
        return new ModelIKCache.CompiledChain(
            ids.get(ids.size() - 1), "target", pole, "pole", 0F, 0F, 1F, tipRotation,
            false /* stretch */, false /* squash */, false /* classic */,
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
     * The applier's effector contract: with {@code tipRotation} on, the effector bone is
     * written from the controller's world orientation by the tip-follow snap, and that
     * written orientation is deliberately NOT clamped to the effector's own
     * {@link BoneConstraint}. The effector is not a solver joint, the joint-space limits
     * handed to the solver therefore never reach it, and upstream
     * ({@code da84e5f8f}, "tip orientation wins") does not clamp it either — so the limit
     * is inert on this bone by design. See
     * {@link #testEffectorTracksControllerAndIsNotClamped} for the sweep that pins the
     * resulting one-for-one tracking and explains why clamping here would be a regression.
     *
     * <p>Both halves of THIS helper matter: the fixtures here turn their controller by 90
     * degrees, so an effector that stayed at rest would mean the snap never ran at all, and
     * one sitting inside the authored range would mean a clamp had come back. The "did it
     * run" check is an explicit angle rather than {@link #sameRotation}: a 5-degree rotation
     * is only 0.99905 away from identity, inside that helper's {@code EPS} band, so with a
     * tight limit the two are indistinguishable by dot product.
     */
    private static void assertEffectorFollowsController(Quaternionf effector, float limit, String label)
    {
        require(effector != null, label + " was never written");

        float swung = angleBetweenDegrees(effector, new Quaternionf());

        require(swung > 0.5F,
            label + " stayed at rest despite a 90-degree controller turn: the tip snap did not run");
        require(violationDegrees(effector, limit) > 1F,
            label + " stayed inside its own +/-" + limit + "-degree constraint: the effector is being "
                + "clamped, which upstream ('tip orientation wins') does not do and which breaks the exact "
                + "parent cancellation the tip follow relies on");
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
