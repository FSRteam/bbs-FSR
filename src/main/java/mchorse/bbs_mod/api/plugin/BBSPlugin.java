package mchorse.bbs_mod.api.plugin;

/**
 * Common-side entrypoint for an FSR hot plugin.
 *
 * <p>Each generation receives its own instance. The host calls {@link #prepare}
 * and {@link #start} while the incumbent generation is still active, with host
 * contributions staged behind the supplied context. The completed candidate
 * is then published atomically. Implementations must keep all installation
 * side effects reversible through that context.</p>
 */
public interface BBSPlugin
{
    default void prepare(BBSPluginContext context) throws Exception {}

    default void start() throws Exception {}

    default void stop(BBSPluginStopReason reason) throws Exception {}
}
