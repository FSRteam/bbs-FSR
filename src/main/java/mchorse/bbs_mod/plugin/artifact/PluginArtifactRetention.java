package mchorse.bbs_mod.plugin.artifact;

import java.util.Optional;

/** Current/previous content hashes for one plugin id. */
public record PluginArtifactRetention(String pluginId, String currentHash, String previousHash)
{
    public Optional<String> current()
    {
        return Optional.ofNullable(this.currentHash);
    }

    public Optional<String> previous()
    {
        return Optional.ofNullable(this.previousHash);
    }
}
