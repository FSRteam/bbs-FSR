package mchorse.bbs_mod.data.migration;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.core.StableIds;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Format 1 &rarr; 2: everything in a film that used to address a replay or a body part by its list
 * position addresses it by a {@link StableIds stable id} instead. The legacy index is resolved by
 * the old rules one last time, right here, and never again:
 *
 * <ul>
 * <li>every replay and body part is stamped with its id;</li>
 * <li>replay property track keys ({@code "0/2/pose"}) become id paths;</li>
 * <li>anchors — the {@code "actor"} inside anchor keyframe values and the forms' static anchor
 * fields — carry the target replay's id, and their {@code "attachment"} (a path into the target's
 * matrix tree, which could start with body part indices) is rewritten against the target's form;</li>
 * <li>camera clip selectors (look/orbit/tracker) do the same, including the tracker's
 * {@code "group"} attachment path.</li>
 * <li>recorded action targets — the {@code "replay_id"} of the {@code "target"} map inside every
 * action clip — carry the targeted replay's id. This one has no upstream counterpart: FS has an
 * {@code ActionTarget} and upstream's {@code FilmStableIds} never had to walk {@code "actions"}.</li>
 * </ul>
 *
 * <p>An index that doesn't resolve to a replay (a target deleted long ago) becomes an explicit
 * "no target": that is what it already meant, the file just stored a dangling number.
 *
 * <p>Two passes: ids are stamped on every replay and every body part of every form first, so that
 * by the time anchors are rewritten the id mapping of any <em>target</em> replay's form exists —
 * an anchor's attachment path is relative to the target, not to the anchor's owner.
 */
