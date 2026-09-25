package mchorse.bbs_mod.client.render.multiview;

/** Finite preview size tiers which preserve the camera's output aspect ratio. */
public record ViewTargetSize(int width, int height)
{
    private static final int[] WIDTH_TIERS = {160, 240, 320, 480, 640, 960, 1280, 1600, 1920, 2048};
    private static final int MAXIMUM_SIDE = 2048;

    public static ViewTargetSize select(int requestedWidth, int requestedHeight, float scale)
    {
        int availableWidth = Math.max(2, requestedWidth);
        int availableHeight = Math.max(2, requestedHeight);
        double aspect = availableWidth / (double) availableHeight;
        double minimum = Math.max(ViewBudgetScheduler.MINIMUM_WIDTH, ViewBudgetScheduler.MINIMUM_HEIGHT * aspect);
        double ceiling = Math.min(Math.max(availableWidth, minimum), MAXIMUM_SIDE * Math.min(1D, aspect));
        double factor = Float.isFinite(scale) ? Math.max(0.1D, Math.min(1D, scale)) : 1D;
        int desiredWidth = Math.max(2, (int) Math.floor(Math.min(ceiling, Math.max(minimum, ceiling * factor))));
        int tierWidth = desiredWidth;

        for (int tier : WIDTH_TIERS)
        {
            if (tier <= desiredWidth && (tier >= minimum || ceiling < minimum))
            {
                tierWidth = tier;
            }
        }

        return new ViewTargetSize(tierWidth, Math.min(MAXIMUM_SIDE, Math.max(2, (int) Math.round(tierWidth / aspect))));
    }

    public boolean meetsMinimum()
    {
        return this.width >= ViewBudgetScheduler.MINIMUM_WIDTH && this.height >= ViewBudgetScheduler.MINIMUM_HEIGHT;
    }
}
