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
     * The namespaces this attribution rule has to know about, per-limb and whole-form alike.
     *
     * <p>This is NOT "every namespace a track id can spell". A plain property track's last segment
     * is an arbitrary property id — {@code pose}, and anything {@link FormUtils#getPropertyPath}
     * can build — and the grouped sound tracks end in {@code $sound…}; neither family is listed,
     * because nothing here is asked to attribute them.</p>
     *
     * <p>What the list does have to be complete about is the namespaces a predicate consults: a
     * marker missing from here is invisible to the "outermost wins" rule, so a predicate sitting
     * later in the id could still claim that id. The whole-form entries beyond the four predicates
     * are listed for exactly that reason.</p>
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

        /* The smallest position wins. A tie is settled by the ORDER of CHANNEL_NAMESPACES - the
         * first entry wins - but no marker above is a prefix of another, so two markers can never
         * match at the same index today and `<` is indistinguishable from `<=`. Adding a marker
         * that IS a prefix of another would turn this array's order into a silent priority rule. */
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

    /**
     * Where in {@code id} the given namespace's marker starts, or -1 when the id does not address
     * that namespace at all.
     *
     * <p>This is the position a parser has to slice at, and it comes from the SAME scan as
     * {@link #namespaceOf} — a caller that asks the predicate and then searches the string again is
     * how the two answers came apart. A bare {@code indexOf} finds the marker text wherever it
     * appears, while attribution only counts a marker that STARTS A SEGMENT. With a form path such as
     * {@code x_ik_targets} the two disagree, and a parser reported the form path {@code x_} for a
     * track whose form path is {@code x_ik_targets}: the namespace was right and the payload was
     * wrong, which no predicate can see.</p>
     *
     * <p>A TERMINAL marker is the one that ENDS the id, so the occurrence it owns is the TRAILING
     * one even when an earlier segment spells the same text: {@code ik_controls/ik_controls} is the
     * IK-controls track of the form {@code ik_controls}, not of the empty form. A non-terminal marker
     * owns its FIRST occurrence, because attribution is outermost-wins.</p>
     */
    public static int namespaceOffset(String id, String namespace)
    {
        if (namespace == null || !namespace.equals(namespaceOf(id)))
        {
            return -1;
        }

        if (isTerminal(namespace))
        {
            /* namespaceOf selects a terminal marker only when the id ends with it, so this is that
             * occurrence - not an earlier segment that happens to spell the same text. */
            return id.length() - namespace.length();
        }

        /* Padded the way namespaceOf pads it: with an empty form path the id starts with the marker,
         * and then the separator introducing it is the padding — whose index is also the marker's
         * index in the unpadded id. */
        return (FormUtils.PATH_SEPARATOR + id).indexOf(FormUtils.PATH_SEPARATOR + namespace);
    }

    /** Whether {@code namespace} is a whole-form control key, i.e. one that has to END the id. */
    private static boolean isTerminal(String namespace)
    {
        for (ChannelNamespace candidate : CHANNEL_NAMESPACES)
        {
            if (candidate.marker().equals(namespace))
            {
                return candidate.terminal();
            }
        }

        return false;
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

    /**
     * The form path a whole-form control id addresses, or null when the id does not address this
     * namespace at all.
     *
     * <p>Parsing answers under the SAME attribution rule as the predicate — {@link
     * #isChannelInNamespace} — rather than with a substring test, so {@code isX(id)} and
     * {@code parseX(id) != null} are one and the same question. That matters where a caller
     * dispatches on "which parser answered first": an id whose free-text NAME spells another
     * namespace's key ({@code ik_targets/ik_controls}) is a real id, and a parser that merely finds
     * the text claims it and sends it to the wrong handler.</p>
     *
     * <p>And it slices where {@link #namespaceOffset} says the rule found the namespace, not at the
     * first place the text occurs: the id {@code xik_controls/ik_controls} addresses the form
     * {@code xik_controls}, and a bare {@code indexOf} answered {@code "x"}.</p>
     */
    private static String parseFormPath(String id, String suffix)
    {
        int index = namespaceOffset(id, suffix);

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
