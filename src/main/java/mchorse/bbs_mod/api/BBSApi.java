package mchorse.bbs_mod.api;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnostics;

import java.util.List;
import java.util.function.Supplier;

/**
 * Public entry point for BBS Addon/API 2.0.
 */
public final class BBSApi
{
    private BBSApi() {}

    public static String currentApiVersion()
    {
        return BBSApiVersion.CURRENT;
    }

    public static void registerAddon(BBSAddonDescriptor descriptor, Supplier<? extends BBSAddon> supplier)
    {
        BBSMod.registerAddon(descriptor, supplier);
    }

    public static void registerAddon(Supplier<? extends BBSAddon> supplier)
    {
        BBSMod.registerAddon(supplier);
    }

    public static List<BBSAddonDiagnostics> addonDiagnostics()
    {
        return BBSMod.getAddonDiagnostics();
    }
}
