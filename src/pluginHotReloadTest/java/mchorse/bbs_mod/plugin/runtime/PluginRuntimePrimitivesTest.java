package mchorse.bbs_mod.plugin.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executable plain-Java coverage for the hot-plugin ownership primitives. */
public final class PluginRuntimePrimitivesTest
{
    private PluginRuntimePrimitivesTest()
    {}

    public static void main(String[] args) throws Exception
    {
        ownerAndLeaseIdentity();
        ledgerClosesInReverseAndAggregatesFailures();
        ledgerRejectsAndClosesLateContributions();
        generationSwapFencesAndDrainsIncumbent();
        guardedCallbackParticipatesInDrain();
        acquireAndSwapNeverExposeAnEmptyRoute();
        managedExecutorsFenceCallbacksAndRestoreTccl();

        System.out.println("PluginRuntimePrimitivesTest: all tests passed");
    }

    private static void ownerAndLeaseIdentity()
    {
        PluginOwner owner = new PluginOwner(" fixture ", 3L);
        AtomicInteger closes = new AtomicInteger();
        PluginLease lease = PluginLease.of(owner, "test", () ->
        {
            closes.incrementAndGet();
            throw new IllegalStateException("expected close failure");
        });

        check("fixture".equals(owner.pluginId()), "owner did not normalize its id");
        check(owner.isNewerThan(new PluginOwner("fixture", 2L)), "owner generation comparison failed");

        try
        {
            lease.close();
            throw new AssertionError("lease swallowed its close failure");
        }
        catch (IllegalStateException exception)
        {
            check("expected close failure".equals(exception.getMessage()), "lease replaced the failure");
        }

        lease.close();
        check(closes.get() == 1, "lease cleanup ran more than once");
    }

    private static void ledgerClosesInReverseAndAggregatesFailures()
    {
        PluginOwner owner = new PluginOwner("ledger", 1L);
        PluginContributionLedger ledger = new PluginContributionLedger(owner);
        List<String> closeOrder = new ArrayList<>();

        ledger.own("first", () -> closeOrder.add("first"));
        ledger.own("second", () ->
        {
            closeOrder.add("second");
            throw new IllegalStateException("second failed");
        });
        ledger.own("third", () ->
        {
            closeOrder.add("third");
            throw new LinkageError("third failed");
        });

        try
        {
            ledger.close();
            throw new AssertionError("ledger swallowed cleanup failures");
        }
        catch (LinkageError error)
        {
            check("third failed".equals(error.getMessage()), "ledger replaced its first failure");
            check(error.getSuppressed().length == 1,
                "ledger did not aggregate the later cleanup failure");
            check("second failed".equals(error.getSuppressed()[0].getMessage()),
                "ledger suppression order changed");
        }

        check(closeOrder.equals(List.of("third", "second", "first")),
            "ledger did not close contributions in reverse registration order");
        check(ledger.state() == PluginContributionLedger.State.CLOSED,
            "failed ledger close did not reach CLOSED");

        try
        {
            ledger.close();
            throw new AssertionError("repeated ledger close lost its deterministic failure");
        }
        catch (LinkageError expected)
        {}

        check(closeOrder.size() == 3, "repeated ledger close reran contribution cleanup");
    }

    private static void ledgerRejectsAndClosesLateContributions()
    {
        PluginOwner owner = new PluginOwner("sealed", 1L);
        PluginContributionLedger ledger = new PluginContributionLedger(owner);
        AtomicInteger closes = new AtomicInteger();

        ledger.seal();

        try
        {
            ledger.own("late", closes::incrementAndGet);
            throw new AssertionError("sealed ledger accepted a late contribution");
        }
        catch (IllegalStateException expected)
        {}

        check(closes.get() == 1, "sealed ledger leaked the rejected contribution");
        ledger.close();
    }

