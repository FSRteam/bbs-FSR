package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.MinecraftSoundCaptureContractTest;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;
import mchorse.bbs_mod.utils.clips.Clips;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Dependency-light contracts for the owned export request and artifact
 * boundary.  These checks intentionally avoid Minecraft and FFmpeg so they
 * can run before an integration/media verification environment is available.
 */
public final class VideoExportLifecycleContractTest
{
    private VideoExportLifecycleContractTest()
    {}

    public static void runAll() throws Exception
    {
        MinecraftSoundCaptureContractTest.runAll();
        assertRichRequestAndArtifactMetadata();
        assertMotionBlurDurationMath();
        assertAudioFrameCeilingAndProfileRate();
        assertClaimProvenanceAndCollisionSafety();
        assertTypedResultStatuses();
        assertStrictMuxTemplateBindings();
        assertWorldSnapshotIsImmutableAndExplicit();
        assertWorldSnapshotFiltersDisabledAudioClips();
        assertF4AndF6CompletionFences();
        assertCaptureContractIsSnapshotted();
        assertPanelExportUsesWholeFilmForUnsetLoopRange();
    }

    private static void assertPanelExportUsesWholeFilmForUnsetLoopRange() throws Exception
    {
        String source = Files.readString(sourcePath("src/client/java/mchorse/bbs_mod/ui/film/PanelVideoExportSession.java"));
        String request = sourceSection(source, "protected VideoExportRequest createExportRequest", "protected AudioRenderResult renderFilmAudio");

        check(request.contains("looping")
                && request.contains("loopMin != this.editor.cameraEditor.clips.loopMax")
                && request.contains("this.start = 0")
                && request.contains("this.end = this.duration"),
            "an unset loop range no longer falls back to the complete Film export");
    }

    private static void assertWorldSnapshotIsImmutableAndExplicit()
    {
        UUID sessionId = UUID.randomUUID();
        List<WorldVideoExportSnapshot.AudioClipSnapshot> source = new ArrayList<>();
        source.add(new WorldVideoExportSnapshot.AudioClipSnapshot(2, "clip-2",
            Link.assets("audio/dialogue.wav"), 20L, 40L, -5L, 0.75F));

        WorldVideoExportSnapshot film = new WorldVideoExportSnapshot(sessionId, 9L,
            WorldVideoExportSnapshot.Kind.FILM_F6, "film-id", 0D, 60D, false,
            ChannelLayout.STEREO, "Sequence", source, 1920, 1080, 60D, 30D, 1);
        source.clear();

        check(film.sessionId().equals(sessionId) && film.generation() == 9L,
            "world snapshot lost its generation fence");
        check(film.width() == 1920 && film.height() == 1080
                && film.captureFrameRate() == 60D && film.outputFrameRate() == 30D
                && film.motionBlurPasses() == 1,
            "world snapshot lost capture/output timing metadata");
        check(film.isFilm() && film.audioClips().size() == 1,
            "world snapshot retained the caller's mutable clip list");
        check(film.audioClips().get(0).audio().toString().equals("assets:audio/dialogue.wav"),
            "world snapshot lost the immutable audio link value");
        expect(UnsupportedOperationException.class,
            () -> film.audioClips().add(film.audioClips().get(0)),
            "world snapshot exposed a mutable clip list");

        WorldVideoExportSnapshot live = new WorldVideoExportSnapshot(UUID.randomUUID(), 10L,
            WorldVideoExportSnapshot.Kind.LIVE_WORLD_F4, "", 0D, 0D, true,
            ChannelLayout.MONO, "", List.of(), 1280, 720, 60D, 60D, 0);
        check(!live.isFilm() && live.openEnd() && live.audioClips().isEmpty(),
            "F4 snapshot did not carry an explicit open-ended live-world marker");
    }

    private static void assertWorldSnapshotFiltersDisabledAudioClips() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-video-contract-snapshot-");

