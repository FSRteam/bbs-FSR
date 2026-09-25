package mchorse.bbs_mod.client.render.multiview;

/** Owns the outer scene boundary, independently of how many world passes are requested. */
public final class FilmViewRenderer
{
    private boolean active;
    private long preparedFrames;

    public boolean isActive()
    {
        return this.active;
    }

    public long getPreparedFrames()
    {
        return this.preparedFrames;
    }

    public void renderFrame(Runnable prepare, Runnable auxiliary, Runnable primary)
    {
        if (this.active)
        {
            primary.run();
            return;
        }

        this.active = true;

        try
        {
            this.preparedFrames++;
            prepare.run();
            auxiliary.run();
            primary.run();
        }
        finally
        {
            this.active = false;
        }
    }
}
