package mchorse.bbs_mod.network;

import mchorse.bbs_mod.film.FilmManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

public final class NetworkFilmKeyTest
{
    private static final String CANONICAL_ID = "foo/bar";
    private static final String DIMENSION = "minecraft:overworld";
    private static final Method CANONICAL_FILM_ID = findCanonicalFilmId();

    public static void runAll()
    {
        Path root = null;

        try
        {
            root = Files.createTempDirectory("bbs-network-film-key-");

            Path film = root.resolve("foo/bar.dat");

            Files.createDirectories(film.getParent());
            Files.createFile(film);

            FilmManager films = new FilmManager(root::toFile);

            testCanonicalNestedId(films);
            testSeparatorAliases(films);
            testWindowsCaseAlias(root, film, films);
            testInternalSymlinkAlias(root, film, films);
            testRepositorySupplierFailure();
        }
        catch (IOException e)
        {
            throw new AssertionError("film-key fixture setup failed", e);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testCanonicalNestedId(FilmManager films)
    {
        check(CANONICAL_ID.equals(NetworkFilmKey.resolve(films, CANONICAL_ID)), "a canonical nested film id was changed");
        check(CANONICAL_ID.equals(canonicalFilmId(films, CANONICAL_ID)), "a canonical nested film id was rejected at the server entry boundary");
    }

    private static void testSeparatorAliases(FilmManager films)
    {
        assertAliasRejected(films, "foo//bar", "a repeated-separator alias was admitted");
        assertAliasRejected(films, "foo\\bar", "a backslash alias was admitted");
    }

    private static void testWindowsCaseAlias(Path root, Path film, FilmManager films)
    {
        String alias = "FOO/BAR";
        FilmManager caseAwareManager = Files.exists(root.resolve("FOO/BAR.dat"))
            ? films
            : new RedirectingFilmManager(root, alias, film.toFile());

        assertAliasRejected(caseAwareManager, alias, "a Windows case alias was admitted");
    }

    private static void testInternalSymlinkAlias(Path root, Path film, FilmManager films)
    {
        String alias = "linked";
        Path link = root.resolve(alias + ".dat");
        FilmManager symlinkAwareManager = films;

        try
        {
            Files.createSymbolicLink(link, root.relativize(film));
        }
        catch (IOException | UnsupportedOperationException | SecurityException e)
        {
            /* Some Windows hosts deny symbolic-link creation. Preserve the
             * canonical-target behavior in the regression on those hosts. */
            symlinkAwareManager = new RedirectingFilmManager(root, alias, film.toFile());
        }

        assertAliasRejected(symlinkAwareManager, alias, "an internal symbolic-link alias was admitted");
    }

    private static void testRepositorySupplierFailure()
    {
        FilmManager films = new FilmManager(() ->
        {
            throw new IllegalStateException("repository unavailable");
        });

        check(NetworkFilmKey.resolve(films, CANONICAL_ID) == null, "a throwing repository supplier escaped resolve");
        check(canonicalFilmId(films, CANONICAL_ID) == null, "a throwing repository supplier escaped exact entry validation");
    }

    private static void assertAliasRejected(FilmManager films, String alias, String message)
    {
        check(CANONICAL_ID.equals(NetworkFilmKey.resolve(films, alias)), message + " before entry validation");

        NetworkMutationSessions sessions = new NetworkMutationSessions();
        UUID firstOwner = UUID.randomUUID();
        UUID aliasOwner = UUID.randomUUID();
        Object firstConnection = new Object();
        Object aliasConnection = new Object();

        sessions.claimFilm(CANONICAL_ID, firstOwner, firstConnection, DIMENSION);

        String admittedId = canonicalFilmId(films, alias);

        if (admittedId != null)
        {
            sessions.claimFilm(admittedId, aliasOwner, aliasConnection, DIMENSION);
        }

        check(admittedId == null, message);
        check(sessions.ownsFilm(CANONICAL_ID, firstOwner, firstConnection, DIMENSION), message + " and replaced the canonical owner");
        check(!sessions.ownsFilm(CANONICAL_ID, aliasOwner, aliasConnection, DIMENSION), message + " and obtained the canonical session");
        check(!sessions.ownsFilm(alias, aliasOwner, aliasConnection, DIMENSION), message + " and obtained a second alias session");
    }

    private static Method findCanonicalFilmId()
    {
        try
        {
            Method method = ServerNetwork.class.getDeclaredMethod("canonicalFilmId", FilmManager.class, String.class);

            method.setAccessible(true);

            return method;
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("server canonical film boundary is unavailable", e);
        }
    }

    private static String canonicalFilmId(FilmManager films, String requestedId)
    {
        try
        {
            return (String) CANONICAL_FILM_ID.invoke(null, films, requestedId);
        }
        catch (IllegalAccessException e)
        {
            throw new AssertionError("server canonical film boundary is inaccessible", e);
        }
        catch (InvocationTargetException e)
        {
            throw new AssertionError("server canonical film boundary failed", e.getCause());
        }
    }

    private static void deleteTree(Path root)
    {
        if (root == null)
        {
            return;
        }

        try (Stream<Path> paths = Files.walk(root))
        {
            paths.sorted(Comparator.reverseOrder()).forEach((path) ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException e)
                {
                    throw new DeleteFailure(e);
                }
            });
        }
        catch (IOException | DeleteFailure e)
        {
            throw new AssertionError("film-key fixture cleanup failed", e);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class RedirectingFilmManager extends FilmManager
    {
        private final String alias;
        private final File target;

        private RedirectingFilmManager(Path root, String alias, File target)
        {
            super(root::toFile);

            this.alias = alias;
            this.target = target;
        }

        @Override
        public File getFile(String id)
        {
            return this.alias.equals(id) ? this.target : super.getFile(id);
        }
    }

    private static final class DeleteFailure extends RuntimeException
    {
        private DeleteFailure(IOException cause)
        {
            super(cause);
        }
    }
}
