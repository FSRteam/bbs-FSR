package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlaySubscription;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-aware registry for one Dashboard guide overlay per addon. */
public final class BBSDashboardOverlayHostRegistry
{
    private static final Map<String, DashboardOverlayContribution> CONTRIBUTIONS = new LinkedHashMap<>();

    private BBSDashboardOverlayHostRegistry() {}

    public static BBSRegistrationResult install(DashboardOverlayContribution contribution)
    {
        synchronized (BBSDashboardOverlayHostRegistry.class)
        {
            DashboardOverlayContribution existing = CONTRIBUTIONS.get(contribution.ownerId());

            if (existing != null)
            {
                return BBSRegistrationResult.duplicate(contribution.fullId(), existing.ownerDescription());
            }

            CONTRIBUTIONS.put(contribution.ownerId(), contribution);
        }

        try
        {
            projectCurrent(contribution, Projection.INSTALL);
            return BBSRegistrationResult.accepted(contribution.fullId());
        }
        catch (Throwable error)
        {
            rollback(contribution);
            contribution.reportFailure("factory", error);

            return BBSRegistrationResult.rejected(contribution.fullId(), "Dashboard overlay projection failed");
        }
    }

    public static BBSDashboardOverlaySubscription subscription(
        BBSRegistrationResult registration,
        DashboardOverlayContribution contribution
    )
    {
        return new Subscription(registration, registration.accepted() ? contribution : null);
    }

    public static void setVisible(DashboardOverlayContribution contribution, boolean visible)
    {
        if (!isCurrent(contribution))
        {
            return;
        }

        contribution.setVisible(visible);
        projectCurrent(contribution, Projection.VISIBILITY);
    }

    public static void remove(DashboardOverlayContribution contribution)
    {
        synchronized (BBSDashboardOverlayHostRegistry.class)
        {
            if (CONTRIBUTIONS.get(contribution.ownerId()) != contribution)
            {
                return;
            }

            CONTRIBUTIONS.remove(contribution.ownerId());
        }

        projectCurrent(contribution, Projection.REMOVE);
    }

    public static void installAll(UIDashboard dashboard)
    {
        for (DashboardOverlayContribution contribution : snapshot())
        {
            try
            {
                dashboard.installDashboardOverlay(contribution);
                contribution.reportMounted();
            }
            catch (Throwable error)
            {
                rollback(contribution);
                contribution.reportFailure("factory", error);
            }
        }
    }

    static synchronized List<DashboardOverlayContribution> snapshot()
    {
        return new ArrayList<>(CONTRIBUTIONS.values());
    }

    static synchronized void clearForTests()
    {
        CONTRIBUTIONS.clear();
    }

    private static void projectCurrent(DashboardOverlayContribution contribution, Projection projection)
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null)
        {
            return;
        }

        Runnable action = () ->
        {
            if (BBSModClient.getDashboardIfCreated() != dashboard)
            {
                return;
            }

            if (projection == Projection.REMOVE)
            {
                dashboard.removeDashboardOverlay(contribution);
            }
            else if (projection == Projection.INSTALL && isCurrent(contribution))
            {
                try
                {
                    dashboard.installDashboardOverlay(contribution);
                    contribution.reportMounted();
                }
                catch (Exception error)
                {
                    throw new IllegalStateException(
                        "Dashboard overlay factory failed for " + contribution.fullId(),
                        error
                    );
                }
            }
            else if (projection == Projection.VISIBILITY && isCurrent(contribution))
            {
                dashboard.setDashboardOverlayVisible(contribution, contribution.visible());
            }
        };
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.isSameThread())
        {
            action.run();
            return;
        }

        minecraft.execute(() ->
        {
            try
            {
                action.run();
            }
            catch (Throwable error)
            {
                contribution.reportFailure(projection.phase, error);

                if (projection == Projection.INSTALL)
                {
                    rollback(contribution);
                    dashboard.removeDashboardOverlay(contribution);
                }
            }
        });
    }

    private static synchronized boolean isCurrent(DashboardOverlayContribution contribution)
    {
        return CONTRIBUTIONS.get(contribution.ownerId()) == contribution;
    }

    private static synchronized void rollback(DashboardOverlayContribution contribution)
    {
        CONTRIBUTIONS.remove(contribution.ownerId(), contribution);
    }

    private enum Projection
    {
        INSTALL("projection"),
        VISIBILITY("visibility"),
        REMOVE("unregister");

        private final String phase;

        Projection(String phase)
        {
            this.phase = phase;
        }
    }

    private static final class Subscription implements BBSDashboardOverlaySubscription
    {
        private final BBSRegistrationResult registration;
        private final DashboardOverlayContribution contribution;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(BBSRegistrationResult registration, DashboardOverlayContribution contribution)
        {
            this.registration = registration;
            this.contribution = contribution;
        }

        @Override
        public BBSRegistrationResult registration()
        {
            return this.registration;
        }

        @Override
        public boolean visible()
        {
            return this.contribution != null && !this.closed.get() && this.contribution.visible();
        }

        @Override
        public void setVisible(boolean visible)
        {
            if (this.contribution != null && !this.closed.get())
            {
                BBSDashboardOverlayHostRegistry.setVisible(this.contribution, visible);
            }
        }

        @Override
        public void close()
        {
            if (this.contribution != null && this.closed.compareAndSet(false, true))
            {
                BBSDashboardOverlayHostRegistry.remove(this.contribution);
            }
        }
    }
}
