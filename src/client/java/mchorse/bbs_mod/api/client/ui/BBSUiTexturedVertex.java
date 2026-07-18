package mchorse.bbs_mod.api.client.ui;

public final class BBSUiTexturedVertex
{
    private final float x;
    private final float y;
    private final float u;
    private final float v;

    public BBSUiTexturedVertex(float x, float y, float u, float v)
    {
        this.x = x;
        this.y = y;
        this.u = u;
        this.v = v;
    }

    public float x()
    {
        return this.x;
    }

    public float y()
    {
        return this.y;
    }

    public float u()
    {
        return this.u;
    }

    public float v()
    {
        return this.v;
    }
}
