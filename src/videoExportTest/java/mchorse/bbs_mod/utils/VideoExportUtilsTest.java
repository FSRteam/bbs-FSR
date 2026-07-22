package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.AudioRendererMixerFocusedTest;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.client.ExportResolutionActionGateTest;
import mchorse.bbs_mod.film.VideoExportAudioNormalizerTest;
import mchorse.bbs_mod.film.VideoExportLifecycleContractTest;
import mchorse.bbs_mod.film.VideoExportSession;
import mchorse.bbs_mod.film.VideoExportSessionTest;
import mchorse.bbs_mod.film.AudioExportLifecycleTest;
import mchorse.bbs_mod.film.VideoExportArtifactIdentityTest;
import mchorse.bbs_mod.importers.types.AudioImporterSupportTest;
import mchorse.bbs_mod.settings.values.core.ValueString;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Executable regression checks for argument, path, process, and owned-resource lifecycles. */
public class VideoExportUtilsTest
{
    public static void main(String[] args) throws Exception
    {
        assertFilmName("My Film");
        assertFilmName("My Film (1)");
        assertQuotedArguments();
        assertBackslashesPreserved();
        assertEscapedQuotes();
        assertExplicitEmptyArgument();
        assertUnclosedQuoteRejected();
        assertReplacementDoesNotCascade();
        assertFrameBufferSizeValidation();
        assertReservedLayoutAdaptersReject();
        assertInstallRootResolution();
        assertTemporaryAudioCleanup();
        assertFailedTemporaryCleanupReported();
        assertEncoderStartupFailure();
        assertProcessOwnedBeforeChannelAdaptation();
        assertEncoderEarlyExit();
        assertEncoderPipeFailure();
        assertEncoderNonzeroExit();
        assertEncoderSuccess();
        assertCompletionTimeoutCannotSucceed();
        assertEncoderCancellationIsIdempotent();
        assertCancelledTerminationTimeoutDiagnosed();
        assertHangingHealthProbeTerminated();
        assertInterruptedProbeRestoresInterrupt();
        assertProcessWaitUsesBoundedSemanticDiagnostics();
        VideoMuxerTeardownContractTest.runAll();
        VideoExportAudioNormalizerTest.runAll();
        AudioRendererMixerFocusedTest.runAll();
        AudioImporterSupportTest.runAll();
        ExportResolutionActionGateTest.runAll();
        VideoExportArtifactIdentityTest.runAll();
        try (ExpectedErrorLogCapture capture = ExpectedErrorLogCapture.install())
        {
            AudioExportLifecycleTest.runAll();
            VideoExportLifecycleContractTest.runAll();
            VideoExportSessionTest.runAll();
            capture.assertExpectedErrors();
        }

        System.out.println("VideoExportUtilsTest passed; captured 16 expected failure diagnostics");
    }

