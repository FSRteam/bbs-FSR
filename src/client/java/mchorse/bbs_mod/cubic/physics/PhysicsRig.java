package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import org.joml.Vector3f;

/**
 * Bone geometry the solver needs, independent of the model backend. Lets the rest-length and
 * angle-constraint code stay a single path instead of branching on cubic vs BOBJ models.
 */
interface PhysicsRig
{
    float EPS = 1.0e-6f;

    /**
     * Rest segment length between the bone and its chain child (world scale used by the solver).
     * When {@code childId} is null the bone is the whole chain, so its own length is used. Returns a
     * negative value when a referenced bone is missing from the model.
     */
    float restLength(String boneId, String childId);

    /**
     * Local rest direction of the bone toward its chain child, falling back to the bone's own child
     * when the chain child is absent. Returns null when the bone is missing from the model.
     */
    Vector3f restDirectionLocal(String boneId, String childId);

    static PhysicsRig of(IModel model)
    {
        if (model instanceof Model cubic)
        {
            return new CubicRig(cubic);
        }

        if (model instanceof BOBJModel bobj)
        {
            return new BobjRig(bobj);
        }

        return null;
    }

    final class CubicRig implements PhysicsRig
    {
        private final Model model;

        private CubicRig(Model model)
        {
            this.model = model;
        }

        @Override
        public float restLength(String boneId, String childId)
        {
            ModelGroup bone = this.model.getGroup(boneId);

            if (bone == null)
            {
                return -1F;
            }

            if (childId != null)
            {
                ModelGroup child = this.model.getGroup(childId);

                if (child == null)
                {
                    return -1F;
                }

                return length(bone, child);
            }

            float len = 0.25F;

            if (bone.children != null && !bone.children.isEmpty())
            {
                len = length(bone, bone.children.get(0));
            }

            return len <= EPS ? EPS : len;
        }

        private static float length(ModelGroup a, ModelGroup b)
        {
            float dx = (b.initial.translate.x - a.initial.translate.x) / 16F;
            float dy = (b.initial.translate.y - a.initial.translate.y) / 16F;
            float dz = (b.initial.translate.z - a.initial.translate.z) / 16F;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

            return len <= EPS ? EPS : len;
        }

        @Override
        public Vector3f restDirectionLocal(String boneId, String childId)
        {
            ModelGroup bone = this.model.getGroup(boneId);

            if (bone == null)
            {
                return null;
            }

            ModelGroup child = childId == null ? null : this.model.getGroup(childId);

            if (child != null)
            {
                return new Vector3f(child.initial.translate).sub(bone.initial.translate).mul(1.0F / 16.0F);
            }

            if (bone.children != null && !bone.children.isEmpty())
            {
                ModelGroup firstChild = bone.children.get(0);

                return new Vector3f(firstChild.initial.translate).sub(bone.initial.translate).mul(1.0F / 16.0F);
            }

            return new Vector3f(0F, -1F, 0F);
        }
    }

    final class BobjRig implements PhysicsRig
    {
        private final BOBJModel model;

        private BobjRig(BOBJModel model)
        {
            this.model = model;
        }

        @Override
        public float restLength(String boneId, String childId)
        {
            BOBJBone bone = this.model.getArmature().bones.get(boneId);

            if (bone == null)
            {
                return -1F;
            }

            if (childId != null)
            {
                BOBJBone child = this.model.getArmature().bones.get(childId);

                if (child == null)
                {
                    return -1F;
                }

                float len = child.relBoneMat.getTranslation(new Vector3f()).length();

                return len <= EPS ? EPS : len;
            }

            float len = 0.25F;

            for (BOBJBone child : this.model.getArmature().orderedBones)
            {
                if (child != null && child.parentBone == bone)
                {
                    len = child.relBoneMat.getTranslation(new Vector3f()).length();
                    break;
                }
            }

            return len <= EPS ? EPS : len;
        }

        @Override
        public Vector3f restDirectionLocal(String boneId, String childId)
        {
            BOBJBone bone = this.model.getArmature().bones.get(boneId);

            if (bone == null)
            {
                return null;
            }

            BOBJBone child = childId == null ? null : this.model.getArmature().bones.get(childId);

            if (child != null)
            {
                Vector3f out = child.relBoneMat.getTranslation(new Vector3f());

                if (out.lengthSquared() > EPS * EPS)
                {
                    return out;
                }
            }

            for (BOBJBone candidate : this.model.getArmature().orderedBones)
            {
                if (candidate != null && candidate.parentBone == bone)
                {
                    Vector3f out = candidate.relBoneMat.getTranslation(new Vector3f());

                    if (out.lengthSquared() > EPS * EPS)
                    {
                        return out;
                    }
                }
            }

            if (bone.parentBone != null)
            {
                Vector3f out = bone.relBoneMat.getTranslation(new Vector3f());

                if (out.lengthSquared() > EPS * EPS)
                {
                    return out;
                }
            }

            return new Vector3f(0F, -1F, 0F);
        }
    }
}
