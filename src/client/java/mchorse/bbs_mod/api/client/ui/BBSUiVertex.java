package mchorse.bbs_mod.api.client.ui;

public final class BBSUiVertex
{
    private final float x;
    private final float y;
    private final int color;

    public BBSUiVertex(float x, float y, int color)
    {
        this.x = x;
        this.y = y;
        this.color = color;
    }

    public float x()
    {
        return this.x;
    }

    public float y()
    {
        return this.y;
    }

    public int color()
    {
        return this.color;
    }
}
