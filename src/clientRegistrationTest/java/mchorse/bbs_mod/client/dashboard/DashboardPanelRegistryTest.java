package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.addon.v2.BBSAddonManager;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.BBSClientApi;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelContent;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelSpec;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnostics;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.api.registry.BBSRegistrationStatus;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.lang.reflect.Field;

public final class DashboardPanelRegistryTest
{
    private static final Icon ICON = new Icon(null, "dashboard-test", 0, 0);

    private DashboardPanelRegistryTest() {}

    public static void runAll()
    {
        addonValidationAndFirstWins();
        addonDiagnosticsRecordRegistrationOutcomes();
        generationCleanupUsesContributionIdentity();
    }

    private static void addonValidationAndFirstWins()
    {
        BBSDashboardPanelHostRegistry.clearForTests();
        BBSDashboardPanelSpec valid = spec("tools");
        BBSAddonDescriptor missingCapability = BBSAddonDescriptor.builder("dashboard_missing_capability").build();
        BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder("dashboard_addon_test")
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();

        checkStatus(BBSClientApi.registerDashboardPanel(null, valid, () -> content()), BBSRegistrationStatus.REJECTED,
            "null addon descriptor was accepted");
        checkStatus(BBSClientApi.registerDashboardPanel(missingCapability, valid, () -> content()), BBSRegistrationStatus.REJECTED,
            "addon without CLIENT_UI was accepted");
        checkStatus(BBSClientApi.registerDashboardPanel(descriptor, null, () -> content()), BBSRegistrationStatus.REJECTED,
            "null panel spec was accepted");
        checkStatus(BBSClientApi.registerDashboardPanel(descriptor, spec("Invalid Id"), () -> content()), BBSRegistrationStatus.REJECTED,
            "invalid local panel id was accepted");
        checkStatus(BBSClientApi.registerDashboardPanel(descriptor,
            BBSDashboardPanelSpec.builder("missing_title").icon(ICON).build(), () -> content()),
            BBSRegistrationStatus.REJECTED, "panel spec without a title was accepted");
        checkStatus(BBSClientApi.registerDashboardPanel(descriptor,
            BBSDashboardPanelSpec.builder("missing_icon").title(IKey.raw("Missing icon")).build(), () -> content()),
            BBSRegistrationStatus.REJECTED, "panel spec without an icon was accepted");
        checkStatus(BBSClientApi.registerDashboardPanel(descriptor, valid, null), BBSRegistrationStatus.REJECTED,
            "null panel factory was accepted");

        BBSRegistrationResult first = BBSClientApi.registerDashboardPanel(descriptor, valid, DashboardPanelRegistryTest::content);
        BBSRegistrationResult duplicate = BBSClientApi.registerDashboardPanel(descriptor, valid, DashboardPanelRegistryTest::content);

        checkStatus(first, BBSRegistrationStatus.ACCEPTED, "valid addon panel was rejected");
        check("dashboard_addon_test:tools".equals(first.id()), "host did not compose the addon owner into the panel id");
        checkStatus(duplicate, BBSRegistrationStatus.DUPLICATE, "duplicate addon panel was not first-wins");
        check(BBSDashboardPanelHostRegistry.snapshot().size() == 1, "duplicate addon panel mutated the host registry");
        BBSDashboardPanelHostRegistry.clearForTests();
    }

