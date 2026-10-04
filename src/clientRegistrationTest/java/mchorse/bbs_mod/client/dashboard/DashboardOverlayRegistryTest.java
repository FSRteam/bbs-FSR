package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlayContent;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlaySubscription;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.api.registry.BBSRegistrationStatus;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

public final class DashboardOverlayRegistryTest
{
    private DashboardOverlayRegistryTest() {}

    public static void runAll()
    {
        BBSDashboardOverlayHostRegistry.clearForTests();
        DashboardOverlayContribution first = contribution("overlay_registry_test");
        DashboardOverlayContribution duplicate = contribution("overlay_registry_test");
        BBSRegistrationResult accepted = BBSDashboardOverlayHostRegistry.install(first);
        BBSRegistrationResult rejected = BBSDashboardOverlayHostRegistry.install(duplicate);
        BBSDashboardOverlaySubscription subscription =
            BBSDashboardOverlayHostRegistry.subscription(accepted, first);

        check(accepted.status() == BBSRegistrationStatus.ACCEPTED,
            "valid overlay contribution was rejected");
        check(rejected.status() == BBSRegistrationStatus.DUPLICATE,
            "duplicate overlay contribution was not first-wins");
        check(!subscription.visible(), "new overlay subscription was not initially hidden");

        subscription.setVisible(true);
        check(subscription.visible(), "overlay subscription did not retain visible demand");
        subscription.close();
        subscription.close();

        check(BBSDashboardOverlayHostRegistry.snapshot().isEmpty(),
            "closed overlay subscription left a registry contribution behind");
        BBSDashboardOverlayHostRegistry.clearForTests();
    }

    private static DashboardOverlayContribution contribution(String ownerId)
    {
        return new DashboardOverlayContribution(
            ownerId,
            new Object(),
            ownerId,
            () -> BBSDashboardOverlayContent.of(new UIElement()),
            (failed, phase, error) -> {},
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
}