    private static void generationSwapFencesAndDrainsIncumbent() throws Exception
    {
        ActivePluginIndex<String> index = new ActivePluginIndex<>();
        ActivePluginGeneration<String> first = new ActivePluginGeneration<>(
            new PluginOwner("swap", 1L),
            "v1"
        );
        ActivePluginGeneration<String> second = new ActivePluginGeneration<>(
            new PluginOwner("swap", 2L),
            "v2"
        );

        check(index.replace(first) == null, "first publish unexpectedly found an incumbent");
        PluginGenerationLease<String> inFlight = index.acquire("swap");

        check(inFlight != null && "v1".equals(inFlight.contributions()),
            "failed to acquire the active v1 snapshot");
        check(index.replace(first.owner(), second) == first,
            "replacement did not return the exact incumbent");
        check(first.state() == PluginGenerationGate.State.DRAINING,
            "replacement did not fence the incumbent");
        check(inFlight.isFenced() && !inFlight.isCurrent(),
            "old in-flight lease remained current after swap");

        try (PluginGenerationLease<String> active = index.acquire("swap"))
        {
            check(active != null && active.owner().equals(second.owner()),
                "new calls did not acquire the replacement generation");
            check("v2".equals(active.contributions()), "replacement contributions were torn");
        }

        try
        {
            index.replace(new ActivePluginGeneration<>(new PluginOwner("swap", 1L), "stale"));
            throw new AssertionError("index accepted a non-monotonic replacement generation");
        }
        catch (IllegalArgumentException expected)
        {}

        check(!first.awaitDrained(Duration.ZERO), "incumbent drained before its lease was released");
        inFlight.close();
        inFlight.close();
        check(first.awaitDrained(Duration.ofSeconds(1L)), "incumbent did not drain after release");
        first.retire();
        check(inFlight.contributions() == null, "closed generation lease retained its snapshot");

        check(index.remove("swap", first.owner()) == null,
            "stale owner unexpectedly removed the replacement");
    }

    private static void acquireAndSwapNeverExposeAnEmptyRoute() throws Exception
    {
        ActivePluginIndex<Long> index = new ActivePluginIndex<>();
        List<ActivePluginGeneration<Long>> generations = new ArrayList<>();
        ActivePluginGeneration<Long> first = new ActivePluginGeneration<>(
            new PluginOwner("race", 1L),
            1L
        );
        generations.add(first);
        index.replace(first);

        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = new Thread(() ->
        {
            while (!done.get())
            {
                PluginGenerationLease<Long> lease = index.acquire("race");

                if (lease == null)
                {
                    readerFailure.compareAndSet(null, new AssertionError("swap exposed an empty route"));
                    return;
                }

                try (lease)
                {
                    if (lease.owner().generation() != lease.contributions())
                    {
                        readerFailure.compareAndSet(null,
                            new AssertionError("route owner and contribution snapshot were torn"));
                        return;
                    }
                }
            }
        }, "plugin-index-reader");
        reader.start();

        for (long generation = 2L; generation <= 500L; generation += 1L)
        {
            ActivePluginGeneration<Long> candidate = new ActivePluginGeneration<>(
                new PluginOwner("race", generation),
                generation
            );
            generations.add(candidate);
            index.replace(candidate);
        }

        done.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(5L));
        check(!reader.isAlive(), "acquire/swap reader did not stop");

        if (readerFailure.get() != null)
        {
            throw new AssertionError("acquire/swap race failed", readerFailure.get());
        }

        ActivePluginGeneration<Long> active = index.remove("race");

        for (ActivePluginGeneration<Long> generation : generations)
        {
            check(generation.awaitDrained(Duration.ofSeconds(1L)),
                "generation did not drain after acquire/swap race: " + generation.owner());
            generation.retire();
        }

