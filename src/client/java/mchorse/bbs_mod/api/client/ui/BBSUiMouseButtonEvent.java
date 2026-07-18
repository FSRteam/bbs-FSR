package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiMouseButtonEvent implements BBSUiInputEvent
{
    private final double x;
    private final double y;
    private final int button;
    private final BBSUiInputAction action;
    private final int modifiers;

    public BBSUiMouseButtonEvent(double x, double y, int button, BBSUiInputAction action, int modifiers)
    {
        this.x = x;
        this.y = y;
        this.button = button;
        this.action = Objects.requireNonNull(action, "action");
        this.modifiers = modifiers;
    }

    @Override
    public BBSUiInputEventType type()
    {
        return BBSUiInputEventType.MOUSE_BUTTON;
    }

    public double x()
    {
        return this.x;
    }

    public double y()
    {
        return this.y;
    }

    public int button()
    {
        return this.button;
    }

    public BBSUiInputAction action()
    {
        return this.action;
    }

    public int modifiers()
    {
        return this.modifiers;
    }
}
