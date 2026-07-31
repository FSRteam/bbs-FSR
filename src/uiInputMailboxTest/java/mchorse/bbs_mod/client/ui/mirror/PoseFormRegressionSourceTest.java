package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.utils.GizmoDrag;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level contracts for pose and MobForm regressions that require a live renderer to reproduce. */
public final class PoseFormRegressionSourceTest
{
    private static final String POSE_EDITOR = "src/client/java/mchorse/bbs_mod/ui/utils/pose/UIPoseEditor.java";
    private static final String PROVIDER = "src/client/java/mchorse/bbs_mod/forms/CustomVertexConsumerProvider.java";
    private static final String QUEUE = "src/client/java/mchorse/bbs_mod/forms/FormTranslucentQueue.java";
    private static final String MOB_RENDERER = "src/client/java/mchorse/bbs_mod/forms/renderers/MobFormRenderer.java";
    private static final String GIZMO_DRAG = "src/client/java/mchorse/bbs_mod/ui/utils/GizmoDrag.java";
    private static final String PROP_TRANSFORM = "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UIPropTransform.java";
    private static final String POSE_KEYFRAME = "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIPoseKeyframeFactory.java";
    private static final String GLINT_KEYFRAME = "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIGlintKeyframeFactory.java";
    private static final String GENERAL_FORM_PANEL = "src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIGeneralFormPanel.java";
    private static final String FORM = "src/main/java/mchorse/bbs_mod/forms/forms/Form.java";
    private static final String FORM_PROPERTIES = "src/main/java/mchorse/bbs_mod/film/replays/FormProperties.java";
    private static final String FORM_EDITOR = "src/client/java/mchorse/bbs_mod/ui/forms/editors/UIFormEditor.java";
    private static final String MOB_FORM_EDITOR = "src/client/java/mchorse/bbs_mod/ui/forms/editors/forms/UIMobForm.java";
    private static final String STATE_EDITOR = "src/client/java/mchorse/bbs_mod/ui/forms/editors/states/keyframes/UIAnimationStateEditor.java";
    private static final String FILM_EDITOR = "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditorUtils.java";
    private static final String GIZMO = "src/client/java/mchorse/bbs_mod/ui/utils/Gizmo.java";
    private static final String FILM_CONTROLLER = "src/client/java/mchorse/bbs_mod/ui/film/controller/UIFilmController.java";
    private static final String FILM_BASE = "src/client/java/mchorse/bbs_mod/film/BaseFilmController.java";
    private static final String FORM_RENDERER = "src/client/java/mchorse/bbs_mod/ui/forms/editors/utils/UIPickableFormRenderer.java";

    private PoseFormRegressionSourceTest()
    {}

    public static void runAll()
    {
        Path root = findProjectRoot();
        String editor = read(root.resolve(POSE_EDITOR));
        String provider = read(root.resolve(PROVIDER));
        String queue = read(root.resolve(QUEUE));
        String renderer = read(root.resolve(MOB_RENDERER));
        String gizmoDrag = read(root.resolve(GIZMO_DRAG));
        String propTransform = read(root.resolve(PROP_TRANSFORM));
        String poseKeyframe = read(root.resolve(POSE_KEYFRAME));
        String glintKeyframe = read(root.resolve(GLINT_KEYFRAME));
        String generalFormPanel = read(root.resolve(GENERAL_FORM_PANEL));
        String form = read(root.resolve(FORM));
        String formProperties = read(root.resolve(FORM_PROPERTIES));
        String formEditor = read(root.resolve(FORM_EDITOR));
        String mobFormEditor = read(root.resolve(MOB_FORM_EDITOR));
        String stateEditor = read(root.resolve(STATE_EDITOR));
        String filmEditor = read(root.resolve(FILM_EDITOR));
        String gizmo = read(root.resolve(GIZMO));
        String filmController = read(root.resolve(FILM_CONTROLLER));
        String filmBase = read(root.resolve(FILM_BASE));
        String formRenderer = read(root.resolve(FORM_RENDERER));

        gizmoPlacementIsIndependentOfCursorPosition(gizmo, filmController, filmBase);
        hiddenAxesStillRefreshGizmoPlacement(formRenderer);
        filmBoneConsumersShareOnePlacementSample(filmBase, filmEditor);
        mirrorEditingUsesSelectionDeltas(editor);
        filmPoseEditingUsesSelectionDeltas(poseKeyframe);
        glintUsesDedicatedKeyframeTrack(editor, poseKeyframe, glintKeyframe, generalFormPanel, form, formProperties, filmEditor, stateEditor);
        deferredLayersCapturePreparation(provider, queue);
        matrixSamplingPreservesFeaturePreparation(renderer);
        translationSamplingUsesLivePose(gizmoDrag, propTransform);
        mobTranslationUsesStableModelPartBasis(formEditor, mobFormEditor, stateEditor, filmEditor);
        modelPartJacobianPreservesParentBasis();
        modelPartJacobianSurvivesConditioning();
        unusableJacobianFallsBackInsteadOfFreezing();
        renderOnlyPlayersCannotPush(renderer);
        mobRenderReleasesSharedStateOnFailure(renderer);
    }

