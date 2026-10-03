package mchorse.bbs_mod.data.migration;

import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.settings.values.core.StableIds;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueStableList;
import mchorse.bbs_mod.settings.values.core.ValueString;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;

/**
 * Format 1 &rarr; 2, exercised through the migration entry points directly rather than through
 * {@code Film.fromData}: the document version gate is not what is under test here, the conversion is.
 *
 * <p><strong>The fixtures are hand-built legacy documents, never {@code Film.toData()} output.</strong>
 * A round trip that starts from {@code Film.toData()} starts from the <em>converted</em> shape — the
 * replays already carry {@code "id"} keys, the actors are already strings — so it would exercise the
 * migration zero times while still passing. Every fixture in this class is therefore a raw
 * {@link MapType} written out key by key with the pre-2 shapes: replay maps with no {@code "id"},
 * anchors whose {@code "actor"} is an {@link IntType}, and action targets whose {@code "replay_id"}
 * is legacy index <em>text</em>.
 *
 * <p>The migration is also <em>not</em> what makes the two formats differ on read: with an
 * {@code IntType} actor, {@code MapType.getString} answers {@code ""}, so an unconverted anchor is
 * not an error the reader notices — it is a silent loss of the target. Several assertions below are
 * therefore contrast assertions against that silence.
 */
public final class FilmStableIdsMigrationTest
{
    private static final String ACTOR = "actor";
    private static final String ATTACHMENT = "attachment";
    private static final String REPLAY_ID = "replay_id";

    private FilmStableIdsMigrationTest()
    {}

    public static void runAll()
    {
        aLegacyFixtureIsNotAlreadyConverted();
        everyReplayGainsItsOwnStableId();
        twoIndependentReadersOfTheSameDocumentAgreeOnEveryId();
        theMigrationDoesNotStampTheDocument();

        aLegacyIntAnchorActorBecomesTheTargetReplaysId();
        theAnchorsAttachmentIsRewrittenAgainstTheTargetsForm();
        anAnchorChannelIsConvertedAndItsTrackKeyIsRenamed();
        aLegacyIntCameraSelectorBecomesTheTargetReplaysId();

        aLegacyActionTargetIndexBecomesTheTargetReplaysId();
        anUnresolvableActionTargetIndexBecomesAnEmptyTarget();
        anAlreadyStableActionTargetIsLeftAlone();
        anExplicitlyEmptyActionTargetStaysEmpty();
        migratingTwiceIsTheSameAsMigratingOnce();

        aCrossFilmPasteFailsClosedInsteadOfResolvingAgainstTheReceiver();
        thePruneClearsExactlyTheIdsTheReceivingFilmLacks();
        theIndexPassAfterThePruneIsANoOp();

        theReferenceResolvesBackToTheElementItNames();

        theScopedBranchEatsTheUuidFallback();
        anEmptyTargetIdReachesTheUuidLookup();
    }

    /* ------------------------------------------------------------------ fixture --- */

    /**
     * A two-replay legacy film. Replay 0 owns a form with two body parts, a static anchor and an
     * anchor keyframe channel; replay 1 owns one body part. Every replay has action clips, and the
     * film has one camera clip. Nothing carries an {@code "id"} and no actor/selector is a string.
     */
    private static MapType legacyFilm()
    {
        MapType film = new MapType();
        ListType replays = new ListType();

        MapType firstReplay = new MapType();

        firstReplay.put("form", nestedForm("first"));
        firstReplay.getMap("form").put("anchor", legacyAnchor(0, "1/bone"));
        firstReplay.put("properties", trackList("0/0", legacyAnchor(1, "")));
        firstReplay.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-000000000001")));
        replays.add(firstReplay);

        MapType secondReplay = new MapType();

        secondReplay.put("form", form("second", 1));
        secondReplay.put("actions", actionList(
            action(1, "aaaaaaaa-0000-0000-0000-000000000002"),
            action(9, "aaaaaaaa-0000-0000-0000-000000000003")
        ));
        replays.add(secondReplay);

        film.put("replays", replays);

        ListType camera = new ListType();
        MapType look = new MapType();

        look.putString("type", "bbs:look");
        look.put("selector", new IntType(0));
        camera.add(look);
        film.put("camera", camera);

        return film;
    }

