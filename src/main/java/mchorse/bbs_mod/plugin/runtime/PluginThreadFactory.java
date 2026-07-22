package mchorse.bbs_mod.plugin.runtime;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class PluginThreadFactory implements ThreadFactory
{
    private final PluginOwner owner;
    private final String role;
    private final PluginCallbackErrorHandler errorHandler;
    private final ClassLoader idleClassLoader;
    private final AtomicInteger sequence = new AtomicInteger();

    PluginThreadFactory(
        PluginOwner owner,
        String role,
        PluginCallbackErrorHandler errorHandler
    )
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.role = sanitize(role);
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.idleClassLoader = PluginThreadFactory.class.getClassLoader();
    }

    @Override
    public Thread newThread(Runnable runnable)
    {
        Thread thread = new Thread(
            runnable,
            "bbs-plugin-" + sanitize(this.owner.pluginId()) + "-g" + this.owner.generation()
                + "-" + this.role + "-" + this.sequence.incrementAndGet()
        );

        thread.setDaemon(true);
        thread.setContextClassLoader(this.idleClassLoader);
        thread.setUncaughtExceptionHandler(
            (failedThread, throwable) -> PluginCallbackScope.report(
                this.errorHandler,
                this.owner,
                throwable
            )
        );

        return thread;
    }

    private static String sanitize(String value)
    {
        if (value == null || value.isBlank())
        {
            return "worker";
        }

        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
