package mchorse.bbs_mod.plugin.watch;

/**
 * Whether the lifecycle controller should apply an emitted intent immediately.
 */
public enum PluginApplyMode
{
    AUTO_APPLY,
    MANUAL_APPLY,
    RELOAD_PENDING;

    public boolean shouldApply()
    {
        return this != RELOAD_PENDING;
    }
}