        try
        {
            Clips camera = new Clips("camera", null);
            AudioClip mono = audioClip("audio/enabled-mono.wav", 13, 29, -4, 0.65F);
            AudioClip disabled = audioClip("audio/disabled.wav", 31, 17, 8, 0.25F);
            AudioClip stereo = audioClip("audio/enabled-stereo.wav", 53, 41, 12, 0.9F);
            disabled.enabled.set(false);
            camera.addClip(mono);
            camera.addClip(disabled);
            camera.addClip(stereo);

            for (ChannelLayout layout : List.of(ChannelLayout.MONO, ChannelLayout.STEREO))
            {
                VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root,
                    "snapshot-" + layout.name().toLowerCase());
                VideoExportRequest request = new VideoExportRequest(artifacts.sessionId(), 23L,
                    7D, 101D, false, 60D, VideoExportAudioProfile.SAMPLE_RATE, 0,
                    layout, true, false, artifacts, "film-snapshot", 30D, 1,
                    false, 640, 360, VideoExportAudioProfile.DEFAULT_VIDEO_ARGUMENTS,
                    VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS, false);

                WorldVideoExportSnapshot snapshot = new WorldVideoExportSession()
                    .createSnapshot(request, camera);

                check(snapshot.layout() == layout,
                    "production snapshot lost the requested " + layout + " layout");
                check(snapshot.sourceStart() == 7D && snapshot.sourceEnd() == 101D
                        && !snapshot.openEnd(),
                    "production snapshot changed the exact export range");
                check(snapshot.audioClips().size() == 2,
                    "production snapshot retained a disabled audio clip");

                WorldVideoExportSnapshot.AudioClipSnapshot first = snapshot.audioClips().get(0);
                WorldVideoExportSnapshot.AudioClipSnapshot second = snapshot.audioClips().get(1);
                check(first.index() == 0 && second.index() == 2,
                    "production snapshot changed source indices while filtering audio clips");
                assertClipSnapshot(first, "assets:audio/enabled-mono.wav", 13L, 29L, -4L, 0.65F);
                assertClipSnapshot(second, "assets:audio/enabled-stereo.wav", 53L, 41L, 12L, 0.9F);
            }
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static AudioClip audioClip(String path, int tick, int duration, int offset, float volume)
    {
        AudioClip clip = new AudioClip();
        clip.audio.set(Link.assets(path));
        clip.tick.set(tick);
        clip.duration.set(duration);
        clip.offset.set(offset);
        clip.volume.set(volume);
        return clip;
    }

    private static void assertClipSnapshot(WorldVideoExportSnapshot.AudioClipSnapshot snapshot,
                                           String audio, long tick, long duration,
                                           long offset, float volume)
    {
        check(snapshot.audio() != null && snapshot.audio().toString().equals(audio),
            "production snapshot changed the enabled audio link");
        check(snapshot.tick() == tick && snapshot.duration() == duration
                && snapshot.sourceOffset() == offset && snapshot.volume() == volume,
            "production snapshot changed an enabled clip's exact range or volume");
    }

    private static void assertRichRequestAndArtifactMetadata() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-video-contract-rich-");
        VideoExportArtifacts artifacts = null;

