package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.addon.BBSAddonCollector;
import mchorse.bbs_mod.addon.BBSAddonIdentityRegistry;
import mchorse.bbs_mod.addon.FabricAddonEntrypointImporterTest;
import mchorse.bbs_mod.api.BBSApiVersion;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonContext;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.addon.BBSAddonRegistrationContext;
import mchorse.bbs_mod.api.addon.BBSAddonSetupContext;
import mchorse.bbs_mod.api.addon.BBSAddonState;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnostics;
import mchorse.bbs_mod.api.network.BBSNetworkRegistry;
import mchorse.bbs_mod.api.particles.BBSParticleRegistry;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.api.registry.BBSRegistrationStatus;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.network.compat.AddonPayloadBroker;
import mchorse.bbs_mod.network.compat.AddonBrokerDiagnosticLimiterTest;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import mchorse.bbs_mod.test.ExpectedErrorLogCapture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Executable regressions for Addon v1/API 2.0 identity and phase windows. */
public final class BBSAddonLifecycleTest
{
    private BBSAddonLifecycleTest()
    {}

    public static void main(String[] args)
    {
        try (ExpectedErrorLogCapture capture = ExpectedErrorLogCapture.install(
            "addon lifecycle", 9, (event) ->
            {
                String message = event.getMessage().getFormattedMessage();
                Throwable error = event.getThrown();
                String cause = error == null ? "" : error.getMessage();
                boolean expectedMessage = message.startsWith("[bbs-addon-api2] failed ")
                    || message.startsWith("[bbs-addon] failed ")
                    || message.startsWith("[BBS-SEM] topic=net.addon_broker")
                        && message.contains(" result=error ");
                return expectedMessage && error instanceof NoClassDefFoundError
                    && (cause.startsWith("missing ")
                        || cause.startsWith("bridge failure ")
                        || cause.startsWith("deterministic "));
            }, "bbs-addon-api2", BBSAddonCollector.class.getName(), "bbs-network"))
        {
            duplicateFailureCannotReplaceWinner();
            retainedRegistrationFacadeIsClosed();
            linkageErrorsAreIsolated();
            clientDiagnosticsAttachWithoutChangingWinner();
            crossProtocolFirstWins();
            failedLegacyAttemptDoesNotFailWinner();
            legacySupplierDiagnosticsAndAtomicBridge();
            exactApiVersionAdmission();
            frozenCompatibilityConstantsStayStable();
            brokerSingleFrameLimitsStayWithinFrozenPayloads();
            clientBrokerDeliveryRequiresDispatcher();
            AddonBrokerDiagnosticLimiterTest.run();
            FabricAddonEntrypointImporterTest.runAll();
            capture.assertExpectedErrors();
        }

        System.out.println("BBSAddonLifecycleTest: all tests passed; captured 9 expected failure diagnostics");
    }

    private static void duplicateFailureCannotReplaceWinner()
    {
        BBSAddonManager manager = new BBSAddonManager(() -> null);
        BBSAddonDescriptor descriptor = descriptor("duplicate-winner");
        TrackingAddon winner = new TrackingAddon(descriptor);
        AtomicInteger rejectedSupplierCalls = new AtomicInteger();

        check(manager.registerExternal(descriptor, () -> winner), "first API 2.0 addon was rejected");
        check(!manager.registerExternal(descriptor, () ->
        {
            rejectedSupplierCalls.incrementAndGet();
            throw new NoClassDefFoundError("duplicate implementation must not load");
        }), "duplicate API 2.0 addon was accepted");

        check(rejectedSupplierCalls.get() == 0, "duplicate supplier ran before first-wins rejection");
        BBSAddonDiagnostics diagnostics = diagnostic(manager, descriptor.addonId());

        check(diagnostics.state() == BBSAddonState.ACCEPTED, "duplicate changed winner state to " + diagnostics.state());
        check(diagnostics.failedPhase() == null, "duplicate failure contaminated winner failed phase");
        check(diagnostics.lastErrorClass() == null, "duplicate failure contaminated winner error class");
        check(diagnostics.rejectedRegistrations().stream().anyMatch((entry) -> entry.contains("status=DUPLICATE")),
            "duplicate rejection was not diagnosed");
    }

