package mchorse.bbs_mod.ui.dashboard.panels;

public interface IFlightSupported
{
    /** Whether dashboard re-entry should immediately restore orbit/flight input. */
    public default boolean shouldEnableFlightOnRestore()
    {
        return true;
    }

    public default boolean supportsRollFOVControl()
    {
        return true;
    }
}
