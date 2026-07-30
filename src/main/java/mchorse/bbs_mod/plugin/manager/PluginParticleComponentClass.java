package mchorse.bbs_mod.plugin.manager;

import java.util.Objects;

/**
 * One API 2.0 particle component class name paired with the ClassLoader that
 * must resolve it.
 *
 * <p>{@code classLoader} is {@code null} for entries that resolve against the
 * caller's own classloader (the Addon v2 bridge, whose behavior predates hot
 * plugins and must stay unchanged). Hot-plugin entries carry their
 * generation's isolated classloader so a component class defined only inside
 * a {@code PluginGenerationClassLoader} can still be resolved by name from
 * {@code ParticleParser}, which otherwise only sees its own host loader.</p>
 */
public record PluginParticleComponentClass(String className, ClassLoader classLoader)
{
    public PluginParticleComponentClass
    {
        Objects.requireNonNull(className, "className");
    }
}
