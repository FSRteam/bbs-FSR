package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;

public final class ModelPivotFrames
{
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
        if (model == null || wanted == null || wanted.isEmpty() || out == null)
        {
            return;
        }

        if (model instanceof Model cubic)
        {
            CubicRenderer.collectPivotFrames(cubic, wanted, out, baseTransform, applyStretch);
            return;
        }

        if (model instanceof BOBJModel bobj)
        {
            collectBobjPivotFrames(bobj, wanted, out, baseTransform);
        }
    }

    private static void collectBobjPivotFrames(BOBJModel model, Set<String> wanted, Map<String, CubicRenderer.PivotFrame> out, Matrix4f baseTransform)
    {
        Vector3f baseTranslation = null;
        Quaternionf baseRotation = null;

        if (baseTransform != null)
        {
            baseTranslation = baseTransform.getTranslation(new Vector3f());
            baseRotation = baseTransform.getNormalizedRotation(new Quaternionf());
        }

        model.getArmature().setupMatrices();

        for (BOBJBone bone : model.getArmature().orderedBones)
        {
            if (bone == null || !wanted.contains(bone.name))
            {
                continue;
            }

            Vector3f position = bone.originMat.getTranslation(new Vector3f());
            Quaternionf parentRotation = bone.originMat.getNormalizedRotation(new Quaternionf());
            Quaternionf worldRotation = bone.mat.getNormalizedRotation(new Quaternionf());

            if (baseRotation != null && baseTranslation != null)
            {
                baseRotation.transform(position);
                position.add(baseTranslation);

                parentRotation = new Quaternionf(baseRotation).mul(parentRotation);
                worldRotation = new Quaternionf(baseRotation).mul(worldRotation);
            }

            out.put(bone.name, new CubicRenderer.PivotFrame(position, parentRotation, worldRotation));
        }
    }
}
