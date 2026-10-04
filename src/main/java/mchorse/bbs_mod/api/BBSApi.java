package mchorse.bbs_mod.api;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.addon.BBSAddon;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnostics;
import mchorse.bbs_mod.loader.LoaderAccessHolder;

import java.util.List;
import java.util.function.Supplier;

/**
 * The entry point of BBS's addon API.
 *
 * <p>Everything in {@code mchorse.bbs_mod.api} is a contract: it does not change without
 * {@link #VERSION} changing with it. Everything outside of that package is BBS's own business and
 * moves without notice — an addon reaching into it is on its own, and the breakage shows up in
 * the game rather than on the build.</p>
 *
 * <p>Two different questions are answered here, and both are kept. {@link #currentApiVersion()},
 * {@link #registerAddon} and {@link #addonDiagnostics()} are FSR's own addon lifecycle: they are
 * how an addon declares itself. {@link #VERSION} and {@link #requireVersion} are the contract
 * gate, which let an addon refuse to load on a BBS too old for it instead of limping along and
 * crashing later. {@link BBSApiVersion} is the human-readable face of {@link #VERSION}, and both
 * are derived from the same pair of numbers so they cannot drift apart.</p>
 */
public final class BBSApi
{
    /**
     * The version of the addon API.
     *
     * <p>It is bumped whenever a contract in {@code mchorse.bbs_mod.api} changes in a way that an
     * addon compiled against the previous one cannot survive, or a new feature set must be
     * distinguishable by addons. Version 2 adds editor, pose and structure extension points;
     * addons requiring version 1 remain compatible.</p>
     */
    public static final int VERSION = BBSApiVersion.MAJOR;

    private BBSApi() {}

    public static String currentApiVersion()
    {
        return BBSApiVersion.CURRENT;
    }

    /**
     * The version of BBS itself, as its mod metadata reports it — {@code 0.0.11} and such.
     *
     * <p>Returns {@code "unknown"} when no loader is installed, which is the case in the headless
     * test harnesses; the addon API must still answer there.</p>
     */
    public static String getModVersion()
    {
        if (!LoaderAccessHolder.has())
        {
            return "unknown";
        }

        return LoaderAccessHolder.get().getModVersion("bbs").orElse("unknown");
    }

    public static boolean isAtLeast(int version)
    {
        return VERSION >= version;
    }

    /**
     * Stops an addon built against a newer API from limping along on an older BBS.
     *
     * <p>Without it, the mismatch reads as "the game crashes on the first right click" instead of
     * "this addon does not fit this BBS build" — call it from the addon's entry point.</p>
     */
    public static void requireVersion(String modId, int version)
    {
        if (!isAtLeast(version))
        {
            throw new IllegalStateException(modId + " requires BBS addon API version " + version
                + ", but this BBS (" + getModVersion() + ") provides " + VERSION
                + ". Update BBS, or install a build of " + modId + " made for it.");
        }
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
