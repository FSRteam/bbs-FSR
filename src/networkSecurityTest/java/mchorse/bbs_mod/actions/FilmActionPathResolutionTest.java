package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.StableIds;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression for the two ways a raw path segment can be resolved against a list without matching
 * the element the path actually names. Both were live defects in
 * {@link FilmActionAuthorityPolicy#isRawMutationAllowedForNonAdministrator} while it resolved a list
 * segment by position only; both are silent when the fixture has a single element.
 *
 * <p>{@link FilmRawPreflightTest#testOmittedSwipeLeafCanBeSafelyInserted()} builds exactly one
 * replay, so the correct resolver and the two wrong ones below agree on its fixture:
 *
 * <ul>
 *   <li>an index lookup that matches the first list element instead of the one whose stable id
 *       matched -- indistinguishable when the target is the only element;</li>
 *   <li>an index lookup that falls back to element 0 when nothing matched -- indistinguishable when
 *       the path always names the element that is already there.</li>
 * </ul>
 *
 * <p>This fixture supplies the input domain that separates them: a target that is <em>not</em> the
 * first replay, and a path that names a stable id the receiving film does not contain at all.
 *
 * <p>Called by {@code NetworkSecurityTest.main} so it runs on the {@code testNetworkSecurity} gate;
 * the gate task executes that single main class, so a sibling test the gate never calls would be
 * coverage on paper only.
 */
public final class FilmActionPathResolutionTest
{
    private FilmActionPathResolutionTest()
    {}

    public static void runAll()
    {
        Runnable restoreRuntime = installSwipeActionFactory();

        try
        {
            targetBehindAFirstReplayIsStillReachable();
            aReplayIdTheReceivingFilmDoesNotHaveIsRefused();
        }
        finally
        {
            restoreRuntime.run();
        }
    }

    /**
     * The edited value belongs to the second replay. Resolving its stable-id segment as the first
     * list element walks into the first replay instead, which carries no actions, and the edit is
     * denied. The first replay is deliberately present and deliberately empty: it is the element a
     * positional or first-map lookup would pick, and it must not be the one the path resolves to.
     */
    private static void targetBehindAFirstReplayIsStillReachable()
    {
        Film film = new Film();
        Replay first = film.replays.addReplay();
        Replay second = film.replays.addReplay();
        SwipeActionClip swipe = new SwipeActionClip();

        film.setId("fixture");
        second.actions.addClip(swipe);

        check(first.actions.size() == 0, "the first replay was not left without actions");
        check(film.replays.getList().indexOf(second) == 1, "the target replay is not the second element");
        check(!swipe.hand.getPath().strings.contains(first.getId()),
            "the requested path names the first replay, so this fixture does not separate the resolvers");

        check(FilmActionAuthorityPolicy.isRawMutationAllowedForNonAdministrator(
                film,
                swipe.hand,
                swipe.hand.getPath(),
                new ByteType(false)
            ),
            "a safe exact-Swipe leaf edit in the second replay was rejected");
    }

    /**
     * The path carries a stable id that the receiving film does not contain, while its first replay
     * has exactly the same shape as the target. Falling back to element 0 on a failed lookup would
     * hand the mutation to that unrelated replay; refusing is the only correct answer.
     *
     * <p>The two fixtures must not accidentally share a stable id, otherwise the path does name an
     * element of the receiving film and the assertion stops distinguishing anything -- so the
     * ids are compared before the policy runs.
     */
    private static void aReplayIdTheReceivingFilmDoesNotHaveIsRefused()
    {
        Film receiving = new Film();
        Replay resident = receiving.replays.addReplay();
        SwipeActionClip residentSwipe = new SwipeActionClip();

        receiving.setId("fixture");
        resident.actions.addClip(residentSwipe);

        Film sender = new Film();
        Replay foreign = sender.replays.addReplay();
        SwipeActionClip foreignSwipe = new SwipeActionClip();

        sender.setId("fixture");
        foreign.actions.addClip(foreignSwipe);

        check(!resident.getId().equals(foreign.getId()),
            "the two fixtures accidentally share a replay id, so this witness proves nothing");
        check(!rawReplayIds(receiving).contains(foreign.getId()),
            "the foreign replay id is present in the receiving film's raw data, "
                + "so the path does name an element and this witness proves nothing");

        check(!FilmActionAuthorityPolicy.isRawMutationAllowedForNonAdministrator(
                receiving,
                foreignSwipe.hand,
                foreignSwipe.hand.getPath(),
                new ByteType(false)
            ),
            "a replay id that is absent from the receiving film was resolved to its first element");
    }

    /**
     * The stable ids the receiving film actually carries in its raw data -- the same list, and the
     * same key, that the authority policy walks. Read from the serialized shape rather than from the
     * value objects, because it is the serialized shape that decides whether the requested segment
     * can match at all.
     */
    private static List<String> rawReplayIds(Film film)
    {
        List<String> ids = new ArrayList<>();

        for (BaseType element : ((MapType) film.toData()).getList("replays"))
        {
            if (element.isMap())
            {
                String id = element.asMap().getString(StableIds.KEY);

                if (id != null)
                {
                    ids.add(id);
                }
            }
        }

        return ids;
    }

    /**
     * {@code Clips.toData()} serializes through the factory held by the clip list's owning mod, so a
     * raw Film snapshot cannot be taken without one. Installed before any Film is constructed,
     * because the list captures the factory at construction time, and restored afterwards so this
     * suite cannot change what a later suite in the same JVM sees.
     */
    private static Runnable installSwipeActionFactory()
    {
        try
        {
            Field actionFactoryField = BBSMod.class.getDeclaredField("factoryActionClips");

            actionFactoryField.setAccessible(true);

            Object previousFactory = actionFactoryField.get(null);
            MapFactory<Clip, ClipFactoryData> factory = new MapFactory<>();

            factory.register(Link.bbs("swipe"), SwipeActionClip.class, null);
            actionFactoryField.set(null, factory);

            return () ->
            {
                try
                {
                    actionFactoryField.set(null, previousFactory);
                }
                catch (IllegalAccessException e)
                {
                    throw new AssertionError("could not restore the action clip factory", e);
                }
            };
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("could not install the swipe action factory fixture", e);
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
