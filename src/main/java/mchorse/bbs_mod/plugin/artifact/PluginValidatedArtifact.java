package mchorse.bbs_mod.plugin.artifact;

import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginManifest;

import java.nio.file.Path;
import java.util.Objects;

/** A source artifact that passed all Phase 1 checks and is safe to shadow-copy. */
public record PluginValidatedArtifact(Path source, BBSPluginManifest manifest,
                                      BBSPluginDescriptor descriptor, String sha256,
                                      long sizeBytes, int entryCount)
{
    public PluginValidatedArtifact
    {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        manifest = Objects.requireNonNull(manifest, "manifest");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        sha256 = requireHash(sha256);

        if (sizeBytes <= 0 || entryCount <= 0)
        {
            throw new IllegalArgumentException("artifact size and entry count must be positive");
        }
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
