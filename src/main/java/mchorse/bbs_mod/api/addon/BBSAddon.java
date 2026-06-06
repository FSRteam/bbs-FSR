package mchorse.bbs_mod.api.addon;

/**
 * API 2.0 addon contract.
 *
 * <p>All callbacks are optional except {@link #descriptor()}. BBS wraps every
 * callback in an execution boundary so one addon cannot crash core startup.</p>
 */
public interface BBSAddon
{
    BBSAddonDescriptor descriptor();

    default void discover(BBSAddonContext context) {}

    default void register(BBSAddonRegistrationContext context) {}

    default void setup(BBSAddonSetupContext context) {}

    default void unload(BBSAddonContext context) {}
}