    private static void retainedRegistrationFacadeIsClosed()
    {
        BBSAddonManager manager = new BBSAddonManager(() -> null);
        BBSAddonDescriptor descriptor = descriptor("retained-context");
        TrackingAddon addon = new TrackingAddon(descriptor);

        check(manager.registerExternal(descriptor, () -> addon), "retained-context addon was rejected");
        check(addon.discoverRegistrationResult.get().status() == BBSRegistrationStatus.REJECTED,
            "discover callback registered through a cast registration facade");
        BBSRegistrationResult retainedDiscover = addon.retainedDiscoverParticles.get().registerComponent(
            "late-discover",
            "late.DiscoverComponent"
        );

        check(retainedDiscover.status() == BBSRegistrationStatus.REJECTED,
            "retained discover context remained writable after callback");
        check(retainedDiscover.reason().contains("phase=DISCOVER"),
            "retained discover rejection omitted its phase");
        manager.runCommonRegistration(null, null, null, null, null, null);

        check(addon.registerCalls.get() == 1, "common registration callback did not run exactly once");
        check(addon.particleResult.get() != null && addon.particleResult.get().accepted(), "in-window particle registration failed");
        check("example.Component".equals(manager.particleComponents().get("example")), "accepted particle registration was lost");

        BBSRegistrationResult late = addon.retainedParticles.get().registerComponent("late", "late.Component");

        check(late.status() == BBSRegistrationStatus.REJECTED, "retained facade remained writable after callback");
        check(late.reason().contains("phase=REGISTER_COMMON"), "late rejection omitted callback phase: " + late.reason());
        check(late.reason().contains(TrackingAddon.class.getName()), "late rejection omitted addon source: " + late.reason());
        check(!manager.particleComponents().containsKey("late"), "late facade mutated the particle registry");

        BBSRegistrationResult legacy = addon.legacyNetworkResult.get();

        check(legacy.status() == BBSRegistrationStatus.REJECTED, "legacy raw receiver unexpectedly became reachable");
        check(legacy.reason().contains("no addon-owned frozen channel"), "legacy rejection did not explain the unreachable contract");

        manager.runCommonSetup();
        BBSRegistrationResult lateSetup = addon.retainedSetupParticles.get().registerComponent(
            "late-setup",
            "late.SetupComponent"
        );
        BBSAddonDiagnostics diagnostics = diagnostic(manager, descriptor.addonId());

        check(lateSetup.status() == BBSRegistrationStatus.REJECTED,
            "retained setup context remained writable after callback");
        check(lateSetup.reason().contains("phase=COMMON_SETUP"),
            "retained setup rejection omitted its phase");
        check(addon.setupRegistrationResult.get().status() == BBSRegistrationStatus.REJECTED,
            "setup callback registered through a cast registration facade");
        check(diagnostics.state() == BBSAddonState.READY, "closed facade rejection changed addon readiness");
        check(addon.setupCalls.get() == 1, "common setup callback did not run exactly once");
    }

    private static void linkageErrorsAreIsolated()
    {
        BBSAddonManager constructionManager = new BBSAddonManager(() -> null);
        BBSAddonDescriptor constructionDescriptor = descriptor("linkage-construction");

        check(!constructionManager.registerExternal(constructionDescriptor, () ->
        {
            throw new NoClassDefFoundError("missing optional construction dependency");
        }), "construction LinkageError escaped as acceptance");
        assertFailedDiscover(constructionManager, constructionDescriptor.addonId(), NoClassDefFoundError.class);

        BBSAddonManager descriptorManager = new BBSAddonManager(() -> null);

        check(!descriptorManager.registerExternal(null, () -> new BBSAddon()
        {
            @Override
            public BBSAddonDescriptor descriptor()
            {
                throw new NoClassDefFoundError("missing optional descriptor dependency");
            }
        }), "descriptor LinkageError escaped as acceptance");
        assertFailedDiscover(descriptorManager, "<unknown>", NoClassDefFoundError.class);
    }

    private static void clientDiagnosticsAttachWithoutChangingWinner()
    {
        BBSAddonManager manager = new BBSAddonManager(() -> null);
        BBSAddonDescriptor descriptor = descriptor("client-diagnostics");

        check(manager.registerExternal(descriptor, () -> new TrackingAddon(descriptor)), "client diagnostic addon was rejected");
        check(manager.recordClientDiagnostic(
            descriptor,
            BBSAddonPhase.REGISTER_CLIENT,
            "example.RendererFactory",
            BBSRegistrationResult.accepted("entity-renderer:example"),
            null
        ), "REGISTER_CLIENT diagnostic was not attached");
        check(manager.recordClientDiagnostic(
            descriptor,
            BBSAddonPhase.CLIENT_SETUP,
            "example.RendererFactory",
            null,
            null
        ), "successful CLIENT_SETUP phase marker was not attached");
        check(manager.recordClientDiagnostic(
            descriptor,
            BBSAddonPhase.CLIENT_SETUP,
            "example.RendererFactory",
            BBSRegistrationResult.rejected("entity-renderer:example", "deterministic client failure"),
            new NoClassDefFoundError("missing optional client renderer dependency")
        ), "CLIENT_SETUP diagnostic was not attached");
        check(!manager.recordClientDiagnostic(
            descriptor,
            BBSAddonPhase.RUNTIME,
            "invalid-phase",
            null,
            null
        ), "non-client lifecycle phase was accepted by the client bridge");

        BBSAddonDiagnostics diagnostics = diagnostic(manager, descriptor.addonId());

        check(diagnostics.state() == BBSAddonState.REGISTERED_CLIENT,
            "client setup phase was not retained or client failure changed its state");
        check(diagnostics.acceptedRegistrations().contains("entity-renderer:example"), "client acceptance was not recorded");
        check(diagnostics.warnings().stream().anyMatch((warning) ->
                warning.contains("phase=REGISTER_CLIENT") && warning.contains("example.RendererFactory")),
            "client registration phase/source was absent from diagnostics");
        check(diagnostics.errors().stream().anyMatch((error) ->
                error.contains("phase=CLIENT_SETUP") && error.contains(NoClassDefFoundError.class.getName())),
            "client setup error phase/source was absent from diagnostics");
    }

