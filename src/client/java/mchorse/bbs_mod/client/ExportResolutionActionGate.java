package mchorse.bbs_mod.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Dependency-light generation gate for work that must run after the next
 * export-sized frame. It owns both the render-thread pending slot and a wrapper
 * already handed to the client executor, so lifecycle cancellation fences both.
 */
final class ExportResolutionActionGate
{
    enum FailureStage
    {
        OWNER_VALIDATION,
        ACTION,
        CLEANUP
    }

    @FunctionalInterface
    interface FailureHandler
    {
        void handle(FailureStage stage, Throwable failure);
    }

    private final FailureHandler failureHandler;
    private long generation;
    private Action pending;
    private Action queued;

    ExportResolutionActionGate(FailureHandler failureHandler)
    {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    synchronized void schedule(BooleanSupplier ownerValid, Runnable action, Runnable cancelled)
    {
        this.cancel(this.pending);
        this.pending = null;
        this.cancel(this.queued);
        this.queued = null;

        this.pending = new Action(
            this,
            ++this.generation,
            Objects.requireNonNull(ownerValid, "ownerValid"),
            Objects.requireNonNull(action, "action"),
            Objects.requireNonNull(cancelled, "cancelled")
        );
    }

    synchronized void cancelAll()
    {
        this.generation += 1L;
        this.cancel(this.pending);
        this.pending = null;
        this.cancel(this.queued);
        this.queued = null;
    }

    synchronized Action queuePending()
    {
        Action action = this.pending;

        this.pending = null;

        if (action != null)
        {
            this.cancel(this.queued);
            this.queued = action;
        }

        return action;
    }

    synchronized void cancelQueued(Action action)
    {
        if (this.queued == action)
        {
            this.queued = null;
        }

        this.cancel(action);
    }

    private synchronized boolean isCurrent(Action action)
    {
        return action.generation == this.generation;
    }

    private synchronized boolean claim(Action action, boolean ownerValid)
    {
        if (this.queued == action)
        {
            this.queued = null;
        }

        return ownerValid
            && action.generation == this.generation
            && action.terminal.compareAndSet(false, true);
    }

    private void cancel(Action action)
    {
        if (action != null)
        {
            action.cancel();
        }
    }

    private void report(FailureStage stage, Throwable failure)
    {
        try
        {
            this.failureHandler.handle(stage, failure);
        }
        catch (RuntimeException | LinkageError ignored)
        {}
    }

    static final class Action
    {
        private final ExportResolutionActionGate gate;
        private final long generation;
        private final BooleanSupplier ownerValid;
        private final Runnable action;
        private final Runnable cancelled;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean cleanupRun = new AtomicBoolean();

        private Action(
            ExportResolutionActionGate gate,
            long generation,
            BooleanSupplier ownerValid,
            Runnable action,
            Runnable cancelled
        )
        {
            this.gate = gate;
            this.generation = generation;
            this.ownerValid = ownerValid;
            this.action = action;
            this.cancelled = cancelled;
        }

        void runIfCurrent()
        {
            boolean valid = this.gate.isCurrent(this);

            if (valid)
            {
                try
                {
                    valid = this.ownerValid.getAsBoolean();
                }
                catch (RuntimeException | Error exception)
                {
                    this.gate.report(FailureStage.OWNER_VALIDATION, exception);
                    valid = false;
                }
            }

            if (!this.gate.claim(this, valid))
            {
                this.cancel();

                return;
            }

            try
            {
                this.action.run();
            }
            catch (RuntimeException | Error exception)
            {
                this.gate.report(FailureStage.ACTION, exception);
                this.runCancelledCleanup();
            }
        }

        private void cancel()
        {
            if (this.terminal.compareAndSet(false, true))
            {
                this.runCancelledCleanup();
            }
        }

        private void runCancelledCleanup()
        {
            if (!this.cleanupRun.compareAndSet(false, true))
            {
                return;
            }

            try
            {
                this.cancelled.run();
            }
            catch (RuntimeException | Error exception)
            {
                this.gate.report(FailureStage.CLEANUP, exception);
            }
        }
    }
}
