package mchorse.bbs_mod.actions;

/** Aggregates recoverable runtime failures without skipping later cleanup. */
final class ActionTeardown
{
    private ActionTeardown()
    {}

    static void runAll(Runnable... cleanups)
    {
        Throwable failure = null;

        for (Runnable cleanup : cleanups)
        {
            failure = run(failure, cleanup);
        }

        throwIfFailed(failure);
    }

    static Throwable run(Throwable failure, Runnable cleanup)
    {
        try
        {
            cleanup.run();
        }
        catch (RuntimeException | LinkageError e)
        {
            return append(failure, e);
        }

        return failure;
    }

    static Throwable append(Throwable failure, Throwable addition)
    {
        if (failure == null)
        {
            return addition;
        }

        if (failure != addition)
        {
            failure.addSuppressed(addition);
        }

        return failure;
    }

    static void throwIfFailed(Throwable failure)
    {
        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }
        else if (failure instanceof LinkageError linkageError)
        {
            throw linkageError;
        }
    }
}
