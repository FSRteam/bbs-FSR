package mchorse.bbs_mod.plugin.runtime;

/** Wraps a checked cleanup failure without hiding its original cause. */
public final class PluginCleanupException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public PluginCleanupException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