    /** A raw form with {@code count} parts, none of them carrying an id. */
    private static MapType form(String label, int count)
    {
        MapType form = new MapType();
        ListType parts = new ListType();

        for (int i = 0; i < count; i++)
        {
            MapType part = new MapType();

            part.putString("type", "bbs:model");
            part.putString("label", label + "-" + i);
            parts.add(part);
        }

        form.put("parts", parts);

        return form;
    }

    /**
     * Two parts where the first one owns a nested form with a single part. A track key of
     * {@code "0/0"} therefore has to be rewritten across two levels, which is the only shape that
     * exercises the mapping's nested entries at all.
     */
    private static MapType nestedForm(String label)
    {
        MapType form = form(label, 2);

        form.getList("parts").getMap(0).put("form", form(label + "-nested", 1));

        return form;
    }

    /** The legacy anchor shape: an {@link IntType} actor with a body-part attachment path. */
    private static MapType legacyAnchor(int actor, String attachment)
    {
        MapType anchor = new MapType();

        anchor.put(ACTOR, new IntType(actor));
        anchor.putString(ATTACHMENT, attachment);

        return anchor;
    }

    /** The properties shape written since the track refactor: {@code tracks[i].channel}. */
    private static MapType trackList(String formPath, MapType anchorValue)
    {
        MapType properties = new MapType();
        ListType tracks = new ListType();
        MapType entry = new MapType();
        MapType channel = new MapType();
        MapType keyframe = new MapType();
        ListType keyframes = new ListType();

        keyframe.put("value", anchorValue);
        keyframes.add(keyframe);
        channel.putString("type", "anchor");
        channel.put("keyframes", keyframes);
        entry.putString("form", formPath);
        entry.put("channel", channel);
        tracks.add(entry);
        properties.put("tracks", tracks);

        return properties;
    }

    private static MapType action(int legacyIndex, String uuid)
    {
        MapType clip = new MapType();
        MapType target = new MapType();

        clip.putString("type", "bbs:attack");
        target.putString("uuid", uuid);
        target.putString("entity_type", "minecraft:pig");
        target.putString(REPLAY_ID, String.valueOf(legacyIndex));
        clip.put("target", target);

        return clip;
    }

    private static ListType actionList(MapType... actions)
    {
        ListType list = new ListType();

        for (MapType action : actions)
        {
            list.add(action);
        }

        return list;
    }

    private static MapType migratedLegacyFilm()
    {
        MapType film = legacyFilm();

        new FilmStableIds().migrate(film);

        return film;
    }

    private static ListType replaysOf(MapType film)
    {
        return film.getList("replays");
    }

    private static String replayId(MapType film, int index)
    {
        return replaysOf(film).getMap(index).getString(StableIds.KEY);
    }

    private static MapType firstActionTarget(MapType film, int replayIndex, int actionIndex)
    {
        return replaysOf(film).getMap(replayIndex).getList("actions").getMap(actionIndex).getMap("target");
    }

    /**
     * The target of one action of a bare replay map — the shape a clipboard replay or a preset has,
     * where there is no enclosing film. Deliberately a separate helper from
     * {@link #firstActionTarget(MapType, int, int)}: addressing a bare replay through the film helper
     * silently reads an empty {@code "replays"} list, and {@code ListType.getMap} answers a
     * <em>fresh</em> map for a missing index, so every write would land on a throwaway object and
     * every assertion would be about nothing.
     */
    private static MapType actionTarget(MapType replay, int actionIndex)
    {
        MapType target = replay.getList("actions").getMap(actionIndex).getMap("target");

        require(target.has(REPLAY_ID),
            "the fixture action at index " + actionIndex + " has no \"" + REPLAY_ID + "\" to assert on");

        return target;
    }

    /* --------------------------------------------------------------- identity --- */

