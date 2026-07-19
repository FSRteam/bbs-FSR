package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;

public final class ModelPivotFrames
{
    /** Caller-owned traversal and transform scratch; keep it with a renderer/simulation instance. */
    public static final class Workspace
    {
        final PoseStack stack = new PoseStack();
        final Vector3f baseTranslation = new Vector3f();
        final Quaternionf baseRotation = new Quaternionf();
        final Matrix4f rigidTransform = new Matrix4f();
    }

    private ModelPivotFrames()
    {
    }

    public static void collect(IModel model, Set<String> wanted, Map<String, CubicRenderer.PivotFrame> out)
    {
        collect(model, wanted, out, null, false);
    }

    public static void collect(IModel model, Set<String> wanted, Map<String, CubicRenderer.PivotFrame> out, Matrix4f baseTransform)
    {
        collect(model, wanted, out, baseTransform, false);
    }

    /**
     * @param applyStretch fold each bone's IK stretch offset into the frames, so a chain collected after
     * an ancestor chain has stretched reads the ancestor's shifted position (see {@link
     * CubicRenderer#collectPivotFrames}). Cubic only: a BOBJ stretch rides the skinning matrix and leaves
     * the skeleton frames (originMat/mat) nominal, so there is nothing to fold in for a BOBJ model.
     */
    public static void collect(IModel model, Set<String> wanted, Map<String, CubicRenderer.PivotFrame> out, Matrix4f baseTransform, boolean applyStretch)
    {
        collect(model, wanted, out, baseTransform, applyStretch, null);
    }

    public static void collect(IModel model, Set<String> wanted, Map<String, CubicRenderer.PivotFrame> out, Matrix4f baseTransform, boolean applyStretch, Workspace workspace)
    {
        if (model == null || wanted == null || wanted.isEmpty() || out == null)
        {
            return;
        }

        if (model instanceof Model cubic)
        {
            CubicRenderer.collectPivotFrames(cubic, wanted, out, baseTransform, applyStretch, workspace);
            return;
        }

        if (model instanceof BOBJModel bobj)
        {
            collectBobjPivotFrames(bobj, wanted, out, baseTransform, workspace);
        }
    }

    private static void collectBobjPivotFrames(BOBJModel model, Set<String> wanted, Map<String, CubicRenderer.PivotFrame> out, Matrix4f baseTransform, Workspace workspace)
    {
        Vector3f baseTranslation = null;
        Quaternionf baseRotation = null;

        if (baseTransform != null)
        {
            baseTranslation = workspace == null ? new Vector3f() : workspace.baseTranslation;
            baseRotation = workspace == null ? new Quaternionf() : workspace.baseRotation;
            baseTransform.getTranslation(baseTranslation);
            baseTransform.getUnnormalizedRotation(baseRotation);
        }

        model.getArmature().setupMatrices();

        for (BOBJBone bone : model.getArmature().orderedBones)
        {
            if (bone == null || !wanted.contains(bone.name))
            {
                continue;
            }

            CubicRenderer.PivotFrame frame = out.get(bone.name);

            if (frame == null)
            {
                frame = new CubicRenderer.PivotFrame(new Vector3f(), new Quaternionf(), new Quaternionf());
                out.put(bone.name, frame);
            }

            Vector3f position = bone.originMat.getTranslation(frame.position());
            Quaternionf parentRotation = bone.originMat.getUnnormalizedRotation(frame.parentRotation());
            Quaternionf worldRotation = bone.mat.getUnnormalizedRotation(frame.worldRotation());

            if (baseRotation != null && baseTranslation != null)
            {
                baseRotation.transform(position);
                position.add(baseTranslation);

                baseRotation.mul(parentRotation, parentRotation);
                baseRotation.mul(worldRotation, worldRotation);
            }
        }
    }
}
