package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.loader.LoaderAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only protocol checks for addon registration behavior.
 */
public final class BBSAddonProtocolSelfCheck
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonProtocolSelfCheck.class);

    private BBSAddonProtocolSelfCheck()
    {}

    public static void run(LoaderAccess loader, BBSAddonCollector collector)
    {
        if (loader == null || collector == null || !loader.isDevelopmentEnvironment())
        {
            return;
        }

        int resolvedCount = loader.getEntrypoints("bbs-addon", BBSAddonMod.class).size();
        LOGGER.info("[bbs-addon-selfcheck] resolved {} registered addon(s) from LoaderAccess", resolvedCount);

        // Trigger warn-once path for unsupported keys.
        loader.getEntrypoints("bbs-addon-selfcheck-unsupported", BBSAddonMod.class);
        loader.getEntrypoints("bbs-addon-selfcheck-unsupported", BBSAddonMod.class);

        // The registration window is expected to be closed by common setup.
        boolean lateAccepted = collector.register("bbs-addon-selfcheck-late", new BBSAddonMod() {});
        if (lateAccepted)
        {
            LOGGER.error("[bbs-addon-selfcheck] unexpected late registration acceptance");
        }
        else
        {
            LOGGER.info("[bbs-addon-selfcheck] late registration rejection OK");
        }

        // Duplicate id policy check: first wins, second is rejected.
        BBSAddonCollector duplicateCollector = new BBSAddonCollector();
        boolean firstAccepted = duplicateCollector.register("bbs-addon-selfcheck-duplicate", new BBSAddonMod() {});
        boolean secondAccepted = duplicateCollector.register("bbs-addon-selfcheck-duplicate", new BBSAddonMod() {});

        if (firstAccepted && !secondAccepted)
        {
            LOGGER.info("[bbs-addon-selfcheck] duplicate registration policy OK (first wins)");
        }
        else
        {
            LOGGER.error(
                "[bbs-addon-selfcheck] duplicate registration policy mismatch (firstAccepted={}, secondAccepted={})",
                firstAccepted,
                secondAccepted
            );
        }
    }
}
