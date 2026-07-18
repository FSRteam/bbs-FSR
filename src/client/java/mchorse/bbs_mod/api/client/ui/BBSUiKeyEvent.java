package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiKeyEvent implements BBSUiInputEvent
{
    private final int keyCode;
    private final int scanCode;
    private final BBSUiInputAction action;
    private final int modifiers;

    public BBSUiKeyEvent(int keyCode, int scanCode, BBSUiInputAction action, int modifiers)
    {
        this.keyCode = keyCode;
        this.scanCode = scanCode;
        this.action = Objects.requireNonNull(action, "action");
        this.modifiers = modifiers;
    }

    @Override
    public BBSUiInputEventType type()
    {
        return BBSUiInputEventType.KEY;
    }

    public int keyCode()
    {
        return this.keyCode;
    }

    public int scanCode()
    {
        return this.scanCode;
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
