package mchorse.bbs_mod.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Supplies a stable, playback-scoped client crack-overlay identity. */
public final class BreakProgressContext
{
    private static final AtomicInteger NEXT_ID = new AtomicInteger(-1);
    private static final ThreadLocal<Integer> CURRENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Session> CURRENT_SESSION = new ThreadLocal<>();

    private BreakProgressContext()
    {}

    public static int allocate()
    {
        return NEXT_ID.getAndUpdate((current) -> current == Integer.MIN_VALUE ? -1 : current - 1);
    }

    public static Session createSession()
    {
        return new Session(allocate());
    }

    public static void withId(int id, Runnable runnable)
    {
        withId(id, () ->
        {
            runnable.run();

            return null;
        });
    }

    public static <T> T withId(int id, Supplier<T> supplier)
    {
        Integer previous = CURRENT_ID.get();

        CURRENT_ID.set(id);

        try
        {
            return supplier.get();
        }
        finally
        {
            if (previous == null)
            {
                CURRENT_ID.remove();
            }
            else
            {
                CURRENT_ID.set(previous);
            }
        }
    }

    public static <T> T withSession(Session session, Supplier<T> supplier)
    {
        Objects.requireNonNull(session, "session");

        Session previous = CURRENT_SESSION.get();
        CURRENT_SESSION.set(session);

        try
        {
            return withId(session.id(), supplier);
        }
        finally
        {
            if (previous == null)
            {
                CURRENT_SESSION.remove();
            }
            else
            {
                CURRENT_SESSION.set(previous);
            }
        }
    }

    public static int currentOr(int fallback)
    {
        Integer current = CURRENT_ID.get();

        return current == null ? fallback : current;
    }

    public static void updateOrDirect(int fallbackId, ServerLevel level, BlockPos pos, int progress)
    {
        Session session = CURRENT_SESSION.get();

        if (session == null)
        {
            level.destroyBlockProgress(currentOr(fallbackId), pos, progress);
        }
        else
        {
            session.update(level, pos, progress);
        }
    }

    public static void clearCurrent()
    {
        Session session = CURRENT_SESSION.get();

        if (session != null)
        {
            session.clear();
        }
    }

    /** Owns the last crack overlay emitted for one exact playback replay. */
    public static final class Session
    {
        private final int id;
        private Object levelIdentity;
        private ProgressEmitter emitter;
        private BlockPos pos;
        private boolean active;

        private Session(int id)
        {
            this.id = id;
        }

        public int id()
        {
            return this.id;
        }

        public boolean isActive()
        {
            return this.active;
        }

        public void update(ServerLevel level, BlockPos pos, int progress)
        {
            Objects.requireNonNull(level, "level");

            this.update(level, level::destroyBlockProgress, pos, progress);
        }

        void update(Object levelIdentity, ProgressEmitter emitter, BlockPos pos, int progress)
        {
            Objects.requireNonNull(levelIdentity, "levelIdentity");
            Objects.requireNonNull(emitter, "emitter");
            Objects.requireNonNull(pos, "pos");

            if (progress < 0 || progress >= 10)
            {
                this.clear();

                return;
            }

            if (this.active && (this.levelIdentity != levelIdentity || !this.pos.equals(pos)))
            {
                this.clear();
            }

            emitter.emit(this.id, pos, progress);
            this.levelIdentity = levelIdentity;
            this.emitter = emitter;
            this.pos = pos.immutable();
            this.active = true;
        }

        public void clear()
        {
            if (!this.active)
            {
                return;
            }

            this.emitter.emit(this.id, this.pos, -1);
            this.active = false;
            this.levelIdentity = null;
            this.emitter = null;
            this.pos = null;
        }
    }

    @FunctionalInterface
    interface ProgressEmitter
    {
        void emit(int id, BlockPos pos, int progress);
    }
}
