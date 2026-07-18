package mchorse.bbs_mod.client.render.surface;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceFrame;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceDemand;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates render-thread capture and the off-thread JPEG encoder. */
public final class BBSRenderSurfaceRuntime
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-render-surface");
    private static final BBSRenderSurfaceRuntime WORLD_LANE = new BBSRenderSurfaceRuntime();
    private static final BBSRenderSurfaceRuntime UI_LANE = new BBSRenderSurfaceRuntime();

    private final BBSRenderSurfaceStreamFence stream = new BBSRenderSurfaceStreamFence();
    private final BBSRenderSurfaceLifecycleFence lifecycle = new BBSRenderSurfaceLifecycleFence();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private volatile StbJpegSurfaceEncoder encoder;
    private long activeGeneration;
    private Set<BBSRenderSurfaceKind> activeKinds = Collections.emptySet();
    private long nextCaptureNanos;
    private long captureIntervalNanos;
    private boolean captureScheduled;
    private RgbFramePool pool;
    private GpuSurfaceReadback readback;

    private BBSRenderSurfaceRuntime()
    {}

    /**
     * Returns whether at least one listener currently requests an available
     * logical surface. Render integrations use this to avoid creating a
     * placement-only mirror session when no media subscriber exists.
     */
    public static boolean hasDemand(Set<BBSRenderSurfaceKind> availableKinds)
    {
        return availableKinds != null
            && !availableKinds.isEmpty()
            && BBSRenderSurfaceRegistry.capturePlan(availableKinds) != null;
    }

    public static void capture(RenderTarget source, Set<BBSRenderSurfaceKind> availableKinds)
    {
        captureLane(WORLD_LANE, source, availableKinds, null);
    }

    public static void captureUiRegion(
        RenderTarget source,
        Set<BBSRenderSurfaceKind> availableKinds,
        int logicalX,
        int logicalY,
        int logicalWidth,
        int logicalHeight,
        int logicalScreenWidth,
        int logicalScreenHeight
    )
    {
        if (source == null || logicalWidth < 1 || logicalHeight < 1 || logicalScreenWidth < 1 || logicalScreenHeight < 1)
        {
            return;
        }

        float scaleX = source.viewWidth / (float) logicalScreenWidth;
        float scaleY = source.viewHeight / (float) logicalScreenHeight;
        int sourceX = Math.max(0, Math.round(logicalX * scaleX));
        int sourceWidth = Math.min(source.viewWidth - sourceX, Math.max(1, Math.round(logicalWidth * scaleX)));
        int sourceHeight = Math.min(source.viewHeight, Math.max(1, Math.round(logicalHeight * scaleY)));
        int sourceY = Math.max(0, source.viewHeight - Math.round((logicalY + logicalHeight) * scaleY));

        captureLane(UI_LANE, source, availableKinds, new CaptureRegion(sourceX, sourceY, sourceWidth, sourceHeight));
    }

    private static void captureLane(
        BBSRenderSurfaceRuntime lane,
        RenderTarget source,
        Set<BBSRenderSurfaceKind> availableKinds,
        CaptureRegion region
    )
    {
        long requestLifecycleEpoch = lane.lifecycle.captureEpoch();

        if (!lane.lifecycleCurrent(requestLifecycleEpoch))
        {
            return;
        }

        if (!RenderSystem.isOnRenderThreadOrInit())
        {
            Set<BBSRenderSurfaceKind> kindsSnapshot = immutableKinds(availableKinds);

            RenderSystem.recordRenderCall(() ->
                lane.captureOnRenderThread(source, kindsSnapshot, requestLifecycleEpoch, region)
            );

            return;
        }

        lane.captureOnRenderThread(
            source,
            availableKinds == null ? Collections.emptySet() : availableKinds,
            requestLifecycleEpoch,
            region
        );
    }

    public static void reset()
    {
        invalidateStream();
    }

    /** Invalidates every issued/read/encoded frame owned by the old UI session. */
    public static void invalidateSession()
    {
        invalidateStream();
    }

    /** Permanently releases the client-lifetime encoder during client stop. */
    public static void shutdown()
    {
        WORLD_LANE.terminated.set(true);
        UI_LANE.terminated.set(true);
        long worldEpoch = WORLD_LANE.prepareExternalInvalidation();
        long uiEpoch = UI_LANE.prepareExternalInvalidation();
        WORLD_LANE.stopEncoder();
        UI_LANE.stopEncoder();

        if (RenderSystem.isOnRenderThreadOrInit())
        {
            WORLD_LANE.applyExternalInvalidation(worldEpoch);
            UI_LANE.applyExternalInvalidation(uiEpoch);
        }
        else
        {
            RenderSystem.recordRenderCall(() ->
            {
                WORLD_LANE.applyExternalInvalidation(worldEpoch);
                UI_LANE.applyExternalInvalidation(uiEpoch);
            });
        }
    }

    private static void invalidateStream()
    {
        /* Fence worker output before render-thread teardown can be scheduled. */
        long worldEpoch = WORLD_LANE.prepareExternalInvalidation();
        long uiEpoch = UI_LANE.prepareExternalInvalidation();

        if (RenderSystem.isOnRenderThreadOrInit())
        {
            WORLD_LANE.applyExternalInvalidation(worldEpoch);
            UI_LANE.applyExternalInvalidation(uiEpoch);
        }
        else
        {
            /* Encoder/PBO ownership stays serialized with capture and GL teardown. */
            RenderSystem.recordRenderCall(() ->
            {
                WORLD_LANE.applyExternalInvalidation(worldEpoch);
                UI_LANE.applyExternalInvalidation(uiEpoch);
            });
        }
    }

    private void captureOnRenderThread(
        RenderTarget source,
        Set<BBSRenderSurfaceKind> availableKinds,
        long captureLifecycleEpoch,
        CaptureRegion region
    )
    {
        if (!this.lifecycleCurrent(captureLifecycleEpoch))
        {
            this.applyPendingExternalInvalidation();

            return;
        }

        if (source == null || source.frameBufferId < 0 || source.viewWidth < 1 || source.viewHeight < 1)
        {
            this.stopAll();

            return;
        }

        BBSRenderSurfaceCapturePlan plan = BBSRenderSurfaceRegistry.capturePlan(availableKinds);

        if (plan == null)
        {
            this.stopAll();

            return;
        }

        try
        {
            if (!this.lifecycleCurrent(captureLifecycleEpoch))
            {
                this.applyPendingExternalInvalidation();

                return;
            }

            if (!this.ensureEncoder())
            {
                this.stopAll();

                return;
            }

            if (!this.ensureStarted(plan.kinds(), captureLifecycleEpoch))
            {
                this.applyPendingExternalInvalidation();

                return;
            }

            StbJpegSurfaceEncoder currentEncoder = this.encoder;

            if (!this.lifecycleCurrent(captureLifecycleEpoch) || currentEncoder == null)
            {
                this.applyPendingExternalInvalidation();

                return;
            }

            this.readback.poll(this.stream::isCurrent, currentEncoder::discardPending, currentEncoder::submit);

            long now = System.nanoTime();
            long interval = Math.max(1L, 1_000_000_000L / plan.framesPerSecond());

            if (this.captureIntervalNanos != interval)
            {
                this.captureIntervalNanos = interval;
                this.captureScheduled = false;
            }

            if (this.captureScheduled && now - this.nextCaptureNanos < 0L)
            {
                return;
            }

            int captureWidth = region == null ? source.viewWidth : region.width();
            int captureHeight = region == null ? source.viewHeight : region.height();
            int[] size = region == null
                ? fit(captureWidth, captureHeight, plan.maxWidth(), plan.maxHeight())
                : fitUiRegion(captureWidth, captureHeight, plan.maxWidth(), plan.maxHeight());

            if (!this.lifecycleCurrent(captureLifecycleEpoch))
            {
                this.applyPendingExternalInvalidation();

                return;
            }

            if (this.lifecycle.getIfCurrent(captureLifecycleEpoch, () ->
            {
                BBSRenderSurfaceStamp stamp = this.stream.issue(this.activeGeneration);

                return region == null
                    ? this.readback.issue(source, size[0], size[1], plan.kinds(), stamp, now, plan.jpegQuality())
                    : this.readback.issue(source, region.x(), region.y(), region.width(), region.height(),
                        size[0], size[1], plan.kinds(), stamp, now, plan.jpegQuality());
            }))
            {
                long previousDeadline = this.captureScheduled ? this.nextCaptureNanos : now;

                this.nextCaptureNanos = nextCaptureDeadline(previousDeadline, now, interval);
                this.captureScheduled = true;
            }
            else if (!this.lifecycleCurrent(captureLifecycleEpoch))
            {
                this.applyPendingExternalInvalidation();
            }
        }
        catch (Exception | LinkageError e)
        {
            if (!this.lifecycleCurrent(captureLifecycleEpoch))
            {
                this.applyPendingExternalInvalidation();

                return;
            }

            LOGGER.error("[bbs-client-render-surface] surface capture failed; disabling until the next demand", e);
            this.stopAll();
        }
    }

    private boolean ensureStarted(Set<BBSRenderSurfaceKind> requestedKinds, long expectedLifecycleEpoch)
    {
        if (this.readback != null
            && this.pool != null
            && this.stream.isCurrent(this.activeGeneration)
            && this.activeKinds.equals(requestedKinds))
        {
            return true;
        }

        if (this.stream.isCurrent(this.activeGeneration))
        {
            /* A logical kind transition starts a new capture stream. Delete
             * pending PBO work before it can be labeled as the new stream. */
            long invalidatedGeneration = this.stream.invalidate();

            this.discardBeforeGeneration(invalidatedGeneration);
        }

        this.closeResources();

        RgbFramePool newPool = new RgbFramePool();
        GpuSurfaceReadback newReadback = new GpuSurfaceReadback(newPool);
        boolean installed = this.lifecycle.runIfCurrent(expectedLifecycleEpoch, () ->
        {
            long currentGeneration = this.stream.beginStream();

            this.pool = newPool;
            this.readback = newReadback;
            this.activeGeneration = currentGeneration;
            this.activeKinds = Collections.unmodifiableSet(EnumSet.copyOf(requestedKinds));
            this.nextCaptureNanos = 0L;
            this.captureIntervalNanos = 0L;
            this.captureScheduled = false;
        });

        if (!installed)
        {
            newReadback.close();
            newPool.close();
        }

        return installed;
    }

    private void stopAll()
    {
        if (this.readback == null && this.pool == null)
        {
            return;
        }

        long invalidatedGeneration = this.stream.invalidate();

        this.discardBeforeGeneration(invalidatedGeneration);
        this.closeResources();
    }

    private void closeResources()
    {
        if (this.readback != null)
        {
            this.readback.close();
            this.readback = null;
        }

        if (this.pool != null)
        {
            this.pool.close();
            this.pool = null;
        }

        this.nextCaptureNanos = 0L;
        this.captureIntervalNanos = 0L;
        this.captureScheduled = false;
        this.activeGeneration = 0L;
        this.activeKinds = Collections.emptySet();
    }

    private long prepareExternalInvalidation()
    {
        return this.lifecycle.invalidate(() ->
        {
            long invalidatedGeneration = this.stream.invalidate();

            this.discardBeforeGeneration(invalidatedGeneration);
        });
    }

    private void applyPendingExternalInvalidation()
    {
        this.lifecycle.applyPending(this::closeResources);
    }

    private void applyExternalInvalidation(long teardownEpoch)
    {
        this.lifecycle.apply(teardownEpoch, this::closeResources);
    }

    private boolean lifecycleCurrent(long expectedEpoch)
    {
        return !this.terminated.get() && this.lifecycle.isCurrent(expectedEpoch);
    }

    private void publishEncoded(BBSRenderSurfaceFrame frame)
    {
        if (this.stream.isCurrent(frame.generation()))
        {
            BBSRenderSurfaceRegistry.publish(
                frame,
                this.stream::isCurrent,
                this.stream::tryStartCallback
            );
        }
    }

    private synchronized boolean ensureEncoder()
    {
        if (this.terminated.get())
        {
            return false;
        }

        if (this.encoder == null)
        {
            this.encoder = new StbJpegSurfaceEncoder(this::publishEncoded);
        }

        return this.encoder.isRunning();
    }

    private void discardBeforeGeneration(long minimumGeneration)
    {
        if (this.encoder != null)
        {
            this.encoder.discardBeforeGeneration(minimumGeneration);
        }
    }

    private synchronized void stopEncoder()
    {
        StbJpegSurfaceEncoder current = this.encoder;

        this.encoder = null;

        if (current != null)
        {
            current.close();
        }
    }

    private static int[] fit(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight)
    {
        float scale = Math.min(1F, Math.min(maxWidth / (float) sourceWidth, maxHeight / (float) sourceHeight));
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));

        return new int[]{width, height};
    }

    /**
     * Advances a phase-preserving capture deadline past {@code now}. A stalled
     * render loop skips missed periods instead of issuing a catch-up burst.
     */
    static long nextCaptureDeadline(long previousDeadline, long now, long interval)
    {
        if (interval < 1L)
        {
            throw new IllegalArgumentException("capture interval must be positive");
        }

        long deadline = previousDeadline + interval;

        if (now - deadline >= 0L)
        {
            long missed = (now - deadline) / interval + 1L;

            deadline += missed * interval;
        }

        return deadline;
    }

    static int[] fitUiRegion(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight)
    {
        long area = (long) sourceWidth * sourceHeight;
        long budget = (long) maxWidth * maxHeight;
        double areaScale = area <= budget ? 1D : Math.sqrt(budget / (double) area);
        double scale = Math.min(1D, Math.min(areaScale, Math.min(
            BBSRenderSurfaceDemand.MAX_WIDTH / (double) sourceWidth,
            BBSRenderSurfaceDemand.MAX_HEIGHT / (double) sourceHeight
        )));
        int width = Math.min(BBSRenderSurfaceDemand.MAX_WIDTH, Math.max(1, (int) Math.floor(sourceWidth * scale)));
        int height = Math.min(BBSRenderSurfaceDemand.MAX_HEIGHT, Math.max(1, (int) Math.floor(sourceHeight * scale)));

        return new int[]{width, height};
    }

    private static Set<BBSRenderSurfaceKind> immutableKinds(Set<BBSRenderSurfaceKind> kinds)
    {
        return kinds == null || kinds.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(kinds));
    }

    private record CaptureRegion(int x, int y, int width, int height)
    {}
}
