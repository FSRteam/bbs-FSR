package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig.BoneConstraint;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Applies a procedural chain's solved directions as final local quaternions.
 * The parent frame is advanced with the exact blended and constrained value
 * assigned to the renderer, so descendants never decompose against a pose that
 * is different from the one that will be drawn.
 */
public final class ModelRotationBlender
{
    private static final float EPS = 1.0e-6f;

    /** Mutable math storage retained by the renderer/owner/chain runtime. */
    public static final class Workspace
    {
        private final Quaternionf parentWorld = new Quaternionf();
        private final Quaternionf inverseParent = new Quaternionf();
        private final Quaternionf baseLocal = new Quaternionf();
        private final Quaternionf secondaryLocal = new Quaternionf();
        private final Quaternionf localRotation = new Quaternionf();
        private final Quaternionf mirroredRotation = new Quaternionf();
        private final Quaternionf twist = new Quaternionf();
        private final Quaternionf eulerScratch = new Quaternionf();
        private final Quaternionf relativeRotation = new Quaternionf();
        private final Matrix3f mirroredMatrix = new Matrix3f();
        private final Vector3f restDirection = new Vector3f();
        private final Vector3f desiredWorld = new Vector3f();
        private final Vector3f desiredLocal = new Vector3f();
        private final Vector3f mirroredRest = new Vector3f();
        private final Vector3f mirroredDesired = new Vector3f();
        private final Vector3f eulerDegrees = new Vector3f();
        private Quaternionf[] outputOrientations = new Quaternionf[0];

        private void ensureCapacity(int count)
        {
            if (this.outputOrientations.length == count)
            {
                return;
            }

            this.outputOrientations = new Quaternionf[count];

            for (int i = 0; i < count; i++)
            {
                this.outputOrientations[i] = new Quaternionf();
            }
        }
    }

    private ModelRotationBlender()
    {
    }

    /** Compatibility overload for non-hot external callers. */
    public static void applyWeightedRotations(IModel model, Quaternionf rootParentRotation, List<String> ids, Vector3f[] positions, float weight)
    {
        applyWeightedRotations(model, rootParentRotation, ids, positions, weight, null, new Workspace());
    }

    /**
     * Hot-path entry. {@code workspace} must belong to the renderer's simulation
     * owner and chain; it is never stored on the shared model resource.
     */
    public static void applyWeightedRotations(IModel model, Quaternionf rootParentRotation, List<String> ids, Vector3f[] positions, float weight, Map<String, BoneConstraint> constraints, Workspace workspace)
    {
        float factor = clamp01(weight);

        if (factor <= EPS || model == null || rootParentRotation == null || ids == null || positions == null || ids.isEmpty() || positions.length < 2)
        {
            return;
        }

        Workspace scratch = workspace == null ? new Workspace() : workspace;

        if (model instanceof Model cubic)
        {
            applyCubic(cubic, rootParentRotation, ids, positions, factor, constraints, scratch);
        }
        else if (model instanceof BOBJModel bobj)
        {
            applyBobj(bobj, rootParentRotation, ids, positions, factor, constraints, scratch);
        }
    }

    public static void applyWeightedRotations(Model model, Quaternionf rootParentRotation, List<String> ids, Vector3f[] positions, float weight)
    {
        applyWeightedRotations((IModel) model, rootParentRotation, ids, positions, weight);
    }