    /**
     * The sort origin, the vertex-provider hijack and both matrix stacks are process-wide state that
     * every later form in the frame reads. A vanilla entity render can throw - malformed NBT, a
     * third-party entity renderer - so releasing them only on the success path leaves the rest of
     * the frame drawing with MobForm's texture binding on an unbalanced stack.
     */
    private static void mobRenderReleasesSharedStateOnFailure(String renderer)
    {
        String render3D = section(renderer,
            "protected void render3D(FormRenderingContext context)",
            "private float prepareAnimationRender");

        assertOrdered(render3D,
            "context.stack.pushPose()",
            "try",
            "getEntityRenderDispatcher().render(",
            "finally",
            "FormTranslucentQueue.setSortOrigin(null)",
            "CustomVertexConsumerProvider.clearRunnables()",
            "context.stack.popPose()");

        String beforeFinally = render3D.substring(0, render3D.indexOf("finally"));

        check(!beforeFinally.contains("CustomVertexConsumerProvider.clearRunnables()")
                && !beforeFinally.contains("context.stack.popPose()"),
            "MobForm world rendering releases the layer hijack or the matrix stack outside its "
                + "finally, so a throwing entity render leaks them into the rest of the frame");
    }

    /**
     * The pick pass runs only while the cursor is inside the preview viewport, so anything it
     * captures makes gizmo dragging depend on cursor position. Placement must come from the visual
     * pass alone, otherwise a MobForm whose equipment layers make the two passes disagree drags
     * against a bone frame at the wrong depth.
     */
    private static void gizmoPlacementIsIndependentOfCursorPosition(String gizmo, String filmController, String filmBase)
    {
        check(filmBase.contains("renderPreviewAxes(context.bone2, context.local2, form, formContext, stack)"),
            "the replay axes preview draws through renderAxes, so it snapshots gizmo placement over "
                + "the bone the user actually drags");
        check(!section(filmBase, "private static void renderPreviewAxes", "private static void renderAnchorGizmo")
                .contains("Gizmo.INSTANCE"),
            "the replay axes preview still touches the shared gizmo placement");

        String stencil = section(gizmo, "public void renderStencil(PoseStack stack, StencilMap map)", "private void drawStencilAxes");

        check(!stencil.contains("captureRenderMatrix"),
            "the gizmo pick pass captures the render matrix, so drag placement follows the cursor "
                + "in and out of the preview viewport");
        check(stencil.contains("this.drawStencilAxes(stack, map)"),
            "the gizmo pick pass no longer draws its handle IDs");
        check(section(gizmo, "public void captureVisual(PoseStack stack)", "public void renderInterface")
                .contains("this.captureRenderMatrix(stack)"),
            "the gizmo visual pass no longer captures placement, leaving nothing to drag against");
        check(filmController.contains("!viewport.isInside(context) || this.controlled != null"),
            "the film pick pass is no longer cursor gated, so this contract no longer applies");
    }

