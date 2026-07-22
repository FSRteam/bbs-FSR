package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.AudioRenderResult;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.MinecraftSoundCapture;
import mchorse.bbs_mod.audio.MinecraftSoundMixer;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;
import mchorse.bbs_mod.utils.VideoExportProcess;
import mchorse.bbs_mod.utils.VideoMuxer;
import mchorse.bbs_mod.utils.VideoRecorder;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Hermetic, owned-pipeline export lifecycle checks.  The fixture executes the
 * real VideoExportSession state machine and its WAV normalizer/mixer.  Only
 * the recorder, client dispatcher, Minecraft capture, and FFmpeg mux process
 * are injected through protected seams.
 */
public final class AudioExportLifecycleTest
{
    private static final int SAMPLE_RATE = VideoExportAudioProfile.SAMPLE_RATE;
    private static final double FRAME_RATE = 30D;
    private static final int WIDTH = 64;
    private static final int HEIGHT = 36;
    private static final ResourceLocation GAME_SOUND =
        ResourceLocation.fromNamespaceAndPath("bbs_export_test", "impulse");

    private AudioExportLifecycleTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("AudioExportLifecycleTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        testF4SilenceAndRange();
        testDirectFilmMonoAndStereo();
        testFilmMinecraftMix();
        testNonzeroPanelLoop();
        testPanelLoopMissingResourceIsTypedDegraded();
        testCancellationIsTerminalAndExactlyOnce();
        testDegradedAudioRecovery();
        testCaptureFailureAndNextGenerationRecovery();
        testStartupFailureIsDistinct();
    }