        try
        {
            artifacts = VideoExportArtifacts.allocate(root, "rich");
            String videoArguments = "-f rawvideo -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf %FILTERS% %OUTPUT%";
            String muxArguments = VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS;
            VideoExportRequest request = new VideoExportRequest(artifacts.sessionId(), 7L,
                11D, 22D, false, 60D, VideoExportAudioProfile.SAMPLE_RATE, 1,
                ChannelLayout.STEREO, true, true, artifacts, "film-rich", 30D,
                3, true, 1920, 1080, videoArguments, muxArguments, true);

            artifacts.claim(artifacts.muxPartial());
            Files.writeString(artifacts.muxPartial(), "muxed-video");
            artifacts.markProduced(artifacts.muxPartial());
            artifacts.publishMuxedVideo();

            long audioFrames = request.audioFramesFor(20L);
            VideoExportArtifact artifact = artifacts.describe(request, true, 20L, audioFrames, false);

            check(artifact.finalVideo() != null && Files.isRegularFile(artifact.finalVideo()),
                "request-aware describe did not expose the published artifact");
            check(artifact.rawVideo() == null, "published artifact retained a stale raw path");
            check(artifact.sessionId().equals(request.sessionId()), "session id was not propagated");
            check(artifact.generation() == request.generation(), "generation was not propagated");
            check(artifact.sourceId().equals("film-rich"), "source id was not propagated");
            check(artifact.sourceStart() == 11D && artifact.sourceEnd() == 22D,
                "half-open source range was not propagated");
            check(!artifact.openEnd(), "closed request was described as open-ended");
            check(artifact.width() == 1920 && artifact.height() == 1080,
                "capture dimensions were not propagated");
            check(artifact.requestedLayout() == ChannelLayout.STEREO
                    && artifact.deliveredLayout() == ChannelLayout.STEREO,
                "requested/delivered layout was not propagated");
            check(artifact.audioPresent() && artifact.audioFrames() == 16_000L,
                "audio delivery metadata was not retained");
            check(artifact.sampleRate() == VideoExportAudioProfile.SAMPLE_RATE,
                "audio sample rate metadata disagrees with the delivery profile");
            check(VideoExportAudioProfile.CODEC.equals(artifact.codec())
                    && VideoExportAudioProfile.PROFILE.equals(artifact.profile())
                    && artifact.bitrate() == VideoExportAudioProfile.BITRATE,
                "final AAC metadata disagrees with the delivery profile");
            check(!artifact.deliveryVerified(),
                "unprobed delivery metadata was advertised as verified media fact");
            check(artifact.captureFrameRate() == request.captureFrameRate()
                    && artifact.outputFrameRate() == request.outputFrameRate()
                    && artifact.motionBlurPasses() == request.motionBlurPasses(),
                "capture/output rate or motion-blur snapshot was lost");
            check(request.heldFrames() == 3 && request.limitFrameRate() && request.encoderLog(),
                "capture cadence/log settings were not snapshotted");
            check(request.videoArguments().equals(videoArguments)
                    && request.muxArguments().equals(muxArguments)
                    && request.artifacts() == artifacts,
                "argument/artifact ownership snapshot was not retained");
            check(artifact.capturedFrames() == 20L && artifact.videoFrames() == 10L,
                "delivered/output frame counts were not normalized from the request");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertMotionBlurDurationMath() throws Exception
    {
        int[] outputRates = {24, 30, 60};

        for (int outputRate : outputRates)
        {
            for (int blur = 0; blur <= 3; blur++)
            {
                Path root = Files.createTempDirectory("bbs-video-contract-rate-");
                try
                {
                    VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root, "rate");
                    int divisor = 1 << blur;
                    long outputFrames = 11L;
                    long capturedFrames = outputFrames * divisor;
                    VideoExportRequest request = request(artifacts, outputRate * (double) divisor,
                        outputRate, blur, "rate", 0D, 11D, false, 640, 360,
                        "-f rawvideo %OUTPUT%", VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS);

                    check(request.outputFramesFor(capturedFrames) == outputFrames,
                        "motion-blur output frame count drifted at " + outputRate + "fps/pass=" + blur);
                    long expectedAudio = outputFrames * VideoExportAudioProfile.SAMPLE_RATE / outputRate;
                    check(request.audioFramesFor(capturedFrames) == expectedAudio,
                        "audio frame conversion drifted at " + outputRate + "fps/pass=" + blur);
                    double expectedDuration = capturedFrames / request.captureFrameRate();
                    check(Math.abs(request.durationSecondsFor(capturedFrames) - expectedDuration) < 1.0E-9D,
                        "capture duration was not derived from the snapshotted capture rate");

                    long nearBoundary = capturedFrames - Math.max(0, divisor - 1L);
                    long normalized = request.outputFramesFor(nearBoundary);
                    double outputDuration = normalized / request.outputFrameRate();
                    double captureDuration = nearBoundary / request.captureFrameRate();
                    check(Math.abs(outputDuration - captureDuration) <= 1D / outputRate + 1.0E-9D,
                        "motion-blur endpoint exceeded one output frame at " + outputRate + "fps/pass=" + blur);
                }
                finally
                {
                    deleteTree(root);
                }
            }
        }
    }

