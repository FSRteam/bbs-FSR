package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;

/** Compatibility helpers; TrackId owns namespace attribution and parsing. */
public class FormControlKeys
{
    public static final String IK_CONTROLS = "ik_controls";
    public static final String PHYSICS_CONTROLS = "physics_controls";
    public static final String WIND_CONTROLS = "wind_controls";
    public static final String GLINT_CONTROLS = TrackId.GLINT_CONTROLS;

    public static boolean isChannelInNamespace(String id, String namespace)
    {
        return namespace != null && namespace.equals(TrackId.namespaceOf(id));
    }

    public static String namespaceOf(String id)
    {
        return TrackId.namespaceOf(id);
    }

    public static int namespaceOffset(String id, String namespace)
    {
        return TrackId.namespaceOffset(id, namespace);
    }

    public static boolean isGlintControlChannel(String id)
    {
        return isChannelInNamespace(id, GLINT_CONTROLS);
    }

    public static String parseGlintControlFormPath(String id)
    {
        TrackId track = TrackId.parse(id);
        return isGlintControlChannel(id) && track != null ? track.formPath() : null;
    }

    public static String toGlintControlKey(String formPath)
    {
        return TrackId.property(formPath, GLINT_CONTROLS).toKey();
    }

    public static boolean isIKControlChannel(String id)
    {
        return TrackId.parse(id, TrackKind.IK_CONTROLS) != null;
    }

    public static String parseIKControlFormPath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.IK_CONTROLS);
        return track == null ? null : track.formPath();
    }

    public static String toIKControlKey(String formPath)
    {
        return TrackId.ikControls(formPath).toKey();
    }

    public static boolean isPhysicsControlChannel(String id)
    {
        return TrackId.parse(id, TrackKind.PHYSICS_CONTROLS) != null;
    }

    public static String parsePhysicsControlFormPath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.PHYSICS_CONTROLS);
        return track == null ? null : track.formPath();
    }

    public static String toPhysicsControlKey(String formPath)
    {
        return TrackId.physicsControls(formPath).toKey();
    }

    public static boolean isWindControlChannel(String id)
    {
        return TrackId.parse(id, TrackKind.WIND_CONTROLS) != null;
    }

    public static String parseWindControlFormPath(String id)
    {
        TrackId track = TrackId.parse(id, TrackKind.WIND_CONTROLS);
        return track == null ? null : track.formPath();
    }

    public static String toWindControlKey(String formPath)
    {
        return TrackId.windControls(formPath).toKey();
    }
}
