package mchorse.bbs_mod.addon.demo;

import mchorse.bbs_mod.addon.BBSAddonRegisterEvent;
import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal sample addon registration using Mod Bus listener.
 */
public final class BBSAddonDemoBootstrap
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonDemoBootstrap.class);

    private BBSAddonDemoBootstrap()
    {}

    public static void bind(IEventBus modBus)
    {
        if (modBus == null)
        {
            return;
        }

        modBus.addListener(BBSAddonDemoBootstrap::onAddonRegister);
    }

    private static void onAddonRegister(BBSAddonRegisterEvent event)
    {
        event.register("bbs-core-demo-addon", BBSAddonDemoMod::new);
        LOGGER.info("[bbs-addon-demo] registered demo addon via BBSAddonRegisterEvent");
    }
}
