package mchorse.bbs_mod.mob;

import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

import java.util.Map;
import java.util.List;

/**
 * Writes a pose onto a vanilla model's parts, and takes it back off again.
 *
 * <p>Vanilla model parts are shared by every entity of that kind in the world, so the pose is
 * ADDED on top of the angles {@code setAngles} just computed and the originals are put aside to be
 * restored the moment the render is over. Both halves live here because the film's offline matrix
 * evaluation needs exactly the same pair, and a second copy of this arithmetic is how the two
 * would quietly drift apart.</p>
 */
public class MobPoseApplier
{
    private static class SavedTransform extends Transform
    {
        public boolean hidden;
    }

    /**
     * The pose stack of a mob form: its pose with the overlay folded in. Same rule as the model
     * form's merge — a non-zero {@code fix} lerps toward the overlay, otherwise it sums.
     */
    public static Pose merge(Pose pose, Pose overlay)
    {
        return merge(pose, overlay, List.of());
    }

    public static Pose merge(Pose pose, Pose overlay, List<ValuePose> additional)
    {
        Pose merged = pose.copy();
        applyOverlay(merged, overlay);
        for (ValuePose value : additional) applyOverlay(merged, value.get());
        return merged;
    }

    private static void applyOverlay(Pose merged, Pose overlay)
    {
        if (overlay == null)
        {
            return;
        }

        for (Map.Entry<String, PoseTransform> entry : overlay.transforms.entrySet())
        {
            PoseTransform poseTransform = merged.getOrCreate(entry.getKey());
            PoseTransform value = entry.getValue();
            poseTransform.visible &= value.visible;

            if (value.fix != 0)
            {
                poseTransform.translate.lerp(value.translate, value.fix);
                poseTransform.scale.lerp(value.scale, value.fix);
                poseTransform.lerpRotation(value, value.fix);
            }
            else
            {
                poseTransform.translate.add(value.translate);
                poseTransform.scale.add(value.scale).sub(1, 1, 1);
                poseTransform.addRotation(value);
            }
        }
    }

    public static void apply(MobRig rig, Pose pose, Map<ModelPart, Transform> saved)
    {
        if (rig == null || pose == null)
        {
            return;
        }

        for (ModelPart part : rig.ordered())
        {
            PoseTransform poseTransform = rig.resolve(pose, rig.name(part));

            if (poseTransform == null)
            {
                continue;
            }

            SavedTransform transform = new SavedTransform();

            transform.hidden = part.skipDraw;
            part.skipDraw |= !poseTransform.visible;

            transform.translate.set(part.x, part.y, part.z);
            transform.rotate.set(part.xRot, part.yRot, part.zRot);
            transform.scale.set(part.xScale, part.yScale, part.zScale);

            /* Vanilla ModelPart holds euler pitch/yaw/roll only, so a quaternion pose bone is
             * decomposed to its euler equivalent here instead of reading the stale rotate triple. */
            Vector3f rotation = poseTransform.getEulerRotation(new Vector3f());

            part.x += poseTransform.translate.x;
            part.y += poseTransform.translate.y;
            part.z += poseTransform.translate.z;
            part.xRot += rotation.x;
            part.yRot += rotation.y;
            part.zRot += rotation.z;
            part.xScale += poseTransform.scale.x - 1F;
            part.yScale += poseTransform.scale.y - 1F;
            part.zScale += poseTransform.scale.z - 1F;

            saved.putIfAbsent(part, transform);
        }
    }

    public static void restore(Map<ModelPart, Transform> saved)
    {
        for (Map.Entry<ModelPart, Transform> entry : saved.entrySet())
        {
            ModelPart part = entry.getKey();
            Transform transform = entry.getValue();

            if (transform instanceof SavedTransform original)
            {
                part.skipDraw = original.hidden;
            }

            part.x = transform.translate.x;
            part.y = transform.translate.y;
            part.z = transform.translate.z;
            part.xRot = transform.rotate.x;
            part.yRot = transform.rotate.y;
            part.zRot = transform.rotate.z;
            part.xScale = transform.scale.x;
            part.yScale = transform.scale.y;
            part.zScale = transform.scale.z;
        }

        saved.clear();
    }
}
