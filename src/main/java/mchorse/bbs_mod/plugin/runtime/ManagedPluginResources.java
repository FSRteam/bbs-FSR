package mchorse.bbs_mod.plugin.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** Factory for resources that are automatically entered into a generation ledger. */
public final class ManagedPluginResources
{
    public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5L);

    private final PluginOwner owner;
    private final PluginContributionLedger ledger;
    private final PluginGenerationFence fence;
    private volatile ClassLoader callbackClassLoader;
    private final PluginCallbackErrorHandler errorHandler;

    public ManagedPluginResources(
        PluginOwner owner,
        PluginContributionLedger ledger,
        PluginGenerationFence fence,
        ClassLoader callbackClassLoader,
        PluginCallbackErrorHandler errorHandler
    )
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.fence = Objects.requireNonNull(fence, "fence");
        this.callbackClassLoader = Objects.requireNonNull(callbackClassLoader, "callbackClassLoader");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");

        if (!owner.equals(ledger.owner()) || !owner.equals(fence.owner()))
        {
            throw new IllegalArgumentException("Managed resource owner does not match ledger/fence");
        }

        this.ledger.own("managed-callback-classloader", () -> this.callbackClassLoader = null);
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public PluginLease own(AutoCloseable resource)
    {
        Objects.requireNonNull(resource, "resource");
        return this.own(resource.getClass().getSimpleName(), resource);
    }

    public PluginLease own(String description, AutoCloseable resource)
    {
        Objects.requireNonNull(resource, "resource");

        return this.ledger.own(
            description == null || description.isBlank() ? "resource" : description,
            () ->
            {
                ClassLoader classLoader = this.callbackClassLoader;

                if (classLoader == null)
                {
                    resource.close();
                    return;
                }

                PluginCallbackScope.call(classLoader, () ->
                {
                    resource.close();
                    return null;
                });
            }
        );
    }

    public ManagedPluginExecutor singleExecutor(String role)
    {
        return this.singleExecutor(role, DEFAULT_CLOSE_TIMEOUT);
    }

    public ManagedPluginExecutor singleExecutor(String role, Duration closeTimeout)
    {
        ManagedPluginExecutor executor = new ManagedPluginExecutor(
            this.owner,
            this.fence,
            this.callbackClassLoader,
            Executors.newSingleThreadExecutor(
                new PluginThreadFactory(this.owner, role, this.errorHandler)
            ),
            closeTimeout,
            this.errorHandler
        );

        this.ledger.own("executor:" + role, executor);
        return executor;
    }

    public ManagedPluginExecutor fixedExecutor(String role, int parallelism)
    {
        return this.fixedExecutor(role, parallelism, DEFAULT_CLOSE_TIMEOUT);
    }

    public ManagedPluginExecutor fixedExecutor(String role, int parallelism, Duration closeTimeout)
    {
        if (parallelism < 1)
        {
            throw new IllegalArgumentException("Executor parallelism must be positive");
        }

        ManagedPluginExecutor executor = new ManagedPluginExecutor(
            this.owner,
            this.fence,
            this.callbackClassLoader,
            Executors.newFixedThreadPool(
                parallelism,
                new PluginThreadFactory(this.owner, role, this.errorHandler)
            ),
            closeTimeout,
            this.errorHandler
        );

        this.ledger.own("executor:" + role, executor);
        return executor;
    }

    public ManagedPluginScheduledExecutor scheduler(String role, int parallelism)
    {
        return this.scheduler(role, parallelism, DEFAULT_CLOSE_TIMEOUT);
    }

    public ManagedPluginScheduledExecutor scheduler(
        String role,
        int parallelism,
        Duration closeTimeout
    )
    {
        if (parallelism < 1)
        {
            throw new IllegalArgumentException("Scheduler parallelism must be positive");
        }

        ScheduledThreadPoolExecutor delegate = new ScheduledThreadPoolExecutor(
            parallelism,
            new PluginThreadFactory(this.owner, role, this.errorHandler)
        );
        delegate.setRemoveOnCancelPolicy(true);
        delegate.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        delegate.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        ManagedPluginScheduledExecutor scheduler = new ManagedPluginScheduledExecutor(
            this.owner,
            this.fence,
            this.callbackClassLoader,
            delegate,
            closeTimeout,
            this.errorHandler
        );

        this.ledger.own("scheduler:" + role, scheduler);
        return scheduler;
    }

    public Runnable guarded(Runnable callback)
    {
        Objects.requireNonNull(callback, "callback");

        return () ->
        {
            ClassLoader classLoader = this.callbackClassLoader;

            if (classLoader == null)
            {
                return;
            }

            PluginCallbackScope.guarded(
                this.owner,
                classLoader,
                this.fence,
                this.errorHandler,
                callback
            ).run();
        };
    }
}
