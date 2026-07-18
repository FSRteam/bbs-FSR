package mchorse.bbs_mod.ui.dashboard.panels;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Selects the current repository until a data session starts, then pins that
 * repository for the rest of the panel lifetime. This lets a dashboard opened
 * before the server handshake refresh from local to remote, while a film that
 * was actually opened from a server cannot switch to the local repository
 * during disconnect.
 */
public final class RepositorySession<T>
{
    private final Supplier<? extends T> selector;
    private T repository;
    private boolean pinned;

    public RepositorySession(Supplier<? extends T> selector)
    {
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    public synchronized T get()
    {
        if (!this.pinned)
        {
            this.repository = Objects.requireNonNull(this.selector.get(), "repository");
        }

        return this.repository;
    }

    /** Freeze the selector at the repository that owns the new data session. */
    public synchronized T pin()
    {
        if (!this.pinned)
        {
            return this.pin(Objects.requireNonNull(this.selector.get(), "repository"));
        }

        return this.repository;
    }

    /** Pin the exact repository that produced an asynchronous load result. */
    public synchronized T pin(T repository)
    {
        if (!this.pinned)
        {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.pinned = true;
        }

        return this.repository;
    }

    public synchronized boolean isPinned()
    {
        return this.pinned;
    }
}