    private static void assertAudioFrameCeilingAndProfileRate() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-video-contract-audio-rate-");
        try
        {
            VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root, "audio-rate");
            VideoExportRequest request = request(artifacts, 29D, 29D, 0,
                "audio-rate", 0D, 1D, false, 640, 360,
                "-f rawvideo %OUTPUT%", VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS);

            check(request.audioFramesFor(1L) == 1656L,
                "audio duration conversion rounded down a partial PCM frame");

            expect(IllegalArgumentException.class,
                () -> new VideoExportRequest(artifacts.sessionId(), 8L, 0D, 1D, false,
                    24D, 44_100, 0, ChannelLayout.MONO, false, false, artifacts,
                    "rate", 24D, 1, false, 640, 360, "", "", false),
                "export request accepted a non-profile sample rate");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertClaimProvenanceAndCollisionSafety() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-video-contract-owner-");

        try
        {
            VideoExportArtifacts preExisting = VideoExportArtifacts.allocate(root, "pre-existing");
            Path userWav = preExisting.filmAudio();
            Files.writeString(userWav, "user-audio");

            expect(FileAlreadyExistsException.class, () -> preExisting.claim(userWav),
                "claim accepted a pre-existing user artifact");
            check(!preExisting.isClaimed(userWav), "failed claim changed ownership state");
            preExisting.cleanup();
            check(Files.exists(userWav), "cleanup deleted a pre-existing user artifact");

            VideoExportArtifacts unmarked = VideoExportArtifacts.allocate(root, "unmarked");
            Path unmarkedPath = unmarked.mixedAudio();
            unmarked.claim(unmarkedPath);
            Files.writeString(unmarkedPath, "created-after-claim-but-not-proven");
            unmarked.cleanup();
            check(Files.exists(unmarkedPath), "cleanup deleted an unproven claimed artifact");

            VideoExportArtifacts collision = VideoExportArtifacts.allocate(root, "collision");
            Path raw = collision.rawVideo();
            collision.claim(raw);
            Files.writeString(raw, "session-video");
            collision.markProduced(raw);
            Path finalVideo = collision.finalVideo();
            Files.writeString(finalVideo, "older-user-export");

            expect(FileAlreadyExistsException.class, collision::publishRawVideo,
                "publication replaced a pre-existing user export");
            check(Files.readString(finalVideo).equals("older-user-export"),
                "failed publication changed the pre-existing export");
            check(Files.readString(raw).equals("session-video"),
                "failed publication discarded the owned recovery video");
            collision.cleanup(true);
            check(Files.exists(raw), "preserveRaw cleanup removed the recovery video");

            VideoExportArtifacts raced = VideoExportArtifacts.allocate(root, "race");
            Path racedPath = raced.normalizedAudio();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try
            {
                Future<Boolean> first = executor.submit(() -> tryClaimAfterBarrier(raced, racedPath, ready, release));
                Future<Boolean> second = executor.submit(() -> tryClaimAfterBarrier(raced, racedPath, ready, release));
                ready.await();
                release.countDown();
                check(first.get() ^ second.get(), "concurrent claims both acquired one artifact");
            }
            finally
            {
                executor.shutdownNow();
            }
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static boolean tryClaimAfterBarrier(VideoExportArtifacts artifacts, Path path,
                                                 CountDownLatch ready, CountDownLatch release)
        throws Exception
    {
        ready.countDown();
        release.await();

        try
        {
            artifacts.claim(path);
            return true;
        }
        catch (IOException expected)
        {
            return false;
        }
    }

    private static void assertTypedResultStatuses()
    {
        VideoExportArtifact empty = VideoExportArtifact.empty(ChannelLayout.MONO);
        VideoExportResult success = new VideoExportResult(VideoExportResult.Kind.SUCCESS,
            VideoExportResult.Stage.COMPLETE, empty, null, List.of(), "ok");
        VideoExportResult degraded = new VideoExportResult(VideoExportResult.Kind.DEGRADED,
            VideoExportResult.Stage.AUDIO_MIX, empty, new IOException("missing sound"), List.of(), "degraded");
        VideoExportResult cancelled = new VideoExportResult(VideoExportResult.Kind.CANCELLED,
            VideoExportResult.Stage.CANCELLED, empty, null, List.of(), "cancelled");
        VideoExportResult muxFailure = new VideoExportResult(VideoExportResult.Kind.MUX_FAILED,
            VideoExportResult.Stage.MUX, empty, new IOException("mux"), List.of(), "mux");
        VideoExportResult missingResource = new VideoExportResult(VideoExportResult.Kind.DEGRADED,
            VideoExportResult.Stage.MISSING_RESOURCE, empty, new IOException("missing resource"),
            List.of(), "missing resource");

        check(success.isSuccess() && !success.isAborted(), "SUCCESS status was not terminal success");
        check(!degraded.isSuccess() && degraded.isDegraded() && degraded.isAborted(),
            "DEGRADED status was mapped to success");
        check(cancelled.isAborted() && cancelled.failureKind() == VideoExportResult.FailureKind.NONE,
            "CANCELLED status carried a spurious failure kind");
        check(muxFailure.isAborted() && muxFailure.failureKind() == VideoExportResult.FailureKind.MUX,
            "MUX failure did not retain its typed stage");
        check(missingResource.isDegraded()
                && missingResource.failureKind() == VideoExportResult.FailureKind.MISSING_RESOURCE,
            "missing-resource failure was folded into a generic audio result");
        expect(UnsupportedOperationException.class,
            () -> success.cleanupFailures().add(new IOException("immutable")),
            "typed cleanup causes were not immutable");
    }

    private static void assertStrictMuxTemplateBindings()
    {
        String valid = VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS;
        VideoExportAudioProfile.validateTemplate(valid, true, true);

        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid.replace("-i %VIDEO%", "-i fixed.mp4"), true, true),
            "mux template accepted an input path instead of the owned video token");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid.replace("-i %VIDEO% -i %AUDIO_TRACK%",
                "-i %AUDIO_TRACK% -i %VIDEO%"), true, true),
            "mux template accepted reversed input ordering");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid.replace("-ac %AUDIO_CHANNELS%", "-ac 2"), true, true),
            "mux template accepted a hard-coded channel count");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid + " -shortest", true, true),
            "mux template accepted -shortest duration masking");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid + " -y", true, true),
            "mux template accepted replacement output semantics");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid.replace("%OUTPUT%", "%OUTPUT%.bak"), true, true),
            "mux template accepted an output token embedded in an unowned suffix");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid.replace("%OUTPUT%", "%OUTPUT% %OUTPUT%"), true, true),
            "mux template accepted duplicate output bindings");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(
                valid.replace("%OUTPUT%", "foreign.mp4 %OUTPUT%"), true, true),
            "mux template accepted an extra hard-coded output");
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(
                valid.replace("%OUTPUT%", "-metadata %OUTPUT%"), true, true),
            "mux template accepted its output placeholder as metadata");
        VideoExportAudioProfile.validateTemplate(
            valid.replace("%OUTPUT%", "-metadata title=bbs %OUTPUT%"), true, true);
        expect(IllegalArgumentException.class,
            () -> VideoExportAudioProfile.validateTemplate(valid + " %UNBOUND%", true, true),
            "mux template accepted an unknown placeholder");
    }

    private static void assertF4AndF6CompletionFences() throws Exception
    {
        String client = Files.readString(sourcePath("src/client/java/mchorse/bbs_mod/BBSModClient.java"));
        check(client.contains("addWorldVideoExportListener(WorldVideoExportListener listener)")
                && client.contains("removeWorldVideoExportListener(WorldVideoExportListener listener)"),
            "world export completion bridge is still a clobbering single-listener API");
        String f4 = sourceSection(client, "private static void keyRecordVideo(Minecraft mc)",
            "private static KeyMapping createKey");
        check(f4.contains("VideoExportRequest activeRequest = worldExportSession.getActiveExportRequest()")
                && f4.contains("activeRequest == null")
                && f4.contains("!activeRequest.openEnd()")
                && f4.contains("!activeRequest.sourceId().isEmpty()")
                && !f4.contains("worldExportSession.getFilmId()"),
            "F4 key path is not fenced by the immutable open-ended request");
        check(f4.contains("worldExportSession.stop();") && f4.contains("worldExportSession.cancel();"),
            "F4 key path lost its distinct stop/cancel branches");
        check(f4.indexOf("worldExportSession.stop();") < f4.indexOf("worldExportSession.cancel();"),
            "F4 warm-up cancellation branch precedes normal completion");

        String f6 = sourceSection(client, "private static void keyPlayFilmAndRecord()",
            "private static void keyPauseFilm");
        check(f6.contains("VideoExportRequest activeRequest = worldExportSession.getActiveExportRequest()")
                && f6.contains("activeRequest != null")
                && f6.contains("!activeRequest.openEnd()")
                && f6.contains("Objects.equals(filmId, activeRequest.sourceId())")
                && !f6.contains("worldExportSession.getFilmId()"),
            "F6 toggle-off is not fenced to the immutable owning-film request");
        check(f6.contains("worldExportSession.cancel();"),
            "F6 toggle-off no longer uses cancellation");

        String world = Files.readString(sourcePath("src/client/java/mchorse/bbs_mod/film/WorldVideoExportSession.java"));
        String request = sourceSection(world, "protected VideoExportRequest createExportRequest",
            "protected AudioRenderResult renderFilmAudio");
        check(request.contains("createOwnedRequest(this.filmId, 0D, duration, false"),
            "F6 request origin/end is no longer shared with playback");
        check(request.contains("createOwnedRequest(\"\", 0D, 0D, true"),
            "F4 request no longer uses an open end");

        check(world.contains("WorldVideoExportSnapshot.Kind.LIVE_WORLD_F4")
                && world.contains("WorldVideoExportSnapshot.Kind.FILM_F6"),
            "F4/F6 snapshot kinds are not explicit");
        check(world.contains("CopyOnWriteArrayList<WorldVideoExportListener>"),
            "world export listeners are not maintained as an additive collection");

        String warmup = sourceSection(world, "protected boolean isWarmupReady()",
            "protected void onRecordingStarted");
        check(warmup.contains("worldController.tick = 0"),
            "F6 warm-up lost its local rewind fence");
        check(warmup.contains("ActionState.PAUSE, 0"),
            "F6 warm-up lost its remote rewind fence");
        String started = sourceSection(world, "protected void onRecordingStarted()",
            "protected boolean isFinished");
        check(started.contains("ActionState.PLAY, tick"),
            "F6 playback did not resume from the fenced origin");

        String session = Files.readString(sourcePath("src/client/java/mchorse/bbs_mod/film/VideoExportSession.java"));
        String recorderStart = sourceSection(session, "private void beginRecording()", "public final void stop()");
        check(recorderStart.contains("this.exportRequest.frameRate()")
                && recorderStart.contains("this.exportRequest.motionBlurPasses()")
                && recorderStart.contains("this.exportRequest.heldFrames()")
                && recorderStart.contains("this.exportRequest.limitFrameRate()")
                && recorderStart.contains("this.exportRequest.videoArguments()")
                && recorderStart.contains("this.exportRequest.encoderLog()")
                && recorderStart.contains("this.exportRequest.layout()"),
            "recorder startup rereads mutable settings instead of the immutable request");
    }

    private static void assertCaptureContractIsSnapshotted() throws Exception
    {
        String session = Files.readString(sourcePath("src/client/java/mchorse/bbs_mod/film/VideoExportSession.java"));
        String begin = sourceSection(session, "private void beginRecording()", "public final void stop()");
        check(begin.contains("minecraftCaptureFailure")
                && begin.contains("captureRequested && !this.capturingMinecraftSounds")
                && begin.contains("this.isMinecraftSoundCaptureAvailable()")
                && begin.contains("this.startMinecraftSoundCapture()"),
            "owned audio export can silently succeed without a capture backend");
        check(begin.contains("snapshotLegacyAudioContract()"),
            "legacy audio export rereads its mutable contract at finish time");
        String legacyFinish = sourceSection(session, "private void finish(Result requested",
            "private void failBeforeStart");
        check(legacyFinish.contains("if (this.minecraftCaptureFailure != null)"),
            "legacy export can ignore a missing requested capture backend");
        check(legacyFinish.contains("captureResultFailure(capture)"),
            "legacy export ignores a typed capture failure after end()");
        String ownedFinish = sourceSection(session, "private void finishOwned(",
            "private VideoExportResult runOwnedPostprocess");
        check(ownedFinish.contains("finishOwnedMinecraftSoundCapture()")
                && ownedFinish.contains("snapshot.failure()"),
            "owned export ignores a typed capture failure after end()");

        String finish = sourceSection(session, "private Throwable finishCapturedSounds(",
            "private LegacyAudioSnapshot snapshotLegacyAudioContract");
        check(finish.contains("recordedFrames <= 0")
                && finish.contains("without a delivered video frame"),
            "legacy audio export treats a zero-frame recording as success");
        check(finish.contains("snapshotMinecraftSoundResources")
                && finish.contains("mixMinecraftSoundSources")
                && finish.contains("snapshot.sampleRate()")
                && finish.contains("snapshot.layout()")
                && finish.contains("snapshot.muxArguments()"),
            "legacy audio export did not use its immutable audio contract");
        check(!finish.contains("48000"),
            "legacy audio export still hard-codes its sample rate at finish time");
    }

    private static VideoExportRequest request(VideoExportArtifacts artifacts, double captureRate,
                                              double outputRate, int blur, String sourceId,
                                              double start, double end, boolean openEnd,
                                              int width, int height, String videoArguments,
                                              String muxArguments)
    {
        return new VideoExportRequest(artifacts.sessionId(), 7L, start, end, openEnd,
            captureRate, VideoExportAudioProfile.SAMPLE_RATE, blur, ChannelLayout.STEREO,
            true, true, artifacts, sourceId, outputRate, 1, false, width, height,
            videoArguments, muxArguments, false);
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

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root))
        {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(path ->
                {
                    try { Files.deleteIfExists(path); }
                    catch (IOException e) { throw new TestCleanupException(e); }
                });
        }
        catch (TestCleanupException e)
        {
            throw e.cause;
        }
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action, String message)
    {
        try
        {
            action.run();
        }
        catch (Throwable error)
        {
            if (type.isInstance(error)) return;
            throw new AssertionError(message + ": got " + error, error);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }

    private static final class TestCleanupException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
        private final IOException cause;

        private TestCleanupException(IOException cause)
        {
            this.cause = cause;
        }
    }

    private static void check(boolean value, String message)
    {
        if (!value) throw new AssertionError(message);
    }
}
