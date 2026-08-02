package mchorse.bbs_mod.cubic.ik;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ModelIKConfig(List<Chain> chains, Map<String, JointDoF> bones)
{
    public ModelIKConfig
    {
        bones = bones == null ? Collections.emptyMap() : bones;
    }

    public static final float DEFAULT_WEIGHT = 1F;
    public static final String DEFAULT_POLE_TARGET = "";
    public static final float DEFAULT_POLE_ANGLE = 0F;
    /* FSR keeps the hard-reach default for existing rigs; soft IK remains an
     * explicit authored value or keyframe override. */
    public static final float DEFAULT_SOFTNESS = 0F;
    public static final int DEFAULT_CHAIN_LENGTH = 0;
    public static final boolean DEFAULT_TIP_ROTATION = false;
    public static final boolean DEFAULT_STRETCH = false;
    public static final boolean DEFAULT_CLASSIC = false;

    /**
     * One IK constraint, modeled after Blender: it lives on the {@code tip}
     * bone, reaches {@code target}, spans {@code chainLength} bones up the
     * hierarchy ({@code 0} = up to the root). When {@code pole} is on, the bend is
     * aimed at {@code poleTarget} (a bone the limb keeps pointing its elbow
     * towards) and {@code poleAngle} (degrees) rolls the bend about the
     * root-to-goal line — Blender's pole angle; with no pole target the bend
     * side comes from the pose (and the authored rest bend on a straight limb).
     * With {@code tipRotation} on, the tip bone copies the {@code target}
     * controller's orientation (Blender's "use tip rotation") instead of
     * keeping its FK pose. With {@code stretch} on, a chain that comes up short
     * telescopes onto its target: the gap is split among its bones as
     * translations, so the joints open and the tip lands on the controller.
     * With {@code classic} on, a chain of exactly two bones is solved by the
     * analytic position-level solver (swing and roll assembled in quaternions,
     * no channel-space iteration) — the pre-redesign limb feel; it ignores
     * per-bone joint freedom and never merges with other chains, falling back
     * to the core solver when it overlaps one.
     */
    public record Chain(String tip, String target, int chainLength, boolean pole, String poleTarget, float poleAngle, float softness, float weight, boolean enabled, boolean tipRotation, boolean stretch, boolean classic)
    {
        public Chain
        {
            tip = tip == null ? "" : tip;
            target = target == null ? "" : target;
            poleTarget = poleTarget == null ? "" : poleTarget;
            chainLength = Math.max(0, chainLength);
            softness = clamp01(softness);
            weight = clamp01(weight);
        }

        private static float clamp01(float value)
        {
            if (value < 0F)
            {
                return 0F;
            }

            return Math.min(value, 1F);
        }
    }

    /**
     * Per-bone joint freedom for the IK solve — Blender's bone IK panel. Per
     * axis: {@code lock} removes the axis from the solve entirely (it stays
     * frozen at its FK value, so an authored twist survives); {@code limit}
     * clamps the CHANNEL angle into [min, max] degrees — the same numbers the
     * animator sees on the rotation pads; {@code stiffness} 0..1 makes the axis
     * increasingly reluctant to move, shifting the bend to freer joints. One
     * entry per bone of the MODEL — a bone shared by several chains has one
     * set of joints, like a Blender pose bone.
     */
    public record JointDoF(boolean lockX, boolean lockY, boolean lockZ,
                           boolean limitX, float minX, float maxX,
                           boolean limitY, float minY, float maxY,
                           boolean limitZ, float minZ, float maxZ,
                           float stiffnessX, float stiffnessY, float stiffnessZ)
    {
        public static final float DEFAULT_MIN = -180F;
        public static final float DEFAULT_MAX = 180F;

        public static final JointDoF FREE = new JointDoF(false, false, false,
            false, DEFAULT_MIN, DEFAULT_MAX,
            false, DEFAULT_MIN, DEFAULT_MAX,
            false, DEFAULT_MIN, DEFAULT_MAX,
            0F, 0F, 0F);

        public JointDoF
        {
            stiffnessX = Chain.clamp01(stiffnessX);
            stiffnessY = Chain.clamp01(stiffnessY);
            stiffnessZ = Chain.clamp01(stiffnessZ);
        }

        /** A free joint carries no information and is not serialized. */
        public boolean isFree()
        {
            return !this.lockX && !this.lockY && !this.lockZ
                && !this.limitX && !this.limitY && !this.limitZ
                && this.stiffnessX <= 0F && this.stiffnessY <= 0F && this.stiffnessZ <= 0F;
        }
    }
}
