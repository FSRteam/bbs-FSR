package mchorse.bbs_mod.plugin.watch;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Full-scan source of truth used after startup, overflow, invalid keys, and
 * manual or periodic rescans.
 */
public final class PluginDirectoryReconciler
{
    private PluginDirectoryReconciler()
    {}

    public static PluginDirectorySnapshot scan(Path root) throws IOException
    {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();

        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS))
        {
            return PluginDirectorySnapshot.empty(normalizedRoot);
        }

        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedRoot))
        {
            throw new IOException("Plugin directory must be a non-symlink directory: " + normalizedRoot);
        }

        Map<Path, PluginArtifactFingerprint> artifacts = new LinkedHashMap<>();
        Set<Path> uncertain = new LinkedHashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(normalizedRoot))
        {
            for (Path artifact : stream)
            {
                if (!isPluginArtifact(artifact))
                {
                    continue;
                }

                Path relative = artifact.getFileName();

                try
                {
                    artifacts.put(relative, PluginArtifactFingerprint.capture(artifact));
                }
                catch (NoSuchFileException e)
                {
                    /* The next event or reconciliation observes the final state. */
                }
                catch (IOException e)
                {
                    uncertain.add(relative);
                }
            }
        }

        return new PluginDirectorySnapshot(normalizedRoot, artifacts, uncertain);
    }

    public static List<PluginWatchIntent> reconcile(
        PluginDirectorySnapshot known,
        PluginDirectorySnapshot observed,
        PluginWatchIntent.Trigger trigger,
        PluginApplyMode applyMode
    )
    {
        Objects.requireNonNull(known, "known");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(applyMode, "applyMode");

        if (!known.root().equals(observed.root()))
        {
            throw new IllegalArgumentException("Directory snapshots have different roots");
        }

        List<PluginWatchIntent> intents = new ArrayList<>();

        for (Map.Entry<Path, PluginArtifactFingerprint> entry : observed.artifacts().entrySet())
        {
            PluginArtifactFingerprint previous = known.artifacts().get(entry.getKey());

            if (previous == null || !previous.sameContent(entry.getValue()))
            {
                intents.add(PluginWatchIntent.upsert(
                    observed.resolve(entry.getKey()),
                    entry.getValue(),
                    trigger,
                    applyMode
                ));
            }
        }

        for (Path previous : known.artifacts().keySet())
        {
            if (!observed.artifacts().containsKey(previous) && !observed.uncertainArtifacts().contains(previous))
            {
                intents.add(PluginWatchIntent.delete(observed.resolve(previous), trigger, applyMode));
            }
        }

        return List.copyOf(intents);
    }

    public static boolean isPluginArtifact(Path path)
    {
        Path name = Objects.requireNonNull(path, "path").getFileName();

        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }
}
