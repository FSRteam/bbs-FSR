package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.resources.Pixels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.opengl.GL11;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * First-frame thumbnail pipeline for the film home grid.
 *
 * <p>Capture: while a film is open in the editor (and shortly after each save),
 * a frame is grabbed from the main render target over the preview area,
 * downscaled to 320px wide and written to
 * {@code config/bbs/settings/film_thumbnails/<sha1(id)>.png}. Grabbing happens
 * on the render thread inside the client tick.
 *
 * <p>Consumption: {@link #getCached(String)} never blocks and never touches
 * disk on the render hot path — a miss schedules a background file read whose
 * upload is posted back to the render thread. Textures are kept in a small
 * LRU; evicted entries delete their GL texture.
 */
public class FilmThumbnails
{
    private static final int MAX_CACHED = 64;
    private static final int CAPTURE_WIDTH = 320;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor((r) ->
    {
        Thread thread = new Thread(r, "bbs-film-thumbnails");

        thread.setDaemon(true);

        return thread;
    });

    private static final LinkedHashMap<String, Texture> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Object LOCK = new Object();

    private static UIFilmPanel panel;
    private static String pendingFilmId;
    private static int pendingTicks = -1;

    /* Batch generation: sequentially opens films that lack covers so their
     * monitor frame can be captured without any manual steps */
    private static java.util.ArrayDeque<String> batchQueue;
    private static String batchCurrent;
    private static int batchTimer;
    private static int batchCooldown;
    private static int batchTotal;
    private static int batchDone;

    public static void setPanel(UIFilmPanel filmPanel)
    {
        panel = filmPanel;
    }

    /** Whether a cover file already exists for this film. */
    public static boolean hasThumbnail(String filmId)
    {
        return filmId != null && fileFor(filmId).isFile();
    }

    /**
     * Queues films for automatic cover generation: each one is opened in the
     * editor, its scheduled first-frame capture runs, then it is unloaded
     * again. Returns how many films actually need generation.
     */
    public static int startBatch(java.util.Collection<String> filmIds)
    {
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();

        for (String id : filmIds)
        {
            if (!hasThumbnail(id))
            {
                queue.add(id);
            }
        }

        if (queue.isEmpty())
        {
            return 0;
        }

        batchQueue = queue;
        batchTotal = queue.size();
        batchDone = 0;
        batchCurrent = null;
        batchCooldown = 0;

        return queue.size();
    }

    public static boolean isBatchRunning()
    {
        return batchQueue != null;
    }

    /**
     * Schedule a first-frame grab for the given film after {@code delayTicks}.
     * The capture silently no-ops when the editor or its preview isn't visible
     * by the time the delay elapses.
     */
    public static void requestCapture(String filmId, int delayTicks)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return;
        }

        pendingFilmId = filmId;
        pendingTicks = Math.max(1, delayTicks);
    }

    /** Client tick hook (render thread). */
    public static void clientTick()
    {
        tickBatch();

        if (pendingTicks < 0)
        {
            return;
        }

        pendingTicks -= 1;

        if (pendingTicks <= 0)
        {
            String filmId = pendingFilmId;

            pendingTicks = -1;
            pendingFilmId = null;

            if (filmId != null)
            {
                capture(filmId);
            }
        }
    }

    /**
     * Batch state machine, all on the render thread: open the next film,
     * poll for its captured cover (bounded by a timeout), unload back to the
     * home, brief cooldown, repeat. The batch aborts silently if the user
     * opens a different film themselves.
     */
    private static void tickBatch()
    {
        if (batchCooldown > 0)
        {
            batchCooldown -= 1;

            return;
        }

        if (batchCurrent == null)
        {
            if (batchQueue == null || batchQueue.isEmpty())
            {
                if (batchTotal > 0)
                {
                    finishBatch();
                }

                return;
            }

            if (panel == null || panel.getData() != null || Minecraft.getInstance().level == null)
            {
                return;
            }

            batchCurrent = batchQueue.poll();
            panel.pickData(batchCurrent);
            batchTimer = 200;

            return;
        }

        /* A film is open and we're waiting for its cover to land */
        String expected = panel.getData() == null ? null : panel.getData().getId();

        if (expected == null || !expected.equals(batchCurrent))
        {
            /* User navigated away themselves - give up on the whole run */
            abortBatch();

            return;
        }

        boolean captured = hasThumbnail(batchCurrent);

        batchTimer -= 1;

        if (captured || batchTimer <= 0)
        {
            batchDone += 1;
            batchCurrent = null;
            batchTimer = 0;
            panel.fill(null);
            batchCooldown = 15;
        }
    }

    private static void finishBatch()
    {
        /* Silent completion - covers simply appear in the grid */
        batchQueue = null;
        batchTotal = 0;
        batchDone = 0;
    }

    private static void abortBatch()
    {
        batchQueue = null;
        batchCurrent = null;
        batchTimer = 0;
        batchTotal = 0;
        batchDone = 0;
    }

    private static void capture(String filmId)
    {
        UIFilmPanel host = panel;
        Minecraft mc = Minecraft.getInstance();

        if (host == null || mc.getWindow() == null || !host.isVisible()
            || host.getData() == null || !filmId.equals(host.getData().getId())
            || host.preview == null || !host.preview.isVisible()
            || host.preview.area.w < 16 || host.preview.area.h < 16)
        {
            return;
        }

        RenderTarget target = mc.getMainRenderTarget();

        if (target == null || target.width < 16 || target.height < 16)
        {
            return;
        }

        NativeImage image = Screenshot.takeScreenshot(target);

        if (image == null)
        {
            return;
        }

        try
        {
            double ratioX = target.width / (double) mc.getWindow().getWidth();
            double ratioY = target.height / (double) mc.getWindow().getHeight();

            int px = (int) Math.round(host.preview.area.x * ratioX);
            int py = (int) Math.round(host.preview.area.y * ratioY);
            int pw = (int) Math.round(host.preview.area.w * ratioX);
            int ph = (int) Math.round(host.preview.area.h * ratioY);

            px = Math.max(0, Math.min(px, target.width - 1));
            py = Math.max(0, Math.min(py, target.height - 1));
            pw = Math.max(1, Math.min(pw, target.width - px));
            ph = Math.max(1, Math.min(ph, target.height - py));

            int outW = Math.min(CAPTURE_WIDTH, pw);
            int outH = Math.max(1, (int) ((long) outW * ph / pw));

            NativeImage out = new NativeImage(outW, outH, false);

            /* Framebuffer rows are bottom-up; map output rows accordingly. */
            for (int y = 0; y < outH; y++)
            {
                int sy = py + (ph - 1) - (int) ((long) y * ph / outH);

                for (int x = 0; x < outW; x++)
                {
                    int sx = px + (int) ((long) x * pw / outW);

                    out.setPixelRGBA(x, y, image.getPixelRGBA(sx, sy));
                }
            }

            File file = fileFor(filmId);

            file.getParentFile().mkdirs();
            out.writeToFile(file);
            out.close();

            synchronized (LOCK)
            {
                Texture cached = CACHE.remove(filmId);

                if (cached != null)
                {
                    cached.delete();
                }
            }
        }
        catch (Exception e)
        {
            /* A failed capture is invisible degradation: the grid keeps its
             * procedural fallback card until the next successful one. */
        }
        finally
        {
            image.close();
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

        if (!file.isFile())
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
                return;
            }

            Minecraft.getInstance().execute(() ->
            {
                try
                {
                    Pixels pixels = Pixels.fromPNGStream(new ByteArrayInputStream(bytes));
                    Texture texture = Texture.textureFromPixels(pixels, GL11.GL_LINEAR);

                    synchronized (LOCK)
                    {
                        CACHE.put(filmId, texture);
                        evictOverflow();
                    }
                }
                catch (Exception e)
                {}
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

    /** Drops the cached texture and the persisted file so the next open/save re-captures. */
    public static void invalidate(String filmId)
    {
        synchronized (LOCK)
        {
            Texture texture = CACHE.remove(filmId);

            if (texture != null)
            {
                texture.delete();
            }
        }

        try
        {
            Files.deleteIfExists(fileFor(filmId).toPath());
        }
        catch (Exception e)
        {}
    }

    private static File fileFor(String filmId)
    {
        return new File(BBSMod.getSettingsPath("film_thumbnails"), sha1(filmId) + ".png");
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
