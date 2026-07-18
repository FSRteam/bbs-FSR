package mchorse.bbs_mod.actions;

import java.util.ArrayList;
import java.util.List;

/** Executable regression for ActionPlayer's retryable teardown boundary. */
public final class ActionTeardownTest
{
    private ActionTeardownTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        linkageFailureDoesNotSkipLaterCleanup();
        runtimeFailureRemainsPrimary();
    }

    private static void linkageFailureDoesNotSkipLaterCleanup()
    {
        List<String> order = new ArrayList<>();

        try
        {
            ActionTeardown.runAll(
                () ->
                {
                    order.add("actors");
                    throw new LinkageError("actor linkage");
                },
                () ->
                {
                    order.add("first-person");
                    throw new IllegalStateException("restore failed");
                },
                () -> order.add("break-progress")
            );

            throw new AssertionError("teardown swallowed its first failure");
        }
        catch (LinkageError e)
        {
            check("actor linkage".equals(e.getMessage()), "teardown replaced its first linkage failure");
            check(e.getSuppressed().length == 1
                    && "restore failed".equals(e.getSuppressed()[0].getMessage()),
                "teardown did not preserve its later runtime failure");
        }

        check(order.equals(List.of("actors", "first-person", "break-progress")),
            "a linkage failure skipped a later teardown step");
    }

    private static void runtimeFailureRemainsPrimary()
    {
        try
        {
            ActionTeardown.runAll(
                () ->
                {
                    throw new IllegalStateException("runtime first");
                },
                () ->
                {
                    throw new LinkageError("linkage second");
                }
            );

            throw new AssertionError("teardown swallowed its runtime failure");
        }
        catch (IllegalStateException e)
        {
            check(e.getSuppressed().length == 1
                    && e.getSuppressed()[0] instanceof LinkageError
                    && "linkage second".equals(e.getSuppressed()[0].getMessage()),
                "teardown lost failure type or suppression order");
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
