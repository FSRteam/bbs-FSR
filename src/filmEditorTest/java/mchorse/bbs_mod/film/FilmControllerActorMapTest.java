package mchorse.bbs_mod.film;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.test.HeadlessClientTestBootstrap;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actor map's <em>write</em> side: {@link BaseFilmController#createEntities()} must key every
 * preview entity by the replay's stable id, never by its position in the list.
 *
 * <p>Before the stable-id migration the key was the position, and every reader below
 * ({@code UIFilmController.getCurrentEntity}, {@code OrbitFilmCameraController.resolveEntity},
 * {@code UIFilmController.renderStencil}) has already been switched to look the actor up with
 * {@code replay.getId()}. That makes the write side the one place where a positional key would
 * still compile, still pass every source-text check, and still leave the whole chain silently
 * empty: the stencil pass, the gizmo target and the orbit controller would all resolve nothing
 * and the actors simply would not appear in playback.</p>
 *
 * <p>The list here is deliberately mutated <em>before</em> the entity pass, so position and id
 * disagree: after removing the first replay the survivors sit at 0 and 1 while their ids are
 * untouched. A positional key would therefore be visible twice over — the surviving ids would not
 * be in the map, and the stale positions would be.</p>
 */
public final class FilmControllerActorMapTest
{
    private static final int REPLAY_COUNT = 3;

    public static void main(String[] args)
    {
        runAll();

        System.out.println("FilmControllerActorMapTest: all checks passed");
    }

    public static void runAll()
    {
        Runnable restoreClientRuntime = HeadlessClientTestBootstrap.install();
        Runnable restoreMinecraft = installHeadlessMinecraftInstance();

        try
        {
            testEntitiesAreKeyedByStableId();
            testRemovalDivergesPositionFromId();
        }
        finally
        {
            restoreMinecraft.run();
            restoreClientRuntime.run();
        }
    }

    /**
     * {@code createEntities()} reads {@code Minecraft.getInstance().level} for the stub world. No
     * client is running here, so a bare allocated instance is installed: every field stays at its
     * default, which makes the level {@code null} — and a null level is a world the stub entity
     * tolerates. Only the singleton has to exist.
     */
    private static Runnable installHeadlessMinecraftInstance()
    {
        try
        {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Field instance = minecraftClass.getDeclaredField("instance");

            instance.setAccessible(true);

            Object previous = instance.get(null);

            if (previous != null)
            {
                return () -> {};
            }

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");

            theUnsafe.setAccessible(true);

            Object unsafe = theUnsafe.get(null);
            Object headless = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, minecraftClass);

            instance.set(null, headless);

            return () ->
            {
                try
                {
                    instance.set(null, previous);
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the Minecraft test singleton", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the headless Minecraft test singleton", exception);
        }
    }

    /** Every enabled replay gets exactly one entry, under its own id, holding its own entity. */
    private static void testEntitiesAreKeyedByStableId()
    {
        Film film = filmWithReplays();
        TestFilmController controller = createEntities(film);
        Map<String, IEntity> entities = controller.getEntities();

        assertEquals(REPLAY_COUNT, entities.size(), "one actor per enabled replay");

        for (Replay replay : film.replays.getList())
        {
            IEntity entity = entities.get(replay.getId());

            assertTrue(entity != null,
                "the actor for replay " + replay.getId() + " is not reachable by its stable id, so every caller"
                    + " that looks an actor up with replay.getId() resolves nothing");
            assertTrue(entities.get(replay.getId()) == entity,
                "looking the actor map up twice by the same stable id returned different entities");
        }

        /* The ids are generated, and the generator is documented never to emit an all-digit id.
         * If that ever regressed, this test could pass for the wrong reason on a positional key. */
        for (Replay replay : film.replays.getList())
        {
            assertTrue(!replay.getId().matches("\\d+"),
                "a replay id is pure decimal, so this test could no longer tell a stable-id key from a"
                    + " positional one: " + replay.getId());
        }
    }

    /**
     * Delete the first replay and rebuild: the survivors' positions shift down by one while their
     * ids do not, so a positional key writes the wrong keys and a stable-id key writes the right
     * ones. Both directions are asserted, because either one alone can be satisfied by accident.
     */
    private static void testRemovalDivergesPositionFromId()
    {
        Film film = filmWithReplays();
        List<String> idsBefore = replayIds(film);
        Replay removed = film.replays.getList().get(0);
        String removedId = removed.getId();

        film.replays.remove(removed);

        TestFilmController controller = createEntities(film);
        Map<String, IEntity> entities = controller.getEntities();
        List<String> survivors = replayIds(film);

        assertEquals(REPLAY_COUNT - 1, entities.size(), "one actor per surviving replay");
        assertEquals(REPLAY_COUNT - 1, survivors.size(), "the replay list did not shrink as expected");

        for (int i = 0; i < survivors.size(); i++)
        {
            String id = survivors.get(i);

            assertTrue(entities.get(id) != null,
                "after a deletion the survivor at position " + i + " (id " + id + ") has no actor: the map is"
                    + " keyed by position, so every actor lookup by stable id silently fails");
        }

        assertTrue(entities.get(removedId) == null,
            "the deleted replay still has an actor in the map");

        /* The distinguishing half: the replay that used to be last is now at the removed index. A
         * positional key would answer this lookup, which is exactly the silent mis-target the
         * stable id exists to prevent. */
        String displaced = idsBefore.get(idsBefore.size() - 1);
        String stalePosition = String.valueOf(entities.size() - 1);

        assertTrue(entities.get(displaced) != null,
            "the displaced replay " + displaced + " cannot be found by its id");

        if (!displaced.equals(stalePosition))
        {
            assertTrue(entities.get(stalePosition) == null,
                "the actor map still answers to the positional key \"" + stalePosition + "\", so it is keyed by"
                    + " position rather than by stable id");
        }

        for (int i = 0; i < survivors.size(); i++)
        {
            if (!String.valueOf(i).equals(survivors.get(i)))
            {
                assertTrue(entities.get(String.valueOf(i)) == null,
                    "the actor map answers to the positional key \"" + i + "\" although no surviving replay has"
                        + " that id, so an index lookup can resolve onto the wrong actor");
            }
        }
    }

    private static TestFilmController createEntities(Film film)
    {
        TestFilmController controller = new TestFilmController(film);

        controller.createEntities();

        return controller;
    }

    private static Film filmWithReplays()
    {
        Film film = new Film();

        for (int i = 0; i < REPLAY_COUNT; i++)
        {
            film.replays.addReplay();
        }

        return film;
    }

    private static List<String> replayIds(Film film)
    {
        List<String> ids = new ArrayList<>();

        for (Replay replay : film.replays.getList())
        {
            ids.add(replay.getId());
        }

        return ids;
    }

    /** A concrete controller with no editor or world around it: the minimum that can reach createEntities. */
    private static final class TestFilmController extends BaseFilmController
    {
        private TestFilmController(Film film)
        {
            super(film);
        }

        @Override
        public Map<String, Integer> getActors()
        {
            return new LinkedHashMap<>();
        }

        @Override
        public int getTick()
        {
            return 0;
        }
    }

    private static void assertEquals(int expected, int actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }
}
