package mchorse.bbs_mod.client.render.surface;

/** Identifies one issued capture inside an opaque surface stream generation. */
record BBSRenderSurfaceStamp(long generation, long sequence)
{
    BBSRenderSurfaceStamp
    {
        if (generation <= 0L)
        {
            throw new IllegalArgumentException("surface generation must be positive");
        }

        if (sequence <= 0L)
        {
            throw new IllegalArgumentException("surface sequence must be positive");
        }
    }
}