    private static void crossProtocolFirstWins()
    {
        BBSAddonIdentityRegistry v1FirstIdentities = new BBSAddonIdentityRegistry();
        BBSAddonCollector v1FirstCollector = new BBSAddonCollector(v1FirstIdentities);
        BBSAddonManager v1FirstManager = new BBSAddonManager(() -> null, v1FirstIdentities);
        AtomicInteger v1SupplierCalls = new AtomicInteger();
        AtomicInteger rejectedV2SupplierCalls = new AtomicInteger();
        String v1FirstId = "cross-v1-first";

        check(v1FirstCollector.registerExternal(v1FirstId, () ->
        {
            v1SupplierCalls.incrementAndGet();
            return new BBSAddonMod() {};
        }), "first v1 addon was rejected");
        check(!v1FirstManager.registerExternal(descriptor(v1FirstId), () ->
        {
            rejectedV2SupplierCalls.incrementAndGet();
            return new TrackingAddon(descriptor(v1FirstId));
        }), "later API 2.0 duplicate was accepted");
        check(v1SupplierCalls.get() == 1 && rejectedV2SupplierCalls.get() == 0,
            "v1-first lifecycle constructed more than one implementation");

        v1FirstCollector.bridgeAndCloseExternalRegistrationWindow(new EventBus());
        v1FirstManager.indexLegacyAddons(v1FirstCollector.getAddonMap(), v1FirstCollector.getRegistrationDiagnostics());
        BBSAddonDiagnostics v1Diagnostics = diagnostic(v1FirstManager, v1FirstId);

        check(v1Diagnostics.state() == BBSAddonState.BRIDGED_LEGACY,
            "v1 winner was not preserved in diagnostics");
        check(v1Diagnostics.rejectedRegistrations().stream().anyMatch((entry) -> entry.contains("status=DUPLICATE")),
            "v1 winner diagnostics lost the rejected API 2.0 duplicate result");
        check(v1Diagnostics.warnings().stream().anyMatch((warning) ->
                warning.contains("phase=DISCOVER")
                    && warning.contains("source=")
                    && warning.contains("kept=API1_REGISTERED")),
            "v1 winner diagnostics lost duplicate phase/source/kept-owner details");

        BBSAddonIdentityRegistry v2FirstIdentities = new BBSAddonIdentityRegistry();
        BBSAddonCollector v2FirstCollector = new BBSAddonCollector(v2FirstIdentities);
        BBSAddonManager v2FirstManager = new BBSAddonManager(() -> null, v2FirstIdentities);
        AtomicInteger v2SupplierCalls = new AtomicInteger();
        AtomicInteger rejectedV1SupplierCalls = new AtomicInteger();
        String v2FirstId = "cross-v2-first";
        BBSAddonDescriptor v2Descriptor = descriptor(v2FirstId);

        check(v2FirstManager.registerExternal(v2Descriptor, () ->
        {
            v2SupplierCalls.incrementAndGet();
            return new TrackingAddon(v2Descriptor);
        }), "first API 2.0 addon was rejected");
        check(!v2FirstCollector.registerExternal(v2FirstId, () ->
        {
            rejectedV1SupplierCalls.incrementAndGet();
            return new BBSAddonMod() {};
        }), "later v1 duplicate was accepted");
        check(v2SupplierCalls.get() == 1 && rejectedV1SupplierCalls.get() == 0,
            "v2-first lifecycle constructed more than one implementation");

        v2FirstCollector.bridgeAndCloseExternalRegistrationWindow(new EventBus());
        v2FirstManager.indexLegacyAddons(v2FirstCollector.getAddonMap(), v2FirstCollector.getRegistrationDiagnostics());
        BBSAddonDiagnostics v2Diagnostics = diagnostic(v2FirstManager, v2FirstId);

        check(v2Diagnostics.state() == BBSAddonState.ACCEPTED, "v1 duplicate changed API 2.0 winner state");
        check(v2Diagnostics.rejectedRegistrations().stream().anyMatch((entry) ->
                entry.contains("duplicate addonId") || entry.contains("status=DUPLICATE")),
            "cross-protocol rejection was absent from diagnostics");
    }

