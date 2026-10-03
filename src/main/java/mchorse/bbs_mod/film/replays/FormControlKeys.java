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
    public static final String GLINT_CONTROLS = "glint_layer";

    public static boolean isGlintControlChannel(String id)
    {
        return isChannelInNamespace(id, GLINT_CONTROLS);
    }

    public static String parseGlintControlFormPath(String id)
    {
        return parseFormPath(id, GLINT_CONTROLS);
    }

    public static String toGlintControlKey(String formPath)
    {
        return toKey(formPath, GLINT_CONTROLS);
    }

    /**
     * One namespace a track id can address: the text that introduces it, and whether it is the id's
     * LAST segment.
     *
     * <p>A per-limb namespace is followed by the free-text name it addresses
     * ({@code ik_targets/hand}, {@code pose.bones.arm}); a whole-form control namespace IS the
     * whole id after the form path ({@code ik_controls}, {@code glint_layer}). The distinction is
     * not cosmetic: only a terminal namespace has to reach the END of the id, which is what stops
     * {@code ik_targets/ik_controls} from being read as the whole-form IK-controls track.</p>
     */
    private record ChannelNamespace(String marker, boolean terminal)
    {}

    /**
     * Every namespace a track id can spell, per-limb and whole-form alike.
     *
     * <p>The whole-form entries beyond the two named here ({@code physics_controls},
     * {@code wind_controls}) are listed so attribution is complete, not because their predicates
     * were touched: a namespace missing from this list would be invisible to the "outermost wins"
     * rule and could be claimed by a predicate that sits later in the id.</p>
     */
    private static final ChannelNamespace[] CHANNEL_NAMESPACES = {
        new ChannelNamespace(PerLimbService.POSE_BONES, false),
        new ChannelNamespace(PerLimbService.MATERIAL_TEXTURES, false),
        new ChannelNamespace(PerLimbService.IK_TARGETS, false),
        new ChannelNamespace(PerLimbService.POLE_TARGETS, false),
        new ChannelNamespace(PerLimbService.PHYSICS_TARGETS, false),
        new ChannelNamespace(GLINT_CONTROLS, true),
        new ChannelNamespace(IK_CONTROLS, true),
        new ChannelNamespace(PHYSICS_CONTROLS, true),
        new ChannelNamespace(WIND_CONTROLS, true)
    };

    /**
     * Whether an id addresses the given namespace.
     *
     * <p>Attribution is <em>outermost wins</em>: the first namespace the id spells is the one it
     * belongs to. That is the inverse of how ids are written — {@link #toKey} puts the form path
     * first and the namespace immediately after it — so the rule reads the id the way it was
     * built.</p>
     *
     * <p>It has to be position, not presence. The last segment of a per-limb id is a NAME, and a
     * name is free text: a bone may be called {@code ik_controls}, so {@code pose.bones.ik_controls}
     * is a pose-bone track and {@code ik_targets/ik_controls} is an IK-target track, though both
     * contain and even end with the whole-form key. Matching the namespace first settles both.</p>
     *
     * <p>The residual ambiguity is inherent to a string id: a FORM PATH that itself spells a
     * namespace wins over the key that follows it. Such a path is a body part whose stable id is
     * literally {@code ik_targets}, and the id cannot then be told apart from a real target track.
     * Ordinary form paths ({@code ""}, {@code part/0}, {@code a/b}) are unaffected.</p>
     */
    public static boolean isChannelInNamespace(String id, String namespace)
    {
        return namespace != null && namespace.equals(namespaceOf(id));
    }

    /** The namespace this id spells first, or null when it spells none — see {@link #isChannelInNamespace}. */
    public static String namespaceOf(String id)
    {
        if (id == null || id.isEmpty())
        {
            return null;
        }

        /* Leading separator so a namespace in the FIRST segment is found too: with an empty form
         * path the id starts with the namespace, not with a separator. */
        String padded = FormUtils.PATH_SEPARATOR + id;
        String found = null;
        int at = Integer.MAX_VALUE;

        for (ChannelNamespace candidate : CHANNEL_NAMESPACES)
        {
            int index = padded.indexOf(FormUtils.PATH_SEPARATOR + candidate.marker());

            if (index >= 0 && index < at && (!candidate.terminal() || padded.endsWith(FormUtils.PATH_SEPARATOR + candidate.marker())))
            {
                at = index;
                found = candidate.marker();
            }
        }

        return found;
    }

    public static boolean isIKControlChannel(String id)
    {
        return isChannelInNamespace(id, IK_CONTROLS);
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
        return isChannelInNamespace(id, PHYSICS_CONTROLS);
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
        return isChannelInNamespace(id, WIND_CONTROLS);
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
