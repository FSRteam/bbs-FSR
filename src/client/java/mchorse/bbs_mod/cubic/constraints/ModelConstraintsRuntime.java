package mchorse.bbs_mod.cubic.constraints;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.RigBone;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ModelConstraintsRuntime
{
    private static ModelInstance lastInstance;
    private static ModelForm lastForm;
    private static long lastEpoch;
    private static long lastRevision;
    private static long lastPoseVersion;
    private static Map<String, BoneConstraint> lastBones;

    private ModelConstraintsRuntime()
    {
    }

    public static void apply(ModelInstance instance)
    {
        if (instance == null || instance.model == null)
        {
            return;
        }

        Map<String, BoneConstraint> bones = getBones(instance);

        if (bones.isEmpty())
        {
            return;
        }

        applyToBones(instance.model, bones);
    }

    /**
     * The form's active constraints by bone name, read from the bone properties themselves.
     *
     * <p>A one-slot frame memo shares the walk between physics and the constraints stage.
     * The existing bone revision and form pose version also invalidate same-frame edits and
     * track applications; no parsed exchange blob or cross-frame solver state is retained.</p>
     */
    public static Map<String, BoneConstraint> getBones(ModelInstance instance)
    {
        if (!(instance != null && instance.form instanceof ModelForm form))
        {
            return Collections.emptyMap();
        }

        long poseVersion = form.getPoseVersion();
        long revision = form.bones.getRevision();
        long epoch = RenderFrame.getEpoch();

        if (RenderFrame.isEnabled() && lastInstance == instance && lastForm == form && lastEpoch == epoch
            && lastRevision == revision && lastPoseVersion == poseVersion)
        {
            return lastBones;
        }

        Map<String, BoneConstraint> bones = collectBones(form);

        lastInstance = instance;
        lastForm = form;
        lastEpoch = epoch;
        lastRevision = revision;
        lastPoseVersion = poseVersion;
        lastBones = bones;

        return bones;
    }

    private static Map<String, BoneConstraint> collectBones(ModelForm form)
    {
        Map<String, BoneConstraint> bones = null;

        for (BaseValue value : form.bones.getAll())
        {
            if (value instanceof FormBone bone)
            {
                BoneConstraint constraint = bone.constraints.get();

                if (constraint.isActive())
                {
                    if (bones == null)
                    {
                        bones = new HashMap<>();
                    }

                    bones.put(bone.getId(), constraint);
                }
            }
        }

        return bones == null ? Collections.emptyMap() : bones;
    }

    /**
     * Clamps a bone's EVALUATED rotation (the constraint-stack result so far — FK, IK, physics),
     * not its FK channels: the evaluated rotation is decomposed to the euler branch nearest the FK
     * channels (a per-frame-stable reference, no frame-to-frame stranding), clamped per axis, and
     * written back to {@code orient}. The channels stay read-only FK truth, and an IK/physics
     * result survives the limit instead of being discarded for the clamped FK pose (limits used to
     * null {@code orient}, visually destroying the solve on a constrained chain bone). Works on
     * quaternion-mode bones too — the clamp reads the evaluated rotation, never a stale euler.
     */
    private static void applyToBones(IModel model, Map<String, BoneConstraint> bones)
    {
        for (RigBone bone : model.getRigBones())
        {
            if (bone == null)
            {
                continue;
            }

            BoneConstraint c = bones.get(bone.getBoneName());

            if (c == null || !c.isActive())
            {
                continue;
            }

            Vector3f euler = bone.toCompatibleEuler(new Vector3f());

            clamp(euler, c, bone.fromDegrees());

            bone.setOrient(bone.orientFromEuler(euler));
        }
    }

    /**
     * Clamps euler angles to the constraint's limits, {@code scale} converting the degree limits to
     * the angles' unit. An axis whose switch is off is left alone — free, not pinned to whatever
     * its unused min/max happen to say.
     */
    private static void clamp(Vector3f euler, BoneConstraint c, float scale)
    {
        if (c.limitX)
        {
            euler.x = clampAxis(euler.x, c.minX * scale, c.maxX * scale);
        }

        if (c.limitY)
        {
            euler.y = clampAxis(euler.y, c.minY * scale, c.maxY * scale);
        }

        if (c.limitZ)
        {
            euler.z = clampAxis(euler.z, c.minZ * scale, c.maxZ * scale);
        }
    }

    private static float clampAxis(float value, float min, float max)
    {
        if (min > max)
        {
            float t = min;
            min = max;
            max = t;
        }

        return MathUtils.clamp(value, min, max);
    }
}
