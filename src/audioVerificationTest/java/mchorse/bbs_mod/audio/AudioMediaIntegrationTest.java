package mchorse.bbs_mod.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;
import mchorse.bbs_mod.utils.VideoExportUtils;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional real-media verification for the mono/stereo export contract.
 *
 * <p>This launcher uses pure audio/export helpers, the project's existing Gson
 * dependency, and the JDK. It never starts Minecraft or uses reflection.
 * Missing media tools are a visible skip unless strict mode is enabled with
 * {@code -DrequireMediaTools=true} or {@code --strict}.</p>
 */
public final class AudioMediaIntegrationTest
{
    private static final Duration TOOL_VERSION_TIMEOUT = Duration.ofSeconds(5L);
    private static final Duration MEDIA_PROCESS_TIMEOUT = Duration.ofSeconds(15L);
    /* Keep process teardown inside the strict one-second return gate while
     * leaving headroom for log and partial-artifact cleanup. */
    private static final Duration PROCESS_TEARDOWN_TIMEOUT = Duration.ofMillis(750L);
    private static final Duration PROCESS_RETURN_TIMEOUT = Duration.ofSeconds(1L);
    private static final Duration INTENTIONAL_TIMEOUT = Duration.ofMillis(250L);
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_WORKSPACE_FILES = 128;
    private static final long MAX_WORKSPACE_BYTES = 32L * 1024L * 1024L;
    private static final int SAMPLE_RATE_44_1 = 44_100;
    private static final int SAMPLE_RATE_48 = 48_000;
    private static final int FIXTURE_MILLISECONDS = 500;
    private static final int VIDEO_FRAME_RATE = 24;
    private static final int VIDEO_WIDTH = 160;
    private static final int VIDEO_HEIGHT = 96;
    private static final int VIDEO_FRAMES = VIDEO_FRAME_RATE * FIXTURE_MILLISECONDS / 1_000;
    private static final int SAMPLES_PER_VIDEO_FRAME = SAMPLE_RATE_48 / VIDEO_FRAME_RATE;
    private static final double MIN_VIDEO_MARKER_LUMA = 160D;
    private static final double MIN_AUDIO_MARKER_RMS = 0.08D;
    private static final List<MarkerPoint> OUTPUT_MARKERS = List.of(
        new MarkerPoint("start", 0),
        new MarkerPoint("middle", VIDEO_FRAMES / 2),
        new MarkerPoint("end", VIDEO_FRAMES - 1));
    private static final int BLOCK_FRAMES = 8_192;
    private static final long TEN_MINUTE_FRAMES = 48_000L * 60L * 10L;
    private static final long MAX_PERFORMANCE_BYTES = 64L * 1024L * 1024L;
    private static final String CUSTOM_MUX_ARGUMENTS =
        "-nostdin -n -i \"%VIDEO%\" -i \"%AUDIO_TRACK%\" "
            + "-map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -profile:a aac_low "
            + "-ar %AUDIO_SAMPLE_RATE% -ac %AUDIO_CHANNELS% "
            + "-channel_layout %AUDIO_LAYOUT% -b:a 192k %OUTPUT%";

    private AudioMediaIntegrationTest()
    {}

    public static void main(String[] arguments) throws Exception
    {
        try
        {
            Options options = Options.parse(arguments);

            if (options.help())
            {
                printUsage();
                return;
            }

            if (options.performance())
            {
                runPerformanceGate();
            }

            runMediaGate(options.strict());
        }
        catch (MediaGateException e)
        {
            System.err.println("AudioMediaIntegrationTest: FAIL kind=" + e.kind()
                + " message=" + oneLine(e.getMessage()));
            throw e;
        }
    }

    private static void runMediaGate(boolean strict) throws Exception
    {
        ToolResolution ffmpegResolution = ToolResolver.resolve(
            "ffmpeg", "ffmpegPath", List.of("FFMPEG_PATH", "FFMPEG"));
        ToolResolution ffprobeResolution = ToolResolver.resolve(
            "ffprobe", "ffprobePath", List.of("FFPROBE_PATH", "FFPROBE"));

        if (!ffmpegResolution.available() || !ffprobeResolution.available())
        {
            String diagnostic = "ffmpeg=" + ffmpegResolution.description()
                + ", ffprobe=" + ffprobeResolution.description();

            System.out.println("AudioMediaIntegrationTest: SKIPPED missing media tools; " + diagnostic);

            if (strict)
            {
                throw new MediaGateException(MediaFailureKind.TOOLS_UNAVAILABLE,
                    "strict media verification requires both tools; " + diagnostic);
            }

            return;
        }

        Path workspace = createWorkspace();
        Exception primary = null;

        try
        {
            MediaHarness harness = new MediaHarness(
                ffmpegResolution.asTool(), ffprobeResolution.asTool(), workspace);

            harness.run(strict);
            assertWorkspaceBounded(workspace);
        }
        catch (Exception e)
        {
            primary = e;
            throw e;
        }
        finally
        {
            try
            {
                deleteWorkspace(workspace);
            }
            catch (IOException e)
            {
                MediaGateException cleanup = new MediaGateException(
                    MediaFailureKind.CLEANUP_FAILURE,
                    "failed to delete media workspace " + workspace, e);

                if (primary != null)
                {
                    primary.addSuppressed(cleanup);
                }
                else
                {
                    throw cleanup;
                }
            }
        }

        if (Files.exists(workspace))
        {
            throw new MediaGateException(MediaFailureKind.CLEANUP_FAILURE,
                "media workspace still exists after cleanup: " + workspace);
        }

        System.out.println("AudioMediaIntegrationTest: PASS mono/stereo WAV and MP4 media contracts");
    }

