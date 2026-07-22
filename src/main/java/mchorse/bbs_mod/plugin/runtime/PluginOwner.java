package mchorse.bbs_mod.plugin.runtime;

import java.util.Objects;

/**
 * Stable identity for one hot-plugin generation.
 *
 * <p>The generation is part of the identity deliberately.  An owner from an
 * older generation must never compare equal to its replacement, even when the
 * plugin id is unchanged.</p>
 */
public record PluginOwner(String pluginId, long generation)
{
    public PluginOwner
    {
        if (pluginId == null || pluginId.isBlank())
        {
            throw new IllegalArgumentException("Plugin id is required");
        }

        if (pluginId.indexOf('\0') >= 0)
        {
            throw new IllegalArgumentException("Plugin id contains a NUL character");
        }

        if (generation < 1L)
        {
            throw new IllegalArgumentException("Plugin generation must be positive");
        }

        pluginId = pluginId.trim();
    }

    public boolean samePlugin(String otherPluginId)
    {
        return this.pluginId.equals(otherPluginId);
    }

    public boolean isNewerThan(PluginOwner other)
    {
        Objects.requireNonNull(other, "other");
        return this.pluginId.equals(other.pluginId) && this.generation > other.generation;
    }

    @Override
    public String toString()
    {
        return this.pluginId + "@" + this.generation;
    }
}
