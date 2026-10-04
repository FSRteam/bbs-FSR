package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchors;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelIds;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        verifyAnchorRegistrationSet(dashboard);
        check(dashboard.contains("dashboardAnchors.putIfAbsent(anchorId, new AnchorTarget(panelId, element))"),
            "Dashboard anchor registration no longer preserves duplicate-id protection");
        check(dashboard.contains("throw new IllegalStateException(\"Duplicate Dashboard anchor id: \" + anchorId)"),
            "Dashboard anchor registration no longer fails hard on duplicate ids");
        check(dashboard.contains("this.getRoot().addBefore(this.overlay, this.addonOverlayLayer);"),
            "addon guide layer is no longer below modal UIOverlay content");
        check(dashboard.contains("BBSDashboardOverlayHostRegistry.installAll(this);"),
            "Dashboard construction no longer installs registered guide overlays");
    }

    private static void verifyAnchorRegistrationSet(String dashboard)
    {
        Set<String> constants = new LinkedHashSet<>();

        for (Field field : BBSDashboardAnchors.class.getDeclaredFields())
        {
            int modifiers = field.getModifiers();

            if (field.getType() == String.class
                    && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers))
            {
                constants.add(field.getName());
            }
        }

        Pattern registrationPattern = Pattern.compile(
            "registerAnchor\\(BBSDashboardAnchors\\.([A-Z0-9_]+),\\s*([^,]+),"
        );
        Matcher matcher = registrationPattern.matcher(dashboard);
        Map<String, String> registrations = new LinkedHashMap<>();

        while (matcher.find())
        {
            String name = matcher.group(1);
            String panelId = matcher.group(2).trim();

            check(constants.contains(name), "UIDashboard registers an unknown Dashboard anchor: " + name);
            check(registrations.put(name, panelId) == null, "Dashboard anchor is registered more than once: " + name);
        }

        check(registrations.keySet().equals(constants),
            "BBSDashboardAnchors constants and UIDashboard registrations drifted apart: constants="
                + constants + ", registrations=" + registrations.keySet());

        for (Field field : BBSDashboardAnchors.class.getDeclaredFields())
        {
            int modifiers = field.getModifiers();

            if (field.getType() != String.class
                    || !Modifier.isPublic(modifiers)
                    || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers))
            {
                continue;
            }

            String value;

            try
            {
                value = (String) field.get(null);
            }
            catch (ReflectiveOperationException error)
            {
                throw new AssertionError("could not read Dashboard anchor constant " + field.getName(), error);
            }

            String prefix = value.substring(0, value.indexOf('.'));
            String registration = registrations.get(field.getName());

            if ("dashboard".equals(prefix))
            {
                check("null".equals(registration),
                    "Dashboard chrome anchor must not have a panel id: " + field.getName());
            }
            else
            {
                String panelConstant = panelConstantFor(prefix);
                check(("BBSDashboardPanelIds." + panelConstant).equals(registration),
                    "Dashboard anchor has the wrong panel id: " + field.getName()
                        + " expected " + panelConstant + " but found " + registration);
            }
        }
    }

    private static String panelConstantFor(String panelId)
    {
        for (Field field : BBSDashboardPanelIds.class.getDeclaredFields())
        {
            int modifiers = field.getModifiers();

            if (field.getType() != String.class
                    || !Modifier.isPublic(modifiers)
                    || !Modifier.isStatic(modifiers)
                    || !Modifier.isFinal(modifiers))
            {
                continue;
            }

            try
            {
                if (panelId.equals(field.get(null)))
                {
                    return field.getName();
                }
            }
            catch (ReflectiveOperationException error)
            {
                throw new AssertionError("could not read Dashboard panel id " + field.getName(), error);
            }
        }

        throw new AssertionError("unknown Dashboard panel id prefix: " + panelId);
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
