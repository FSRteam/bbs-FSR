package mchorse.bbs_mod.client.ui.mirror;

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

        mirrorEditingUsesSelectionDeltas(editor);
        filmPoseEditingUsesSelectionDeltas(poseKeyframe);
        deferredLayersCapturePreparation(provider, queue);
        matrixSamplingIsolatesFeatureLayers(renderer);
        translationSamplingUsesLivePose(gizmoDrag, propTransform);
        renderOnlyPlayersCannotPush(renderer);
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

    private static void matrixSamplingIsolatesFeatureLayers(String source)
    {
        String method = section(source, "private void renderForMatrixCollection", "public void tick(IEntity source)");

        check(method.contains("accessor.bbs$getLayers()"),
            "MobForm matrix sampling no longer obtains the renderer feature layers");
        assertOrdered(method, "new ArrayList<>(layers)", "layers.clear()", "finally", "layers.addAll(savedLayers)");
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
        check(propTransform.contains("isUsableTranslateJacobian(jacobian)"),
            "local ray translation no longer rejects a degenerate sampled Jacobian");
        check(propTransform.contains("Float.isFinite(determinant)"),
            "ray translation no longer rejects non-finite sampled transforms");
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