    private static void failedLegacyAttemptDoesNotFailWinner()
    {
        String addonId = "legacy-failed-then-success";
        BBSAddonCollector collector = new BBSAddonCollector();
        AtomicInteger failedSupplierCalls = new AtomicInteger();
        AtomicInteger winnerSupplierCalls = new AtomicInteger();
        Supplier<BBSAddonMod> failedSupplier = () ->
        {
            failedSupplierCalls.incrementAndGet();
            throw new NoClassDefFoundError("missing dependency on rejected attempt");
        };

        check(!collector.registerExternal(addonId, failedSupplier), "failed v1 supplier was accepted");
        check(collector.registerExternal(addonId, () ->
        {
            winnerSupplierCalls.incrementAndGet();
            return new BBSAddonMod() {};
        }), "same-id v1 retry was rejected after an unclaimed supplier failure");
        check(failedSupplierCalls.get() == 1 && winnerSupplierCalls.get() == 1,
            "failed-then-success v1 suppliers did not each execute exactly once");

        collector.bridgeAndCloseExternalRegistrationWindow(new EventBus());

        BBSAddonManager manager = new BBSAddonManager(() -> null);

        manager.indexLegacyAddons(collector.getAddonMap(), collector.getRegistrationDiagnostics());

        BBSAddonDiagnostics diagnostics = diagnostic(manager, addonId);

        check(diagnostics.state() == BBSAddonState.BRIDGED_LEGACY,
            "rejected supplier failure replaced the successfully bridged v1 winner");
        check(diagnostics.failedPhase() == null && diagnostics.lastErrorClass() == null,
            "rejected supplier failure contaminated winner failure metadata");
        check(diagnostics.errors().stream().anyMatch((error) ->
                error.contains("rejected API1_REGISTERED attempt: phase=DISCOVER")
                    && error.contains("source=")
                    && error.contains(failedSupplier.getClass().getName())
                    && error.contains(NoClassDefFoundError.class.getName())),
            "winner diagnostics lost the rejected supplier phase/source/error history");
        check(diagnostics.warnings().stream().anyMatch((warning) ->
                warning.contains("rejected API1_REGISTERED attempt: failedPhase=DISCOVER")
                    && warning.contains(NoClassDefFoundError.class.getName())),
            "winner diagnostics lost rejected supplier failure metadata");

        String bridgeFailureId = "legacy-bridge-failure";
        BBSAddonCollector bridgeFailureCollector = new BBSAddonCollector();

        check(bridgeFailureCollector.registerExternal(bridgeFailureId, new BBSAddonMod() {}),
            "bridge-failure fixture was rejected");
        bridgeFailureCollector.bridgeAndCloseExternalRegistrationWindow(new EventBus()
        {
            @Override
            public void register(Object subscriber)
            {
                throw new NoClassDefFoundError("missing dependency in accepted winner bridge");
            }
        });

        BBSAddonManager bridgeFailureManager = new BBSAddonManager(() -> null);

        bridgeFailureManager.indexLegacyAddons(
            bridgeFailureCollector.getAddonMap(),
            bridgeFailureCollector.getRegistrationDiagnostics()
        );

        BBSAddonDiagnostics bridgeFailure = diagnostic(bridgeFailureManager, bridgeFailureId);

        check(bridgeFailure.state() == BBSAddonState.FAILED,
            "accepted v1 winner bridge failure did not fail its own diagnostics");
        check(bridgeFailure.failedPhase() == BBSAddonPhase.REGISTER_COMMON,
            "accepted v1 winner bridge failure recorded the wrong phase");
        check(NoClassDefFoundError.class.getName().equals(bridgeFailure.lastErrorClass()),
            "accepted v1 winner bridge failure lost its error class");

        String api2WinnerId = "legacy-failure-api2-success";
        BBSAddonIdentityRegistry sharedIdentities = new BBSAddonIdentityRegistry();
        BBSAddonCollector failedLegacyCollector = new BBSAddonCollector(sharedIdentities);
        BBSAddonManager api2WinnerManager = new BBSAddonManager(() -> null, sharedIdentities);
        BBSAddonDescriptor api2WinnerDescriptor = descriptor(api2WinnerId);

        check(!failedLegacyCollector.registerExternal(api2WinnerId, () ->
        {
            throw new NoClassDefFoundError("missing dependency before API 2.0 winner");
        }), "failed v1 attempt before API 2.0 winner was accepted");
        check(api2WinnerManager.registerExternal(
            api2WinnerDescriptor,
            () -> new TrackingAddon(api2WinnerDescriptor)
        ), "API 2.0 winner was rejected after an unclaimed v1 supplier failure");

        api2WinnerManager.indexLegacyAddons(
            failedLegacyCollector.getAddonMap(),
            failedLegacyCollector.getRegistrationDiagnostics()
        );

        BBSAddonDiagnostics api2Winner = diagnostic(api2WinnerManager, api2WinnerId);

        check(api2Winner.state() == BBSAddonState.ACCEPTED,
            "rejected v1 supplier failure replaced the later API 2.0 winner");
        check(api2Winner.failedPhase() == null && api2Winner.lastErrorClass() == null,
            "rejected v1 supplier failure contaminated API 2.0 winner metadata");
        check(api2Winner.errors().stream().anyMatch((error) ->
                error.contains("rejected API1_REGISTERED attempt: phase=DISCOVER")
                    && error.contains(NoClassDefFoundError.class.getName())),
            "API 2.0 winner diagnostics lost the rejected v1 supplier history");

        String mismatchedBridgeId = "legacy-mismatched-bridge-source";
        BBSAddonCollector mismatchedBridgeCollector = new BBSAddonCollector();
        BBSAddonMod rejectedBridgeAttempt = new BBSAddonMod() {};
        BBSAddonMod acceptedBridgeWinner = new BBSAddonMod() {};

        check(mismatchedBridgeCollector.registerExternal(mismatchedBridgeId, rejectedBridgeAttempt),
            "mismatched bridge diagnostic fixture was rejected");
        mismatchedBridgeCollector.bridgeAndCloseExternalRegistrationWindow(new EventBus()
        {
            @Override
            public void register(Object subscriber)
            {
                throw new NoClassDefFoundError("bridge failure from a different implementation");
            }
        });

        BBSAddonManager mismatchedBridgeManager = new BBSAddonManager(() -> null);

        mismatchedBridgeManager.indexLegacyAddons(
            Map.of(mismatchedBridgeId, acceptedBridgeWinner),
            mismatchedBridgeCollector.getRegistrationDiagnostics()
        );

        BBSAddonDiagnostics mismatchedBridge = diagnostic(mismatchedBridgeManager, mismatchedBridgeId);

        check(mismatchedBridge.state() == BBSAddonState.BRIDGED_LEGACY,
            "REGISTER_COMMON failure from a different source replaced the accepted v1 winner");
        check(mismatchedBridge.failedPhase() == null && mismatchedBridge.lastErrorClass() == null,
            "mismatched bridge source contaminated accepted v1 winner metadata");
        check(mismatchedBridge.errors().stream().anyMatch((error) ->
                error.contains("rejected API1_REGISTERED attempt: phase=REGISTER_COMMON")
                    && error.contains("bridge:" + rejectedBridgeAttempt.getClass().getName())
                    && error.contains(NoClassDefFoundError.class.getName())),
            "mismatched bridge source was not preserved as rejected-attempt history");
    }