    /**
     * The form editor's F8 toggle only hides the gizmo's visual - its pick stencil keeps running, so
     * a handle stays grabbable. Since the pick pass no longer captures placement, the visual pass has
     * to capture it even in the frames where it draws nothing, or that drag runs on a stale frame.
     */
    private static void hiddenAxesStillRefreshGizmoPlacement(String formRenderer)
    {
        String axes = section(formRenderer, "private void renderAxes(UIContext context)", "private void renderFormHitbox");

        assertOrdered(axes,
            "if (UIBaseMenu.shouldRenderAxes())",
            "Gizmo.INSTANCE.render(stack)",
            "else",
            "Gizmo.INSTANCE.captureVisual(stack)");
        check(!section(formRenderer, "Gizmo.INSTANCE.renderStencil", "this.stencil.pickGUI")
                .contains("captureVisual"),
            "the form editor pick pass captures placement again, reintroducing the cursor dependency");
    }

    /**
     * A gizmo pairs a bone's rotation offset with its composite matrix. Sampling them from separate
     * placements lets the rotation basis disagree with the mesh - for a form with IK or physics bones
     * a second collectMatrices call also builds a second simulation history.
     */
    private static void filmBoneConsumersShareOnePlacementSample(String filmBase, String filmEditor)
    {
        assertOrdered(
            section(filmBase, "public static Vector3f getGizmoBoneRotationOffset", "private static Matrix4f absoluteSemanticMatrix"),
            "sampleBonePlacement(entities, entity, replay, cameraX, cameraY, cameraZ, transition, bonePath)",
            "private static BonePlacement sampleBonePlacement",
            "Object simulationOwner = relative ? relativeSimulationOwner(entity) : entity",
            "renderer.collectMatrices(");
        check(!section(filmBase, "public static Vector3f getGizmoBoneRotationOffset", "private static Matrix4f absoluteSemanticMatrix")
                .contains("collectMatrices(entity, transition)"),
            "the film bone rotation offset samples through the ownerless collectMatrices overload again");
        check(filmEditor.contains("sampleFilmBoneRotationOffset(panel, camera, entity, replay, transition, bone.a)"),
            "the film gizmo drag samples the bone rotation offset outside the shared placement path");
        check(section(filmEditor, "private static Vector3f sampleFilmBoneRotationOffset", "private static void buildAnchorGizmoDrag")
                .contains("applyReplayProperties(panel, entity, replay, transition)"),
            "the film bone rotation offset samples without the replay's animated properties applied");
    }

    private static void mirrorEditingUsesSelectionDeltas(String source)
    {
        check(source.contains("class UIPosePropTransform extends UIDeltaPropTransform"),
            "pose editing no longer applies transform-channel deltas to the selection");
        check(source.contains("resolveBoneEdits(this.isMirrorEdit(), this.isAlternateInvert())"),
            "pose editing no longer dispatches mirror and alternate-invert bone edits");
        check(source.contains("transform.rotate2.mul(1F, -1F, -1F)"),
            "mirror editing no longer reflects secondary bone rotation");
        check(source.contains("transform.rotate2.set(0F, 0F, 0F)"),
            "pose reset no longer clears secondary bone rotation");
    }

    private static void deferredLayersCapturePreparation(String provider, String queue)
    {
        check(provider.contains("Function<RenderType, Runnable> layerPreparations"),
            "deferred render layers no longer snapshot their preparation action");
        check(provider.contains("captureLayerPreparation(layer)"),
            "deferred render commands no longer receive a captured preparation action");
        check(queue.contains("private final Runnable prepare"),
            "deferred render commands no longer retain layer preparation");
        assertOrdered(queue,
            "layer.setupRenderState()",
            "this.prepare.run()",
            "buffer.drawWithShader",
            "layer.clearRenderState()");
    }

