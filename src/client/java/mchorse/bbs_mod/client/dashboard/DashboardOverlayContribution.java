package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardOverlayFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Exact owner identity and factory retained for one active Dashboard overlay. */
public final class DashboardOverlayContribution
{
    private static final Pattern OWNER_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    private final String ownerId;
    private final Object ownerIdentity;
    private final String ownerDescription;
    private final String fullId;
    private final BBSDashboardOverlayFactory factory;
    private final FailureHandler failureHandler;
    private final Runnable mountedHandler;
    private final AtomicBoolean visible = new AtomicBoolean();

    public DashboardOverlayContribution(
        String ownerId,
        Object ownerIdentity,
        String ownerDescription,
        BBSDashboardOverlayFactory factory,
        FailureHandler failureHandler,
        Runnable mountedHandler
    )
    {
        String normalizedOwner = Objects.requireNonNull(ownerId, "ownerId").trim();

        if (!OWNER_ID.matcher(normalizedOwner).matches())
        {
            throw new IllegalArgumentException("Dashboard overlay owner id must match " + OWNER_ID.pattern());
        }

        this.ownerId = normalizedOwner;
        this.ownerIdentity = Objects.requireNonNull(ownerIdentity, "ownerIdentity");
        this.ownerDescription = ownerDescription == null || ownerDescription.isBlank()
            ? normalizedOwner
            : ownerDescription;
        this.fullId = normalizedOwner + ":dashboard_overlay";
        this.factory = Objects.requireNonNull(factory, "factory");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.mountedHandler = Objects.requireNonNull(mountedHandler, "mountedHandler");
    }

    public String ownerId()
    {
        return this.ownerId;
    }

    public Object ownerIdentity()
    {
        return this.ownerIdentity;
    }

    public String ownerDescription()
    {
        return this.ownerDescription;
    }

    public String fullId()
    {
        return this.fullId;
    }

    public BBSDashboardOverlayFactory factory()
    {
        return this.factory;
    }

    public boolean visible()
    {
        return this.visible.get();
    }

    public void setVisible(boolean visible)
    {
        this.visible.set(visible);
    }

    public void reportFailure(String phase, Throwable error)
    {
        try
        {
            this.failureHandler.onFailure(this, phase, error);
        }
        catch (Throwable ignored)
        {}
    }

    public void reportMounted()
    {
        try
        {
            this.mountedHandler.run();
        }
        catch (Throwable ignored)
        {}
    }

    @FunctionalInterface
    public interface FailureHandler
    {
        void onFailure(DashboardOverlayContribution contribution, String phase, Throwable error);
    }
}