    /**
     * The premise the whole file rests on: the fixture really is in the legacy shape. If a future
     * edit made {@link #legacyFilm()} carry ids, every round-trip assertion below would start
     * passing for the wrong reason.
     */
    private static void aLegacyFixtureIsNotAlreadyConverted()
    {
        MapType film = legacyFilm();
        MapType firstReplay = replaysOf(film).getMap(0);

        require(!firstReplay.has(StableIds.KEY), "the fixture replay already carries an \"" + StableIds.KEY + "\" key");
        require(!firstReplay.getMap("form").getList("parts").getMap(0).has(StableIds.KEY),
            "the fixture body part already carries an \"" + StableIds.KEY + "\" key");
        require(firstReplay.getMap("form").getMap("anchor").get(ACTOR) instanceof IntType,
            "the fixture anchor actor is not the legacy IntType shape");
        require("0".equals(firstActionTarget(film, 0, 0).getString(REPLAY_ID)),
            "the fixture action target is not a legacy index in text form");
        require(SaveVersion.read(film) == SaveVersion.LEGACY,
            "the fixture carries a format version, so it is not a legacy document");

        /* And the consequence of that shape: read as typed data, the legacy actor is not an error,
         * it is an empty target. This is the silence the migration exists to end. */
        Anchor unconverted = new Anchor();

        unconverted.fromData(firstReplay.getMap("form").getMap("anchor"));

        require(Anchor.NO_ATTACHMENT.equals(unconverted.replay),
            "an unconverted legacy anchor no longer reads as \"no target\", so this file's contrast "
                + "assertions would not be measuring the migration: got \"" + unconverted.replay + "\"");
        require(!unconverted.hasTarget(),
            "an unconverted anchor reads as having a target, which would make the fixture already valid");
    }

    private static void everyReplayGainsItsOwnStableId()
    {
        MapType film = migratedLegacyFilm();

        require(replaysOf(film).size() == 2, "the fixture lost a replay during migration");

        String first = replayId(film, 0);
        String second = replayId(film, 1);

        require(StableIds.isStableId(first), "replay 0 has no stable id after migration: \"" + first + "\"");
        require(StableIds.isStableId(second), "replay 1 has no stable id after migration: \"" + second + "\"");
        require(!first.equals(second), "both replays were given the same stable id: " + first);

        /* The replay's own form and body parts are stamped too, by the same conversion. */
        String part = replaysOf(film).getMap(0).getMap("form").getList("parts").getMap(1).getString(StableIds.KEY);

        require(StableIds.isStableId(part), "a body part has no stable id after migration: \"" + part + "\"");
    }

    /**
     * The reason {@code StableIds.fromLegacyIndex} exists rather than {@code generate()}: the client
     * and the server read the same file independently, and a random id would differ per side, so
     * every cross-reference between an unsaved legacy document's replays would mean two different
     * things. Two readers of byte-identical legacy documents must land on byte-identical documents.
     */
    private static void twoIndependentReadersOfTheSameDocumentAgreeOnEveryId()
    {
        MapType firstReader = migratedLegacyFilm();
        MapType secondReader = migratedLegacyFilm();

        require(BaseType.equals(firstReader, secondReader),
            "two independent readers of the same legacy document produced different documents: "
                + firstReader + " vs " + secondReader);
    }

    /**
     * Stamping is the save path's job ({@code BaseManager.save} / {@code SaveVersion.stamp}). A
     * migration that stamps would make a document that has never been saved look like one this
     * build already wrote, and a later reader would skip nothing but trust a wrong version.
     */
    private static void theMigrationDoesNotStampTheDocument()
    {
        MapType film = migratedLegacyFilm();

        require(SaveVersion.read(film) == SaveVersion.LEGACY,
            "the migration stamped the format version: it must leave that to the save path, got "
                + SaveVersion.read(film));
    }

    /* --------------------------------------------------------------- anchors --- */

    private static void aLegacyIntAnchorActorBecomesTheTargetReplaysId()
    {
        MapType film = migratedLegacyFilm();
        MapType anchorMap = replaysOf(film).getMap(0).getMap("form").getMap("anchor");
        Anchor anchor = new Anchor();

        anchor.fromData(anchorMap);

        require(anchor.hasTarget(), "the migrated anchor still reads as \"no target\"");
        require(replayId(film, 0).equals(anchor.replay),
            "the anchor did not resolve to the first replay's id: actor=\"" + anchor.replay
                + "\" want \"" + replayId(film, 0) + "\"");
    }

    /**
     * An attachment is a path into the <em>target</em> replay's matrix tree, and its leading
     * segments were body-part indices. Those indices are now ids, and the rewrite must use the
     * target's own form, not the anchor owner's.
     */
    private static void theAnchorsAttachmentIsRewrittenAgainstTheTargetsForm()
    {
        MapType film = migratedLegacyFilm();
        String partId = replaysOf(film).getMap(0).getMap("form").getList("parts").getMap(1).getString(StableIds.KEY);
        Anchor anchor = new Anchor();

        anchor.fromData(replaysOf(film).getMap(0).getMap("form").getMap("anchor"));

        require((partId + "/bone").equals(anchor.attachment),
            "the anchor attachment was not rewritten against the target's form: got \"" + anchor.attachment
                + "\" want \"" + partId + "/bone\"");
    }

