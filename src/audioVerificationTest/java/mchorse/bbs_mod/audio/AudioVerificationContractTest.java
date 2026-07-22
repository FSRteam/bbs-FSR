package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.film.VideoExportArtifact;
import mchorse.bbs_mod.film.VideoExportArtifacts;
import mchorse.bbs_mod.film.VideoExportRequest;
import mchorse.bbs_mod.film.VideoExportResult;
import mchorse.bbs_mod.importers.types.ImportOutcome;
import mchorse.bbs_mod.ui.film.audio.CaptureFailure;
import mchorse.bbs_mod.ui.film.audio.CaptureResult;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Dependency-light contract markers for the audio pipeline.
 *
 * <p>This launcher deliberately does not require Minecraft, an audio device,
 * FFmpeg, or ffprobe.  The existing focused tests exercise numeric and fake
 * lifecycle behavior; this class checks the cross-layer status and ownership
 * contracts that those tests consume.</p>
 */
public final class AudioVerificationContractTest
{
    private AudioVerificationContractTest()
    {}

    public static void main(String[] args) throws Exception
    {
        typedStatusMatrix();
        importAndLayoutPolicy();
        AudioLayoutAvailabilityTest.runAll();
        terminalDeliveryIsExactlyOnce();
        AudioProductionPerformanceTest.runAll();
        artifactOwnershipAndNoReplace();
        mediaCapabilityGate();
        System.out.println("AudioVerificationContractTest: all hermetic contracts passed");
    }

    private static void typedStatusMatrix() throws Exception
    {
        check(PcmEncoding.fromWaveFormat(1, 24) == PcmEncoding.PCM_S24_LE,
            "format status did not recognize supported PCM24");
        expect(IllegalArgumentException.class,
            () -> PcmEncoding.fromWaveFormat(99, 24));
        check(new MalformedAudioException("fixture", "truncated").source().equals("fixture"),
            "malformed format failure lost its source");
        AudioDecodeException unsupported = new UnsupportedAudioFormatException("fixture", "tag");
        check(unsupported instanceof UnsupportedAudioFormatException
                && "fixture".equals(unsupported.source()),
            "unsupported format failure lost its typed source contract");

        ImportOutcome imported = ImportOutcome.success(2);
        ImportOutcome empty = ImportOutcome.success(0);
        ImportOutcome failed = ImportOutcome.failure(1, "converter failed");
        check(imported.success() && imported.imported() == 2, "import success status is not observable");
        check(!empty.success() && empty.imported() == 0, "empty import was reported as success");
        check(!failed.success() && failed.message().contains("converter"),
            "import failure lost its diagnostic");

        EnumSet<AudioRenderResult.Status> renderStatuses = EnumSet.allOf(AudioRenderResult.Status.class);
        check(renderStatuses.containsAll(EnumSet.of(
                AudioRenderResult.Status.SUCCESS,
                AudioRenderResult.Status.EMPTY,
                AudioRenderResult.Status.CANCELLED,
                AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                AudioRenderResult.Status.MISSING_RESOURCE,
                AudioRenderResult.Status.IO_FAILURE,
                AudioRenderResult.Status.MIX_FAILURE)),
            "mixer status matrix is incomplete");
        AudioRenderResult renderFailure = AudioRenderResult.failure(
            AudioRenderResult.Status.MISSING_RESOURCE, new File("missing.wav"),
            ChannelLayout.STEREO, 0L, "missing", new IOException("missing"));
        check(!renderFailure.success() && renderFailure.cause() != null,
            "mixer failure did not retain its cause");

        CaptureResult cancelled = CaptureResult.cancelled(48_000, 1);
        CaptureResult captureFailure = CaptureResult.failed(
            CaptureFailure.DEVICE_READ_FAILED, new IOException("device"), 48_000, 1);
        check(cancelled.isCancelled() && !cancelled.isSuccess(),
            "capture cancellation collapsed into success");
        check(captureFailure.isFailure() && captureFailure.failure() == CaptureFailure.DEVICE_READ_FAILED,
            "capture failure status lost its failure kind");

        EnumSet<VideoExportResult.Kind> exportKinds = EnumSet.allOf(VideoExportResult.Kind.class);
        check(exportKinds.containsAll(EnumSet.of(
                VideoExportResult.Kind.SUCCESS,
                VideoExportResult.Kind.DEGRADED,
                VideoExportResult.Kind.CANCELLED,
                VideoExportResult.Kind.AUDIO_FAILED,
                VideoExportResult.Kind.MUX_FAILED,
                VideoExportResult.Kind.PUBLISH_FAILED)),
            "export status matrix is incomplete");
        VideoExportResult muxFailure = new VideoExportResult(
            VideoExportResult.Kind.MUX_FAILED, VideoExportResult.Stage.MUX,
            VideoExportArtifact.empty(ChannelLayout.MONO), new IOException("mux"),
            List.of(), "mux failed");
        check(muxFailure.isAborted() && muxFailure.failureKind() == VideoExportResult.FailureKind.MUX,
            "export mux failure was not typed");
    }

