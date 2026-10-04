package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptorValidator;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchorResult;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchorStatus;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardNavigationResult;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardNavigationStatus;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Any-thread bridge for host-owned Dashboard navigation and anchor queries. */
public final class BBSDashboardGuideDispatcher
{
    private BBSDashboardGuideDispatcher() {}

    public static CompletableFuture<BBSDashboardNavigationResult> navigate(
        BBSAddonDescriptor descriptor,
        String panelId
    )
    {
        String issue = validateAccess(descriptor);

        if (issue != null)
        {
            return CompletableFuture.completedFuture(navigation(
                BBSDashboardNavigationStatus.REJECTED, panelId, issue
            ));
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null)
        {
            return CompletableFuture.completedFuture(navigation(
                BBSDashboardNavigationStatus.FAILED, panelId, "Minecraft client is not available"
            ));
        }

        return submit(
            minecraft::execute,
            () -> new MinecraftGuideTarget().navigate(panelId),
            (error) -> navigation(
                BBSDashboardNavigationStatus.FAILED,
                panelId,
                "Dashboard navigation failed: " + error.getClass().getName()
            )
        );
    }

    public static CompletableFuture<BBSDashboardAnchorResult> resolveAnchor(
        BBSAddonDescriptor descriptor,
        String anchorId
    )
    {
        String issue = validateAccess(descriptor);

        if (issue != null)
        {
            return CompletableFuture.completedFuture(anchor(
                BBSDashboardAnchorStatus.REJECTED, anchorId, issue
            ));
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null)
        {
            return CompletableFuture.completedFuture(anchor(
                BBSDashboardAnchorStatus.FAILED, anchorId, "Minecraft client is not available"
            ));
        }

        return submit(
            minecraft::execute,
            () -> new MinecraftGuideTarget().resolveAnchor(anchorId),
            (error) -> anchor(
                BBSDashboardAnchorStatus.FAILED,
                anchorId,
                "Dashboard anchor query failed: " + error.getClass().getName()
            )
        );
    }

    static CompletableFuture<BBSDashboardNavigationResult> navigateForTesting(
        BBSAddonDescriptor descriptor,
        String panelId,
        Executor executor,
        GuideTarget target
    )
    {
        String issue = validateAccess(descriptor);

        if (issue != null)
        {
            return CompletableFuture.completedFuture(navigation(
                BBSDashboardNavigationStatus.REJECTED, panelId, issue
            ));
        }

        return submit(
            executor,
            () -> target.navigate(panelId),
            (error) -> navigation(BBSDashboardNavigationStatus.FAILED, panelId, error.getClass().getName())
        );
    }

    static CompletableFuture<BBSDashboardAnchorResult> resolveAnchorForTesting(
        BBSAddonDescriptor descriptor,
        String anchorId,
        Executor executor,
        GuideTarget target
    )
    {
        String issue = validateAccess(descriptor);

        if (issue != null)
        {
            return CompletableFuture.completedFuture(anchor(
                BBSDashboardAnchorStatus.REJECTED, anchorId, issue
            ));
        }

        return submit(
            executor,
            () -> target.resolveAnchor(anchorId),
            (error) -> anchor(BBSDashboardAnchorStatus.FAILED, anchorId, error.getClass().getName())
        );
    }

    private static <T> CompletableFuture<T> submit(
        Executor executor,
        Supplier<T> action,
        java.util.function.Function<Throwable, T> failure
    )
    {
        CompletableFuture<T> future = new CompletableFuture<>();

        try
        {
            executor.execute(() ->
            {
                try
                {
                    future.complete(action.get());
                }
                catch (Exception | LinkageError error)
                {
                    future.complete(failure.apply(error));
                }
            });
        }
        catch (Exception | LinkageError error)
        {
            future.complete(failure.apply(error));
        }

        return future;
    }

    private static String validateAccess(BBSAddonDescriptor descriptor)
    {
        if (descriptor == null)
        {
            return "addon descriptor is null";
        }

        java.util.List<String> issues = BBSAddonDescriptorValidator.validate(descriptor, (id) -> true);

        if (!issues.isEmpty())
        {
            return issues.get(0);
        }
        if (!descriptor.capabilities().contains(BBSAddonCapability.CLIENT_UI))
        {
            return "addon did not declare CLIENT_UI capability";
        }

        return null;
    }

    private static BBSDashboardNavigationResult navigation(
        BBSDashboardNavigationStatus status,
        String panelId,
        String message
    )
    {
        return new BBSDashboardNavigationResult(status, panelId, message);
    }

    private static BBSDashboardAnchorResult anchor(
        BBSDashboardAnchorStatus status,
        String anchorId,
        String message
    )
    {
        return new BBSDashboardAnchorResult(status, anchorId, 0, 0, 0, 0, false, false, message);
    }

    interface GuideTarget
    {
        BBSDashboardNavigationResult navigate(String panelId);

        BBSDashboardAnchorResult resolveAnchor(String anchorId);
    }

    private static final class MinecraftGuideTarget implements GuideTarget
    {
        @Override
        public BBSDashboardNavigationResult navigate(String panelId)
        {
            UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

            if (dashboard == null)
            {
                return navigation(BBSDashboardNavigationStatus.DASHBOARD_NOT_CREATED, panelId,
                    "BBS Dashboard has not been created");
            }
            if (UIScreen.getCurrentMenu() != dashboard)
            {
                return navigation(BBSDashboardNavigationStatus.NOT_DASHBOARD_SCREEN, panelId,
                    "BBS Dashboard is not the current screen");
            }

            return dashboard.navigateDashboardPanel(panelId);
        }

        @Override
        public BBSDashboardAnchorResult resolveAnchor(String anchorId)
        {
            UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

            if (dashboard == null)
            {
                return anchor(BBSDashboardAnchorStatus.DASHBOARD_NOT_CREATED, anchorId,
                    "BBS Dashboard has not been created");
            }
            if (UIScreen.getCurrentMenu() != dashboard)
            {
                return anchor(BBSDashboardAnchorStatus.NOT_DASHBOARD_SCREEN, anchorId,
                    "BBS Dashboard is not the current screen");
            }

            return dashboard.resolveDashboardAnchor(anchorId);
        }
    }
}
