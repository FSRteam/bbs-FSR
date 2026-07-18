package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiTextEvent implements BBSUiInputEvent
{
    private final String text;
    private final int modifiers;

    public BBSUiTextEvent(String text, int modifiers)
    {
        this.text = Objects.requireNonNull(text, "text");
        this.modifiers = modifiers;
    }

    @Override
    public BBSUiInputEventType type()
    {
        return BBSUiInputEventType.TEXT;
    }

    public String text()
    {
        return this.text;
    }

    public int modifiers()
    {
        return this.modifiers;
    }
}
