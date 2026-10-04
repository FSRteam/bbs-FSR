package mchorse.bbs_mod.client.render.multiview;

import java.util.Comparator;

/** A shared secondary-view budget; primary quality and refresh are never reduced. */
public final class ViewBudgetScheduler
{
    public static final int DEFAULT_ACTIVE_HZ = 30;
    public static final int DEFAULT_IDLE_HZ = 15;
    public static final int MINIMUM_HZ = 15;
    public static final int MINIMUM_WIDTH = 320;
    public static final int MINIMUM_HEIGHT = 180;
    public static final double DEFAULT_BUDGET_FRACTION = 0.20D;
    public static final double MINIMUM_BUDGET_FRACTION = 0.05D;
    public static final double MAXIMUM_BUDGET_FRACTION = 2D;

    private static final long QUALITY_COOLDOWN_NANOS = 1_000_000_000L;
    private static final long FAILURE_RETRY_NANOS = 1_000_000_000L;

    private long frameBudgetNanos;
    private long primaryCostNanos;
    private double budgetFraction = DEFAULT_BUDGET_FRACTION;
    private long remainingNanos;
    private boolean baselineMeasured;
    private boolean calibrationUsed;
    private long reservedNanos;

    public ViewBudgetScheduler()
    {
        this(60D);
    }

    public ViewBudgetScheduler(double primaryFramesPerSecond)
    {
        double fps = Double.isFinite(primaryFramesPerSecond) && primaryFramesPerSecond > 1D
            ? primaryFramesPerSecond : 60D;
        this.primaryCostNanos = Math.max(1L, Math.round(1_000_000_000D / fps));
        this.frameBudgetNanos = Math.max(1L, Math.round(this.primaryCostNanos * this.budgetFraction));
    }

    public static double normalizeBudgetFraction(double fraction)
    {
        return Double.isFinite(fraction)
            ? Math.max(MINIMUM_BUDGET_FRACTION, Math.min(MAXIMUM_BUDGET_FRACTION, fraction)) : DEFAULT_BUDGET_FRACTION;
    }

    public void setBudgetFraction(double fraction)
    {
        this.budgetFraction = normalizeBudgetFraction(fraction);
    }

    public double getBudgetFraction()
    {
        return this.budgetFraction;
    }

    public void beginFrame(long primaryCostNanos, boolean measured)
    {
        this.baselineMeasured = measured && primaryCostNanos > 0L;

        if (this.baselineMeasured)
        {
            this.primaryCostNanos = primaryCostNanos;
        }

        this.frameBudgetNanos = Math.max(1L, Math.round(this.primaryCostNanos * this.budgetFraction));
        this.remainingNanos = this.frameBudgetNanos;
        this.calibrationUsed = false;
        this.reservedNanos = 0L;
    }

    public Comparator<ViewRenderState> priority(long nowNanos)
    {
        /* Age breaks ties and prevents a permanent first-view monopoly. */
        return Comparator.<ViewRenderState, Boolean>comparing(view -> !view.isInteracting(nowNanos))
            .thenComparingLong(ViewRenderState::getLastRenderedNanos)
            .thenComparing(ViewRenderState::getId);
    }

