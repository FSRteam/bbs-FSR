package mchorse.bbs_mod.api.plugin;

import java.nio.file.Path;

/**
 * Host-owned context for one plugin generation.
 *
 * <p>The interface intentionally contains no NeoForge bus, registry, loader,
 * or manager types. Host capabilities can be added as narrow owner-aware
 * facades without making those implementation details part of the SPI.</p>
 */
public interface BBSPluginContext
{
    BBSPluginDescriptor descriptor();

    long generation();

    Path dataDirectory();

    BBSPluginDiagnosticSink diagnostics();

    BBSPluginEventRegistry events();

    BBSPluginFormRegistry forms();

    BBSPluginClipRegistry clips();

    BBSPluginParticleRegistry particles();

    /** Resolve an optional physical-side context without linking client classes on a server. */
    default <T> T extension(Class<T> extensionType)
    {
        throw new IllegalStateException("plugin context extension is unavailable: " + extensionType.getName());
    }

    /** Register a host-owned resource for reverse-order, idempotent cleanup. */
    <T extends AutoCloseable> T own(T resource);

    default String pluginId()
    {
        return descriptor().id();
    }
}
