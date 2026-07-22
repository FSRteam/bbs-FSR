package mchorse.bbs_mod.api.plugin;

public enum BBSPluginState
{
    DISCOVERED,
    VALIDATED,
    PREPARING,
    STAGED,
    ACTIVE,
    RELOAD_PENDING,
    DRAINING,
    UNLOADING,
    LOGICALLY_UNLOADED,
    DISABLED,
    INCOMPATIBLE,
    FAILED,
    RESTART_REQUIRED
}
