package mchorse.bbs_mod.audio;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dependency-light contracts for Minecraft sound capture failure propagation.
 * The source assertions protect the callback boundary without requiring a
 * running client or an OpenAL sound engine.
 */
public final class MinecraftSoundCaptureContractTest
{
    private MinecraftSoundCaptureContractTest()
    {}

    public static void runAll() throws Exception
    {
        assertTypedResultSemantics();
        assertFailureAndCleanupContract();
    }

    private static void assertTypedResultSemantics()
    {
        MinecraftSoundCapture.Result success = new MinecraftSoundCapture.Result(
            MinecraftSoundCapture.Status.SUCCESS, MinecraftSoundCapture.Failure.NONE, null, 2, 3);
        check(success.success() && !success.failed() && success.soundCount() == 2
                && success.frameCount() == 3 && success.hasPartialData(),
            "successful capture result lost its immutable counts");

        RuntimeException cause = new RuntimeException("sound callback");
        MinecraftSoundCapture.Result failure = new MinecraftSoundCapture.Result(
            MinecraftSoundCapture.Status.FAILED, MinecraftSoundCapture.Failure.SOUND_EVENT,
            cause, 1, 2);
        check(failure.failed() && !failure.success()
                && failure.failure() == MinecraftSoundCapture.Failure.SOUND_EVENT
                && failure.cause() == cause && failure.hasPartialData(),
            "capture failure result did not retain typed cause and partial-data marker");
    }

    private static void assertFailureAndCleanupContract() throws Exception
    {
        String source = Files.readString(sourcePath(
            "src/client/java/mchorse/bbs_mod/audio/MinecraftSoundCapture.java"));
        String captureFrame = sourceSection(source, "public void captureFrame()", "private static boolean hasEnded");
        String onPlaySound = sourceSection(source, "public void onPlaySound(", "private void recordFailure");
        String end = sourceSection(source, "public void end()", "/** Call once immediately before each encoded frame. */");

        check(source.contains("public Result getResult()"),
            "Minecraft capture does not expose a typed result snapshot");
        check(!source.contains("catch (RuntimeException ignored)"),
            "Minecraft capture still swallows callback runtime failures");
        check(captureFrame.contains("Failure.LOOP_TRACKING")
                && captureFrame.contains("Failure.FRAME_CAPTURE"),
            "captureFrame does not classify loop and listener failures");
        check(onPlaySound.contains("Failure.SOUND_EVENT"),
            "onPlaySound does not publish a typed event-capture failure");
        check(end.contains("finally") && end.contains("this.loops.clear()")
                && end.contains("this.finishResult()")
                && end.indexOf("removeListener") < end.indexOf("this.loops.clear()"),
            "end() can skip loop cleanup when listener removal fails");
        check(source.contains("appendSuppressed(current.cause(), cause)"),
            "capture failures do not retain the first cause with later failures suppressed");
    }

    private static Path sourcePath(String relative)
    {
        Path direct = Path.of(relative);
        if (Files.isRegularFile(direct)) return direct;
        Path nested = Path.of("new").resolve(relative);
        if (Files.isRegularFile(nested)) return nested;
        throw new AssertionError("Could not locate source file " + relative);
    }

    private static String sourceSection(String source, String start, String end)
    {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from)
        {
            throw new AssertionError("Could not locate source section " + start);
        }
        return source.substring(from, to);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