    /**
     * An anchor keyframe channel inside the replay's property tracks is rewritten by the same rules,
     * and its track key ({@code "0/1"}) becomes an id path in the same pass. The channel here points
     * at the <em>second</em> replay, so an implementation that always resolved to index 0 would show
     * up here.
     */
    private static void anAnchorChannelIsConvertedAndItsTrackKeyIsRenamed()
    {
        MapType film = migratedLegacyFilm();
        MapType track = replaysOf(film).getMap(0).getMap("properties").getList("tracks").getMap(0);
        MapType firstPart = replaysOf(film).getMap(0).getMap("form").getList("parts").getMap(0);
        String nestedPartId = firstPart.getMap("form").getList("parts").getMap(0).getString(StableIds.KEY);

        require(replayId(film, 1).equals(track.getMap("channel").getList("keyframes").getMap(0).getMap("value").getString(ACTOR)),
            "the anchor keyframe channel did not resolve to the second replay's id");

        String formPath = track.getString("form");
        String expected = firstPart.getString(StableIds.KEY) + "/" + nestedPartId;

        require(formPath.equals(expected),
            "the track key was not renamed across both legacy index segments: got \"" + formPath
                + "\" want \"" + expected + "\"");
    }

    private static void aLegacyIntCameraSelectorBecomesTheTargetReplaysId()
    {
        MapType film = migratedLegacyFilm();
        String selector = film.getList("camera").getMap(0).getString("selector");

        require(replayId(film, 0).equals(selector),
            "the camera selector did not resolve to the first replay's id: got \"" + selector + "\"");
    }

    /* ----------------------------------------------------------- action targets --- */

    private static void aLegacyActionTargetIndexBecomesTheTargetReplaysId()
    {
        MapType film = migratedLegacyFilm();

        require(replayId(film, 0).equals(firstActionTarget(film, 0, 0).getString(REPLAY_ID)),
            "an action target recorded on replay 0 did not resolve to replay 0's id");
        require(replayId(film, 1).equals(firstActionTarget(film, 1, 0).getString(REPLAY_ID)),
            "an action target recorded on replay 1 did not resolve to replay 1's id");

        /* Read back through the typed value the runtime actually uses, so the assertion is about the
         * value a caller will see and not only about the raw map. */
        ActionTarget target = new ActionTarget("target");

        target.fromData(firstActionTarget(film, 1, 0));

        require(replayId(film, 1).equals(target.replayId.get()),
            "the typed target did not read the migrated id back: got \"" + target.replayId.get() + "\"");
    }

    /**
     * Three states, not one — an index that resolves, an index that does not, and an explicit empty
     * target. Testing only one of them is the blind spot this whole file exists to close.
     */
    private static void anUnresolvableActionTargetIndexBecomesAnEmptyTarget()
    {
        MapType film = migratedLegacyFilm();
        String dangling = firstActionTarget(film, 1, 1).getString(REPLAY_ID);

        require(!"-1".equals(dangling),
            "an unresolvable target was written as \"-1\", a non-empty id that queries the actor scope "
                + "and loses ActionTarget.resolve's uuid fallback");
        require("".equals(dangling),
            "an action target whose index resolves to no replay must become an explicit \"no target\": got \"" + dangling + "\"");
        require(!dangling.equals(replayId(film, 1)) && !dangling.equals(replayId(film, 0)),
            "an unresolvable index was resolved to some other replay: \"" + dangling + "\"");
        require(StableIds.isStableId(firstActionTarget(film, 0, 0).getString(REPLAY_ID))
            && "".equals(firstActionTarget(film, 1, 1).getString(REPLAY_ID)),
            "the resolvable and unresolvable states no longer differ, so the assertions above prove nothing");
    }

    private static void anAlreadyStableActionTargetIsLeftAlone()
    {
        MapType replay = new MapType();

        replay.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-000000000004")));
        actionTarget(replay, 0).putString(REPLAY_ID, "abc123de");
        FilmStableIds.migrateReplayReferences(replay, List.of("ffffffff"));

        require("abc123de".equals(actionTarget(replay, 0).getString(REPLAY_ID)),
            "an already-stable target id was rewritten, which would orphan an existing reference");
    }