        check(active == generations.get(generations.size() - 1),
            "remove did not return the final active generation");
        index.close();
    }

    private static void guardedCallbackParticipatesInDrain() throws Exception
    {
        PluginOwner owner = new PluginOwner("guard", 1L);
        ActivePluginIndex<String> index = new ActivePluginIndex<>();
        ActivePluginGeneration<String> generation = new ActivePluginGeneration<>(owner, "guarded");
        index.replace(generation);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable guarded = PluginCallbackScope.guarded(
            owner,
            PluginRuntimePrimitivesTest.class.getClassLoader(),
            generation.fence(),
            (failedOwner, throwable) -> failure.set(throwable),
            () ->
            {
                entered.countDown();

                try
                {
                    release.await();
                }
                catch (InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        );
        Thread callback = new Thread(guarded, "guarded-plugin-callback");
        callback.start();

        check(entered.await(1L, TimeUnit.SECONDS), "guarded callback did not start");
        check(generation.gate().activeCalls() == 1,
            "guarded callback did not acquire a counted generation lease");
        index.remove(owner.pluginId(), owner);
        check(!generation.awaitDrained(Duration.ZERO),
            "generation drained while a guarded callback was still running");

        release.countDown();
        callback.join(TimeUnit.SECONDS.toMillis(2L));
        check(!callback.isAlive(), "guarded callback did not finish");
        check(failure.get() == null, "guarded callback unexpectedly failed");
        check(generation.awaitDrained(Duration.ofSeconds(1L)),
            "generation did not drain after guarded callback release");
        generation.retire();
        index.close();
    }

    private static void managedExecutorsFenceCallbacksAndRestoreTccl() throws Exception
    {
        PluginOwner owner = new PluginOwner("managed", 1L);
        ActivePluginIndex<String> index = new ActivePluginIndex<>();
        ActivePluginGeneration<String> generation = new ActivePluginGeneration<>(owner, "callbacks");
        index.replace(generation);

        PluginContributionLedger ledger = new PluginContributionLedger(owner);
        ClassLoader pluginLoader = new ClassLoader(PluginRuntimePrimitivesTest.class.getClassLoader()) {};
        AtomicReference<Throwable> reported = new AtomicReference<>();
        ManagedPluginResources resources = new ManagedPluginResources(
            owner,
            ledger,
            generation.fence(),
            pluginLoader,
            (failedOwner, throwable) ->
            {
                check(owner.equals(failedOwner), "executor reported the wrong owner");
                reported.compareAndSet(null, throwable);
            }
        );
        AtomicReference<ClassLoader> closeTccl = new AtomicReference<>();
        AtomicInteger resourceCloses = new AtomicInteger();
        PluginLease owned = resources.own("tccl-resource", () ->
        {
            closeTccl.set(Thread.currentThread().getContextClassLoader());
            resourceCloses.incrementAndGet();
        });

        owned.close();
        check(closeTccl.get() == pluginLoader, "managed resource cleanup did not receive plugin TCCL");

        ManagedPluginExecutor executor = resources.singleExecutor("test", Duration.ofSeconds(1L));
        AtomicReference<Thread> worker = new AtomicReference<>();
        Future<ClassLoader> tccl = executor.submit(() ->
        {
            worker.set(Thread.currentThread());
            return Thread.currentThread().getContextClassLoader();
        });

        check(tccl.get(1L, TimeUnit.SECONDS) == pluginLoader,
            "managed callback did not receive the generation TCCL");
        check(worker.get().getContextClassLoader() == ManagedPluginExecutor.class.getClassLoader(),
            "managed worker retained the generation TCCL while idle");

        Future<?> failure = executor.submit(() ->
        {
            throw new IllegalArgumentException("callback failure");
        });

        try
        {
            failure.get(1L, TimeUnit.SECONDS);
            throw new AssertionError("submitted callback failure was swallowed");
        }
        catch (ExecutionException exception)
        {
            check(exception.getCause() instanceof IllegalArgumentException,
                "submitted callback failure type changed");
        }

        check(reported.get() instanceof IllegalArgumentException,
            "submitted callback failure did not reach the host error boundary");

        ManagedPluginScheduledExecutor scheduler = resources.scheduler(
            "timer",
            1,
            Duration.ofSeconds(1L)
        );
        AtomicInteger staleCalls = new AtomicInteger();
        ScheduledFuture<?> stale = scheduler.schedule((Runnable) staleCalls::incrementAndGet, 50L, TimeUnit.MILLISECONDS);
        ActivePluginGeneration<String> removed = index.remove("managed", owner);

        stale.get(1L, TimeUnit.SECONDS);
        check(staleCalls.get() == 0, "queued callback crossed the generation fence");
        resources.guarded(staleCalls::incrementAndGet).run();
        check(staleCalls.get() == 0, "guarded callback unexpectedly ran after retirement");

        ledger.close();
        check(resourceCloses.get() == 1, "early managed resource close ran twice at ledger teardown");
        check(executor.isTerminated(), "managed executor survived ledger close");
        check(scheduler.isTerminated(), "managed scheduler survived ledger close");
        check(removed.awaitDrained(Duration.ofSeconds(1L)), "managed generation did not drain");
        removed.retire();
        index.close();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
