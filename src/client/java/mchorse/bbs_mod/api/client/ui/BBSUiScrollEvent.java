package mchorse.bbs_mod.api.client.ui;

public final class BBSUiScrollEvent implements BBSUiInputEvent
{
    /**
     * Legacy four-argument events do not carry an event-time modifier mask.
     * A single-event batch can recover it from the authoritative held-state
     * snapshot, while a multi-event producer must use the five-argument
     * constructor so modifier transitions remain ordered.
     */
    public static final int UNSPECIFIED_MODIFIERS = -1;

    private final double x;
    private final double y;
    private final double horizontalAmount;
    private final double verticalAmount;
    private final int modifiers;

    public BBSUiScrollEvent(double x, double y, double horizontalAmount, double verticalAmount)
    {
        this(x, y, horizontalAmount, verticalAmount, UNSPECIFIED_MODIFIERS);
    }

    public BBSUiScrollEvent(double x, double y, double horizontalAmount, double verticalAmount, int modifiers)
    {
        this.x = x;
        this.y = y;
        this.horizontalAmount = horizontalAmount;
        this.verticalAmount = verticalAmount;
        this.modifiers = modifiers;
    }

    @Override
    public BBSUiInputEventType type()
    {
        return BBSUiInputEventType.SCROLL;
    }

    public double x()
    {
        return this.x;
    }

    public double y()
    {
        return this.y;
    }

    public double horizontalAmount()
    {
        return this.horizontalAmount;
    }

    public double verticalAmount()
    {
        return this.verticalAmount;
    }

    public int modifiers()
    {
        return this.modifiers;
    }

    public boolean hasSpecifiedModifiers()
    {
        return this.modifiers != UNSPECIFIED_MODIFIERS;
    }
}
