package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiGlyphRun implements BBSUiDrawCommand
{
    private final String text;
    private final BBSUiVertex topLeft;
    private final BBSUiVertex bottomLeft;
    private final BBSUiVertex bottomRight;
    private final BBSUiVertex topRight;
    private final boolean shadow;

    public BBSUiGlyphRun(
        String text,
        BBSUiVertex topLeft,
        BBSUiVertex bottomLeft,
        BBSUiVertex bottomRight,
        BBSUiVertex topRight,
        boolean shadow
    )
    {
        this.text = Objects.requireNonNull(text, "text");
        this.topLeft = Objects.requireNonNull(topLeft, "topLeft");
        this.bottomLeft = Objects.requireNonNull(bottomLeft, "bottomLeft");
        this.bottomRight = Objects.requireNonNull(bottomRight, "bottomRight");
        this.topRight = Objects.requireNonNull(topRight, "topRight");
        this.shadow = shadow;
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.GLYPH_RUN;
    }

    public String text()
    {
        return this.text;
    }

    public BBSUiVertex topLeft()
    {
        return this.topLeft;
    }

    public BBSUiVertex bottomLeft()
    {
        return this.bottomLeft;
    }

    public BBSUiVertex bottomRight()
    {
        return this.bottomRight;
    }

    public BBSUiVertex topRight()
    {
        return this.topRight;
    }

    public boolean shadow()
    {
        return this.shadow;
    }
}
