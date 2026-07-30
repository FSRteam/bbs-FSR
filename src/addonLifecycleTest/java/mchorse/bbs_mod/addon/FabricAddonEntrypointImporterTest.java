package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.events.Subscribe;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for the optional Connector/Fabric addon bridge. */
public final class FabricAddonEntrypointImporterTest
{
    private FabricAddonEntrypointImporterTest()
    {}

    public static void runAll()
    {
        unavailableAndEmptyDiscoveryAreNoOps();
        discoveredAddonIsImportedAndBridged();
        existingRegistrationKeepsIdentityOwnership();
        brokenEntrypointsAreIsolated();
    }

    private static void unavailableAndEmptyDiscoveryAreNoOps()
    {
        BBSAddonCollector unavailableCollector = new BBSAddonCollector();
        FabricAddonEntrypointImporter.ImportSummary unavailable = new FabricAddonEntrypointImporter(
            (key, type) -> FabricAddonEntrypointImporter.Discovery.unavailable()
        ).importInto(unavailableCollector);

        check(!unavailable.loaderAvailable(), "absent Fabric Loader was reported as available");
        check(unavailable.discovered() == 0 && unavailableCollector.size() == 0,
            "absent Fabric Loader changed the addon collector");

        BBSAddonCollector emptyCollector = new BBSAddonCollector();
        FabricAddonEntrypointImporter.ImportSummary empty = new FabricAddonEntrypointImporter(
            (key, type) -> FabricAddonEntrypointImporter.Discovery.available(List.of())
        ).importInto(emptyCollector);

        check(empty.loaderAvailable() && empty.discovered() == 0 && empty.failed() == 0,
            "empty Fabric entrypoint discovery was not a successful no-op");
        check(emptyCollector.getRegistrationDiagnostics().isEmpty(),
            "empty Fabric entrypoint discovery produced diagnostics");
    }

    private static void discoveredAddonIsImportedAndBridged()
    {
        BBSAddonCollector collector = new BBSAddonCollector();
        TrackingAddon addon = new TrackingAddon();
        FabricAddonEntrypointImporter.Candidate candidate = new FabricAddonEntrypointImporter.Candidate(
            "irlite",
            "fabric-loader:irlite",
            () -> addon
        );
        FabricAddonEntrypointImporter.ImportSummary summary = new FabricAddonEntrypointImporter(
            (key, type) -> FabricAddonEntrypointImporter.Discovery.available(List.of(candidate))
        ).importInto(collector);

        check(summary.loaderAvailable() && summary.discovered() == 1 && summary.imported() == 1,
            "valid Fabric addon was not imported");
        check(collector.getAddonMap().get("irlite") == addon,
            "Fabric provider id did not own the imported addon instance");

        EventBus bus = new EventBus();

        collector.bridgeAndCloseExternalRegistrationWindow(bus);
        bus.post(new ProbeEvent());
        check(addon.events.get() == 1, "imported Fabric addon was not bridged into the BBS event bus");
    }

    private static void existingRegistrationKeepsIdentityOwnership()
    {
        BBSAddonCollector collector = new BBSAddonCollector();
        TrackingAddon winner = new TrackingAddon();
        TrackingAddon rejected = new TrackingAddon();

        check(collector.register("same-id", winner), "native addon fixture was rejected");

        FabricAddonEntrypointImporter.Candidate candidate = new FabricAddonEntrypointImporter.Candidate(
            "same-id",
            "fabric-loader:same-id",
            () -> rejected
        );
        FabricAddonEntrypointImporter.ImportSummary summary = new FabricAddonEntrypointImporter(
            (key, type) -> FabricAddonEntrypointImporter.Discovery.available(List.of(candidate))
        ).importInto(collector);

        check(summary.imported() == 0 && summary.rejected() == 1,
            "duplicate Fabric provider did not report collector rejection");
        check(collector.getAddonMap().get("same-id") == winner,
            "Fabric duplicate replaced the first registered addon");
    }

    private static void brokenEntrypointsAreIsolated()
    {
        BBSAddonCollector collector = new BBSAddonCollector();
        TrackingAddon survivor = new TrackingAddon();
        FabricAddonEntrypointImporter.Candidate linkageFailure = new FabricAddonEntrypointImporter.Candidate(
            "broken-linkage",
            "fabric-loader:broken-linkage",
            () ->
            {
                throw new NoClassDefFoundError("missing optional addon dependency");
            }
        );
        FabricAddonEntrypointImporter.Candidate wrongType = new FabricAddonEntrypointImporter.Candidate(
            "wrong-type",
            "fabric-loader:wrong-type",
            Object::new
        );
        FabricAddonEntrypointImporter.Candidate valid = new FabricAddonEntrypointImporter.Candidate(
            "survivor",
            "fabric-loader:survivor",
            () -> survivor
        );
        FabricAddonEntrypointImporter.DiscoveryFailure metadataFailure =
            new FabricAddonEntrypointImporter.DiscoveryFailure(
                "<unknown>",
                "fabric-loader:container-3",
                new IllegalStateException("missing provider metadata")
            );
        FabricAddonEntrypointImporter.ImportSummary summary = new FabricAddonEntrypointImporter(
            (key, type) -> new FabricAddonEntrypointImporter.Discovery(
                true,
                List.of(linkageFailure, wrongType, valid),
                List.of(metadataFailure)
            )
        ).importInto(collector);

        check(summary.discovered() == 4 && summary.imported() == 1 && summary.failed() == 3,
            "broken Fabric entrypoint accounting was incorrect");
        check(collector.getAddonMap().get("survivor") == survivor,
            "one broken Fabric entrypoint prevented a later valid import");
        check(collector.getRegistrationDiagnostics().stream().anyMatch((diagnostic) ->
                "broken-linkage".equals(diagnostic.addonId())
                    && NoClassDefFoundError.class.getName().equals(diagnostic.errorClass())),
            "broken Fabric entrypoint linkage was not retained in diagnostics");
        check(collector.getRegistrationDiagnostics().stream().anyMatch((diagnostic) ->
                "wrong-type".equals(diagnostic.addonId())
                    && IllegalStateException.class.getName().equals(diagnostic.errorClass())),
            "wrong Fabric entrypoint type was not retained in diagnostics");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    public static final class ProbeEvent
    {}

    public static final class TrackingAddon implements BBSAddonMod
    {
        private final AtomicInteger events = new AtomicInteger();

        @Subscribe
        public void onProbe(ProbeEvent event)
        {
            this.events.incrementAndGet();
        }
    }
}
