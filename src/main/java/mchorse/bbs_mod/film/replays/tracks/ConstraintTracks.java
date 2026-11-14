package mchorse.bbs_mod.film.replays.tracks;

import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import mchorse.bbs_mod.settings.values.core.ValueBoneConstraint;

/** Shared address resolution for static limits and their runtime track. */
public final class ConstraintTracks
{
    public static final String PREFIX = "constraints.bones.";

    public record Address(String formPath, String bone)
    {}

    public static Address parse(String key)
    {
        TrackId track = TrackId.parse(key, TrackKind.BONE_CONSTRAINT);
        return track == null || track.subject().isEmpty() ? null : new Address(track.formPath(), track.subject());
    }

    public static String key(String path, String bone)
    {
        return TrackId.boneConstraint(path, bone).toKey();
    }

    public static ValueBoneConstraint property(Form root, Address address, boolean create)
    {
        if (address == null || !(FormUtils.getForm(root, address.formPath()) instanceof ModelForm model)) return null;
        FormBone bone = create ? model.bones.getOrCreate(address.bone()) : model.bones.getBone(address.bone());
        return bone == null ? null : bone.constraints;
    }

    public static BoneConstraint seed(Form root, Address address)
    {
        ValueBoneConstraint property = property(root, address, false);
        return property == null ? new BoneConstraint() : property.getOriginalValue().copy();
    }
}