    /** Keeps deliberate failure-path diagnostics executable without leaking ERROR lines into a passing build. */
    private static final class ExpectedErrorLogCapture extends AbstractAppender implements AutoCloseable
    {
        private static final int EXPECTED_ERROR_COUNT = 16;
        private final Logger logger;
        private final Level previousLevel;
        private final boolean previousAdditive;
        private final Map<String, Appender> previousAppenders;
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private ExpectedErrorLogCapture(Logger logger)
        {
            super("video-export-expected-errors", null, PatternLayout.createDefaultLayout(),
                false, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.previousLevel = logger.getLevel();
            this.previousAdditive = logger.isAdditive();
            this.previousAppenders = Map.copyOf(logger.getAppenders());
            this.start();

            for (Appender appender : this.previousAppenders.values())
            {
                logger.removeAppender(appender);
            }

            logger.setAdditive(false);
            logger.addAppender(this);
            logger.setLevel(Level.ALL);
        }

        private static ExpectedErrorLogCapture install()
        {
            return new ExpectedErrorLogCapture((Logger) LogManager.getLogger(VideoExportSession.class));
        }

        @Override
        public void append(LogEvent event)
        {
            this.events.add(event.toImmutable());
        }

        private void assertExpectedErrors()
        {
            List<LogEvent> errors = this.events.stream()
                .filter((event) -> event.getLevel().isMoreSpecificThan(Level.ERROR))
                .toList();

            if (errors.size() != EXPECTED_ERROR_COUNT)
            {
                throw new AssertionError("Expected " + EXPECTED_ERROR_COUNT
                    + " captured video-export errors, got " + errors.size() + ": "
                    + describe(errors));
            }

            List<LogEvent> unexpected = errors.stream()
                .filter((event) -> !isExpectedError(event))
                .toList();

            if (!unexpected.isEmpty())
            {
                throw new AssertionError("Unexpected video-export error diagnostics: "
                    + describe(unexpected));
            }
        }

        private static boolean isExpectedError(LogEvent event)
        {
            String message = event.getMessage().getFormattedMessage();
            Throwable error = event.getThrown();
            String cause = error == null || error.getMessage() == null ? "" : error.getMessage();
            boolean expectedMessage = message.startsWith("Video export ")
                || message.equals("Video export failed")
                || message.equals("Failed to prepare video export")
                || message.equals("Failed to tear down video export session")
                || message.equals("Failed to reset video export session")
                || message.equals("Video export completion listener failed")
                || message.equals("Persistent video export completion listener failed");
            boolean expectedCause = cause.startsWith("fixture ")
                || cause.startsWith("fake ")
                || cause.startsWith("Failed to delete temporary export audio ")
                || cause.equals("persistent observer failure");

            return expectedMessage && expectedCause;
        }

        private static String describe(List<LogEvent> events)
        {
            return events.stream()
                .map((event) -> event.getMessage().getFormattedMessage() + " -> "
                    + (event.getThrown() == null ? "no cause" : event.getThrown().getMessage()))
                .toList().toString();
        }

        @Override
        public void close()
        {
            this.logger.removeAppender(this);

            for (Appender appender : this.previousAppenders.values())
            {
                this.logger.addAppender(appender);
            }

            this.logger.setAdditive(this.previousAdditive);
            this.logger.setLevel(this.previousLevel);
            this.stop();
        }
    }

    private static void assertFilmName(String name)
    {
        assertEquals(
            List.of(name + ".mp4"),
            VideoExportUtils.resolveArguments("%NAME%.mp4", Map.of("%NAME%", name))
        );
    }

    private static void assertQuotedArguments()
    {
        assertEquals(
            List.of("-metadata", "title=My Film", "-i", "C:\\Audio Files\\track.wav"),
            VideoExportUtils.resolveArguments(
                "-metadata \"title=My Film\" -i \"%AUDIO_TRACK%\"",
                Map.of("%AUDIO_TRACK%", "C:\\Audio Files\\track.wav")
            )
        );
    }

    private static void assertBackslashesPreserved()
    {
        assertEquals(
            List.of("-i", "C:\\Audio Files\\track.wav", "\\\\server\\share\\track.wav"),
            VideoExportUtils.resolveArguments(
                "-i \"C:\\Audio Files\\track.wav\" \"\\\\server\\share\\track.wav\"",
                Map.of()
            )
        );
    }

    private static void assertEscapedQuotes()
    {
        assertEquals(
            List.of("-metadata", "title=Say \"Hi\"", "-metadata", "comment=It's fine"),
            VideoExportUtils.resolveArguments(
                "-metadata \"title=Say \\\"Hi\\\"\" -metadata 'comment=It\\'s fine'",
                Map.of()
            )
        );
    }

    private static void assertExplicitEmptyArgument()
    {
        assertEquals(
            List.of("-metadata", "", "tail"),
            VideoExportUtils.resolveArguments("-metadata \"\" tail", Map.of())
        );
    }

    private static void assertUnclosedQuoteRejected()
    {
        try
        {
            VideoExportUtils.resolveArguments("-metadata \"unfinished", Map.of());
        }
        catch (IllegalArgumentException e)
        {
            return;
        }

        throw new AssertionError("Unclosed ffmpeg argument quote was accepted");
    }

    private static void assertReplacementDoesNotCascade()
    {
        assertEquals(
            List.of("%FILTERS%.mp4"),
            VideoExportUtils.resolveArguments(
                "%NAME%.mp4",
                Map.of("%NAME%", "%FILTERS%", "%FILTERS%", "unexpected")
            )
        );
    }

