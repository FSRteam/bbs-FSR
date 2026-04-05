package mchorse.bbs_mod.addon.demo;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo addon used to prove collector -> bridge -> internal EventBus path.
 */
public class BBSAddonDemoMod implements BBSAddonMod
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonDemoMod.class);

    @Subscribe
    public void onRegisterSourcePacks(RegisterSourcePacksEvent event)
    {
        LOGGER.info("[bbs-addon-demo] internal EventBus bridge active (RegisterSourcePacksEvent received)");
    }

    @Subscribe
    public void onRegisterSettings(RegisterSettingsEvent event)
    {
        LOGGER.info("[bbs-addon-demo] internal EventBus bridge active (RegisterSettingsEvent received)");
    }
}