    private static void applyCubic(Model model, Quaternionf rootParentRotation, List<String> ids, Vector3f[] positions, float factor, Map<String, BoneConstraint> constraints, Workspace scratch)
    {
        int rotCount = getRotationCount(ids, positions);

        if (rotCount <= 0)
        {
            return;
        }

        scratch.ensureCapacity(rotCount);
        Quaternionf parentWorld = scratch.parentWorld.set(rootParentRotation);

        for (int i = 0; i < rotCount; i++)
        {
            ModelGroup bone = model.getGroup(ids.get(i));

            if (bone == null || !cubicRestDirection(model, ids, i, scratch.restDirection))
            {
                return;
            }

            Vector3f desiredWorld = scratch.desiredWorld.set(positions[i + 1]).sub(positions[i]);

            if (!normalize(desiredWorld))
            {
                continue;
            }

            Vector3f desiredLocal = scratch.desiredLocal.set(desiredWorld);
            scratch.inverseParent.set(parentWorld).invert().transform(desiredLocal);

            if (!normalize(desiredLocal))
            {
                continue;
            }

            Quaternionf base = cubicLocal(bone, scratch.baseLocal, scratch.secondaryLocal);
            Quaternionf solved = Matrices.fromToMirroredX(
                scratch.restDirection,
                desiredLocal,
                scratch.localRotation,
                scratch.mirroredRotation,
                scratch.mirroredMatrix,
                scratch.mirroredRest,
                scratch.mirroredDesired
            );

            solved.mul(Matrices.twistAbout(base, scratch.restDirection, scratch.twist));

            Quaternionf applied = scratch.outputOrientations[i];
            blend(applied, base, solved, factor);
            clampFinalOrientation(applied, constraint(constraints, bone.id), scratch);

            bone.orient = applied;
            parentWorld.mul(applied);
        }
    }

    private static void applyBobj(BOBJModel model, Quaternionf rootParentRotation, List<String> ids, Vector3f[] positions, float factor, Map<String, BoneConstraint> constraints, Workspace scratch)
    {
        int rotCount = getRotationCount(ids, positions);

        if (rotCount <= 0)
        {
            return;
        }

        scratch.ensureCapacity(rotCount);
        Quaternionf parentWorld = scratch.parentWorld.set(rootParentRotation);
        Map<String, BOBJBone> bones = model.getArmature().bones;

        for (int i = 0; i < rotCount; i++)
        {
            BOBJBone bone = bones.get(ids.get(i));

            if (bone == null
                || !getBobjRestDirection(model, bone, i + 1 < ids.size() ? bones.get(ids.get(i + 1)) : null, ids, i, scratch.restDirection)
                || !normalize(scratch.restDirection))
            {
                return;
            }

            Vector3f desiredWorld = scratch.desiredWorld.set(positions[i + 1]).sub(positions[i]);

            if (!normalize(desiredWorld))
            {
                continue;
            }

            Vector3f desiredLocal = scratch.desiredLocal.set(desiredWorld);
            scratch.inverseParent.set(parentWorld).invert().transform(desiredLocal);

            if (!normalize(desiredLocal))
            {
                continue;
            }

            Quaternionf base = bobjLocal(bone, scratch.baseLocal, scratch.secondaryLocal);
            Quaternionf solved = Matrices.fromToMirroredX(
                scratch.restDirection,
                desiredLocal,
                scratch.localRotation,
                scratch.mirroredRotation,
                scratch.mirroredMatrix,
                scratch.mirroredRest,
                scratch.mirroredDesired
            );

            solved.mul(Matrices.twistAbout(base, scratch.restDirection, scratch.twist));

            Quaternionf applied = scratch.outputOrientations[i];
            blend(applied, base, solved, factor);
            clampFinalOrientation(applied, constraint(constraints, bone.name), scratch);

            bone.orient = applied;

            if (i + 1 < rotCount)
            {
                BOBJBone next = bones.get(ids.get(i + 1));

                if (next == null)
                {
                    return;
                }

                next.relBoneMat.getNormalizedRotation(scratch.relativeRotation);
                parentWorld.mul(applied).mul(scratch.relativeRotation);
            }
        }
    }

    private static Quaternionf cubicLocal(ModelGroup bone, Quaternionf out, Quaternionf secondary)
    {
        if (bone.orient != null)
        {
            return out.set(bone.orient);
        }

        if (bone.current.rotationMode == Transform.RotationMode.QUATERNION)
        {
            return out.set(bone.current.quat);
        }

        return out.rotationZYX(
            (float) Math.toRadians(bone.current.rotate.z),
            (float) Math.toRadians(bone.current.rotate.y),
            (float) Math.toRadians(bone.current.rotate.x)
        );
    }

    private static Quaternionf bobjLocal(BOBJBone bone, Quaternionf out, Quaternionf secondary)
    {
        if (bone.orient != null)
        {
            return out.set(bone.orient);
        }

        if (bone.transform.rotationMode == Transform.RotationMode.QUATERNION)
        {
            return out.set(bone.transform.quat);
        }

        return out.rotationZYX(bone.transform.rotate.z, bone.transform.rotate.y, bone.transform.rotate.x);
    }

