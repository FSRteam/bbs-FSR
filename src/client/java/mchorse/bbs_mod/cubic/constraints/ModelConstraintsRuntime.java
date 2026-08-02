package mchorse.bbs_mod.cubic.constraints;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.joml.Matrices;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class ModelConstraintsRuntime
{
    private static final WeakHashMap<MapType, Map<String, ModelConstraintsConfig.BoneConstraint>> EMBEDDED = new WeakHashMap<>();

    private ModelConstraintsRuntime()
    {
    }

    public static void clearCache()
    {
        EMBEDDED.clear();
    }

    public static void invalidate(String modelId)
    {
        EMBEDDED.clear();
    }

    public static void apply(ModelInstance instance)
    {
        if (instance == null || instance.model == null)
        {
            return;
        }

        Map<String, ModelConstraintsConfig.BoneConstraint> bones = getBones(instance);

        if (bones == null || bones.isEmpty())
        {
            return;
        }

        if (instance.model instanceof Model model)
        {
            applyToModel(model, bones);
        }
        else if (instance.model instanceof BOBJModel bobj)
        {
            applyToBobj(bobj, bones);
        }
    }

    public static Map<String, ModelConstraintsConfig.BoneConstraint> getBones(ModelInstance instance)
    {
        if (instance != null && instance.form instanceof ModelForm form && form.constraints.get() instanceof MapType map)
        {
            Map<String, ModelConstraintsConfig.BoneConstraint> cached = EMBEDDED.get(map);

            if (cached != null)
            {
                return cached;
            }

            ModelConstraintsConfig config = ModelConstraintsIO.fromData(map);
            Map<String, ModelConstraintsConfig.BoneConstraint> bones = config == null || config.bones() == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(config.bones()));

            EMBEDDED.put(map, bones);

            return bones;
        }

        return Collections.emptyMap();
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
    private static void applyToModel(Model model, Map<String, ModelConstraintsConfig.BoneConstraint> bones)
    {
        for (ModelGroup group : model.getAllGroups())
        {
            if (group == null)
            {
                continue;
            }

            ModelConstraintsConfig.BoneConstraint c = bones.get(group.id);

            if (c == null || !c.enabled())
            {
                continue;
            }

            Vector3f euler = Matrices.toCompatibleEulerZYXDegrees(group.evaluatedRotation(), group.current.rotate, new Vector3f());

            clamp(euler, c, 1F);

            group.orient = Matrices.toLocalRotationZYXDegrees(euler);
        }
    }

    /** See {@link #applyToModel}; BOBJ channels are radians, the config limits are degrees. */
    private static void applyToBobj(BOBJModel model, Map<String, ModelConstraintsConfig.BoneConstraint> bones)
    {
        for (BOBJBone bone : model.getArmature().orderedBones)
        {
            if (bone == null)
            {
                continue;
            }

            ModelConstraintsConfig.BoneConstraint c = bones.get(bone.name);

            if (c == null || !c.enabled())
            {
                continue;
            }

            Vector3f euler = Matrices.toCompatibleEulerZYXRadians(bone.evaluatedRotation(), bone.transform.rotate, new Vector3f());

            clamp(euler, c, MathUtils.PI / 180F);

            bone.orient = Matrices.toLocalRotationZYXRadians(euler);
        }
    }

    /** Clamps euler angles to the constraint's limits, {@code scale} converting the degree limits to the angles' unit. */
    private static void clamp(Vector3f euler, ModelConstraintsConfig.BoneConstraint c, float scale)
    {
        euler.x = clampAxis(euler.x, c.minX() * scale, c.maxX() * scale);
        euler.y = clampAxis(euler.y, c.minY() * scale, c.maxY() * scale);
        euler.z = clampAxis(euler.z, c.minZ() * scale, c.maxZ() * scale);
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