    private static void importAndLayoutPolicy() throws Exception
    {
        List<String> source = AudioImportPolicy.SOURCE.buildFfmpegArguments("in", "out");
        List<String> mono = AudioImportPolicy.MONO.buildFfmpegArguments("in", "out");
        List<String> stereo = AudioImportPolicy.STEREO.buildFfmpegArguments("in", "out");
        check(optionValue(source, "-ac") == null, "SOURCE import inserted an implicit channel conversion");
        check("1".equals(optionValue(mono, "-ac")), "MONO import did not request one channel");
        check("2".equals(optionValue(stereo, "-ac")), "STEREO import did not request two channels");
        check(AudioImportPolicy.values().length == 3,
            "import policy exposes an unapproved delivery layout");

        List<String> diagnostics = new ArrayList<>();
        check(ChannelLayout.normalizeExport("stereo", diagnostics::add) == ChannelLayout.STEREO,
            "stereo setting did not survive normalization");
        check(ChannelLayout.normalizeExport("5.1", diagnostics::add) == ChannelLayout.MONO,
            "reserved 5.1 setting did not fall back to mono");
        check(ChannelLayout.normalizeExport(null, diagnostics::add) == ChannelLayout.MONO,
            "missing setting did not use the legacy mono default");
        check(diagnostics.size() == 2 && diagnostics.stream().allMatch(value -> value.length() <= 256),
            "layout migration diagnostics were not bounded");
        check(ChannelLayout.fromId("5.1") == ChannelLayout.SURROUND_5_1
                && !ChannelLayout.SURROUND_5_1.supported(),
            "5.1 identifier is not reserved as unsupported");
        expect(IllegalArgumentException.class,
            () -> new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.SURROUND_5_1, 48_000));
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.channels(ChannelLayout.SURROUND_5_1));
    }

    private static void terminalDeliveryIsExactlyOnce() throws Exception
    {
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<String> terminal = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        TerminalGate<String> gate = new TerminalGate<>(value ->
        {
            deliveries.incrementAndGet();
            terminal.set(value);
        });

        Thread worker = new Thread(() ->
        {
            started.countDown();
            try
            {
                release.await();
                gate.complete("SUCCESS");
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                gate.complete("INTERRUPTED");
            }
            finally
            {
                finished.countDown();
            }
        }, "audio-verification-terminal");
        worker.start();

        check(started.await(1L, TimeUnit.SECONDS), "fake session did not start");
        gate.complete("CANCELLED");
        release.countDown();
        check(finished.await(1L, TimeUnit.SECONDS), "fake session did not terminate within one second");
        worker.join(1_000L);
        gate.complete("DUPLICATE");

        check(!worker.isAlive(), "fake terminal worker leaked past the one-second bound");
        check(deliveries.get() == 1 && "CANCELLED".equals(terminal.get()),
            "terminal callback was delivered more than once or lost cancellation");
    }

    private static void artifactOwnershipAndNoReplace() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-verification-artifacts-");

        try
        {
            VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root, "contract");
            UUID sessionId = artifacts.sessionId();
            String workName = artifacts.workDirectory().getFileName().toString();
            check(workName.equals(".bbs-export-" + sessionId),
                "work directory does not carry its session provenance");
            check(artifacts.finalVideo().getFileName().toString().contains(sessionId.toString()),
                "published artifact name does not carry its session provenance");
            check(!artifacts.owns(root.resolve("unowned.mp4")),
                "artifact owner accepted a path outside its session directory");

            Files.write(artifacts.finalVideo(), new byte[] {7, 7, 7});
            Files.write(artifacts.filmAudio(), new byte[] {8, 8, 8});
            expect(FileAlreadyExistsException.class, () -> artifacts.claim(artifacts.filmAudio()));

            artifacts.claim(artifacts.rawVideo());
            artifacts.claim(artifacts.mixedAudio());
            Files.write(artifacts.rawVideo(), new byte[] {1, 2, 3});
            Files.write(artifacts.mixedAudio(), new byte[] {4, 5, 6});
            artifacts.markProduced(artifacts.rawVideo());
            artifacts.markProduced(artifacts.mixedAudio());
            byte[] userVideo = Files.readAllBytes(artifacts.finalVideo());
            expect(FileAlreadyExistsException.class, artifacts::publishRawVideo);
            check(Arrays.equals(userVideo, Files.readAllBytes(artifacts.finalVideo())),
                "publish attempted to replace a pre-existing user video");

            VideoExportRequest request = new VideoExportRequest(sessionId, 4L, 0D, 10D, false,
                24D, 48_000, ChannelLayout.STEREO, true, false, artifacts);
            VideoExportArtifact descriptor = artifacts.describe(request, true, 240L, 20_000L, false);
            check(sessionId.equals(descriptor.sessionId()) && descriptor.generation() == 4L,
                "artifact descriptor lost session identity");
            check(descriptor.requestedLayout() == ChannelLayout.STEREO
                    && descriptor.sourceStart() == 0D && descriptor.sourceEnd() == 10D,
                "artifact descriptor lost the immutable export range");
            VideoExportResult result = new VideoExportResult(VideoExportResult.Kind.SUCCESS,
                VideoExportResult.Stage.COMPLETE, descriptor, null, List.of(), "ok");
            check(sessionId.equals(result.sessionId()) && result.isSuccess(),
                "typed result is not bound to the producing session");

            List<Throwable> cleanup = artifacts.cleanup();
            check(cleanup.isEmpty(), "owned artifact cleanup reported an unexpected failure");
            check(Files.exists(artifacts.finalVideo()) && Files.exists(artifacts.filmAudio()),
                "cleanup removed pre-existing unclaimed user files");
            check(!Files.exists(artifacts.rawVideo()) && !Files.exists(artifacts.mixedAudio()),
                "cleanup retained claimed session intermediates");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void mediaCapabilityGate()
    {
        String ffmpeg = resolveTool("ffmpegPath", "FFMPEG", "ffmpeg");
        String ffprobe = resolveTool("ffprobePath", "FFPROBE", "ffprobe");
        boolean strict = Boolean.parseBoolean(System.getProperty("requireMediaTools", "false"));

        if (ffmpeg == null || ffprobe == null)
        {
            String message = "ffmpeg=" + (ffmpeg == null ? "missing" : ffmpeg)
                + ", ffprobe=" + (ffprobe == null ? "missing" : ffprobe);
            if (strict)
            {
                throw new AssertionError("MEDIA FAIL (strict): " + message);
            }

            System.out.println("MEDIA SKIPPED: " + message);
        }
        else
        {
            System.out.println("MEDIA CAPABLE: ffmpeg=" + ffmpeg + ", ffprobe=" + ffprobe
                + " (real-media execution remains an explicit smoke tier)");
        }
    }

    private static String resolveTool(String property, String environment, String command)
    {
        String configured = firstNonBlank(System.getProperty(property), System.getenv(environment));
        if (configured != null)
        {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize().toString();
        }

        String pathValue = System.getenv("PATH");
        if (pathValue == null) return null;

        for (String directory : pathValue.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
        {
            Path candidate = Path.of(directory).resolve(command);
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
            if (System.getProperty("os.name", "").toLowerCase().contains("win"))
            {
                Path executable = Path.of(directory).resolve(command + ".exe");
                if (Files.isRegularFile(executable)) return executable.toAbsolutePath().normalize().toString();
            }
        }

        return null;
    }

    private static String firstNonBlank(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank()) return value;
        }

        return null;
    }

    private static String optionValue(List<String> arguments, String option)
    {
        for (int i = 0; i + 1 < arguments.size(); i++)
        {
            if (option.equals(arguments.get(i))) return arguments.get(i + 1);
        }

        return null;
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingAction action)
        throws Exception
    {
        try
        {
            action.run();
        }
        catch (Throwable error)
        {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getSimpleName() + " but got "
                + error.getClass().getSimpleName(), error);
        }

        throw new AssertionError("Expected " + type.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }

    private static final class TerminalGate<T>
    {
        private final AtomicBoolean completed = new AtomicBoolean();
        private final Consumer<T> consumer;

        private TerminalGate(Consumer<T> consumer)
        {
            this.consumer = consumer;
        }

        private void complete(T value)
        {
            if (this.completed.compareAndSet(false, true)) this.consumer.accept(value);
        }
    }

}
