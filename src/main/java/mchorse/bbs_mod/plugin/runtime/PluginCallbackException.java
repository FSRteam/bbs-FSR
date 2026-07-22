package mchorse.bbs_mod.plugin.runtime;

/** Wraps a checked exception crossing a host-managed plugin callback boundary. */
public final class PluginCallbackException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public PluginCallbackException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
