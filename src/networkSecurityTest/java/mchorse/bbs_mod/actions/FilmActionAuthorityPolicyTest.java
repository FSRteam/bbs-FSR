package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.MissingClip;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import java.util.List;
import java.util.Map;

public final class FilmActionAuthorityPolicyTest
{
    public static void main(String[] args)
    {
        bootstrapStandaloneMinecraftRuntime();
        runAll();

        System.out.println("FilmActionAuthorityPolicyTest passed");
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    public static void runAll()
    {
        testStrictVisualActionAllowlist();
        testCustomClientMarkerCannotExpandAllowlist();
        testDisabledServerActionsRemainPrivileged();
        testRawFilmPolicyWithoutTypedDecoding();
        testRawFilmShapeCannotHideDroppedActions();
        testRequesterAndFirstPersonDecisions();
        testStopNotificationMatrix();
    }

    private static void testStrictVisualActionAllowlist()
    {
        Film visual = new Film();
        Replay replay = visual.replays.addReplay();
        SwipeActionClip swipe = new SwipeActionClip();

        replay.enabled.set(false);
        replay.actions.addClip(swipe);
        check(!FilmActionAuthorityPolicy.requiresAdministrator((Clip) swipe), "the exact Swipe clip required administrator authority");
        check(!FilmActionAuthorityPolicy.requiresAdministrator(visual), "a pure Swipe film required administrator authority");

        CustomSwipeActionClip customSwipe = new CustomSwipeActionClip();

        replay.actions.addClip(customSwipe);
        check(FilmActionAuthorityPolicy.requiresAdministrator((Clip) customSwipe), "a Swipe subclass bypassed clip authority");
        check(FilmActionAuthorityPolicy.requiresAdministrator(visual), "a custom Swipe subclass bypassed the exact-class allowlist");
        check(FilmActionAuthorityPolicy.requiresAdministrator((Clip) null), "a null clip did not fail closed");
        check(FilmActionAuthorityPolicy.requiresAdministrator((Film) null), "a null film did not fail closed");
    }

    private static void testDisabledServerActionsRemainPrivileged()
    {
        Film film = new Film();
        Replay replay = film.replays.addReplay();
        CommandActionClip command = new CommandActionClip();

        replay.enabled.set(false);
        command.enabled.set(false);
        replay.actions.addClip(command);

        check(FilmActionAuthorityPolicy.requiresAdministrator((Clip) command), "a command clip bypassed clip authority");
        check(FilmActionAuthorityPolicy.requiresAdministrator(film), "a disabled server action bypassed administrator authority");
    }

    private static void testCustomClientMarkerCannotExpandAllowlist()
    {
        Film film = new Film();

        film.replays.addReplay().actions.addClip(new PretendClientActionClip());

        check(FilmActionAuthorityPolicy.requiresAdministrator(film), "a custom isClient override expanded the visual-action allowlist");
    }

    private static void testRawFilmShapeCannotHideDroppedActions()
    {
        Film empty = new Film();
        MapType rawEmpty = (MapType) empty.toData();

        check(!FilmActionAuthorityPolicy.requiresAdministrator(empty, rawEmpty), "an empty canonical film required administrator authority");

        Film rawSource = new Film();
        Replay rawReplay = rawSource.replays.addReplay();
        MapType raw = (MapType) rawSource.toData();
        ListType rawReplays = raw.getList("replays");
        MapType rawReplayData = rawReplays.getMap(0);
        ListType rawActions = rawReplayData.getList("actions");
        MapType unknownAction = new MapType();

        unknownAction.putString("type", "bbs:unknown_authority_test");
        rawActions.add(unknownAction);

        Film decoded = new Film();

        decoded.fromData(raw);

        /* Since the plugin hot-reload work, an unknown action is no longer
         * silently dropped: it decodes to a non-executing MissingClip
         * placeholder. That is the stricter contract for this policy: the
         * typed film keeps the same action count as the raw payload AND the
         * placeholder itself sits outside the exact-class allowlist, so
         * administrator authority is still required with or without the raw
         * document. */
        check(rawActions.size() == 1 && decoded.replays.getList().get(0).actions.size() == 1,
            "the unknown-action fixture was not retained as a placeholder by typed decoding");

        Clip placeholder = decoded.replays.getList().get(0).actions.get().get(0);

        check(placeholder instanceof MissingClip,
            "the unknown-action fixture did not decode to MissingClip");
        check(FilmActionAuthorityPolicy.requiresAdministrator(placeholder),
            "a MissingClip placeholder bypassed clip authority");
        check(FilmActionAuthorityPolicy.requiresAdministrator(decoded),
            "a film with an unknown action bypassed administrator authority");
        check(FilmActionAuthorityPolicy.requiresAdministrator(decoded, raw),
            "a raw unknown action bypassed authority across typed decoding");

        MapType missingActions = new MapType();
        ListType replayList = new ListType();

        replayList.add(missingActions);
        missingActions.putString("label", rawReplay.label.get());

        MapType missingActionsFilm = new MapType();

        missingActionsFilm.put("replays", replayList);
        check(!FilmActionAuthorityPolicy.requiresAdministrator(rawSource, missingActionsFilm),
            "a missing raw actions field was rejected despite a zero typed action count");

        missingActions.putString("actions", "not-a-list");
        check(FilmActionAuthorityPolicy.requiresAdministrator(rawSource, missingActionsFilm),
            "a non-list raw actions field bypassed authority");

        check(FilmActionAuthorityPolicy.requiresAdministrator(rawSource, new MapType()),
            "a missing raw replays list bypassed authority");
    }

    private static void testRawFilmPolicyWithoutTypedDecoding()
    {
        MapType raw = new MapType();
        ListType replays = new ListType();
        MapType replay = new MapType();
        ListType actions = new ListType();
        MapType swipe = new MapType();

        raw.put("replays", replays);
        replays.add(replay);
        check(!FilmActionAuthorityPolicy.requiresAdministrator(raw), "a raw replay without actions required administrator authority");

        replay.put("actions", actions);
        swipe.putString("type", "bbs:swipe");
        actions.add(swipe);
        check(!FilmActionAuthorityPolicy.requiresAdministrator(raw), "an exact raw Swipe action required administrator authority");

        swipe.putString("type", "BBS:SWIPE");
        check(FilmActionAuthorityPolicy.requiresAdministrator(raw), "a non-exact raw Swipe type bypassed authority");

        swipe.putString("type", "bbs:unknown");
        check(FilmActionAuthorityPolicy.requiresAdministrator(raw), "an unknown raw action bypassed authority");

        actions.elements.clear();
        actions.addString("bbs:swipe");
        check(FilmActionAuthorityPolicy.requiresAdministrator(raw), "a non-map raw action bypassed authority");

        replay.putString("actions", "not-a-list");
        check(FilmActionAuthorityPolicy.requiresAdministrator(raw), "a non-list raw action channel bypassed authority");
        check(FilmActionAuthorityPolicy.requiresAdministrator((MapType) null), "a null raw film did not fail closed");
    }

    private static void testRequesterAndFirstPersonDecisions()
    {
        check(FilmActionAuthorityPolicy.isRequesterAuthorized(true, true, true), "an active administrator requester was rejected");
        check(!FilmActionAuthorityPolicy.isRequesterAuthorized(false, true, true), "a disconnected requester stayed authorized");
        check(!FilmActionAuthorityPolicy.isRequesterAuthorized(true, false, true), "a replaced connection stayed authorized");
        check(!FilmActionAuthorityPolicy.isRequesterAuthorized(true, true, false), "a requester without level-2 permission stayed authorized");

        check(!FilmActionAuthorityPolicy.canApplyFirstPersonState(true, PlayerType.NORMAL, true, false),
            "a non-administrator normal playback could mutate first-person state");
        check(FilmActionAuthorityPolicy.canApplyFirstPersonState(true, PlayerType.TARGETED_COMMAND, true, true),
            "an authorized targeted playback lost its explicit first-person capability");
        check(!FilmActionAuthorityPolicy.canApplyFirstPersonState(true, PlayerType.TARGETED_COMMAND, true, false),
            "a targeted playback without a requester gained first-person authority");
        check(!FilmActionAuthorityPolicy.canApplyFirstPersonState(true, PlayerType.FILM_EDITOR, true, true),
            "an editor playback could mutate first-person state");

        check(!FilmActionAuthorityPolicy.hasRuntimeAuthority(true, false, false), "a privileged film survived requester revocation");
        check(!FilmActionAuthorityPolicy.hasRuntimeAuthority(false, true, false), "applied first-person state survived requester revocation");
        check(FilmActionAuthorityPolicy.hasRuntimeAuthority(false, false, false), "a visual film incorrectly required a requester");

    }

    private static void testStopNotificationMatrix()
    {
        check(PlayerType.NORMAL.shouldSendStopNotification(false), "normal natural completion omitted c5");
        check(PlayerType.NORMAL.shouldSendStopNotification(true), "normal forced teardown omitted c5");
        check(PlayerType.TARGETED_COMMAND.shouldSendStopNotification(false), "targeted natural completion omitted c5");
        check(PlayerType.TARGETED_COMMAND.shouldSendStopNotification(true), "targeted forced teardown omitted c5");
        check(!PlayerType.FILM_EDITOR.shouldSendStopNotification(false), "film editor natural completion changed UI semantics");
        check(PlayerType.FILM_EDITOR.shouldSendStopNotification(true), "film editor forced teardown left its owner UI alive");
        check(!PlayerType.RECORDING.shouldSendStopNotification(false), "recording natural completion emitted c5");
        check(!PlayerType.RECORDING.shouldSendStopNotification(true), "recording forced teardown emitted c5");
    }

    private static final class CustomSwipeActionClip extends SwipeActionClip
    {
        @Override
        protected Clip create()
        {
            return new CustomSwipeActionClip();
        }
    }

    private static final class PretendClientActionClip extends ActionClip
    {
        @Override
        public boolean isClient()
        {
            return true;
        }

        @Override
        protected Clip create()
        {
            return new PretendClientActionClip();
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