    private static void filmPoseEditingUsesSelectionDeltas(String source)
    {
        String transform = section(source, "public static class UIPoseTransforms", "public void endGesture()");

        check(transform.contains("extends UIKeyframePropTransform"),
            "film pose tracks no longer apply keyframe selection deltas");
        check(transform.contains("resolveBoneEdits(this.isMirrorEdit(), this.isAlternateInvert())"),
            "film pose tracks no longer dispatch mirror and alternate-invert bone edits");
        check(transform.contains("this.editor.applyToBone"),
            "film pose tracks no longer apply per-bone mirror semantics");
        check(transform.contains("applyRecordingBones"),
            "film pose mirror edits no longer support transform recording");
    }

    private static void glintUsesDedicatedKeyframeTrack(
        String poseEditor,
        String poseKeyframe,
        String glintKeyframe,
        String generalFormPanel,
        String form,
        String formProperties,
        String filmEditor,
        String stateEditor
    )
    {
        String poseLayout = section(poseKeyframe, "public void resize()", "public static class UIPoseFactoryEditor");

        check(!poseLayout.contains("glintSection"),
            "the pose keyframe panel still mounts the enchantment-layer controls");
        check(generalFormPanel.contains("this.glintSection.setExpanded(false)"),
            "the form editor's enchantment layer is not collapsed by default");
        check(poseEditor.contains("this.glintSection.setExpanded(false)"),
            "the per-bone form editor's enchantment layer is not collapsed by default");
        check(form.contains("this.glintMode.invisible()")
                && form.contains("this.glintColor.invisible()")
                && form.contains("this.glintSpeed.invisible()")
                && form.contains("this.glintTransform.invisible()"),
            "the four low-level form glint properties are still exposed as separate timeline tracks");
        check(glintKeyframe.contains("class UIGlintKeyframeFactory")
                && glintKeyframe.contains("UIKeys.POSE_CONTEXT_GLINT_LAYER")
                && glintKeyframe.contains("GlintControls"),
            "the dedicated enchantment-layer keyframe editor is missing");
        check(formProperties.contains("for (KeyframeChannel value : glintChannels)")
                && formProperties.contains("entry.getValue().apply(pose.get().get(entry.getKey()))"),
            "the dedicated enchantment layer no longer overlays the evaluated pose");
        check(filmEditor.contains("addGlintControlSheet(form, properties, sheets)"),
            "the Film editor no longer contributes the enchantment-layer sheet");
        check(stateEditor.contains("addGlintControlSheet(form, this.state.properties, orderedFormSheets)"),
            "animation states no longer contribute the enchantment-layer sheet");
    }

    private static void matrixSamplingPreservesFeaturePreparation(String source)
    {
        String method = section(source, "private MatrixCache collectBoneMatrices", "private Runnable createWorldLayerPreparation");

        check(!method.contains("layers.clear()") && !source.contains("private void renderForMatrixCollection"),
            "MobForm matrix sampling still suppresses equipment feature preparation");
        check(method.contains("client.getEntityRenderDispatcher().render("),
            "MobForm matrix sampling no longer follows the complete bbs-fs entity render path");
    }

    private static void translationSamplingUsesLivePose(String gizmoDrag, String propTransform)
    {
        String jacobian = section(gizmoDrag, "public static Matrix3f computeTranslateJacobian", "public static Matrix3f computeRotateAxes");

        check(jacobian.contains("new Vector3f(worldPositionSampler.get())"),
            "translation Jacobian no longer samples the live pose before perturbing it");
        check(jacobian.contains("saved.x + epsilon"),
            "translation Jacobian no longer perturbs the current X translation");
        check(jacobian.contains("saved.y + epsilon"),
            "translation Jacobian no longer perturbs the current Y translation");
        check(jacobian.contains("saved.z + epsilon"),
            "translation Jacobian no longer perturbs the current Z translation");
        check(!jacobian.contains("transform.translate.set(0F, 0F, 0F)"),
            "translation Jacobian samples from the rest pose and can diverge with equipment layers");

        check(propTransform.contains("if (dx != 0 || dy != 0)"),
            "ray translation writes transform values before the cursor has moved");
        check(propTransform.contains("GizmoDrag.resolveTranslateJacobian(this.drag.translateJacobian, this.drag.modelPartTranslate)"),
            "ray translation no longer routes its basis through the shared degenerate-Jacobian fallback");

        /* Scoped to the method rather than matched as a multi-line literal: an embedded \n never
         * matches a CRLF checkout, which made the negated form of this assertion pass on Windows no
         * matter what the source said. beginRayTranslateScreen keeps its own early exits for a
         * degenerate view matrix, so only the axis path is asserted here. */
        String rayTranslate = section(propTransform,
            "private void beginRayTranslate(int mouseX, int mouseY)",
            "private void beginRayTranslateScreen(int mouseX, int mouseY)");

        check(rayTranslate.contains("this.resolveTranslateJacobian()"),
            "axis ray translation no longer resolves its basis through the shared fallback");
        check(!rayTranslate.contains("this.dragHasStart = false"),
            "a degenerate translate Jacobian silently cancels the drag instead of falling back");
    }

