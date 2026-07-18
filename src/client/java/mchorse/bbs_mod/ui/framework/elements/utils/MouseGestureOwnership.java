package mchorse.bbs_mod.ui.framework.elements.utils;

/**
 * Tracks the mouse button that owns one continuous UI gesture. Releases from
 * other buttons must not finish a drag that they did not start.
 */
public final class MouseGestureOwnership
{
    private int button = -1;
    private long generation;
    private long nextGeneration;

    public boolean acquire(int button)
    {
        return this.acquireToken(button) != 0L;
    }

    /** Acquire the owner and return a token which identifies this generation. */
    public long acquireToken(int button)
    {
        if (button < 0 || this.isActive())
        {
            return 0L;
        }

        this.nextGeneration = this.nextGeneration == Long.MAX_VALUE ? 1L : this.nextGeneration + 1L;
        this.button = button;
        this.generation = this.nextGeneration;

        return this.generation;
    }

    public boolean isActive()
    {
        return this.button >= 0;
    }

    public boolean isOwnedBy(int button)
    {
        return this.button == button;
    }

    public boolean isOwnedBy(int button, long generation)
    {
        return this.button == button && this.generation == generation && generation != 0L;
    }

    public boolean release(int button)
    {
        if (!this.isOwnedBy(button))
        {
            return false;
        }

        this.button = -1;
        this.generation = 0L;

        return true;
    }

    /** Release only the captured generation; a reentrant replacement is preserved. */
    public boolean release(int button, long generation)
    {
        if (!this.isOwnedBy(button, generation))
        {
            return false;
        }

        this.button = -1;
        this.generation = 0L;

        return true;
    }

    public long generation()
    {
        return this.generation;
    }

    /** Explicit cancellation is separate from an unrelated button release. */
    public void cancel()
    {
        this.button = -1;
        this.generation = 0L;
    }
}
