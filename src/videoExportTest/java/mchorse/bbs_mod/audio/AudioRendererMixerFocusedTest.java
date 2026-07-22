package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.MinecraftSoundCapture.CapturedSound;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.ListenerFrame;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.LoopFrame;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.film.VideoExportAudioNormalizer;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Executable regressions for the film renderer and captured-sound block mixer. */
public final class AudioRendererMixerFocusedTest
{
    private AudioRendererMixerFocusedTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("AudioRendererMixerFocusedTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        exactFrameCountsAtCommonRates();
        filmRoutingAndInterpolation();
        nonCoreRoutingMatchesTheCoreMatrix();
        filmWindowStartUsesRequestedTimeline();
        cursorStartsAtFractionalOutputFrame();
        cursorPitchPhaseSurvivesBlockBoundaryAndLoopWrap();
        minecraftCursorRoutingPreservesSourceIdentity();
        masterLimiterAndQuantizerRunAfterAccumulation();
        rendererAndMixerShareTheBlockBoundary();
        resourceSnapshotIsDetachedAndFailuresAreTyped();
        mixerInputsAreDetachedAndSnapshotCancellationWins();
        progressIsBoundedAndCancellationCleansOwnedTemporary();
        existingOutputIsNeverReplaced();
    }

    private static void nonCoreRoutingMatchesTheCoreMatrix() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-routing-adapters-");

