package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Active owner-aware Dashboard contributions shared by addons and hot plugins. */
public final class BBSDashboardPanelHostRegistry
{
    private static final Map<String, DashboardPanelContribution> CONTRIBUTIONS = new LinkedHashMap<>();
    private static final List<DashboardPanelContribution> DEFERRED_REMOVALS = new ArrayList<>();
    private static int projectionBatchDepth;

    private BBSDashboardPanelHostRegistry() {}

    public static synchronized BBSRegistrationResult preflight(
        DashboardPanelContribution contribution,
        boolean replace
    )
    {
        DashboardPanelContribution existing = CONTRIBUTIONS.get(contribution.fullId());

        if (existing != null && (!replace || !existing.ownerId().equals(contribution.ownerId())))
        {
            return BBSRegistrationResult.duplicate(contribution.fullId(), existing.ownerDescription());
        }

        return BBSRegistrationResult.accepted(contribution.fullId());
    }

    public static BBSRegistrationResult install(DashboardPanelContribution contribution, boolean replace)
    {
        DashboardPanelContribution previous;
        BBSRegistrationResult preflight;

        synchronized (BBSDashboardPanelHostRegistry.class)
        {
            preflight = preflight(contribution, replace);

            if (!preflight.accepted())
            {
                return preflight;
            }

            previous = CONTRIBUTIONS.put(contribution.fullId(), contribution);
        }

        try
        {
            projectCurrent(contribution, false);
            return preflight;
        }
        catch (Throwable error)
        {
            synchronized (BBSDashboardPanelHostRegistry.class)
            {
                if (CONTRIBUTIONS.get(contribution.fullId()) == contribution)
                {
                    if (previous == null)
                    {
                        CONTRIBUTIONS.remove(contribution.fullId());
                    }
                    else
                    {
                        CONTRIBUTIONS.put(contribution.fullId(), previous);
                    }
                }
            }

            contribution.reportFailure("factory", error);

            if (error instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            if (error instanceof Error fatal)
            {
                throw fatal;
            }

            throw new IllegalStateException("Dashboard panel projection failed for " + contribution.fullId(), error);
        }
    }

    public static void remove(DashboardPanelContribution contribution)
    {
        boolean deferProjection;

        synchronized (BBSDashboardPanelHostRegistry.class)
        {
            if (CONTRIBUTIONS.get(contribution.fullId()) != contribution)
            {
                return;
            }

            CONTRIBUTIONS.remove(contribution.fullId());
            deferProjection = projectionBatchDepth > 0;

            if (deferProjection)
            {
                DEFERRED_REMOVALS.add(contribution);
            }
        }

        if (!deferProjection)
        {
            projectCurrent(contribution, true);
        }
    }

    public static synchronized void beginProjectionBatch()
    {
        projectionBatchDepth += 1;
    }

    public static void endProjectionBatch()
    {
        List<DashboardPanelContribution> removals;

        synchronized (BBSDashboardPanelHostRegistry.class)
        {
            if (projectionBatchDepth <= 0)
            {
                throw new IllegalStateException("Dashboard panel projection batch is not open");
            }

            projectionBatchDepth -= 1;

            if (projectionBatchDepth > 0)
            {
                return;
            }

            removals = List.copyOf(DEFERRED_REMOVALS);
            DEFERRED_REMOVALS.clear();
        }

        for (DashboardPanelContribution contribution : removals)
        {
            /* A failed candidate can reactivate the incumbent inside the same
             * structural transaction. Do not apply its earlier queued removal. */
            if (!isCurrent(contribution))
            {
                projectCurrent(contribution, true);
            }
        }
    }

    public static void installAll(UIDashboard dashboard)
    {
        for (DashboardPanelContribution contribution : snapshot())
        {
            try
            {
                dashboard.installDashboardPanel(contribution);
                contribution.reportMounted();
            }
            catch (Throwable error)
            {
                contribution.reportFailure("factory", error);
            }
        }
    }

    static synchronized List<DashboardPanelContribution> snapshot()
    {
        return new ArrayList<>(CONTRIBUTIONS.values());
    }

    static synchronized void clearForTests()
    {
        CONTRIBUTIONS.clear();
        DEFERRED_REMOVALS.clear();
        projectionBatchDepth = 0;
    }

    private static void projectCurrent(
        DashboardPanelContribution contribution,
        boolean remove
    )
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null)
        {
            return;
        }

        Runnable projection = () ->
        {
            if (BBSModClient.getDashboardIfCreated() != dashboard)
            {
                return;
            }

            if (remove)
            {
                dashboard.removeDashboardPanel(contribution);
            }
            else if (isCurrent(contribution))
            {
                try
                {
                    dashboard.installDashboardPanel(contribution);
                    contribution.reportMounted();
                }
                catch (Exception error)
                {
                    throw new IllegalStateException("Dashboard panel factory failed for " + contribution.fullId(), error);
                }
            }
        };
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.isSameThread())
        {
            projection.run();
            return;
        }

        minecraft.execute(() ->
        {
            try
            {
                projection.run();
            }
            catch (Throwable error)
            {
                contribution.reportFailure("projection", error);
            }
        });

    }

    private static synchronized boolean isCurrent(DashboardPanelContribution contribution)
    {
        return CONTRIBUTIONS.get(contribution.fullId()) == contribution;
    }
}
