package mchorse.bbs_mod.client.render.multiview;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Opt-in runtime measurements; the normal client does no sampling or formatting here. */
public final class ViewPerformanceMonitor
{
    private static final boolean ENABLED = Boolean.getBoolean("bbs.multiview.profile");
    private static final long WINDOW_NANOS = 2_000_000_000L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long[] FRAME_GAPS = new long[8192];
    private static final Map<String, ViewSamples> VIEWS = new HashMap<>();

    private static long windowStart;
    private static long previousFrame;
    private static long frames;
    private static long cpuNanos;
    private static long gpuNanos;
    private static long gpuSamples;
    private static int gapCount;

    private ViewPerformanceMonitor()
    {}

    public static void record(long frameCpuNanos, long frameGpuNanos, Iterable<ViewRenderState> views)
    {
        if (!ENABLED)
        {
            return;
        }

        long now = System.nanoTime();

        if (previousFrame == 0L)
        {
            previousFrame = now;
            windowStart = now;

            for (ViewRenderState view : views)
            {
                VIEWS.computeIfAbsent(view.getId(), ignored -> new ViewSamples()).lastImage = view.getLastRenderedNanos();
            }

            return;
        }

        if (gapCount < FRAME_GAPS.length)
        {
            FRAME_GAPS[gapCount++] = Math.max(0L, now - previousFrame);
        }

        previousFrame = now;
        frames++;
        cpuNanos += Math.max(0L, frameCpuNanos);

        if (frameGpuNanos >= 0L)
        {
            gpuNanos += frameGpuNanos;
            gpuSamples++;
        }

        for (ViewRenderState view : views)
        {
            ViewSamples samples = VIEWS.computeIfAbsent(view.getId(), ignored -> new ViewSamples());
            long stamp = view.getLastRenderedNanos();

            if (stamp > samples.lastImage)
            {
                samples.images++;
            }

            samples.lastImage = stamp;
        }

        long elapsed = now - windowStart;

        if (elapsed < WINDOW_NANOS)
        {
            return;
        }

        double seconds = elapsed / 1_000_000_000D;
        long[] sorted = Arrays.copyOf(FRAME_GAPS, gapCount);
        Arrays.sort(sorted);
        StringBuilder description = new StringBuilder();

        for (ViewRenderState view : views)
        {
            ViewSamples samples = VIEWS.get(view.getId());

            if (description.length() > 0)
            {
                description.append("; ");
            }

            description.append(String.format(Locale.ROOT,
                "%s=%dx%d,%.2fHz,cpu=%.3fms,gpu=%.3fms,budget=%.3fms,%s,shaders=%s",
                view.getId(), view.getWidth(), view.getHeight(), samples.images / seconds,
                view.getLastCpuNanos() / 1_000_000D, view.getTiming().getLastGpuNanos() / 1_000_000D,
                view.getBudgetNanos() / 1_000_000D, view.getStatus(), view.isShadersEnabled()));
            samples.images = 0L;
        }

        LOGGER.info(String.format(Locale.ROOT,
            "BBS multiview profile: fps=%.2f frameP95=%.3fms frameP99=%.3fms cpu=%.3fms gpu=%.3fms views=[%s]",
            frames / seconds, percentile(sorted, 0.95D), percentile(sorted, 0.99D),
            cpuNanos / (double) frames / 1_000_000D,
            gpuSamples == 0L ? -1D : gpuNanos / (double) gpuSamples / 1_000_000D, description));

        windowStart = now;
        frames = 0L;
        cpuNanos = 0L;
        gpuNanos = 0L;
        gpuSamples = 0L;
        gapCount = 0;
    }

    private static double percentile(long[] sorted, double quantile)
    {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * quantile) - 1);
        return index < 0 ? 0D : sorted[index] / 1_000_000D;
    }

    private static final class ViewSamples
    {
        private long lastImage;
        private long images;
    }
}
