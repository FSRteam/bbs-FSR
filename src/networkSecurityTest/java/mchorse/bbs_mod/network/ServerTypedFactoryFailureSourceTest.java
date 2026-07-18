package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for s2/s4 registered factory failure isolation. */
final class ServerTypedFactoryFailureSourceTest
{
    private static final Path SOURCE = Path.of(
        "src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"
    );

    private ServerTypedFactoryFailureSourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("ServerTypedFactoryFailureSourceTest passed");
    }

    static void runAll()
    {
        try
        {
            String source = Files.readString(findProjectRoot().resolve(SOURCE));

            checkGunFactoryBoundary(source);
            checkFilmSaveFactoryBoundary(source);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect server typed factory boundaries", e);
        }
    }

    private static void checkGunFactoryBoundary(String source)
    {
        int handlerStart = source.indexOf("private static void handleModelBlockTransformsPacket");
        int handlerEnd = source.indexOf("private static void updateModelBlockStackData", handlerStart);

        check(handlerStart >= 0 && handlerEnd > handlerStart, "could not locate the s2 handler");

        String handler = compact(source.substring(handlerStart, handlerEnd));

        assertOrdered(
            "s2",
            handler,
            "server.execute(",
            "isCurrentConnection(server, player)",
            "PermissionUtils.arePanelsAllowed(server, player)",
            "stack.getItem() == BBSMod.GUN_ITEM.get()",
            "try { GunProperties properties = GunPropertiesPolicy.parseAllowed(data)",
            "GunPropertiesPolicy.isMutationAllowed(",
            "CustomData.update(",
            "catch (RuntimeException | LinkageError factoryError)",
            "reason=typed_factory_failed",
            "return;"
        );
    }

    private static void checkFilmSaveFactoryBoundary(String source)
    {
        int handlerStart = source.indexOf("private static void handleManagerDataPacket");
        int handlerEnd = source.indexOf("private static void handleActionRecording", handlerStart);

        check(handlerStart >= 0 && handlerEnd > handlerStart, "could not locate the s4 handler");

        String handler = compact(source.substring(handlerStart, handlerEnd));

        assertOrdered(
            "s4 SAVE",
            handler,
            "op == RepositoryOperation.SAVE",
            "FilmActionAuthorityPolicy.requiresAdministrator(map) && !administrator",
            "try { candidate = new Film()",
            "candidate.fromData(map)",
            "catch (RuntimeException | LinkageError factoryError)",
            "reason=film_factory_failed",
            "return;",
            "FilmActionAuthorityPolicy.requiresAdministrator(candidate, map)",
            "films.save(id, map)"
        );
        check(handler.lastIndexOf("catch (Exception | LinkageError e)") > handler.indexOf("films.save(id, map)"),
            "s4 repository response serialization no longer contains addon linkage failures");
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", " ");
    }

    private static void assertOrdered(String scope, String source, String... markers)
    {
        int index = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, index + 1);

            check(next >= 0, "missing " + scope + " marker: " + marker);
            check(next > index, scope + " marker is out of order: " + marker);
            index = next;
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        for (int i = 0; i < 8 && current != null; i += 1)
        {
            if (Files.isRegularFile(current.resolve(SOURCE)))
            {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate project root for " + SOURCE);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