    private static void legacySupplierDiagnosticsAndAtomicBridge()
    {
        BBSAddonCollector collector = new BBSAddonCollector();
        AtomicBoolean duringBridgeAccepted = new AtomicBoolean();
        AtomicInteger duringBridgeSupplierCalls = new AtomicInteger();
        AtomicInteger lateSupplierCalls = new AtomicInteger();
        Supplier<BBSAddonMod> linkageSupplier = () ->
        {
            throw new NoClassDefFoundError("missing legacy dependency");
        };

        check(!collector.registerExternal("null-v1", (Supplier<? extends BBSAddonMod>) null), "null v1 supplier was accepted");
        check(!collector.registerExternal("linkage-v1", linkageSupplier), "v1 LinkageError was accepted");
        check(collector.getRegistrationDiagnostics().stream().anyMatch((diagnostic) ->
                "null-v1".equals(diagnostic.addonId()) && diagnostic.message().contains("supplier is null")),
            "null v1 supplier warning was not retained");
        check(collector.getRegistrationDiagnostics().stream().anyMatch((diagnostic) ->
                "linkage-v1".equals(diagnostic.addonId())
                    && NoClassDefFoundError.class.getName().equals(diagnostic.errorClass())
                    && diagnostic.source().contains(linkageSupplier.getClass().getName())),
            "v1 LinkageError diagnostic did not retain its supplier source");

        BBSAddonManager diagnosticsManager = new BBSAddonManager(() -> null);

        diagnosticsManager.indexLegacyAddons(Map.of(), collector.getRegistrationDiagnostics());
        assertFailedDiscover(diagnosticsManager, "linkage-v1", NoClassDefFoundError.class);
        check(diagnostic(diagnosticsManager, "null-v1").state() == BBSAddonState.SKIPPED,
            "null v1 supplier did not produce a skipped diagnostic");

        check(collector.registerExternal("bridge-seed", new BBSAddonMod() {}), "bridge seed was rejected");
        collector.bridgeAndCloseExternalRegistrationWindow(new EventBus()
        {
            @Override
            public void register(Object subscriber)
            {
                duringBridgeAccepted.set(collector.registerExternal("during-bridge", () ->
                {
                    duringBridgeSupplierCalls.incrementAndGet();
                    return new BBSAddonMod() {};
                }));
                super.register(subscriber);
            }
        });
        check(!duringBridgeAccepted.get(), "external registration entered the atomic bridge snapshot");
        check(duringBridgeSupplierCalls.get() == 0, "atomic bridge invoked a registration supplier after closure");
        check(!collector.registerExternal("after-bridge", () ->
        {
            lateSupplierCalls.incrementAndGet();
            return new BBSAddonMod() {};
        }), "external v1 registration was accepted after atomic bridge closure");
        check(lateSupplierCalls.get() == 0, "closed bridge window invoked a late supplier");
    }

