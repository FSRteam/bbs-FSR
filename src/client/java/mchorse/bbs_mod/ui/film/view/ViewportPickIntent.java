package mchorse.bbs_mod.ui.film.view;

/** A short click may outlive the displayed view's picking buffer. */
public final class ViewportPickIntent<T>
{
    private static final int DRAG_THRESHOLD = 3;

    public record Target(Object view, Object film, Object replay, long historyEpoch, int mode, int tick, ViewFrameGeometry frame)
    {
        private boolean matches(Target other)
        {
            return other != null && this.view == other.view && this.film == other.film
                && this.replay == other.replay && this.historyEpoch == other.historyEpoch
                && this.mode == other.mode && this.tick == other.tick && this.frame.equals(other.frame);
        }
    }

    public record Input(int x, int y, int button, boolean alt, boolean control, boolean shift)
    {}

    public record Completion<T>(long generation, Target target, Input input, T result)
    {}

    private long nextGeneration;
    private long generation;
    private long pointerGeneration;
    private long menuGeneration;
    private Target target;
    private Input input;
    private boolean released;
    private boolean resolved;
    private T result;

    public long begin(Target target, Input input, long pointerGeneration, long menuGeneration)
    {
        this.cancel();

        if (target == null || target.view == null || target.film == null || input.button < 0)
        {
            return 0L;
        }

        this.nextGeneration = this.nextGeneration == Long.MAX_VALUE ? 1L : this.nextGeneration + 1L;
        this.generation = this.nextGeneration;
        this.pointerGeneration = pointerGeneration;
        this.menuGeneration = menuGeneration;
        this.target = target;
        this.input = input;

        return this.generation;
    }

    public boolean validate(Target target, long pointerGeneration, long menuGeneration, boolean available)
    {
        if (this.generation == 0L)
        {
            return false;
        }

        if (!available || !this.target.matches(target) || this.pointerGeneration != pointerGeneration
            || this.menuGeneration != menuGeneration)
        {
            this.cancel();

            return false;
        }

        return true;
    }

    public void move(int x, int y)
    {
        if (this.generation != 0L && !this.released
            && (Math.abs((long) x - this.input.x) > DRAG_THRESHOLD || Math.abs((long) y - this.input.y) > DRAG_THRESHOLD))
        {
            this.cancel();
        }
    }

    public boolean release(int button, long generation, int x, int y, boolean dragged)
    {
        if (!this.isOwnedBy(button) || this.generation != generation || this.released)
        {
            return false;
        }

        this.move(x, y);

        if (dragged)
        {
            this.cancel();
        }
        else if (this.generation != 0L)
        {
            this.released = true;
        }

        return true;
    }

    public void resolve(long generation, T result)
    {
        if (generation != 0L && this.generation == generation && !this.resolved)
        {
            this.result = result;
            this.resolved = true;
        }
    }

    public Completion<T> take(long generation)
    {
        if (this.generation != generation || !this.isReady())
        {
            return null;
        }

        Completion<T> completion = new Completion<>(this.generation, this.target, this.input, this.result);

        this.cancel();

        return completion;
    }

    public boolean isOwnedBy(int button)
    {
        return this.generation != 0L && this.input.button == button;
    }

    public boolean isReady()
    {
        return this.generation != 0L && this.released && this.resolved;
    }

    public boolean isResolved()
    {
        return this.resolved;
    }

    public long generation()
    {
        return this.generation;
    }

    public Input input()
    {
        return this.input;
    }

    public void cancel()
    {
        this.generation = 0L;
        this.target = null;
        this.input = null;
        this.result = null;
        this.released = false;
        this.resolved = false;
    }
}
