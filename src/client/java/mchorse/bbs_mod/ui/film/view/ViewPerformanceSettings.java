package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.client.render.multiview.ViewBudgetScheduler;
import mchorse.bbs_mod.client.render.multiview.ViewTargetSize;
import mchorse.bbs_mod.data.types.MapType;

/** Editor-only performance preferences; Film cameras and export settings are unaffected. */
public final class ViewPerformanceSettings
{
    public static final double DEFAULT_BUDGET_FRACTION = ViewBudgetScheduler.DEFAULT_BUDGET_FRACTION;
    public static final double MINIMUM_BUDGET_FRACTION = ViewBudgetScheduler.MINIMUM_BUDGET_FRACTION;
    public static final double MAXIMUM_BUDGET_FRACTION = ViewBudgetScheduler.MAXIMUM_BUDGET_FRACTION;

    private double auxiliaryBudgetFraction = DEFAULT_BUDGET_FRACTION;

    public double getAuxiliaryBudgetFraction()
    {
        return this.auxiliaryBudgetFraction;
    }

    public void setAuxiliaryBudgetFraction(double fraction)
    {
        this.auxiliaryBudgetFraction = ViewBudgetScheduler.normalizeBudgetFraction(fraction);
    }

    public MapType toData()
    {
        MapType data = new MapType();

        data.putDouble("auxiliary_budget", this.auxiliaryBudgetFraction);

        return data;
    }

    public void fromData(MapType data)
    {
        this.setAuxiliaryBudgetFraction(data.getDouble("auxiliary_budget", DEFAULT_BUDGET_FRACTION));
    }

    public static int normalizeResolutionWidth(int width)
    {
        return width <= 0 ? 0 : Math.max(320, Math.min(1920, width));
    }

    public static ViewTargetSize resolution(int width, int availableWidth, int availableHeight, int outputWidth, int outputHeight)
    {
        double aspect = Math.max(1, outputWidth) / (double) Math.max(1, outputHeight);
        int preferredWidth = normalizeResolutionWidth(width);
        double fittedWidth = preferredWidth == 0
            ? Math.min(Math.max(2, availableWidth), Math.max(2, availableHeight) * aspect) : preferredWidth;
        double minimumWidth = Math.max(ViewBudgetScheduler.MINIMUM_WIDTH, ViewBudgetScheduler.MINIMUM_HEIGHT * aspect);
        int requestedWidth = Math.max(2, (int) Math.ceil(Math.max(fittedWidth, minimumWidth)));
        int requestedHeight = Math.max(2, (int) Math.round(requestedWidth / aspect));

        return ViewTargetSize.select(requestedWidth, requestedHeight, 1F);
    }
}