    private static void blend(Quaternionf out, Quaternionf base, Quaternionf solved, float factor)
    {
        if (factor >= 1F - EPS)
        {
            out.set(solved);
        }
        else
        {
            out.set(base).slerp(solved, factor);
        }

        out.normalize();
    }

    /** Clamp the exact quaternion assigned to cubic/BOBJ rendering. */
    static void clampFinalOrientation(Quaternionf orientation, BoneConstraint constraint, Workspace scratch)
    {
        if (constraint == null || !constraint.enabled())
        {
            return;
        }

        Vector3f euler = Matrices.toEulerZYXDegrees(orientation, scratch.eulerDegrees, scratch.eulerScratch);
        float x = clampRange(euler.x, constraint.minX(), constraint.maxX());
        float y = clampRange(euler.y, constraint.minY(), constraint.maxY());
        float z = clampRange(euler.z, constraint.minZ(), constraint.maxZ());

        orientation.rotationZYX((float) Math.toRadians(z), (float) Math.toRadians(y), (float) Math.toRadians(x));
    }

    private static BoneConstraint constraint(Map<String, BoneConstraint> constraints, String id)
    {
        return constraints == null || constraints.isEmpty() ? null : constraints.get(id);
    }

    private static boolean cubicRestDirection(Model model, List<String> ids, int index, Vector3f out)
    {
        ModelGroup bone = model.getGroup(ids.get(index));
        ModelGroup child = index + 1 < ids.size() ? model.getGroup(ids.get(index + 1)) : null;

        if (bone == null)
        {
            return false;
        }

        if (child != null)
        {
            out.set(child.initial.translate).sub(bone.initial.translate);
        }
        else if (ids.size() >= 2)
        {
            ModelGroup parent = model.getGroup(ids.get(index - 1));

            if (parent == null)
            {
                return false;
            }

            out.set(bone.initial.translate).sub(parent.initial.translate);
        }
        else if (bone.children != null && !bone.children.isEmpty())
        {
            out.set(bone.children.get(0).initial.translate).sub(bone.initial.translate);
        }
        else
        {
            out.set(0F, -1F, 0F);
        }

        return normalize(out);
    }

    public static Vector3f getBobjRestDirection(BOBJModel model, BOBJBone bone, BOBJBone child, List<String> ids, int index)
    {
        Vector3f out = new Vector3f();

        return getBobjRestDirection(model, bone, child, ids, index, out) ? out : out.set(0F, -1F, 0F);
    }

    private static boolean getBobjRestDirection(BOBJModel model, BOBJBone bone, BOBJBone child, List<String> ids, int index, Vector3f out)
    {
        if (child != null)
        {
            child.relBoneMat.getTranslation(out);

            if (out.lengthSquared() > EPS * EPS)
            {
                return true;
            }
        }

        if (index > 0)
        {
            bone.relBoneMat.getTranslation(out);

            if (out.lengthSquared() > EPS * EPS)
            {
                return true;
            }
        }

        for (BOBJBone candidate : model.getArmature().orderedBones)
        {
            if (candidate != null && candidate.parentBone == bone)
            {
                candidate.relBoneMat.getTranslation(out);

                if (out.lengthSquared() > EPS * EPS)
                {
                    return true;
                }
            }
        }

        out.set(0F, -1F, 0F);

        return true;
    }

    private static int getRotationCount(List<String> ids, Vector3f[] positions)
    {
        int boneCount = ids.size();
        boolean hasTip = positions.length >= boneCount + 1;

        return boneCount - 1 + (hasTip ? 1 : 0);
    }

    private static boolean normalize(Vector3f value)
    {
        float lengthSquared = value.lengthSquared();

        if (lengthSquared <= EPS * EPS)
        {
            return false;
        }

        value.mul(1F / (float) Math.sqrt(lengthSquared));

        return true;
    }

    private static float clamp01(float value)
    {
        return value < 0F ? 0F : Math.min(value, 1F);
    }

    private static float clampRange(float value, float min, float max)
    {
        if (min > max)
        {
            float swap = min;
            min = max;
            max = swap;
        }

        return Math.max(min, Math.min(max, value));
    }
}
