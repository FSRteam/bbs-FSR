package mchorse.bbs_mod.client.render.multiview;

import com.mojang.blaze3d.pipeline.RenderTarget;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.graphics.texture.Texture;
import org.joml.Matrix4f;

import java.util.Objects;

/** Owns one viewport's last published image, camera, measurements and render targets. */
public final class ViewRenderState
{
    private final String id;
    private final ViewGpuTiming timing;
    private final Matrix4f lastView = new Matrix4f();
    private final Matrix4f lastProjection = new Matrix4f();
    private final Camera camera = new Camera();
    private net.minecraft.client.Camera worldCamera;
    private boolean primary;
    private RenderTarget framebuffer;
    private Texture texture;
    private ViewFramebuffer front;
    private ViewFramebuffer back;
    private boolean targetPending;
    private int requestedWidth;
    private int requestedHeight;
    private float resolutionScale = 1F;
    private int preferredResolutionWidth = -1;
    private double requestedBudgetFraction = Double.NaN;
    private int requestedRefreshRate = ViewBudgetScheduler.DEFAULT_ACTIVE_HZ;
    private int refreshRate = ViewBudgetScheduler.DEFAULT_ACTIVE_HZ;
    private boolean active = true;
    private boolean visible = true;
    private boolean shadersEnabled = true;
    private boolean refreshRequested;
    private boolean valid;
    private Throwable failure;
    private Status status = Status.WARMING_UP;
    private long historyEpoch;
    private Film cameraFilm;
    private String cameraId;
    private boolean cameraSourceInitialized;
    private boolean orthographic;
    private long sourceHistoryEpoch = Long.MIN_VALUE;
    private long requestedFrame = Long.MIN_VALUE;
    private long sampleFrameId = -1L;
    private long lastRenderedNanos;
    private long nextRefreshNanos;
    private long lastAttemptNanos;
    private long lastCpuNanos;
    private long budgetNanos;
    private long priorityUntilNanos;
    private long lastQualityChangeNanos;
    private ViewTargetSize acceptedSize;
    private ViewTargetSize pendingSize;
    private long pendingSizeSince;

    public ViewRenderState(String id, int width, int height, boolean primary)
    {
        this(id, width, height, primary, new ViewGpuTiming());
    }