public class FilmStableIds implements IDataMigration
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The keyframe channel type whose values are anchors — the marker for actor rewriting. */
    private static final String ANCHOR_CHANNEL_TYPE = "anchor";

    /** An action clip's sibling key, and inside it the map naming the replay it was recorded on. */
    private static final String ACTIONS = "actions";
    private static final String TARGET = "target";
    private static final String REPLAY_ID = "replay_id";

    /** The multi-camera track list, and the clip list each track owns. */
    private static final String CAMERA_TRACKS = "camera_tracks";
    private static final String CAMERA_TRACK_CLIPS = "clips";

    @Override
    public int getVersion()
    {
        return 1;
    }

    @Override
    public void migrate(MapType data)
    {
        ListType replays = data.has("replays") ? data.getList("replays") : new ListType();

        /* Pass 1: identity. */
        List<String> replayIds = stampReplayIds(replays);
        List<Map<String, String>> formMappings = new ArrayList<>();

        for (BaseType replayType : replays)
        {
            MapType replay = replayType.isMap() ? replayType.asMap() : null;

            formMappings.add(replay == null ? Map.of() : FormStableIds.ensureReplay(replay));
        }

        /* Pass 2: references. */
        for (int i = 0; i < replays.size(); i++)
        {
            if (!replays.get(i).isMap())
            {
                continue;
            }

            MapType replay = replays.get(i).asMap();

            if (replay.has("properties"))
            {
                convertAnchorChannels(replay.getMap("properties"), replayIds, formMappings);
            }

            if (replay.has("form"))
            {
                convertFormAnchors(replay.getMap("form"), replayIds, formMappings);
            }

            migrateReplayReferences(replay, replayIds);
        }

        if (data.has("camera"))
        {
            convertCameraSelectors(data.getList("camera"), replayIds, formMappings);
        }

        if (data.has(CAMERA_TRACKS))
        {
            for (BaseType trackType : data.getList(CAMERA_TRACKS))
            {
                if (trackType.isMap() && trackType.asMap().has(CAMERA_TRACK_CLIPS))
                {
                    convertCameraSelectors(trackType.asMap().getList(CAMERA_TRACK_CLIPS), replayIds, formMappings);
                }
            }
        }
    }

    /** Give every replay its id; the list index is the id's meaning up until this very point. */
    private static List<String> stampReplayIds(ListType replays)
    {
        List<String> taken = new ArrayList<>();

        for (BaseType type : replays)
        {
            if (type.isMap() && StableIds.isStableId(type.asMap().getString(StableIds.KEY)))
            {
                taken.add(type.asMap().getString(StableIds.KEY));
            }
        }

        List<String> ids = new ArrayList<>();
        Set<String> assigned = new HashSet<>();

        for (BaseType type : replays)
        {
            if (!type.isMap())
            {
                ids.add(null);

                continue;
            }

            MapType replay = type.asMap();
            String id = replay.getString(StableIds.KEY);

            if (!StableIds.isStableId(id) || assigned.contains(id))
            {
                id = StableIds.fromLegacyIndex(ids.size(), taken);

                taken.add(id);
                replay.putString(StableIds.KEY, id);
            }

            assigned.add(id);
            ids.add(id);
        }

        return ids;
    }

    /** Static anchor fields and state anchor channels, of the form and every nested body part form. */
    private static void convertFormAnchors(MapType form, List<String> replayIds, List<Map<String, String>> formMappings)
    {
        if (form.has("anchor"))
        {
            convertAnchor(form.getMap("anchor"), replayIds, formMappings);
        }

        for (BaseType stateType : form.has("states") ? form.getList("states") : new ListType())
        {
            if (stateType.isMap() && stateType.asMap().has("properties"))
            {
                convertAnchorChannels(stateType.asMap().getMap("properties"), replayIds, formMappings);
            }
        }

        for (BaseType partType : FormStableIds.getParts(form))
        {
            if (partType.isMap() && partType.asMap().has("form"))
            {
                convertFormAnchors(partType.asMap().getMap("form"), replayIds, formMappings);
            }
        }
    }

    /** Anchor keyframe channels inside a track map, recognized by their channel type. */
    private static void convertAnchorChannels(MapType properties, List<String> replayIds, List<Map<String, String>> formMappings)
    {
        for (MapType channel : channelsOf(properties))
        {
            if (!channel.getString("type").equals(ANCHOR_CHANNEL_TYPE))
            {
                continue;
            }

            for (BaseType keyframe : channel.getList("keyframes"))
            {
                if (keyframe.isMap() && keyframe.asMap().has("value") && keyframe.asMap().get("value").isMap())
                {
                    convertAnchor(keyframe.asMap().getMap("value"), replayIds, formMappings);
                }
            }
        }
    }

    /** Every keyframe channel inside a properties map, whichever of the two shapes it is in. */
    private static List<MapType> channelsOf(MapType properties)
    {
        List<MapType> channels = new ArrayList<>();

        if (FormStableIds.isTrackList(properties))
        {
            for (BaseType entryType : properties.getList(FormStableIds.TRACKS))
            {
                if (entryType.isMap() && entryType.asMap().has("channel") && entryType.asMap().get("channel").isMap())
                {
                    channels.add(entryType.asMap().getMap("channel"));
                }
            }
        }
        else
        {
            for (String key : properties.keys())
            {
                BaseType channelType = properties.get(key);

                if (channelType.isMap())
                {
                    channels.add(channelType.asMap());
                }
            }
        }

        return channels;
    }

    /**
     * One anchor map: {@code "actor"} legacy int index &rarr; the target replay's id, and
     * {@code "attachment"} rewritten against the <em>target's</em> form (its leading segments are
     * body part indices of that form's tree). A string actor is preserved, but its attachment may
     * still need conversion. A dangling or negative numeric index becomes an empty target id.
     */
    private static void convertAnchor(MapType anchor, List<String> replayIds, List<Map<String, String>> formMappings)
    {
        BaseType actor = anchor.get("actor");

        if (actor == null)
        {
            return;
        }

        boolean converted = BaseType.isString(actor);
        int index = converted ? replayIds.indexOf(anchor.getString("actor")) : anchor.getInt("actor", -1);
        String id = index >= 0 && index < replayIds.size() ? replayIds.get(index) : null;

        if (index >= 0 && id == null)
        {
            LOGGER.warn("Anchor points at replay [" + index + "] which does not exist; unanchoring");
        }

        if (!converted)
        {
            anchor.putString("actor", id == null ? "" : id);
        }

        if (id != null && anchor.has("attachment"))
        {
            anchor.putString("attachment", rewriteAttachment(anchor.getString("attachment"), formMappings.get(index)));
        }
    }

    /**
     * One camera clip list — the legacy output camera or one multi-camera track's clips — whose clips
     * carry a {@code selector} targeting a replay by index.
     */
    private static void convertCameraSelectors(ListType camera, List<String> replayIds, List<Map<String, String>> formMappings)
    {
        for (BaseType clipType : camera)
        {
            if (!clipType.isMap())
            {
                continue;
            }

            MapType clip = clipType.asMap();
            BaseType selector = clip.get("selector");

            if (selector == null)
            {
                continue;
            }

            boolean converted = BaseType.isString(selector);
            int index = converted ? replayIds.indexOf(clip.getString("selector")) : clip.getInt("selector", -1);
            String id = index >= 0 && index < replayIds.size() ? replayIds.get(index) : null;

            if (index >= 0 && id == null)
            {
                LOGGER.warn("Camera clip selector points at replay [" + index + "] which does not exist; clearing");
            }

            if (!converted)
            {
                clip.putString("selector", id == null ? "" : id);
            }

            /* The tracker's attachment path into the tracked replay's matrix tree. */
            if (id != null && clip.has("group"))
            {
                clip.putString("group", rewriteAttachment(clip.getString("group"), formMappings.get(index)));
            }
        }
    }

    /**
     * Normalize every action clip target of one replay: a legacy numeric {@code "replay_id"} becomes
     * the targeted replay's stable id, and an index that no longer resolves becomes an explicit
     * "no target".
     *
     * <p>Public and reusable because a replay also travels outside a film document — a clipboard
     * replay, a replay preset — where there is no film version gate and, for a cross-film paste, no
     * source replay order to resolve against. Such a caller passes an <em>empty</em> id list: with
     * the order unknown the index cannot be trusted, so it fails closed to {@code ""} and
     * {@code ActionTarget.resolve} falls back to the recorded uuid. An id that is already stable is
     * left alone, which is what keeps a within-film copy/paste resolving correctly.</p>
     */
    public static void migrateReplayReferences(MapType replay, List<String> replayIds)
    {
        forEachActionTarget(replay, (target) -> convertActionTarget(target, replayIds));
    }

    /**
     * Clear a {@code "replay_id"} that names a replay the receiving film does not contain.
     *
     * <p>This answers a different question than {@link #migrateReplayReferences}, and mixing the two
     * is what makes it necessary to keep them apart. That method resolves a <em>legacy index</em>
     * against the source film's order; this one audits an <em>already stable id</em> for membership
     * in the receiving film. A stable id survives the legacy conversion untouched on purpose — that
     * is what keeps a within-film copy/paste pointing where it did — so a cross-film paste would
     * otherwise carry the <em>source</em> film's ids into a film that has never heard of them, and
     * {@code ActionTarget.resolve} would take its scoped branch and lose the uuid fallback. Clearing
     * them restores that fallback, the same recovery {@link #migrateReplayReferences} already grants
     * an unresolvable index.</p>
     *
     * <p>A legacy index is deliberately not this method's business: an unknown order still fails
     * closed, but through {@link #migrateReplayReferences}, so a caller cannot accidentally turn "the
     * source order is unknown" into "resolve it against the receiving film". Only {@code "replay_id"}
     * is touched, never {@code "uuid"} — see {@link #convertActionTarget}.</p>
     */
    public static void pruneDanglingReplayReferences(MapType replay, Collection<String> knownReplayIds)
    {
        forEachActionTarget(replay, (target) -> pruneDanglingReplayReference(target, knownReplayIds));
    }

    /** Every {@code "target"} map of one replay, in document order. One walk, two questions. */
    private static void forEachActionTarget(MapType replay, Consumer<MapType> visitor)
    {
        if (!replay.has(ACTIONS))
        {
            return;
        }

        for (BaseType type : replay.getList(ACTIONS))
        {
            if (!type.isMap())
            {
                continue;
            }

            MapType clip = type.asMap();

            if (!clip.has(TARGET))
            {
                continue;
            }

            BaseType targetType = clip.get(TARGET);

            if (targetType != null && targetType.isMap())
            {
                visitor.accept(targetType.asMap());
            }
        }
    }

    /** One {@code "target"} map of the membership pass: only an id the receiving film lacks is cut. */
    private static void pruneDanglingReplayReference(MapType target, Collection<String> knownReplayIds)
    {
        String current = target.getString(REPLAY_ID, "");

        if (!StableIds.isStableId(current) || knownReplayIds.contains(current))
        {
            return;
        }

        target.putString(REPLAY_ID, "");
    }

    /**
     * One {@code "target"} map. Only {@code "replay_id"} is touched, never {@code "uuid"}: the uuid
     * is the exact match {@code ActionTarget.resolve} falls back to once this id is empty, and
     * overwriting it would destroy the only precise route left.
     */
    private static void convertActionTarget(MapType target, List<String> replayIds)
    {
        BaseType value = target.get(REPLAY_ID);

        if (value == null)
        {
            return;
        }

        int index;

        if (BaseType.isString(value))
        {
            String current = target.getString(REPLAY_ID, "");

            /* Idempotent: already a stable id, or explicitly "no target". */
            if (current.isEmpty() || StableIds.isStableId(current))
            {
                return;
            }

            index = parseIntOrNegative(current);
        }
        else
        {
            index = target.getInt(REPLAY_ID, -1);
        }

        String id = index >= 0 && index < replayIds.size() ? replayIds.get(index) : "";

        /* An unresolvable target becomes an explicit "no target" rather than a dangling index:
         * ActionTarget.resolve() then falls back to the recorded uuid. Never "-1", which is a
         * non-empty id that queries the actor scope and loses that fallback. */
        target.putString(REPLAY_ID, id == null ? "" : id);
    }

    /**
     * A legacy index as text, or -1. Deliberately {@code Integer.parseInt} rather than
     * {@code StringUtils.isInteger}: the latter answers {@code true} for the empty string and for
     * "-", so it cannot be used as "is this an integer".
     */
    private static int parseIntOrNegative(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    /**
     * An attachment path is {@code [<part indices>/]<bone>} or just a part path (the whole part);
     * the leading index segments are rewritten exactly like track keys are.
     */
    private static String rewriteAttachment(String attachment, Map<String, String> mapping)
    {
        return attachment.isEmpty() ? attachment : FormStableIds.rewriteTrackKey(attachment, mapping);
    }
}