    private static void testF4SilenceAndRange() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-f4-");
        try
        {
            Scenario scenario = new Scenario("f4", ChannelLayout.MONO, 12D, 0D, true,
                false, false, 37, 20D, RenderKind.SUCCESS, true, true);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            VideoExportResult result = fixture.runToTerminal();

            check(result.kind() == VideoExportResult.Kind.SUCCESS,
                "F4 silence did not complete successfully");
            check(result.artifact() != null && result.artifact().finalVideo() != null,
                "F4 did not publish its owned video");
            check(!result.artifact().audioPresent() && result.artifact().audioFrames() == 0L,
                "F4 unexpectedly advertised an audio stream");
            check(fixture.renderCalls == 0 && fixture.mixCalls == 0 && fixture.muxCalls == 0,
                "F4 entered an audio or mux branch");
            fixture.assertRangeAndFrameAlignment(result);
            fixture.assertExactlyOnce(false);
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testDirectFilmMonoAndStereo() throws Exception
    {
        for (ChannelLayout layout : new ChannelLayout[]{ChannelLayout.MONO, ChannelLayout.STEREO})
        {
            Path root = Files.createTempDirectory("bbs-export-film-");
            try
            {
                Scenario scenario = new Scenario("direct-film-" + layout.id(), layout,
                    20D, 80D, false, true, false, 90, 20D, RenderKind.SUCCESS, true, true);
                OwnedFixture fixture = new OwnedFixture(root, scenario);
                VideoExportResult result = fixture.runToTerminal();

                check(result.kind() == VideoExportResult.Kind.SUCCESS,
                    "direct-film " + layout + " did not complete successfully");
                check(result.artifact().audioPresent(), "direct-film omitted its audio stream");
                check(result.artifact().requestedLayout() == layout
                        && result.artifact().deliveredLayout() == layout,
                    "direct-film changed the requested layout");
                check(fixture.renderCalls == 1 && fixture.mixCalls == 0 && fixture.muxCalls == 1,
                    "direct-film did not take exactly the direct audio branch");
                check(fixture.muxAudio != null && fixture.muxAudio.getFormat().layout() == layout,
                    "direct-film mux did not receive the requested channel layout");
                fixture.assertRangeAndFrameAlignment(result);
                fixture.assertExactlyOnce(false);
                fixture.close();
            }
            finally
            {
                deleteTree(root);
            }
        }
    }

    private static void testFilmMinecraftMix() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-mix-");
        try
        {
            Scenario scenario = new Scenario("film-minecraft", ChannelLayout.STEREO,
                10D, 70D, false, true, true, 90, 20D, RenderKind.SUCCESS, true, true);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            VideoExportResult result = fixture.runToTerminal();

            check(result.kind() == VideoExportResult.Kind.SUCCESS,
                "film+Minecraft mix did not complete successfully");
            check(result.artifact().audioPresent()
                    && result.artifact().deliveredLayout() == ChannelLayout.STEREO,
                "film+Minecraft mix did not deliver stereo audio");
            check(fixture.captureBeginCalls == 1 && fixture.captureEndCalls == 1,
                "Minecraft capture did not have one begin/end pair");
            check(fixture.renderCalls == 1 && fixture.mixCalls == 1 && fixture.muxCalls == 1,
                "film+Minecraft mix did not execute all owned audio stages");
            check(fixture.mixResourceSnapshotSuccess,
                "film+Minecraft mix did not receive a successful resource snapshot");
            check(fixture.muxAudio != null && fixture.muxAudio.numChannels == 2,
                "film+Minecraft mix lost stereo channels before mux");
            check(fixture.muxAudio.data.length > 0,
                "film+Minecraft mix produced an empty PCM artifact");
            fixture.assertRangeAndFrameAlignment(result);
            fixture.assertExactlyOnce(false);
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testNonzeroPanelLoop() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-panel-loop-");
        try
        {
            /* Panel loop bounds are film ticks.  They must remain the same
             * non-zero half-open range through request, renderer and artifact. */
            Scenario scenario = new Scenario("panel-loop", ChannelLayout.STEREO,
                40D, 70D, false, true, false, 45, 20D, RenderKind.SUCCESS, true, true);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            VideoExportResult result = fixture.runToTerminal();

            check(result.kind() == VideoExportResult.Kind.SUCCESS,
                "nonzero panel loop did not complete successfully");
            check(fixture.firstRequest.sourceStart() == 40D
                    && fixture.firstRequest.sourceEnd() == 70D
                    && !fixture.firstRequest.openEnd(),
                "panel loop bounds were not snapshotted as a half-open interval");
            check(fixture.renderRequest.sourceStart() == 40D
                    && fixture.renderRequest.sourceEnd() == 70D,
                "film renderer received a different panel-loop interval");
            check(result.artifact().sourceStart() == 40D
                    && result.artifact().sourceEnd() == 70D,
                "panel-loop artifact range drifted");
            fixture.assertRangeAndFrameAlignment(result);
            fixture.assertExactlyOnce(false);
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testCancellationIsTerminalAndExactlyOnce() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-cancel-");
        try
        {
            Scenario scenario = new Scenario("cancel", ChannelLayout.MONO,
                0D, 60D, false, true, false, 60, 20D, RenderKind.SUCCESS, true, false);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            fixture.start();
            fixture.stop();
            check(fixture.state == VideoExportSession.State.POSTPROCESSING,
                "stop did not enter owned postprocessing");
            fixture.cancel();
            fixture.cancel();
            fixture.drainCallbacks();
            fixture.runQueuedWorker();
            fixture.drainCallbacks();

            VideoExportResult result = fixture.getLastExportResult();
            check(result != null && result.kind() == VideoExportResult.Kind.CANCELLED,
                "cancel did not win the terminal result");
            check(fixture.renderCalls == 0 && fixture.mixCalls == 0 && fixture.muxCalls == 0,
                "cancel committed audio work after the cancellation request");
            fixture.assertExactlyOnce(true);
            check(fixture.recorder.completeCalls == 1 && fixture.recorder.cancelCalls == 0,
                "postprocess cancellation invoked recorder teardown twice");
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testPanelLoopMissingResourceIsTypedDegraded() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-panel-missing-");
        try
        {
            Scenario scenario = new Scenario("panel-loop-missing", ChannelLayout.MONO,
                20D, 50D, false, true, false, 45, 20D,
                RenderKind.MISSING_RESOURCE, true, false);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            VideoExportResult result = fixture.runToTerminal();

            check(result.kind() == VideoExportResult.Kind.DEGRADED,
                "missing panel-loop audio was reported as success");
            check(result.stage() == VideoExportResult.Stage.MISSING_RESOURCE
                    && result.failureKind() == VideoExportResult.FailureKind.MISSING_RESOURCE,
                "missing panel-loop resource lost its typed failure");
            check(result.artifact().finalVideo() != null && !result.artifact().audioPresent(),
                "missing panel-loop audio did not retain an explicit video-only result");
            check(fixture.renderCalls == 1 && fixture.mixCalls == 0 && fixture.muxCalls == 0,
                "missing film resource continued into mix/mux");
            fixture.assertRangeAndFrameAlignment(result);
            fixture.assertExactlyOnce(true);
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testDegradedAudioRecovery() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-degraded-");
        try
        {
            Scenario scenario = new Scenario("degraded", ChannelLayout.MONO,
                0D, 60D, false, true, false, 90, 20D, RenderKind.SUCCESS, true, false);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            fixture.muxSucceeds = false;
            VideoExportResult result = fixture.runToTerminal();

            check(result.kind() == VideoExportResult.Kind.DEGRADED,
                "mux/audio failure was reported as unqualified success");
            check(result.stage() == VideoExportResult.Stage.MUX
                    && result.failureKind() == VideoExportResult.FailureKind.MUX,
                "degraded result lost its typed mux failure");
            check(result.artifact().finalVideo() != null && result.artifact().recoveryAudio() != null,
                "degraded result did not retain video and recovery audio");
            check(Files.isRegularFile(result.artifact().finalVideo())
                    && Files.isRegularFile(result.artifact().recoveryAudio()),
                "degraded artifacts were not recoverable");
            Wave recovery = readWave(result.artifact().recoveryAudio());
            check(recovery.getFormat().layout() == ChannelLayout.MONO,
                "recovery audio changed the requested layout");
            check(recovery.getFrameCount()
                    == fixture.firstRequest.audioFramesFor(scenario.deliveredFrames()),
                "recovery audio duration did not use the video frame ceiling");
            fixture.assertExactlyOnce(true);
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testCaptureFailureAndNextGenerationRecovery() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-capture-failure-");
        try
        {
            Scenario first = new Scenario("capture-failure", ChannelLayout.STEREO,
                0D, 40D, false, false, true, 60, 20D, RenderKind.SUCCESS, true, true);
            OwnedFixture fixture = new OwnedFixture(root, first);
            VideoExportResult failed = fixture.runToTerminal();

            check(failed.kind() == VideoExportResult.Kind.AUDIO_FAILED,
                "requested Minecraft capture failure was not typed AUDIO_FAILED");
            check(failed.stage() == VideoExportResult.Stage.AUDIO_MIX
                    && failed.failureKind() == VideoExportResult.FailureKind.AUDIO_MIX,
                "capture failure lost its typed audio-mix classification");
            check(fixture.captureBeginCalls == 1 && fixture.captureEndCalls == 1,
                "failed capture did not have one begin/end attempt");
            check(failed.artifact().rawVideo() != null
                    && Files.isRegularFile(failed.artifact().rawVideo()),
                "audio failure did not retain the raw video recovery artifact");
            Path retainedRaw = failed.artifact().rawVideo();
            fixture.assertExactlyOnce(true);

            Scenario second = new Scenario("capture-recovered", ChannelLayout.MONO,
                0D, 0D, true, false, false, 31, 20D, RenderKind.SUCCESS, true, true);
            fixture.setScenario(second);
            VideoExportResult recovered = fixture.runToTerminal();
            check(recovered.kind() == VideoExportResult.Kind.SUCCESS,
                "session could not recover after typed audio failure");
            check(!recovered.sessionId().equals(failed.sessionId())
                    && recovered.generation() > failed.generation(),
                "recovery reused the failed session generation");
            check(Files.isRegularFile(retainedRaw),
                "next generation cleanup removed the previous recovery artifact");
            fixture.assertExactlyOnce(false);
            check(fixture.persistentTerminalCalls == 2,
                "persistent terminal observer did not receive both generations");
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void testStartupFailureIsDistinct() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-start-failure-");
        try
        {
            Scenario scenario = new Scenario("startup-failure", ChannelLayout.MONO,
                0D, 0D, true, false, false, 1, 20D, RenderKind.SUCCESS, true, true);
            OwnedFixture fixture = new OwnedFixture(root, scenario);
            fixture.recorder.startSucceeds = false;
            VideoExportResult result = fixture.runToTerminal();

            check(result.kind() == VideoExportResult.Kind.START_FAILED,
                "recorder startup failure was folded into success/encode failure");
            check(result.stage() == VideoExportResult.Stage.START,
                "startup failure reported the wrong stage");
            check(fixture.teardownCalls == 1 && fixture.persistentTerminalCalls == 1,
                "startup failure did not teardown and notify exactly once");
            fixture.close();
        }
        finally
        {
            deleteTree(root);
        }
    }

    private enum RenderKind
    {
        SUCCESS,
        EMPTY,
        MISSING_RESOURCE,
        IO_FAILURE
    }

    private record Scenario(String id, ChannelLayout layout, double sourceStart,
                            double sourceEnd, boolean openEnd, boolean filmAudio,
                            boolean minecraftAudio, int deliveredFrames,
                            double sourceUnitsPerSecond, RenderKind renderKind,
                            boolean muxSucceeds, boolean captureAvailable)
    {}

    private static final class OwnedFixture extends VideoExportSession
    {
        private final Path root;
        private Scenario scenario;
        private final FixtureRecorder recorder = new FixtureRecorder();
        private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        private FutureTask<Void> queuedWorker;
        private VideoExportArtifacts artifacts;
        private VideoExportRequest firstRequest;
        private VideoExportRequest renderRequest;
        private int generation;
        private int renderCalls;
        private int mixCalls;
        private int muxCalls;
        private int captureBeginCalls;
        private int captureEndCalls;
        private int teardownCalls;
        private int terminalHookCalls;
        private int persistentTerminalCalls;
        private int terminalHookBaseline;
        private int teardownBaseline;
        private boolean lastLegacyAborted;
        private boolean mixResourceSnapshotSuccess;
        private boolean muxSucceeds;
        private Wave filmWave;
        private Wave gameWave;
        private Wave muxAudio;
        private AtomicInteger typedCalls = new AtomicInteger();
        private AtomicInteger legacyCalls = new AtomicInteger();

        private OwnedFixture(Path root, Scenario scenario)
        {
            this.root = root;
            this.scenario = scenario;
            this.muxSucceeds = scenario.muxSucceeds();
            this.gameWave = createWave(ChannelLayout.MONO, 48_000, 11);
            this.addFinishedResultListener(result -> this.persistentTerminalCalls += 1);
        }

        private void setScenario(Scenario scenario)
        {
            this.scenario = scenario;
            this.muxSucceeds = scenario.muxSucceeds();
            this.recorder.startSucceeds = true;
        }

        private boolean start() throws Exception
        {
            this.typedCalls = new AtomicInteger();
            this.legacyCalls = new AtomicInteger();
            this.lastLegacyAborted = false;
            this.terminalHookBaseline = this.terminalHookCalls;
            this.teardownBaseline = this.teardownCalls;
            this.recorder.deliveredFrames = this.scenario.deliveredFrames();
            this.setFinishedListener(aborted ->
            {
                this.lastLegacyAborted = aborted;
                this.legacyCalls.incrementAndGet();
            });
            this.setFinishedResultListener(result -> this.typedCalls.incrementAndGet());
            boolean active = this.begin(1, WIDTH, HEIGHT, 0L);
            if (this.recorder.startSucceeds)
            {
                check(active, "owned fixture did not enter recording: " + this.scenario.id());
            }
            else
            {
                check(!active, "failed recorder startup left the session active");
            }
            return active;
        }

        private VideoExportResult runToTerminal() throws Exception
        {
            if (this.start()) this.sessionStopAndRun();
            else this.drainCallbacks();
            VideoExportResult result = this.getLastExportResult();
            check(result != null, "owned fixture did not produce a terminal result");
            return result;
        }

        private void sessionStopAndRun()
        {
            this.stop();
            this.runQueuedWorker();
            this.drainCallbacks();
        }

        private void runQueuedWorker()
        {
            FutureTask<Void> worker = this.queuedWorker;
            if (worker != null)
            {
                worker.run();
                this.queuedWorker = null;
            }
        }

        private void drainCallbacks()
        {
            Runnable callback;
            while ((callback = this.callbacks.poll()) != null)
            {
                callback.run();
            }
        }

        private void assertRangeAndFrameAlignment(VideoExportResult result)
        {
            VideoExportArtifact artifact = result.artifact();
            check(artifact != null, "terminal result omitted its artifact");
            check(artifact.sourceStart() == firstRequest.sourceStart()
                    && artifact.sourceEnd() == firstRequest.sourceEnd()
                    && artifact.openEnd() == firstRequest.openEnd(),
                "request and artifact did not retain one half-open range");
            check(artifact.capturedFrames() == scenario.deliveredFrames(),
                "delivered frame count drifted across the owned boundary");
            check(artifact.videoFrames() == firstRequest.outputFramesFor(scenario.deliveredFrames()),
                "output frame count was not derived from the captured frame count");

            double videoSeconds = artifact.videoFrames() / firstRequest.outputFrameRate();
            double capturedSeconds = artifact.durationSeconds();
            check(Math.abs(videoSeconds - capturedSeconds) <= 1D / firstRequest.outputFrameRate() + 1E-9D,
                "video duration is more than one output frame from capture duration");
            if (!firstRequest.openEnd())
            {
                double requestedSeconds = (firstRequest.sourceEnd() - firstRequest.sourceStart())
                    / scenario.sourceUnitsPerSecond();
                check(Math.abs(requestedSeconds - videoSeconds)
                        <= 1D / firstRequest.outputFrameRate() + 1E-9D,
                    "video duration drifted from the requested half-open range");
            }
            if (artifact.audioPresent())
            {
                long expectedAudioFrames = firstRequest.audioFramesFor(scenario.deliveredFrames());
                check(artifact.audioFrames() == expectedAudioFrames,
                    "audio frame ceiling does not match delivered video frames");
                double audioSeconds = artifact.audioFrames() / (double) SAMPLE_RATE;
                check(Math.abs(audioSeconds - videoSeconds)
                        <= 1D / firstRequest.outputFrameRate() + 1E-9D,
                    "audio/video duration differs by more than one output frame");
            }
        }

        private void assertExactlyOnce(boolean aborted)
        {
            check(this.legacyCalls.get() == 1, "legacy terminal callback was not exactly once");
            check(this.typedCalls.get() == 1, "typed terminal callback was not exactly once");
            check(this.terminalHookCalls == this.terminalHookBaseline + 1,
                "terminal hook was not exactly once");
            check(this.teardownCalls == this.teardownBaseline + 1,
                "owned teardown was not exactly once");
            check(this.lastLegacyAborted == aborted,
                "legacy callback aborted flag disagreed with typed terminal result");
            this.update();
            this.stop();
            this.cancel();
            this.drainCallbacks();
            check(this.legacyCalls.get() == 1 && this.typedCalls.get() == 1
                    && this.terminalHookCalls == this.terminalHookBaseline + 1
                    && this.teardownCalls == this.teardownBaseline + 1,
                "repeated terminal calls changed the terminal counts");
        }

        @Override
        protected VideoRecorder getRecorder()
        {
            return this.recorder;
        }

        @Override
        protected VideoExportRequest createExportRequest(int width, int height) throws Exception
        {
            this.artifacts = VideoExportArtifacts.allocate(this.root, this.scenario.id());
            VideoExportRequest request = new VideoExportRequest(this.artifacts.sessionId(),
                ++this.generation, this.scenario.sourceStart(), this.scenario.sourceEnd(),
                this.scenario.openEnd(), FRAME_RATE, SAMPLE_RATE, 0, this.scenario.layout(),
                this.scenario.filmAudio(), this.scenario.minecraftAudio(), this.artifacts,
                this.scenario.id(), FRAME_RATE, 1, false, width, height,
                VideoExportAudioProfile.DEFAULT_VIDEO_ARGUMENTS,
                VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS, false);
            this.firstRequest = request;
            return request;
        }

        @Override
        protected void onOwnedExportStarted(VideoExportRequest request)
        {
            this.firstRequest = request;
        }

        @Override
        protected boolean prepare()
        {
            return true;
        }

        @Override
        protected boolean isWarmupReady()
        {
            return true;
        }

        @Override
        protected void onRecordingStarted()
        {}

        @Override
        protected boolean isFinished()
        {
            return false;
        }

        @Override
        protected void teardown(boolean cancelled)
        {
            this.teardownCalls += 1;
        }

        @Override
        protected String getMovieName()
        {
            return "fixture-" + this.scenario.id();
        }

        @Override
        protected void postToClient(Runnable runnable)
        {
            if (runnable != null) this.callbacks.add(runnable);
        }

        @Override
        protected Future<?> submitPostprocess(Runnable runnable)
        {
            this.queuedWorker = new FutureTask<>(runnable, null);
            return this.queuedWorker;
        }

        @Override
        protected boolean isMinecraftSoundCaptureAvailable()
        {
            return this.scenario.captureAvailable() && this.scenario.minecraftAudio();
        }

        @Override
        protected void startMinecraftSoundCapture()
        {
            this.captureBeginCalls += 1;
        }

        @Override
        protected CapturedAudioSnapshot finishOwnedMinecraftSoundCapture()
        {
            this.captureEndCalls += 1;
            if (this.scenario.id().contains("capture-failure"))
            {
                return new CapturedAudioSnapshot(List.of(), List.of(),
                    new IllegalStateException("fixture capture failure"));
            }

            List<MinecraftSoundCapture.CapturedSound> sounds = this.scenario.minecraftAudio()
                ? List.of(new MinecraftSoundCapture.CapturedSound(GAME_SOUND, 0,
                    1D, 0D, 0D, false, false, 1F, 1F, 16F, false))
                : List.of();
            List<MinecraftSoundCapture.ListenerFrame> frames = new ArrayList<>();
            for (int i = 0; i < this.scenario.deliveredFrames(); i++)
            {
                frames.add(new MinecraftSoundCapture.ListenerFrame(0D, 0D, 0D,
                    1D, 0D, 0D));
            }
            return new CapturedAudioSnapshot(sounds, frames, null);
        }

        @Override
        protected MinecraftSoundMixer.SoundResourceFiles snapshotMinecraftSoundResources(
            List<MinecraftSoundCapture.CapturedSound> sounds, BooleanSupplier cancelled)
        {
            /* The mixer seam receives an explicit immutable memory map below;
             * this success descriptor proves the resource stage was entered
             * without touching Minecraft's resource manager. */
            return MinecraftSoundMixer.SoundResourceFiles.empty();
        }

        @Override
        protected AudioRenderResult renderFilmAudio(VideoExportRequest request, File output,
                                                      BooleanSupplier cancelled,
                                                      BiConsumer<Long, Long> progress)
        {
            this.renderCalls += 1;
            this.renderRequest = request;
            if (this.scenario.renderKind() != RenderKind.SUCCESS)
            {
                AudioRenderResult.Status status = this.scenario.renderKind() == RenderKind.EMPTY
                    ? AudioRenderResult.Status.EMPTY
                    : this.scenario.renderKind() == RenderKind.MISSING_RESOURCE
                        ? AudioRenderResult.Status.MISSING_RESOURCE
                        : AudioRenderResult.Status.IO_FAILURE;
                return AudioRenderResult.failure(status, output, request.layout(), 0L,
                    "fixture film render " + status, new IOException("fixture film render failure"));
            }

            long frames = request.audioFramesFor(this.scenario.deliveredFrames());
            this.filmWave = createWave(request.layout(), SAMPLE_RATE, frames);
            try
            {
                WaveWriter.write(output, this.filmWave);
            }
            catch (IOException e)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.IO_FAILURE, output,
                    request.layout(), 0L, e.getMessage(), e);
            }
            progress.accept(frames, frames);
            return AudioRenderResult.success(output, request.layout(), frames);
        }

        @Override
        protected AudioRenderResult mixMinecraftSoundSources(File output,
            List<MinecraftSoundCapture.CapturedSound> sounds,
            List<MinecraftSoundCapture.ListenerFrame> frames,
            Path filmAudio, MinecraftSoundMixer.SoundResourceFiles resources,
            int sampleRate, double frameRate, int totalFrames, ChannelLayout layout,
            BooleanSupplier cancelled, BiConsumer<Long, Long> progress)
        {
            this.mixCalls += 1;
            this.mixResourceSnapshotSuccess = resources != null && resources.success();
            try
            {
                Wave film = readWave(filmAudio);
                Map<ResourceLocation, Wave> game = Map.of(GAME_SOUND, this.gameWave);
                return MinecraftSoundMixer.mixToFileResult(output, sounds, frames, film,
                    game, sampleRate, frameRate, totalFrames, layout, cancelled, progress);
            }
            catch (Exception e)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, output,
                    layout, 0L, "fixture Minecraft mix failed", e);
            }
        }

        @Override
        protected VideoMuxer.MuxResult muxOwnedExport(File video, File audio, Path output, Path log,
                                                        ChannelLayout layout, String arguments,
                                                        BooleanSupplier cancelled, boolean keepLog)
        {
            this.muxCalls += 1;
            try
            {
                this.muxAudio = readWave(audio.toPath());
            }
            catch (Exception e)
            {
                return new VideoMuxer.MuxResult(VideoMuxer.Status.ENCODE_FAILED, null, e,
                    "fixture could not inspect mux audio", false, List.of());
            }
            if (!this.muxSucceeds)
            {
                return new VideoMuxer.MuxResult(VideoMuxer.Status.ENCODE_FAILED, null,
                    new IOException("fixture nonzero mux exit"), "fixture mux failed", false, List.of());
            }
            try
            {
                Files.writeString(output, "fixture-muxed-video");
                return new VideoMuxer.MuxResult(VideoMuxer.Status.SUCCESS, output, null,
                    "", false, List.of());
            }
            catch (IOException e)
            {
                return new VideoMuxer.MuxResult(VideoMuxer.Status.PREPARATION_FAILED, null,
                    e, "fixture mux write failed", false, List.of());
            }
        }

        @Override
        protected void onTerminalResult(VideoExportResult result)
        {
            this.terminalHookCalls += 1;
        }

        private void close()
        {
            this.drainCallbacks();
            this.runQueuedWorker();
            this.drainCallbacks();
        }
    }

    private static final class FixtureRecorder extends VideoRecorder
    {
        private Object owner;
        private boolean recording;
        private boolean startSucceeds = true;
        private boolean outputPublished;
        private File outputFile;
        private int counter;
        private int deliveredFrames;
        private Throwable failure;
        private VideoExportProcess.Outcome outcome = VideoExportProcess.Outcome.IDLE;
        private int completeCalls;
        private int cancelCalls;

        @Override
        public synchronized boolean tryReserve(Object candidate)
        {
            if (this.owner == null) this.owner = candidate;
            return this.owner == candidate;
        }

        @Override
        public synchronized boolean tryReleaseReservation(Object candidate)
        {
            if (this.owner == candidate)
            {
                this.owner = null;
                return true;
            }
            return this.owner == null;
        }

        @Override
        public boolean isRecording() { return this.recording; }
        @Override
        public int getCounter() { return this.counter; }
        @Override
        public Throwable getFailure() { return this.failure; }
        @Override
        public VideoExportProcess.Outcome getOutcome() { return this.outcome; }
        @Override
        public boolean didPublishOutput() { return this.outputPublished; }
        @Override
        public boolean didStartOutputProducer() { return this.outputPublished; }
        @Override
        public boolean hasLiveProcess() { return false; }
        @Override
        public File getOutputFile() { return this.outputFile; }

        @Override
        public boolean tryStartRecording(String movieName, File audioFile, File outputFile,
                                         File logFile, ChannelLayout layout, double frameRate,
                                         int motionBlurPasses, int heldFrames,
                                         boolean limitFrameRate, String arguments,
                                         boolean logEnabled, int textureId, int width, int height)
        {
            this.outputPublished = false;
            this.outputFile = null;
            this.recording = false;
            this.counter = 0;
            if (!this.startSucceeds)
            {
                this.failure = new IOException("fixture FFmpeg startup failure");
                this.outcome = VideoExportProcess.Outcome.FAILED;
                return false;
            }
            try
            {
                Files.writeString(outputFile.toPath(), "fixture-raw-video");
            }
            catch (IOException e)
            {
                this.failure = e;
                this.outcome = VideoExportProcess.Outcome.FAILED;
                return false;
            }
            this.outputFile = outputFile;
            this.outputPublished = true;
            this.recording = true;
            this.counter = 0;
            this.failure = null;
            this.outcome = VideoExportProcess.Outcome.RUNNING;
            return true;
        }

        @Override
        public boolean checkRecordingHealth()
        {
            return this.recording && this.outcome == VideoExportProcess.Outcome.RUNNING;
        }

        @Override
        public VideoExportProcess.Outcome completeRecording()
        {
            this.completeCalls += 1;
            this.recording = false;
            this.counter = this.deliveredFrames;
            this.outcome = VideoExportProcess.Outcome.SUCCEEDED;
            return this.outcome;
        }

        @Override
        public VideoExportProcess.Outcome cancelRecording()
        {
            this.cancelCalls += 1;
            this.recording = false;
            this.outcome = VideoExportProcess.Outcome.CANCELLED;
            return this.outcome;
        }

        @Override
        public VideoExportProcess.Outcome failRecording(Throwable cause)
        {
            this.recording = false;
            this.failure = cause;
            this.outcome = VideoExportProcess.Outcome.FAILED;
            return this.outcome;
        }

        @Override
        public boolean acceptPublishedOutput(File expectedRaw, File published)
        {
            if (this.outputFile == null || !this.outputFile.equals(expectedRaw)
                || published == null || !published.isFile()) return false;
            this.outputFile = published;
            return true;
        }

        @Override
        public void announceSuccessfulCompletion()
        {}
    }

    private static Wave createWave(ChannelLayout layout, int sampleRate, long frames)
    {
        PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE, layout, sampleRate);
        int count = Math.toIntExact(Math.multiplyExact(frames, format.bytesPerFrame()));
        byte[] data = new byte[count];
        ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (long frame = 0; frame < frames; frame++)
        {
            short left = (short) (2000 + (frame % 31) * 37);
            short right = (short) (-1500 - (frame % 17) * 29);
            bytes.putShort(left);
            if (layout == ChannelLayout.STEREO) bytes.putShort(right);
        }
        return new Wave(format, data);
    }

    private static Wave readWave(Path path) throws IOException
    {
        try (InputStream stream = Files.newInputStream(path))
        {
            return new WaveReader().read(stream);
        }
    }

    private static void check(boolean value, String message)
    {
        if (!value) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception
    {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root))
        {
            paths.sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
                .forEach(path ->
                {
                    try { Files.deleteIfExists(path); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
        }
    }
}
