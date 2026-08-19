package mchorse.bbs_mod.ui.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelContent;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelSpec;
import mchorse.bbs_mod.client.dashboard.DashboardPanelContribution;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.util.ArrayList;
import java.util.List;

public final class DashboardPanelLifecycleTest
{
    private static final Icon ICON = new Icon(null, "dashboard-lifecycle-test", 0, 0);

    private DashboardPanelLifecycleTest() {}

    public static void runAll()
    {
        dynamicReplacementKeepsLifecycleOrder();
        extensionCallbackFailuresAreContained();
    }

    private static void dynamicReplacementKeepsLifecycleOrder()
    {
        List<String> events = new ArrayList<>();
        UIDashboardPanels panels = new UIDashboardPanels();
        TrackingPanel fallback = new TrackingPanel("fallback", events);
        TrackingPanel first = new TrackingPanel("v1", events);
        TrackingPanel replacement = new TrackingPanel("v2", events);

        panels.registerPanel(fallback, IKey.raw("Fallback"), ICON);
        panels.registerPanel(first, IKey.raw("V1"), ICON);
        panels.setPanel(first);
        check(events.isEmpty(), "closed Dashboard invoked panel lifecycle callbacks");

        panels.open();
        first.update();
        panels.replacePanel(first, replacement, IKey.raw("V2"), ICON);
        panels.removePanel(replacement, fallback);
        panels.close();

        List<String> extensionEvents = events.stream()
            .filter((event) -> event.startsWith("v1.") || event.startsWith("v2."))
            .toList();
        check(extensionEvents.equals(List.of(
            "v1.open", "v1.appear", "v1.update", "v1.disappear", "v1.close",
            "v2.open", "v2.appear", "v2.disappear", "v2.close"
        )), "replace/remove lifecycle order changed: " + extensionEvents);
        check(panels.panel == fallback, "removing the selected extension panel did not choose the requested fallback");
    }

    private static void extensionCallbackFailuresAreContained()
    {
        List<String> callbacks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        BBSDashboardPanelContent content = new BBSDashboardPanelContent()
        {
            private final UIElement root = new UIElement();

            @Override
            public UIElement root()
            {
                return this.root;
            }

            @Override
            public void onOpen()
            {
                callbacks.add("open");
            }

            @Override
            public void onAppear()
            {
                callbacks.add("appear");
            }

            @Override
            public void onUpdate()
            {
                callbacks.add("update");
                throw new IllegalStateException("expected update failure");
            }

            @Override
            public void onDisappear()
            {
                callbacks.add("disappear");
            }

            @Override
            public void onClose()
            {
                callbacks.add("close");
            }
        };
        DashboardPanelContribution contribution = new DashboardPanelContribution(
            "dashboard_lifecycle_test",
            new Object(),
            "lifecycle test",
            BBSDashboardPanelSpec.builder("panel").title(IKey.raw("Panel")).icon(ICON).build(),
            () -> content,
            (failed, phase, error) -> failures.add(phase + ":" + error.getClass().getSimpleName()),
            () -> {}
        );
        UIExtensionDashboardPanel panel;

        try
        {
            panel = new UIExtensionDashboardPanel(null, contribution);
        }
        catch (Exception error)
        {
            throw new AssertionError("valid extension content failed to mount", error);
        }

        panel.open();
        panel.appear();
        panel.update();
        panel.disappear();
        panel.close();

        check(callbacks.equals(List.of("open", "appear", "update", "disappear", "close")),
            "one failed callback prevented later lifecycle callbacks: " + callbacks);
        check(failures.equals(List.of("update:IllegalStateException")),
            "callback failure was not reported exactly once: " + failures);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TrackingPanel extends UIDashboardPanel
    {
        private final String name;
        private final List<String> events;

        private TrackingPanel(String name, List<String> events)
        {
            super(null);
            this.name = name;
            this.events = events;
        }

        @Override
        public void open()
        {
            this.events.add(this.name + ".open");
        }

        @Override
        public void appear()
        {
            this.events.add(this.name + ".appear");
        }

        @Override
        public void update()
        {
            this.events.add(this.name + ".update");
        }

        @Override
        public void disappear()
        {
            this.events.add(this.name + ".disappear");
        }

        @Override
        public void close()
        {
            this.events.add(this.name + ".close");
        }
    }
}