    private static void mobTranslationUsesStableModelPartBasis(
        String formEditor,
        String mobFormEditor,
        String stateEditor,
        String filmEditor
    )
    {
        check(mobFormEditor.contains("GizmoDrag.computeModelPartTranslateJacobian(origin)"),
            "MobForm pose editing no longer derives translation from the stable ModelPart parent basis");
        check(formEditor.contains("this.editor.getTranslateJacobian(transform, transition)"),
            "form gizmos no longer request the MobForm-specific translation Jacobian");
        check(mobFormEditor.contains("transform == this.getPoseEditor().transform")
                && mobFormEditor.contains("bone == null || bone.isEmpty()"),
            "MobForm root transforms or empty bone selections can incorrectly use ModelPart pixel translation");
        check(!mobFormEditor.contains("this.getEditableTransform()"),
            "MobForm resolves its edited transform through getEditableTransform(), which switches the "
                + "visible panel as a side effect of building a gizmo drag");
        check(formEditor.contains("modelPartTranslate"),
            "form gizmos no longer mark MobForm bone translation as model-pixel scaled");
        check(stateEditor.contains("poseBone") && stateEditor.contains("form instanceof MobForm")
                && stateEditor.contains("computeModelPartTranslateJacobian(origin)")
                && stateEditor.contains("modelPartTranslate"),
            "animation-state MobForm bone tracks no longer isolate translation from equipment rendering");
        check(filmEditor.contains("poseBone") && filmEditor.contains("editedForm instanceof MobForm")
                && filmEditor.contains("computeModelPartTranslateJacobian(origin)")
                && filmEditor.contains("modelPartTranslate"),
            "film MobForm bone tracks no longer isolate translation from equipment rendering");
        check(filmEditor.contains("drag.setJacobian(GizmoDrag.resolveTranslateJacobian(translateJacobian, mobPoseBone))")
                && stateEditor.contains("GizmoDrag.resolveTranslateJacobian(translateJacobian, mobPoseBone)")
                && formEditor.contains("GizmoDrag.resolveTranslateJacobian(translateJacobian, modelPart)"),
            "translate Jacobians no longer pass through the shared degenerate-basis fallback");
        /* The numeric sampler must sit in the non-MobForm branch: keyframe interpolation scales a
         * sampled basis by w, and inverting it amplifies the drag by 1/w. */
        assertOrdered(
            section(filmEditor, "boolean mobPoseBone =", "drag.modelPartTranslate(mobPoseBone)"),
            "if (mobPoseBone)",
            "computeModelPartTranslateJacobian(origin)",
            "else",
            "GizmoDrag.computeTranslateJacobian("
        );
    }

    private static void modelPartJacobianPreservesParentBasis()
    {
        Matrix4f origin = new Matrix4f()
            .rotateXYZ(0.31F, -0.47F, 0.19F)
            .scale(2F, 3F, 4F)
            .translate(7F, -5F, 11F);
        Matrix3f jacobian = GizmoDrag.computeModelPartTranslateJacobian(origin);

        for (int axis = 0; axis < 3; axis++)
        {
            Vector3f unit = new Vector3f(axis == 0 ? 1F : 0F, axis == 1 ? 1F : 0F, axis == 2 ? 1F : 0F);
            Vector3f pixels = new Vector3f(unit).mul(16F);
            Vector3f expected = origin.transformDirection(new Vector3f(unit));
            Vector3f actual = jacobian.transform(pixels);

            check(expected.distance(actual) < 1.0E-6F,
                "16 ModelPart pixels did not map to one parent-space unit on axis " + axis);
        }
    }

