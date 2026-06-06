package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.api.addon.BBSAddon;
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
    private final LinkedHashMap<String, BBSAddonRecord> addons = new LinkedHashMap<>();
    private final LinkedHashMap<String, BBSAddonDiagnosticRecord> diagnostics = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> particleComponents = new LinkedHashMap<>();
    private boolean registrationOpen = true;

    public BBSAddonManager(Supplier<LoaderAccess> loaderSupplier)
    {
        this.loaderSupplier = loaderSupplier;
    }

    public synchronized boolean registerExternal(BBSAddonDescriptor descriptor, Supplier<? extends BBSAddon> supplier)
    {
        if (!this.registrationOpen)
        {
            String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();
            BBSAddonDiagnosticRecord record = this.diagnosticForRejected(descriptor, BBSAddonProtocol.API2_DECLARED);

            record.state(BBSAddonState.SKIPPED);
            record.record(BBSRegistrationResult.rejected(addonId, "API 2.0 registration window is closed"));
            LOGGER.warn("[bbs-addon-api2] rejected addon '{}' because registration window is closed", addonId);

            return false;
        }

        if (supplier == null)
        {
            BBSAddonDiagnosticRecord record = this.diagnosticForRejected(descriptor, BBSAddonProtocol.INVALID);

            record.state(BBSAddonState.SKIPPED);
            record.record(BBSRegistrationResult.rejected(record.addonId(), "addon supplier is null"));

            return false;
        }

        BBSAddon addon;

        try
        {
            addon = supplier.get();
        }
        catch (Exception | LinkageError e)
        {
            BBSAddonDiagnosticRecord record = this.diagnosticForRejected(descriptor, BBSAddonProtocol.INVALID);

            record.fail(BBSAddonPhase.DISCOVER, e);
            LOGGER.error("[bbs-addon-api2] failed to construct addon '{}'", record.addonId(), e);

            return false;
        }

        if (addon == null)
        {
            BBSAddonDiagnosticRecord record = this.diagnosticForRejected(descriptor, BBSAddonProtocol.INVALID);

            record.state(BBSAddonState.SKIPPED);
            record.record(BBSRegistrationResult.rejected(record.addonId(), "addon supplier returned null"));

            return false;
        }

        BBSAddonDescriptor actualDescriptor = descriptor;

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
                    BBSAddonDiagnosticRecord record = this.diagnosticForRejected(actualDescriptor, BBSAddonProtocol.INVALID);

                    record.state(BBSAddonState.SKIPPED);
                    record.record(BBSRegistrationResult.rejected(actualDescriptor.addonId(), "descriptor addonId does not match addon.descriptor()"));

                    return false;
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            BBSAddonDiagnosticRecord record = this.diagnosticForRejected(actualDescriptor, BBSAddonProtocol.INVALID);

            record.fail(BBSAddonPhase.DISCOVER, e);
            LOGGER.error("[bbs-addon-api2] failed to resolve addon descriptor '{}'", record.addonId(), e);

            return false;
        }

        List<String> issues = BBSAddonDescriptorValidator.validate(actualDescriptor, this::isModLoadedSafely);
        BBSAddonDiagnosticRecord diagnostics = this.diagnosticForRejected(actualDescriptor, BBSAddonProtocol.API2_DECLARED);

        if (!issues.isEmpty())
        {
            diagnostics.state(BBSAddonState.SKIPPED);

            for (String issue : issues)
            {
                diagnostics.record(BBSRegistrationResult.rejected(diagnostics.addonId(), issue));
                LOGGER.warn("[bbs-addon-api2] rejected addon '{}': {}", diagnostics.addonId(), issue);
            }

            return false;
        }

        String addonId = actualDescriptor.addonId();

        if (this.addons.containsKey(addonId))
        {
            BBSAddonRecord kept = this.addons.get(addonId);

            diagnostics.state(BBSAddonState.SKIPPED);
            diagnostics.record(BBSRegistrationResult.duplicate(addonId, kept.addon.getClass().getName()));
            LOGGER.warn("[bbs-addon-api2] duplicate addonId '{}', keeping '{}' and rejecting '{}'",
                addonId,
                kept.addon.getClass().getName(),
                addon.getClass().getName());

            return false;
        }

        BBSAddonRecord record = new BBSAddonRecord(actualDescriptor, addon, diagnostics);

        this.addons.put(addonId, record);
        diagnostics.state(BBSAddonState.ACCEPTED);
        LOGGER.info("[bbs-addon-api2] accepted API 2.0 addon '{}' ({})", addonId, addon.getClass().getName());

        BBSAddonExecutionBoundary.run(record, BBSAddonPhase.DISCOVER, () ->
        {
            BBSAddonCommonContext context = this.createCommonContext(record, null, null, null, null, null, null);

            addon.discover(context);
        });

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
        if (legacyAddons == null || legacyAddons.isEmpty())
        {
            return;
        }

        for (Map.Entry<String, BBSAddonMod> entry : legacyAddons.entrySet())
        {
            String addonId = entry.getKey();
            BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder(addonId)
                .displayName(addonId + " (Addon v1)")
                .addonVersion("legacy")
                .apiVersion("1.0")
                .compatPolicy(BBSAddonCompatPolicy.LEGACY_COMPAT_ONLY)
                .build();

            if (this.addons.containsKey(addonId))
            {
                BBSAddonRecord api2 = this.addons.get(addonId);

                api2.diagnostics.warn("legacy addon uses the same addonId; API 2.0 addon keeps the v2 lifecycle");
                LOGGER.warn("[bbs-addon-api2] legacy addon '{}' shares an id with API 2.0 addon '{}'",
                    entry.getValue() == null ? "<null>" : entry.getValue().getClass().getName(),
                    addonId);

                continue;
            }

            BBSAddonDiagnosticRecord record = this.diagnostics.computeIfAbsent(addonId,
                (id) -> new BBSAddonDiagnosticRecord(descriptor, BBSAddonProtocol.API1_REGISTERED, BBSAddonState.BRIDGED_LEGACY));

            record.state(BBSAddonState.BRIDGED_LEGACY);
            record.warn("using Addon v1 compatibility protocol; migrate to API 2.0 when possible");
        }
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

            BBSAddonCommonContext context = this.createCommonContext(record, settingsFolder, provider, forms, cameraClips, actionClips, eventBus);
            boolean ok = BBSAddonExecutionBoundary.run(record, BBSAddonPhase.REGISTER_COMMON, () -> record.addon.register(context));

            if (ok && record.diagnostics.state() != BBSAddonState.FAILED)
            {
                record.diagnostics.state(BBSAddonState.REGISTERED_COMMON);
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

            BBSAddonCommonContext context = this.createCommonContext(record, null, null, null, null, null, null);
            boolean ok = BBSAddonExecutionBoundary.run(record, BBSAddonPhase.COMMON_SETUP, () -> record.addon.setup(context));

            if (ok && record.diagnostics.state() != BBSAddonState.FAILED)
            {
                record.diagnostics.state(BBSAddonState.READY);
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
        File settingsFolder,
        AssetProvider provider,
        FormArchitect forms,
        MapFactory<Clip, ClipFactoryData> cameraClips,
        MapFactory<Clip, ClipFactoryData> actionClips,
        EventBus eventBus
    )
    {
        return new BBSAddonCommonContext(
            record.descriptor,
            record.diagnostics,
            this.safeLoader(),
            BBSAddonRegistryAdapters.resources(record.diagnostics, provider),
            BBSAddonRegistryAdapters.forms(record.diagnostics, forms),
            BBSAddonRegistryAdapters.settings(record.diagnostics, settingsFolder),
            BBSAddonRegistryAdapters.clips(record.diagnostics, cameraClips, actionClips),
            BBSAddonRegistryAdapters.particles(record.diagnostics, this.particleComponents),
            BBSAddonRegistryAdapters.network(record.diagnostics),
            BBSAddonRegistryAdapters.events(record.diagnostics, eventBus)
        );
    }

    private BBSAddonDiagnosticRecord diagnosticForRejected(BBSAddonDescriptor descriptor, BBSAddonProtocol protocol)
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();

        return this.diagnostics.computeIfAbsent(addonId,
            (id) -> new BBSAddonDiagnosticRecord(descriptor, protocol, BBSAddonState.DISCOVERED));
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
        catch (Exception e)
        {
            LOGGER.debug("[bbs-addon-api2] could not check dependency mod '{}'", modId, e);
            return true;
        }
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
        catch (Exception e)
        {
            LOGGER.debug("[bbs-addon-api2] loader is not available yet", e);
            return null;
        }
    }
}
