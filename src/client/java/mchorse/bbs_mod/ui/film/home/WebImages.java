package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.resources.Pixels;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous web image cache for film home content (markdown images and
 * http(s) ad/news pictures).
 *
 * <p>Design:
 * <ul>
 * <li><b>Memory</b>: small LRU of decoded textures keyed by URL; evicted
 * entries delete their GL texture. Render-thread only.</li>
 * <li><b>Disk</b>: {@code config/bbs/settings/web_cache/<sha1(url)>.png},
 * downloaded to a {@code .part} file and atomically moved on success, so an
 * interrupted download never leaves a half-written image behind — the next
 * attempt simply overwrites the leftover part file.</li>
 * <li><b>Downloads</b>: daemon pool, browser-ish headers, 10s connect / 15s
 * read timeouts, {@code image/*} content type enforced, 20 MB size cap.</li>
 * <li><b>Failures</b>: a URL that fails enters a 30 s cooldown during which it
 * isn't retried (callers keep showing their fallback/spinner-free idle state),
 * then the next {@link #get(String)} tries again.</li>
 * </ul>
 *
 * <p>{@link #get(String)} never blocks: a miss schedules the download and
 * returns null; the decoded texture appears in the cache once the upload lands
 * on the render thread.
 */
public class WebImages
{
    private static final int MAX_CACHED = 64;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final long FAIL_COOLDOWN_MS = 30_000L;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, (r) ->
    {
        Thread thread = new Thread(r, "bbs-web-images");

        thread.setDaemon(true);

        return thread;
    });

    private static final LinkedHashMap<String, Texture> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> FAILED_UNTIL = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private WebImages()
    {}

    /**
     * Resolves a remote content image through the async web cache. Local asset
     * paths are intentionally rejected because film-home editorial content is
     * delivered entirely by the remote publisher.
     */
    public static Texture resolve(String image)
    {
        if (image == null || image.isEmpty())
        {
            return null;
        }

        return isRemote(image) ? get(image) : null;
    }

    /** Whether this reference should currently show its loading animation. */
    public static boolean isLoading(String image)
    {
        if (!isRemote(image))
        {
            return false;
        }

        synchronized (LOCK)
        {
            if (CACHE.containsKey(image))
            {
                return false;
            }
        }

        return !isCoolingDown(image);
    }

    public static boolean isRemote(String image)
    {
        return image != null && (image.startsWith("http://") || image.startsWith("https://"));
    }

    /**
     * Returns the cached texture for a remote URL, or null while it isn't
     * loaded. A miss outside the failure cooldown schedules a download.
     */
    public static Texture get(String url)
    {
        if (!isRemote(url))
        {
            return null;
        }

        synchronized (LOCK)
        {
            Texture texture = CACHE.get(url);

            if (texture != null)
            {
                return texture;
            }
        }

        kickDownload(url);

        return null;
    }

    public static boolean isCoolingDown(String url)
    {
        Long until = FAILED_UNTIL.get(url);

        return until != null && until > System.currentTimeMillis();
    }

    private static void kickDownload(String url)
    {
        if (isCoolingDown(url) || !IN_FLIGHT.add(url))
        {
            return;
        }

        IO.submit(() ->
        {
            byte[] bytes;

            try
            {
                bytes = download(url);
            }
            catch (Exception e)
            {
                fail(url);

                return;
            }

            Minecraft.getInstance().execute(() ->
            {
                try
                {
                    Pixels pixels = Pixels.fromPNGStream(new java.io.ByteArrayInputStream(bytes));
                    Texture texture = Texture.textureFromPixels(pixels, GL11.GL_LINEAR);

                    synchronized (LOCK)
                    {
                        CACHE.put(url, texture);
                        evictOverflow();
                    }
                }
                catch (Exception e)
                {
                    fail(url);
                }
                finally
                {
                    IN_FLIGHT.remove(url);
                }
            });
        });
    }

    private static void fail(String url)
    {
        IN_FLIGHT.remove(url);
        FAILED_UNTIL.put(url, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
    }

    /** Blocking download with disk caching; runs on the IO pool only. */
    private static byte[] download(String url) throws Exception
    {
        File target = fileFor(url);
        File part = new File(target.getParentFile(), target.getName() + ".part");

        if (target.isFile() && target.length() > 0)
        {
            return Files.readAllBytes(target.toPath());
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "curl/8.9.0");
        connection.setRequestProperty("Accept", "image/*");

        String type = connection.getHeaderField("Content-Type");

        if (connection.getResponseCode() / 100 != 2 || type == null || !type.startsWith("image/"))
        {
            throw new IllegalStateException("not an image response");
        }

        try (InputStream stream = connection.getInputStream())
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;

            while ((read = stream.read(chunk)) >= 0)
            {
                total += read;

                if (total > MAX_BYTES)
                {
                    throw new IllegalStateException("image too large");
                }

                buffer.write(chunk, 0, read);
            }

            bytes(buffer.toByteArray(), part, target);

            return buffer.toByteArray();
        }
        finally
        {
            connection.disconnect();
        }
    }

    /** Writes through a .part sibling and atomically moves into place. */
    private static void bytes(byte[] data, File part, File target) throws Exception
    {
        target.getParentFile().mkdirs();
        Files.write(part.toPath(), data);
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static File fileFor(String url)
    {
        return new File(BBSMod.getSettingsPath("web_cache"), sha1(url) + ".png");
    }

    private static void evictOverflow()
    {
        Iterator<Texture> iterator = CACHE.values().iterator();

        while (CACHE.size() > MAX_CACHED && iterator.hasNext())
        {
            Texture texture = iterator.next();

            iterator.remove();
            texture.delete();
        }
    }

    /**
     * Orbiting-dots loading animation (render thread). Drawn centered on the
     * given point; the head dot travels clockwise, trailing dots fade out.
     */
    public static void drawSpinner(UIContext context, float cx, float cy, int color)
    {
        long time = System.currentTimeMillis();
        int dots = 8;
        int head = (int) ((time / 110L) % dots);
        int rgb = color & Colors.RGB;

        for (int k = 0; k < dots; k++)
        {
            double angle = k * (Math.PI / 4D) - Math.PI / 2D;
            float px = cx + (float) Math.cos(angle) * 9F;
            float py = cy + (float) Math.sin(angle) * 9F;
            int distance = (k - head + dots) % dots;
            int alpha = Math.max(Colors.A25, Colors.A100 - distance * 0x20);

            context.batcher.filledCircle(px, py, 1.6F, alpha | rgb, 8);
        }
    }

    private static String sha1(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder builder = new StringBuilder();

            for (byte b : digest.digest(value.getBytes(StandardCharsets.UTF_8)))
            {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(value.hashCode());
        }
    }
}
