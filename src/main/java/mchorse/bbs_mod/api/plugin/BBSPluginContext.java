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

    /** Register a host-owned resource for reverse-order, idempotent cleanup. */
    <T extends AutoCloseable> T own(T resource);

    default String pluginId()
    {
        return descriptor().id();
    }
}
