package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.utils.joml.Matrices;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CubicRenderer
{
    private static final float EPS = 1.0e-6f;

    /**
     * Process/render given model
     *
     * This method recursively goes through all groups in the model, and
     * applies given render processor. Processor may return true from its
     * sole method which means that iteration should be halted.
     */
    public static boolean processRenderModel(ICubicRenderer renderProcessor, BufferBuilder builder, PoseStack stack, Model model)
    {
        for (ModelGroup group : model.topGroups)
        {
            if (processRenderRecursively(renderProcessor, builder, stack, model, group))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Apply the render processor, recursively
     */
    private static boolean processRenderRecursively(ICubicRenderer renderProcessor, BufferBuilder builder, PoseStack stack, Model model, ModelGroup group)
    {
        stack.pushPose();
        renderProcessor.applyGroupTransformations(stack, group);

        if (group.visible)
        {
            if (renderProcessor.renderGroup(builder, stack, group, model))
            {
                stack.popPose();

                return true;
            }
        }

        for (ModelGroup childGroup : group.children)
        {
            if (processRenderRecursively(renderProcessor, builder, stack, model, childGroup))
            {
                stack.popPose();

                return true;
            }
        }

        stack.popPose();

        return false;
    }

    public static record PivotFrame(Vector3f position, Quaternionf parentRotation, Quaternionf worldRotation)
    {}

    public static void collectPivotFrames(Model model, Set<String> wanted, Map<String, PivotFrame> out)
    {
        collectPivotFrames(model, wanted, out, null, false);
    }

    public static void collectPivotFrames(Model model, Set<String> wanted, Map<String, PivotFrame> out, Matrix4f baseTransform)
    {
        collectPivotFrames(model, wanted, out, baseTransform, false);
    }

    /**
     * @param applyStretch when true, each bone's transient {@link ModelGroup#offset} (the IK stretch
     * telescope) is folded into its frame exactly as {@link ICubicRenderer#applyGroupTransformations}
     * folds it — offset first, in the parent frame — so a chain collected AFTER an ancestor chain has
     * stretched reads the ancestor's shifted position, the same spot the renderer draws it. Off (the
     * default) reads the un-stretched pose, which the debug overlay and a chain's OWN solve want: a
     * chain's offset is not written until its own solve runs, so with this on a chain still only ever
     * inherits ANCESTOR stretch, never its own. Offset is a pure translation, so it moves {@code
     * position} only — {@code parentRotation}/{@code worldRotation} are untouched.
     */
    public static void collectPivotFrames(Model model, Set<String> wanted, Map<String, PivotFrame> out, Matrix4f baseTransform, boolean applyStretch)
    {
        collectPivotFrames(model, wanted, out, baseTransform, applyStretch, null);
    }

    public static void collectPivotFrames(Model model, Set<String> wanted, Map<String, PivotFrame> out, Matrix4f baseTransform, boolean applyStretch, ModelPivotFrames.Workspace workspace)
    {
        if (model == null || wanted == null || wanted.isEmpty() || out == null)
        {
            return;
        }

        PoseStack stack = workspace == null ? new PoseStack() : workspace.stack;

        if (workspace != null)
        {
            stack.setIdentity();
        }

        if (baseTransform != null)
        {
            Vector3f t = workspace == null ? new Vector3f() : workspace.baseTranslation;
            Quaternionf r = workspace == null ? new Quaternionf() : workspace.baseRotation;
            Matrix4f rigid = workspace == null ? new Matrix4f() : workspace.rigidTransform;

            baseTransform.getTranslation(t);
            baseTransform.getNormalizedRotation(r);
            rigid.rotation(r).setTranslation(t);

            stack.last().pose().set(rigid);
        }

        for (ModelGroup group : model.topGroups)
        {
            collectPivotFramesRec(stack, group, wanted, out, applyStretch);
        }
    }

    private static void collectPivotFramesRec(PoseStack stack, ModelGroup group, Set<String> wanted, Map<String, PivotFrame> out, boolean applyStretch)
    {
        stack.pushPose();

        /* Offset leads, matching the renderer (ICubicRenderer.applyGroupTransformations), so a stretched
         * ancestor shifts this bone and everything below it before its own pose — see the applyStretch note. */
        if (applyStretch)
        {
            ICubicRenderer.offsetGroup(stack, group);
        }

        ICubicRenderer.translateGroup(stack, group);
        ICubicRenderer.moveToGroupPivot(stack, group);

        boolean store = wanted.contains(group.id);
        PivotFrame frame = null;

        if (store)
        {
            frame = out.get(group.id);

            if (frame == null)
            {
                frame = new PivotFrame(new Vector3f(), new Quaternionf(), new Quaternionf());
                out.put(group.id, frame);
            }

            Matrix4f mat = stack.last().pose();
            mat.getTranslation(frame.position());
            mat.getNormalizedRotation(frame.parentRotation());
        }

        ICubicRenderer.rotateGroup(stack, group);

        if (store)
        {
            Matrix4f mat = stack.last().pose();
            mat.getNormalizedRotation(frame.worldRotation());
        }

        ICubicRenderer.scaleGroup(stack, group);
        ICubicRenderer.moveBackFromGroupPivot(stack, group);

        for (ModelGroup child : group.children)
        {
            collectPivotFramesRec(stack, child, wanted, out, applyStretch);
        }

        stack.popPose();
    }

    public static void applyRotations(Model model, Quaternionf rootParentRotation, List<String> ids, Vector3f[] positions)
    {
        if (model == null || rootParentRotation == null || ids == null || positions == null || ids.isEmpty() || positions.length < 2)
        {
            return;
        }

        Quaternionf parentWorld = new Quaternionf(rootParentRotation);
        int boneCount = ids.size();
        boolean hasTip = positions.length >= boneCount + 1;
        int rotCount = boneCount - 1 + (hasTip ? 1 : 0);

        for (int i = 0; i < rotCount; i++)
        {
            ModelGroup bone = model.getGroup(ids.get(i));
            ModelGroup child = i + 1 < boneCount ? model.getGroup(ids.get(i + 1)) : null;

            if (bone == null)
            {
                return;
            }

            Vector3f restDirLocal;

            if (child != null)
            {
                restDirLocal = new Vector3f(child.initial.translate).sub(bone.initial.translate).mul(1.0f / 16.0f);
            }
            else
            {
                if (boneCount >= 2)
                {
                    ModelGroup parent = model.getGroup(ids.get(i - 1));

                    if (parent == null)
                    {
                        return;
                    }

                    restDirLocal = new Vector3f(bone.initial.translate).sub(parent.initial.translate).mul(1.0f / 16.0f);
                }
                else if (bone.children != null && !bone.children.isEmpty())
                {
                    ModelGroup firstChild = bone.children.get(0);
                    restDirLocal = new Vector3f(firstChild.initial.translate).sub(bone.initial.translate).mul(1.0f / 16.0f);
                }
                else
                {
                    restDirLocal = new Vector3f(0F, -1F, 0F);
                }
            }

            Vector3f desiredDirWorld = new Vector3f(positions[i + 1]).sub(positions[i]);

            if (restDirLocal.lengthSquared() < EPS * EPS || desiredDirWorld.lengthSquared() < EPS * EPS)
            {
                continue;
            }

            restDirLocal.normalize();
            desiredDirWorld.normalize();

            Quaternionf invParent = new Quaternionf(parentWorld).invert();
            Vector3f desiredDirLocal = new Vector3f(desiredDirWorld);

            invParent.transform(desiredDirLocal);

            if (desiredDirLocal.lengthSquared() < EPS * EPS)
            {
                continue;
            }

            desiredDirLocal.normalize();

            Quaternionf localRot = Matrices.fromToMirroredX(restDirLocal, desiredDirLocal);
            localRot.mul(twistAround(bone.current.rotate, bone.current.rotate2, restDirLocal));
            Vector3f eulerDeg = Matrices.toEulerZYXDegrees(localRot);

            float rx = bone.current.rotate.x;
            float ry = bone.current.rotate.y;
            float rz = bone.current.rotate.z;
            eulerDeg.x = wrapDegreesNear(eulerDeg.x, rx);
            eulerDeg.y = wrapDegreesNear(eulerDeg.y, ry);
            eulerDeg.z = wrapDegreesNear(eulerDeg.z, rz);

            bone.current.rotate.set(eulerDeg);
            bone.current.rotate2.set(0F, 0F, 0F);

            /* This solve finalizes the bone in euler — drop any composed orientation so the renderer
             * falls back to this euler (IK/physics own these bones, byte-identical to before). */
            bone.orient = null;

            parentWorld.mul(Matrices.toQuaternionZYXDegrees(eulerDeg.x, eulerDeg.y, eulerDeg.z));
        }
    }

    private static Quaternionf twistAround(Vector3f rotate, Vector3f rotate2, Vector3f axisLocal)
    {
        Quaternionf local = Matrices.toQuaternionZYXDegrees(rotate.x, rotate.y, rotate.z);

        if (rotate2.x != 0F || rotate2.y != 0F || rotate2.z != 0F)
        {
            local.mul(Matrices.toQuaternionZYXDegrees(rotate2.x, rotate2.y, rotate2.z));
        }

        return Matrices.twistAbout(local, axisLocal);
    }

    private static float wrapDegreesNear(float angle, float reference)
    {
        float delta = angle - reference;

        while (delta > 180F)
        {
            angle -= 360F;
            delta -= 360F;
        }

        while (delta < -180F)
        {
            angle += 360F;
            delta += 360F;
        }

        return angle;
    }
}
