package mchorse.bbs_mod.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ActionRetirementQueueTest
{
    private ActionRetirementQueueTest()
    {}

    public static void runAll()
    {
        cleanupContinuesAndRetainsExactFailures();
        arbitraryErrorsAreNotSwallowed();
        actorStagingWiringIsTransactional();
    }

    public static void main(String[] args)
    {
        runAll();
    }

    private static void cleanupContinuesAndRetainsExactFailures()
    {
        ActionRetirementQueue<EqualValue> queue = new ActionRetirementQueue<>();
        EqualValue runtimeFailure = new EqualValue("runtime");
        EqualValue successful = new EqualValue("success");
        EqualValue linkageFailure = new EqualValue("linkage");
        RuntimeException runtime = new IllegalStateException("runtime failure");
        LinkageError linkage = new NoClassDefFoundError("linkage failure");
        int[] attempts = new int[3];

        queue.retain(runtimeFailure);
        queue.retain(runtimeFailure);
        queue.retain(successful);
        queue.retain(linkageFailure);

        check(queue.size() == 3, "retirement ownership used equals() or retained one identity twice");

        Throwable failure = queue.drain((value) ->
        {
            if (value == runtimeFailure)
            {
                attempts[0] += 1;
                throw runtime;
            }

            if (value == successful)
            {
                attempts[1] += 1;
                return;
            }

            attempts[2] += 1;
            throw linkage;
        });

        check(failure == runtime, "first retirement failure was not preserved");
        check(failure.getSuppressed().length == 1 && failure.getSuppressed()[0] == linkage,
            "later LinkageError was not aggregated as a suppressed failure");
        check(attempts[0] == 1 && attempts[1] == 1 && attempts[2] == 1,
            "one actor failure skipped cleanup of a later actor");
        check(queue.size() == 2, "successful retirement was not removed while failed identities were retained");

        Throwable retryFailure = queue.drain((value) -> {});

        check(retryFailure == null && queue.isEmpty(), "failed actor identities were not retryable");
    }

    private static void arbitraryErrorsAreNotSwallowed()
    {
        ActionRetirementQueue<Object> queue = new ActionRetirementQueue<>();
        Object actor = new Object();
        AssertionError expected = new AssertionError("do not swallow arbitrary Error");

        queue.retain(actor);

        try
        {
            queue.drain((value) ->
            {
                throw expected;
            });
            throw new AssertionError("arbitrary Error was swallowed by retirement cleanup");
        }
        catch (AssertionError error)
        {
            check(error == expected, "retirement cleanup replaced an arbitrary Error");
        }

        check(queue.size() == 1, "an unsuccessfully retired actor lost its owner after arbitrary Error");
        queue.drain((value) -> {});
        check(queue.isEmpty(), "actor retained after arbitrary Error could not be retried");
    }

    private static void actorStagingWiringIsTransactional()
    {
        String player = read("src/main/java/mchorse/bbs_mod/actions/ActionPlayer.java");
        String manager = read("src/main/java/mchorse/bbs_mod/actions/ActionManager.java");
        String staging = section(player, "private boolean tryUpdateReplayEntities()", "public void resendActors()");
        String stop = section(player, "private void discardCurrentActors()", "private boolean canApplyFirstPersonState()");

        check(staging.contains("catch (RuntimeException | LinkageError e)"),
            "actor staging still lets LinkageError bypass rollback");
        check(staging.contains("this.rollbackStagedActors(stagedActors)"),
            "a rejected actor stage no longer retires every staged actor");
        check(staging.contains("this.retireDetachedActors(stagedActors)"),
            "an exceptional actor stage no longer retains failed cleanup identities");
        assertOrdered(staging,
            "Map<String, LivingEntity> previousActors = this.actors",
            "this.actors = nextActors",
            "this.retireDetachedActors(previousActors.values())",
            "this.resendActors()");
        check(!staging.contains("this.actors.clear()") && !staging.contains("this.actors.putAll(nextActors)"),
            "live actor map commit can still expose a partially replaced map");
        check(stop.contains("this.actorRetirements.drain(LivingEntity::discard)"),
            "runtime teardown no longer retries detached actor retirement");
        assertOrdered(manager,
            "player = new ActionPlayer(",
            "player.stopDamage = false",
            "player.initializeReplayEntities()",
            "player.initializeFirstPersonState()",
            "this.players.add(player)");
        check(manager.contains("firstPersonLease,\n                true"),
            "ActionManager no longer owns a deferred actor stage before constructor rollback is reachable");
    }

    private static String read(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (IOException e)
        {
            throw new AssertionError("Could not read " + path, e);
        }
    }

    private static String section(String source, String start, String end)
    {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());

        check(from >= 0 && to > from, "Could not locate source section " + start);

        return source.substring(from, to);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int cursor = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, cursor + 1);

            check(next > cursor, "Missing or out-of-order source marker: " + marker);
            cursor = next;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private record EqualValue(String id)
    {
        @Override
        public boolean equals(Object object)
        {
            return object instanceof EqualValue;
        }

        @Override
        public int hashCode()
        {
            return 1;
        }
    }
}
