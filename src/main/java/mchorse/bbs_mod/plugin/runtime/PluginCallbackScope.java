package mchorse.bbs_mod.plugin.runtime;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Executes callbacks with the generation classloader as the temporary TCCL. */
public final class PluginCallbackScope
{
    private PluginCallbackScope()
    {}

    public static void run(ClassLoader classLoader, Runnable callback)
    {
        Objects.requireNonNull(callback, "callback");
        call(classLoader, () ->
        {
            callback.run();
            return null;
        });
    }

    public static <T> T call(ClassLoader classLoader, Callable<T> callback)
    {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(callback, "callback");

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        Throwable failure = null;
        T result = null;

        try
        {
            thread.setContextClassLoader(classLoader);
            result = callback.call();
        }
        catch (Throwable throwable)
        {
            failure = throwable;
        }
        finally
        {
            try
            {
                thread.setContextClassLoader(previous);
            }
            catch (Throwable restoreFailure)
            {
                failure = PluginFailures.append(failure, restoreFailure);
            }
        }

        throwCallbackFailure(failure);

        return result;
    }

    public static Runnable guarded(
        PluginOwner owner,
        ClassLoader classLoader,
        PluginGenerationFence fence,
        PluginCallbackErrorHandler errorHandler,
        Runnable callback
    )
    {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(errorHandler, "errorHandler");
        Objects.requireNonNull(callback, "callback");

        if (!owner.equals(fence.owner()))
        {
            throw new IllegalArgumentException("Callback owner does not match generation fence");
        }

        return () ->
        {
            try
            {
                fence.runIfOpen(() -> run(classLoader, callback));
            }
            catch (Throwable throwable)
            {
                report(errorHandler, owner, throwable);

                if (isFatal(throwable))
                {
                    throw (Error) throwable;
                }
            }
        };
    }

    static void report(
        PluginCallbackErrorHandler errorHandler,
        PluginOwner owner,
        Throwable throwable
    )
    {
        try
        {
            errorHandler.onFailure(owner, throwable);
        }
        catch (Throwable diagnosticFailure)
        {
            if (diagnosticFailure != throwable)
            {
                throwable.addSuppressed(diagnosticFailure);
            }
        }
    }

    static boolean isFatal(Throwable throwable)
    {
        return throwable instanceof VirtualMachineError;
    }

    private static void throwCallbackFailure(Throwable failure)
    {
        if (failure == null)
        {
            return;
        }

        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }

        if (failure instanceof Error error)
        {
            throw error;
        }

        throw new PluginCallbackException("Plugin callback failed", failure);
    }
}