    private static void anExplicitlyEmptyActionTargetStaysEmpty()
    {
        MapType replay = new MapType();

        replay.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-000000000005")));
        actionTarget(replay, 0).putString(REPLAY_ID, "");
        FilmStableIds.migrateReplayReferences(replay, List.of("abc123de"));

        require("".equals(actionTarget(replay, 0).getString(REPLAY_ID)),
            "an explicit \"no target\" was filled in by the migration");

        MapType negative = new MapType();

        negative.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-000000000006")));
        actionTarget(negative, 0).putString(REPLAY_ID, "-1");
        FilmStableIds.migrateReplayReferences(negative, List.of("abc123de"));

        require("".equals(actionTarget(negative, 0).getString(REPLAY_ID)),
            "a legacy \"-1\" sentinel was resolved instead of being cleared");
    }

    /**
     * {@code migrate} does not stamp, so the same un-stamped document is handed to {@code fromData}
     * again on every read until it is saved. A migration that is not idempotent corrupts the
     * document a little more on each read.
     */
    private static void migratingTwiceIsTheSameAsMigratingOnce()
    {
        MapType once = legacyFilm();
        MapType twice = legacyFilm();

        new FilmStableIds().migrate(once);
        new FilmStableIds().migrate(twice);
        new FilmStableIds().migrate(twice);

        require(BaseType.equals(once, twice),
            "migrating twice differs from migrating once: " + once + " vs " + twice);
        require(SaveVersion.read(twice) == SaveVersion.LEGACY,
            "the second migration stamped the document");
    }

    /* ------------------------------------------------------- cross-film paste --- */

    /**
     * A replay also travels outside a film document — a clipboard replay, a replay preset. There is
     * no source order to resolve a legacy index against, so the index must fail closed rather than
     * be guessed. The counterfactual below is the hazard being refused: passing the <em>receiving</em>
     * film's ids makes the same legacy index resolve to that film's replay, which is the bug scheme A
     * exists to prevent.
     */
    private static void aCrossFilmPasteFailsClosedInsteadOfResolvingAgainstTheReceiver()
    {
        String receivingId = "ffffffff";

        MapType pasted = new MapType();

        pasted.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-000000000007")));
        FilmStableIds.migrateReplayReferences(pasted, List.of());

        require("".equals(actionTarget(pasted, 0).getString(REPLAY_ID)),
            "a cross-film paste with an unknown source order resolved a legacy index instead of failing closed: got \""
                + actionTarget(pasted, 0).getString(REPLAY_ID) + "\"");

        MapType counterfactual = new MapType();

        counterfactual.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-000000000008")));
        FilmStableIds.migrateReplayReferences(counterfactual, List.of(receivingId));

        require(receivingId.equals(actionTarget(counterfactual, 0).getString(REPLAY_ID)),
            "the counterfactual no longer demonstrates the hazard, so the assertion above is not "
                + "distinguishing fail-closed from \"resolved against the receiving film\"");
    }

    /**
     * The membership pass answers a different question than the index pass: an <em>already stable</em>
     * id that the receiving film does not contain is cut, an id it does contain is kept, a legacy
     * index is left for the index pass, and {@code "uuid"} is never touched.
     */
    private static void thePruneClearsExactlyTheIdsTheReceivingFilmLacks()
    {
        String mine = "abc123de";
        String foreign = "abcdef01";
        MapType replay = new MapType();

        replay.put("actions", actionList(
            action(0, "aaaaaaaa-0000-0000-0000-000000000009"),
            action(0, "aaaaaaaa-0000-0000-0000-00000000000a"),
            action(0, "aaaaaaaa-0000-0000-0000-00000000000b"),
            action(0, "aaaaaaaa-0000-0000-0000-00000000000c")
        ));
        actionTarget(replay, 0).putString(REPLAY_ID, foreign);
        actionTarget(replay, 1).putString(REPLAY_ID, mine);
        actionTarget(replay, 2).putString(REPLAY_ID, "3");
        actionTarget(replay, 3).putString(REPLAY_ID, "");

        FilmStableIds.pruneDanglingReplayReferences(replay, List.of(mine));

        require("".equals(actionTarget(replay, 0).getString(REPLAY_ID)),
            "an id the receiving film does not contain survived the prune, so the scoped branch would "
                + "keep eating the uuid fallback");
        require(mine.equals(actionTarget(replay, 1).getString(REPLAY_ID)),
            "an id the receiving film does contain was cleared, breaking a within-film copy/paste");
        require("3".equals(actionTarget(replay, 2).getString(REPLAY_ID)),
            "the prune resolved a legacy index against the receiving film; that is the index pass's job");
        require("".equals(actionTarget(replay, 3).getString(REPLAY_ID)),
            "the prune rewrote an explicit \"no target\"");
        require("aaaaaaaa-0000-0000-0000-000000000009".equals(actionTarget(replay, 0).getString("uuid")),
            "the prune overwrote the recorded uuid, destroying the only exact fallback left");

        /* Idempotent, and it never invents an id. */
        FilmStableIds.pruneDanglingReplayReferences(replay, List.of(mine));

        require("".equals(actionTarget(replay, 0).getString(REPLAY_ID)),
            "pruning twice changed the result");
    }

