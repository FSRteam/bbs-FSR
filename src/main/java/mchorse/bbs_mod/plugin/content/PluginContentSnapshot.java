package mchorse.bbs_mod.plugin.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable content prepared for one plugin generation.
 *
 * <p>The snapshot deliberately contains only host/JDK value types. It can be
 * retained by resource and UI projections without retaining a plugin class,
 * instance, or class loader.</p>
 */
public final class PluginContentSnapshot
{
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final String pluginId;
    private final String version;
    private final long generation;
    private final String artifactHash;
    private final Map<String, PluginContentEntry> entries;
    private final long totalBytes;

    private PluginContentSnapshot(Builder builder)
    {
        this.pluginId = requireText(builder.pluginId, "pluginId");
        this.version = requireText(builder.version, "version");

        if (builder.generation <= 0)
        {
            throw new IllegalArgumentException("generation must be positive");
        }

        this.generation = builder.generation;
        this.artifactHash = requireArtifactHash(builder.artifactHash);

        List<PluginContentEntry> sorted = new ArrayList<>(builder.entries.values());

        sorted.sort((first, second) -> first.path().compareTo(second.path()));

        Map<String, PluginContentEntry> entries = new LinkedHashMap<>();
        long totalBytes = 0;

        for (PluginContentEntry entry : sorted)
        {
            entries.put(entry.path(), entry);
            totalBytes = Math.addExact(totalBytes, entry.size());
        }

        this.entries = Collections.unmodifiableMap(entries);
        this.totalBytes = totalBytes;
    }

    public static Builder builder(String pluginId, String version, long generation, String artifactHash)
    {
        return new Builder(pluginId, version, generation, artifactHash);
    }

    public String pluginId()
    {
        return this.pluginId;
    }

    public String version()
    {
        return this.version;
    }

    public long generation()
    {
        return this.generation;
    }

    public String artifactHash()
    {
        return this.artifactHash;
    }

    public Map<String, PluginContentEntry> entries()
    {
        return this.entries;
    }

    public Optional<PluginContentEntry> entry(String path)
    {
        return Optional.ofNullable(this.entries.get(PluginContentEntry.validatePath(path)));
    }

    public List<PluginContentEntry> entries(PluginContentKind kind)
    {
        Objects.requireNonNull(kind, "kind");

        List<PluginContentEntry> matches = new ArrayList<>();

        for (PluginContentEntry entry : this.entries.values())
        {
            if (entry.kind() == kind)
            {
                matches.add(entry);
            }
        }

        return List.copyOf(matches);
    }

    public long totalBytes()
    {
        return this.totalBytes;
    }

    private static String requireText(String value, String field)
    {
        Objects.requireNonNull(value, field);

        if (value.isBlank())
        {
            throw new IllegalArgumentException(field + " must not be blank");
        }

        return value;
    }

    private static String requireArtifactHash(String value)
    {
        String hash = requireText(value, "artifactHash").toLowerCase(java.util.Locale.ROOT);

        if (!SHA_256.matcher(hash).matches())
        {
            throw new IllegalArgumentException("artifactHash must be a SHA-256 hex string");
        }

        return hash;
    }

    public static final class Builder
    {
        private final String pluginId;
        private final String version;
        private final long generation;
        private final String artifactHash;
        private final Map<String, PluginContentEntry> entries = new LinkedHashMap<>();

        private Builder(String pluginId, String version, long generation, String artifactHash)
        {
            this.pluginId = pluginId;
            this.version = version;
            this.generation = generation;
            this.artifactHash = artifactHash;
        }

        public Builder put(PluginContentKind kind, String path, byte[] content)
        {
            return this.put(new PluginContentEntry(kind, path, content));
        }

        public Builder put(PluginContentEntry entry)
        {
            Objects.requireNonNull(entry, "entry");

            if (this.entries.putIfAbsent(entry.path(), entry) != null)
            {
                throw new IllegalArgumentException("Duplicate content path: " + entry.path());
            }

            return this;
        }

        public Builder putAll(Collection<PluginContentEntry> entries)
        {
            Objects.requireNonNull(entries, "entries");

            for (PluginContentEntry entry : entries)
            {
                this.put(entry);
            }

            return this;
        }

        public PluginContentSnapshot build()
        {
            return new PluginContentSnapshot(this);
        }
    }
}
