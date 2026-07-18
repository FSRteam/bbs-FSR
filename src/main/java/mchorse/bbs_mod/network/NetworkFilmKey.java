package mchorse.bbs_mod.network;

import mchorse.bbs_mod.film.FilmManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/** Canonical logical identity for a film contained by the server repository. */
final class NetworkFilmKey
{
    private static final String FILM_EXTENSION = ".dat";

    public static String resolve(FilmManager films, String requestedId)
    {
        if (films == null)
        {
            return null;
        }

        try
        {
            File root = films.getFolder();
            File file = films.getFile(requestedId);

            if (root == null || file == null)
            {
                return null;
            }

            return fromCanonicalPaths(root.getCanonicalFile().toPath(), file.getCanonicalFile().toPath());
        }
        /* Repository suppliers and custom managers may fail with an unchecked
         * exception. A network/command identity boundary must reject that input
         * instead of letting a partial play/stop operation escape upstream. */
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    static String fromCanonicalPaths(Path root, Path file)
    {
        if (root == null || file == null)
        {
            return null;
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();

        if (!normalizedFile.startsWith(normalizedRoot) || normalizedFile.equals(normalizedRoot))
        {
            return null;
        }

        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');

        if (relative.length() <= FILM_EXTENSION.length()
            || !relative.regionMatches(true, relative.length() - FILM_EXTENSION.length(), FILM_EXTENSION, 0, FILM_EXTENSION.length()))
        {
            return null;
        }

        String key = relative.substring(0, relative.length() - FILM_EXTENSION.length());

        return key.isBlank() ? null : key;
    }
}