    /**
     * The paste path prunes and then hands the replay to {@code Replay.fromData}, whose index pass
     * runs with an empty id list. The pruned {@code ""} has to short-circuit that second pass, or
     * the two passes would fight over the same key.
     *
     * <p>The fixture carries exactly one action target on purpose: a second key with an unresolvable
     * legacy index would legitimately be cleared by the index pass, and the assertion would then be
     * measuring the fixture rather than the short circuit.
     */
    private static void theIndexPassAfterThePruneIsANoOp()
    {
        String foreign = "abcdef01";
        MapType replay = new MapType();

        replay.put("actions", actionList(action(0, "aaaaaaaa-0000-0000-0000-00000000000d")));
        actionTarget(replay, 0).putString(REPLAY_ID, foreign);

        FilmStableIds.pruneDanglingReplayReferences(replay, List.of());
        FilmStableIds.migrateReplayReferences(replay, List.of());

        require("".equals(actionTarget(replay, 0).getString(REPLAY_ID)),
            "the index pass after the prune did not leave the pruned value alone");
    }

    /* ------------------------------------------------------- id churn (tooth) --- */

    /**
     * The sharp form of "the reference still points at the same element": resolve the id
     * <em>back into the list</em> instead of comparing two strings.
     *
     * <p>Comparing {@code anchor.replay} against a remembered string is half a tooth — a
     * {@code ValueStableList} that regenerated every id on load would not move a string that nobody
     * holds. Asking the list which element the id names fails the moment the identity churns.
     *
     * <p>{@code Replays} itself cannot be constructed here: it drags {@code Replay}, whose clip list
     * captures {@code BBSMod.getFactoryActionClips()}, and that static initialiser needs a
     * bootstrapped NeoForge runtime. The fixture below uses the same {@link ValueStableList} base
     * class, so {@code ensureId} and the id lookup under test are the production ones.
     */
    private static void theReferenceResolvesBackToTheElementItNames()
    {
        MapType film = migratedLegacyFilm();
        String partId = replaysOf(film).getMap(0).getMap("form").getList("parts").getMap(1).getString(StableIds.KEY);
        Anchor anchor = new Anchor();

        anchor.fromData(replaysOf(film).getMap(0).getMap("form").getMap("anchor"));
        require(StableIds.isStableId(anchor.replay), "the anchor did not carry a stable id into the list lookup");

        FixtureList list = new FixtureList("replays");

        list.fromData(replaysOf(film).copy().asList());

        require(list.getAllTyped().size() == 2, "the fixture list did not read both replays");

        /* The lookup assertion comes first on purpose. Behind the id-preservation assertion it would
         * be reachable only when the ids happened to survive, which is exactly the case where the
         * lookup succeeds -- i.e. the sharp assertion would be the one that never fires. */
        Object referenced = list.get(anchor.replay);

        require(referenced != null,
            "the anchor's id \"" + anchor.replay + "\" no longer names any element of the list");
        require(list.getAllTyped().get(0).getId().equals(replayId(film, 0)),
            "the list did not preserve the migrated replay id");
        require(referenced == list.getAllTyped().get(0),
            "the anchor's id resolved to a different element than the replay it was recorded against");
        require(!list.getAllTyped().get(0).getId().equals(partId),
            "the fixture resolved a body-part id as if it were the replay id");
    }

    /* -------------------------------------------------- the consumed identity --- */