    private static void assertFrameBufferSizeValidation()
    {
        if (VideoExportUtils.frameBufferSize(1920, 1080) != 1920 * 1080 * 3)
        {
            throw new AssertionError("Packed BGR frame size was calculated incorrectly");
        }

        assertInvalidFrameSize(0, 1080);
        assertInvalidFrameSize(1920, -1);
        assertInvalidFrameSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static void assertInvalidFrameSize(int width, int height)
    {
        try
        {
            VideoExportUtils.frameBufferSize(width, height);
        }
        catch (IllegalArgumentException e)
        {
            return;
        }

        throw new AssertionError("Invalid video dimensions were accepted: " + width + "x" + height);
    }

    private static void assertReservedLayoutAdaptersReject() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-layout-adapter-test-");
        Path video = Files.write(root.resolve("video.mp4"), new byte[] {1});
        Path audio = Files.write(root.resolve("audio.wav"), new byte[] {1});
        ValueString previousLayout = BBSSettings.videoAudioLayout;

        try
        {
            for (ChannelLayout layout : List.of(ChannelLayout.MONO, ChannelLayout.STEREO))
            {
                Path output = root.resolve(layout.id() + ".mp4");
                VideoMuxer.MuxResult accepted = VideoMuxer.mux(video.toFile(), audio.toFile(),
                    output, null, layout, VideoMuxer.DEFAULT_ARGUMENTS, () -> true, false);

                assertEquals(VideoMuxer.Status.CANCELLED, accepted.status());
                assertMissing(output, "accepted layout published a cancelled mux");
            }

            Path typedOutput = root.resolve("typed-5.1.mp4");
            VideoMuxer.MuxResult typed = VideoMuxer.mux(video.toFile(), audio.toFile(),
                typedOutput, null, ChannelLayout.SURROUND_5_1,
                VideoMuxer.DEFAULT_ARGUMENTS, () -> false, false);

            assertEquals(VideoMuxer.Status.PREPARATION_FAILED, typed.status());
            assertUnsupportedLayout(typed.cause(), "typed mux");
            assertMissing(typedOutput, "typed 5.1 mux published output");

            File legacyExplicit = VideoMuxer.mux(video.toFile(), audio.toFile(),
                "legacy-explicit-5.1", VideoMuxer.DEFAULT_ARGUMENTS, ChannelLayout.SURROUND_5_1);

            if (legacyExplicit != null)
            {
                throw new AssertionError("legacy explicit 5.1 mux reported success");
            }

            ValueString reserved = new ValueString("test_audio_channel_layout", ChannelLayout.MONO.id());
            reserved.set(ChannelLayout.SURROUND_5_1.id());
            BBSSettings.videoAudioLayout = reserved;

            File legacySettings = VideoMuxer.mux(video.toFile(), audio.toFile(),
                "legacy-settings-5.1", VideoMuxer.DEFAULT_ARGUMENTS);

            if (legacySettings != null)
            {
                throw new AssertionError("legacy settings 5.1 mux reported success");
            }

            VideoRecorder settingsRecorder = new VideoRecorder();
            if (settingsRecorder.tryStartRecording("settings-5.1", audio.toFile(), 0, 1, 1))
            {
                throw new AssertionError("legacy settings 5.1 recorder started");
            }

            assertRecorderRejected(settingsRecorder, "legacy settings recorder");

            VideoRecorder explicitRecorder = new VideoRecorder();
            Path recorderOutput = root.resolve("recorder-5.1.mp4");
            if (explicitRecorder.tryStartRecording("explicit-5.1", audio.toFile(),
                recorderOutput.toFile(), null, ChannelLayout.SURROUND_5_1, 0, 1, 1))
            {
                throw new AssertionError("explicit 5.1 recorder started");
            }

            assertRecorderRejected(explicitRecorder, "explicit recorder");
            assertMissing(recorderOutput, "explicit 5.1 recorder created output");
        }
        finally
        {
            BBSSettings.videoAudioLayout = previousLayout;
            deleteTree(root);
        }
    }

    private static void assertRecorderRejected(VideoRecorder recorder, String adapter)
    {
        assertEquals(VideoExportProcess.Outcome.FAILED, recorder.getOutcome());
        assertUnsupportedLayout(recorder.getFailure(), adapter);

        if (recorder.didStartOutputProducer())
        {
            throw new AssertionError(adapter + " started an output producer");
        }
    }

