package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelContent;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelSpec;
import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginKind;
import mchorse.bbs_mod.api.plugin.BBSPluginReloadMode;
import mchorse.bbs_mod.api.plugin.BBSPluginSide;
import mchorse.bbs_mod.api.plugin.client.BBSPluginClientContext;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.client.dashboard.BBSDashboardPanelHostRegistry;
import mchorse.bbs_mod.client.dashboard.DashboardPanelContribution;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.plugin.client.BBSPluginClientStructuralBridge;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.util.List;
import java.util.Set;

public final class DashboardPanelStructuralRegistrationTest
{
    private static final String PLUGIN_ID = "dashboard_structural_test";
    private static final Icon ICON = new Icon(null, "dashboard-structural-test", 0, 0);

    private DashboardPanelStructuralRegistrationTest() {}

    public static void main(String[] args)
    {
        capabilityGateAndStagedReplacement();
        System.out.println("DashboardPanelStructuralRegistrationTest: staged replacement passed");
    }

    private static void capabilityGateAndStagedReplacement()
    {
        PluginOwner owner1 = new PluginOwner(PLUGIN_ID, 1L);
        PluginContributionLedger ledger1 = new PluginContributionLedger(owner1);
        PluginStructuralRegistrationWindow window1 = new PluginStructuralRegistrationWindow(owner1);
        BBSPluginClientContext client1 = context(descriptor(Set.of(BBSPluginCapability.DASHBOARD_PANELS)), owner1, ledger1, window1);
        BBSDashboardPanelSpec spec = BBSDashboardPanelSpec.builder("tools").title(IKey.raw("Tools v1")).icon(ICON).build();
        BBSRegistrationResult staged = client1.dashboardPanels().register(spec,
            () -> BBSDashboardPanelContent.of(new UIElement()));
        DashboardPanelContribution probe = contribution(new PluginOwner(PLUGIN_ID, 99L));

        check(staged.accepted(), "dashboard contribution was rejected during prepare: " + staged);
        check(window1.keys().equals(Set.of("dashboard-panel:" + PLUGIN_ID + ":tools")),
            "dashboard contribution used the wrong structural key: " + window1.keys());
        check(BBSDashboardPanelHostRegistry.preflight(probe, false).accepted(),
            "staged dashboard contribution became visible before commit");

        window1.activate();
        check(PLUGIN_ID.concat("@1").equals(BBSDashboardPanelHostRegistry.preflight(probe, false).keptBy()),
            "committed v1 contribution was not visible");

        PluginOwner owner2 = new PluginOwner(PLUGIN_ID, 2L);
        PluginContributionLedger ledger2 = new PluginContributionLedger(owner2);
        PluginStructuralRegistrationWindow window2 = new PluginStructuralRegistrationWindow(owner2, window1.keys());
        BBSPluginClientContext client2 = context(descriptor(Set.of(BBSPluginCapability.DASHBOARD_PANELS)), owner2, ledger2, window2);

        check(client2.dashboardPanels().register(
            BBSDashboardPanelSpec.builder("tools").title(IKey.raw("Tools v2")).icon(ICON).build(),
            () -> BBSDashboardPanelContent.of(new UIElement())
        ).accepted(), "replacement dashboard contribution was rejected");

        BBSDashboardPanelHostRegistry.beginProjectionBatch();
        try
        {
            window1.deactivate();
            window2.activate();
        }
        finally
        {
            BBSDashboardPanelHostRegistry.endProjectionBatch();
        }
        check(PLUGIN_ID.concat("@2").equals(BBSDashboardPanelHostRegistry.preflight(probe, false).keptBy()),
            "v2 did not replace the active dashboard contribution");

        ledger1.close();
        check(PLUGIN_ID.concat("@2").equals(BBSDashboardPanelHostRegistry.preflight(probe, false).keptBy()),
            "old generation ledger cleanup removed v2");

        PluginOwner missingOwner = new PluginOwner("dashboard_missing_capability_test", 1L);
        PluginContributionLedger missingLedger = new PluginContributionLedger(missingOwner);
        PluginStructuralRegistrationWindow missingWindow = new PluginStructuralRegistrationWindow(missingOwner);
        BBSPluginClientContext missing = context(descriptor(Set.of()), missingOwner, missingLedger, missingWindow);

        try
        {
            missing.dashboardPanels().register(spec, () -> BBSDashboardPanelContent.of(new UIElement()));
            throw new AssertionError("dashboard facade accepted a plugin without dashboard_panels capability");
        }
        catch (IllegalStateException expected)
        {}
        finally
        {
            missingLedger.close();
        }

        window2.deactivate();
        ledger2.close();
        check(BBSDashboardPanelHostRegistry.preflight(probe, false).accepted(),
            "active dashboard contribution remained after unload");
    }

    private static BBSPluginClientContext context(
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        PluginStructuralRegistrationWindow window
    )
    {
        return (BBSPluginClientContext) BBSPluginClientStructuralBridge.createExtension(
            BBSPluginClientContext.class, descriptor, owner, ledger, window,
            (severity, code, message) -> {}
        );
    }

    private static BBSPluginDescriptor descriptor(Set<BBSPluginCapability> capabilities)
    {
        return new BBSPluginDescriptor(
            1, BBSPluginKind.CODE, PLUGIN_ID, PLUGIN_ID, "1.0.0", "[1.0,2.0)",
            "fixture.Plugin", "fixture.ClientPlugin", BBSPluginSide.COMMON,
            capabilities, List.of(), BBSPluginReloadMode.HOT
        );
    }

    private static DashboardPanelContribution contribution(PluginOwner owner)
    {
        return new DashboardPanelContribution(
            owner.pluginId(), owner, owner.toString(),
            BBSDashboardPanelSpec.builder("tools").title(IKey.raw("Probe")).icon(ICON).build(),
            () -> BBSDashboardPanelContent.of(new UIElement()),
            (failed, phase, error) -> {}, () -> {}
        );
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
