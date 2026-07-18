package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Regression for raw-first authority at every client-reachable Film decode. */
public final class FilmRawPreflightTest
{
    private static int customConstructions;

    private FilmRawPreflightTest()
    {}

    public static void main(String[] args)
    {
        bootstrapStandaloneMinecraftRuntime();
        runAll();

        System.out.println("FilmRawPreflightTest passed");
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
        Field actionFactoryField;
        Object previousFactory;

        try
        {
            actionFactoryField = BBSMod.class.getDeclaredField("factoryActionClips");
            actionFactoryField.setAccessible(true);
            previousFactory = actionFactoryField.get(null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("could not install the malicious action factory fixture", e);
        }

        MapFactory<Clip, ClipFactoryData> factory = new MapFactory<>();

        factory.register(Link.bbs("swipe"), SwipeActionClip.class, null);
        factory.register(Link.create("test:counting_action"), CountingActionClip.class, null);

        try
        {
            actionFactoryField.set(null, factory);
            testStoredFilmEntrancesDoNotConstructUnauthorizedActions(factory);
            testSyncRejectsBeforeConstruction();
            testOmittedSwipeLeafCanBeSafelyInserted();
            testLoadRetainsFailClosedExceptionContract();
            testLoadFailuresUseBoundedSemanticDiagnostics();
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("could not apply the malicious action factory fixture", e);
        }
        finally
        {
            try
            {
                actionFactoryField.set(null, previousFactory);
            }
            catch (IllegalAccessException e)
            {
                throw new AssertionError("could not restore the action factory fixture", e);
            }
        }
    }

    private static void testStoredFilmEntrancesDoNotConstructUnauthorizedActions(
        MapFactory<Clip, ClipFactoryData> factory
    )
    {
        StubFilmManager films = new StubFilmManager(effectfulRawFilm(), factory);
        String[] entrances = {"s4 LOAD", "s5 recording start", "s6 NORMAL", "s7 RESTART"};

        for (String entrance : entrances)
        {
            customConstructions = 0;

            Film rejected = FilmActionAuthorityPolicy.loadFilmForRequester(films, "fixture", false);

            check(rejected == null, entrance + " accepted an unauthorized custom action");
            check(customConstructions == 0, entrance + " constructed a custom ActionClip before raw authority");
        }

        customConstructions = 0;

        Film authorized = FilmActionAuthorityPolicy.loadFilmForRequester(films, "fixture", true);

        check(authorized != null, "the authorized control load did not reach typed Film construction");
        check(customConstructions == 1,
            "the malicious fixture did not exercise exactly one registered ActionClip constructor: " + customConstructions);
    }

    private static void testSyncRejectsBeforeConstruction()
    {
        Film film = new Film();
        MapType replacement = effectfulRawFilm();

        film.setId("fixture");

        customConstructions = 0;

        boolean allowed = FilmActionAuthorityPolicy.isRawMutationAllowedForNonAdministrator(
            film,
            film,
            new DataPath(List.of("fixture")),
            replacement
        );

        if (allowed)
        {
            film.fromData(replacement);
        }

        check(!allowed, "s8 prospective raw authority accepted a custom broad Film replacement");
        check(customConstructions == 0, "s8 sync constructed a custom ActionClip before prospective raw authority");
        check(film.replays.getList().isEmpty(), "s8 sync committed an unauthorized broad Film replacement");
        checkSyncGatePrecedesTypedMutation();
    }

    private static void checkSyncGatePrecedesTypedMutation()
    {
        Path source = findProjectRoot().resolve("src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java");

        try
        {
            String actionPlayer = Files.readString(source);
            int start = actionPlayer.indexOf("public void syncData(DataPath key, BaseType data)");
            int end = start < 0 ? -1 : actionPlayer.indexOf("public void goTo(int tick)", start);

            check(start >= 0 && end > start, "could not locate ActionPlayer.syncData source boundaries");

            String sync = actionPlayer.substring(start, end);
            int rawGate = sync.indexOf("isRawMutationAllowedForNonAdministrator");
            int typedMutation = sync.indexOf("baseValue.fromData(data)");
            int typedFailureBoundary = sync.indexOf(
                "catch (RuntimeException | LinkageError failure)",
                typedMutation
            );
            int rollbackMutation = sync.indexOf("baseValue.fromData(previous)", typedFailureBoundary);
            int rollbackFailureBoundary = sync.indexOf(
                "catch (RuntimeException | LinkageError rollbackError)",
                rollbackMutation
            );

            check(rawGate >= 0 && typedMutation > rawGate,
                "s8 prospective raw authority no longer runs before typed fromData");
            check(typedFailureBoundary > typedMutation,
                "s8 typed mutation no longer catches addon linkage failures");
            check(rollbackMutation > typedFailureBoundary && rollbackFailureBoundary > rollbackMutation,
                "s8 linkage failure rollback no longer fails closed");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect ActionPlayer.syncData raw-first ordering", e);
        }
    }

    private static void testOmittedSwipeLeafCanBeSafelyInserted()
    {
        Film film = new Film();
        Replay replay = film.replays.addReplay();
        SwipeActionClip swipe = new SwipeActionClip();

        film.setId("fixture");
        replay.actions.addClip(swipe);

        MapType serialized = (MapType) film.toData();
        MapType rawSwipe = serialized.getList("replays").getMap(0).getList("actions").getMap(0);

        check(!rawSwipe.has("hand"), "the default Swipe hand fixture was unexpectedly serialized");
        check(FilmActionAuthorityPolicy.isRawMutationAllowedForNonAdministrator(
                film,
                swipe.hand,
                swipe.hand.getPath(),
                new ByteType(false)
            ),
            "a safe exact-Swipe leaf edit was rejected because its default field was omitted");
    }

    private static void testLoadRetainsFailClosedExceptionContract()
    {
        FilmManager films = new ThrowingFilmManager(effectfulRawFilm());
        FilmManager linkageFailure = new LinkageThrowingFilmManager(effectfulRawFilm());

        check(films.load("fixture") == null, "FilmManager.load propagated typed construction failure");
        check(films.load("fixture", (raw) ->
        {
            throw new IllegalStateException("preflight fixture");
        }) == null, "FilmManager.load propagated raw preflight failure");
        check(linkageFailure.load("fixture") == null,
            "FilmManager.load propagated an addon linkage failure from typed construction");
    }

    private static void testLoadFailuresUseBoundedSemanticDiagnostics()
    {
        Path source = findProjectRoot().resolve("src/main/java/mchorse/bbs_mod/film/FilmManager.java");

        try
        {
            String manager = Files.readString(source);

            check(!manager.contains("printStackTrace"),
                "FilmManager load failures bypassed the project logger");
            check(manager.contains("topic=film.load")
                    && manager.contains("phase=raw_decode")
                    && manager.contains("phase=preflight_or_typed_decode")
                    && manager.contains("error_class={}"),
                "FilmManager load diagnostics lost their bounded semantic fields");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect FilmManager diagnostic wiring", e);
        }
    }

    private static MapType effectfulRawFilm()
    {
        MapType film = new MapType();
        ListType replays = new ListType();
        MapType replay = new MapType();
        ListType actions = new ListType();
        MapType action = new MapType();

        action.putString("type", "test:counting_action");
        actions.add(action);
        replay.put("actions", actions);
        replays.add(replay);
        film.put("replays", replays);

        return film;
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve("src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java")))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve("src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java")))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    public static final class CountingActionClip extends ActionClip
    {
        public CountingActionClip()
        {
            customConstructions += 1;
        }

        @Override
        protected Clip create()
        {
            return new CountingActionClip();
        }
    }

    private static class StubFilmManager extends FilmManager
    {
        private final MapType rawFilm;
        private final MapFactory<Clip, ClipFactoryData> actionFactory;

        private StubFilmManager(MapType rawFilm)
        {
            this(rawFilm, null);
        }

        private StubFilmManager(MapType rawFilm, MapFactory<Clip, ClipFactoryData> actionFactory)
        {
            super(() -> null);

            this.rawFilm = rawFilm;
            this.actionFactory = actionFactory;
        }

        @Override
        public MapType loadRaw(String id)
        {
            return (MapType) this.rawFilm.copy();
        }

        @Override
        public boolean exists(String name)
        {
            return true;
        }

        @Override
        protected Film createData(String id, MapType mapType)
        {
            if (this.actionFactory != null)
            {
                MapType rawAction = mapType.getList("replays").getMap(0).getList("actions").getMap(0);
                String rawType = rawAction.getString("type", null);

                check(this.actionFactory.getTypeClass(rawType) == CountingActionClip.class,
                    "the registered constructor fixture lost its raw type mapping: " + rawType);

                new CountingActionClip();
            }

            return new Film();
        }
    }

    private static final class ThrowingFilmManager extends StubFilmManager
    {
        private ThrowingFilmManager(MapType rawFilm)
        {
            super(rawFilm);
        }

        @Override
        protected Film createData(String id, MapType mapType)
        {
            throw new IllegalStateException("typed construction fixture");
        }
    }

    private static final class LinkageThrowingFilmManager extends StubFilmManager
    {
        private LinkageThrowingFilmManager(MapType rawFilm)
        {
            super(rawFilm);
        }

        @Override
        protected Film createData(String id, MapType mapType)
        {
            throw new LinkageError("addon linkage fixture");
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
