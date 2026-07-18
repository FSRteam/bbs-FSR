package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiCursor
{
    private final float x;
    private final float y;
    private final BBSUiCursorShape shape;

    public BBSUiCursor(float x, float y, BBSUiCursorShape shape)
    {
        this.x = x;
        this.y = y;
        this.shape = Objects.requireNonNull(shape, "shape");
    }

    public float x()
    {
        return this.x;
    }

    public float y()
    {
        return this.y;
    }

    public BBSUiCursorShape shape()
    {
        return this.shape;
    }
}
