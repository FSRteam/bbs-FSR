package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.api.plugin.BBSPlugin;
import mchorse.bbs_mod.api.plugin.BBSPluginContext;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginDiagnosticSink;
import mchorse.bbs_mod.api.plugin.BBSPluginEventRegistry;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginLease;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class PluginGenerationContext implements BBSPluginContext
{
    private final BBSPluginDescriptor descriptor;
    private final PluginOwner owner;
    private final Path dataDirectory;
    private final BBSPluginDiagnosticSink diagnostics;
    private final PluginContributionLedger ledger;
    private final ClassLoader generationLoader;
    private final Map<PluginEventRoute, Consumer<Object>> eventRoutes = new LinkedHashMap<>();
    private final Map<Class<?>, Integer> eventOrdinals = new LinkedHashMap<>();
    private final BBSPluginEventRegistry events = this::subscribe;
    private boolean eventRegistrationOpen = true;

    PluginGenerationContext(
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        Path dataDirectory,
        BBSPluginDiagnosticSink diagnostics,
        PluginContributionLedger ledger,
        ClassLoader generationLoader
    )
    {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.generationLoader = generationLoader;
    }

    @Override
    public BBSPluginDescriptor descriptor()
    {
        return this.descriptor;
    }

    @Override
    public long generation()
    {
        return this.owner.generation();
    }

    @Override
    public Path dataDirectory()
    {
        return this.dataDirectory;
    }

    @Override
    public BBSPluginDiagnosticSink diagnostics()
    {
        return this.diagnostics;
    }

    @Override
    public BBSPluginEventRegistry events()
    {
        return this.events;
    }

    @Override
    public <T extends AutoCloseable> T own(T resource)
    {
        Objects.requireNonNull(resource, "resource");
        this.ledger.own(resource);

        return resource;
    }

    synchronized Map<PluginEventRoute, Consumer<Object>> sealEvents()
    {
        this.eventRegistrationOpen = false;

        return Map.copyOf(this.eventRoutes);
    }

    private synchronized <E> AutoCloseable subscribe(Class<E> eventType, Consumer<? super E> callback)
    {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(callback, "callback");

        if (!this.eventRegistrationOpen)
        {
            throw new IllegalStateException("event registration is closed for " + this.owner);
        }

        ClassLoader eventLoader = eventType.getClassLoader();
        ClassLoader hostLoader = BBSPlugin.class.getClassLoader();

        if (eventLoader == this.generationLoader || (eventLoader != null && eventLoader != hostLoader)
            || !eventType.getName().startsWith("mchorse.bbs_mod."))
        {
            throw new IllegalArgumentException("hot plugins may subscribe only to host-owned BBS event types");
        }

        int ordinal = this.eventOrdinals.getOrDefault(eventType, 0);
        this.eventOrdinals.put(eventType, ordinal + 1);
        PluginEventRoute route = new PluginEventRoute(eventType, ordinal);
        Consumer<Object> erased = (event) -> callback.accept(eventType.cast(event));
        this.eventRoutes.put(route, erased);

        PluginLease lease = this.ledger.own(
            "event " + eventType.getName() + "#" + ordinal,
            () ->
            {
                synchronized (PluginGenerationContext.this)
                {
                    PluginGenerationContext.this.eventRoutes.remove(route);
                }
            }
        );

        return lease;
    }
}