    private static void addonDiagnosticsRecordRegistrationOutcomes()
    {
        BBSDashboardPanelHostRegistry.clearForTests();
        BBSAddonManager manager = new BBSAddonManager(() -> null);
        BBSAddonDescriptor descriptor = BBSAddonDescriptor.builder("dashboard_diagnostics_test")
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();
        Field activeManager = null;
        Object previous = null;

        try
        {
            activeManager = BBSMod.class.getDeclaredField("activeAddonManager");
            activeManager.setAccessible(true);
            previous = activeManager.get(null);
            check(manager.registerExternal(descriptor, () -> new BBSAddon()
            {
                @Override
                public BBSAddonDescriptor descriptor()
                {
                    return descriptor;
                }
            }), "diagnostics fixture addon was rejected");
            activeManager.set(null, manager);

            checkStatus(BBSClientApi.registerDashboardPanel(descriptor, spec("diagnostics"), DashboardPanelRegistryTest::content),
                BBSRegistrationStatus.ACCEPTED, "diagnostics fixture panel was rejected");
            checkStatus(BBSClientApi.registerDashboardPanel(descriptor, spec("diagnostics"), DashboardPanelRegistryTest::content),
                BBSRegistrationStatus.DUPLICATE, "diagnostics fixture duplicate was accepted");
            checkStatus(BBSClientApi.registerDashboardPanel(descriptor, null, DashboardPanelRegistryTest::content),
                BBSRegistrationStatus.REJECTED, "diagnostics fixture null spec was accepted");

            BBSAddonDiagnostics diagnostics = manager.diagnostics().stream()
                .filter((entry) -> descriptor.addonId().equals(entry.addonId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Dashboard registration diagnostics were not attached to the addon"));

            check(diagnostics.acceptedRegistrations().contains("dashboard_diagnostics_test:diagnostics"),
                "accepted Dashboard panel id was absent from addon diagnostics");
            check(diagnostics.rejectedRegistrations().stream().anyMatch((entry) ->
                    entry.contains("dashboard_diagnostics_test:diagnostics")),
                "duplicate Dashboard panel id was absent from addon diagnostics");
            check(diagnostics.rejectedRegistrations().stream().anyMatch((entry) ->
                    entry.contains("dashboard_diagnostics_test:<unknown>")
                        && entry.contains("Dashboard panel spec is null")),
                "null Dashboard panel spec was absent from addon diagnostics");
            check(diagnostics.warnings().stream().anyMatch((warning) ->
                    warning.contains("phase=REGISTER_CLIENT")
                        && warning.contains(BBSClientApi.class.getName())),
                "Dashboard registration phase/source was absent from addon diagnostics");
        }
        catch (ReflectiveOperationException error)
        {
            throw new AssertionError("could not install the addon diagnostics fixture", error);
        }
        finally
        {
            try
            {
                if (activeManager != null)
                {
                    activeManager.set(null, previous);
                }
            }
            catch (ReflectiveOperationException error)
            {
                throw new AssertionError("could not restore the addon diagnostics fixture", error);
            }

            BBSDashboardPanelHostRegistry.clearForTests();
        }
    }

    private static void generationCleanupUsesContributionIdentity()
    {
        BBSDashboardPanelHostRegistry.clearForTests();
        DashboardPanelContribution first = contribution(new PluginOwner("dashboard_generation_test", 1L));
        DashboardPanelContribution replacement = contribution(new PluginOwner("dashboard_generation_test", 2L));

        check(BBSDashboardPanelHostRegistry.install(first, false).accepted(), "first generation was rejected");
        check(BBSDashboardPanelHostRegistry.preflight(replacement, true).accepted(), "replacement generation was rejected");

        BBSDashboardPanelHostRegistry.beginProjectionBatch();
        try
        {
            BBSDashboardPanelHostRegistry.remove(first);
            check(BBSDashboardPanelHostRegistry.install(replacement, true).accepted(), "replacement generation failed to install");
        }
        finally
        {
            BBSDashboardPanelHostRegistry.endProjectionBatch();
        }

        BBSDashboardPanelHostRegistry.remove(first);
        check(BBSDashboardPanelHostRegistry.snapshot().equals(java.util.List.of(replacement)),
            "late cleanup from the old generation removed the replacement");
        BBSDashboardPanelHostRegistry.remove(replacement);
        check(BBSDashboardPanelHostRegistry.snapshot().isEmpty(), "replacement cleanup left a host contribution behind");
    }

    private static DashboardPanelContribution contribution(PluginOwner owner)
    {
        return new DashboardPanelContribution(
            owner.pluginId(), owner, owner.toString(), spec("tools"), DashboardPanelRegistryTest::content,
            (contribution, phase, error) -> {}, () -> {}
        );
    }

    private static BBSDashboardPanelSpec spec(String id)
    {
        return BBSDashboardPanelSpec.builder(id).title(IKey.raw("Dashboard test")).icon(ICON).build();
    }

    private static BBSDashboardPanelContent content()
    {
        return BBSDashboardPanelContent.of(new UIElement());
    }

    private static void checkStatus(BBSRegistrationResult result, BBSRegistrationStatus status, String message)
    {
        check(result.status() == status, message + ": " + result);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
