package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;

/** Legacy FSR address API backed by the shared typed track contract. */
public class PerLimbService
{
    public static final String POSE_BONES = TrackId.BONE_PREFIX;
    public static final String MATERIAL_TEXTURES = TrackId.MATERIAL_TEXTURE_PREFIX;
    public static final String IK_TARGETS = TrackId.IK_TARGETS;
    public static final String POLE_TARGETS = TrackId.POLE_TARGETS;
    public static final String PHYSICS_TARGETS = TrackId.PHYSICS_TARGETS;

    public record PoseBonePath(String formPath, String bone) {}

    public static boolean isPoseBoneChannel(String id)
    {
        return TrackId.parse(id, TrackKind.BONE) != null;
    }

    public static PoseBonePath parsePoseBonePath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.BONE);
        return track == null ? null : new PoseBonePath(track.formPath(), track.subject());
    }

    public static String toPoseBoneKey(String formPath, String bone)
    {
        return TrackId.bone(formPath, bone).toKey();
    }

    public record MaterialTexturePath(String formPath, String material) {}

    public static boolean isMaterialTextureChannel(String id)
    {
        return TrackId.parse(id, TrackKind.MATERIAL_TEXTURE) != null;
    }

    public static MaterialTexturePath parseMaterialTexturePath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.MATERIAL_TEXTURE);
        return track == null ? null : new MaterialTexturePath(track.formPath(), track.subject());
    }

    public static String toMaterialTextureKey(String formPath, String material)
    {
        return TrackId.materialTexture(formPath, material).toKey();
    }

    public record IKTargetPath(String formPath, String controller) {}

    public static boolean isIKTargetChannel(String id)
    {
        return TrackId.parse(id, TrackKind.IK_TARGET) != null;
    }

    public static IKTargetPath parseIKTargetPath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.IK_TARGET);
        return track == null ? null : new IKTargetPath(track.formPath(), track.subject());
    }

    public static String toIKTargetKey(String formPath, String controller)
    {
        return TrackId.ikTarget(formPath, controller).toKey();
    }

    public record PoleTargetPath(String formPath, String controller) {}

    public static boolean isPoleTargetChannel(String id)
    {
        return TrackId.parse(id, TrackKind.POLE_TARGET) != null;
    }

    public static PoleTargetPath parsePoleTargetPath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.POLE_TARGET);
        return track == null ? null : new PoleTargetPath(track.formPath(), track.subject());
    }

    public static String toPoleTargetKey(String formPath, String controller)
    {
        return TrackId.poleTarget(formPath, controller).toKey();
    }

    public record PhysicsTargetPath(String formPath, String rootBone) {}

    public static boolean isPhysicsTargetChannel(String id)
    {
        return TrackId.parse(id, TrackKind.PHYSICS_TARGET) != null;
    }

    public static PhysicsTargetPath parsePhysicsTargetPath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.PHYSICS_TARGET);
        return track == null ? null : new PhysicsTargetPath(track.formPath(), track.subject());
    }

    public static String toPhysicsTargetKey(String formPath, String rootBone)
    {
        return TrackId.physicsTarget(formPath, rootBone).toKey();
    }
}