    /**
     * The determinant of a model-pixel basis is scaled by 1/16³, so an absolute cut-off rejects
     * healthy MobForm bones (which froze the film gizmo) while still admitting merely ill-conditioned
     * ones (which sent the value to six figures). Both ends have to behave.
     */
    private static void modelPartJacobianSurvivesConditioning()
    {
        Matrix3f pixels = new Matrix3f().rotateXYZ(0.31F, -0.47F, 0.19F).scale(1F / 16F);

        check(Math.abs(pixels.determinant()) < 1.0E-3F,
            "a model-pixel basis is no longer small enough to exercise the conditioning guard");
        check(GizmoDrag.isUsableTranslateJacobian(pixels),
            "a healthy model-pixel translate basis is rejected, which freezes the gizmo");

        Matrix3f smaller = new Matrix3f(pixels).scale(1F / 64F);

        check(GizmoDrag.isUsableTranslateJacobian(smaller),
            "a well conditioned but tiny translate basis is rejected");

        Matrix3f flat = new Matrix3f().set(
            1F, 0F, 0F,
            1F, 0F, 0F,
            0F, 0F, 1F
        );

        check(!GizmoDrag.isUsableTranslateJacobian(flat),
            "a rank deficient translate basis is accepted and inverts into an unbounded drag");
        check(!GizmoDrag.isUsableTranslateJacobian(new Matrix3f().zero()),
            "an all-zero translate basis is accepted");
    }

    /** A rejected basis must degrade to a rest basis in the same units, never cancel the drag. */
    private static void unusableJacobianFallsBackInsteadOfFreezing()
    {
        Matrix3f flat = new Matrix3f().zero();
        Matrix3f modelPart = GizmoDrag.resolveTranslateJacobian(flat, true);
        Matrix3f blocks = GizmoDrag.resolveTranslateJacobian(flat, false);

        check(GizmoDrag.isUsableTranslateJacobian(modelPart) && GizmoDrag.isUsableTranslateJacobian(blocks),
            "the translate Jacobian fallback is itself degenerate");
        check(modelPart.transform(new Vector3f(16F, 0F, 0F)).distance(new Vector3f(1F, 0F, 0F)) < 1.0E-6F,
            "the ModelPart fallback no longer maps 16 pixels onto one block");
        check(blocks.transform(new Vector3f(1F, 0F, 0F)).distance(new Vector3f(1F, 0F, 0F)) < 1.0E-6F,
            "the block-space fallback is not a unit basis");

        Matrix3f usable = new Matrix3f().rotateXYZ(0.2F, 0.4F, -0.1F).scale(1F / 16F);

        check(GizmoDrag.resolveTranslateJacobian(usable, true).equals(usable, 1.0E-6F),
            "a usable translate Jacobian is replaced by the fallback");
    }

    private static void renderOnlyPlayersCannotPush(String source)
    {
        check(source.contains("this.entity = new MobPlayer("),
            "player MobForms no longer use the collision-isolated render player");

        String player = section(source, "private static class MobPlayer", "private static class EmptyVertexConsumer");

        check(player.contains("extends RemotePlayer"),
            "the MobForm render player no longer preserves RemotePlayer animation behavior");
        check(compact(player).contains("protected void pushEntities() {}"),
            "the MobForm render player can push the overlapping real local player");
    }

    private static String section(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0, "missing production marker: " + startMarker);
        check(end > start, "missing production end marker: " + endMarker);

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int index = source.indexOf(marker, previous + 1);

            check(index > previous, "missing or out-of-order production marker: " + marker);
            previous = index;
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(MOB_RENDERER)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the bbs-FSR project source tree");
    }

    private static String read(Path path)
    {
        try
        {
            return Files.readString(path);
        }
        catch (IOException exception)
        {
            throw new AssertionError("could not read production source: " + path, exception);
        }
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
