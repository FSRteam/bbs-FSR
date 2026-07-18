package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.mixin.ServerPlayNetworkHandlerMixin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable regression for the final player-command recording boundary. */
public final class CommandRecordingResultTest
{
    private CommandRecordingResultTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
    }

    public static void runAll() throws Exception
    {
        AtomicInteger recordings = new AtomicInteger();
        CommandRecordingResult result = new CommandRecordingResult();

        check(!result.tryRecord(false, recordings::incrementAndGet),
            "a failed command result was accepted for recording");
        check(result.tryRecord(true, recordings::incrementAndGet),
            "the first successful command result was not recorded");
        check(!result.tryRecord(true, recordings::incrementAndGet) && recordings.get() == 1,
            "forked command successes recorded the top-level command more than once");

        CommandRecordingResult runtimeFailure = new CommandRecordingResult();

        check(runtimeFailure.tryRecord(true, () ->
        {
            throw new IllegalStateException("recording failed");
        }), "a recording callback failure escaped its command-result boundary");
        check(!runtimeFailure.tryRecord(true, recordings::incrementAndGet),
            "a failed recording callback was retried by a later fork result");

        CommandRecordingResult linkageFailure = new CommandRecordingResult();

        check(linkageFailure.tryRecord(true, () ->
        {
            throw new LinkageError("recording linkage failed");
        }), "a recording linkage failure escaped its command-result boundary");

        String handler = classShape(ServerPlayNetworkHandlerMixin.class);

        check(handler.contains("CommandRecordingResult")
                && handler.contains("withCallback")
                && handler.contains("CommandResultCallback")
                && handler.contains("chain"),
            "command recording no longer uses the chained Brigadier result callback");
        check(handler.contains("RETURN") && !handler.contains("HEAD") && !handler.contains("onParseCommand"),
            "command recording moved back to the pre-validation parse entry");
    }

    private static String classShape(Class<?> type) throws Exception
    {
        String resource = type.getName().replace('.', '/') + ".class";

        try (InputStream stream = type.getClassLoader().getResourceAsStream(resource))
        {
            check(stream != null, type.getSimpleName() + " bytecode was not available to the regression");

            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
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
