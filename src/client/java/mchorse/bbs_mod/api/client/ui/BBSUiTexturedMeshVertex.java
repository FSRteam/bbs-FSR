package mchorse.bbs_mod.api.client.ui;

/** A transformed textured-mesh vertex with native per-vertex ARGB tint. */
public final class BBSUiTexturedMeshVertex
{
    private final float x;
    private final float y;
    private final float u;
    private final float v;
    private final int color;

    public BBSUiTexturedMeshVertex(float x, float y, float u, float v, int color)
    {
        this.x = x;
        this.y = y;
        this.u = u;
        this.v = v;
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

    public float u()
    {
        return this.u;
    }

    public float v()
    {
        return this.v;
    }

    public int color()
    {
        return this.color;
    }
}
