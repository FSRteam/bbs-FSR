package mchorse.bbs_mod.plugin.runtime;

/** Host-owned diagnostic boundary for exceptions raised by plugin callbacks. */
@FunctionalInterface
public interface PluginCallbackErrorHandler
{
    PluginCallbackErrorHandler IGNORE = (owner, throwable) -> {};

    void onFailure(PluginOwner owner, Throwable throwable);
}
