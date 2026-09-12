package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.resources.Pixels;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cover thumbnails for the film home grid, captured through the film editor's
 * own screenshot pipeline ({@code UIFilmPreview.snapshotToFile}): whenever a
 * film's data lands in the editor - or it gets saved - the monitor is rendered
 * at export resolution for one isolated frame and written to a temp PNG; a
 * background job then waits for that file, downscales it into the 320px cover
 * at {@code config/bbs/settings/film_thumbnails/<sha1(id)>.png} and invalidates
 * the in-memory texture so the grid picks the fresh picture up.
 *
 * <p>{@link #getCached(String)} never blocks: a miss schedules a background
 * file read whose upload lands on the render thread; textures live in a small
 * LRU whose evictions delete their GL texture.
 */
public class FilmThumbnails
{
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("bbs-film-home");

    private static final int MAX_CACHED = 64;
    private static final int CAPTURE_WIDTH = 320;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor((r) ->
    {
        Thread thread = new Thread(r, "bbs-film-thumbnails");

        thread.setDaemon(true);

        return thread;
    });

    private static final LinkedHashMap<String, Texture> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Set<String> CAPTURES = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOADS = ConcurrentHashMap.newKeySet();
    private static final Object LOCK = new Object();

    private static UIFilmPanel panel;

    public static void setPanel(UIFilmPanel filmPanel)
    {
        panel = filmPanel;
    }

    /**
     * Asks the currently open film's monitor for an isolated export-resolution
     * snapshot, which is then downscaled into this film's cover. Silently
     * does nothing when the film isn't open in the editor.
     */
    public static void requestCapture(String filmId)
    {
        UIFilmPanel host = panel;

        if (filmId == null || filmId.isEmpty() || host == null || host.preview == null
            || host.getData() == null || !filmId.equals(host.getData().getId()))
        {
            return;
        }

        File temp = tempFileFor(filmId);

        if (!CAPTURES.add(filmId))
        {
            return;
        }

        File parent = temp.getParentFile();

        if (parent != null)
        {
            parent.mkdirs();
        }

        try
        {
            Files.deleteIfExists(temp.toPath());

            host.preview.snapshotToFile(
                temp,
                () -> IO.submit(() -> process(filmId, temp)),
                () -> CAPTURES.remove(filmId),
                () -> panel == host
                    && host.getData() != null
                    && filmId.equals(host.getData().getId())
            );
        }
        catch (Exception e)
        {
            CAPTURES.remove(filmId);
            LOGGER.warn("[BBS-SEM] topic=film_thumb phase=schedule result=error", e);
        }
    }

    /**
     * Waits for the screenshot writer thread to finish the full-res temp PNG
     * (bounded), downscales it into the final cover file and invalidates the
     * cached texture so the grid reloads it.
     */
    private static void process(String filmId, File temp)
    {
        Pixels source = null;
        Pixels scaled = null;

        try
        {
            long previousLength = -1L;
            int stableReads = 0;

            for (int i = 0; i < 50 && stableReads < 2; i++)
            {
                long length = temp.isFile() ? temp.length() : 0L;

                if (length > 0L && length == previousLength)
                {
                    stableReads += 1;
                }
                else
                {
                    stableReads = 0;
                }

                previousLength = length;

                if (stableReads >= 2)
                {
                    break;
                }

                Thread.sleep(200L);
            }

            if (!temp.isFile() || temp.length() == 0)
            {
                return;
            }

            try (java.io.InputStream stream = Files.newInputStream(temp.toPath()))
            {
                source = Pixels.fromPNGStream(stream);
            }

            int outW = Math.min(CAPTURE_WIDTH, source.width);
            int outH = Math.max(1, (int) ((long) outW * source.height / source.width));

            scaled = Pixels.fromSize(outW, outH);

            for (int y = 0; y < outH; y++)
            {
                int sy = Math.min(source.height - 1, (int) ((long) y * source.height / outH));

                for (int x = 0; x < outW; x++)
                {
                    int sx = Math.min(source.width - 1, (int) ((long) x * source.width / outW));

                    scaled.setColor(x, y, source.getColor(sx, sy));
                }
            }

            scaled.rewindBuffer();

            BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < outH; y++)
            {
                for (int x = 0; x < outW; x++)
                {
                    mchorse.bbs_mod.utils.colors.Color color = scaled.getColor(x, y);

                    image.setRGB(x, y, ((int) (color.a * 255F) << 24) | ((int) (color.r * 255F) << 16) | ((int) (color.g * 255F) << 8) | (int) (color.b * 255F));
                }
            }

            File target = fileFor(filmId);
            File targetPart = new File(target.getParentFile(), target.getName() + ".part");

            target.getParentFile().mkdirs();
            ImageIO.write(image, "png", targetPart);
            Files.move(targetPart.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            Files.deleteIfExists(temp.toPath());

            Minecraft.getInstance().execute(() ->
            {
                synchronized (LOCK)
                {
                    Texture cached = CACHE.remove(filmId);

                    if (cached != null)
                    {
                        cached.delete();
                    }
                }
            });

            LOGGER.info("[BBS-SEM] topic=film_thumb phase=process result=written file={} size={}x{}", target, outW, outH);
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=film_thumb phase=process result=error", e);
        }
        finally
        {
            if (source != null)
            {
                source.delete();
            }

            if (scaled != null)
            {
                scaled.delete();
            }

            CAPTURES.remove(filmId);
        }
    }

    /**
     * Returns the cached thumbnail texture, or null when it isn't loaded yet.
     * A miss with an existing file schedules a background load; callers just
     * keep drawing their fallback until this returns non-null.
     */
    public static Texture getCached(String filmId)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return null;
        }

        synchronized (LOCK)
        {
            Texture texture = CACHE.get(filmId);

            if (texture != null)
            {
                return texture;
            }
        }

        kickLoad(filmId);

        return null;
    }

    private static void kickLoad(String filmId)
    {
        final File file = fileFor(filmId);

        if (!file.isFile() || !LOADS.add(filmId))
        {
            return;
        }

        IO.submit(() ->
        {
            byte[] bytes;

            try
            {
                bytes = Files.readAllBytes(file.toPath());
            }
            catch (Exception e)
            {
                LOADS.remove(filmId);
                return;
            }

            Minecraft.getInstance().execute(() ->
            {
                Pixels pixels = null;

                try
                {
                    pixels = Pixels.fromPNGStream(new ByteArrayInputStream(bytes));
                    Texture texture = Texture.textureFromPixels(pixels, GL11.GL_LINEAR);
                    pixels = null;

                    synchronized (LOCK)
                    {
                        Texture previous = CACHE.put(filmId, texture);

                        if (previous != null && previous != texture)
                        {
                            previous.delete();
                        }

                        evictOverflow();
                    }
                }
                catch (Exception e)
                {}
                finally
                {
                    if (pixels != null)
                    {
                        pixels.delete();
                    }

                    LOADS.remove(filmId);
                }
            });
        });
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

    private static File fileFor(String filmId)
    {
        return new File(BBSMod.getSettingsPath("film_thumbnails"), sha1(filmId) + ".png");
    }

    private static File tempFileFor(String filmId)
    {
        return new File(BBSMod.getSettingsPath("film_thumbnails"), sha1(filmId) + "_temp.png");
    }

    private static String sha1(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder builder = new StringBuilder();

            for (byte b : digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
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
