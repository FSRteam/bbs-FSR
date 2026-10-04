package mchorse.bbs_mod.ui.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlayContent;
import mchorse.bbs_mod.client.dashboard.DashboardOverlayContribution;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

import java.util.ArrayList;
import java.util.List;

public final class DashboardOverlayLifecycleTest
{
    private DashboardOverlayLifecycleTest() {}

    public static void runAll()
    {
        blankOverlaySpacePassesThrough();
        factoryAndLifecycleFailuresAreContained();
    }

    private static void blankOverlaySpacePassesThrough()
    {
        UIElement host = new UIElement();
        ClickElement underlay = new ClickElement();
        ClickElement overlayButton = new ClickElement();
        UIElement contentRoot = new UIElement();
        UIContext context = new UIContext(null);

        host.area.set(0, 0, 100, 100);
        underlay.full(host);
        contentRoot.add(overlayButton);
        host.add(underlay);

        DashboardOverlayContribution contribution = contribution(
            "overlay_input_test",
            () -> BBSDashboardOverlayContent.of(contentRoot),
            new ArrayList<>()
        );
        contribution.setVisible(true);
        UIExtensionDashboardOverlay overlay;

        try
        {
            overlay = new UIExtensionDashboardOverlay(host, contribution);
        }
        catch (Exception error)
        {
            throw new AssertionError("valid overlay failed to mount", error);
        }

        host.resize();
        underlay.area.set(0, 0, 100, 100);
        overlay.area.set(0, 0, 100, 100);
        contentRoot.area.set(0, 0, 100, 100);
        overlayButton.area.set(0, 0, 20, 20);

        context.setMouse(50, 50, 0);
        check(host.mouseClicked(context) != null, "blank overlay click did not reach the underlay");
        check(underlay.clicks == 1 && overlayButton.clicks == 0,
            "blank overlay area consumed the underlay click");

        context.setMouse(10, 10, 0);
        check(host.mouseClicked(context) != null, "interactive overlay child did not consume its click");
        check(underlay.clicks == 1 && overlayButton.clicks == 1,
            "interactive overlay child leaked its click to the underlay");

        overlay.unmount();
        context.setMouse(10, 10, 0);
        host.mouseClicked(context);
        check(underlay.clicks == 2, "unregistered overlay left an input blocker behind");
    }

    private static void factoryAndLifecycleFailuresAreContained()
    {
        UIElement host = new UIElement();
        int initialChildren = host.getChildren().size();
        DashboardOverlayContribution factoryFailure = contribution(
            "overlay_factory_failure",
            () -> { throw new IllegalStateException("expected factory failure"); },
            new ArrayList<>()
        );

        try
        {
            new UIExtensionDashboardOverlay(host, factoryFailure);
            throw new AssertionError("throwing overlay factory was accepted");
        }
        catch (IllegalStateException expected)
        {}
        catch (Exception error)
        {
            throw new AssertionError("factory failure changed type", error);
        }

        check(host.getChildren().size() == initialChildren,
            "throwing factory left a wrapper in the overlay host");

        List<String> failures = new ArrayList<>();
        UIElement root = new UIElement();
        BBSDashboardOverlayContent content = new BBSDashboardOverlayContent()
        {
            @Override
            public UIElement root()
            {
                return root;
            }

            @Override
            public void onMounted()
            {
                throw new IllegalStateException("expected mounted failure");
            }

            @Override
            public void onUnmounted()
            {
                failures.add("unmounted");
            }
        };
        UIExtensionDashboardOverlay overlay;

        try
        {
            overlay = new UIExtensionDashboardOverlay(host,
                contribution("overlay_lifecycle_failure", () -> content, failures));
        }
        catch (Exception error)
        {
            throw new AssertionError("lifecycle callback failure escaped mounting", error);
        }

        overlay.unmount();
        check(failures.equals(List.of("mounted:IllegalStateException", "unmounted")),
            "overlay lifecycle failure was not isolated: " + failures);
        check(!overlay.hasParent(), "unmounted overlay wrapper remained attached");
    }

    private static DashboardOverlayContribution contribution(
        String ownerId,
        mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlayFactory factory,
        List<String> failures
    )
    {
        return new DashboardOverlayContribution(
            ownerId,
            new Object(),
            ownerId,
            factory,
            (failed, phase, error) -> failures.add(phase + ":" + error.getClass().getSimpleName()),
            () -> {}
        );
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class ClickElement extends UIElement
    {
        private int clicks;

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (!this.area.isInside(context))
            {
                return false;
            }

            this.clicks++;
            return true;
        }
    }
}
