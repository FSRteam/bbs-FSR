package mchorse.bbs_mod.plugin.artifact;

import java.nio.file.Path;
import java.util.Objects;

/** Verified pointer to an existing content-addressed artifact. */
public record PluginArtifactReference(String pluginId, String sha256, Path path, long sizeBytes)
{
    public PluginArtifactReference
    {
        if (pluginId == null || pluginId.isBlank())
        {
            throw new IllegalArgumentException("pluginId is blank");
        }

        if (!PluginArtifactNames.isSha256(sha256))
        {
            throw new IllegalArgumentException("sha256 is invalid");
        }

        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();

        if (sizeBytes <= 0)
        {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}
