package mchorse.bbs_mod.client.dashboard;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelFactory;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelSpec;

import java.util.Objects;
import java.util.regex.Pattern;

/** Exact owner identity and factory retained for one active panel contribution. */
public final class DashboardPanelContribution
{
    private static final Pattern OWNER_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    private final String ownerId;
    private final Object ownerIdentity;
    private final String ownerDescription;
    private final String fullId;
    private final BBSDashboardPanelSpec spec;
    private final BBSDashboardPanelFactory factory;
    private final FailureHandler failureHandler;
    private final Runnable mountedHandler;

    public DashboardPanelContribution(
        String ownerId,
        Object ownerIdentity,
        String ownerDescription,
        BBSDashboardPanelSpec spec,
        BBSDashboardPanelFactory factory,
        FailureHandler failureHandler,
        Runnable mountedHandler
    )
    {
        String normalizedOwner = Objects.requireNonNull(ownerId, "ownerId").trim();

        if (!OWNER_ID.matcher(normalizedOwner).matches())
        {
            throw new IllegalArgumentException("Dashboard panel owner id must match " + OWNER_ID.pattern());
        }

        this.ownerId = normalizedOwner;
        this.ownerIdentity = Objects.requireNonNull(ownerIdentity, "ownerIdentity");
        this.ownerDescription = ownerDescription == null || ownerDescription.isBlank()
            ? normalizedOwner
            : ownerDescription;
        this.spec = Objects.requireNonNull(spec, "spec");

        if (!BBSDashboardPanelSpec.isValidId(spec.id()))
        {
            throw new IllegalArgumentException("Dashboard panel id is invalid");
        }

        Objects.requireNonNull(spec.title(), "spec.title");
        Objects.requireNonNull(spec.icon(), "spec.icon");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.mountedHandler = Objects.requireNonNull(mountedHandler, "mountedHandler");
        this.fullId = normalizedOwner + ":" + spec.id();
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

    public BBSDashboardPanelSpec spec()
    {
        return this.spec;
    }

    public BBSDashboardPanelFactory factory()
    {
        return this.factory;
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
        void onFailure(DashboardPanelContribution contribution, String phase, Throwable error);
    }
}