    private static void exactApiVersionAdmission()
    {
        check(BBSApiVersion.isSupported("2.0"), "exact Addon/API 2.0 was rejected");
        check(BBSApiVersion.isSupported(" 2.0 "), "surrounding API version whitespace was not normalized");
        check(!BBSApiVersion.isSupported(null), "null API version was accepted");

        for (String unsupported : new String[] {"", " ", "2", "02.0", "2.00", "2.0.0", "2.1", "2.x"})
        {
            check(!BBSApiVersion.isSupported(unsupported), "non-exact API version was accepted: '" + unsupported + "'");
        }

        BBSAddonManager manager = new BBSAddonManager(() -> null);
        BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder("future-api-version")
            .apiVersion("2.1")
            .build();
        AtomicInteger supplierCalls = new AtomicInteger();

        check(!manager.registerExternal(descriptor, () ->
        {
            supplierCalls.incrementAndGet();

            return new TrackingAddon(descriptor);
        }), "future API version was accepted");
        check(supplierCalls.get() == 0, "future API version loaded its addon before descriptor rejection");

        BBSAddonDiagnostics diagnostics = diagnostic(manager, descriptor.addonId());

        check(diagnostics.state() == BBSAddonState.SKIPPED, "future API version did not produce skipped diagnostics");
        check(diagnostics.rejectedRegistrations().stream().anyMatch((entry) ->
                entry.contains("unsupported apiVersion '2.1'")),
            "future API version rejection was not retained in diagnostics");
    }

    private static void frozenCompatibilityConstantsStayStable()
    {
        check("2.0".equals(BBSApiVersion.CURRENT), "Addon/API version changed from 2.0");
        check(ResourceLocation.fromNamespaceAndPath("bbs", "s15").equals(NetworkCompat.ADDON_BROKER_C2S),
            "addon C2S broker id changed");
        check(ResourceLocation.fromNamespaceAndPath("bbs", "c18").equals(NetworkCompat.ADDON_BROKER_S2C),
            "addon S2C broker id changed");

        Map<?, ?> c2s = staticMap(NetworkCompat.class, "C2S_BINDINGS");
        Map<?, ?> s2c = staticMap(NetworkCompat.class, "S2C_BINDINGS");

        check(c2s.size() == 15, "frozen C2S table no longer spans s1..s15");
        check(s2c.size() == 19, "frozen S2C table no longer spans c1..c19");

        for (int i = 1; i <= 15; i += 1)
        {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bbs", "s" + i);

            check(c2s.containsKey(id), "frozen C2S table is missing " + id);
        }

        for (int i = 1; i <= 19; i += 1)
        {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("bbs", "c" + i);

            check(s2c.containsKey(id), "frozen S2C table is missing " + id);
        }
    }

