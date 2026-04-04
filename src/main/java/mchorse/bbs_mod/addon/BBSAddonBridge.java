package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility to expose collector lifecycle to the core mod without entangling with Fabric/NeoForge specifics.
 */
public final class BBSAddonBridge
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonBridge.class);

    private final BBSAddonCollector collector;

    public BBSAddonBridge(BBSAddonCollector collector)
    {
        this.collector = collector;
    }

    public BBSAddonCollector getCollector()
    {
        return this.collector;
    }

    /**
     * Bridge collected addons into the internal EventBus.
     */
    public void bridgeToInternalBus(EventBus bus)
    {
        if (this.collector == null)
        {
            return;
        }

        this.collector.bridgeTo(bus);
        LOGGER.info("[bbs-addon] bridged {} addon(s) into internal bus", this.collector.getAddons().size());
    }
}
