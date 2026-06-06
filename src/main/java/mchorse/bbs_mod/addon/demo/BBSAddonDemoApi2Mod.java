package mchorse.bbs_mod.addon.demo;

import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonRegistrationContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only API 2.0 addon sample.
 */
public final class BBSAddonDemoApi2Mod implements BBSAddon
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSAddonDemoApi2Mod.class);
    private static final BBSAddonDescriptor DESCRIPTOR = BBSAddonDescriptor.builder("bbs-core-demo-api2-addon")
        .displayName("BBS Core Demo API 2 Addon")
        .addonVersion("1.0.0")
        .capability(BBSAddonCapability.SETTINGS)
        .build();

    @Override
    public BBSAddonDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public void register(BBSAddonRegistrationContext context)
    {
        context.diagnostics().info("API 2.0 demo addon register phase reached");
        context.settings().register(Icons.PROCESSOR, "bbs_api2_demo", (builder) ->
        {
            builder.category("general");
            builder.getBoolean("enabled", true);
        });

        LOGGER.info("[bbs-addon-demo] API 2.0 demo addon registered settings facade");
    }
}