    public ViewRenderState(String id, int width, int height, boolean primary, ViewGpuTiming timing)
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("View id must not be blank");
        }

        this.id = id;
        this.primary = primary;
        this.timing = timing;
        this.setRequestedSize(width, height);
    }

    public String getId()
    {
        return this.id;
    }

    public boolean isPrimary()
    {
        return this.primary;
    }

    public void setPrimary(boolean primary)
    {
        this.primary = primary;
    }

    public RenderTarget getFramebuffer()
    {
        return this.front == null ? this.framebuffer : this.front.target();
    }

    public boolean hasRenderResources()
    {
        return this.front != null || this.back != null || this.framebuffer != null || this.texture != null;
    }

    /** The primary target is borrowed from BBSRendering and is never destroyed here. */
    public void setFramebuffer(RenderTarget framebuffer)
    {
        this.framebuffer = framebuffer;
    }

    public Texture getTexture()
    {
        return this.texture;
    }

    public void setTexture(Texture texture)
    {
        this.texture = texture;
    }

    public int getRequestedWidth()
    {
        return this.requestedWidth;
    }

    public int getRequestedHeight()
    {
        return this.requestedHeight;
    }

    public void setRequestedSize(int width, int height)
    {
        this.requestedWidth = Math.max(2, width);
        this.requestedHeight = Math.max(2, height);
    }

    public int getWidth()
    {
        return this.front != null ? this.front.target().viewWidth
            : this.framebuffer == null ? this.requestedWidth : this.framebuffer.viewWidth;
    }

    public int getHeight()
    {
        return this.front != null ? this.front.target().viewHeight
            : this.framebuffer == null ? this.requestedHeight : this.framebuffer.viewHeight;
    }

    public ViewTargetSize desiredSize()
    {
        return ViewTargetSize.select(this.requestedWidth, this.requestedHeight, this.resolutionScale);
    }

    /** Delay layout-only resizes; an explicit budget downgrade takes effect immediately. */
    public ViewTargetSize targetSize(long nowNanos)
    {
        ViewTargetSize desired = this.desiredSize();

        if (this.acceptedSize == null)
        {
            this.acceptedSize = desired;
        }
        else if (!desired.equals(this.acceptedSize))
        {
            if (!desired.equals(this.pendingSize))
            {
                this.pendingSize = desired;
                this.pendingSizeSince = nowNanos;
            }

            if (this.lastQualityChangeNanos == nowNanos || nowNanos - this.pendingSizeSince >= 500_000_000L)
            {
                this.acceptedSize = desired;
                this.pendingSize = null;
                this.invalidateHistory();
            }
        }
        else
        {
            this.pendingSize = null;
        }

        return this.acceptedSize;
    }

    /** Called only inside a render-state scope, before starting the world pass. */
    public ViewFramebuffer prepareTarget(ViewTargetSize size)
    {
        if (this.primary)
        {
            throw new IllegalStateException("The primary framebuffer is owned by BBSRendering");
        }

        if (this.requiresTargetAllocation(size))
        {
            ViewFramebuffer replacement = ViewFramebuffer.create(size.width(), size.height());
            ViewFramebuffer retired = this.back;
            this.back = replacement;

            if (retired != null)
            {
                retired.close();
            }
        }

        this.targetPending = true;
        return this.back;
    }

    public boolean requiresTargetAllocation(ViewTargetSize size)
    {
        return this.back == null || this.back.target().viewWidth != size.width() || this.back.target().viewHeight != size.height();
    }

    public net.minecraft.client.Camera getWorldCamera()
    {
        if (this.worldCamera == null)
        {
            this.worldCamera = new net.minecraft.client.Camera();
        }

        return this.worldCamera;
    }

    public float getResolutionScale()
    {
        return this.resolutionScale;
    }

    public void setResolutionScale(float scale)
    {
        this.resolutionScale = Float.isFinite(scale) ? Math.max(0.1F, Math.min(1F, scale)) : 1F;
    }

    public void changeQuality(float scale, long nowNanos)
    {
        this.setResolutionScale(scale);
        this.lastQualityChangeNanos = nowNanos;
        this.acceptedSize = this.desiredSize();
        this.pendingSize = null;
        this.invalidateHistory();
    }

    /** An edited performance preference starts a new adaptive-quality evaluation. */
    public void syncPerformancePreferences(double budgetFraction, int preferredWidth)
    {
        if (this.primary)
        {
            return;
        }

        double fraction = ViewBudgetScheduler.normalizeBudgetFraction(budgetFraction);
        int width = Math.max(0, preferredWidth);

        if (Double.compare(this.requestedBudgetFraction, fraction) == 0 && this.preferredResolutionWidth == width)
        {
            return;
        }

        this.requestedBudgetFraction = fraction;
        this.preferredResolutionWidth = width;
        ViewTargetSize previous = this.acceptedSize == null ? this.desiredSize() : this.acceptedSize;
        this.resolutionScale = 1F;
        this.setRefreshRate(this.requestedRefreshRate);
        this.lastQualityChangeNanos = 0L;
        this.acceptedSize = this.desiredSize();
        this.pendingSize = null;
        this.pendingSizeSince = 0L;

        if (!previous.equals(this.acceptedSize))
        {
            this.invalidateHistory();
        }

        this.requestRefresh();
    }

    public long getLastQualityChangeNanos()
    {
        return this.lastQualityChangeNanos;
    }

    public int getRefreshRate()
    {
        return this.refreshRate;
    }

    public void setRefreshRate(int refreshRate)
    {
        long previousInterval = 1_000_000_000L / this.refreshRate;
        this.refreshRate = Math.max(ViewBudgetScheduler.MINIMUM_HZ, Math.min(240, refreshRate));

        if (this.nextRefreshNanos != 0L)
        {
            this.nextRefreshNanos += 1_000_000_000L / this.refreshRate - previousInterval;
        }
    }

    public void changeRefreshRate(int refreshRate, long nowNanos)
    {
        this.setRefreshRate(refreshRate);
        this.lastQualityChangeNanos = nowNanos;
        /* Cadence changes neither camera history nor framebuffer dimensions. */
    }

    public void setRequestedRefreshRate(int refreshRate)
    {
        int value = Math.max(ViewBudgetScheduler.MINIMUM_HZ, Math.min(240, refreshRate));

        if (this.requestedRefreshRate != value)
        {
            this.requestedRefreshRate = value;
            this.setRefreshRate(value);
        }
    }

    boolean isRefreshDue(long nowNanos)
    {
        return !this.hasFrame() || (this.nextRefreshNanos == 0L
            ? this.getImageAgeNanos(nowNanos) >= 1_000_000_000L / this.refreshRate
            : nowNanos >= this.nextRefreshNanos);
    }

    void scheduleRefresh(long nowNanos)
    {
        long interval = 1_000_000_000L / this.refreshRate;

        if (this.nextRefreshNanos == 0L)
        {
            this.nextRefreshNanos = nowNanos + interval;
        }
        else
        {
            /* Keep a stable sampling clock, skipping missed periods without a
             * catch-up burst. Pass duration must not lengthen every interval. */
            long periods = Math.max(1L, (nowNanos - this.nextRefreshNanos) / interval + 1L);
            this.nextRefreshNanos += periods * interval;
        }
    }

    public boolean isActive()
    {
        return this.active;
    }

    public void setActive(boolean active)
    {
        this.active = active;
    }

    public boolean isVisible()
    {
        return this.visible;
    }

    public void setVisible(boolean visible)
    {
        this.visible = visible;
    }

    public boolean isShadersEnabled()
    {
        return this.shadersEnabled;
    }

    public void setShadersEnabled(boolean shadersEnabled)
    {
        if (this.shadersEnabled != shadersEnabled)
        {
            this.shadersEnabled = shadersEnabled;
            this.invalidateHistory();
        }
    }

    public boolean isRefreshRequested()
    {
        return this.refreshRequested;
    }

    public void requestRefresh()
    {
        this.requestRefresh(System.nanoTime());
    }

    public void requestRefresh(long nowNanos)
    {
        this.refreshRequested = true;
        this.priorityUntilNanos = nowNanos + 500_000_000L;
    }

    public boolean isInteracting(long nowNanos)
    {
        return nowNanos < this.priorityUntilNanos;
    }

    public void syncRequest(long frameId, long epoch)
    {
        if (this.sourceHistoryEpoch != epoch)
        {
            this.sourceHistoryEpoch = epoch;
            this.invalidateHistory();
        }

        if (frameId >= 0L && this.requestedFrame != frameId)
        {
            this.requestedFrame = frameId;
            this.requestRefresh();
        }
    }

    public long getHistoryEpoch()
    {
        return this.historyEpoch;
    }

    /** Fence the camera actually rendered, including output cuts while the editor has another binding. */
    public void syncCameraSource(Film film, int tick, String boundCameraId, boolean followOutput, boolean orthographic)
    {
        String selected = film == null ? null : followOutput ? film.resolveCameraId(tick)
            : boundCameraId == null ? null : film.hasCamera(boundCameraId) ? boundCameraId : Film.LEGACY_CAMERA_ID;

        if (!this.cameraSourceInitialized || this.cameraFilm != film || !Objects.equals(this.cameraId, selected)
            || this.orthographic != orthographic)
        {
            this.cameraSourceInitialized = true;
            this.cameraFilm = film;
            this.cameraId = selected;
            this.orthographic = orthographic;
            this.invalidateHistory();
        }
    }

    public void invalidateHistory()
    {
        this.historyEpoch += 1L;
        this.refreshRequested = true;
        /* History invalidation cannot invalidate the image already on screen:
         * that image and its picking matrices remain a single published sample. */
    }

    public boolean hasFrame()
    {
        return this.valid && this.sampleFrameId >= 0L;
    }

    public long getSampleFrameId()
    {
        return this.sampleFrameId;
    }

    public long getLastRenderedNanos()
    {
        return this.lastRenderedNanos;
    }

    public long getImageAgeNanos(long nowNanos)
    {
        return this.hasFrame() ? Math.max(0L, nowNanos - this.lastRenderedNanos) : Long.MAX_VALUE;
    }

    public long getLastAttemptNanos()
    {
        return this.lastAttemptNanos;
    }

    public long getLastCpuNanos()
    {
        return this.lastCpuNanos;
    }

    public long getLastGpuNanos()
    {
        return this.timing.getLastGpuNanos();
    }

    public ViewGpuTiming getTiming()
    {
        return this.timing;
    }

    public ViewGpuTiming.Spec timingSpec(ViewTargetSize size)
    {
        return new ViewGpuTiming.Spec(size.width(), size.height(), this.shadersEnabled);
    }

    public long getBudgetNanos()
    {
        return this.budgetNanos;
    }

    public boolean isBudgetExceeded()
    {
        return this.status == Status.BUDGET_UNSATISFIED
            || this.budgetNanos > 0L && Math.max(this.lastCpuNanos, this.getLastGpuNanos()) > this.budgetNanos;
    }

    public Throwable getFailure()
    {
        return this.failure;
    }

    public Status getStatus()
    {
        return this.status;
    }

    public void setStatus(Status status)
    {
        this.status = status;
    }

    public Matrix4f getLastView()
    {
        return new Matrix4f(this.lastView);
    }

    public Matrix4f getLastProjection()
    {
        return new Matrix4f(this.lastProjection);
    }

    public Camera getCamera()
    {
        return this.camera;
    }

    public void publish(long frameId, Matrix4f view, Matrix4f projection, Camera resolvedCamera, long cpuNanos)
    {
        this.publishAt(frameId, view, projection, resolvedCamera, cpuNanos, System.nanoTime());
    }

    public void publishAt(long frameId, Matrix4f view, Matrix4f projection, Camera resolvedCamera, long cpuNanos, long nowNanos)
    {
        if (this.targetPending)
        {
            ViewFramebuffer previous = this.front;
            this.front = this.back;
            this.back = previous;
            this.texture = this.front.texture();
            this.targetPending = false;
        }

        if (resolvedCamera != null)
        {
            this.camera.copy(resolvedCamera);
        }

        if (view != null)
        {
            this.lastView.set(view);
            this.camera.view.set(view);
        }

        if (projection != null)
        {
            this.lastProjection.set(projection);
            this.camera.projection.set(projection);
        }

        this.sampleFrameId = frameId;
        this.lastRenderedNanos = nowNanos;
        this.lastAttemptNanos = nowNanos;
        this.lastCpuNanos = Math.max(0L, cpuNanos);
        this.failure = null;
        this.valid = true;
        this.refreshRequested = false;
    }

    public void publish(long frameId, long cpuNanos)
    {
        this.publish(frameId, null, null, null, cpuNanos);
    }

    public void fail(Throwable failure, long cpuNanos)
    {
        this.failAt(failure, cpuNanos, System.nanoTime());
    }

    public void failAt(Throwable failure, long cpuNanos, long nowNanos)
    {
        this.targetPending = false;
        this.failure = failure;
        this.lastCpuNanos = Math.max(0L, cpuNanos);
        this.lastAttemptNanos = nowNanos;
        this.status = Status.FAILED;
        this.refreshRequested = false;
        this.historyEpoch++;
    }

    public void setBudgetNanos(long budgetNanos)
    {
        this.budgetNanos = Math.max(0L, budgetNanos);
    }

    public void releaseTargets()
    {
        ViewFramebuffer retiredFront = this.front;
        ViewFramebuffer retiredBack = this.back;
        this.front = null;
        this.back = null;
        this.targetPending = false;

        if (!this.primary)
        {
            this.texture = null;
            this.valid = false;
        }

        try (RenderStateRestorer releases = new RenderStateRestorer())
        {
            if (retiredFront != null)
            {
                releases.add(retiredFront::close);
            }

            if (retiredBack != null && retiredBack != retiredFront)
            {
                releases.add(retiredBack::close);
            }
        }
    }

    public void dispose()
    {
        try (RenderStateRestorer releases = new RenderStateRestorer())
        {
            releases.add(this.timing::close);
            releases.add(this::releaseTargets);
        }
        finally
        {
            this.framebuffer = null;
            this.texture = null;
            this.worldCamera = null;
            this.valid = false;
            this.failure = null;
            this.status = Status.WARMING_UP;
            this.refreshRequested = false;
            this.resolutionScale = 1F;
            this.preferredResolutionWidth = -1;
            this.requestedBudgetFraction = Double.NaN;
            this.refreshRate = this.requestedRefreshRate;
            this.acceptedSize = null;
            this.pendingSize = null;
            this.pendingSizeSince = 0L;
            this.sampleFrameId = -1L;
            this.lastRenderedNanos = 0L;
            this.nextRefreshNanos = 0L;
            this.lastAttemptNanos = 0L;
            this.lastCpuNanos = 0L;
            this.budgetNanos = 0L;
            this.lastQualityChangeNanos = 0L;
            this.priorityUntilNanos = 0L;
            this.sourceHistoryEpoch = Long.MIN_VALUE;
            this.cameraFilm = null;
            this.cameraId = null;
            this.cameraSourceInitialized = false;
            this.orthographic = false;
            this.requestedFrame = Long.MIN_VALUE;
            this.historyEpoch++;
        }
    }

    public enum Status
    {
        WARMING_UP, LIVE, THROTTLED, BUDGET_UNSATISFIED, FAILED, HIDDEN
    }
}
