package mchorse.bbs_mod.cubic.ik;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig.BoneConstraint;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.render.ModelRotationBlender;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderSpace;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.joml.Matrices;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix3f;
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
        testFormRenderingSimulationPolicy();
        testRendererCleanupOnEarlyReturnAndFailure();
        testControlSoftnessDefault();
        testHardAndSoftReach();
        testSolverWorkspaceReuseAndRewind();
        testStraightRestoreAndBendHysteresis();
        testPoleBindingLifecycle();
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
        ValueInt oldOverlayCount = BBSSettings.recordingPoseTransformOverlays;

        if (oldOverlayCount == null)
        {
            BBSSettings.recordingPoseTransformOverlays = new ValueInt("test_pose_transform_overlays", 0);
        }

        TrackingForm form;

        try
        {
            form = new TrackingForm();
        }
        finally
        {
            if (oldOverlayCount == null)
            {
                BBSSettings.recordingPoseTransformOverlays = null;
            }
        }

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

    private static void testHardAndSoftReach()
    {
        IKSolver.Workspace workspace = new IKSolver.Workspace();
        List<Vector3f> hard = lineChain();

        IKSolver.solve(hard, new Vector3f(3F, 0F, 0F), false, null, 0F, 0F, 12, 1.0e-5F, null, null, null, false, null, workspace);
        require(close(hard.get(2).distance(hard.get(0)), 2F), "omitted/hard softness did not reach exact chain length");

        List<Vector3f> soft = lineChain();
        IKSolver.solve(soft, new Vector3f(3F, 0F, 0F), false, null, 0F, 0.2F, 12, 1.0e-5F, null, null, null, false, null, workspace);

        float softReach = soft.get(2).distance(soft.get(0));
        require(softReach < 2F - 1.0e-4F && softReach > 1.9F, "explicit soft IK no longer preserves asymptotic reach");
    }

    private static void testSolverWorkspaceReuseAndRewind()
    {
        IKSolver.Workspace workspace = new IKSolver.Workspace();

        workspace.reset();
        Vector3f firstVector = workspace.vector();
        Quaternionf firstQuaternion = workspace.quaternion();
        Matrix3f firstMatrix = workspace.matrix();
        int vectorMark = workspace.vectorMark();
        int quaternionMark = workspace.quaternionMark();
        int matrixMark = workspace.matrixMark();
        Vector3f branchVector = workspace.vector();
        Quaternionf branchQuaternion = workspace.quaternion();
        Matrix3f branchMatrix = workspace.matrix();

        branchVector.set(4F, 5F, 6F);
        branchQuaternion.rotateXYZ(0.1F, 0.2F, 0.3F);
        branchMatrix.rotateXYZ(0.1F, 0.2F, 0.3F);
        workspace.rewind(vectorMark, quaternionMark, matrixMark);

        require(workspace.vector() == branchVector, "workspace rewind allocated a replacement vector");
        require(workspace.quaternion() == branchQuaternion, "workspace rewind allocated a replacement quaternion");
        require(workspace.matrix() == branchMatrix, "workspace rewind allocated a replacement matrix");
        require(branchVector.equals(new Vector3f()), "rewound vector was not cleared before reuse");
        require(branchQuaternion.equals(new Quaternionf()), "rewound quaternion was not reset before reuse");
        require(branchMatrix.equals(new Matrix3f()), "rewound matrix was not reset before reuse");

        float[] lengths = workspace.lengths(8);
        Quaternionf[] parentFrames = workspace.parentFrames(8);

        require(workspace.lengths(4) == lengths, "workspace rebuilt a warm length array for a smaller chain");
        require(workspace.parentFrames(4) == parentFrames, "workspace rebuilt warm parent frames for a smaller chain");

        List<Vector3f> firstSolve = fourPointChain();
        IKSolver.solve(firstSolve, new Vector3f(2F, 1F, 0.5F), false, null, 0F, 0F,
            12, 1.0e-5F, null, null, null, false, null, workspace);
        int usedVectors = workspace.vectorMark();
        int usedQuaternions = workspace.quaternionMark();
        int usedMatrices = workspace.matrixMark();
        List<Vector3f> secondSolve = fourPointChain();

        IKSolver.solve(secondSolve, new Vector3f(2F, 1F, 0.5F), false, null, 0F, 0F,
            12, 1.0e-5F, null, null, null, false, null, workspace);

        require(workspace.vectorMark() == usedVectors
                && workspace.quaternionMark() == usedQuaternions
                && workspace.matrixMark() == usedMatrices,
            "identical warm solves consumed different scratch ranges");

        for (int i = 0; i < firstSolve.size(); i++)
        {
            require(firstSolve.get(i).distance(secondSolve.get(i)) <= EPS,
                "workspace reuse changed deterministic solve output at joint " + i);
        }

        workspace.reset();
        require(workspace.vector() == firstVector, "warm solve discarded its first vector slot");
        require(workspace.quaternion() == firstQuaternion, "warm solve discarded its first quaternion slot");
        require(workspace.matrix() == firstMatrix, "warm solve discarded its first matrix slot");
    }

    private static void testStraightRestoreAndBendHysteresis()
    {
        IKSolver.Workspace solverWorkspace = new IKSolver.Workspace();

        for (float degrees : new float[] {0F, 2F, 3F})
        {
            List<Vector3f> solved = solveAtPredictedBend(degrees, 0F, solverWorkspace);

            require(crossMagnitude(solved) <= 1.0e-6F,
                "hard IK failed exact straight restore at " + degrees + " degrees");
            require(close(solved.get(0).distance(solved.get(2)), 2F),
                "straight restore compressed the authored-straight chain at " + degrees + " degrees");
        }

        List<Vector3f> fourDegrees = solveAtPredictedBend(4F, 0F, solverWorkspace);
        require(crossMagnitude(fourDegrees) > 1.0e-3F, "hard IK did not leave the straight dead zone above 3 degrees");
        require(fourDegrees.get(2).distance(targetForPredictedBend(4F, 1F, 1F)) <= EPS,
            "normal bending above the dead zone no longer reaches the target");

        List<Vector3f> explicitSoftness = solveAtPredictedBend(2F, 0.2F, solverWorkspace);
        require(crossMagnitude(explicitSoftness) > 1.0e-3F,
            "explicit softness was incorrectly snapped by hard-IK straight restore");

        CubicMultiFixture fixture = cubicMultiFixture(false);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        ModelIKApplier.ChainWorkspace chainWorkspace = new ModelIKApplier.ChainWorkspace();

        apply(fixture.model, chain, chainWorkspace, null, Collections.emptyMap());

        Vector3f root = new Vector3f(chainWorkspace.positions.get(0));
        Vector3f axis = new Vector3f(chainWorkspace.positions.get(2)).sub(root).normalize();
        float firstLength = chainWorkspace.positions.get(0).distance(chainWorkspace.positions.get(1));
        float secondLength = chainWorkspace.positions.get(1).distance(chainWorkspace.positions.get(2));

        applyTargetAtBend(fixture.model, chain, chainWorkspace, root, axis, firstLength, secondLength, 2F);
        require(chainWorkspace.recoveringBend, "2-degree solve did not enter bend recovery");
        require(chainWorkspace.hasStableBend, "straight recovery did not retain a stable bend side");
        Vector3f stableNormal = new Vector3f(chainWorkspace.stableBendNormal);

        fixture.model.resetPose();
        applyTargetAtBend(fixture.model, chain, chainWorkspace, root, axis, firstLength, secondLength, 10F);
        require(chainWorkspace.recoveringBend, "bend recovery left hysteresis before 20 degrees");
        require(stableNormal.dot(chainWorkspace.stableBendNormal) > 0.99F,
            "bend side flipped inside the hysteresis band");

        fixture.model.resetPose();
        applyTargetAtBend(fixture.model, chain, chainWorkspace, root, axis, firstLength, secondLength, 21F);
        require(!chainWorkspace.recoveringBend, "bend recovery did not release above 20 degrees");
    }

    private static void testPoleBindingLifecycle()
    {
        CubicFixture fixture = cubicFixture();
        ModelIKCache.CompiledChain chain = compiledChain(false);
        ModelIKApplier.ChainWorkspace workspace = new ModelIKApplier.ChainWorkspace();
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

    private static void testRealPoleDelta()
    {
        CubicMultiFixture fixture = cubicMultiFixture(true);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), true, false);
        ModelIKApplier.ChainWorkspace workspace = new ModelIKApplier.ChainWorkspace();

        apply(fixture.model, chain, workspace, null, Collections.emptyMap());

        require(workspace.bindingValid && workspace.bindPoleValid, "pole chain did not establish a real bind baseline");
        require(fixture.root.orient == null && fixture.mid.orient == null,
            "bound pole manufactured a pose change without controller movement");

        Vector3f root = new Vector3f(workspace.positions.get(0));
        Vector3f axis = new Vector3f(workspace.positions.get(2)).sub(root).normalize();
        Vector3f initialNormal = bendNormal(workspace.positions);
        Vector3f boundPole = new Vector3f(workspace.frames.get("pole").position());
        Quaternionf quarterTurn = new Quaternionf().fromAxisAngleRad(axis.x, axis.y, axis.z, (float) (Math.PI / 2D));
        Vector3f movedPole = quarterTurn.transform(boundPole.sub(root)).add(root);
        Vector3f expectedNormal = quarterTurn.transform(new Vector3f(initialNormal)).normalize();

        ModelIKApplier.apply(
            fixture.model,
            List.of(chain),
            List.of(workspace),
            null,
            Map.of("pole", movedPole),
            null,
            null,
            null,
            Collections.emptyMap()
        );

        require(fixture.root.orient != null && fixture.mid.orient != null,
            "moving a bound pole did not produce a procedural pose");
        require(workspace.bendNormal.lengthSquared() > 0.99F,
            "moving a bound pole did not produce a normalized bend plane");
        require(workspace.bendNormal.dot(expectedNormal) > 0.98F,
            "pole movement was not applied as the signed relative angular delta");
    }

    private static void testCubicShortChainTipLimit()
    {
        CubicFixture fixture = cubicFixture();
        ModelIKCache.CompiledChain chain = compiledChain(false);
        ModelIKApplier.ChainWorkspace workspace = new ModelIKApplier.ChainWorkspace();
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
        assertWithin(fixture.tip.orient, 5F, "cubic tip final limit");
    }

    private static void testBobjShortChainTipLimit()
    {
        BobjFixture fixture = bobjFixture();
        ModelIKCache.CompiledChain chain = compiledChain(false);
        ModelIKApplier.ChainWorkspace workspace = new ModelIKApplier.ChainWorkspace();
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
        assertWithin(fixture.tip.orient, 5F, "BOBJ tip final limit");
    }

    private static void testCubicMultiBoneTipLimit()
    {
        CubicMultiFixture fixture = cubicMultiFixture(false);
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        ModelIKApplier.ChainWorkspace workspace = new ModelIKApplier.ChainWorkspace();
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
        assertWithin(fixture.tip.orient, 5F, "multi-bone cubic tip final limit");
    }

    private static void testBobjMultiBoneTipLimit()
    {
        BobjMultiFixture fixture = bobjMultiFixture();
        ModelIKCache.CompiledChain chain = compiledChain(List.of("root", "mid", "tip"), false, true);
        ModelIKApplier.ChainWorkspace workspace = new ModelIKApplier.ChainWorkspace();
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
        assertWithin(fixture.tip.orient, 5F, "multi-bone BOBJ tip final limit");
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

    private static void apply(Object model, ModelIKCache.CompiledChain chain, ModelIKApplier.ChainWorkspace workspace, Map<String, IKControl> controls, Map<String, BoneConstraint> limits)
    {
        ModelIKApplier.apply(
            (mchorse.bbs_mod.cubic.IModel) model,
            List.of(chain),
            List.of(workspace),
            null,
            null,
            null,
            null,
            controls,
            limits
        );
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

    private static void applyTargetAtBend(Model model, ModelIKCache.CompiledChain chain, ModelIKApplier.ChainWorkspace workspace,
        Vector3f root, Vector3f axis, float firstLength, float secondLength, float degrees)
    {
        float distance = targetDistanceForBend(degrees, firstLength, secondLength);
        Vector3f target = new Vector3f(root).fma(distance, axis);

        ModelIKApplier.apply(
            model,
            List.of(chain),
            List.of(workspace),
            Map.of("target", target),
            null,
            null,
            null,
            null,
            Collections.emptyMap()
        );
    }

    private static List<Vector3f> solveAtPredictedBend(float degrees, float softness, IKSolver.Workspace workspace)
    {
        List<Vector3f> positions = lineChain();
        Vector3f target = targetForPredictedBend(degrees, 1F, 1F);

        IKSolver.solve(
            positions,
            target,
            false,
            null,
            0F,
            softness,
            12,
            1.0e-5F,
            null,
            null,
            new Vector3f(0F, 0F, 1F),
            true,
            new Vector3f(),
            workspace
        );

        return positions;
    }

    private static Vector3f targetForPredictedBend(float degrees, float firstLength, float secondLength)
    {
        float distance = targetDistanceForBend(degrees, firstLength, secondLength);

        /* Probe the inclusive 3-degree boundary from its legal side despite the
         * final float round-trip through distance -> cosine in the solver. */
        if (Math.abs(degrees - 3F) <= 1.0e-6F)
        {
            distance += 1.0e-6F;
        }

        return new Vector3f(distance, 0F, 0F);
    }

    private static float targetDistanceForBend(float degrees, float firstLength, float secondLength)
    {
        float radians = (float) Math.toRadians(degrees);

        return (float) Math.sqrt(
            firstLength * firstLength
                + secondLength * secondLength
                + 2F * firstLength * secondLength * (float) Math.cos(radians)
        );
    }

    private static float crossMagnitude(List<Vector3f> positions)
    {
        Vector3f first = new Vector3f(positions.get(1)).sub(positions.get(0));
        Vector3f second = new Vector3f(positions.get(2)).sub(positions.get(1));

        return first.cross(second).length();
    }

    private static Vector3f bendNormal(List<Vector3f> positions)
    {
        Vector3f root = positions.get(0);
        Vector3f first = new Vector3f(positions.get(1)).sub(root);
        Vector3f reach = new Vector3f(positions.get(2)).sub(root);

        return first.cross(reach).normalize();
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

    private static List<Vector3f> lineChain()
    {
        return new ArrayList<>(List.of(
            new Vector3f(0F, 0F, 0F),
            new Vector3f(1F, 0F, 0F),
            new Vector3f(2F, 0F, 0F)
        ));
    }

    private static List<Vector3f> fourPointChain()
    {
        return new ArrayList<>(List.of(
            new Vector3f(0F, 0F, 0F),
            new Vector3f(1F, 0.2F, 0F),
            new Vector3f(2F, 0.1F, 0.2F),
            new Vector3f(3F, 0F, 0F)
        ));
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