    private static Path createWorkspace() throws MediaGateException
    {
        try
        {
            return Files.createTempDirectory("bbs-audio-media-" + UUID.randomUUID() + "-")
                .toAbsolutePath().normalize();
        }
        catch (IOException e)
        {
            throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                "failed to create a unique media workspace", e);
        }
    }

    private static void runPerformanceGate() throws MediaGateException
    {
        int channels = 2;
        float[] mix = new float[BLOCK_FRAMES * channels];
        short[] packed = new short[BLOCK_FRAMES * channels];
        long ownedBytes = (long) mix.length * Float.BYTES
            + (long) packed.length * Short.BYTES;

        verify(mix.length == BLOCK_FRAMES * channels
                && packed.length == BLOCK_FRAMES * channels,
            "performance buffers do not match the 8,192-frame block contract");
        verify(ownedBytes < MAX_PERFORMANCE_BYTES,
            "performance fixture working buffers exceed 64 MiB");

        /* Warm the loop before the after-fixture-load heap baseline. */
        long state = renderProceduralBlocks(mix, packed, BLOCK_FRAMES, 0x5eedL).state();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long baseline = memory.getHeapMemoryUsage().getUsed();
        long peak = baseline;
        long completed = 0L;
        long blocks = 0L;
        long checksum = 0L;

        while (completed < TEN_MINUTE_FRAMES)
        {
            int frames = (int) Math.min(BLOCK_FRAMES, TEN_MINUTE_FRAMES - completed);
            ProceduralBlock block = renderProceduralBlocks(mix, packed, frames, state);

            state = block.state();
            checksum = checksum * 31L + block.checksum();
            completed += frames;
            blocks += 1L;
            peak = Math.max(peak, memory.getHeapMemoryUsage().getUsed());
        }

        long expectedBlocks = (TEN_MINUTE_FRAMES + BLOCK_FRAMES - 1L) / BLOCK_FRAMES;
        long observedGrowth = Math.max(0L, peak - baseline);
        long peakWorkingBytes = Math.max(ownedBytes, observedGrowth);

        verify(completed == TEN_MINUTE_FRAMES,
            "performance marker did not render the full ten-minute fixture");
        verify(blocks == expectedBlocks,
            "performance marker committed an unexpected number of blocks");
        verify(peakWorkingBytes < MAX_PERFORMANCE_BYTES,
            "performance marker exceeded the 64 MiB working-set contract: "
                + peakWorkingBytes + " bytes");

        System.out.println("AudioMediaIntegrationTest: PERFORMANCE PASS duration_seconds=600"
            + " sample_rate=48000 channels=2 block_frames=" + BLOCK_FRAMES
            + " blocks=" + blocks + " peak_bytes=" + peakWorkingBytes
            + " owned_buffer_bytes=" + ownedBytes + " checksum=" + checksum);
    }

    private static ProceduralBlock renderProceduralBlocks(
        float[] mix, short[] packed, long frames, long initialState)
    {
        if (frames < 0L || frames > BLOCK_FRAMES)
        {
            throw new IllegalArgumentException(
                "procedural render escaped the 8,192-frame block contract: " + frames);
        }

        long state = initialState;
        long checksum = 0L;
        int samples = Math.toIntExact(frames * 2L);

        for (int sample = 0; sample < samples; sample += 2)
        {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            float left = ((state >>> 40) / 8_388_608F - 1F) * 0.5F;
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            float right = ((state >>> 40) / 8_388_608F - 1F) * 0.25F;

            mix[sample] = left;
            mix[sample + 1] = right;
            packed[sample] = quantize(left);
            packed[sample + 1] = quantize(right);
            checksum = checksum * 31L + packed[sample];
            checksum = checksum * 31L + packed[sample + 1];
        }

        return new ProceduralBlock(state, checksum);
    }

    private static short quantize(float sample)
    {
        int value = Math.round(Math.max(-1F, Math.min(1F, sample)) * 32_767F);

        return (short) value;
    }

    private static void printUsage()
    {
        System.out.println("Usage: AudioMediaIntegrationTest [--strict] [--performance]");
        System.out.println("Properties: ffmpegPath, ffprobePath, requireMediaTools, "
            + "audioVerificationPerformance");
    }

    private static String oneLine(String value)
    {
        if (value == null) return "";

        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();

        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512) + "...";
    }

    private static void verify(boolean condition, String message) throws MediaGateException
    {
        if (!condition)
        {
            throw new MediaGateException(MediaFailureKind.CONTRACT_MISMATCH, message);
        }
    }

    private record Options(boolean strict, boolean performance, boolean help)
    {
        private static Options parse(String[] arguments) throws MediaGateException
        {
            boolean strict = Boolean.getBoolean("requireMediaTools")
                || Boolean.getBoolean("audioMediaStrict");
            boolean performance = Boolean.getBoolean("audioVerificationPerformance")
                || Boolean.getBoolean("audioMediaPerformance")
                || Boolean.getBoolean("audio.media.performance")
                || Boolean.getBoolean("performance");
            boolean help = false;

            for (String argument : arguments)
            {
                switch (argument)
                {
                    case "--strict" -> strict = true;
                    case "--performance" -> performance = true;
                    case "--help", "-h" -> help = true;
                    default -> throw new MediaGateException(MediaFailureKind.ARGUMENT_FAILURE,
                        "unknown argument: " + argument);
                }
            }

            return new Options(strict, performance, help);
        }
    }

    private record ProceduralBlock(long state, long checksum)
    {}

    public enum MediaFailureKind
    {
        TOOLS_UNAVAILABLE,
        ARGUMENT_FAILURE,
        START_FAILURE,
        INTERRUPTED,
        TIMEOUT,
        NON_ZERO_EXIT,
        TERMINATION_FAILURE,
        INVALID_JSON,
        INVALID_CSV,
        CONTRACT_MISMATCH,
        OUTPUT_EXISTS,
        IO_FAILURE,
        CLEANUP_FAILURE
    }

    /** A typed gate failure suitable for JavaExec diagnostics. */
    public static class MediaGateException extends Exception
    {
        private static final long serialVersionUID = 1L;
        private final MediaFailureKind kind;

        private MediaGateException(MediaFailureKind kind, String message)
        {
            super(message);
            this.kind = Objects.requireNonNull(kind);
        }

        private MediaGateException(MediaFailureKind kind, String message, Throwable cause)
        {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind);
        }

        public String kind()
        {
            return this.kind.name();
        }

        public MediaFailureKind failureKind()
        {
            return this.kind;
        }
    }

    /** Process-specific typed failure, including timeout and exit status. */
    public static final class MediaProcessException extends MediaGateException
    {
        private static final long serialVersionUID = 1L;
        private final Integer exitCode;
        private final String output;

        private MediaProcessException(
            MediaFailureKind kind, String message, Integer exitCode, String output, Throwable cause)
        {
            super(kind, message, cause);
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public Integer exitCode()
        {
            return this.exitCode;
        }

        public String output()
        {
            return this.output;
        }
    }

    private record Tool(String name, Path executable, String source)
    {}

    private record ToolResolution(String name, Path executable, String source, String diagnostic)
    {
        private boolean available()
        {
            return this.executable != null;
        }

        private Tool asTool()
        {
            if (!this.available()) throw new IllegalStateException(this.name + " is unavailable");

            return new Tool(this.name, this.executable, this.source);
        }

        private String description()
        {
            return this.available()
                ? this.executable + " (source=" + this.source + ")"
                : this.diagnostic;
        }
    }

    private static final class ToolResolver
    {
        private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        private static ToolResolution resolve(
            String tool, String property, List<String> environmentNames)
        {
            String configured = trimToNull(System.getProperty(property));

            if (configured != null)
            {
                Path resolved = resolveConfigured(tool, configured);

                return resolved == null
                    ? new ToolResolution(tool, null, property,
                        "invalid " + property + "=" + oneLine(configured))
                    : new ToolResolution(tool, resolved, property, null);
            }

            for (String environmentName : environmentNames)
            {
                configured = trimToNull(System.getenv(environmentName));

                if (configured != null)
                {
                    Path resolved = resolveConfigured(tool, configured);

                    return resolved == null
                        ? new ToolResolution(tool, null, environmentName,
                            "invalid " + environmentName + "=" + oneLine(configured))
                        : new ToolResolution(tool, resolved, environmentName, null);
                }
            }

            Path resolved = searchPath(tool);

            return resolved == null
                ? new ToolResolution(tool, null, "PATH", "not found on PATH")
                : new ToolResolution(tool, resolved, "PATH", null);
        }

        private static Path resolveConfigured(String tool, String configured)
        {
            String value = unquote(configured);

            try
            {
                Path candidate = Path.of(value);

                if (Files.isDirectory(candidate))
                {
                    Path direct = findExecutable(candidate, tool);
                    if (direct != null) return direct;

                    return findExecutable(candidate.resolve("bin"), tool);
                }

                Path file = regularFile(candidate);
                if (file != null) return file;

                if (WINDOWS && !hasExtension(candidate))
                {
                    file = regularFile(Path.of(value + ".exe"));
                    if (file != null) return file;
                }

                if (candidate.getNameCount() == 1)
                {
                    return searchPath(value);
                }
            }
            catch (InvalidPathException ignored)
            {}

            return null;
        }

        private static Path searchPath(String command)
        {
            String path = trimToNull(System.getenv("PATH"));
            if (path == null) return null;

            for (String element : path.split(
                java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1))
            {
                String directory = trimToNull(unquote(element));
                if (directory == null) continue;

                try
                {
                    Path found = findExecutable(Path.of(directory), command);
                    if (found != null) return found;
                }
                catch (InvalidPathException ignored)
                {}
            }

            return null;
        }

        private static Path findExecutable(Path directory, String command)
        {
            Path direct = regularFile(directory.resolve(command));
            if (direct != null) return direct;

            if (WINDOWS && !hasExtension(Path.of(command)))
            {
                for (String extension : executableExtensions())
                {
                    Path candidate = regularFile(directory.resolve(command + extension));
                    if (candidate != null) return candidate;
                }
            }

            return null;
        }

        private static List<String> executableExtensions()
        {
            String pathExt = trimToNull(System.getenv("PATHEXT"));
            if (pathExt == null) return List.of(".exe", ".com", ".cmd", ".bat");

            List<String> extensions = new ArrayList<>();

            for (String extension : pathExt.split(";"))
            {
                extension = trimToNull(extension);
                if (extension != null) extensions.add(extension.toLowerCase(Locale.ROOT));
            }

            return extensions.isEmpty() ? List.of(".exe") : extensions;
        }

        private static Path regularFile(Path candidate)
        {
            return Files.isRegularFile(candidate)
                ? candidate.toAbsolutePath().normalize()
                : null;
        }

        private static boolean hasExtension(Path path)
        {
            Path name = path.getFileName();
            return name != null && name.toString().lastIndexOf('.') > 0;
        }

        private static String unquote(String value)
        {
            if (value == null || value.length() < 2) return value;

            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);

            return (first == last && (first == '\'' || first == '"'))
                ? value.substring(1, value.length() - 1)
                : value;
        }

        private static String trimToNull(String value)
        {
            if (value == null) return null;

            value = value.trim();

            return value.isEmpty() ? null : value;
        }
    }

    private static final class MediaHarness
    {
        private final Tool ffmpeg;
        private final Tool ffprobe;
        private final Path workspace;
        private final MediaProcessRunner processes;

        private MediaHarness(Tool ffmpeg, Tool ffprobe, Path workspace)
        {
            this.ffmpeg = ffmpeg;
            this.ffprobe = ffprobe;
            this.workspace = workspace;
            this.processes = new MediaProcessRunner(workspace);
        }

        private void run(boolean strict) throws Exception
        {
            recordToolVersion(this.ffmpeg);
            recordToolVersion(this.ffprobe);
            validateCustomExportArguments();

            int monoFrames = SAMPLE_RATE_44_1 * FIXTURE_MILLISECONDS / 1_000;
            int stereoFrames = SAMPLE_RATE_48 * FIXTURE_MILLISECONDS / 1_000;
            Path mono44 = this.workspace.resolve("handwritten-mono-44100.wav");
            Path stereo48 = this.workspace.resolve("handwritten-stereo-48000.wav");

            WaveFixture.write(mono44, SAMPLE_RATE_44_1, 1, monoFrames, true);
            WaveFixture.write(stereo48, SAMPLE_RATE_48, 2, stereoFrames, true);
            assertOddPadding(mono44, 1, SAMPLE_RATE_44_1, monoFrames);
            assertOddPadding(stereo48, 2, SAMPLE_RATE_48, stereoFrames);
            assertWaveProbe(mono44, SAMPLE_RATE_44_1, 1, "mono", monoFrames);
            assertWaveProbe(stereo48, SAMPLE_RATE_48, 2, "stereo", stereoFrames);

            Path mono48 = publishArtifact(
                this.workspace.resolve("generated-mono-48000.wav"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", mono44.toString(), "-map", "0:a:0", "-c:a", "pcm_s16le",
                    "-ar", "48000", "-ac", "1", "-channel_layout", "mono",
                    partial.toString()),
                partial -> assertWaveProbe(partial, SAMPLE_RATE_48, 1, "mono",
                    SAMPLE_RATE_48 * FIXTURE_MILLISECONDS / 1_000));

            Path video = publishArtifact(
                this.workspace.resolve("generated-video-only.mp4"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-f", "lavfi", "-i",
                    "color=c=black:s=160x96:r=" + VIDEO_FRAME_RATE
                        + ":d=" + decimalSeconds(),
                    "-an", "-c:v", "mpeg4", "-pix_fmt", "yuv420p",
                    "-t", decimalSeconds(), partial.toString()),
                this::assertNonEmpty);

            List<String> monoArguments = resolvedMuxArguments(
                video, mono48, this.workspace.resolve("mono-placeholder.mp4"),
                ChannelLayout.MONO);
            List<String> stereoArguments = resolvedMuxArguments(
                video, stereo48, this.workspace.resolve("stereo-placeholder.mp4"),
                ChannelLayout.STEREO);

            assertOption(monoArguments, "-ac", "1");
            assertOption(monoArguments, "-channel_layout", "mono");
            assertOption(stereoArguments, "-ac", "2");
            assertOption(stereoArguments, "-channel_layout", "stereo");
            verify(!forcesMono(stereoArguments),
                "custom stereo export arguments force -ac 1");

            Path monoMp4 = publishMux(video, mono48, "generated-mono.mp4",
                ChannelLayout.MONO);
            Path stereoMp4 = publishMux(video, stereo48, "generated-stereo.mp4",
                ChannelLayout.STEREO);

            assertMp4Probe(monoMp4, 1, "mono");
            assertMp4Probe(stereoMp4, 2, "stereo");
            assertStereoIdentity(stereoMp4);
            runMuxPathAndMarkerFixtures();
            assertNoPartialOnRejectedVerification();
            assertTypedNonZeroFailure();
            if (strict)
            {
                assertTypedTimeoutFailure();
            }
        }

        private void runMuxPathAndMarkerFixtures() throws Exception
        {
            TimelineRange fullRange = new TimelineRange("full", 0, VIDEO_FRAMES, false);
            TimelineRange panelRange = new TimelineRange(
                "panel-loop", VIDEO_FRAMES / 2, VIDEO_FRAMES / 2 + VIDEO_FRAMES, true);
            List<Integer> fullMarkers = sourceMarkerFrames(fullRange);
            List<Integer> panelMarkers = sourceMarkerFrames(panelRange);

            Path fullVideo = publishMarkerVideo(
                "alignment-full-video.mp4", fullRange.sourceEndFrame(), fullMarkers);
            Path directFilmMono = this.workspace.resolve("alignment-direct-film-mono.wav");

            WaveFixture.writeMarkers(directFilmMono, 1,
                fullRange.sourceEndFrame(), fullMarkers);
            assertWaveProbe(directFilmMono, SAMPLE_RATE_48, 1, "mono",
                (long) VIDEO_FRAMES * SAMPLES_PER_VIDEO_FRAME);

            MuxFixture directFixture = publishRoutedMux(
                "direct-film", fullVideo,
                new RoutedAudio(directFilmMono, MuxAudioPath.DIRECT_FILM,
                    ChannelLayout.MONO), fullRange);
            assertMarkerAlignment(directFixture);

            Path postFilmStereo = this.workspace.resolve("alignment-post-film-stereo.wav");
            Path minecraftStereo = this.workspace.resolve("alignment-minecraft-stereo.wav");

            WaveFixture.writeMarkers(postFilmStereo, 2,
                fullRange.sourceEndFrame(), fullMarkers);
            WaveFixture.writeMinecraftSound(minecraftStereo, fullRange.sourceEndFrame());
            RoutedAudio postMinecraft = publishPostMinecraftMix(
                postFilmStereo, minecraftStereo, "alignment-post-minecraft-stereo.wav");
            assertPostMinecraftMix(postFilmStereo, minecraftStereo, postMinecraft.path());

            MuxFixture postMinecraftFixture = publishRoutedMux(
                "post-minecraft-sound", fullVideo, postMinecraft, fullRange);
            RawAudioInspection postMinecraftOutput = assertMarkerAlignment(postMinecraftFixture);

            verify(postMinecraftOutput.channelRms().length == 2
                    && postMinecraftOutput.channelRms()[1] > 0.03D,
                "post-Minecraft-sound mux lost the right-channel game-sound bed");

            Path panelSourceVideo = publishMarkerVideo(
                "alignment-panel-source-video.mp4", VIDEO_FRAME_RATE, panelMarkers);
            Path panelSourceAudio = this.workspace.resolve(
                "alignment-panel-source-film-stereo.wav");

            WaveFixture.writeMarkers(panelSourceAudio, 2, VIDEO_FRAME_RATE, panelMarkers);
            Path panelVideo = publishPanelLoopVideo(panelSourceVideo, panelRange);
            Path panelAudio = publishPanelLoopAudio(panelSourceAudio, panelRange);
            MuxFixture panelFixture = publishRoutedMux(
                "panel-loop", panelVideo,
                new RoutedAudio(panelAudio, MuxAudioPath.DIRECT_FILM,
                    ChannelLayout.STEREO), panelRange);

            verify(panelFixture.range().panelLoop()
                    && panelFixture.range().sourceStartFrame() > 0,
                "panel-loop fixture did not retain a nonzero source range");
            assertMarkerAlignment(panelFixture);
        }

        private Path publishMarkerVideo(
            String outputName, int totalFrames, List<Integer> markerFrames) throws Exception
        {
            verify(totalFrames > 0 && markerFrames.size() == OUTPUT_MARKERS.size(),
                "marker video fixture has an invalid frame/marker count");
            StringBuilder enabled = new StringBuilder();

            for (int markerFrame : markerFrames)
            {
                verify(markerFrame >= 0 && markerFrame < totalFrames,
                    "video marker escaped its source range: " + markerFrame);
                if (!enabled.isEmpty()) enabled.append('+');
                enabled.append("eq(n\\,").append(markerFrame).append(')');
            }

            String filter = "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='"
                + enabled + "'";

            return publishArtifact(this.workspace.resolve(outputName),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-f", "lavfi", "-i",
                    "color=c=black:s=" + VIDEO_WIDTH + "x" + VIDEO_HEIGHT
                        + ":r=" + VIDEO_FRAME_RATE,
                    "-vf", filter, "-frames:v", String.valueOf(totalFrames),
                    "-an", "-c:v", "mpeg4", "-g", "1", "-q:v", "2",
                    "-pix_fmt", "yuv420p", partial.toString()),
                this::assertNonEmpty);
        }

        private RoutedAudio publishPostMinecraftMix(
            Path filmAudio, Path minecraftAudio, String outputName) throws Exception
        {
            Path mixed = publishArtifact(this.workspace.resolve(outputName),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", filmAudio.toString(), "-i", minecraftAudio.toString(),
                    "-filter_complex",
                    "[0:a:0][1:a:0]amix=inputs=2:duration=first:normalize=0[mixed]",
                    "-map", "[mixed]", "-c:a", "pcm_s16le", "-ar", "48000",
                    "-ac", "2", "-channel_layout", "stereo", partial.toString()),
                partial -> assertWaveProbe(partial, SAMPLE_RATE_48, 2, "stereo",
                    (long) VIDEO_FRAMES * SAMPLES_PER_VIDEO_FRAME));

            return new RoutedAudio(mixed, MuxAudioPath.POST_MINECRAFT_SOUND,
                ChannelLayout.STEREO);
        }

        private Path publishPanelLoopVideo(Path source, TimelineRange range) throws Exception
        {
            verify(range.panelLoop() && range.sourceStartFrame() > 0,
                "panel video trim requires a nonzero panel-loop range");

            return publishArtifact(this.workspace.resolve("alignment-panel-loop-video.mp4"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", source.toString(), "-vf",
                    "trim=start_frame=" + range.sourceStartFrame()
                        + ":end_frame=" + range.sourceEndFrame()
                        + ",setpts=PTS-STARTPTS",
                    "-frames:v", String.valueOf(range.frameCount()), "-an",
                    "-c:v", "mpeg4", "-g", "1", "-q:v", "2",
                    "-pix_fmt", "yuv420p", partial.toString()),
                this::assertNonEmpty);
        }

        private Path publishPanelLoopAudio(Path source, TimelineRange range) throws Exception
        {
            long startSample = (long) range.sourceStartFrame() * SAMPLES_PER_VIDEO_FRAME;
            long endSample = (long) range.sourceEndFrame() * SAMPLES_PER_VIDEO_FRAME;

            verify(range.panelLoop() && startSample > 0L,
                "panel audio trim requires a nonzero panel-loop range");

            return publishArtifact(this.workspace.resolve("alignment-panel-loop-film-stereo.wav"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", source.toString(), "-af",
                    "atrim=start_sample=" + startSample + ":end_sample=" + endSample
                        + ",asetpts=PTS-STARTPTS",
                    "-map", "0:a:0", "-c:a", "pcm_s16le", "-ar", "48000",
                    "-ac", "2", "-channel_layout", "stereo", partial.toString()),
                partial -> assertWaveProbe(partial, SAMPLE_RATE_48, 2, "stereo",
                    (long) range.frameCount() * SAMPLES_PER_VIDEO_FRAME));
        }

        private MuxFixture publishRoutedMux(
            String id, Path video, RoutedAudio audio, TimelineRange range) throws Exception
        {
            verify(range.frameCount() == VIDEO_FRAMES,
                "routed mux fixture must span exactly " + VIDEO_FRAMES + " video frames");
            Path media = publishMux(video, audio.path(),
                "alignment-" + id + ".mp4", audio.layout());

            return new MuxFixture(id, media, audio.path(), audio.pathKind(),
                audio.layout(), range);
        }

        private void assertPostMinecraftMix(Path film, Path minecraft, Path mixed)
            throws MediaGateException
        {
            WaveInspection filmInspection = WaveFixture.inspect(film, true);
            WaveInspection minecraftInspection = WaveFixture.inspect(minecraft, true);
            WaveInspection mixedInspection = WaveFixture.inspect(mixed, true);

            verify(filmInspection.rms().length == 2
                    && filmInspection.rms()[0] > 0.1D
                    && filmInspection.rms()[1] < 0.0001D,
                "post-Minecraft film fixture did not isolate markers to the left channel");
            verify(minecraftInspection.rms().length == 2
                    && minecraftInspection.rms()[0] < 0.0001D
                    && minecraftInspection.rms()[1] > 0.03D,
                "Minecraft-sound fixture did not isolate its bed to the right channel");
            verify(mixedInspection.rms().length == 2
                    && mixedInspection.rms()[0] > 0.1D
                    && mixedInspection.rms()[1] > 0.03D,
                "post-Minecraft mix did not retain both film markers and game sound");
        }

        private RawAudioInspection assertMarkerAlignment(MuxFixture fixture) throws Exception
        {
            ProbeReport report = probe(fixture.media());
            ProbeStream video = onlyStream(report, "video");
            ProbeStream audio = onlyStream(report, "audio");
            Path rawVideo = publishArtifact(
                this.workspace.resolve("decoded-" + fixture.id() + "-video.gray"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", fixture.media().toString(), "-map", "0:v:0", "-vsync", "0",
                    "-pix_fmt", "gray", "-f", "rawvideo", partial.toString()),
                this::assertNonEmpty);
            Path rawAudio = publishArtifact(
                this.workspace.resolve("decoded-" + fixture.id() + "-audio.s16"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", fixture.media().toString(), "-map", "0:a:0",
                    "-c:a", "pcm_s16le", "-ar", "48000", "-ac",
                    String.valueOf(fixture.layout().channels()), "-f", "s16le",
                    partial.toString()),
                this::assertNonEmpty);
            double[] videoLuma = readVideoFrameLuma(rawVideo, fixture.range().frameCount());
            RawAudioInspection audioInspection = readAudioFrameRms(
                rawAudio, fixture.layout().channels(), fixture.range().frameCount());
            double videoStart = requiredStartTime(video, fixture.media());
            double audioStart = requiredStartTime(audio, fixture.media());
            List<String> markerDiagnostics = new ArrayList<>();

            for (MarkerPoint marker : OUTPUT_MARKERS)
            {
                int first = Math.max(0, marker.outputFrame() - 1);
                int last = Math.min(fixture.range().frameCount() - 1,
                    marker.outputFrame() + 1);
                int videoFrame = peakIndex(videoLuma, first, last);
                int audioFrame = peakIndex(audioInspection.frameRms(), first, last);
                double videoMarkerTime = videoStart + videoFrame / (double) VIDEO_FRAME_RATE;
                double audioMarkerTime = audioStart + audioFrame / (double) VIDEO_FRAME_RATE;
                double deltaFrames = Math.abs(videoMarkerTime - audioMarkerTime)
                    * VIDEO_FRAME_RATE;

                verify(videoLuma[videoFrame] >= MIN_VIDEO_MARKER_LUMA,
                    fixture.id() + " " + marker.id() + " video marker was not decoded");
                verify(audioInspection.frameRms()[audioFrame] >= MIN_AUDIO_MARKER_RMS,
                    fixture.id() + " " + marker.id() + " audio marker was not decoded");
                verify(deltaFrames <= 1D + 1e-6D,
                    fixture.id() + " " + marker.id()
                        + " marker alignment exceeded one video frame: " + deltaFrames);
                markerDiagnostics.add(marker.id() + "_delta_frames="
                    + String.format(Locale.ROOT, "%.3f", deltaFrames));
            }

            System.out.println("AudioMediaIntegrationTest: MARKERS PASS path="
                + fixture.pathKind().id() + " layout=" + fixture.layout().id()
                + " range=" + fixture.range().id()
                + " source_frames=" + fixture.range().sourceStartFrame()
                + ".." + fixture.range().sourceEndFrame() + " "
                + String.join(" ", markerDiagnostics));

            return audioInspection;
        }

        private double[] readVideoFrameLuma(Path path, int expectedFrames)
            throws MediaGateException
        {
            int bytesPerFrame = VIDEO_WIDTH * VIDEO_HEIGHT;

            try
            {
                byte[] bytes = Files.readAllBytes(path);

                verify(bytes.length % bytesPerFrame == 0,
                    "decoded marker video has a partial raw frame");
                int frames = bytes.length / bytesPerFrame;
                verify(frames == expectedFrames,
                    "decoded marker video frame count mismatch: expected=" + expectedFrames
                        + ", actual=" + frames);
                double[] luma = new double[frames];

                for (int frame = 0; frame < frames; frame++)
                {
                    long total = 0L;
                    int offset = frame * bytesPerFrame;

                    for (int i = 0; i < bytesPerFrame; i++)
                    {
                        total += bytes[offset + i] & 0xff;
                    }

                    luma[frame] = total / (double) bytesPerFrame;
                }

                return luma;
            }
            catch (IOException e)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "failed to read decoded marker video " + path, e);
            }
        }

        private RawAudioInspection readAudioFrameRms(
            Path path, int channels, int expectedVideoFrames) throws MediaGateException
        {
            try
            {
                byte[] bytes = Files.readAllBytes(path);
                int bytesPerSampleFrame = channels * Short.BYTES;

                verify(bytes.length % bytesPerSampleFrame == 0,
                    "decoded marker audio has a partial sample frame");
                int sampleFrames = bytes.length / bytesPerSampleFrame;
                int expectedSampleFrames = expectedVideoFrames * SAMPLES_PER_VIDEO_FRAME;

                verify(sampleFrames >= expectedSampleFrames,
                    "decoded marker audio is shorter than its video range: expected="
                        + expectedSampleFrames + ", actual=" + sampleFrames);
                double[] frameRms = new double[expectedVideoFrames];
                double[] channelRms = new double[channels];

                for (int videoFrame = 0; videoFrame < expectedVideoFrames; videoFrame++)
                {
                    double sum = 0D;
                    int firstSampleFrame = videoFrame * SAMPLES_PER_VIDEO_FRAME;

                    for (int frame = firstSampleFrame;
                        frame < firstSampleFrame + SAMPLES_PER_VIDEO_FRAME; frame++)
                    {
                        for (int channel = 0; channel < channels; channel++)
                        {
                            int index = (frame * channels + channel) * Short.BYTES;
                            short packed = (short) ((bytes[index] & 0xff)
                                | (bytes[index + 1] & 0xff) << 8);
                            double sample = packed / 32_768D;

                            sum += sample * sample;
                            channelRms[channel] += sample * sample;
                        }
                    }

                    frameRms[videoFrame] = Math.sqrt(sum
                        / (SAMPLES_PER_VIDEO_FRAME * (double) channels));
                }

                for (int channel = 0; channel < channels; channel++)
                {
                    channelRms[channel] = Math.sqrt(
                        channelRms[channel] / expectedSampleFrames);
                }

                return new RawAudioInspection(frameRms, channelRms);
            }
            catch (IOException e)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "failed to read decoded marker audio " + path, e);
            }
        }

        private static int peakIndex(double[] values, int first, int last)
        {
            int peak = first;

            for (int i = first + 1; i <= last; i++)
            {
                if (values[i] > values[peak]) peak = i;
            }

            return peak;
        }

        private static List<Integer> sourceMarkerFrames(TimelineRange range)
        {
            List<Integer> markers = new ArrayList<>(OUTPUT_MARKERS.size());

            for (MarkerPoint marker : OUTPUT_MARKERS)
            {
                markers.add(range.sourceStartFrame() + marker.outputFrame());
            }

            return List.copyOf(markers);
        }

        private void recordToolVersion(Tool tool) throws MediaGateException
        {
            ProcessResult result = this.processes.run(
                tool, List.of("-version"), TOOL_VERSION_TIMEOUT);
            String version = firstNonBlankLine(result.output());

            verify(version != null
                    && version.toLowerCase(Locale.ROOT).contains(tool.name() + " version"),
                tool.name() + " did not report a recognizable version: "
                    + oneLine(result.output()));
            System.out.println("AudioMediaIntegrationTest: TOOL " + tool.name()
                + " source=" + tool.source() + " version=" + oneLine(version));
        }

        private void validateCustomExportArguments() throws MediaGateException
        {
            try
            {
                VideoExportAudioProfile.validateTemplate(CUSTOM_MUX_ARGUMENTS, true, true);
            }
            catch (IllegalArgumentException e)
            {
                throw new MediaGateException(MediaFailureKind.ARGUMENT_FAILURE,
                    "custom mono/stereo mux template was rejected", e);
            }
        }

        private Path publishMux(
            Path video, Path audio, String outputName, ChannelLayout layout) throws Exception
        {
            Path destination = this.workspace.resolve(outputName);

            return publishArtifact(destination,
                partial -> resolvedMuxArguments(video, audio, partial, layout),
                partial -> assertMp4Probe(partial, layout.channels(), layout.id()));
        }

        private List<String> resolvedMuxArguments(
            Path video, Path audio, Path output, ChannelLayout layout) throws MediaGateException
        {
            Map<String, String> replacements = new LinkedHashMap<>();

            replacements.put("%VIDEO%", video.toAbsolutePath().normalize().toString());
            replacements.put("%AUDIO_TRACK%", audio.toAbsolutePath().normalize().toString());
            replacements.put("%AUDIO_SAMPLE_RATE%", String.valueOf(
                VideoExportAudioProfile.SAMPLE_RATE));
            replacements.put("%AUDIO_CHANNELS%", String.valueOf(
                VideoExportAudioProfile.channels(layout)));
            replacements.put("%AUDIO_LAYOUT%", layout.id());
            replacements.put("%OUTPUT%", output.toAbsolutePath().normalize().toString());

            try
            {
                return VideoExportUtils.resolveArguments(CUSTOM_MUX_ARGUMENTS, replacements);
            }
            catch (IllegalArgumentException e)
            {
                throw new MediaGateException(MediaFailureKind.ARGUMENT_FAILURE,
                    "failed to resolve custom " + layout.id() + " export arguments", e);
            }
        }

        private Path publishArtifact(
            Path destination, CommandFactory commandFactory, ArtifactVerifier verifier)
            throws Exception
        {
            return publishArtifact(
                destination, commandFactory, verifier, MEDIA_PROCESS_TIMEOUT);
        }

        private Path publishArtifact(
            Path destination,
            CommandFactory commandFactory,
            ArtifactVerifier verifier,
            Duration timeout) throws Exception
        {
            destination = destination.toAbsolutePath().normalize();
            verify(destination.getParent().equals(this.workspace),
                "artifact escaped the media workspace: " + destination);

            if (Files.exists(destination))
            {
                throw new MediaGateException(MediaFailureKind.OUTPUT_EXISTS,
                    "refusing to replace media artifact " + destination);
            }

            Path partial = partialPath(destination);
            Exception primary = null;
            boolean published = false;

            try
            {
                List<String> arguments = commandFactory.create(partial);

                this.processes.run(this.ffmpeg, arguments, timeout);
                assertNonEmpty(partial);
                verifier.verify(partial);
                moveWithoutReplace(partial, destination);
                published = true;

                return destination;
            }
            catch (Exception e)
            {
                primary = e;
                throw e;
            }
            finally
            {
                if (!published)
                {
                    try
                    {
                        Files.deleteIfExists(partial);
                    }
                    catch (IOException e)
                    {
                        MediaGateException cleanup = new MediaGateException(
                            MediaFailureKind.CLEANUP_FAILURE,
                            "failed to remove unpublished artifact for " + destination, e);

                        if (primary != null)
                        {
                            primary.addSuppressed(cleanup);
                        }
                        else
                        {
                            throw cleanup;
                        }
                    }
                }
            }
        }

        private void assertWaveProbe(
            Path path, int sampleRate, int channels, String layout, long expectedFrames)
            throws MediaGateException
        {
            assertWaveProbe(path, sampleRate, channels, layout, expectedFrames, 0.002D, true);
        }

        private void assertWaveProbe(
            Path path, int sampleRate, int channels, String layout, long expectedFrames,
            double durationTolerance, boolean requireExactFrames)
            throws MediaGateException
        {
            ProbeReport report = probe(path);

            verify(report.streams().size() == 1,
                "WAV must contain exactly one stream: " + path.getFileName());
            ProbeStream audio = onlyStream(report, "audio");
            verify("pcm_s16le".equals(audio.codecName()),
                "WAV codec mismatch for " + path.getFileName() + ": " + audio.codecName());
            verify(audio.sampleRate() != null && audio.sampleRate() == sampleRate,
                "WAV sample rate mismatch for " + path.getFileName());
            verify(audio.channels() != null && audio.channels() == channels,
                "WAV channel count mismatch for " + path.getFileName());
            /*
             * ffprobe builds differ on classic PCM WAVs: some infer mono/stereo
             * from channel count, while others leave channel_layout unset. The
             * channel count above remains mandatory; when either structured
             * representation does advertise a layout, it must still agree.
             */
            assertLayoutIfPresent(path, layout, audio.channelLayout());
            verify(audio.nbReadFrames() != null && audio.nbReadFrames() > 0L,
                "WAV nb_read_frames is missing or empty for " + path.getFileName());

            double expectedDuration = expectedFrames / (double) sampleRate;
            double duration = streamDuration(audio, report);

            verify(Math.abs(duration - expectedDuration) <= durationTolerance,
                "WAV duration mismatch for " + path.getFileName()
                    + ": expected=" + expectedDuration + ", actual=" + duration);

            if (requireExactFrames)
            {
                verify(Math.round(duration * sampleRate) == expectedFrames,
                    "WAV duration does not describe the expected sample-frame count for "
                        + path.getFileName());
            }
        }

        private void assertMp4Probe(Path path, int channels, String layout)
            throws MediaGateException
        {
            ProbeReport report = probe(path);
            ProbeStream video = onlyStream(report, "video");
            ProbeStream audio = onlyStream(report, "audio");

            verify(report.streams().size() == 2,
                "MP4 must contain exactly one video and one audio stream: " + path.getFileName());
            verify("mpeg4".equals(video.codecName()),
                "MP4 video codec mismatch: " + video.codecName());
            verify("aac".equals(audio.codecName()),
                "MP4 audio codec mismatch: " + audio.codecName());
            verify(audio.profile() != null
                    && audio.profile().toLowerCase(Locale.ROOT).contains("lc"),
                "MP4 audio profile is not AAC-LC: " + audio.profile());
            verify(audio.sampleRate() != null
                    && audio.sampleRate() == VideoExportAudioProfile.SAMPLE_RATE,
                "MP4 audio sample rate is not 48 kHz");
            verify(audio.channels() != null && audio.channels() == channels,
                "MP4 audio channel count mismatch: expected=" + channels
                    + ", actual=" + audio.channels());
            verify(layout.equals(audio.channelLayout()),
                "MP4 JSON layout mismatch: expected=" + layout
                    + ", actual=" + audio.channelLayout());
            verify(layout.equals(probeCsvField(path, "channel_layout")),
                "MP4 CSV layout mismatch for " + path.getFileName());
            verify(audio.nbReadFrames() != null && audio.nbReadFrames() > 0L,
                "MP4 audio nb_read_frames is missing or empty");
            verify(video.nbReadFrames() != null
                    && Math.abs(video.nbReadFrames() - VIDEO_FRAMES) <= 1L,
                "MP4 video nb_read_frames mismatch: expected=" + VIDEO_FRAMES
                    + ", actual=" + video.nbReadFrames());

            double tolerance = 1D / VIDEO_FRAME_RATE + 0.005D;
            double expectedDuration = FIXTURE_MILLISECONDS / 1_000D;
            double videoStart = requiredStartTime(video, path);
            double audioStart = requiredStartTime(audio, path);

            verify(Math.abs(streamDuration(video, report) - expectedDuration) <= tolerance,
                "MP4 video duration is outside one output frame");
            verify(Math.abs(streamDuration(audio, report) - expectedDuration) <= tolerance,
                "MP4 audio duration is outside one output frame");
            verify(report.formatDuration() != null
                    && Math.abs(report.formatDuration() - expectedDuration) <= tolerance,
                "MP4 container duration is outside one output frame");
            verify(Math.abs(videoStart - audioStart) <= 1D / VIDEO_FRAME_RATE + 1e-6D,
                "MP4 audio/video stream starts differ by more than one output frame: video="
                    + videoStart + ", audio=" + audioStart);
        }

        private void assertStereoIdentity(Path stereoMp4) throws Exception
        {
            Path decoded = publishArtifact(
                this.workspace.resolve("decoded-stereo-identity.wav"),
                partial -> List.of(
                    "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                    "-i", stereoMp4.toString(), "-map", "0:a:0", "-c:a", "pcm_s16le",
                    "-ar", "48000", "-ac", "2", "-channel_layout", "stereo",
                    partial.toString()),
                partial -> assertWaveProbe(partial, SAMPLE_RATE_48, 2, "stereo",
                    SAMPLE_RATE_48 * FIXTURE_MILLISECONDS / 1_000,
                    1D / VIDEO_FRAME_RATE + 0.005D, false));
            WaveInspection inspection = WaveFixture.inspect(decoded, true);

            verify(inspection.channels() == 2,
                "decoded stereo identity fixture lost its channel count");
            verify(inspection.rms().length == 2,
                "decoded stereo identity fixture did not expose left/right samples");
            verify(inspection.rms()[0] > inspection.rms()[1] * 2.5D,
                "stereo channel identity was swapped, collapsed, or corrupted: left_rms="
                    + inspection.rms()[0] + ", right_rms=" + inspection.rms()[1]);
        }

        private void assertOddPadding(Path path, int channels, int sampleRate, long frames)
            throws MediaGateException
        {
            WaveInspection inspection = WaveFixture.inspect(path, false);

            verify(inspection.oddChunkPadded(),
                "handwritten WAV did not retain its odd-sized padded chunk: "
                    + path.getFileName());
            verify(inspection.channels() == channels
                    && inspection.sampleRate() == sampleRate
                    && inspection.frames() == frames,
                "handwritten WAV structure mismatch: " + path.getFileName());
        }

        private void assertNoPartialOnRejectedVerification() throws Exception
        {
            Path rejected = this.workspace.resolve("rejected-after-probe.wav");
            MediaGateException failure = null;

            try
            {
                publishArtifact(rejected,
                    partial -> List.of(
                        "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                        "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono",
                        "-t", "0.05", "-c:a", "pcm_s16le", partial.toString()),
                    partial ->
                    {
                        assertNonEmpty(partial);
                        throw new MediaGateException(MediaFailureKind.CONTRACT_MISMATCH,
                            "intentional post-process rejection");
                    });
            }
            catch (MediaGateException e)
            {
                failure = e;
            }

            verify(failure != null
                    && MediaFailureKind.CONTRACT_MISMATCH.name().equals(failure.kind()),
                "post-process rejection did not retain its typed failure");
            verify(!Files.exists(rejected),
                "rejected output was published as a successful artifact");
            assertNoPartialFiles("rejected-after-probe");
        }

        private void assertTypedNonZeroFailure() throws Exception
        {
            Path failed = this.workspace.resolve("nonzero-output.wav");
            MediaProcessException failure = null;

            try
            {
                publishArtifact(failed,
                    partial -> List.of(
                        "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                        "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono",
                        "-t", "0.05", "-c:a", "bbs_codec_does_not_exist",
                        partial.toString()),
                    this::assertNonEmpty);
            }
            catch (MediaProcessException e)
            {
                failure = e;
            }

            verify(failure != null
                    && MediaFailureKind.NON_ZERO_EXIT.name().equals(failure.kind())
                    && failure.exitCode() != null && failure.exitCode() != 0,
                "FFmpeg nonzero exit did not produce a typed failure");
            verify(!Files.exists(failed),
                "nonzero FFmpeg exit left a published artifact");
            assertNoPartialFiles("nonzero-output");
        }

        private void assertTypedTimeoutFailure() throws Exception
        {
            Path timedOut = this.workspace.resolve("intentional-timeout.wav");
            MediaProcessException failure = null;

            try
            {
                publishArtifact(
                    timedOut,
                    partial -> List.of(
                        "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                        "-re", "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono",
                        "-t", "5", "-c:a", "pcm_s16le", partial.toString()),
                    this::assertNonEmpty,
                    INTENTIONAL_TIMEOUT);
            }
            catch (MediaProcessException e)
            {
                failure = e;
            }

            long returnedAt = System.nanoTime();

            verify(failure != null
                    && failure.failureKind() == MediaFailureKind.TIMEOUT,
                "FFmpeg timeout did not produce a typed failure");
            verify(!Files.exists(timedOut),
                "timed-out FFmpeg published an output artifact");
            assertNoPartialFiles("intentional-timeout");

            List<Long> liveProcessIds = this.processes.liveOwnedProcessIds();

            verify(liveProcessIds.isEmpty(),
                "timed-out FFmpeg left owned process-tree members alive: " + liveProcessIds);

            long returnLatency = this.processes.timeoutReturnLatencyNanos(returnedAt);

            verify(returnLatency <= PROCESS_RETURN_TIMEOUT.toNanos(),
                "timed-out FFmpeg returned after "
                    + TimeUnit.NANOSECONDS.toMillis(returnLatency)
                    + " ms; cancellation/timeout return budget is "
                    + PROCESS_RETURN_TIMEOUT.toMillis() + " ms");
        }

        private void assertNoPartialFiles(String prefix) throws MediaGateException
        {
            try (var paths = Files.list(this.workspace))
            {
                verify(paths.noneMatch(path -> path.getFileName().toString()
                        .startsWith(prefix + ".partial-")),
                    "owned partial artifact survived cleanup for " + prefix);
            }
            catch (IOException e)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "failed to inspect partial artifact cleanup", e);
            }
        }

        private void assertNonEmpty(Path path) throws MediaGateException
        {
            try
            {
                verify(Files.isRegularFile(path) && Files.size(path) > 0L,
                    "media tool reported success without a non-empty artifact: " + path);
            }
            catch (IOException e)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "failed to inspect media artifact " + path, e);
            }
        }

        private ProbeReport probe(Path path) throws MediaGateException
        {
            ProcessResult result = this.processes.run(this.ffprobe, List.of(
                "-v", "error", "-count_frames",
                "-show_entries",
                "stream=index,codec_name,profile,codec_type,sample_rate,channels,"
                    + "channel_layout,start_time,duration,nb_read_frames:format=duration",
                "-of", "json", path.toAbsolutePath().normalize().toString()),
                MEDIA_PROCESS_TIMEOUT);

            return ProbeReport.parse(result.output(), path);
        }

        private String probeCsvField(Path path, String field) throws MediaGateException
        {
            String value = probeCsvFieldIfPresent(path, field);

            if (value == null)
            {
                throw new MediaGateException(MediaFailureKind.INVALID_CSV,
                    "ffprobe CSV field is empty for " + path.getFileName() + ": " + field);
            }

            return value;
        }

        private String probeCsvFieldIfPresent(Path path, String field) throws MediaGateException
        {
            ProcessResult result = this.processes.run(this.ffprobe, List.of(
                "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=" + field, "-of", "csv=p=0",
                path.toAbsolutePath().normalize().toString()), MEDIA_PROCESS_TIMEOUT);
            String line = firstNonBlankLine(result.output());

            if (line == null)
            {
                return null;
            }

            List<String> fields = Csv.parseLine(line);

            if (fields.size() != 1 || fields.get(0).isBlank())
            {
                throw new MediaGateException(MediaFailureKind.INVALID_CSV,
                    "ffprobe CSV output has an unexpected shape for " + path.getFileName()
                        + ": " + oneLine(line));
            }

            String value = fields.get(0).trim();

            return value.isEmpty() || "N/A".equalsIgnoreCase(value)
                || "unknown".equalsIgnoreCase(value) ? null : value;
        }

        private void assertLayoutIfPresent(Path path, String expected, String jsonLayout)
            throws MediaGateException
        {
            String csvLayout = probeCsvFieldIfPresent(path, "channel_layout");
            boolean jsonAdvertised = jsonLayout != null
                && !jsonLayout.isBlank() && !"N/A".equalsIgnoreCase(jsonLayout)
                && !"unknown".equalsIgnoreCase(jsonLayout);

            if (jsonAdvertised)
            {
                verify(expected.equalsIgnoreCase(jsonLayout.trim()),
                    "WAV JSON layout mismatch for " + path.getFileName()
                        + ": " + jsonLayout);
            }

            if (csvLayout != null)
            {
                verify(expected.equalsIgnoreCase(csvLayout),
                    "WAV CSV layout mismatch for " + path.getFileName()
                        + ": " + csvLayout);
            }
        }

        private ProbeStream onlyStream(ProbeReport report, String type)
            throws MediaGateException
        {
            List<ProbeStream> matches = report.streams().stream()
                .filter(stream -> type.equals(stream.codecType())).toList();

            verify(matches.size() == 1,
                "expected exactly one " + type + " stream but found " + matches.size());

            return matches.get(0);
        }

        private double streamDuration(ProbeStream stream, ProbeReport report)
            throws MediaGateException
        {
            Double duration = stream.duration() == null
                ? report.formatDuration()
                : stream.duration();

            verify(duration != null && Double.isFinite(duration) && duration > 0D,
                "ffprobe did not report a finite positive duration");

            return duration;
        }

        private double requiredStartTime(ProbeStream stream, Path source)
            throws MediaGateException
        {
            Double start = stream.startTime();

            verify(start != null && Double.isFinite(start),
                "ffprobe did not report a finite " + stream.codecType()
                    + " start_time for " + source.getFileName());

            return start;
        }

        private static void assertOption(List<String> arguments, String option, String expected)
            throws MediaGateException
        {
            String actual = null;

            for (int i = 0; i + 1 < arguments.size(); i++)
            {
                if (option.equalsIgnoreCase(arguments.get(i)))
                {
                    actual = arguments.get(i + 1);
                    break;
                }
            }

            verify(expected.equalsIgnoreCase(actual),
                "custom export option mismatch: " + option + " expected=" + expected
                    + ", actual=" + actual);
        }

        private static boolean forcesMono(List<String> arguments)
        {
            for (int i = 0; i < arguments.size(); i++)
            {
                String argument = arguments.get(i).toLowerCase(Locale.ROOT);

                if (("-ac".equals(argument) && i + 1 < arguments.size()
                    && "1".equals(arguments.get(i + 1)))
                    || "-ac1".equals(argument) || "-ac=1".equals(argument))
                {
                    return true;
                }
            }

            return false;
        }

        private static String decimalSeconds()
        {
            return String.format(Locale.ROOT, "%.3f", FIXTURE_MILLISECONDS / 1_000D);
        }
    }

    @FunctionalInterface
    private interface CommandFactory
    {
        List<String> create(Path output) throws Exception;
    }

    @FunctionalInterface
    private interface ArtifactVerifier
    {
        void verify(Path artifact) throws Exception;
    }

    private enum MuxAudioPath
    {
        DIRECT_FILM("direct-film"),
        POST_MINECRAFT_SOUND("post-minecraft-sound");

        private final String id;

        MuxAudioPath(String id)
        {
            this.id = id;
        }

        private String id()
        {
            return this.id;
        }
    }

    private record MarkerPoint(String id, int outputFrame)
    {}

    private record TimelineRange(
        String id, int sourceStartFrame, int sourceEndFrame, boolean panelLoop)
    {
        private TimelineRange
        {
            Objects.requireNonNull(id);

            if (sourceStartFrame < 0 || sourceEndFrame <= sourceStartFrame)
            {
                throw new IllegalArgumentException(
                    "invalid media fixture range " + sourceStartFrame + ".." + sourceEndFrame);
            }
        }

        private int frameCount()
        {
            return this.sourceEndFrame - this.sourceStartFrame;
        }
    }

    private record RoutedAudio(Path path, MuxAudioPath pathKind, ChannelLayout layout)
    {
        private RoutedAudio
        {
            Objects.requireNonNull(path);
            Objects.requireNonNull(pathKind);
            Objects.requireNonNull(layout);
        }
    }

    private record MuxFixture(
        String id,
        Path media,
        Path audioInput,
        MuxAudioPath pathKind,
        ChannelLayout layout,
        TimelineRange range)
    {}

    private record RawAudioInspection(double[] frameRms, double[] channelRms)
    {}

    private record ProcessResult(int exitCode, String output, long elapsedMillis)
    {}

    private static final class MediaProcessRunner
    {
        private final Path workspace;
        private final AtomicInteger sequence = new AtomicInteger();
        private final Map<Long, ProcessHandle> ownedProcesses = new LinkedHashMap<>();
        private long lastTimeoutNanos = -1L;

        private MediaProcessRunner(Path workspace)
        {
            this.workspace = workspace;
        }

        private ProcessResult run(Tool tool, List<String> arguments, Duration timeout)
            throws MediaProcessException
        {
            List<String> command = new ArrayList<>(arguments.size() + 1);

            command.add(tool.executable().toString());
            command.addAll(arguments);

            Path log = this.workspace.resolve(String.format(Locale.ROOT,
                "process-%03d.log", this.sequence.incrementAndGet()));
            Process process;
            long started = System.nanoTime();

            try
            {
                ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(this.workspace.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());

                process = builder.start();
                rememberProcess(process.toHandle());
                closeQuietly(process.getOutputStream());
            }
            catch (IOException | RuntimeException e)
            {
                throw new MediaProcessException(MediaFailureKind.START_FAILURE,
                    "failed to start " + tool.name() + " from " + tool.executable(),
                    null, readAndDeleteQuietly(log), e);
            }

            boolean interrupted = false;
            boolean terminationAttempted = false;

            try
            {
                boolean exited;
                long timeoutDeadline = System.nanoTime() + timeout.toNanos();

                try
                {
                    exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e)
                {
                    interrupted = true;
                    terminationAttempted = true;
                    this.terminate(process, tool.name());
                    throw new MediaProcessException(MediaFailureKind.INTERRUPTED,
                        tool.name() + " wait was interrupted", null,
                        readAndDeleteQuietly(log), e);
                }

                if (!exited)
                {
                    this.lastTimeoutNanos = timeoutDeadline;
                    terminationAttempted = true;
                    MediaProcessException cleanup = null;
                    String output;

                    try
                    {
                        this.terminate(process, tool.name());
                    }
                    catch (MediaProcessException e)
                    {
                        cleanup = e;
                    }

                    output = readAndDeleteQuietly(log);

                    MediaProcessException failure = new MediaProcessException(
                        MediaFailureKind.TIMEOUT,
                        tool.name() + " exceeded " + timeout.toMillis() + " ms",
                        null, output, null);

                    if (cleanup != null) failure.addSuppressed(cleanup);

                    throw failure;
                }

                int exitCode = process.exitValue();
                String output = readAndDeleteQuietly(log);

                if (exitCode != 0)
                {
                    throw new MediaProcessException(MediaFailureKind.NON_ZERO_EXIT,
                        tool.name() + " exited with code " + exitCode + ": "
                            + oneLine(output), exitCode, output, null);
                }

                return new ProcessResult(exitCode, output,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            finally
            {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());

                if (process.isAlive() && !terminationAttempted)
                {
                    try
                    {
                        this.terminate(process, tool.name());
                    }
                    catch (MediaProcessException cleanup)
                    {
                        if (!interrupted) throw cleanup;
                    }
                }

                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void terminate(Process process, String tool) throws MediaProcessException
        {
            long deadline = System.nanoTime() + PROCESS_TEARDOWN_TIMEOUT.toNanos();
            Map<Long, ProcessHandle> processTree = new LinkedHashMap<>();

            try
            {
                observeProcessTree(process, processTree);
                destroyAlive(processTree.values(), false);
                observeProcessTree(process, processTree);
                destroyAlive(processTree.values(), true);

                while (liveProcessIds(processTree).size() > 0)
                {
                    long remaining = deadline - System.nanoTime();

                    if (remaining <= 0L)
                    {
                        throw new MediaProcessException(MediaFailureKind.TERMINATION_FAILURE,
                            tool + " left owned processes alive after forced termination: "
                                + liveProcessIds(processTree), null, "", null);
                    }

                    observeProcessTree(process, processTree);
                    destroyAlive(processTree.values(), true);
                    TimeUnit.NANOSECONDS.sleep(Math.min(
                        remaining, TimeUnit.MILLISECONDS.toNanos(10L)));
                }
            }
            catch (InterruptedException e)
            {
                destroyAlive(processTree.values(), true);
                Thread.currentThread().interrupt();
                throw new MediaProcessException(MediaFailureKind.INTERRUPTED,
                    "interrupted while terminating " + tool, null, "", e);
            }
        }

        private void observeProcessTree(
            Process process, Map<Long, ProcessHandle> processTree)
        {
            try (var descendants = process.descendants())
            {
                descendants.forEach(handle ->
                {
                    processTree.putIfAbsent(handle.pid(), handle);
                    rememberProcess(handle);
                });
            }

            ProcessHandle root = process.toHandle();

            processTree.putIfAbsent(root.pid(), root);
            rememberProcess(root);
        }

        private void rememberProcess(ProcessHandle process)
        {
            this.ownedProcesses.put(process.pid(), process);
        }

        private static void destroyAlive(
            Iterable<ProcessHandle> processes, boolean forcibly)
        {
            for (ProcessHandle process : processes)
            {
                if (!process.isAlive()) continue;

                if (forcibly)
                {
                    process.destroyForcibly();
                }
                else
                {
                    process.destroy();
                }
            }
        }

        private static List<Long> liveProcessIds(Map<Long, ProcessHandle> processes)
        {
            return processes.values().stream()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .toList();
        }

        private List<Long> liveOwnedProcessIds()
        {
            return liveProcessIds(this.ownedProcesses);
        }

        private long timeoutReturnLatencyNanos(long returnedAt) throws MediaGateException
        {
            if (this.lastTimeoutNanos < 0L || returnedAt < this.lastTimeoutNanos)
            {
                throw new MediaGateException(MediaFailureKind.CONTRACT_MISMATCH,
                    "intentional FFmpeg timeout did not record a return-latency origin");
            }

            return returnedAt - this.lastTimeoutNanos;
        }

        private static String readAndDeleteQuietly(Path log)
        {
            String output = "";

            try
            {
                if (Files.isRegularFile(log))
                {
                    try (InputStream stream = Files.newInputStream(log))
                    {
                        byte[] bytes = stream.readNBytes(MAX_PROCESS_OUTPUT_BYTES + 1);
                        boolean truncated = bytes.length > MAX_PROCESS_OUTPUT_BYTES;
                        int length = Math.min(bytes.length, MAX_PROCESS_OUTPUT_BYTES);

                        output = new String(bytes, 0, length, StandardCharsets.UTF_8);
                        if (truncated) output += "\n[output truncated]";
                    }
                }
            }
            catch (IOException e)
            {
                output = "[failed to read process output: " + e.getMessage() + "]";
            }
            finally
            {
                try
                {
                    Files.deleteIfExists(log);
                }
                catch (IOException ignored)
                {}
            }

            return output;
        }

        private static void closeQuietly(AutoCloseable closeable)
        {
            try
            {
                if (closeable != null) closeable.close();
            }
            catch (Exception ignored)
            {}
        }
    }

    private record ProbeStream(
        int index,
        String codecName,
        String profile,
        String codecType,
        Integer sampleRate,
        Integer channels,
        String channelLayout,
        Double startTime,
        Double duration,
        Long nbReadFrames)
    {}

    private record ProbeReport(List<ProbeStream> streams, Double formatDuration)
    {
        private static ProbeReport parse(String json, Path source) throws MediaGateException
        {
            try
            {
                JsonElement parsed = JsonParser.parseString(json);

                if (!parsed.isJsonObject())
                {
                    throw invalid(source, "root is not an object", null);
                }

                JsonObject root = parsed.getAsJsonObject();
                JsonElement streamElement = root.get("streams");

                if (streamElement == null || !streamElement.isJsonArray())
                {
                    throw invalid(source, "streams is not an array", null);
                }

                JsonArray streamArray = streamElement.getAsJsonArray();
                List<ProbeStream> streams = new ArrayList<>(streamArray.size());

                for (JsonElement element : streamArray)
                {
                    if (!element.isJsonObject())
                    {
                        throw invalid(source, "stream entry is not an object", null);
                    }

                    JsonObject stream = element.getAsJsonObject();

                    streams.add(new ProbeStream(
                        requiredInt(stream, "index", source),
                        optionalString(stream, "codec_name"),
                        optionalString(stream, "profile"),
                        optionalString(stream, "codec_type"),
                        optionalInt(stream, "sample_rate", source),
                        optionalInt(stream, "channels", source),
                        optionalString(stream, "channel_layout"),
                        optionalDouble(stream, "start_time", source),
                        optionalDouble(stream, "duration", source),
                        optionalLong(stream, "nb_read_frames", source)));
                }

                Double formatDuration = null;
                JsonElement formatElement = root.get("format");

                if (formatElement != null && formatElement.isJsonObject())
                {
                    formatDuration = optionalDouble(
                        formatElement.getAsJsonObject(), "duration", source);
                }

                return new ProbeReport(List.copyOf(streams), formatDuration);
            }
            catch (MediaGateException e)
            {
                throw e;
            }
            catch (RuntimeException e)
            {
                throw invalid(source, "parser rejected ffprobe output", e);
            }
        }

        private static int requiredInt(JsonObject object, String field, Path source)
            throws MediaGateException
        {
            Integer value = optionalInt(object, field, source);
            if (value == null) throw invalid(source, "missing integer field " + field, null);

            return value;
        }

        private static String optionalString(JsonObject object, String field)
        {
            JsonElement element = object.get(field);

            return element == null || element.isJsonNull() ? null : element.getAsString();
        }

        private static Integer optionalInt(JsonObject object, String field, Path source)
            throws MediaGateException
        {
            String value = optionalString(object, field);
            if (value == null || "N/A".equalsIgnoreCase(value)) return null;

            try
            {
                return Integer.valueOf(value);
            }
            catch (NumberFormatException e)
            {
                throw invalid(source, "invalid integer field " + field + "=" + value, e);
            }
        }

        private static Long optionalLong(JsonObject object, String field, Path source)
            throws MediaGateException
        {
            String value = optionalString(object, field);
            if (value == null || "N/A".equalsIgnoreCase(value)) return null;

            try
            {
                return Long.valueOf(value);
            }
            catch (NumberFormatException e)
            {
                throw invalid(source, "invalid long field " + field + "=" + value, e);
            }
        }

        private static Double optionalDouble(JsonObject object, String field, Path source)
            throws MediaGateException
        {
            String value = optionalString(object, field);
            if (value == null || "N/A".equalsIgnoreCase(value)) return null;

            try
            {
                double number = Double.parseDouble(value);

                return Double.isFinite(number) ? number : null;
            }
            catch (NumberFormatException e)
            {
                throw invalid(source, "invalid decimal field " + field + "=" + value, e);
            }
        }

        private static MediaGateException invalid(Path source, String message, Throwable cause)
        {
            return new MediaGateException(MediaFailureKind.INVALID_JSON,
                "invalid ffprobe JSON for " + source.getFileName() + ": " + message, cause);
        }
    }

    private static final class Csv
    {
        private static List<String> parseLine(String line) throws MediaGateException
        {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;

            for (int i = 0; i < line.length(); i++)
            {
                char character = line.charAt(i);

                if (character == '"')
                {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"')
                    {
                        field.append('"');
                        i += 1;
                    }
                    else
                    {
                        quoted = !quoted;
                    }
                }
                else if (character == ',' && !quoted)
                {
                    fields.add(field.toString());
                    field.setLength(0);
                }
                else
                {
                    field.append(character);
                }
            }

            if (quoted)
            {
                throw new MediaGateException(MediaFailureKind.INVALID_CSV,
                    "unterminated quoted ffprobe CSV field");
            }

            fields.add(field.toString());

            return fields;
        }
    }

    private record WaveInspection(
        int sampleRate,
        int channels,
        long frames,
        boolean oddChunkPadded,
        double[] rms)
    {}

    private static final class WaveFixture
    {
        private static final byte[] ODD_CHUNK = {0x42, 0x42, 0x53};

        private static void write(
            Path path, int sampleRate, int channels, int frames, boolean oddPadding)
            throws MediaGateException
        {
            writePcm16(path, sampleRate, channels, frames, oddPadding,
                (frame, channel) ->
                {
                    if (channels == 1)
                    {
                        return sine(frame, sampleRate, 330D, 0.45D);
                    }

                    return channel == 0
                        ? sine(frame, sampleRate, 440D, 0.75D)
                        : sine(frame, sampleRate, 880D, 0.18D);
                });
        }

        private static void writeMarkers(
            Path path, int channels, int totalVideoFrames, List<Integer> markerVideoFrames)
            throws MediaGateException
        {
            int frames = Math.multiplyExact(totalVideoFrames, SAMPLES_PER_VIDEO_FRAME);

            writePcm16(path, SAMPLE_RATE_48, channels, frames, false,
                (frame, channel) ->
                {
                    if (channel != 0) return 0;

                    int videoFrame = frame / SAMPLES_PER_VIDEO_FRAME;

                    return markerVideoFrames.contains(videoFrame)
                        ? sine(frame, SAMPLE_RATE_48, 997D, 0.85D)
                        : 0;
                });
        }

        private static void writeMinecraftSound(Path path, int totalVideoFrames)
            throws MediaGateException
        {
            int frames = Math.multiplyExact(totalVideoFrames, SAMPLES_PER_VIDEO_FRAME);

            writePcm16(path, SAMPLE_RATE_48, 2, frames, false,
                (frame, channel) -> channel == 1
                    ? sine(frame, SAMPLE_RATE_48, 523.25D, 0.08D)
                    : 0);
        }

        private static void writePcm16(
            Path path,
            int sampleRate,
            int channels,
            int frames,
            boolean oddPadding,
            SampleProvider samples) throws MediaGateException
        {
            Objects.requireNonNull(samples);
            int bytesPerFrame = channels * Short.BYTES;
            long dataSize = Math.multiplyExact((long) frames, bytesPerFrame);
            int oddSize = oddPadding ? ODD_CHUNK.length : 0;
            long fileSize = 12L + 8L + 16L + (oddPadding ? 8L + oddSize + 1L : 0L)
                + 8L + dataSize;

            if (dataSize > 0xffff_ffffL || fileSize - 8L > 0xffff_ffffL)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "WAV fixture is too large for RIFF");
            }

            try (OutputStream stream = Files.newOutputStream(path,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(stream)))
            {
                writeAscii(output, "RIFF");
                writeLeInt(output, fileSize - 8L);
                writeAscii(output, "WAVE");
                writeAscii(output, "fmt ");
                writeLeInt(output, 16L);
                writeLeShort(output, 1);
                writeLeShort(output, channels);
                writeLeInt(output, sampleRate);
                writeLeInt(output, (long) sampleRate * bytesPerFrame);
                writeLeShort(output, bytesPerFrame);
                writeLeShort(output, 16);

                if (oddPadding)
                {
                    writeAscii(output, "JUNK");
                    writeLeInt(output, oddSize);
                    output.write(ODD_CHUNK);
                    output.write(0);
                }

                writeAscii(output, "data");
                writeLeInt(output, dataSize);

                for (int frame = 0; frame < frames; frame++)
                {
                    for (int channel = 0; channel < channels; channel++)
                    {
                        writeLeShort(output, samples.sample(frame, channel));
                    }
                }
            }
            catch (IOException | ArithmeticException e)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "failed to write handwritten WAV fixture " + path, e);
            }
        }

        @FunctionalInterface
        private interface SampleProvider
        {
            int sample(int frame, int channel);
        }

        private static WaveInspection inspect(Path path, boolean calculateRms)
            throws MediaGateException
        {
            try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r"))
            {
                if (!"RIFF".equals(readAscii(input, 4)))
                {
                    throw new IOException("missing RIFF signature");
                }

                long riffSize = readLeUnsignedInt(input);

                if (!"WAVE".equals(readAscii(input, 4)))
                {
                    throw new IOException("missing WAVE signature");
                }

                if (riffSize + 8L > input.length())
                {
                    throw new IOException("RIFF size exceeds file length");
                }

                int formatTag = -1;
                int channels = -1;
                int sampleRate = -1;
                int blockAlign = -1;
                int bits = -1;
                long dataOffset = -1L;
                long dataSize = -1L;
                boolean oddChunkPadded = false;

                while (input.getFilePointer() + 8L <= input.length())
                {
                    String id = readAscii(input, 4);
                    long size = readLeUnsignedInt(input);
                    long payload = input.getFilePointer();
                    long paddedEnd = payload + size + (size & 1L);

                    if (paddedEnd > input.length())
                    {
                        throw new IOException("chunk " + id + " exceeds file length");
                    }

                    if ("fmt ".equals(id))
                    {
                        if (size < 16L) throw new IOException("short fmt chunk");

                        formatTag = readLeUnsignedShort(input);
                        channels = readLeUnsignedShort(input);
                        sampleRate = Math.toIntExact(readLeUnsignedInt(input));
                        readLeUnsignedInt(input);
                        blockAlign = readLeUnsignedShort(input);
                        bits = readLeUnsignedShort(input);

                        if (formatTag == 0xfffe && size >= 40L)
                        {
                            input.seek(payload + 24L);
                            formatTag = readLeUnsignedShort(input);
                        }
                    }
                    else if ("data".equals(id))
                    {
                        dataOffset = payload;
                        dataSize = size;
                    }

                    if ((size & 1L) != 0L)
                    {
                        input.seek(payload + size);
                        int padding = input.read();

                        if (padding < 0) throw new IOException("missing odd chunk padding");
                        if ("JUNK".equals(id) && padding == 0) oddChunkPadded = true;
                    }

                    input.seek(paddedEnd);
                }

                if (formatTag != 1 || bits != 16 || channels <= 0 || sampleRate <= 0
                    || blockAlign != channels * Short.BYTES || dataOffset < 0L || dataSize < 0L
                    || dataSize % blockAlign != 0L)
                {
                    throw new IOException("unsupported or malformed PCM16 WAV");
                }

                long frames = dataSize / blockAlign;
                double[] rms = calculateRms ? new double[channels] : new double[0];

                if (calculateRms)
                {
                    input.seek(dataOffset);

                    for (long frame = 0L; frame < frames; frame++)
                    {
                        for (int channel = 0; channel < channels; channel++)
                        {
                            double sample = readLeSignedShort(input) / 32_768D;
                            rms[channel] += sample * sample;
                        }
                    }

                    for (int channel = 0; channel < channels; channel++)
                    {
                        rms[channel] = Math.sqrt(rms[channel] / Math.max(1L, frames));
                    }
                }

                return new WaveInspection(sampleRate, channels, frames, oddChunkPadded, rms);
            }
            catch (IOException | ArithmeticException e)
            {
                throw new MediaGateException(MediaFailureKind.IO_FAILURE,
                    "failed to inspect PCM16 WAV " + path, e);
            }
        }

        private static int sine(int frame, int sampleRate, double frequency, double amplitude)
        {
            return (int) Math.round(Math.sin(2D * Math.PI * frequency * frame / sampleRate)
                * amplitude * 32_767D);
        }

        private static void writeAscii(DataOutputStream output, String value) throws IOException
        {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        private static void writeLeShort(DataOutputStream output, int value) throws IOException
        {
            output.write(value & 0xff);
            output.write(value >>> 8 & 0xff);
        }

        private static void writeLeInt(DataOutputStream output, long value) throws IOException
        {
            output.write((int) value & 0xff);
            output.write((int) (value >>> 8) & 0xff);
            output.write((int) (value >>> 16) & 0xff);
            output.write((int) (value >>> 24) & 0xff);
        }

        private static String readAscii(RandomAccessFile input, int length) throws IOException
        {
            byte[] bytes = new byte[length];

            input.readFully(bytes);

            return new String(bytes, StandardCharsets.US_ASCII);
        }

        private static int readLeUnsignedShort(RandomAccessFile input) throws IOException
        {
            int low = input.read();
            int high = input.read();

            if ((low | high) < 0) throw new IOException("unexpected end of WAV");

            return low | high << 8;
        }

        private static short readLeSignedShort(RandomAccessFile input) throws IOException
        {
            return (short) readLeUnsignedShort(input);
        }

        private static long readLeUnsignedInt(RandomAccessFile input) throws IOException
        {
            long low = readLeUnsignedShort(input);
            long high = readLeUnsignedShort(input);

            return low | high << 16;
        }
    }

    private static Path partialPath(Path destination)
    {
        String name = destination.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String base = extension <= 0 ? name : name.substring(0, extension);
        String suffix = extension <= 0 ? "" : name.substring(extension);

        return destination.resolveSibling(base + ".partial-" + UUID.randomUUID() + suffix);
    }

    private static void moveWithoutReplace(Path source, Path destination) throws MediaGateException
    {
        try
        {
            /* Plain move has the required no-replace contract. ATOMIC_MOVE may
             * replace an existing target on some providers. */
            Files.move(source, destination);
        }
        catch (IOException e)
        {
            MediaFailureKind kind = Files.exists(destination)
                ? MediaFailureKind.OUTPUT_EXISTS
                : MediaFailureKind.IO_FAILURE;

            throw new MediaGateException(kind,
                "failed to publish media artifact " + destination, e);
        }
    }

    private static void assertWorkspaceBounded(Path workspace) throws MediaGateException
    {
        AtomicInteger files = new AtomicInteger();
        long[] bytes = {0L};

        try
        {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException
                {
                    int count = files.incrementAndGet();
                    bytes[0] = Math.addExact(bytes[0], attributes.size());

                    if (count > MAX_WORKSPACE_FILES || bytes[0] > MAX_WORKSPACE_BYTES)
                    {
                        throw new IOException("media workspace exceeded its file/size bound");
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException | ArithmeticException e)
        {
            throw new MediaGateException(MediaFailureKind.CONTRACT_MISMATCH,
                "media workspace was not bounded", e);
        }
    }

    private static void deleteWorkspace(Path workspace) throws IOException
    {
        if (workspace == null || !Files.exists(workspace)) return;

        Path normalized = workspace.toAbsolutePath().normalize();
        AtomicInteger visited = new AtomicInteger();

        Files.walkFileTree(normalized, new SimpleFileVisitor<>()
        {
            private void count(Path path) throws IOException
            {
                if (!path.toAbsolutePath().normalize().startsWith(normalized))
                {
                    throw new IOException("cleanup path escaped its workspace: " + path);
                }

                if (visited.incrementAndGet() > MAX_WORKSPACE_FILES + 16)
                {
                    throw new IOException("cleanup exceeded its bounded file count");
                }
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException
            {
                count(file);
                Files.deleteIfExists(file);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                throws IOException
            {
                if (failure != null) throw failure;

                count(directory);
                Files.deleteIfExists(directory);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String firstNonBlankLine(String value)
    {
        if (value == null) return null;

        for (String line : value.split("\\R"))
        {
            if (!line.isBlank()) return line.trim();
        }

        return null;
    }
}
