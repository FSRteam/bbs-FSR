package mchorse.bbs_mod;

import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.cache.CacheAssetsSourcePack;
import mchorse.bbs_mod.resources.cache.ResourceCache;
import mchorse.bbs_mod.resources.cache.ResourceEntry;
import mchorse.bbs_mod.utils.watchdog.WatchDog;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class BBSResources
{
    private static CacheAssetsSourcePack local;
    private static File server;
    private static WatchDog watchDog;

    private static Set<String> requested = new HashSet<>();
    private static long lastUpdate;

    private static int lastAssetUpdate = -1;
    private static Map<Link, Boolean> assetUpdates = new HashMap<>();

    public static Set<String> getRequested()
    {
        return requested;
    }

    public static void markUpdate()
    {
        lastUpdate = System.currentTimeMillis();
    }

    public static boolean canDetectChanges()
    {
        return System.currentTimeMillis() - lastUpdate > 1000L;
    }

    public static void resetResources()
    {
        MinecraftClient.getInstance().execute(() ->
        {
            BBSModClient.getModels().reload();
            BBSModClient.getTextures().delete();
            BBSModClient.getSounds().deleteSounds();

            BBSModClient.getFormCategories().setup();
        });
    }

    public static void init(File bbs)
    {
        assetUpdates.clear();
        lastAssetUpdate = -1;

        server = new File(bbs, "server");
        server.mkdirs();

        setupWatchdog(false);

        BBSModClient.getFormCategories().setup();
    }

    public static void setupWatchdog(boolean sender)
    {
        File assetsFolder = BBSMod.getAssetsFolder();

        watchDog = new WatchDog(assetsFolder, false, (runnable) -> MinecraftClient.getInstance().execute(runnable));
        watchDog.getProxy().register(BBSModClient.getTextures());
        watchDog.getProxy().register(BBSModClient.getModels());
        watchDog.getProxy().register(BBSModClient.getSounds());
        watchDog.getProxy().register(BBSModClient.getFormCategories());

        if (sender)
        {
            watchDog.getProxy().register(new BBSResourceListener((link, delete) ->
            {
                assetUpdates.put(link, delete);
                lastAssetUpdate = 10;
            }));
        }

        watchDog.start();
    }

    public static void setup(MinecraftClient client, String id, ResourceCache cache)
    {
        requested.clear();

        File folder = new File(server, id);
        File cacheFile = new File(server, id + ".cache.json");
        ResourceCache oldCache = new ResourceCache();

        if (cacheFile.exists())
        {
            oldCache.read(cacheFile);
        }

        folder.mkdirs();

        local = new CacheAssetsSourcePack(folder, cache);

        BBSMod.getDynamicSourcePack().setSecondary(local);

        stopWatchdog();
        setupWatchdog(true);

        for (ResourceEntry newEntry : cache.cache)
        {
            ResourceEntry entry = oldCache.get(newEntry.path());

            if (entry == null || newEntry.lastModified() > entry.lastModified())
            {
                requested.add(newEntry.path());
            }
        }

        cache.write(cacheFile);

        client.execute(() ->
        {
            for (String path : requested)
            {
                ClientNetwork.sendRequestAsset(path, 0);
            }

            if (requested.isEmpty())
            {
                resetResources();
            }
        });
    }

    public static void reset()
    {
        if (local != null)
        {
            local = null;

            BBSMod.getDynamicSourcePack().setSecondary(null);

            stopWatchdog();
            setupWatchdog(false);

            assetUpdates.clear();
            lastAssetUpdate = -1;
        }
    }

    public static void stopWatchdog()
    {
        if (watchDog != null)
        {
            watchDog.stop();
            watchDog = null;
        }
    }

    public static void update()
    {
        lastAssetUpdate -= 1;

        if (lastAssetUpdate == 0)
        {
            for (Map.Entry<Link, Boolean> entry : assetUpdates.entrySet())
            {
                ClientNetwork.sendAsset(entry.getKey(), entry.getValue() ? -1 : 0);
            }

            assetUpdates.clear();
        }
    }

    public static void tick()
    {
        if (watchDog != null)
        {
            watchDog.getProxy().tick();
        }
    }
}