    public boolean shouldRender(ViewRenderState view, long nowNanos)
    {
        if (view == null || view.isPrimary())
        {
            return false;
        }

        if (!view.isActive() || !view.isVisible())
        {
            view.setStatus(ViewRenderState.Status.HIDDEN);
            return false;
        }

        view.getTiming().poll();
        view.setBudgetNanos(this.frameBudgetNanos);

        if (view.getFailure() != null && nowNanos - view.getLastAttemptNanos() < FAILURE_RETRY_NANOS)
        {
            view.setStatus(ViewRenderState.Status.FAILED);
            return false;
        }

        ViewTargetSize size = view.targetSize(nowNanos);
        ViewGpuTiming.Spec spec = view.timingSpec(size);
        long estimate = view.getTiming().getEstimatedNanos(spec);

        if (!view.isRefreshDue(nowNanos))
        {
            return false;
        }

        if (estimate > this.frameBudgetNanos)
        {
            if (this.reduceQuality(view, nowNanos))
            {
                view.setStatus(ViewRenderState.Status.THROTTLED);
                return false;
            }

            view.setStatus(ViewRenderState.Status.BUDGET_UNSATISFIED);

            /* Keep the minimum usable cadence even when the measured cost
             * cannot meet the FPS budget. The status remains a budget failure. */
            if (!this.minimumCadenceDue(view, nowNanos))
            {
                return false;
            }

            this.reserve(view, estimate, nowNanos);
            return true;
        }

        if (!view.getTiming().hasSample(spec) || estimate == 0L)
        {
            if (this.calibrationUsed || this.remainingNanos != this.frameBudgetNanos)
            {
                view.setStatus(ViewRenderState.Status.WARMING_UP);
                return false;
            }

            this.calibrationUsed = true;
            this.reserve(view, this.frameBudgetNanos, nowNanos);
            view.setStatus(ViewRenderState.Status.WARMING_UP);
            return true;
        }

        if (estimate > this.remainingNanos)
        {
            if (view.hasFrame() && this.minimumCadenceDue(view, nowNanos))
            {
                view.setStatus(ViewRenderState.Status.BUDGET_UNSATISFIED);
                this.reserve(view, estimate, nowNanos);
                return true;
            }

            view.setStatus(ViewRenderState.Status.THROTTLED);
            return false;
        }

        this.reserve(view, estimate, nowNanos);
        view.setStatus(!size.meetsMinimum() ? ViewRenderState.Status.BUDGET_UNSATISFIED
            : !this.baselineMeasured || !view.getTiming().hasGpuSample(spec)
                ? ViewRenderState.Status.WARMING_UP : ViewRenderState.Status.LIVE);
        return true;
    }

    private boolean minimumCadenceDue(ViewRenderState view, long nowNanos)
    {
        if (view.getRefreshRate() != MINIMUM_HZ)
        {
            view.changeRefreshRate(MINIMUM_HZ, nowNanos);
        }

        return view.isRefreshDue(nowNanos);
    }

    private void reserve(ViewRenderState view, long estimate, long nowNanos)
    {
        view.scheduleRefresh(nowNanos);
        this.reservedNanos = estimate;
        this.remainingNanos = Math.max(0L, this.remainingNanos - estimate);
    }

    public void record(ViewRenderState view, long elapsedNanos)
    {
        this.recordAt(view, elapsedNanos, System.nanoTime());
    }

    public void recordAt(ViewRenderState view, long elapsedNanos, long nowNanos)
    {
        if (view == null || view.isPrimary())
        {
            return;
        }

        long measuredCost = Math.max(Math.max(0L, elapsedNanos),
            view.getTiming().getEstimatedNanos(view.timingSpec(view.targetSize(nowNanos))));
        this.remainingNanos = Math.max(0L, this.remainingNanos - Math.max(0L, measuredCost - this.reservedNanos));
        this.reservedNanos = 0L;

        if (measuredCost > this.frameBudgetNanos && view.getFailure() == null)
        {
            view.setStatus(ViewRenderState.Status.BUDGET_UNSATISFIED);
        }
    }

    private boolean reduceQuality(ViewRenderState view, long nowNanos)
    {
        if (view.getLastQualityChangeNanos() != 0L
            && nowNanos - view.getLastQualityChangeNanos() < QUALITY_COOLDOWN_NANOS)
        {
            return false;
        }

        ViewTargetSize current = view.desiredSize();
        float scale = view.getResolutionScale();

        while (scale > 0.1F)
        {
            scale = Math.max(0.1F, scale * 0.75F);
            ViewTargetSize smaller = ViewTargetSize.select(view.getRequestedWidth(), view.getRequestedHeight(), scale);

            if (smaller.width() < current.width() || smaller.height() < current.height())
            {
                view.changeQuality(scale, nowNanos);
                return true;
            }
        }

        if (view.getRefreshRate() > MINIMUM_HZ)
        {
            view.changeRefreshRate(MINIMUM_HZ, nowNanos);
            return true;
        }

        return false;
    }

    public long getFrameBudgetNanos()
    {
        return this.frameBudgetNanos;
    }

    public long getRemainingNanos()
    {
        return this.remainingNanos;
    }
}
