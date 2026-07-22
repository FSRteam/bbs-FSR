package mchorse.bbs_mod.plugin.runtime;

final class PluginFailures
{
    private PluginFailures()
    {}

    static Throwable append(Throwable failure, Throwable addition)
    {
        if (failure == null)
        {
            return addition;
        }

        if (failure != addition)
        {
            failure.addSuppressed(addition);
        }

        return failure;
    }

    static void throwIfPresent(PluginOwner owner, String operation, Throwable failure)
    {
        throwIfPresent("Plugin " + owner + " failed while " + operation, failure);
    }

    static void throwIfPresent(String message, Throwable failure)
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

        throw new PluginCleanupException(
            message,
            failure
        );
    }
}
