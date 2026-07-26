package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.api.plugin.BBSPlugin;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginStopReason;
import mchorse.bbs_mod.plugin.content.PluginContentSnapshot;
import mchorse.bbs_mod.plugin.runtime.PluginCallbackScope;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

final class PluginRuntimeGeneration implements AutoCloseable
{
    private final PluginOwner owner;
    private final BBSPluginDescriptor descriptor;
    private final String artifactHash;
    private final PluginContentSnapshot content;
    private final PluginContributionLedger ledger;
    private final PluginGenerationContext context;
    private final List<BBSPlugin> entrypoints;
    private Map<PluginEventRoute, Consumer<Object>> eventRoutes = Map.of();
    private ClassLoader classLoader;
    private int startedEntrypoints;
    private boolean closed;

    PluginRuntimeGeneration(
        PluginOwner owner,
        BBSPluginDescriptor descriptor,
        String artifactHash,
        PluginContentSnapshot content,
        PluginContributionLedger ledger,
        PluginGenerationContext context,
        List<BBSPlugin> entrypoints,
        ClassLoader classLoader
    )
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.artifactHash = Objects.requireNonNull(artifactHash, "artifactHash");
        this.content = content;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.context = Objects.requireNonNull(context, "context");
        this.entrypoints = new ArrayList<>(Objects.requireNonNull(entrypoints, "entrypoints"));
        this.classLoader = classLoader;
    }

    PluginOwner owner()
    {
        return this.owner;
    }

    BBSPluginDescriptor descriptor()
    {
        return this.descriptor;
    }

    String artifactHash()
    {
        return this.artifactHash;
    }

    PluginContentSnapshot content()
    {
        return this.content;
    }

    Map<PluginEventRoute, Consumer<Object>> eventRoutes()
    {
        return this.eventRoutes;
    }

    PluginStructuralRegistrationWindow structuralRegistrations()
    {
        return this.context.structuralRegistrations();
    }

    void prepareAndStart() throws Exception
    {
        for (BBSPlugin entrypoint : this.entrypoints)
        {
            this.invoke(() -> entrypoint.prepare(this.context));
        }

        this.context.sealStructuralRegistrations();

        for (BBSPlugin entrypoint : this.entrypoints)
        {
            this.invoke(entrypoint::start);
            this.startedEntrypoints += 1;
        }

        this.eventRoutes = this.context.sealEvents();
        this.ledger.seal();
    }

    void dispatch(PluginEventRoute route, Object event)
    {
        Consumer<Object> callback = this.eventRoutes.get(route);

        if (callback == null)
        {
            return;
        }

        try
        {
            this.invoke(() -> callback.accept(event));
        }
        catch (Throwable error)
        {
            throw new PluginCallbackException(this.owner, route, error);
        }
    }

    void stop(BBSPluginStopReason reason) throws Exception
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;
        Throwable failure = null;

        if (this.startedEntrypoints > 0)
        {
            for (int i = this.startedEntrypoints - 1; i >= 0; i -= 1)
            {
                BBSPlugin entrypoint = this.entrypoints.get(i);

                try
                {
                    this.invoke(() -> entrypoint.stop(reason));
                }
                catch (Throwable error)
                {
                    failure = append(failure, error);
                }
            }
        }

        try
        {
            this.ledger.close();
        }
        catch (Throwable error)
        {
            failure = append(failure, error);
        }

        this.eventRoutes = Map.of();
        this.entrypoints.clear();
        ClassLoader loader = this.classLoader;
        this.classLoader = null;

        if (loader instanceof AutoCloseable closeable)
        {
            try
            {
                closeable.close();
            }
            catch (Throwable error)
            {
                failure = append(failure, error);
            }
        }

        if (failure != null)
        {
            if (failure instanceof Exception exception)
            {
                throw exception;
            }

            if (failure instanceof Error error)
            {
                throw error;
            }

            throw new RuntimeException(failure);
        }
    }

    @Override
    public void close() throws Exception
    {
        this.stop(BBSPluginStopReason.SHUTDOWN);
    }

    private void invoke(PluginCallbackScopeCallback callback) throws Exception
    {
        if (this.classLoader == null)
        {
            callback.run();
            return;
        }

        try
        {
            PluginCallbackScope.call(this.classLoader, () ->
            {
                callback.run();
                return null;
            });
        }
        catch (RuntimeException error)
        {
            Throwable cause = error.getCause();

            if (cause instanceof Exception exception)
            {
                throw exception;
            }

            throw error;
        }
    }

    private static Throwable append(Throwable failure, Throwable next)
    {
        if (failure == null)
        {
            return next;
        }

        if (failure != next)
        {
            failure.addSuppressed(next);
        }

        return failure;
    }

    @FunctionalInterface
    private interface PluginCallbackScopeCallback
    {
        void run() throws Exception;
    }

    static final class PluginCallbackException extends RuntimeException
    {
        PluginCallbackException(PluginOwner owner, PluginEventRoute route, Throwable cause)
        {
            super("Plugin callback failed for " + owner + " route=" + route, cause);
        }
    }
}
