package mchorse.bbs_mod.test;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Minimal NeoForge/client singleton setup for dependency-light standalone tests. */
public final class HeadlessClientTestBootstrap
{
    private HeadlessClientTestBootstrap()
    {}

    public static Runnable install()
    {
        try
        {
            bootstrapStandaloneMinecraftRuntime();

            Field cameraFactory = BBSMod.class.getDeclaredField("factoryCameraClips");
            Field actionFactory = BBSMod.class.getDeclaredField("factoryActionClips");
            Field l10n = BBSModClient.class.getDeclaredField("l10n");

            cameraFactory.setAccessible(true);
            actionFactory.setAccessible(true);
            l10n.setAccessible(true);

            Object previousCameraFactory = cameraFactory.get(null);
            Object previousActionFactory = actionFactory.get(null);
            Object previous = l10n.get(null);

            if (previousCameraFactory == null)
            {
                cameraFactory.set(null, new MapFactory<>());
            }

            if (previousActionFactory == null)
            {
                actionFactory.set(null, new MapFactory<>());
            }

            if (previous == null)
            {
                l10n.set(null, new L10n());
            }

            return () ->
            {
                try
                {
                    l10n.set(null, previous);
                    actionFactory.set(null, previousActionFactory);
                    cameraFactory.set(null, previousCameraFactory);
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the headless client test runtime", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the headless client test runtime", exception);
        }
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }
}
