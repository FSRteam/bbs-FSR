package mchorse.bbs_mod.addon.demo;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.addon.BBSAddonRegisterEvent;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
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
        BBSMod.registerAddon(
            BBSAddonDescriptor.builder("bbs-core-demo-api2-addon")
                .displayName("BBS Core Demo API 2 Addon")
                .addonVersion("1.0.0")
                .capability(BBSAddonCapability.SETTINGS)
                .build(),
            BBSAddonDemoApi2Mod::new
        );
    }

    private static void onAddonRegister(BBSAddonRegisterEvent event)
    {
        event.register("bbs-core-demo-addon", BBSAddonDemoMod::new);
        LOGGER.info("[bbs-addon-demo] registered demo addon via BBSAddonRegisterEvent");
    }
}
