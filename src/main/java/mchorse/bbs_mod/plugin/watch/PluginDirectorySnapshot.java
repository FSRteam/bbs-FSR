package mchorse.bbs_mod.plugin.watch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable observation of the top-level plugin directory.
 */
public final class PluginDirectorySnapshot
{
    private final Path root;
    private final Map<Path, PluginArtifactFingerprint> artifacts;
    private final Set<Path> uncertainArtifacts;

    public PluginDirectorySnapshot(
        Path root,
        Map<Path, PluginArtifactFingerprint> artifacts,
        Set<Path> uncertainArtifacts
    )
    {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();

        List<Path> sortedArtifacts = new ArrayList<>(artifacts.keySet());
        Map<Path, PluginArtifactFingerprint> artifactCopy = new LinkedHashMap<>();

        sortedArtifacts.sort(PluginDirectorySnapshot::comparePaths);

        for (Path relative : sortedArtifacts)
        {
            Path safeRelative = requireRelativeArtifact(relative);

            artifactCopy.put(safeRelative, Objects.requireNonNull(artifacts.get(relative), "fingerprint"));
        }

        List<Path> sortedUncertain = new ArrayList<>(uncertainArtifacts);
        Set<Path> uncertainCopy = new LinkedHashSet<>();

        sortedUncertain.sort(PluginDirectorySnapshot::comparePaths);

        for (Path relative : sortedUncertain)
        {
            Path safeRelative = requireRelativeArtifact(relative);

            if (artifactCopy.containsKey(safeRelative))
            {
                throw new IllegalArgumentException("Artifact cannot be both stable and uncertain: " + safeRelative);
            }

            uncertainCopy.add(safeRelative);
        }

        this.artifacts = Collections.unmodifiableMap(artifactCopy);
        this.uncertainArtifacts = Collections.unmodifiableSet(uncertainCopy);
    }

    public static PluginDirectorySnapshot empty(Path root)
    {
        return new PluginDirectorySnapshot(root, Map.of(), Set.of());
    }

    public Path root()
    {
        return this.root;
    }

    public Map<Path, PluginArtifactFingerprint> artifacts()
    {
        return this.artifacts;
    }

    public Set<Path> uncertainArtifacts()
    {
        return this.uncertainArtifacts;
    }

    public Path resolve(Path relativeArtifact)
    {
        return this.root.resolve(requireRelativeArtifact(relativeArtifact)).normalize();
    }

    private static Path requireRelativeArtifact(Path path)
    {
        Objects.requireNonNull(path, "path");

        Path relative = path.normalize();

        if (relative.isAbsolute() || relative.getNameCount() != 1 || relative.toString().isBlank())
        {
            throw new IllegalArgumentException("Artifact path must be one top-level filename: " + path);
        }

        return relative;
    }

    private static int comparePaths(Path first, Path second)
    {
        return first.toString().compareTo(second.toString());
    }
}
