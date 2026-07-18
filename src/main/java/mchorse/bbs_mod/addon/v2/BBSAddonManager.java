package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.addon.BBSAddonCollector;
import mchorse.bbs_mod.addon.BBSAddonIdentityRegistry;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptorValidator;
import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.addon.BBSAddonProtocol;
import mchorse.bbs_mod.api.addon.BBSAddonState;
import mchorse.bbs_mod.api.addon.BBSAddonCompatPolicy;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnostics;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.loader.LoaderAccess;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class BBSAddonManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-addon-api2");

    private final Supplier<LoaderAccess> loaderSupplier;
    private final BBSAddonIdentityRegistry identities;
    private final LinkedHashMap<String, BBSAddonRecord> addons = new LinkedHashMap<>();
    private final LinkedHashMap<String, BBSAddonDiagnosticRecord> diagnostics = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> particleComponents = new LinkedHashMap<>();
    private boolean registrationOpen = true;

    public BBSAddonManager(Supplier<LoaderAccess> loaderSupplier)
    {
        this(loaderSupplier, new BBSAddonIdentityRegistry());
    }

    public BBSAddonManager(Supplier<LoaderAccess> loaderSupplier, BBSAddonIdentityRegistry identities)
    {
        this.loaderSupplier = loaderSupplier;
        this.identities = identities == null ? new BBSAddonIdentityRegistry() : identities;
    }

    public synchronized boolean registerExternal(BBSAddonDescriptor descriptor, Supplier<? extends BBSAddon> supplier)
    {
        String source = supplierSource(supplier);

        if (!this.registrationOpen)
        {
            String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();

            this.rejectAttempt(
                descriptor,
                BBSAddonProtocol.API2_DECLARED,
                BBSAddonPhase.DISCOVER,
                source,
                "API 2.0 registration window is closed",
                null
            );
            LOGGER.warn("[bbs-addon-api2] rejected addon '{}' because registration window is closed", addonId);

            return false;
        }

        BBSAddonIdentityRegistry.Owner declaredOwner = this.ownerForDescriptor(descriptor);

        if (declaredOwner != null)
        {
            return this.rejectDuplicate(descriptor, source, declaredOwner);
        }

        if (supplier == null)
        {
            return this.rejectAttempt(
                descriptor,
                BBSAddonProtocol.INVALID,
                BBSAddonPhase.DISCOVER,
                source,
                "addon supplier is null",
                null
            );
        }

        boolean descriptorValidated = descriptor != null;
        BBSAddonDiagnosticRecord attempt = descriptor == null ? null : this.beginAttempt(descriptor, BBSAddonProtocol.API2_DECLARED);

        if (descriptorValidated && !this.validateDescriptorForRegistration(descriptor, attempt, source))
        {
            return false;
        }

        BBSAddon addon;

        try
        {
            addon = supplier.get();
        }
        catch (Exception | LinkageError e)
        {
            this.rejectAttempt(
                descriptor,
                BBSAddonProtocol.INVALID,
                BBSAddonPhase.DISCOVER,
                source,
                "addon construction failed",
                e
            );
            LOGGER.error("[bbs-addon-api2] failed to construct addon '{}' from source={}",
                descriptor == null ? "<unknown>" : descriptor.addonId(), source, e);

            return false;
        }

        if (addon == null)
        {
            return this.rejectAttempt(
                descriptor,
                BBSAddonProtocol.INVALID,
                BBSAddonPhase.DISCOVER,
                source,
                "addon supplier returned null",
                null
            );
        }

        BBSAddonDescriptor actualDescriptor = descriptor;
        String implementationSource = addon.getClass().getName();

        try
        {
            if (actualDescriptor == null)
            {
                actualDescriptor = addon.descriptor();
            }
            else
            {
                BBSAddonDescriptor addonDescriptor = addon.descriptor();

                if (addonDescriptor != null && !actualDescriptor.addonId().equals(addonDescriptor.addonId()))
                {
                    return this.rejectAttempt(
                        actualDescriptor,
                        BBSAddonProtocol.INVALID,
                        BBSAddonPhase.DISCOVER,
                        implementationSource,
                        "descriptor addonId does not match addon.descriptor()",
                        null
                    );
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            this.rejectAttempt(
                actualDescriptor,
                BBSAddonProtocol.INVALID,
                BBSAddonPhase.DISCOVER,
                implementationSource,
                "addon descriptor resolution failed",
                e
            );
            LOGGER.error("[bbs-addon-api2] failed to resolve addon descriptor from source={}", implementationSource, e);

            return false;
        }

        if (!descriptorValidated)
        {
            BBSAddonIdentityRegistry.Owner actualOwner = this.ownerForDescriptor(actualDescriptor);

            if (actualOwner != null)
            {
                return this.rejectDuplicate(actualDescriptor, implementationSource, actualOwner);
            }

            attempt = this.beginAttempt(actualDescriptor, BBSAddonProtocol.API2_DECLARED);

            if (!this.validateDescriptorForRegistration(actualDescriptor, attempt, implementationSource))
            {
                return false;
            }
        }

        String addonId = actualDescriptor.addonId();
        BBSAddonIdentityRegistry.Owner racedOwner = this.identities.claim(
            addonId,
            BBSAddonProtocol.API2_DECLARED,
            "api2:" + implementationSource
        );

        if (racedOwner != null)
        {
            return this.rejectDuplicate(actualDescriptor, implementationSource, racedOwner);
        }

        BBSAddonDiagnosticRecord acceptedDiagnostics = attempt == null
            ? this.beginAttempt(actualDescriptor, BBSAddonProtocol.API2_DECLARED)
            : attempt;
        BBSAddonRecord record = new BBSAddonRecord(actualDescriptor, addon, acceptedDiagnostics);

        this.addons.put(addonId, record);
        this.diagnostics.put(addonId, acceptedDiagnostics);
        acceptedDiagnostics.state(BBSAddonState.ACCEPTED);
        acceptedDiagnostics.info("phase=" + BBSAddonPhase.DISCOVER + " source=" + implementationSource + " accepted");
        LOGGER.info("[bbs-addon-api2] accepted API 2.0 addon '{}' ({})", addonId, addon.getClass().getName());

        BBSAddonCommonContext context = this.createCommonContext(
            record,
            BBSAddonPhase.DISCOVER,
            implementationSource,
            null,
            null,
            null,
            null,
            null,
            null
        );

        try
        {
            BBSAddonExecutionBoundary.run(record, BBSAddonPhase.DISCOVER, () -> addon.discover(context));
        }
        finally
        {
            context.close();
        }

        return true;
    }

    public synchronized void closeRegistrationWindow()
    {
        if (!this.registrationOpen)
        {
            return;
        }

        this.registrationOpen = false;
        LOGGER.info("[bbs-addon-api2] registration window closed after collecting {} API 2.0 addon(s)", this.addons.size());
    }

    public synchronized void indexLegacyAddons(Map<String, BBSAddonMod> legacyAddons)
    {
        this.indexLegacyAddons(legacyAddons, List.of());
    }

    public synchronized void indexLegacyAddons(
        Map<String, BBSAddonMod> legacyAddons,
        Collection<BBSAddonCollector.RegistrationDiagnostic> collectorDiagnostics
    )
    {
        Map<String, BBSAddonMod> acceptedLegacy = legacyAddons == null ? Map.of() : legacyAddons;

        for (Map.Entry<String, BBSAddonMod> entry : acceptedLegacy.entrySet())
        {
            String addonId = entry.getKey();
            BBSAddonDescriptor descriptor = legacyDescriptor(addonId);

            if (this.addons.containsKey(addonId))
            {
                BBSAddonRecord api2 = this.addons.get(addonId);

                api2.diagnostics.warn("legacy addon uses the same addonId; API 2.0 addon keeps the v2 lifecycle");
                LOGGER.warn("[bbs-addon-api2] legacy addon '{}' shares an id with API 2.0 addon '{}'",
                    entry.getValue() == null ? "<null>" : entry.getValue().getClass().getName(),
                    addonId);

                continue;
            }

            BBSAddonDiagnosticRecord previous = this.diagnostics.get(addonId);
            BBSAddonDiagnosticRecord record = new BBSAddonDiagnosticRecord(
                descriptor,
                BBSAddonProtocol.API1_REGISTERED,
                BBSAddonState.BRIDGED_LEGACY
            );

            this.diagnostics.put(addonId, record);
            record.warn("using Addon v1 compatibility protocol; migrate to API 2.0 when possible");
            record.info("phase=" + BBSAddonPhase.REGISTER_COMMON + " source=" + sourceOf(entry.getValue()) + " bridged");

            if (previous != null)
            {
                record.mergeRejectedAttempt(previous.snapshot());
                record.warn("an API 2.0 attempt with this addonId was rejected; the first accepted Addon v1 implementation remains authoritative");
            }
        }

        if (collectorDiagnostics == null)
        {
            return;
        }

        for (BBSAddonCollector.RegistrationDiagnostic diagnostic : collectorDiagnostics)
        {
            if (diagnostic == null)
            {
                continue;
            }

            String addonId = diagnostic.addonId() == null || diagnostic.addonId().isBlank()
                ? "<unknown>"
                : diagnostic.addonId();
            BBSAddonDiagnosticRecord record = this.diagnostics.get(addonId);

            if (record == null)
            {
                record = new BBSAddonDiagnosticRecord(
                    legacyDescriptor(addonId),
                    BBSAddonProtocol.API1_REGISTERED,
                    BBSAddonState.SKIPPED
                );
                this.diagnostics.put(addonId, record);
            }

            String detail = "phase=" + diagnostic.phase() + " source=" + diagnostic.source() + ": " + diagnostic.message();
            boolean acceptedLegacyWinner = acceptedLegacy.containsKey(addonId)
                && record.state() == BBSAddonState.BRIDGED_LEGACY;
            boolean acceptedApi2Winner = this.addons.containsKey(addonId);

            if (diagnostic.errorClass() == null)
            {
                record.warn(detail);
            }
            else if (acceptedApi2Winner
                || (acceptedLegacyWinner
                    && !isAcceptedLegacyBridgeFailure(diagnostic, acceptedLegacy.get(addonId))))
            {
                BBSAddonDiagnosticRecord rejectedAttempt = new BBSAddonDiagnosticRecord(
                    legacyDescriptor(addonId),
                    BBSAddonProtocol.API1_REGISTERED,
                    BBSAddonState.SKIPPED
                );

                rejectedAttempt.fail(
                    diagnostic.phase(),
                    diagnostic.errorClass(),
                    detail + " (" + diagnostic.errorClass() + ")"
                );
                rejectedAttempt.record(BBSRegistrationResult.rejected(addonId, detail));
                record.mergeRejectedAttempt(rejectedAttempt.snapshot());

                continue;
            }
            else
            {
                record.fail(diagnostic.phase(), diagnostic.errorClass(), detail + " (" + diagnostic.errorClass() + ")");
            }

            record.record(BBSRegistrationResult.rejected(addonId, detail));
        }
    }

    private static boolean isAcceptedLegacyBridgeFailure(
        BBSAddonCollector.RegistrationDiagnostic diagnostic,
        BBSAddonMod acceptedAddon
    )
    {
        return diagnostic.phase() == BBSAddonPhase.REGISTER_COMMON
            && ("bridge:" + sourceOf(acceptedAddon)).equals(diagnostic.source());
    }

    public synchronized void runCommonRegistration(
        File settingsFolder,
        AssetProvider provider,
        FormArchitect forms,
        MapFactory<Clip, ClipFactoryData> cameraClips,
        MapFactory<Clip, ClipFactoryData> actionClips,
        EventBus eventBus
    )
    {
        this.closeRegistrationWindow();

        for (BBSAddonRecord record : this.addons.values())
        {
            if (record.diagnostics.state() == BBSAddonState.FAILED)
            {
                continue;
            }

            String source = sourceOf(record.addon);
            BBSAddonCommonContext context = this.createCommonContext(
                record,
                BBSAddonPhase.REGISTER_COMMON,
                source,
                settingsFolder,
                provider,
                forms,
                cameraClips,
                actionClips,
                eventBus
            );
            boolean ok;

            try
            {
                ok = BBSAddonExecutionBoundary.run(record, BBSAddonPhase.REGISTER_COMMON, () -> record.addon.register(context));
            }
            finally
            {
                context.close();
            }

            if (ok && record.diagnostics.state() != BBSAddonState.FAILED)
            {
                record.diagnostics.state(BBSAddonState.REGISTERED_COMMON);
                record.diagnostics.info("phase=" + BBSAddonPhase.REGISTER_COMMON + " source=" + source + " completed");
            }
        }
    }

    public synchronized void runCommonSetup()
    {
        for (BBSAddonRecord record : this.addons.values())
        {
            if (record.diagnostics.state() == BBSAddonState.FAILED)
            {
                continue;
            }

            String source = sourceOf(record.addon);
            BBSAddonCommonContext context = this.createCommonContext(
                record,
                BBSAddonPhase.COMMON_SETUP,
                source,
                null,
                null,
                null,
                null,
                null,
                null
            );
            boolean ok;

            try
            {
                ok = BBSAddonExecutionBoundary.run(record, BBSAddonPhase.COMMON_SETUP, () -> record.addon.setup(context));
            }
            finally
            {
                context.close();
            }

            if (ok && record.diagnostics.state() != BBSAddonState.FAILED)
            {
                record.diagnostics.state(BBSAddonState.READY);
                record.diagnostics.info("phase=" + BBSAddonPhase.COMMON_SETUP + " source=" + source + " completed");
            }
        }
    }

    public synchronized List<BBSAddonDiagnostics> diagnostics()
    {
        List<BBSAddonDiagnostics> snapshots = new ArrayList<>();

        for (BBSAddonDiagnosticRecord record : this.diagnostics.values())
        {
            snapshots.add(record.snapshot());
        }

        return Collections.unmodifiableList(snapshots);
    }

    /**
     * Physical-client bridges use this common-only entry point to attach
     * structural registration outcomes to the accepted addon record. It never
     * references client classes and never lets a client hook failure crash or
     * replace the common lifecycle winner.
     */
    public synchronized boolean recordClientDiagnostic(
        BBSAddonDescriptor descriptor,
        BBSAddonPhase phase,
        String source,
        BBSRegistrationResult result,
        Throwable error
    )
    {
        if (phase != BBSAddonPhase.REGISTER_CLIENT && phase != BBSAddonPhase.CLIENT_SETUP)
        {
            LOGGER.warn("[bbs-addon-api2] rejected client diagnostic with invalid phase={} source={}", phase, source);

            return false;
        }

        if (descriptor == null || descriptor.addonId() == null || descriptor.addonId().isBlank())
        {
            LOGGER.warn("[bbs-addon-api2] rejected client diagnostic without an addon descriptor phase={} source={}", phase, source);

            return false;
        }

        BBSAddonRecord record = this.addons.get(descriptor.addonId());

        if (record == null)
        {
            LOGGER.warn("[bbs-addon-api2] rejected client diagnostic for unregistered addon '{}' phase={} source={}",
                descriptor.addonId(), phase, source);

            return false;
        }

        String clientSource = source == null || source.isBlank() ? "<unknown-client-source>" : source;
        String detail = "phase=" + phase + " source=" + clientSource;

        if (result != null)
        {
            record.diagnostics.record(result);

            if (result.accepted())
            {
                record.diagnostics.info(detail + " accepted id=" + result.id());
            }
            else
            {
                record.diagnostics.warn(detail + " " + result);
            }
        }
        else
        {
            record.diagnostics.info(detail + " reached");
        }

        if (error != null)
        {
            record.diagnostics.error(detail + " failed", error);
        }

        if (phase == BBSAddonPhase.CLIENT_SETUP
            && error == null
            && (result == null || result.accepted())
            && (record.diagnostics.state() == BBSAddonState.ACCEPTED
                || record.diagnostics.state() == BBSAddonState.REGISTERED_COMMON))
        {
            record.diagnostics.state(BBSAddonState.REGISTERED_CLIENT);
        }

        return true;
    }

    public synchronized Map<String, String> particleComponents()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.particleComponents));
    }

    public synchronized Collection<BBSAddon> addons()
    {
        List<BBSAddon> result = new ArrayList<>();

        for (BBSAddonRecord record : this.addons.values())
        {
            result.add(record.addon);
        }

        return Collections.unmodifiableList(result);
    }

    private BBSAddonCommonContext createCommonContext(
        BBSAddonRecord record,
        BBSAddonPhase phase,
        String source,
        File settingsFolder,
        AssetProvider provider,
        FormArchitect forms,
        MapFactory<Clip, ClipFactoryData> cameraClips,
        MapFactory<Clip, ClipFactoryData> actionClips,
        EventBus eventBus
    )
    {
        BBSAddonRegistrationWindow window = new BBSAddonRegistrationWindow(phase, source);

        return new BBSAddonCommonContext(
            record.descriptor,
            record.diagnostics,
            window,
            this.safeLoader(),
            BBSAddonRegistryAdapters.resources(window, record.diagnostics, record.descriptor, provider),
            BBSAddonRegistryAdapters.forms(window, record.diagnostics, record.descriptor, forms),
            BBSAddonRegistryAdapters.settings(window, record.diagnostics, record.descriptor, settingsFolder),
            BBSAddonRegistryAdapters.clips(window, record.diagnostics, record.descriptor, cameraClips, actionClips),
            BBSAddonRegistryAdapters.particles(window, record.diagnostics, record.descriptor, this.particleComponents),
            BBSAddonRegistryAdapters.network(window, record.diagnostics, record.descriptor),
            BBSAddonRegistryAdapters.events(window, record.diagnostics, record.descriptor, eventBus)
        );
    }

    private BBSAddonDiagnosticRecord beginAttempt(BBSAddonDescriptor descriptor, BBSAddonProtocol protocol)
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();
        BBSAddonDiagnosticRecord record = new BBSAddonDiagnosticRecord(descriptor, protocol, BBSAddonState.DISCOVERED);

        this.diagnostics.put(addonId, record);

        return record;
    }

    private BBSAddonIdentityRegistry.Owner ownerForDescriptor(BBSAddonDescriptor descriptor)
    {
        if (descriptor == null || descriptor.addonId() == null || descriptor.addonId().isBlank())
        {
            return null;
        }

        return this.identities.owner(descriptor.addonId());
    }

    private boolean rejectDuplicate(BBSAddonDescriptor descriptor, String source, BBSAddonIdentityRegistry.Owner owner)
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();
        BBSAddonRecord kept = this.addons.get(addonId);
        BBSAddonDiagnosticRecord record;

        if (kept == null)
        {
            record = this.beginAttempt(descriptor, BBSAddonProtocol.API2_DECLARED);
            record.state(BBSAddonState.SKIPPED);
        }
        else
        {
            record = kept.diagnostics;
        }

        String detail = "phase=" + BBSAddonPhase.DISCOVER + " source=" + source
            + ": duplicate addonId rejected; kept=" + owner;

        record.warn(detail);
        record.record(BBSRegistrationResult.duplicate(addonId, owner.source()));
        LOGGER.warn("[bbs-addon-api2] duplicate addonId '{}', keeping '{}' and rejecting source='{}'",
            addonId, owner, source);

        return false;
    }

    private boolean rejectAttempt(
        BBSAddonDescriptor descriptor,
        BBSAddonProtocol protocol,
        BBSAddonPhase phase,
        String source,
        String reason,
        Throwable error
    )
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();
        BBSAddonRecord kept = this.addons.get(addonId);
        BBSAddonDiagnosticRecord record = kept == null ? this.diagnostics.get(addonId) : kept.diagnostics;

        if (record == null || (kept == null && record.state() != BBSAddonState.DISCOVERED))
        {
            record = this.beginAttempt(descriptor, protocol);
        }

        String detail = "phase=" + phase + " source=" + source + ": " + reason;

        if (error == null)
        {
            if (kept == null)
            {
                record.state(BBSAddonState.SKIPPED);
            }

            record.warn(detail);
        }
        else if (kept == null)
        {
            record.fail(phase, error, detail);
        }
        else
        {
            record.error(detail, error);
        }

        record.record(BBSRegistrationResult.rejected(addonId, detail));

        return false;
    }

    private boolean validateDescriptorForRegistration(
        BBSAddonDescriptor descriptor,
        BBSAddonDiagnosticRecord diagnostics,
        String source
    )
    {
        List<String> issues = BBSAddonDescriptorValidator.validate(descriptor, this::isModLoadedSafely);

        this.recordDescriptorDiagnostics(diagnostics, descriptor);

        if (!this.isSideAllowed(descriptor))
        {
            issues.add("side " + descriptor.side() + " does not match current distribution " + FMLEnvironment.dist);
        }

        if (issues.isEmpty())
        {
            return true;
        }

        diagnostics.state(BBSAddonState.SKIPPED);

        for (String issue : issues)
        {
            String detail = "phase=" + BBSAddonPhase.DISCOVER + " source=" + source + ": " + issue;

            diagnostics.record(BBSRegistrationResult.rejected(diagnostics.addonId(), detail));
            LOGGER.warn("[bbs-addon-api2] rejected addon '{}': {}", diagnostics.addonId(), detail);
        }

        return false;
    }

    private boolean isModLoadedSafely(String modId)
    {
        LoaderAccess loader = this.safeLoader();

        if (loader == null)
        {
            return true;
        }

        try
        {
            return loader.isModLoaded(modId);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.debug("[bbs-addon-api2] could not check dependency mod '{}'", modId, e);
            return true;
        }
    }

    private void recordDescriptorDiagnostics(BBSAddonDiagnosticRecord diagnostics, BBSAddonDescriptor descriptor)
    {
        if (descriptor == null)
        {
            return;
        }

        if (descriptor.compatPolicy() == BBSAddonCompatPolicy.API2_ONLY)
        {
            diagnostics.info("compatPolicy API2_ONLY disables Addon v1 compatibility bridging for this descriptor");
        }
        else if (descriptor.compatPolicy() == BBSAddonCompatPolicy.ALLOW_LEGACY_COMPAT)
        {
            diagnostics.info("compatPolicy ALLOW_LEGACY_COMPAT permits legacy compatibility diagnostics if a v1 addon shares this id");
        }

        for (String modId : descriptor.optionalMods())
        {
            if (this.isModLoadedSafely(modId))
            {
                diagnostics.info("optional mod '" + modId + "' is loaded");
            }
            else
            {
                diagnostics.warn("optional mod '" + modId + "' is not loaded; addon remains enabled without that integration");
            }
        }
    }

    private boolean isSideAllowed(BBSAddonDescriptor descriptor)
    {
        if (descriptor == null || descriptor.side() == null || descriptor.side() == BBSAddonSide.COMMON)
        {
            return true;
        }

        Dist current = FMLEnvironment.dist;

        if (descriptor.side() == BBSAddonSide.CLIENT)
        {
            return current == Dist.CLIENT;
        }

        if (descriptor.side() == BBSAddonSide.DEDICATED_SERVER)
        {
            return current == Dist.DEDICATED_SERVER;
        }

        return false;
    }

    private LoaderAccess safeLoader()
    {
        if (this.loaderSupplier == null)
        {
            return null;
        }

        try
        {
            return this.loaderSupplier.get();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.debug("[bbs-addon-api2] loader is not available yet", e);
            return null;
        }
    }

    private static String supplierSource(Supplier<?> supplier)
    {
        return supplier == null ? "<null-supplier>" : supplier.getClass().getName();
    }

    private static String sourceOf(Object value)
    {
        return value == null ? "<null>" : value.getClass().getName();
    }

    private static BBSAddonDescriptor legacyDescriptor(String addonId)
    {
        String id = addonId == null || addonId.isBlank() ? "<unknown>" : addonId;

        return BBSAddonDescriptor.builder(id)
            .displayName(id + " (Addon v1)")
            .addonVersion("legacy")
            .apiVersion("1.0")
            .compatPolicy(BBSAddonCompatPolicy.LEGACY_COMPAT_ONLY)
            .build();
    }
}
