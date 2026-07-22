package mchorse.bbs_mod.plugin.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executor whose callbacks carry a generation fence and temporary plugin TCCL.
 * Shutdown is bounded and idempotent; it never leaves a non-daemon plugin
 * worker behind when the ledger retires a generation.
 */
public class ManagedPluginExecutor extends AbstractExecutorService
{
    private final PluginOwner owner;
    private final PluginGenerationFence fence;
    private final ExecutorService delegate;
    private final Duration closeTimeout;
    private final PluginCallbackErrorHandler errorHandler;
    private volatile ClassLoader callbackClassLoader;

    protected ManagedPluginExecutor(
        PluginOwner owner,
        PluginGenerationFence fence,
        ClassLoader callbackClassLoader,
        ExecutorService delegate,
        Duration closeTimeout,
        PluginCallbackErrorHandler errorHandler
    )
    {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.fence = Objects.requireNonNull(fence, "fence");
        this.callbackClassLoader = Objects.requireNonNull(callbackClassLoader, "callbackClassLoader");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.closeTimeout = requireTimeout(closeTimeout);
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");

        if (!owner.equals(fence.owner()))
        {
            throw new IllegalArgumentException("Executor owner does not match generation fence");
        }
    }

    public PluginOwner owner()
    {
        return this.owner;
    }

    public PluginGenerationFence fence()
    {
        return this.fence;
    }

    protected ExecutorService delegate()
    {
        return this.delegate;
    }

    protected Runnable wrapExecute(Runnable command)
    {
        Objects.requireNonNull(command, "command");

        return () ->
        {
            PluginGenerationLease<Void> lease = this.fence.acquire();

            if (lease == null)
            {
                return;
            }

            ClassLoader classLoader = this.callbackClassLoader;

            if (classLoader == null)
            {
                lease.close();
                return;
            }

            try (lease)
            {
                PluginCallbackScope.run(classLoader, command);
            }
            catch (Throwable throwable)
            {
                PluginCallbackScope.report(this.errorHandler, this.owner, throwable);

                if (PluginCallbackScope.isFatal(throwable))
                {
                    throw (Error) throwable;
                }
            }
        };
    }

    protected <T> Callable<T> wrapSubmitted(Callable<T> command)
    {
        Objects.requireNonNull(command, "command");

        return () ->
        {
            PluginGenerationLease<Void> lease = this.fence.acquire();

            if (lease == null)
            {
                throw new CancellationException("Plugin generation is no longer active: " + this.owner);
            }

            ClassLoader classLoader = this.callbackClassLoader;

            if (classLoader == null)
            {
                lease.close();
                throw new CancellationException("Plugin executor is closed: " + this.owner);
            }

            try (lease)
            {
                return PluginCallbackScope.call(classLoader, command);
            }
            catch (Throwable throwable)
            {
                PluginCallbackScope.report(this.errorHandler, this.owner, throwable);

                if (PluginCallbackScope.isFatal(throwable))
                {
                    throw (Error) throwable;
                }

                return ManagedPluginExecutor.propagate(throwable);
            }
        };
    }

    /** Future-returning Runnable wrapper: stale work is a no-op, callback failure is visible. */
    protected Runnable wrapScheduled(Runnable command)
    {
        Objects.requireNonNull(command, "command");

        return () ->
        {
            PluginGenerationLease<Void> lease = this.fence.acquire();

            if (lease == null)
            {
                return;
            }

            ClassLoader classLoader = this.callbackClassLoader;

            if (classLoader == null)
            {
                lease.close();
                return;
            }

            try (lease)
            {
                PluginCallbackScope.run(classLoader, command);
            }
            catch (Throwable throwable)
            {
                PluginCallbackScope.report(this.errorHandler, this.owner, throwable);

                if (throwable instanceof RuntimeException runtimeException)
                {
                    throw runtimeException;
                }

                if (throwable instanceof Error error)
                {
                    throw error;
                }

                throw new IllegalStateException("Plugin callback failed for " + this.owner, throwable);
            }
        };
    }

    @Override
    public void execute(Runnable command)
    {
        this.ensureAccepting();
        this.delegate.execute(this.wrapExecute(command));
    }

    @Override
    public Future<?> submit(Runnable task)
    {
        Objects.requireNonNull(task, "task");
        this.ensureAccepting();
        return this.delegate.submit(this.wrapSubmitted(() ->
        {
            task.run();
            return null;
        }));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result)
    {
        Objects.requireNonNull(task, "task");
        this.ensureAccepting();
        return this.delegate.submit(this.wrapSubmitted(() ->
        {
            task.run();
            return result;
        }));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task)
    {
        this.ensureAccepting();
        return this.delegate.submit(this.wrapSubmitted(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException
    {
        this.ensureAccepting();
        return this.delegate.invokeAll(this.wrapTasks(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks,
        long timeout,
        TimeUnit unit
    ) throws InterruptedException
    {
        this.ensureAccepting();
        return this.delegate.invokeAll(this.wrapTasks(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, java.util.concurrent.ExecutionException
    {
        this.ensureAccepting();
        return this.delegate.invokeAny(this.wrapTasks(tasks));
    }

    @Override
    public <T> T invokeAny(
        Collection<? extends Callable<T>> tasks,
        long timeout,
        TimeUnit unit
    ) throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException
    {
        this.ensureAccepting();
        return this.delegate.invokeAny(this.wrapTasks(tasks), timeout, unit);
    }

    @Override
    public void shutdown()
    {
        this.delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow()
    {
        return this.delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown()
    {
        return this.delegate.isShutdown();
    }

    @Override
    public boolean isTerminated()
    {
        return this.delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
    {
        return this.delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close()
    {
        this.delegate.shutdownNow();

        boolean interrupted = false;
        boolean terminated;

        try
        {
            terminated = this.delegate.awaitTermination(
                this.closeTimeout.toNanos(),
                TimeUnit.NANOSECONDS
            );
        }
        catch (InterruptedException exception)
        {
            interrupted = true;
            terminated = this.delegate.isTerminated();
        }
        finally
        {
            this.callbackClassLoader = null;
        }

        if (interrupted)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted closing plugin executor " + this.owner);
        }

        if (!terminated)
        {
            throw new IllegalStateException("Plugin executor did not terminate for " + this.owner);
        }
    }

    private <T> List<Callable<T>> wrapTasks(Collection<? extends Callable<T>> tasks)
    {
        Objects.requireNonNull(tasks, "tasks");
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());

        for (Callable<T> task : tasks)
        {
            wrapped.add(this.wrapSubmitted(task));
        }

        return wrapped;
    }

    private void ensureAccepting()
    {
        if (this.isShutdown() || this.callbackClassLoader == null || !this.fence.isOpen())
        {
            throw new RejectedExecutionException("Plugin executor is not accepting work for " + this.owner);
        }
    }

    private static Duration requireTimeout(Duration timeout)
    {
        Objects.requireNonNull(timeout, "closeTimeout");

        if (timeout.isNegative())
        {
            throw new IllegalArgumentException("Executor close timeout cannot be negative");
        }

        return timeout;
    }

    private static <T> T propagate(Throwable throwable) throws Exception
    {
        if (throwable instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }

        if (throwable instanceof Error error)
        {
            throw error;
        }

        if (throwable instanceof Exception exception)
        {
            throw exception;
        }

        throw new PluginCallbackException("Plugin callback failed", throwable);
    }
}
