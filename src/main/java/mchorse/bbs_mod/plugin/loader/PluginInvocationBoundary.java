package mchorse.bbs_mod.plugin.loader;

import java.util.concurrent.Callable;

/** Executes plugin code with its generation loader as the thread context loader. */
public final class PluginInvocationBoundary
{
    private PluginInvocationBoundary() {}

    public static void run(ClassLoader loader, ThrowingRunnable callback) throws Exception
    {
        call(loader, () ->
        {
            callback.run();
            return null;
        });
    }

    public static <T> T call(ClassLoader loader, Callable<T> callback) throws Exception
    {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();

        try
        {
            thread.setContextClassLoader(loader);
            return callback.call();
        }
        finally
        {
            thread.setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable
    {
        void run() throws Exception;
    }
}