        try
        {
            Wave mono = pcm16Wave(ChannelLayout.MONO, 8000, 0.5D);
            Path monoSource = root.resolve("mono-source.wav");
            Path normalizedStereo = root.resolve("normalized-stereo.wav");

            WaveWriter.write(monoSource.toFile(), mono);
            VideoExportAudioNormalizer.normalize(monoSource, normalizedStereo,
                ChannelLayout.STEREO, 8000, 1L, () -> false);

            Wave convertedStereo = mono.convertLayout(ChannelLayout.STEREO);
            Wave streamedStereo = readWave(normalizedStereo);
            double expectedCenter = sample(mono, 0, 0) * Math.sqrt(0.5D);

            assertNear(sample(mono, 0, 0), sample(convertedStereo, 0, 0), 0.00004D,
                "Wave file conversion must duplicate mono at unity");
            assertNear(sample(mono, 0, 0), sample(convertedStereo, 0, 1), 0.00004D,
                "Wave file conversion must duplicate mono to both channels");
            assertNear(expectedCenter, sample(streamedStereo, 0, 0), 0.00004D,
                "video normalizer left channel is not equal-power centered");
            assertNear(expectedCenter, sample(streamedStereo, 0, 1), 0.00004D,
                "video normalizer right channel is not equal-power centered");

            Wave stereo = pcm16Wave(ChannelLayout.STEREO, 8000, 0.75D, -0.25D);
            Path stereoSource = root.resolve("stereo-source.wav");
            Path normalizedMono = root.resolve("normalized-mono.wav");

            WaveWriter.write(stereoSource.toFile(), stereo);
            VideoExportAudioNormalizer.normalize(stereoSource, normalizedMono,
                ChannelLayout.MONO, 8000, 1L, () -> false);

            Wave convertedMono = stereo.convertLayout(ChannelLayout.MONO);
            Wave streamedMono = readWave(normalizedMono);
            double expectedDownmix = (sample(stereo, 0, 0) + sample(stereo, 0, 1)) * 0.5D;

            check(Arrays.equals(convertedMono.data, streamedMono.data),
                "Wave and video normalizer disagree on stereo-to-mono routing");
            assertNear(expectedDownmix, sample(streamedMono, 0, 0), 0.00004D,
                "video normalizer did not retain the equal-weight stereo downmix");

            Wave interpolation = pcm16Wave(ChannelLayout.MONO, 8000,
                0D, 1D / Short.MAX_VALUE);
            Path interpolationSource = root.resolve("interpolation-source.wav");
            Path interpolationOutput = root.resolve("interpolation-output.wav");

            WaveWriter.write(interpolationSource.toFile(), interpolation);
            VideoExportAudioNormalizer.normalize(interpolationSource, interpolationOutput,
                ChannelLayout.STEREO, 16000, 3L, () -> false);

            Wave interpolated = readWave(interpolationOutput);
            check(sample(interpolated, 1, 0) == 0D && sample(interpolated, 1, 1) == 0D,
                "video normalizer quantized before the final routed output boundary");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void exactFrameCountsAtCommonRates() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-frame-count-");

        try
        {
            for (int sampleRate : new int[] {44100, 48000})
            {
                Path output = root.resolve("rate-" + sampleRate + ".wav");
                long expectedFrames = (long) Math.ceil(3D / 24D * sampleRate);
                AudioRenderResult result = renderFilm(output,
                    constantWave(ChannelLayout.MONO, sampleRate, 1, 0.25F),
                    sampleRate, 24D, 3, ChannelLayout.MONO,
                    () -> false, (completed, total) -> {});

                check(result.success(), sampleRate + " Hz render failed: " + result.message());
                check(result.frames() == expectedFrames,
                    sampleRate + " Hz result reported the wrong frame count");

                Wave rendered = readWave(output);

                check(rendered.getFrameCount() == expectedFrames,
                    sampleRate + " Hz WAV contains the wrong frame count");
                check(rendered.data.length == expectedFrames * 2L,
                    sampleRate + " Hz WAV data length is not frame aligned");
            }

            Path exactBoundary = root.resolve("exact-rational-boundary.wav");
            AudioRenderResult exact = renderFilm(exactBoundary,
                constantWave(ChannelLayout.MONO, 44100, 97020, 0.125F),
                44100, 5D, 11, ChannelLayout.MONO,
                () -> false, (completed, total) -> {});

            check(exact.success() && exact.frames() == 97020L,
                "exact rational duration gained a floating-point boundary frame");
            check(readWave(exactBoundary).getFrameCount() == 97020L,
                "exact rational WAV gained a floating-point boundary frame");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void filmRoutingAndInterpolation() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-routing-");

        try
        {
            Path monoToStereo = root.resolve("mono-to-stereo.wav");
            AudioRenderResult centered = renderFilm(monoToStereo,
                monoWave(4, 1F), 4, 4D, 1, ChannelLayout.STEREO,
                () -> false, (completed, total) -> {});
            Wave centeredWave = readWave(monoToStereo);

            check(centered.success(), "mono-to-stereo render failed");
            assertNear(Math.sqrt(0.5D), sample(centeredWave, 0, 0), 0.0002D,
                "mono center left gain");
            assertNear(Math.sqrt(0.5D), sample(centeredWave, 0, 1), 0.0002D,
                "mono center right gain");

            Wave stereoImpulse = stereoWave(4, 1F, -1F);
            Path stereoOutput = root.resolve("stereo-preserved.wav");
            Path monoOutput = root.resolve("stereo-downmix.wav");

            check(renderFilm(stereoOutput, stereoImpulse, 4, 4D, 1,
                ChannelLayout.STEREO, () -> false, (completed, total) -> {}).success(),
                "stereo-to-stereo render failed");
            check(renderFilm(monoOutput, stereoImpulse, 4, 4D, 1,
                ChannelLayout.MONO, () -> false, (completed, total) -> {}).success(),
                "stereo-to-mono render failed");

            Wave preserved = readWave(stereoOutput);
            Wave downmixed = readWave(monoOutput);

            assertNear(1D, sample(preserved, 0, 0), 0.0001D,
                "stereo left impulse identity");
            assertNear(-1D, sample(preserved, 0, 1), 0.0001D,
                "stereo right impulse identity");
            assertNear(0D, sample(downmixed, 0, 0), 0.0001D,
                "equal-weight stereo downmix");

            Path resampledOutput = root.resolve("stereo-resampled.wav");
            Wave resampleSource = stereoWave(2,
                1F, 0F,
                0F, 1F);

            check(renderFilm(resampledOutput, resampleSource, 4, 4D, 4,
                ChannelLayout.STEREO, () -> false, (completed, total) -> {}).success(),
                "stereo resample render failed");

            Wave resampled = readWave(resampledOutput);
            double[][] expected = {
                {1D, 0D},
                {0.5D, 0.5D},
                {0D, 1D},
                {0D, 1D}
            };

            check(resampled.getFrameCount() == expected.length,
                "resampler changed the requested output duration");
            for (int frame = 0; frame < expected.length; frame++)
            {
                assertNear(expected[frame][0], sample(resampled, frame, 0), 0.0001D,
                    "resampled left frame " + frame);
                assertNear(expected[frame][1], sample(resampled, frame, 1), 0.0001D,
                    "resampled right frame " + frame);
            }
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void filmWindowStartUsesRequestedTimeline() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-renderer-window-");

        try
        {
            Path output = root.resolve("window.wav");
            AudioRenderer.PreparedFilmSource source = new AudioRenderer.PreparedFilmSource(
                monoWave(4, 0F, 0.25F, 0.5F, 0.75F), 1D, 0.25D, 1D, 1F);
            AudioRenderResult result = renderPrepared(output, List.of(source), 4,
                1.25D, 1.75D, ChannelLayout.MONO,
                () -> false, (completed, total) -> {});
            Wave rendered = readWave(output);

            check(result.success(), "prepared window render failed: " + result.message());
            check(result.frames() == 2L && rendered.getFrameCount() == 2L,
                "prepared window did not preserve its half-open duration");
            assertNear(0.5D, sample(rendered, 0, 0), 0.0001D,
                "window first sample did not include range start and source offset");
            assertNear(0.75D, sample(rendered, 1, 0), 0.0001D,
                "window second sample did not advance from the requested range start");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void cursorStartsAtFractionalOutputFrame() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-fractional-start-");

        try
        {
            int outputRate = 44100;
            double frameRate = 24D;
            double timelineStart = outputRate / frameRate;
            long firstOutput = (long) Math.ceil(timelineStart);
            ResourceLocation location = resource("fractional_start");
            CapturedSound sound = new CapturedSound(location, 1, 0D, 0D, 0D,
                true, false, 1F, 1F, 0F, false);
            Path output = root.resolve("fractional-start.wav");
            AudioRenderResult result = renderCaptured(output, List.of(sound), List.of(),
                Map.of(location, monoWave(outputRate, 0F, 1F, 0F)), outputRate,
                frameRate, 2, ChannelLayout.MONO,
                () -> false, (completed, total) -> {});
            Wave rendered = readWave(output);

            check(result.success(), "fractional captured-sound render failed: " + result.message());
            assertNear(0D, sample(rendered, firstOutput - 1L, 0), 0.000001D,
                "event became audible before its half-open start");
            assertNear(0.5D, sample(rendered, firstOutput, 0), 0.0001D,
                "44.1 kHz/24 fps cursor discarded its half-frame source phase");
            assertNear(0.5D, sample(rendered, firstOutput + 1L, 0), 0.0001D,
                "fractional cursor did not linearly interpolate the following sample");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void cursorPitchPhaseSurvivesBlockBoundaryAndLoopWrap() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-pitch-phase-");

        try
        {
            int outputRate = 44100;
            double frameRate = 24D;
            int totalFrames = 5;
            float[] pitches = {0.5F, 1.25F, 0.75F, 1.5F, 0.625F};
            float[] sourceSamples = {0F, 0.25F, 0.5F, 0.75F};
            ResourceLocation location = resource("continuous_pitch_loop");
            CapturedSound sound = loopingSound(location, pitches);
            List<Long> progress = new ArrayList<>();
            Path output = root.resolve("continuous-pitch-loop.wav");
            AudioRenderResult result = renderCaptured(output, List.of(sound), List.of(),
                Map.of(location, monoWave(outputRate, sourceSamples)), outputRate,
                frameRate, totalFrames, ChannelLayout.MONO,
                () -> false, (completed, total) -> progress.add(completed));
            Wave rendered = readWave(output);
            int renderedFrames = Math.toIntExact(rendered.getFrameCount());

            check(result.success(), "continuous pitch render failed: " + result.message());
            check(progress.size() > 1,
                "long pitch fixture did not cross a production block boundary");
            assertMonotonicBounded(progress, renderedFrames);

            double phase = 0D;
            for (int outputFrame = 0; outputFrame < renderedFrames; outputFrame++)
            {
                assertNear(loopSample(sourceSamples, phase),
                    sample(rendered, outputFrame, 0), 0.0001D,
                    "pitch phase or loop interpolation drifted at output " + outputFrame);

                int timelineFrame = (int) Math.floor(outputFrame * frameRate / outputRate);
                phase += pitches[Math.min(timelineFrame, pitches.length - 1)];
            }
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void minecraftCursorRoutingPreservesSourceIdentity() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-cursor-routing-");

        try
        {
            ListenerFrame listener = new ListenerFrame(0D, 0D, 0D, 1D, 0D, 0D);
            ResourceLocation monoLocation = resource("positioned_mono");
            CapturedSound positionedMono = new CapturedSound(monoLocation, 0,
                1D, 0D, 0D, false, false, 1F, 1F, 16F, false);
            Path monoOutput = root.resolve("positioned-mono.wav");
            AudioRenderResult monoResult = renderCaptured(monoOutput,
                List.of(positionedMono), List.of(listener),
                Map.of(monoLocation, monoWave(1, 1F)),
                1, 1D, 1, ChannelLayout.STEREO,
                () -> false, (completed, total) -> {});
            Wave panned = readWave(monoOutput);

            check(monoResult.success(), "positioned mono render failed");
            assertNear(0D, sample(panned, 0, 0), 0.0001D,
                "right-positioned mono sound leaked into left channel");
            assertNear(1D, sample(panned, 0, 1), 0.0001D,
                "right-positioned mono sound lost right-channel gain");

            ResourceLocation stereoLocation = resource("positioned_stereo");
            CapturedSound positionedStereo = new CapturedSound(stereoLocation, 0,
                1D, 0D, 0D, false, true, 1F, 1F, 1F, false);
            Wave authoredStereo = stereoWave(1, 0.25F, -0.75F);
            Path stereoOutput = root.resolve("authored-stereo.wav");
            AudioRenderResult stereoResult = renderCaptured(stereoOutput,
                List.of(positionedStereo), List.of(listener),
                Map.of(stereoLocation, authoredStereo),
                1, 1D, 1, ChannelLayout.STEREO,
                () -> false, (completed, total) -> {});
            Wave preserved = readWave(stereoOutput);

            check(stereoResult.success(), "authored stereo render failed");
            assertNear(0.25D, sample(preserved, 0, 0), 0.0001D,
                "authored stereo left channel was spatially panned");
            assertNear(-0.75D, sample(preserved, 0, 1), 0.0001D,
                "authored stereo right channel was spatially panned");

            Path downmixOutput = root.resolve("authored-stereo-mono.wav");
            AudioRenderResult downmixResult = renderCaptured(downmixOutput,
                List.of(positionedStereo), List.of(listener),
                Map.of(stereoLocation, authoredStereo),
                1, 1D, 1, ChannelLayout.MONO,
                () -> false, (completed, total) -> {});
            Wave downmixed = readWave(downmixOutput);

            check(downmixResult.success(), "captured stereo downmix failed");
            assertNear(-0.25D, sample(downmixed, 0, 0), 0.0001D,
                "captured stereo sound did not use equal-weight mono downmix");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void masterLimiterAndQuantizerRunAfterAccumulation() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-renderer-master-");

        try
        {
            AudioRenderer.PreparedFilmSource positive = prepared(
                monoWave(4, 0.8F, -0.8F, 0.5F, -0.5F));
            AudioRenderer.PreparedFilmSource matching = prepared(
                monoWave(4, 0.8F, -0.8F, 0.5F, -0.5F));
            AudioRenderer.PreparedFilmSource cancellation = prepared(
                monoWave(4, -0.8F, 0.8F, 0.5F, -0.5F));
            Path firstOutput = root.resolve("first-order.wav");
            Path secondOutput = root.resolve("second-order.wav");
            AudioRenderResult firstResult = renderPrepared(firstOutput,
                List.of(positive, matching, cancellation), 4, 0D, 1D,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            AudioRenderResult secondResult = renderPrepared(secondOutput,
                List.of(cancellation, matching, positive), 4, 0D, 1D,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            byte[] firstOrder = readWave(firstOutput).data;
            byte[] secondOrder = readWave(secondOutput).data;

            check(firstResult.success() && secondResult.success(),
                "prepared master render failed");

            int expectedPositive = (int) Math.round(0.8D * 32768D);
            int expectedNegative = (int) Math.round(-0.8D * 32768D);

            check(Math.abs(readShort(firstOrder, 0) - expectedPositive) <= 1,
                "source accumulation was clipped before the master boundary");
            check(Math.abs(readShort(firstOrder, 1) - expectedNegative) <= 1,
                "negative source accumulation was clipped before the master boundary");
            check(readShort(firstOrder, 2) == Short.MAX_VALUE,
                "positive master limiter/quantizer endpoint is invalid");
            check(readShort(firstOrder, 3) == Short.MIN_VALUE,
                "negative master limiter/quantizer endpoint is invalid");

            for (int frame = 0; frame < 4; frame++)
            {
                check(Math.abs(readShort(firstOrder, frame) - readShort(secondOrder, frame)) <= 1,
                    "source order changed master output beyond one PCM unit at frame " + frame);
            }
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void rendererAndMixerShareTheBlockBoundary() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-shared-block-renderer-");

        try
        {
            Wave source = constantWave(ChannelLayout.STEREO, 1000, 9000, 0.75F);
            Path rendererOutput = root.resolve("renderer.wav");
            Path mixerOutput = root.resolve("mixer.wav");
            AudioRenderResult renderer = renderPrepared(rendererOutput,
                List.of(new AudioRenderer.PreparedFilmSource(source, 0D, 0D, 9D, 1F)),
                1000, 0D, 9D, ChannelLayout.STEREO,
                () -> false, (completed, total) -> {});
            AudioRenderResult mixer = renderFilm(mixerOutput, source,
                1000, 1000D, 9000, ChannelLayout.STEREO,
                () -> false, (completed, total) -> {});

            check(renderer.success() && mixer.success(),
                "shared block renderer adapters failed");
            check(Arrays.equals(readWave(rendererOutput).data, readWave(mixerOutput).data),
                "film adapters diverged across their shared block/master boundary");

            Path reversedOutput = root.resolve("reversed.wav");
            AudioRenderResult reversed = renderPrepared(reversedOutput,
                List.of(prepared(monoWave(4, 0.5F))), 4, 1D, 0D,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            check(reversed.status() == AudioRenderResult.Status.MIX_FAILURE
                    && !Files.exists(reversedOutput),
                "inverted typed range was silently normalized");

            Path emptyOutput = root.resolve("empty-range.wav");
            AudioRenderResult empty = renderPrepared(emptyOutput,
                List.of(prepared(monoWave(4, 0.5F))), 4, 0.5D, 0.5D,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            check(empty.status() == AudioRenderResult.Status.EMPTY
                    && !Files.exists(emptyOutput),
                "empty typed range was not preserved");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void resourceSnapshotIsDetachedAndFailuresAreTyped() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-snapshot-");

        try
        {
            ResourceLocation location = resource("detached_snapshot");
            Wave source = constantWave(ChannelLayout.MONO, 1000, 9000, 0.25F);
            CapturedSound sound = new CapturedSound(location, 0, 0D, 0D, 0D,
                true, false, 1F, 1F, 0F, false);
            AtomicBoolean mutated = new AtomicBoolean();
            Path detachedOutput = root.resolve("detached.wav");
            AudioRenderResult detached = renderCaptured(detachedOutput, List.of(sound), List.of(),
                Map.of(location, source), 1000, 1000D, 9000, ChannelLayout.MONO,
                () -> false, (completed, total) ->
                {
                    if (mutated.compareAndSet(false, true))
                    {
                        Arrays.fill(source.data, (byte) 0);
                    }
                });

            check(detached.success(), "detached resource snapshot render failed");
            assertNear(0.25D, sample(readWave(detachedOutput), 8999, 0), 0.0001D,
                "worker observed caller mutation after the snapshot boundary");

            ResourceLocation missingLocation = resource("missing_snapshot_key");
            CapturedSound missing = new CapturedSound(missingLocation, 0, 0D, 0D, 0D,
                true, false, 1F, 1F, 0F, false);
            AudioRenderResult missingResult = renderCaptured(root.resolve("missing.wav"),
                List.of(missing), List.of(), Map.of(), 1, 1D, 1,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            check(missingResult.status() == AudioRenderResult.Status.MISSING_RESOURCE,
                "missing snapshot key lost its typed status");

            Wave unsupported = monoWave(1, 0.5F);
            unsupported.numChannels = 6;
            AudioRenderResult unsupportedResult = renderCaptured(root.resolve("unsupported.wav"),
                List.of(sound), List.of(), Map.of(location, unsupported), 1, 1D, 1,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            check(unsupportedResult.status() == AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                "unsupported snapshot layout lost its typed status");

            Wave malformed = monoWave(1, 0.5F);
            malformed.data = new byte[] {0};
            AudioRenderResult malformedResult = renderCaptured(root.resolve("malformed.wav"),
                List.of(sound), List.of(), Map.of(location, malformed), 1, 1D, 1,
                ChannelLayout.MONO, () -> false, (completed, total) -> {});
            check(malformedResult.status() == AudioRenderResult.Status.MIX_FAILURE,
                "malformed snapshot data was reported as an unsupported format");

            AudioRenderResult surround = renderFilm(root.resolve("surround.wav"),
                monoWave(1, 0.5F), 1, 1D, 1, ChannelLayout.SURROUND_5_1,
                () -> false, (completed, total) -> {});
            check(surround.status() == AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                "explicit 5.1 render request was not rejected");

            AudioRenderResult empty = MinecraftSoundMixer.mixToFileResult(
                root.resolve("empty.wav").toFile(), List.of(), List.of(), null, Map.of(),
                1, 1D, 1, ChannelLayout.MONO,
                () -> false, (completed, total) -> {});
            check(empty.status() == AudioRenderResult.Status.EMPTY,
                "source-free mix lost its EMPTY status");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void mixerInputsAreDetachedAndSnapshotCancellationWins() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-input-snapshot-");

        try
        {
            ResourceLocation location = resource("detached_inputs");
            Wave film = constantWave(ChannelLayout.MONO, 1000, 9000, 0.1F);
            Wave soundWave = constantWave(ChannelLayout.MONO, 1000, 1, 0.25F);
            CapturedSound sound = new CapturedSound(location, 0, 1D, 0D, 0D,
                false, false, 0.2F, 1F, 0F, true);
            sound.track.add(new LoopFrame(0.8F, 1F, 1D, 0D, 0D));
            List<ListenerFrame> listeners = new ArrayList<>();
            listeners.add(new ListenerFrame(0D, 0D, 0D, 1D, 0D, 0D));
            AtomicBoolean mutated = new AtomicBoolean();
            Path detachedOutput = root.resolve("detached-inputs.wav");

            AudioRenderResult detached = MinecraftSoundMixer.mixToFileResult(
                detachedOutput.toFile(), List.of(sound), listeners, film,
                Map.of(location, soundWave), 1000, 1000D, 9000,
                ChannelLayout.STEREO, () -> false, (completed, total) ->
                {
                    if (mutated.compareAndSet(false, true))
                    {
                        Arrays.fill(film.data, (byte) 0);
                        sound.track.clear();
                        listeners.clear();
                    }
                });

            check(detached.success(), "detached input snapshot render failed");
            Wave rendered = readWave(detachedOutput);
            double filmCenter = 0.1D * Math.sqrt(0.5D);

            assertNear(filmCenter, sample(rendered, 8999, 0), 0.0002D,
                "worker observed caller film/listener mutation after snapshot");
            assertNear(filmCenter + 0.2D, sample(rendered, 8999, 1), 0.0002D,
                "worker observed caller loop-state mutation after snapshot");

            Wave unsupported = monoWave(1, 0.5F);
            unsupported.numChannels = 6;
            AudioRenderResult cancelledFirst = MinecraftSoundMixer.mixToFileResult(
                root.resolve("cancelled-first.wav").toFile(), List.of(sound), listeners,
                null, Map.of(location, unsupported), 1, 1D, 1,
                ChannelLayout.MONO, () -> true, (completed, total) -> {});

            check(cancelledFirst.status() == AudioRenderResult.Status.CANCELLED,
                "snapshot validation overrode an already-cancelled request");

            CapturedSound active = new CapturedSound(location, 0, 0D, 0D, 0D,
                true, false, 1F, 1F, 0F, false);
            AtomicInteger cancellationChecks = new AtomicInteger();
            AudioRenderResult cancelledDuringCopy = MinecraftSoundMixer.mixToFileResult(
                root.resolve("cancelled-during-copy.wav").toFile(), List.of(active), List.of(),
                null, Map.of(location, soundWave), 1, 1D, 1,
                ChannelLayout.MONO, () -> cancellationChecks.incrementAndGet() >= 4,
                (completed, total) -> {});

            check(cancelledDuringCopy.status() == AudioRenderResult.Status.CANCELLED,
                "resource snapshot copy did not check cancellation per entry");
            check(MinecraftSoundMixer.snapshotResources(List.of(), () -> true).status()
                    == AudioRenderResult.Status.CANCELLED,
                "empty resource preparation ignored entry cancellation");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void progressIsBoundedAndCancellationCleansOwnedTemporary() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-cancel-");

        try
        {
            Path output = root.resolve("cancelled.wav");
            Path userFile = Files.write(root.resolve("keep.bin"), new byte[] {9, 8, 7});
            byte[] userBytes = Files.readAllBytes(userFile);
            Wave source = constantWave(ChannelLayout.MONO, 1000, 9000, 0.1F);
            AtomicBoolean cancelled = new AtomicBoolean();
            List<Long> cancelledProgress = new ArrayList<>();
            AudioRenderResult cancelledResult = renderFilm(output, source,
                1000, 1000D, 9000, ChannelLayout.MONO,
                cancelled::get, (completed, total) ->
                {
                    cancelledProgress.add(completed);
                    cancelled.set(true);
                });

            check(cancelledResult.status() == AudioRenderResult.Status.CANCELLED,
                "cancelled mix reported " + cancelledResult.status());
            check(cancelledProgress.size() == 1
                    && cancelledProgress.get(0) > 0L
                    && cancelledProgress.get(0) < 9000L,
                "cancelled mix reported unbounded or post-cancel progress");
            check(!Files.exists(output), "cancelled mix published a final output");
            check(Arrays.equals(userBytes, Files.readAllBytes(userFile)),
                "cancel cleanup changed an unrelated user file");
            assertNoMixerTemporary(root, output);

            Path completedOutput = root.resolve("completed.wav");
            List<Long> completedProgress = new ArrayList<>();
            List<Long> totals = new ArrayList<>();
            AudioRenderResult completed = renderFilm(completedOutput, source,
                1000, 1000D, 9000, ChannelLayout.MONO,
                () -> false, (done, total) ->
                {
                    completedProgress.add(done);
                    totals.add(total);
                });

            check(completed.success(), "uncancelled progress render failed");
            check(completedProgress.size() > 1,
                "long render did not report bounded block progress");
            check(totals.stream().allMatch(total -> total == 9000L),
                "progress total changed during rendering");
            assertMonotonicBounded(completedProgress, 9000L);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void existingOutputIsNeverReplaced() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-mixer-no-replace-");

        try
        {
            Path output = Files.write(root.resolve("existing.wav"), new byte[] {4, 2, 4, 2});
            byte[] existing = Files.readAllBytes(output);
            AudioRenderResult result = renderFilm(output, monoWave(1, 0.5F),
                1, 1D, 1, ChannelLayout.MONO,
                () -> false, (completed, total) -> {});

            check(result.status() == AudioRenderResult.Status.IO_FAILURE,
                "existing target did not fail with IO_FAILURE");
            check(Arrays.equals(existing, Files.readAllBytes(output)),
                "mixer replaced a pre-existing target");
            assertNoMixerTemporary(root, output);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static AudioRenderResult renderFilm(Path output, Wave filmAudio,
                                                  int sampleRate, double frameRate,
                                                  int totalFrames, ChannelLayout layout,
                                                  BooleanSupplier cancelled,
                                                  BiConsumer<Long, Long> progress)
    {
        return MinecraftSoundMixer.mixToFileResult(output.toFile(), List.of(), List.of(),
            filmAudio, sampleRate, frameRate, totalFrames, layout, cancelled, progress);
    }

    private static AudioRenderResult renderCaptured(Path output,
                                                     List<CapturedSound> sounds,
                                                     List<ListenerFrame> frames,
                                                     Map<ResourceLocation, Wave> resources,
                                                     int sampleRate, double frameRate,
                                                     int totalFrames, ChannelLayout layout,
                                                     BooleanSupplier cancelled,
                                                     BiConsumer<Long, Long> progress)
    {
        return MinecraftSoundMixer.mixToFileResult(output.toFile(), sounds, frames, null,
            resources, sampleRate, frameRate, totalFrames, layout, cancelled, progress);
    }

    private static AudioRenderResult renderPrepared(Path output,
                                                    List<AudioRenderer.PreparedFilmSource> sources,
                                                    int sampleRate, double start, double end,
                                                    ChannelLayout layout,
                                                    BooleanSupplier cancelled,
                                                    BiConsumer<Long, Long> progress)
    {
        return AudioRenderer.renderPreparedAudio(output.toFile(), sources, sampleRate,
            start, end, layout, cancelled, progress);
    }

    private static AudioRenderer.PreparedFilmSource prepared(Wave wave)
    {
        return new AudioRenderer.PreparedFilmSource(wave, 0D, 0D, 1D, 1F);
    }

    private static CapturedSound loopingSound(ResourceLocation location, float[] pitches)
    {
        CapturedSound sound = new CapturedSound(location, 0, 0D, 0D, 0D,
            true, false, 1F, 1F, 0F, true);

        for (float pitch : pitches)
        {
            sound.track.add(new LoopFrame(1F, pitch, 0D, 0D, 0D));
        }

        return sound;
    }

    private static ResourceLocation resource(String path)
    {
        return ResourceLocation.fromNamespaceAndPath("bbs_test", path);
    }

    private static Wave monoWave(int sampleRate, float... samples)
    {
        return wave(ChannelLayout.MONO, sampleRate, samples);
    }

    private static Wave stereoWave(int sampleRate, float... samples)
    {
        check((samples.length & 1) == 0, "stereo fixture has a partial frame");

        return wave(ChannelLayout.STEREO, sampleRate, samples);
    }

    private static Wave constantWave(ChannelLayout layout, int sampleRate,
                                     int frames, float value)
    {
        float[] samples = new float[Math.multiplyExact(frames, layout.channels())];

        Arrays.fill(samples, value);

        return wave(layout, sampleRate, samples);
    }

    private static Wave wave(ChannelLayout layout, int sampleRate, float[] samples)
    {
        PcmFormat format = new PcmFormat(PcmEncoding.IEEE_FLOAT32_LE, layout, sampleRate);
        byte[] data = new byte[Math.multiplyExact(samples.length, format.bytesPerSample())];

        for (int i = 0; i < samples.length; i++)
        {
            PcmSamples.writeNormalized(format.encoding(), data,
                i * format.bytesPerSample(), samples[i]);
        }

        return new Wave(format, data);
    }

    private static Wave pcm16Wave(ChannelLayout layout, int sampleRate, double... samples)
    {
        PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE, layout, sampleRate);
        byte[] data = new byte[Math.multiplyExact(samples.length, format.bytesPerSample())];

        for (int i = 0; i < samples.length; i++)
        {
            PcmSamples.writeNormalized(format.encoding(), data,
                i * format.bytesPerSample(), samples[i]);
        }

        return new Wave(format, data);
    }

    private static Wave readWave(Path path) throws Exception
    {
        try (InputStream stream = Files.newInputStream(path))
        {
            return new WaveReader().read(stream);
        }
    }

    private static double sample(Wave wave, long frame, int channel)
    {
        return PcmSamples.readNormalized(wave, frame, channel);
    }

    private static short readShort(byte[] data, int frame)
    {
        int offset = Math.multiplyExact(frame, 2);

        return (short) ((data[offset] & 0xff) | data[offset + 1] << 8);
    }

    private static double loopSample(float[] samples, double phase)
    {
        double wrapped = phase % samples.length;

        if (wrapped < 0D)
        {
            wrapped += samples.length;
        }

        int first = (int) Math.floor(wrapped);
        int second = (first + 1) % samples.length;
        double fraction = wrapped - first;

        return samples[first] + (samples[second] - samples[first]) * fraction;
    }

    private static void assertMonotonicBounded(List<Long> progress, long total)
    {
        long previous = -1L;

        for (long completed : progress)
        {
            check(completed >= 0L && completed <= total,
                "progress escaped [0,total]: " + completed + "/" + total);
            check(completed >= previous, "progress moved backwards");
            previous = completed;
        }

        check(previous == total, "successful progress did not reach total");
    }

    private static void assertNoMixerTemporary(Path directory, Path output) throws IOException
    {
        String prefix = "." + output.getFileName() + ".";

        try (Stream<Path> files = Files.list(directory))
        {
            check(files.noneMatch(path -> path.getFileName().toString().startsWith(prefix)),
                "session-owned mixer temporary file was not cleaned");
        }
    }

    private static void assertNear(double expected, double actual,
                                   double tolerance, String message)
    {
        if (Math.abs(expected - actual) > tolerance)
        {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
