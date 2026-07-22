package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.film.VideoExportArtifact;
import mchorse.bbs_mod.film.VideoExportArtifacts;
import mchorse.bbs_mod.film.VideoExportRequest;
import mchorse.bbs_mod.settings.values.ui.ValueExportChannelLayout;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;
import mchorse.bbs_mod.utils.VideoExportProcess;
import mchorse.bbs_mod.utils.VideoExportUtils;
import mchorse.bbs_mod.utils.VideoMuxer;
import mchorse.bbs_mod.utils.VideoRecorder;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Negative availability gate for reserved and unimplemented audio layouts. */
public final class AudioLayoutAvailabilityTest
{
    private static final EnumSet<ChannelLayout> AVAILABLE_LAYOUTS =
        EnumSet.of(ChannelLayout.MONO, ChannelLayout.STEREO);
    private static final List<String> UNAVAILABLE_TEXT = List.of(
        "5.1", "surround_5_1", "hrtf", "binaural", "spatializer");
    private static final Pattern SELECTOR_LAYOUT = Pattern.compile(
        "setAudioLayout\\s*\\(\\s*ChannelLayout\\.([A-Z0-9_]+)\\s*\\)");
    private static final Pattern VISIBLE_SELECTOR_ORDER = Pattern.compile(
        "this\\.argumentsAudio\\s*,\\s*this\\.audio\\s*,\\s*"
            + "UI\\.label\\(UIKeys\\.VIDEO_SETTINGS_AUDIO_CHANNELS\\)"
            + "\\.marginTop\\(6\\)\\s*,\\s*this\\.audioLayout\\s*,\\s*"
            + "this\\.openFolderAfterExport",
        Pattern.DOTALL);
    private static final Pattern MAIN_AUDIO_SETTING_ORDER = Pattern.compile(
        "videoExportAudio\\s*=.*?videoAudioLayout\\s*=.*?"
            + "builder\\.register\\(videoAudioLayout\\).*?"
            + "videoExportMinecraftSounds\\s*=",
        Pattern.DOTALL);

    private AudioLayoutAvailabilityTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("AudioLayoutAvailabilityTest: unavailable layout gates passed");
    }

    public static void runAll() throws Exception
    {
        Path project = findProjectRoot();

        onlyMonoAndStereoAreAvailable(project);
        selectorIsVisibleAdjacentAndLocalized(project);
        legacyPersistenceFallsBackToMono();
        encoderArgumentsOnlyAdvertiseAvailableLayouts();
        explicitSurroundRequestsFailBeforeProduction();
        generatedWaveMetadataOnlyAdvertisesAvailableLayouts();
        resourcesDoNotAdvertiseUnavailableLayouts(project);
    }

    private static void onlyMonoAndStereoAreAvailable(Path project) throws IOException
    {
        EnumSet<ChannelLayout> exportable = EnumSet.noneOf(ChannelLayout.class);

        for (ChannelLayout layout : ChannelLayout.values())
        {
            if (layout.supported()) exportable.add(layout);
        }

        check(exportable.equals(AVAILABLE_LAYOUTS),
            "exportable layouts changed: " + exportable);
        check(ChannelLayout.SURROUND_5_1.channels() == 6
                && !ChannelLayout.SURROUND_5_1.supported(),
            "the reserved 5.1 identifier became exportable");
        check(ChannelLayout.fromId("5.1") == ChannelLayout.SURROUND_5_1,
            "the legacy 5.1 identifier is no longer recognized for migration");

        for (String unavailable : List.of("hrtf", "binaural", "spatializer"))
        {
            check(ChannelLayout.fromId(unavailable) == null,
                "unimplemented layout was parsed as selectable: " + unavailable);
        }

        Path selector = project.resolve(
            "src/client/java/mchorse/bbs_mod/settings/ui/UIVideoSettingsOverlayPanel.java");
        String source = Files.readString(requireFile(selector), StandardCharsets.UTF_8);
        Matcher matcher = SELECTOR_LAYOUT.matcher(source);
        EnumSet<ChannelLayout> selectable = EnumSet.noneOf(ChannelLayout.class);

        while (matcher.find())
        {
            selectable.add(ChannelLayout.valueOf(matcher.group(1)));
        }

        check(selectable.equals(AVAILABLE_LAYOUTS),
            "video settings selector changed its layout set: " + selectable);
        assertUnavailableTextAbsent(source, "video settings selector");
    }

    private static void selectorIsVisibleAdjacentAndLocalized(Path project) throws IOException
    {
        Path selector = project.resolve(
            "src/client/java/mchorse/bbs_mod/settings/ui/UIVideoSettingsOverlayPanel.java");
        String source = Files.readString(requireFile(selector), StandardCharsets.UTF_8);

        check(VISIBLE_SELECTOR_ORDER.matcher(source).find(),
            "audio channel selector is not immediately below Export audio");
        check(source.contains(
                "this.audioLayout = UI.row(this.audioMono, this.audioStereo);"),
            "audio channel selector does not expose Mono and Stereo side by side");
        check(source.contains(
                "new UIButton(UIKeys.VIDEO_SETTINGS_AUDIO_CHANNELS_MONO")
                && source.contains(
                "new UIButton(UIKeys.VIDEO_SETTINGS_AUDIO_CHANNELS_STEREO"),
            "audio channel selector does not expose both visible choices");

        Path mainSettings = project.resolve("src/main/java/mchorse/bbs_mod/BBSSettings.java");
        String mainSource = Files.readString(requireFile(mainSettings), StandardCharsets.UTF_8);
        check(MAIN_AUDIO_SETTING_ORDER.matcher(mainSource).find(),
            "main video settings do not place audio channels below Export audio");
        assertUnavailableTextAbsent(mainSource, "main video settings");

        Path settingsMap = project.resolve(
            "src/client/java/mchorse/bbs_mod/settings/ui/UIValueMap.java");
        String mapSource = Files.readString(requireFile(settingsMap), StandardCharsets.UTF_8);
        check(mapSource.contains("register(ValueExportChannelLayout.class")
                && mapSource.contains(
                "button.addLabel(UIKeys.VIDEO_SETTINGS_AUDIO_CHANNELS_MONO)")
                && mapSource.contains(
                "button.addLabel(UIKeys.VIDEO_SETTINGS_AUDIO_CHANNELS_STEREO)"),
            "main settings do not register a visible Mono/Stereo channel switch");
        assertUnavailableTextAbsent(mapSource, "main settings value map");

        Path strings = project.resolve("src/client/resources/assets/bbs/assets/strings");

        assertTranslations(strings.resolve("en_us.json"), Map.of(
            "bbs.ui.video_settings.audio_channels", "Audio channels",
            "bbs.ui.video_settings.audio_channels.mono", "Mono",
            "bbs.ui.video_settings.audio_channels.stereo", "Stereo"));
        assertTranslations(strings.resolve("en_us.json"), Map.of(
            "bbs.config.video.audio_channel_layout", "Audio channels",
            "bbs.config.video.audio_channel_layout-comment",
                "Choose whether exported audio uses one channel (Mono) or two channels (Stereo)."));
        assertTranslations(strings.resolve("zh_cn.json"), Map.of(
            "bbs.ui.video_settings.audio_channels", "\u97f3\u9891\u58f0\u9053",
            "bbs.ui.video_settings.audio_channels.mono", "\u5355\u58f0\u9053",
            "bbs.ui.video_settings.audio_channels.stereo", "\u7acb\u4f53\u58f0",
            "bbs.config.video.audio_channel_layout", "\u97f3\u9891\u58f0\u9053",
            "bbs.config.video.audio_channel_layout-comment",
                "\u9009\u62e9\u5bfc\u51fa\u97f3\u9891\u4f7f\u7528\u5355\u58f0\u9053"
                    + "\u6216\u7acb\u4f53\u58f0\u3002"));
    }

    private static void assertTranslations(Path path, Map<String, String> expected)
        throws IOException
    {
        String resource = Files.readString(requireFile(path), StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : expected.entrySet())
        {
            String property = "\"" + entry.getKey() + "\"";
            String value = "\"" + entry.getValue() + "\"";

            check(resource.contains(property) && resource.contains(value),
                path.getFileName() + " is missing translation " + entry.getKey());
        }
    }

    private static void legacyPersistenceFallsBackToMono()
    {
        ValueExportChannelLayout missing = new ValueExportChannelLayout("layout");

        assertPersistedLayout(missing, ChannelLayout.MONO, "missing legacy layout");

        for (String value : List.of("", "unknown", "5.1", "SURROUND_5_1",
            "hrtf", "binaural", "spatializer"))
        {
            ValueExportChannelLayout persisted = new ValueExportChannelLayout("layout");

            persisted.fromData(new StringType(value));
            assertPersistedLayout(persisted, ChannelLayout.MONO,
                "legacy layout '" + value + "'");
        }

        for (ChannelLayout layout : AVAILABLE_LAYOUTS)
        {
            ValueExportChannelLayout persisted = new ValueExportChannelLayout("layout");

            persisted.fromData(new StringType(layout.id()));
            assertPersistedLayout(persisted, layout,
                "available layout '" + layout.id() + "'");
        }
    }

    private static void assertPersistedLayout(ValueExportChannelLayout value,
                                              ChannelLayout expected, String description)
    {
        check(value.getResolved() == expected,
            description + " resolved to " + value.getResolved());
        check(expected.id().equals(value.get()),
            description + " retained an unavailable persisted identifier: " + value.get());
        check(expected.id().equals(value.toData().asString()),
            description + " serialized an unavailable identifier");
        assertUnavailableTextAbsent(value.toData().asString(), description + " serialization");
    }

    private static void encoderArgumentsOnlyAdvertiseAvailableLayouts() throws Exception
    {
        for (ChannelLayout layout : AVAILABLE_LAYOUTS)
        {
            assertEncoderArguments(layout, VideoExportAudioProfile.DEFAULT_DIRECT_ARGUMENTS, false);
            assertEncoderArguments(layout, VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS, true);
        }

        expect(IllegalArgumentException.class, () -> encoderArguments(
            ChannelLayout.SURROUND_5_1, VideoExportAudioProfile.DEFAULT_DIRECT_ARGUMENTS, false));
        expect(IllegalArgumentException.class, () -> encoderArguments(
            ChannelLayout.SURROUND_5_1, VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS, true));

        for (String persisted : List.of("5.1", "hrtf", "binaural", "spatializer"))
        {
            ChannelLayout fallback = ChannelLayout.normalizeExport(persisted);
            List<String> arguments = encoderArguments(fallback,
                VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS, true);

            check(fallback == ChannelLayout.MONO,
                "legacy unavailable layout did not fall back before argument generation");
            check("1".equals(optionValue(arguments, "-ac"))
                    && "mono".equals(optionValue(arguments, "-channel_layout")),
                "legacy unavailable layout leaked into encoder arguments: " + persisted);
            assertUnavailableTextAbsent(String.join(" ", arguments),
                "normalized encoder arguments for " + persisted);
        }
    }

    private static void assertEncoderArguments(ChannelLayout layout, String template,
                                               boolean mux) throws Exception
    {
        List<String> arguments = encoderArguments(layout, template, mux);

        check(String.valueOf(layout.channels()).equals(optionValue(arguments, "-ac")),
            "encoder channel count disagrees with " + layout);
        check(layout.id().equals(optionValue(arguments, "-channel_layout")),
            "encoder channel layout disagrees with " + layout);
        check(String.valueOf(VideoExportAudioProfile.SAMPLE_RATE).equals(
                optionValue(arguments, "-ar")),
            "encoder sample rate disagrees with the export profile");
        check(arguments.stream().noneMatch(argument -> argument.contains("%AUDIO_")),
            "encoder retained an unresolved audio placeholder");
        assertUnavailableTextAbsent(String.join(" ", arguments),
            "encoder arguments for " + layout);
    }

    private static List<String> encoderArguments(ChannelLayout layout, String template,
                                                 boolean mux)
    {
        int channels = VideoExportAudioProfile.channels(layout);

        VideoExportAudioProfile.validateTemplate(template, true, mux);

        Map<String, String> replacements = new LinkedHashMap<>();

        replacements.put("%VIDEO%", "fixture-video.mp4");
        replacements.put("%AUDIO_TRACK%", "fixture-audio.wav");
        replacements.put("%OUTPUT%", "fixture-output.mp4");
        replacements.put("%NAME%", "fixture-output");
        replacements.put("%AUDIO_SAMPLE_RATE%",
            String.valueOf(VideoExportAudioProfile.SAMPLE_RATE));
        replacements.put("%AUDIO_CHANNELS%", String.valueOf(channels));
        replacements.put("%AUDIO_LAYOUT%", layout.id());
        replacements.put("%WIDTH%", "16");
        replacements.put("%HEIGHT%", "16");
        replacements.put("%FPS%", "24");
        replacements.put("%FILTERS%", "vflip");

        return VideoExportUtils.resolveArguments(template, replacements);
    }

    private static void explicitSurroundRequestsFailBeforeProduction() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-layout-availability-");

        try
        {
            VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root, "layout-gate");

            for (ChannelLayout layout : AVAILABLE_LAYOUTS)
            {
                VideoExportRequest request = request(artifacts, layout);
                VideoExportArtifact descriptor = artifacts.describe(
                    request, true, 24L, 48_000L, false);

                check(request.layout() == layout
                        && descriptor.requestedLayout() == layout
                        && descriptor.deliveredLayout() == layout,
                    "available layout did not survive the request/descriptor boundary: " + layout);
                assertUnavailableTextAbsent(descriptor.toString(),
                    "export descriptor for " + layout);
            }

            for (String persisted : List.of("5.1", "hrtf", "binaural", "spatializer"))
            {
                VideoExportRequest request = request(
                    artifacts, ChannelLayout.normalizeExport(persisted));
                VideoExportArtifact descriptor = artifacts.describe(
                    request, true, 24L, 48_000L, false);

                check(request.layout() == ChannelLayout.MONO
                        && descriptor.requestedLayout() == ChannelLayout.MONO
                        && descriptor.deliveredLayout() == ChannelLayout.MONO,
                    "legacy unavailable layout leaked into the request/descriptor: " + persisted);
                assertUnavailableTextAbsent(descriptor.toString(),
                    "normalized export descriptor for " + persisted);
            }

            expect(IllegalArgumentException.class,
                () -> request(artifacts, ChannelLayout.SURROUND_5_1));
            expect(IllegalArgumentException.class,
                () -> new PcmFormat(PcmEncoding.PCM_S16_LE,
                    ChannelLayout.SURROUND_5_1, VideoExportAudioProfile.SAMPLE_RATE));
            expect(IllegalArgumentException.class,
                () -> VideoExportAudioProfile.channels(ChannelLayout.SURROUND_5_1));

            Path rendererOutput = root.resolve("reserved-render.wav");
            AudioRenderResult renderer = AudioRenderer.renderAudioResult(
                rendererOutput.toFile(), List.of(), 20,
                VideoExportAudioProfile.SAMPLE_RATE, 0F, 1F,
                ChannelLayout.SURROUND_5_1, () -> false, (completed, total) -> {});

            check(renderer.status() == AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                "renderer accepted an explicit 5.1 request");
            check(!Files.exists(rendererOutput),
                "renderer created media for an explicit 5.1 request");

            Path mixerOutput = root.resolve("reserved-mix.wav");
            AudioRenderResult mixer = MinecraftSoundMixer.mixToFileResult(
                mixerOutput.toFile(), List.of(), List.of(), null, Map.of(),
                VideoExportAudioProfile.SAMPLE_RATE, 20D, 20,
                ChannelLayout.SURROUND_5_1, () -> false, (completed, total) -> {});

            check(mixer.status() == AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                "mixer accepted an explicit 5.1 request");
            check(!Files.exists(mixerOutput),
                "mixer created media for an explicit 5.1 request");

            Path video = Files.write(root.resolve("video.mp4"), new byte[] {1});
            Path audio = Files.write(root.resolve("audio.wav"), new byte[] {1});
            Path muxOutput = root.resolve("reserved-mux.mp4");
            VideoMuxer.MuxResult mux = VideoMuxer.mux(video.toFile(), audio.toFile(),
                muxOutput, null, ChannelLayout.SURROUND_5_1,
                VideoMuxer.DEFAULT_ARGUMENTS, () -> false, false);

            check(mux.status() == VideoMuxer.Status.PREPARATION_FAILED,
                "mux accepted an explicit 5.1 request");
            assertUnsupportedLayoutFailure(mux.cause(), "mux");
            check(!Files.exists(muxOutput),
                "mux created media for an explicit 5.1 request");

            Path recorderOutput = root.resolve("reserved-recorder.mp4");
            VideoRecorder recorder = new VideoRecorder();
            boolean started = recorder.tryStartRecording("reserved-layout", audio.toFile(),
                recorderOutput.toFile(), null, ChannelLayout.SURROUND_5_1, 0, 1, 1);

            check(!started && recorder.getOutcome() == VideoExportProcess.Outcome.FAILED,
                "recorder accepted an explicit 5.1 request");
            assertUnsupportedLayoutFailure(recorder.getFailure(), "recorder");
            check(!recorder.didStartOutputProducer(),
                "recorder reached the encoder for an explicit 5.1 request");
            check(!Files.exists(recorderOutput),
                "recorder created media for an explicit 5.1 request");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static VideoExportRequest request(VideoExportArtifacts artifacts,
                                              ChannelLayout layout)
    {
        return new VideoExportRequest(artifacts.sessionId(), 1L, 0D, 1D, false,
            24D, VideoExportAudioProfile.SAMPLE_RATE, layout,
            true, false, artifacts);
    }

    private static void generatedWaveMetadataOnlyAdvertisesAvailableLayouts() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-layout-wave-");

        try
        {
            for (ChannelLayout layout : AVAILABLE_LAYOUTS)
            {
                PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE, layout,
                    VideoExportAudioProfile.SAMPLE_RATE);
                Wave wave = new Wave(format, new byte[format.bytesPerFrame() * 2]);
                Path output = root.resolve(layout.id() + ".wav");

                WaveWriter.write(output.toFile(), wave);

                byte[] bytes = Files.readAllBytes(output);
                Wave decoded;

                try (var input = Files.newInputStream(output))
                {
                    decoded = new WaveReader().read(input);
                }

                check(decoded.getFormat().layout() == layout,
                    "generated WAV changed its available layout: " + layout);
                check(unsignedShortLittleEndian(bytes, 22) == layout.channels(),
                    "generated WAV header advertised the wrong channel count: " + layout);
                check(unsignedShortLittleEndian(bytes, 22) != 6,
                    "generated WAV header advertised 5.1");
                assertUnavailableTextAbsent(new String(bytes, StandardCharsets.ISO_8859_1),
                    "generated WAV for " + layout);
            }
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void resourcesDoNotAdvertiseUnavailableLayouts(Path project)
        throws IOException
    {
        Path directory = project.resolve("src/client/resources/assets/bbs/assets/strings");

        if (!Files.isDirectory(directory))
        {
            throw new IOException("Core translation resources are missing: " + directory);
        }

        try (var paths = Files.walk(directory))
        {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(AudioLayoutAvailabilityTest::isTextResource).toList())
            {
                assertUnavailableTextAbsent(
                    Files.readString(path, StandardCharsets.UTF_8),
                    "resource " + project.relativize(path));
            }
        }
    }

    private static boolean isTextResource(Path path)
    {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        return name.endsWith(".json") || name.endsWith(".toml")
            || name.endsWith(".xml") || name.endsWith(".properties")
            || name.endsWith(".txt") || name.endsWith(".lang")
            || name.endsWith(".yml") || name.endsWith(".yaml")
            || name.endsWith(".mcmeta");
    }

    private static Path findProjectRoot() throws IOException
    {
        List<Path> seeds = new ArrayList<>();

        seeds.add(Path.of("").toAbsolutePath().normalize());

        try
        {
            seeds.add(Path.of(AudioLayoutAvailabilityTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize());
        }
        catch (URISyntaxException | RuntimeException ignored)
        {}

        for (Path seed : seeds)
        {
            Path candidate = Files.isRegularFile(seed) ? seed.getParent() : seed;

            while (candidate != null)
            {
                if (Files.isDirectory(candidate.resolve("src"))
                    && Files.isRegularFile(candidate.resolve("build.gradle")))
                {
                    return candidate;
                }

                candidate = candidate.getParent();
            }
        }

        throw new IOException("Could not locate the new project root for layout source gates");
    }

    private static Path requireFile(Path path) throws IOException
    {
        if (!Files.isRegularFile(path))
        {
            throw new IOException("Required layout contract source is missing: " + path);
        }

        return path;
    }

    private static String optionValue(List<String> arguments, String option)
    {
        for (int index = 0; index + 1 < arguments.size(); index++)
        {
            if (option.equalsIgnoreCase(arguments.get(index)))
            {
                return arguments.get(index + 1);
            }
        }

        return null;
    }

    private static int unsignedShortLittleEndian(byte[] bytes, int offset)
    {
        check(offset >= 0 && offset + 1 < bytes.length,
            "generated WAV is too short for its channel header");

        return Byte.toUnsignedInt(bytes[offset])
            | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static void assertUnsupportedLayoutFailure(Throwable failure, String owner)
    {
        check(failure instanceof IllegalArgumentException,
            owner + " lost its typed unsupported-layout failure");
        check(failure.getMessage() != null
                && failure.getMessage().contains(ChannelLayout.SURROUND_5_1.name()),
            owner + " lost the rejected layout identity");
    }

    private static void assertUnavailableTextAbsent(String value, String owner)
    {
        String normalized = value.toLowerCase(Locale.ROOT);

        for (String unavailable : UNAVAILABLE_TEXT)
        {
            check(!normalized.contains(unavailable),
                owner + " advertises unavailable audio mode '" + unavailable + "'");
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;

        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
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

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }
}