    private static void assertUnsupportedLayout(Throwable cause, String adapter)
    {
        if (!(cause instanceof IllegalArgumentException)
            || cause.getMessage() == null || !cause.getMessage().contains("SURROUND_5_1"))
        {
            throw new AssertionError(adapter + " lost its typed unsupported-layout failure", cause);
        }
    }

    private static void assertMissing(Path path, String message)
    {
        if (Files.exists(path))
        {
            throw new AssertionError(message + ": " + path);
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;

        try (java.util.stream.Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void assertInstallRootResolution() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-ffmpeg-root-");
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path windowsEncoder = Files.createFile(bin.resolve("ffmpeg.exe"));

        assertEquals(windowsEncoder.toFile().getCanonicalFile(), FFMpegUtils.findFFMPEG(root.toString(), true).getCanonicalFile());
        Files.delete(windowsEncoder);

        Path unixEncoder = Files.createFile(bin.resolve("ffmpeg"));

        assertEquals(unixEncoder.toFile().getCanonicalFile(), FFMpegUtils.findFFMPEG(root.toString(), false).getCanonicalFile());

        Files.delete(unixEncoder);
        Files.delete(bin);
        Files.delete(root);
    }

    private static void assertTemporaryAudioCleanup() throws Exception
    {
        Path folder = Files.createTempDirectory("bbs-export-test-");
        File userFile = folder.resolve("user.wav").toFile();
        Files.writeString(userFile.toPath(), "keep");

        File temporary = VideoExportUtils.createTemporaryAudioFile(folder.toFile());

        if (!VideoExportUtils.tryDeleteTemporaryFile(temporary))
        {
            throw new AssertionError("Owned temporary audio was not deleted");
        }

        if (temporary.exists() || !userFile.exists())
        {
            throw new AssertionError("Temporary cleanup affected the wrong file");
        }

        Files.delete(userFile.toPath());
        Files.delete(folder);
    }

    private static void assertFailedTemporaryCleanupReported() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-export-delete-failure-");
        Path child = Files.writeString(directory.resolve("child.tmp"), "keep directory non-empty");

        if (VideoExportUtils.tryDeleteTemporaryFile(directory.toFile()))
        {
            throw new AssertionError("Failed temporary cleanup was reported as successful");
        }

        Files.delete(child);
        Files.delete(directory);
    }

    private static void assertEncoderStartupFailure()
    {
        FakeProcess process = new FakeProcess(false, 1);
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        if (lifecycle.start(process, channel))
        {
            throw new AssertionError("An already-exited FFmpeg process was accepted");
        }

        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.getOutcome());
        assertEquals(1, channel.closeCount);
    }

    private static void assertProcessOwnedBeforeChannelAdaptation()
    {
        FakeProcess process = new FakeProcess(true, 0);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process);
        lifecycle.fail(new IOException("fake stdin adaptation failure"));

        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.getOutcome());
        assertEquals(1, process.output.closeCount);
        assertEquals(1, process.input.closeCount);
        assertEquals(1, process.error.closeCount);

