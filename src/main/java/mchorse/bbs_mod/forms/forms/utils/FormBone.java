package mchorse.bbs_mod.forms.forms.utils;

import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.settings.values.core.ValueBoneConstraint;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.util.Set;

/**
 * One bone of a model form, as a group of the bone's own properties. Mirrors a per-material
 * group: every default is neutral — an untouched bone behaves exactly as if this object didn't
 * exist — and {@link ValueBones} creates it lazily on the first edit.
 *
 * <p>Each property is one compound animatable value: its type serves both as the form's static
 * setting and as a film track's keyframe, which is what keeps a separate "animatable mirror"
 * class from ever existing.</p>
 *
 * <p><b>The bone group is modelled ahead of its properties.</b> This build stores only the
 * rotation limits here; IK and physics still travel in the form-level {@code ik} / {@code physics}
 * blobs, so the per-bone keys a newer build writes for them are not children of this class. They
 * are named by {@link #PRESERVED_UNKNOWN_KEYS} instead, because being present in {@code bones}
 * makes this group a <em>known</em> group: {@code ValueGroup.fromData} reads only the keys some
 * child claims, so without that list the first save after a read would delete every chain and
 * every physics setting of every bone — silently, which is the one outcome this whole branch
 * exists to prevent.</p>
 */
public class FormBone extends ValueGroup
{
    /** The per-bone children of a newer build that this one does not model yet. */
    private static final Set<String> PRESERVED_UNKNOWN_KEYS = Set.of(
        "ik_target",
        "ik_pole_target",
        "ik_chain_length",
        "ik_tip_rotation",
        "ik_stretch",
        "ik_squash",
        "ik_classic",
        "ik",
        "joint",
        "physics_end",
        "physics_target_bone",
        "physics_iterations",
        "physics_collisions",
        "physics_radius",
        "physics_relative_gravity",
        "physics_gravity_rotate_x",
        "physics_gravity_rotate_y",
        "physics_gravity_rotate_z",
        "physics"
    );

    public final ValueBoneConstraint constraints = new ValueBoneConstraint("constraints", new BoneConstraint());

    public FormBone(String id)
    {
        super(id);

        this.constraints.invisible();

        this.add(this.constraints);
    }

    /**
     * Whether every property is neutral — such a bone is skipped when the form persists.
     *
     * <p>Holding preserved data counts as not neutral: a bone whose only content is a chain this
     * build cannot read is not the same as a bone nobody ever touched, and dropping it would drop
     * the data with it.</p>
     */
    public boolean isDefault()
    {
        return this.constraints.get().isDefault() && !this.hasPreservedUnknownKeys();
    }

    /**
     * The bone's own switch: a constraint nobody switched on is neutral, and a neutral child would
     * otherwise turn every bone that carries only preserved data into a bone that also carries a
     * full block of default angles — breaking the round trip of a file this build did not write.
     */
    @Override
    protected boolean canPersist(BaseValue value)
    {
        return !(value == this.constraints && this.constraints.get().isDefault());
    }

    @Override
    protected Set<String> preservedUnknownKeys()
    {
        return PRESERVED_UNKNOWN_KEYS;
    }
}
