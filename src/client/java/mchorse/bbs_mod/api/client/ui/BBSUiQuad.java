package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

public final class BBSUiQuad implements BBSUiDrawCommand
{
    private final BBSUiVertex topLeft;
    private final BBSUiVertex bottomLeft;
    private final BBSUiVertex bottomRight;
    private final BBSUiVertex topRight;

    public BBSUiQuad(BBSUiVertex topLeft, BBSUiVertex bottomLeft, BBSUiVertex bottomRight, BBSUiVertex topRight)
    {
        this.topLeft = Objects.requireNonNull(topLeft, "topLeft");
        this.bottomLeft = Objects.requireNonNull(bottomLeft, "bottomLeft");
        this.bottomRight = Objects.requireNonNull(bottomRight, "bottomRight");
        this.topRight = Objects.requireNonNull(topRight, "topRight");
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.QUAD;
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
}
