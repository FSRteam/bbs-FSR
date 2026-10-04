package mchorse.bbs_mod.utils.iris;

/** The clock advances with published images, not with skipped window frames. */
public final class ViewSampleClock
{
    private static final int PERIOD = 720720;

    private Sample pending;
    private long lastPublishedNanos;
    private int nextCounter;
    private boolean published;

    public Sample begin(long now, float initialFrameTime)
    {
        float elapsed = this.published ? Math.max(0L, now - this.lastPublishedNanos) / 1_000_000_000F : initialFrameTime;

        return this.beginFixedStep(now, elapsed);
    }

    /** Offline output follows film time even when one image takes seconds to render. */
    public Sample beginFixedStep(long now, float elapsed)
    {
        if (!Float.isFinite(elapsed) || elapsed < 0F)
        {
            elapsed = 0F;
        }

        this.pending = new Sample(this.nextCounter, elapsed, now);

        return this.pending;
    }

    public static float exportFrameTime(double frameRate, boolean advancing)
    {
        return advancing && Double.isFinite(frameRate) && frameRate > 0D ? (float) (1D / frameRate) : 0F;
    }

    public void publish(Sample sample)
    {
        if (sample == null || sample != this.pending)
        {
            throw new IllegalStateException("Cannot publish an obsolete view sample");
        }

        this.lastPublishedNanos = sample.timeNanos;
        this.nextCounter = (sample.frameCounter + 1) % PERIOD;
        this.published = true;
        this.pending = null;
    }

    public record Sample(int frameCounter, float frameTime, long timeNanos)
    {}
}
