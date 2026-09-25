package mchorse.bbs_mod.utils.iris;

import java.util.ArrayDeque;
import java.util.Deque;

/** Render-thread view identity, with publication separate from state restoration. */
public final class IrisViewState
{
    private static final ThreadLocal<Deque<Scope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);

    private IrisViewState()
    {}

    public static Scope enter(String viewId, boolean shadersEnabled, long historyEpoch)
    {
        return enter(viewId, shadersEnabled, historyEpoch, false, null, null, null);
    }

    public static Scope enter(String viewId, boolean shadersEnabled, long historyEpoch, boolean primary,
        ViewSampleClock.Sample sample, Runnable restore, Runnable publish)
    {
        Scope scope = new Scope(normalize(viewId), shadersEnabled, historyEpoch, primary, sample, restore, publish);

        SCOPES.get().push(scope);

        return scope;
    }

    public static Scope current()
    {
        return SCOPES.get().peek();
    }

    public static String activeViewId()
    {
        Scope scope = current();

        return scope == null ? null : scope.viewId;
    }

    public static boolean shadersEnabled()
    {
        Scope scope = current();

        return scope == null || scope.shadersEnabled;
    }

    public static boolean isPrimary()
    {
        Scope scope = current();

        return scope != null && scope.primary;
    }

    public static long historyEpoch()
    {
        Scope scope = current();

        return scope == null ? 0L : scope.historyEpoch;
    }

    public static ViewSampleClock.Sample sample()
    {
        Scope scope = current();

        return scope == null ? null : scope.sample;
    }

    public static void reportFailure(String viewId, Throwable failure)
    {
        Scope scope = current();

        if (scope != null && scope.viewId.equals(normalize(viewId)))
        {
            scope.failure = failure;
        }
    }

    public static Throwable failure()
    {
        Scope scope = current();

        return scope == null ? null : scope.failure;
    }

    private static String normalize(String viewId)
    {
        return viewId == null || viewId.isBlank() ? "main" : viewId;
    }

    public static final class Scope implements AutoCloseable
    {
        private final String viewId;
        private final boolean shadersEnabled;
        private final long historyEpoch;
        private final boolean primary;
        private final ViewSampleClock.Sample sample;
        private final Runnable restore;
        private final Runnable publish;
        private Throwable failure;
        private boolean closed;
        private boolean restored;
        private boolean committed;

        private Scope(String viewId, boolean shadersEnabled, long historyEpoch, boolean primary,
            ViewSampleClock.Sample sample, Runnable restore, Runnable publish)
        {
            this.viewId = viewId;
            this.shadersEnabled = shadersEnabled;
            this.historyEpoch = historyEpoch;
            this.primary = primary;
            this.sample = sample;
            this.restore = restore;
            this.publish = publish;
        }

        public String viewId()
        {
            return this.viewId;
        }

        public boolean shadersEnabled()
        {
            return this.shadersEnabled;
        }

        public long historyEpoch()
        {
            return this.historyEpoch;
        }

        public Throwable failure()
        {
            return this.failure;
        }

        public boolean isCommitted()
        {
            return this.committed;
        }

        /** Called only after the completed image and its matrices have been published. */
        public void commit()
        {
            if (this.committed)
            {
                return;
            }

            if (!this.restored || this.failure != null)
            {
                throw new IllegalStateException("Cannot publish an unrestored or failed view pass", this.failure);
            }

            if (this.publish != null)
            {
                this.publish.run();
            }

            this.committed = true;
        }

        @Override
        public void close()
        {
            if (this.closed)
            {
                return;
            }

            Deque<Scope> scopes = SCOPES.get();

            if (scopes.peek() != this)
            {
                throw new IllegalStateException("View scopes must close in reverse order");
            }

            try
            {
                if (this.restore != null)
                {
                    this.restore.run();
                }

                this.restored = true;
            }
            catch (RuntimeException | Error exception)
            {
                this.failure = exception;

                throw exception;
            }
            finally
            {
                this.closed = true;
                scopes.pop();

                if (scopes.isEmpty())
                {
                    SCOPES.remove();
                }
            }
        }
    }
}
