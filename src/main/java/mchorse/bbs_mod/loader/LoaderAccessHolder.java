package mchorse.bbs_mod.loader;

import java.util.Objects;

/**
 * Global access point for LoaderAccess where constructor injection is impractical.
 */
public final class LoaderAccessHolder
{
    private static LoaderAccess instance;

    private LoaderAccessHolder() {}

    public static void set(LoaderAccess access)
    {
        instance = Objects.requireNonNull(access);
    }

    public static LoaderAccess get()
    {
        if (instance == null)
        {
            throw new IllegalStateException("LoaderAccess not initialized");
        }

        return instance;
    }

    /**
     * @return Whether a loader access has been installed.
     *
     * <p>For code that has a meaningful answer when there is no loader — the addon API reports
     * "unknown" for the mod version rather than refusing to answer in a headless harness.</p>
     */
    public static boolean has()
    {
        return instance != null;
    }
}
