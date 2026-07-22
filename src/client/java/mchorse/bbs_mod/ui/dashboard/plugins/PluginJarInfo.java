package mchorse.bbs_mod.ui.dashboard.plugins;

import mchorse.bbs_mod.api.plugin.BBSPluginManifest;
import mchorse.bbs_mod.plugin.artifact.PluginManifestDecoder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Read-only presentation metadata extracted from a plugin JAR: the manifest,
 * plus optional icon and changelog entries used by the plugin detail panel.
 */
public final class PluginJarInfo
{
    public static final String ICON_PATH = "META-INF/bbs-plugin-icon.png";
    public static final String CHANGELOG_PATH = "META-INF/bbs-plugin-changelog.md";

    private static final long MAX_ICON_BYTES = 1024 * 1024;
    private static final long MAX_CHANGELOG_BYTES = 256 * 1024;

    public final Path source;
    public final BBSPluginManifest manifest;
    public final byte[] iconBytes;
    public final String changelog;

    private PluginJarInfo(Path source, BBSPluginManifest manifest, byte[] iconBytes, String changelog)
    {
        this.source = source;
        this.manifest = manifest;
        this.iconBytes = iconBytes;
        this.changelog = changelog;
    }

    /** Returns null when the file is not a readable plugin JAR with a valid manifest. */
    public static PluginJarInfo read(Path jarPath)
    {
        try (JarFile jar = new JarFile(jarPath.toFile()))
        {
            JarEntry manifestEntry = jar.getJarEntry(BBSPluginManifest.PATH);

            if (manifestEntry == null)
            {
                return null;
            }

            byte[] manifestBytes = readEntry(jar, manifestEntry, 256 * 1024);
            BBSPluginManifest manifest = new PluginManifestDecoder().decode(manifestBytes);
            byte[] icon = readOptional(jar, ICON_PATH, MAX_ICON_BYTES);
            byte[] changelogBytes = readOptional(jar, CHANGELOG_PATH, MAX_CHANGELOG_BYTES);
            String changelog = changelogBytes == null ? null : new String(changelogBytes, StandardCharsets.UTF_8);

            return new PluginJarInfo(jarPath, manifest, icon, changelog);
        }
        catch (Throwable error)
        {
            return null;
        }
    }

    private static byte[] readOptional(JarFile jar, String path, long limit)
    {
        JarEntry entry = jar.getJarEntry(path);

        if (entry == null)
        {
            return null;
        }

        try
        {
            return readEntry(jar, entry, limit);
        }
        catch (Throwable error)
        {
            return null;
        }
    }

    private static byte[] readEntry(JarFile jar, JarEntry entry, long limit) throws Exception
    {
        if (entry.getSize() > limit)
        {
            throw new IllegalStateException("entry too large: " + entry.getName());
        }

        try (InputStream input = jar.getInputStream(entry))
        {
            byte[] bytes = input.readNBytes((int) limit + 1);

            if (bytes.length > limit)
            {
                throw new IllegalStateException("entry too large: " + entry.getName());
            }

            return bytes;
        }
    }
}
