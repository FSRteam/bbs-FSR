package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.client.ui.BBSUiAssetBytes;
import mchorse.bbs_mod.api.client.ui.BBSUiAssetRef;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.resources.MultiLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interns TextureManager-owned static assets without exposing Link values.
 * Resource bytes are read away from the render thread through a bounded queue.
 */
final class BBSUiAssetPublisher
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-ui-assets");
    private static final int MAX_TRACKED_ASSETS = 1024;
    private static final int MAX_PENDING_READS = 32;
    private static final int MAX_ASSET_BYTES = 16 * 1024 * 1024;
    private static final long MAX_CACHED_BYTES = 64L * 1024L * 1024L;
    private static final long RETRY_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_ASSET_ID = new AtomicLong(1L);
    private static final LinkedHashMap<Texture, AssetEntry> ENTRIES = new LinkedHashMap<>(64, 0.75F, true);
    private static final LinkedHashMap<AssetEntry, BBSUiAssetBytes> CACHE = new LinkedHashMap<>(64, 0.75F, true);
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(MAX_PENDING_READS),
        (runnable) ->
        {
            Thread thread = new Thread(runnable, "bbs-ui-asset-reader");

            thread.setDaemon(true);

            return thread;
        }
    );

    private static long cachedBytes;
    private static long epoch = 1L;

    static
    {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private BBSUiAssetPublisher()
    {}

    public static BBSUiAssetRef reference(Texture texture)
    {
        if (texture == null || !texture.isValid())
        {
            return null;
        }

        AssetEntry entry;
        BBSUiAssetBytes cached;
        boolean read = false;
        boolean notify = false;
        long now = System.nanoTime();

        synchronized (LOCK)
        {
            entry = ENTRIES.get(texture);

            if (entry == null)
            {
                Link link = findLink(BBSModClient.getTextures(), texture);

                if (link == null || Link.COLOR.equals(link.source) || link instanceof MultiLink)
                {
                    return null;
                }

                evictTrackedAssetIfNeeded();

                entry = new AssetEntry(
                    texture,
                    link,
                    new BBSUiAssetRef("ui-asset-" + NEXT_ASSET_ID.getAndIncrement(), texture.width, texture.height),
                    epoch
                );
                ENTRIES.put(texture, entry);
            }

            if (!BBSUiMirrorRegistry.needsAsset(entry.ref.id()))
            {
                return entry.ref;
            }

            cached = entry.asset;

            if (cached != null && !entry.notifying && now >= entry.retryAfterNanos)
            {
                entry.notifying = true;
                notify = true;
            }
            else if (cached == null && !entry.loading && !entry.failed && now >= entry.retryAfterNanos)
            {
                entry.loading = true;
                read = true;
            }
        }

        if (read)
        {
            AssetEntry selected = entry;

            submit(selected, true, () -> readAndPublish(selected));
        }
        else if (notify)
        {
            AssetEntry selected = entry;
            BBSUiAssetBytes asset = cached;

            submit(selected, false, () -> publishCached(selected, asset));
        }

        return entry.ref;
    }

    public static void reset()
    {
        synchronized (LOCK)
        {
            epoch += 1L;
            cachedBytes = 0L;

            ENTRIES.clear();
            CACHE.clear();
            EXECUTOR.getQueue().clear();
        }

        BBSUiMirrorRegistry.resetAssets();
    }

    private static void submit(AssetEntry entry, boolean read, Runnable runnable)
    {
        try
        {
            EXECUTOR.execute(runnable);
        }
        catch (RejectedExecutionException e)
        {
            synchronized (LOCK)
            {
                if (read)
                {
                    entry.loading = false;
                }
                else
                {
                    entry.notifying = false;
                }

                entry.retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
            }
        }
    }

    private static void readAndPublish(AssetEntry entry)
    {
        BBSUiAssetBytes asset = null;
        Throwable failure = null;

        try (InputStream stream = BBSModClient.getTextures().provider.getAsset(entry.link))
        {
            byte[] bytes = readBounded(stream);

            if (!isPng(bytes))
            {
                throw new IOException("unsupported UI asset encoding");
            }

            asset = new BBSUiAssetBytes(entry.ref, "image/png", sha256(bytes), bytes);
        }
        catch (Exception | LinkageError e)
        {
            failure = e;
        }

        synchronized (LOCK)
        {
            entry.loading = false;

            if (!isCurrent(entry))
            {
                return;
            }

            if (asset == null)
            {
                entry.failed = true;
            }
            else
            {
                cache(entry, asset);
            }
        }

        if (asset != null)
        {
            if (!BBSUiMirrorRegistry.publishAsset(asset))
            {
                delayRetry(entry);
            }
        }
        else if (failure != null)
        {
            LOGGER.debug(
                "[bbs-client-ui-assets] asset '{}' is unavailable ({})",
                entry.ref.id(),
                failure.getClass().getSimpleName()
            );
        }
    }

    private static void publishCached(AssetEntry entry, BBSUiAssetBytes asset)
    {
        try
        {
            synchronized (LOCK)
            {
                if (!isCurrent(entry) || entry.asset != asset)
                {
                    return;
                }

                CACHE.get(entry);
            }

            if (!BBSUiMirrorRegistry.publishAsset(asset))
            {
                delayRetry(entry);
            }
        }
        finally
        {
            synchronized (LOCK)
            {
                entry.notifying = false;
            }
        }
    }

    private static void cache(AssetEntry entry, BBSUiAssetBytes asset)
    {
        if (asset.length() > MAX_CACHED_BYTES)
        {
            return;
        }

        BBSUiAssetBytes old = CACHE.put(entry, asset);

        if (old != null)
        {
            cachedBytes -= old.length();
        }

        entry.asset = asset;
        cachedBytes += asset.length();

        Iterator<Map.Entry<AssetEntry, BBSUiAssetBytes>> iterator = CACHE.entrySet().iterator();

        while (cachedBytes > MAX_CACHED_BYTES && iterator.hasNext())
        {
            Map.Entry<AssetEntry, BBSUiAssetBytes> oldest = iterator.next();

            iterator.remove();
            cachedBytes -= oldest.getValue().length();
            oldest.getKey().asset = null;
        }
    }

    private static void evictTrackedAssetIfNeeded()
    {
        if (ENTRIES.size() < MAX_TRACKED_ASSETS)
        {
            return;
        }

        Iterator<Map.Entry<Texture, AssetEntry>> iterator = ENTRIES.entrySet().iterator();

        if (!iterator.hasNext())
        {
            return;
        }

        AssetEntry entry = iterator.next().getValue();

        iterator.remove();

        BBSUiAssetBytes asset = CACHE.remove(entry);

        if (asset != null)
        {
            cachedBytes -= asset.length();
        }
    }

    private static boolean isCurrent(AssetEntry entry)
    {
        return entry.epoch == epoch && ENTRIES.get(entry.texture) == entry;
    }

    private static void delayRetry(AssetEntry entry)
    {
        synchronized (LOCK)
        {
            if (isCurrent(entry))
            {
                entry.retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
            }
        }
    }

    private static Link findLink(TextureManager manager, Texture texture)
    {
        if (manager == null)
        {
            return null;
        }

        for (Map.Entry<Link, Texture> entry : manager.textures.entrySet())
        {
            if (entry.getValue() == texture)
            {
                return entry.getKey();
            }
        }

        return null;
    }

    private static byte[] readBounded(InputStream stream) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;

        while ((read = stream.read(buffer)) >= 0)
        {
            if (read == 0)
            {
                continue;
            }

            total += read;

            if (total > MAX_ASSET_BYTES)
            {
                throw new IOException("UI asset exceeds byte limit");
            }

            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }

    private static boolean isPng(byte[] bytes)
    {
        if (bytes.length < PNG_SIGNATURE.length)
        {
            return false;
        }

        for (int i = 0; i < PNG_SIGNATURE.length; i++)
        {
            if (bytes[i] != PNG_SIGNATURE[i])
            {
                return false;
            }
        }

        return true;
    }

    private static String sha256(byte[] bytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final class AssetEntry
    {
        private final Texture texture;
        private final Link link;
        private final BBSUiAssetRef ref;
        private final long epoch;
        private BBSUiAssetBytes asset;
        private boolean loading;
        private boolean notifying;
        private boolean failed;
        private long retryAfterNanos;

        private AssetEntry(Texture texture, Link link, BBSUiAssetRef ref, long epoch)
        {
            this.texture = texture;
            this.link = link;
            this.ref = ref;
            this.epoch = epoch;
        }
    }
}