        if (process.destroyCount != 1)
        {
            throw new AssertionError("Pre-channel startup failure did not terminate FFmpeg exactly once");
        }
    }

    private static void assertEncoderEarlyExit()
    {
        FakeProcess process = new FakeProcess(true, 0);
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);
        process.exit();

        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.complete());
        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.poll());
        assertEquals(1, channel.closeCount);
    }

    private static void assertEncoderPipeFailure()
    {
        FakeProcess process = new FakeProcess(true, 0);
        CountingChannel channel = new CountingChannel(true);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);

        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.write(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.cancel());
        assertEquals(1, channel.closeCount);

        if (process.destroyCount != 1)
        {
            throw new AssertionError("Pipe failure did not terminate FFmpeg exactly once");
        }
    }

    private static void assertEncoderNonzeroExit()
    {
        FakeProcess process = new FakeProcess(true, 7);
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);

        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.complete());

        if (lifecycle.getFailure() == null || !lifecycle.getFailure().getMessage().contains("code 7"))
        {
            throw new AssertionError("Nonzero FFmpeg exit code was not surfaced");
        }
    }

    private static void assertEncoderSuccess()
    {
        FakeProcess process = new FakeProcess(true, 0);
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);

        assertEquals(VideoExportProcess.Outcome.RUNNING, lifecycle.write(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        assertEquals(VideoExportProcess.Outcome.SUCCEEDED, lifecycle.complete());
        assertEquals(VideoExportProcess.Outcome.SUCCEEDED, lifecycle.complete());
        assertEquals(1, channel.closeCount);
    }

    private static void assertEncoderCancellationIsIdempotent()
    {
        FakeProcess process = new FakeProcess(true, 0);
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);

        assertEquals(VideoExportProcess.Outcome.CANCELLED, lifecycle.cancel());
        assertEquals(VideoExportProcess.Outcome.CANCELLED, lifecycle.cancel());
        assertEquals(VideoExportProcess.Outcome.CANCELLED, lifecycle.complete());
        assertEquals(1, channel.closeCount);

        if (process.destroyCount != 1)
        {
            throw new AssertionError("Repeated disconnect/cancel cleanup terminated FFmpeg more than once");
        }
    }

    private static void assertCompletionTimeoutCannotSucceed()
    {
        CompletionTimeoutProcess process = new CompletionTimeoutProcess();
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);

        assertEquals(VideoExportProcess.Outcome.FAILED, lifecycle.complete());

        if (lifecycle.getFailure() == null || !lifecycle.getFailure().getMessage().contains("Timed out"))
        {
            throw new AssertionError("Forced FFmpeg completion was reported as a natural success");
        }

        if (process.destroyCount != 1)
        {
            throw new AssertionError("Timed-out FFmpeg completion was not terminated exactly once");
        }
    }

    private static void assertHangingHealthProbeTerminated()
    {
        HangingProcess process = new HangingProcess();

        if (FFMpegUtils.waitForProcess(process, 1L))
        {
            throw new AssertionError("Hanging FFmpeg health probe was reported as healthy");
        }

        if (process.isAlive() || process.destroyCount != 1)
        {
            throw new AssertionError("Hanging FFmpeg health probe was not terminated exactly once");
        }
    }

    private static void assertCancelledTerminationTimeoutDiagnosed()
    {
        StubbornProcess process = new StubbornProcess();
        CountingChannel channel = new CountingChannel(false);
        VideoExportProcess lifecycle = new VideoExportProcess(0L);

        lifecycle.start(process, channel);

        assertEquals(VideoExportProcess.Outcome.CANCELLED, lifecycle.cancel());

        if (lifecycle.getFailure() == null || !lifecycle.getFailure().getMessage().contains("Timed out"))
        {
            throw new AssertionError("Cancellation cleanup timeout was not surfaced");
        }
    }

    private static void assertInterruptedProbeRestoresInterrupt()
    {
        InterruptingProcess process = new InterruptingProcess();

        if (FFMpegUtils.waitForProcess(process, 1L))
        {
            throw new AssertionError("Interrupted FFmpeg probe was reported as healthy");
        }

        if (!Thread.currentThread().isInterrupted())
        {
            throw new AssertionError("FFmpeg probe swallowed the interrupt flag");
        }

        Thread.interrupted();
    }

    private static void assertProcessWaitUsesBoundedSemanticDiagnostics() throws IOException
    {
        Path source = findProjectRoot().resolve("src/main/java/mchorse/bbs_mod/utils/FFMpegUtils.java");
        String utils = Files.readString(source);
        int start = utils.indexOf("static boolean waitForProcess(Process process, long timeoutMs)");
        int end = start < 0 ? -1 : utils.indexOf("private static void closeProcessStreams", start);

        if (start < 0 || end <= start)
        {
            throw new AssertionError("Could not locate FFMpegUtils.waitForProcess source boundaries");
        }

        String waitForProcess = utils.substring(start, end);

        if (utils.contains("printStackTrace")
            || !utils.contains("topic=ffmpeg.process phase=launch")
            || !waitForProcess.contains("topic=ffmpeg.process")
            || !waitForProcess.contains("phase=wait")
            || !utils.contains("topic=ffmpeg.discovery phase=executable_scan")
            || !utils.contains("error_class={}"))
        {
            throw new AssertionError("FFmpeg failure diagnostics are no longer bounded and phase-specific");
        }
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve("src/main/java/mchorse/bbs_mod/utils/FFMpegUtils.java")))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve("src/main/java/mchorse/bbs_mod/utils/FFMpegUtils.java")))
            {
                return nested;
            }

            current = current.getParent();
        }

        throw new AssertionError("Could not locate new project root");
    }

    private static void assertEquals(List<String> expected, List<String> actual)
    {
        if (!actual.equals(expected))
        {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static class CountingChannel implements WritableByteChannel
    {
        private final boolean failWrites;
        private boolean open = true;
        private int closeCount;

        private CountingChannel(boolean failWrites)
        {
            this.failWrites = failWrites;
        }

        @Override
        public int write(ByteBuffer source) throws IOException
        {
            if (this.failWrites)
            {
                throw new IOException("fake pipe failure");
            }

            int remaining = source.remaining();

            source.position(source.limit());

            return remaining;
        }

        @Override
        public boolean isOpen()
        {
            return this.open;
        }

        @Override
        public void close()
        {
            if (this.open)
            {
                this.open = false;
                this.closeCount += 1;
            }
        }
    }

    private static class FakeProcess extends Process
    {
        protected final int exitCode;
        protected final CloseTrackingOutputStream output = new CloseTrackingOutputStream();
        protected final CloseTrackingInputStream input = new CloseTrackingInputStream();
        protected final CloseTrackingInputStream error = new CloseTrackingInputStream();
        protected boolean alive;
        protected int destroyCount;

        private FakeProcess(boolean alive, int exitCode)
        {
            this.alive = alive;
            this.exitCode = exitCode;
        }

        private void exit()
        {
            this.alive = false;
        }

        @Override
        public OutputStream getOutputStream()
        {
            return this.output;
        }

        @Override
        public InputStream getInputStream()
        {
            return this.input;
        }

        @Override
        public InputStream getErrorStream()
        {
            return this.error;
        }

        @Override
        public int waitFor()
        {
            this.alive = false;

            return this.exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
        {
            this.alive = false;

            return true;
        }

        @Override
        public int exitValue()
        {
            if (this.alive)
            {
                throw new IllegalThreadStateException("Fake process is still alive");
            }

            return this.exitCode;
        }

        @Override
        public void destroy()
        {
            if (this.alive)
            {
                this.destroyCount += 1;
                this.alive = false;
            }
        }

        @Override
        public Process destroyForcibly()
        {
            this.destroy();

            return this;
        }

        @Override
        public boolean isAlive()
        {
            return this.alive;
        }
    }

    private static class HangingProcess extends FakeProcess
    {
        private HangingProcess()
        {
            super(true, 0);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit)
        {
            return !this.alive;
        }
    }

    private static class InterruptingProcess extends FakeProcess
    {
        private InterruptingProcess()
        {
            super(true, 0);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
        {
            throw new InterruptedException("fake probe interruption");
        }
    }

    private static class CompletionTimeoutProcess extends FakeProcess
    {
        private boolean destroyRequested;

        private CompletionTimeoutProcess()
        {
            super(true, 0);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit)
        {
            if (!this.destroyRequested)
            {
                return false;
            }

            this.alive = false;

            return true;
        }

        @Override
        public void destroy()
        {
            this.destroyCount += 1;
            this.destroyRequested = true;
        }
    }

    private static class StubbornProcess extends FakeProcess
    {
        private StubbornProcess()
        {
            super(true, 0);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit)
        {
            return false;
        }

        @Override
        public void destroy()
        {
            this.destroyCount += 1;
        }

        @Override
        public Process destroyForcibly()
        {
            this.destroyCount += 1;

            return this;
        }
    }

    private static class CloseTrackingOutputStream extends ByteArrayOutputStream
    {
        private int closeCount;

        @Override
        public void close() throws IOException
        {
            super.close();
            this.closeCount += 1;
        }
    }

    private static class CloseTrackingInputStream extends ByteArrayInputStream
    {
        private int closeCount;

        private CloseTrackingInputStream()
        {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException
        {
            super.close();
            this.closeCount += 1;
        }
    }
}