    private static void brokerSingleFrameLimitsStayWithinFrozenPayloads()
    {
        BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder("broker-frame-test")
            .capability(BBSAddonCapability.NETWORK)
            .build();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("broker-frame-test", "message");
        int c2sBodyLimit = staticInt(AddonPayloadBroker.class, "MAX_C2S_BODY_BYTES");
        int s2cBodyLimit = staticInt(AddonPayloadBroker.class, "MAX_S2C_BODY_BYTES");
        FriendlyByteBuf c2sBody = NetworkCompat.createBuffer();
        FriendlyByteBuf s2cBody = NetworkCompat.createBuffer();

        c2sBody.writeZero(c2sBodyLimit);
        s2cBody.writeZero(s2cBodyLimit);

        FriendlyByteBuf c2sFrame = createBrokerFrame(descriptor, id, c2sBody, c2sBodyLimit, "c2s");
        FriendlyByteBuf s2cFrame = createBrokerFrame(descriptor, id, s2cBody, s2cBodyLimit, "s2c");

        check(c2sFrame != null
                && c2sFrame.readableBytes() <= NetworkCompat.MAX_SERVERBOUND_RAW_PAYLOAD_BYTES,
            "legal maximum broker C2S body no longer fits the frozen s15 payload");
        check(s2cFrame != null
                && s2cFrame.readableBytes() <= NetworkCompat.MAX_CLIENTBOUND_RAW_PAYLOAD_BYTES,
            "legal maximum broker S2C body no longer fits the frozen c18 payload");

        FriendlyByteBuf oversizedC2s = NetworkCompat.createBuffer();
        FriendlyByteBuf oversizedS2c = NetworkCompat.createBuffer();

        oversizedC2s.writeZero(c2sBodyLimit + 1);
        oversizedS2c.writeZero(s2cBodyLimit + 1);

        check(createBrokerFrame(descriptor, id, oversizedC2s, c2sBodyLimit, "c2s") == null,
            "broker accepted a C2S body above its single-frame limit");
        check(createBrokerFrame(descriptor, id, oversizedS2c, s2cBodyLimit, "s2c") == null,
            "broker accepted an S2C body above its single-frame limit");
    }

    @SuppressWarnings("deprecation")
    private static void clientBrokerDeliveryRequiresDispatcher()
    {
        BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder("client-broker-test")
            .capability(BBSAddonCapability.NETWORK)
            .build();
        ResourceLocation messageId = ResourceLocation.fromNamespaceAndPath("client-broker-test", "message");
        ResourceLocation throwingId = ResourceLocation.fromNamespaceAndPath("client-broker-test", "throwing");
        AtomicInteger deliveries = new AtomicInteger();
        AtomicInteger value = new AtomicInteger();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Object clientGeneration = new Object();

        AddonPayloadBroker.resetClientBudget();

        check(AddonPayloadBroker.registerClientReceiver(descriptor, messageId, (id, payload) ->
        {
            deliveries.incrementAndGet();
            value.set(payload.readInt());
        }).accepted(), "client broker receiver registration failed");

        AddonPayloadBroker.handleClientPayload(brokerFrame(messageId, 41), clientGeneration, queued::set);
        check(deliveries.get() == 0, "client broker receiver ran on the decode/network thread");
        check(queued.get() != null, "client broker did not hand delivery to its dispatcher");

        queued.getAndSet(null).run();
        check(deliveries.get() == 1 && value.get() == 41, "dispatched client broker payload was corrupted or lost");

        AddonPayloadBroker.handleClientPayload(brokerFrame(messageId, 42), queued::set);
        check(queued.get() == null && deliveries.get() == 1,
            "dispatcher-only legacy broker entry bypassed exact-generation admission");

        AddonPayloadBroker.handleClientPayload(brokerFrame(messageId, 42));
        check(deliveries.get() == 1, "legacy one-argument broker entry bypassed the client dispatcher");

        check(AddonPayloadBroker.registerClientReceiver(descriptor, throwingId, (id, payload) ->
        {
            throw new NoClassDefFoundError("deterministic client receiver linkage failure");
        }).accepted(), "throwing client broker receiver registration failed");

        AddonPayloadBroker.handleClientPayload(brokerFrame(throwingId, 0), clientGeneration, queued::set);
        check(queued.get() != null, "throwing receiver delivery was not dispatched");
        queued.getAndSet(null).run();

        AddonPayloadBroker.handleClientPayload(brokerFrame(messageId, 43), clientGeneration, task ->
        {
            throw new NoClassDefFoundError("deterministic dispatcher linkage failure");
        });
        check(deliveries.get() == 1, "dispatcher failure leaked into direct receiver execution");

        AddonPayloadBroker.resetClientBudget();
    }

    private static FriendlyByteBuf brokerFrame(ResourceLocation id, int value)
    {
        FriendlyByteBuf frame = NetworkCompat.createBuffer();

        frame.writeUtf(id.toString());
        frame.writeInt(Integer.BYTES);
        frame.writeInt(value);

        return frame;
    }

