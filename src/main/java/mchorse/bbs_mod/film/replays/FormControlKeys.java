package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.forms.FormUtils;

/**
 * Channel-id helpers for the whole-form control tracks: IK, physics and wind. Unlike the per-bone /
 * per-controller / per-material tracks (whose keys live in {@link PerLimbService}), each of these is a
 * SINGLE track per form — its keyframe value carries the controls (a per-chain map for IK and physics,
 * the global wind for wind) — so the id only encodes the owning form path, not a limb.
 */
public class FormControlKeys
{
    public static final String IK_CONTROLS = "ik_controls";
    public static final String PHYSICS_CONTROLS = "physics_controls";
    public static final String WIND_CONTROLS = "wind_controls";

    public static boolean isIKControlChannel(String id)
    {
        return id != null && id.contains(IK_CONTROLS);
    }

    /** The IK-controls channel is one per form (not per controller); this returns its owning form path. */
    public static String parseIKControlFormPath(String id)
    {
        return parseFormPath(id, IK_CONTROLS);
    }

    public static String toIKControlKey(String formPath)
    {
        return toKey(formPath, IK_CONTROLS);
    }

    public static boolean isPhysicsControlChannel(String id)
    {
        return id != null && id.contains(PHYSICS_CONTROLS);
    }

    /** The physics-controls channel is one per form (not per chain); this returns its owning form path. */
    public static String parsePhysicsControlFormPath(String id)
    {
        return parseFormPath(id, PHYSICS_CONTROLS);
    }

    public static String toPhysicsControlKey(String formPath)
    {
        return toKey(formPath, PHYSICS_CONTROLS);
    }

    public static boolean isWindControlChannel(String id)
    {
        return id != null && id.contains(WIND_CONTROLS);
    }

    /** The wind-controls channel is one per form (the wind is global, not per chain); this returns its owning form path. */
    public static String parseWindControlFormPath(String id)
    {
        return parseFormPath(id, WIND_CONTROLS);
    }

    public static String toWindControlKey(String formPath)
    {
        return toKey(formPath, WIND_CONTROLS);
    }

    private static String parseFormPath(String id, String suffix)
    {
        if (id == null)
        {
            return null;
        }

        int index = id.indexOf(suffix);

        if (index < 0)
        {
            return null;
        }

        String formPath = id.substring(0, index);

        if (formPath.endsWith(FormUtils.PATH_SEPARATOR))
        {
            formPath = formPath.substring(0, formPath.length() - 1);
        }

        return formPath;
    }

    private static String toKey(String formPath, String suffix)
    {
        if (formPath == null || formPath.isEmpty())
        {
            return suffix;
        }

        return formPath + FormUtils.PATH_SEPARATOR + suffix;
    }
}