    /**
     * The wiring half of the migration, and the sharpest assertion in this file.
     *
     * <p>{@code ActionTarget.resolve} returns early from its scoped branch when the target carries a
     * non-empty id <em>and</em> the caller has published an actor map. That early return does not
     * consult {@code level} at all, so {@code level = null} is a tripwire rather than a shortcut:
     * taking the scoped branch leaves it untouched and answers {@code null} cleanly, while anything
     * that falls through to the uuid lookup dereferences it and blows up naming the method and line.
     * Passing a null level is the only way to observe <em>which</em> branch was taken from a source
     * set with no bootstrapped server to hand.
     *
     * <p>Without the actor map the same target falls through — the second probe in this file's
     * history confirmed the tripwire's prerequisite by measuring that case too.
     */
    private static void theScopedBranchEatsTheUuidFallback()
    {
        ActionTarget target = typedTarget("abc123de");
        ResolveOutcome outcome = driveResolve(target, Map.of());

        require(outcome.result == null,
            "the scoped branch returned an entity from an empty actor map");
        require(outcome.failure == null,
            "a non-empty replay id with a published actor map no longer takes resolve's early return: "
                + "it fell through to the uuid lookup (" + outcome.failure + "), which is the state the "
                + "migration exists to end");
    }

    /**
     * The mirror image, and the executable form of "the migration restores the fallback": once the
     * id has been cleared, the same target must fall through to the uuid lookup. Reaching that
     * lookup is what the tripwire reports.
     */
    private static void anEmptyTargetIdReachesTheUuidLookup()
    {
        ActionTarget target = typedTarget("");
        ResolveOutcome outcome = driveResolve(target, Map.of());

        require(outcome.failure != null,
            "an empty target id no longer falls through to the uuid lookup: resolve returned "
                + outcome.result + " instead of reaching resolveExact");
        require(outcome.failure.contains("NullPointerException"),
            "the fall-through did not come from the uuid lookup dereferencing its level: " + outcome.failure);
        require(outcome.failure.contains("resolveExact"),
            "the fall-through did not come from ActionTarget.resolveExact: " + outcome.failure);
    }

    private static ActionTarget typedTarget(String replayId)
    {
        MapType data = new MapType();

        data.putString("uuid", "aaaaaaaa-0000-0000-0000-00000000000e");
        data.putString("entity_type", "minecraft:pig");
        data.putString(REPLAY_ID, replayId);

        ActionTarget target = new ActionTarget("target");

        target.fromData(data);

        return target;
    }

    /**
     * Drives {@code resolve} with a null level and reports what happened as
     * {@code [result, failure]}: exactly one of the two is non-null. A null level is deliberate; see
     * {@link #theScopedBranchEatsTheUuidFallback()}.
     */
    /**
     * Drives {@code resolve} with a null level and reports what happened. Exactly one of the two
     * fields is set: the level being null is deliberate, so reaching the uuid lookup cannot be
     * "handled" accidentally — see {@link #theScopedBranchEatsTheUuidFallback()}.
     */
    private static ResolveOutcome driveResolve(ActionTarget target, Map<String, ? extends Entity> actors)
    {
        ResolveOutcome outcome = new ResolveOutcome();

        ActionTarget.withReplayActors(actors, () ->
        {
            try
            {
                outcome.result = target.resolve(null, (entity) -> true);
            }
            catch (Throwable e)
            {
                outcome.failure = e.getClass().getName() + " @ " + frames(e);
            }
        });

        return outcome;
    }

    /** What {@code resolve} did: an entity, or the failure that proves which branch it took. */
    private static final class ResolveOutcome
    {
        private Entity result;
        private String failure;
    }

    private static String frames(Throwable e)
    {
        StringBuilder builder = new StringBuilder();

        for (StackTraceElement frame : e.getStackTrace())
        {
            if (frame.getClassName().startsWith("mchorse.bbs_mod.actions"))
            {
                builder.append(frame.getMethodName()).append(':').append(frame.getLineNumber()).append(' ');
            }
        }

        return builder.toString();
    }

    /* ---------------------------------------------------------------- helpers --- */

    /** A stable-id list over a minimal group, so the identity machinery can be driven directly. */
    public static final class FixtureList extends ValueStableList<FixtureValue>
    {
        public FixtureList(String id)
        {
            super(id);
        }

        @Override
        protected FixtureValue create(String id)
        {
            return new FixtureValue(id);
        }
    }

    public static final class FixtureValue extends ValueGroup
    {
        public final ValueString label = new ValueString("label", "");

        public FixtureValue(String id)
        {
            super(id);

            this.add(this.label);
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