    private static Map<?, ?> staticMap(Class<?> owner, String fieldName)
    {
        try
        {
            java.lang.reflect.Field field = owner.getDeclaredField(fieldName);

            field.setAccessible(true);

            return (Map<?, ?>) field.get(null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("Could not inspect " + owner.getName() + "#" + fieldName, e);
        }
    }

    private static int staticInt(Class<?> owner, String fieldName)
    {
        try
        {
            java.lang.reflect.Field field = owner.getDeclaredField(fieldName);

            field.setAccessible(true);

            return field.getInt(null);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("Could not inspect " + owner.getName() + "#" + fieldName, e);
        }
    }

    private static FriendlyByteBuf createBrokerFrame(
        BBSAddonDescriptor descriptor,
        ResourceLocation id,
        FriendlyByteBuf payload,
        int maxBodyBytes,
        String direction
    )
    {
        try
        {
            java.lang.reflect.Method method = AddonPayloadBroker.class.getDeclaredMethod(
                "createFrame",
                BBSAddonDescriptor.class,
                ResourceLocation.class,
                FriendlyByteBuf.class,
                int.class,
                String.class
            );

            method.setAccessible(true);

            return (FriendlyByteBuf) method.invoke(null, descriptor, id, payload, maxBodyBytes, direction);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("Could not create an addon broker frame", e);
        }
    }

    private static void assertFailedDiscover(BBSAddonManager manager, String addonId, Class<?> errorClass)
    {
        BBSAddonDiagnostics diagnostics = diagnostic(manager, addonId);

        check(diagnostics.state() == BBSAddonState.FAILED, "LinkageError did not produce FAILED diagnostics");
        check(diagnostics.failedPhase() == BBSAddonPhase.DISCOVER, "LinkageError phase was not DISCOVER");
        check(errorClass.getName().equals(diagnostics.lastErrorClass()), "LinkageError class was not retained");
    }

    private static BBSAddonDiagnostics diagnostic(BBSAddonManager manager, String addonId)
    {
        return manager.diagnostics().stream()
            .filter((diagnostic) -> addonId.equals(diagnostic.addonId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing diagnostics for " + addonId));
    }

    private static BBSAddonDescriptor descriptor(String id)
    {
        return BBSAddonDescriptor.builder(id)
            .capability(BBSAddonCapability.PARTICLES)
            .capability(BBSAddonCapability.NETWORK)
            .build();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TrackingAddon implements BBSAddon
    {
        private final BBSAddonDescriptor descriptor;
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger setupCalls = new AtomicInteger();
        private final AtomicReference<BBSParticleRegistry> retainedDiscoverParticles = new AtomicReference<>();
        private final AtomicReference<BBSParticleRegistry> retainedParticles = new AtomicReference<>();
        private final AtomicReference<BBSParticleRegistry> retainedSetupParticles = new AtomicReference<>();
        private final AtomicReference<BBSNetworkRegistry> retainedNetwork = new AtomicReference<>();
        private final AtomicReference<BBSRegistrationResult> particleResult = new AtomicReference<>();
        private final AtomicReference<BBSRegistrationResult> legacyNetworkResult = new AtomicReference<>();
        private final AtomicReference<BBSRegistrationResult> discoverRegistrationResult = new AtomicReference<>();
        private final AtomicReference<BBSRegistrationResult> setupRegistrationResult = new AtomicReference<>();

        private TrackingAddon(BBSAddonDescriptor descriptor)
        {
            this.descriptor = descriptor;
        }

        @Override
        public BBSAddonDescriptor descriptor()
        {
            return this.descriptor;
        }

        @Override
        public void discover(BBSAddonContext context)
        {
            if (context instanceof BBSAddonRegistrationContext registration)
            {
                this.retainedDiscoverParticles.set(registration.particles());
                this.discoverRegistrationResult.set(registration.particles().registerComponent(
                    "during-discover",
                    "during.DiscoverComponent"
                ));
            }
        }

        @Override
        public void register(BBSAddonRegistrationContext context)
        {
            this.registerCalls.incrementAndGet();
            this.retainedParticles.set(context.particles());
            this.retainedNetwork.set(context.network());
            this.particleResult.set(context.particles().registerComponent("example", "example.Component"));
            this.legacyNetworkResult.set(context.network().registerLegacyServerReceiver(
                ResourceLocation.fromNamespaceAndPath("bbs", "s1"),
                (server, player, buffer) -> {}
            ));
        }

        @Override
        public void setup(BBSAddonSetupContext context)
        {
            this.setupCalls.incrementAndGet();

            if (context instanceof BBSAddonRegistrationContext registration)
            {
                this.retainedSetupParticles.set(registration.particles());
                this.setupRegistrationResult.set(registration.particles().registerComponent(
                    "during-setup",
                    "during.SetupComponent"
                ));
            }
        }
    }
}
