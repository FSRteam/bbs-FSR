package mchorse.bbs_mod.plugin.artifact;

import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginManifest;

import java.nio.file.Path;
import java.util.Objects;

/** Content-addressed copy used by generation loaders; the source JAR is never opened by runtime loaders. */
public record PluginShadowArtifact(String pluginId, String sha256, Path path, long sizeBytes,
                                   BBSPluginManifest manifest, BBSPluginDescriptor descriptor)
{
    public PluginShadowArtifact
    {
        pluginId = requireText(pluginId, "pluginId");
        sha256 = requireHash(sha256);
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");

        if (sizeBytes <= 0)
        {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }

    private static String requireText(String value, String name)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(name + " is blank");
        }

        return value;
    }

    private static String requireHash(String value)
    {
        if (!PluginArtifactNames.isSha256(value))
        {
            throw new IllegalArgumentException("sha256 must be a lowercase 64-character hex digest");
        }

        return value;
    }
}
