package mchorse.bbs_mod.actions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Binary and source regressions for legacy direct-addon ActionManager calls. */
public final class ActionManagerCompatibilityTest
{
    private static final Path ACTION_MANAGER = Path.of("src/main/java/mchorse/bbs_mod/actions/ActionManager.java");
    private static final Path SERVER_NETWORK = Path.of("src/main/java/mchorse/bbs_mod/network/ServerNetwork.java");

    private ActionManagerCompatibilityTest()
    {}

    public static void main(String[] args)
    {
        verifySourceWiring();

        if (args.length == 0 || !"source-only".equals(args[0]))
        {
            verifyRuntimeDescriptors();
        }

        System.out.println("ActionManagerCompatibilityTest passed");
    }

    public static void runAll()
    {
        verifySourceWiring();
        verifyRuntimeDescriptors();
    }

    private static void verifyRuntimeDescriptors()
    {
        try
        {
            ClassLoader loader = ActionManagerCompatibilityTest.class.getClassLoader();
            Class<?> managerClass = Class.forName("mchorse.bbs_mod.actions.ActionManager", false, loader);
            Class<?> filmClass = Class.forName("mchorse.bbs_mod.film.Film", false, loader);
            Class<?> playerClass = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
            Method legacy = managerClass.getMethod(
                "startRecording",
                filmClass,
                playerClass,
                int.class,
                int.class,
                int.class
            );
            Method transactional = managerClass.getMethod(
                "tryStartRecording",
                filmClass,
                playerClass,
                int.class,
                int.class,
                int.class
            );

            check(legacy.getReturnType() == void.class,
                "legacy ActionManager.startRecording JVM descriptor changed");
            check(transactional.getReturnType() == boolean.class,
                "transactional ActionManager.tryStartRecording result changed");

            Object manager = managerClass.getConstructor().newInstance();
            Object[] rejectedRequest = {null, null, 0, 0, 0};

            check(legacy.invoke(manager, rejectedRequest) == null,
                "legacy void recording wrapper returned a value");
            check(Boolean.FALSE.equals(transactional.invoke(manager, rejectedRequest)),
                "transactional recording start accepted an invalid legacy request");
        }
        catch (InvocationTargetException e)
        {
            throw new AssertionError("legacy ActionManager recording wrapper threw", e.getCause());
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            throw new AssertionError("ActionManager recording compatibility descriptor changed", e);
        }
    }

    private static void verifySourceWiring()
    {
        try
        {
            Path root = findProjectRoot();
            String manager = Files.readString(root.resolve(ACTION_MANAGER));
            String network = Files.readString(root.resolve(SERVER_NETWORK));
            String tick = section(manager, "public void tick()", "/* Actions playback */");

            check(manager.contains(
                    "public void startRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)"),
                "legacy void recording entry is missing");
            check(manager.contains(
                    "public boolean tryStartRecording(Film film, ServerPlayer entity, int tick, int countdown, int replayId)"),
                "transactional recording entry is missing");
            check(manager.contains("this.tryStartRecording(film, entity, tick, countdown, replayId)"),
                "legacy recording entry does not delegate to the transaction owner");
            check(network.contains("actions.tryStartRecording(film, player, tick, countdown, replayId)"),
                "server recording admission ignores the playback cursor or transactional result");
            check(!network.contains("actions.startRecording(film, player, 0, countdown, replayId)"),
                "server recording admission calls the legacy void wrapper");
            check(manager.contains("new ActionRecorder(film, entity, 0, countdown)"),
                "recorded action ticks no longer remain relative to the merge cursor");
            check(!manager.contains("catch (RuntimeException e)"),
                "an ActionManager runtime boundary still lets addon LinkageError escape");
            assertOrdered(tick,
                "shouldStop = player.tick()",
                "catch (RuntimeException | LinkageError e)",
                "player.requestForcedStop()",
                "shouldStop = true",
                "this.tryTeardown(player, \"natural\")");
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError("could not inspect ActionManager compatibility wiring", e);
        }
    }

    private static String section(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start, "could not locate production source section: " + startMarker);

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int cursor = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, cursor + 1);

            check(next >= 0, "missing or out-of-order production marker: " + marker);
            cursor = next;
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        for (int i = 0; i < 8 && current != null; i += 1)
        {
            if (Files.isRegularFile(current.resolve(ACTION_MANAGER)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate project root");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
