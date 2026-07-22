package mchorse.bbs_mod.ui.dashboard.plugins;

import mchorse.bbs_mod.BBSMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copies plugin JARs into the hot plugin directory. Follows the hot-plugin
 * contract: write to a same-directory temporary file, then atomically move it
 * over the final name so the watcher observes a single stable artifact.
 */
public final class PluginInstaller
{
    private PluginInstaller() {}

    public static Path install(PluginJarInfo candidate) throws IOException
    {
        Path directory = BBSMod.getPluginDirectory();

        if (directory == null)
        {
            throw new IOException("hot plugin runtime is not running");
        }

        Files.createDirectories(directory);

        String id = candidate.manifest.id();
        Path target = directory.resolve(sanitize(id + "-" + candidate.manifest.version()) + ".jar");

        /* Retire other artifacts that own the same plugin id so the manager
         * does not reject the new artifact as DUPLICATE_ID. */
        for (Path other : jarsOwnedBy(directory, id))
        {
            if (!other.equals(target))
            {
                Files.deleteIfExists(other);
            }
        }

        Path temporary = Files.createTempFile(directory, ".bbs-install-", ".tmp");

        try
        {
            Files.copy(candidate.source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException error)
        {
            Files.deleteIfExists(temporary);

            throw error;
        }

        return target;
    }

    public static void uninstall(String pluginId) throws IOException
    {
        Path directory = BBSMod.getPluginDirectory();

        if (directory == null)
        {
            return;
        }

        for (Path jar : jarsOwnedBy(directory, pluginId))
        {
            Files.deleteIfExists(jar);
        }
    }

    /** The installed JAR that declares the given plugin id, or null. */
    public static PluginJarInfo findInstalled(String pluginId)
    {
        Path directory = BBSMod.getPluginDirectory();

        if (directory == null || !Files.isDirectory(directory))
        {
            return null;
        }

        for (Path jar : listJars(directory))
        {
            PluginJarInfo info = PluginJarInfo.read(jar);

            if (info != null && info.manifest.id().equals(pluginId))
            {
                return info;
            }
        }

        return null;
    }

    private static List<Path> jarsOwnedBy(Path directory, String pluginId)
    {
        List<Path> owned = new ArrayList<>();

        for (Path jar : listJars(directory))
        {
            PluginJarInfo info = PluginJarInfo.read(jar);

            if (info != null && info.manifest.id().equals(pluginId))
            {
                owned.add(jar);
            }
        }

        return owned;
    }

    private static List<Path> listJars(Path directory)
    {
        List<Path> jars = new ArrayList<>();

        try (var files = Files.list(directory))
        {
            files.filter((path) -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .filter(Files::isRegularFile)
                .forEach(jars::add);
        }
        catch (IOException ignored)
        {}

        return jars;
    }

    private static String sanitize(String name)
    {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
