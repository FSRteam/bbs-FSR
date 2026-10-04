package com.example.bbsaddon;

import mchorse.bbs_mod.api.BBSApi;
import net.neoforged.fml.common.Mod;

/**
 * The NeoForge entry point.
 *
 * <p>On Fabric the equivalent of this class does not exist: BBS reads its own
 * {@code bbs-addon} / {@code bbs-client-addon} entrypoint lists from
 * {@code fabric.mod.json}. NeoForge has no such list, so the addon uses the entry point every mod
 * has — its {@code @Mod} constructor — and calls BBS from there.</p>
 */
@Mod(ExampleAddon.ADDON_ID)
public final class ExampleAddonNeoForge
{
    public ExampleAddonNeoForge()
    {
        /* Refuse to limp along on a BBS older than the API this addon was written against. The
         * message names the addon and both versions, so the failure reads as "this addon does not
         * fit this build" instead of as a missing method later on. */
        BBSApi.requireVersion(ExampleAddon.ADDON_ID, ExampleAddon.REQUIRED_API_VERSION);

        /* The descriptor plus a supplier to build the addon with is the API 2.0 registration path.
         * Arriving before BBS's managers are up is fine: the call is queued and replayed, so there
         * is no ordering to guess at beyond the mod dependency in neoforge.mods.toml. */
        BBSApi.registerAddon(ExampleAddon.DESCRIPTOR, ExampleAddon::new);
    }
}
