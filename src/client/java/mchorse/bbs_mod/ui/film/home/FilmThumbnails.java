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
    private static int pendingAttempts;

    private static final int RETRY_TICKS = 8;
    private static final int MAX_ATTEMPTS = 10;

    public static void setPanel(UIFilmPanel filmPanel)
    {
        panel = filmPanel;
    }

    /**
     * Schedules a grab of the monitor's current frame. The first attempt
     * fires almost immediately; if the preview hasn't presented a real frame
     * yet (the grab would be black), it silently retries a few times.
     */
    public static void requestCapture(String filmId, int delayTicks)
    {
        if (filmId == null || filmId.isEmpty())
        {
            return;
        }

        if (filmId.equals(pendingFilmId) && pendingTicks >= 0)
        {
            return;
        }

        pendingFilmId = filmId;
        pendingTicks = Math.max(1, delayTicks);
        pendingAttempts = MAX_ATTEMPTS;
    }

    /** Client tick hook (render thread). */
    public static void clientTick()
    {
        if (pendingTicks < 0)
        {
            return;
        }

        pendingTicks -= 1;

        if (pendingTicks > 0)
        {
            return;
        }

        String filmId = pendingFilmId;

        pendingTicks = -1;
        pendingFilmId = null;

        if (filmId == null)
        {
            return;
        }

        if (!capture(filmId) && pendingAttempts > 0)
        {
            /* Blank grab - the monitor hadn't presented a frame yet; retry shortly */
            pendingAttempts -= 1;
            pendingFilmId = filmId;
            pendingTicks = RETRY_TICKS;
        }
    }

    /**
     * Grabs the monitor's current frame for this film.
     *
     * @return true when done (captured, or the editor/preview is gone so no
     *         retry makes sense); false when the grab was blank and a retry
     *         should happen shortly.
     */
    private static boolean capture(String filmId)
    {
        UIFilmPanel host = panel;
        Minecraft mc = Minecraft.getInstance();

        if (host == null || mc.getWindow() == null || !host.isVisible()
            || host.getData() == null || !filmId.equals(host.getData().getId())
            || host.preview == null || !host.preview.isVisible()
            || host.preview.area.w < 16 || host.preview.area.h < 16)
        {
            return true;
        }

        RenderTarget target = mc.getMainRenderTarget();

        if (target == null || target.width < 16 || target.height < 16)
        {
            return true;
        }

        NativeImage image = Screenshot.takeScreenshot(target);

        if (image == null)
        {
            return true;
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

            /* A fully black grab means the preview hadn't presented a frame
             * yet - keep whatever cover the film already has and retry. */
            if (isBlank(out))
            {
                out.close();

                return false;
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

        return true;
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

    /** True when every sampled pixel is (near) black - an unpresented preview. */
    private static boolean isBlank(NativeImage image)
    {
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 8);

        for (int y = 0; y < image.getHeight(); y += step)
        {
            for (int x = 0; x < image.getWidth(); x += step)
            {
                int pixel = image.getPixelRGBA(x, y);

                if ((pixel & 0xFF) > 10 || ((pixel >> 8) & 0xFF) > 10 || ((pixel >> 16) & 0xFF) > 10)
                {
                    return false;
                }
            }
        }

        return true;
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
