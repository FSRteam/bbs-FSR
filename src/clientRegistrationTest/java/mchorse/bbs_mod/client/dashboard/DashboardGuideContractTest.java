package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelIds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Source-level guard for the public ids and their real Dashboard registration points. */
public final class DashboardGuideContractTest
{
    private DashboardGuideContractTest() {}

    public static void runAll()
    {
        builtInIdsMatchTaskbarRegistrations();
        anchorsAndOverlayLayerStayWired();
    }

    private static void builtInIdsMatchTaskbarRegistrations()
    {
        check(BBSDashboardPanelIds.BUILT_IN.size() == 9,
            "built-in Dashboard id table no longer has nine entries");
        check(new LinkedHashSet<>(BBSDashboardPanelIds.BUILT_IN).size() == 9,
            "built-in Dashboard id table contains duplicates");

        String dashboard = readSource("src/client/java/mchorse/bbs_mod/ui/dashboard/UIDashboard.java");
        Map<String, String> constants = new LinkedHashMap<>();
        constants.put(BBSDashboardPanelIds.MORPHING, "MORPHING");
        constants.put(BBSDashboardPanelIds.FILM, "FILM");
        constants.put(BBSDashboardPanelIds.MODEL_BLOCKS, "MODEL_BLOCKS");
        constants.put(BBSDashboardPanelIds.PARTICLES, "PARTICLES");
        constants.put(BBSDashboardPanelIds.MODEL_EDITOR, "MODEL_EDITOR");
        constants.put(BBSDashboardPanelIds.TEXTURES, "TEXTURES");
        constants.put(BBSDashboardPanelIds.AUDIO, "AUDIO");
        constants.put(BBSDashboardPanelIds.GRAPH, "GRAPH");
        constants.put(BBSDashboardPanelIds.PLUGINS, "PLUGINS");

        for (String constant : constants.values())
        {
            check(dashboard.contains("registerBuiltInPanel(BBSDashboardPanelIds." + constant + ","),
                "built-in panel constant is not registered: " + constant);
        }

        check(count(dashboard, "registerBuiltInPanel(BBSDashboardPanelIds.") == 9,
            "Dashboard taskbar registrations and public id table drifted apart");
        check(dashboard.contains("List.copyOf(this.builtInPanels.keySet()).equals(BBSDashboardPanelIds.BUILT_IN)"),
            "runtime id/order invariant was removed");
    }

    private static void anchorsAndOverlayLayerStayWired()
    {
        String dashboard = readSource("src/client/java/mchorse/bbs_mod/ui/dashboard/UIDashboard.java");

        check(dashboard.contains("BBSDashboardAnchors.panelButton(id)"),
            "panel-button anchors are no longer registered with built-in panels");
        check(dashboard.contains("BBSDashboardAnchors.panelContent(id)"),
            "panel-content anchors are no longer registered with built-in panels");
        check(dashboard.contains("BBSDashboardAnchors.MORPHING_PALETTE")
                && dashboard.contains("BBSDashboardAnchors.MORPHING_DEMORPH")
                && dashboard.contains("BBSDashboardAnchors.MORPHING_FROM_MOB"),
            "ch01-ch05 Morphing anchors are incomplete");
        check(dashboard.contains("BBSDashboardAnchors.SETTINGS")
                && dashboard.contains("BBSDashboardAnchors.SELECTORS"),
            "Dashboard chrome anchors are incomplete");
        check(dashboard.contains("this.getRoot().addBefore(this.overlay, this.addonOverlayLayer);"),
            "addon guide layer is no longer below modal UIOverlay content");
        check(dashboard.contains("BBSDashboardOverlayHostRegistry.installAll(this);"),
            "Dashboard construction no longer installs registered guide overlays");
    }

    private static int count(String source, String marker)
    {
        int count = 0;
        int offset = 0;

        while ((offset = source.indexOf(marker, offset)) >= 0)
        {
            count++;
            offset += marker.length();
        }

        return count;
    }

    private static String readSource(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            Path source = current.resolve(relativePath);

            if (Files.isRegularFile(source))
            {
                try
                {
                    return Files.readString(source);
                }
                catch (IOException error)
                {
                    throw new AssertionError("could not read " + source, error);
                }
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relativePath);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
