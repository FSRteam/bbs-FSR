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
}
