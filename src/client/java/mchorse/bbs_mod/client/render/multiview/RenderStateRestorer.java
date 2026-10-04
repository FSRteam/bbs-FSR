package mchorse.bbs_mod.client.render.multiview;

import java.util.ArrayDeque;
import java.util.Deque;

/** Runs every registered restoration in reverse order, preserving cleanup failures. */
public final class RenderStateRestorer implements AutoCloseable
{
    private final Deque<Runnable> actions = new ArrayDeque<>();
    private boolean closed;

    public void add(Runnable action)
    {
        if (this.closed)
        {
            throw new IllegalStateException("Render state has already been restored");
        }

        this.actions.push(action);
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;
        Throwable failure = null;

        while (!this.actions.isEmpty())
        {
            try
            {
                this.actions.pop().run();
            }
            catch (RuntimeException | Error error)
            {
                if (failure == null)
                {
                    failure = error;
                }
                else if (failure != error)
                {
                    if (priority(error) > priority(failure))
                    {
                        error.addSuppressed(failure);
                        failure = error;
                    }
                    else
                    {
                        failure.addSuppressed(error);
                    }
                }
            }
        }

        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }

        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static int priority(Throwable failure)
    {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath ? 2
            : failure instanceof Error ? 1 : 0;
    }
}
