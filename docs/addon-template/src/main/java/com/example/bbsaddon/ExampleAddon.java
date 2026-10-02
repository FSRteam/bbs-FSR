package com.example.bbsaddon;

import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonRegistrationContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * The addon itself: what it is, and the one thing it registers with BBS.
 *
 * <p>Only {@link #descriptor()} is mandatory. {@code register} is called inside BBS's registration
 * window, and everything this addon adds to BBS belongs there — a facade kept past the callback is
 * rejected rather than silently accepted.</p>
 */
public final class ExampleAddon implements BBSAddon
{
    /**
     * The addon id. It is also the NeoForge mod id in {@code neoforge.mods.toml}, the descriptor's
     * id string and the settings module's id, so those four cannot drift apart.
     */
    public static final String ADDON_ID = "exampleaddon";

    /**
     * The addon API version this addon is written against. Checked by
     * {@link ExampleAddonNeoForge} before anything else runs.
     */
    public static final int REQUIRED_API_VERSION = 2;

    /**
     * The descriptor the bootstrap hands to BBS. It is built once, here, rather than in
     * {@link #descriptor()}, so the bootstrap can pass it explicitly and stay readable.
     */
    public static final BBSAddonDescriptor DESCRIPTOR = BBSAddonDescriptor.builder(ADDON_ID)
        .displayName("Example Addon")
        .addonVersion("1.0.0")
        .capability(BBSAddonCapability.SETTINGS)
        /* BBS itself. The loader already enforces this through neoforge.mods.toml, and naming it
         * here makes BBS's own descriptor validator check it as well — for a required mod other
         * than BBS, that is the check that turns "the addon half-loaded" into "the addon was
         * refused, with the missing mod named in the diagnostics". */
        .requiredMod("bbs")
        .build();

    @Override
    public BBSAddonDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    /**
     * Called once, on both sides, while BBS accepts structural registration.
     *
     * <p>The facade used here is the one this skeleton demonstrates. Every other one —
     * {@code resources()}, {@code forms()}, {@code clips()}, {@code particles()},
     * {@code network()}, {@code events()} — has the same shape: it checks the matching
     * {@link BBSAddonCapability}, and returns a result whose {@code accepted()} says whether the
     * registration actually went in. The recipes are in {@code docs/addon-api-2.md}.</p>
     */
    @Override
    public void register(BBSAddonRegistrationContext context)
    {
        context.settings().register(Icons.GEAR, ADDON_ID, (builder) ->
        {
            builder.category("general", Icons.GEAR);
            builder.getBoolean("enabled", true);
            builder.getFloat("wobble_scale", 1F, 0F, 10F);
        });
    }
}
