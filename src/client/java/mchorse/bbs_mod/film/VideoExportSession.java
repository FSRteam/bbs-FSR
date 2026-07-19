package mchorse.bbs_mod.film;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.MinecraftSoundCapture;
import mchorse.bbs_mod.audio.MinecraftSoundMixer;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.VideoExportUtils;
import mchorse.bbs_mod.utils.VideoExportProcess;
import mchorse.bbs_mod.utils.VideoMuxer;
import mchorse.bbs_mod.utils.VideoRecorder;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Owns the lifecycle of one video export, including the optional warm-up,
 * the shared recorder, teardown, and a one-shot completion callback.
 */
public abstract class VideoExportSession
{
    private static final Logger LOGGER = LogUtils.getLogger();

    public enum State
    {
        IDLE,
        WARMUP,
        RECORDING
    }

    public enum Result
    {
        SUCCESS,
        CANCELLED,
        FAILED
    }

    protected State state = State.IDLE;
    protected long warmupEndsAtMs;

    protected File audioFile;
    private File temporaryAudioFile;
    protected int textureId;
    protected int width;
    protected int height;

    private FinishedListener finishedListener;
    private Result lastResult;
    private Throwable lastFailure;
    private VideoRecorder reservedRecorder;
    private FinishedListener deferredFinishedListener;
    private boolean deferredFinishedAborted;
    private boolean beginning;
    private boolean finishing;
    private String movieName;
    private File deferredAudioFile;
    private double recordingFrameRate;
    private long recordingStartedAtMs;
    private boolean capturingMinecraftSounds;

    protected VideoRecorder getRecorder()
    {
        return BBSModClient.getVideoRecorder();
    }

    public boolean isExporting()
    {
        return this.state != State.IDLE;
    }

    public boolean isWarmingUp()
    {
        return this.state == State.WARMUP;
    }

    public boolean isRecording()
    {
        VideoRecorder recorder = this.getSessionRecorder();

        return this.state == State.RECORDING && recorder != null && recorder.isRecording();
    }

    public Result getLastResult()
    {
        return this.lastResult;
    }

    public Throwable getLastFailure()
    {
        return this.lastFailure;
    }

    public long getWarmupRemainingMs()
    {
        if (!this.isWarmingUp())
        {
            return 0L;
        }

        return Math.max(0L, this.warmupEndsAtMs - System.currentTimeMillis());
    }

    public void setFinishedListener(FinishedListener listener)
    {
        this.finishedListener = listener;
    }

    /** Reserve the shared encoder before a deferred export-resolution frame. */
    public final boolean reserveRecorder()
    {
        if (this.isExporting() || this.beginning || this.finishing)
        {
            return false;
        }

        if (this.reservedRecorder != null)
        {
            return true;
        }

        VideoRecorder recorder = this.getRecorder();

        if (recorder == null || recorder.isRecording() || !recorder.tryReserve(this))
        {
            return false;
        }

        this.reservedRecorder = recorder;

        return true;
    }

    protected final boolean begin(int textureId, int width, int height, long delayMs)
    {
        if (!this.reserveRecorder())
        {
            return false;
        }

        this.beginning = true;
        this.lastResult = null;
        this.lastFailure = null;
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.audioFile = null;

        try
        {
            if (!this.prepare())
            {
                this.failBeforeStart(null);

                return false;
            }

            /* Mark the session active before applying any target-side effects. */
            this.state = State.WARMUP;
            this.applyExportTarget();

            this.warmupEndsAtMs = System.currentTimeMillis() + Math.max(0L, delayMs);

            if (delayMs > 0L || !this.isWarmupReady())
            {
                this.onWarmupStarted();
            }
            else
            {
                this.beginRecording();
            }

            /* The finally block may notify a listener which starts a new
             * export. Return only whether this begin attempt stayed active. */
            return this.state != State.IDLE;
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to prepare video export", e);

            if (this.state == State.IDLE)
            {
                this.failBeforeStart(e);
            }
            else
            {
                this.fail(e);
            }

            return false;
        }
        finally
        {
            this.beginning = false;
            this.dispatchDeferredFinishedListener();
        }
    }

    public final void update()
    {
        try
        {
            if (this.state == State.WARMUP)
            {
                if (this.shouldAbortWarmup())
                {
                    this.cancel();

                    return;
                }

                if (!this.isWarmupReady() || System.currentTimeMillis() < this.warmupEndsAtMs)
                {
                    return;
                }

                this.beginRecording();
            }
            else if (this.state == State.RECORDING)
            {
                VideoRecorder recorder = this.getSessionRecorder();

                if (recorder == null || !recorder.checkRecordingHealth())
                {
                    Throwable failure = recorder == null ? null : recorder.getFailure();

                    this.fail(failure == null ? new IllegalStateException("FFmpeg stopped before video export completed") : failure);

                    return;
                }

                if (this.isFinished())
                {
                    this.stop();
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Video export session update failed", e);
            this.fail(e);
        }
    }

    private void beginRecording()
    {
        VideoRecorder recorder = this.getSessionRecorder();

        if (recorder == null)
        {
            this.fail(new IllegalStateException("Video recorder is unavailable"));

            return;
        }

        /* Enter the state before starting so every failure takes the teardown path. */
        this.state = State.RECORDING;

        String movieName = this.getMovieName();

        if (movieName == null || movieName.isEmpty())
        {
            movieName = StringUtils.createTimestampFilename();
        }

        MinecraftSoundCapture capture = this.getMinecraftSoundCapture();
        this.capturingMinecraftSounds = this.shouldCaptureMinecraftSounds() && capture != null;
        File encoderAudio = this.audioFile;

        if (this.capturingMinecraftSounds)
        {
            this.deferredAudioFile = this.audioFile;
            encoderAudio = null;
        }

        try
        {
            if (!recorder.tryStartRecording(movieName, encoderAudio, this.textureId, this.width, this.height))
            {
                Throwable failure = recorder.getFailure();

                this.fail(failure == null ? new IllegalStateException("FFmpeg failed to start") : failure);

                return;
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to start video export", e);
            this.fail(e);

            return;
        }

        try
        {
            this.movieName = movieName;
            this.recordingStartedAtMs = System.currentTimeMillis();

            if (this.capturingMinecraftSounds)
            {
                this.recordingFrameRate = BBSRendering.getVideoFrameRate();
                capture.begin();
            }

            this.onRecordingStarted();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to enter the recording phase", e);
            this.fail(e);
        }
    }

    public final void stop()
    {
        this.finish(Result.SUCCESS, null);
    }

    public final void cancel()
    {
        if (this.state == State.IDLE)
        {
            if (!this.beginning && !this.finishing)
            {
                this.releaseRecorderReservation();
            }

            return;
        }

        this.finish(Result.CANCELLED, null);
    }

    /** Release a deferred-start lease without cancelling a recording that won the race. */
    public final void cancelPendingReservation()
    {
        if (this.state == State.IDLE && !this.beginning && !this.finishing)
        {
            this.releaseRecorderReservation();
        }
    }

    protected final void fail(Throwable failure)
    {
        this.finish(Result.FAILED, failure);
    }

    private void finish(Result requested, Throwable failure)
    {
        if (this.state == State.IDLE || this.finishing)
        {
            return;
        }

        this.finishing = true;
        State previousState = this.state;
        Result result = requested;
        VideoRecorder recorder = this.getSessionRecorder();
        int recordedFrames = recorder == null ? 0 : recorder.getCounter();

        try
        {
            if (previousState == State.RECORDING)
            {
                if (recorder == null)
                {
                    result = Result.FAILED;
                    failure = appendFailure(failure, new IllegalStateException("Video recorder disappeared during export"));
                }
                else
                {
                    VideoExportProcess.Outcome outcome;

                    if (requested == Result.SUCCESS)
                    {
                        outcome = recorder.completeRecording();
                    }
                    else if (requested == Result.CANCELLED)
                    {
                        outcome = recorder.cancelRecording();
                    }
                    else
                    {
                        outcome = recorder.failRecording(failure);
                    }

                    result = mapOutcome(outcome);

                    if (result == Result.FAILED)
                    {
                        failure = appendFailure(failure, recorder.getFailure());
                    }
                }
            }
            else if (requested == Result.SUCCESS)
            {
                result = Result.FAILED;
                failure = appendFailure(failure, new IllegalStateException("Video export completed before FFmpeg started"));
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to stop video export cleanly", e);
            result = Result.FAILED;
            failure = appendFailure(failure, e);

            if (recorder != null)
            {
                try
                {
                    recorder.failRecording(e);
                }
                catch (Exception | LinkageError cleanupError)
                {
                    LOGGER.error("Failed to abort video recorder after a session error", cleanupError);
                    failure = appendFailure(failure, cleanupError);
                }
            }
        }

        this.state = State.IDLE;

        if (this.capturingMinecraftSounds)
        {
            MinecraftSoundCapture capture = this.getMinecraftSoundCapture();

            if (capture != null)
            {
                capture.end();

                if (result == Result.SUCCESS)
                {
                    this.finishCapturedSounds(capture, recordedFrames);
                }
            }
        }

        try
        {
            this.teardown(result != Result.SUCCESS);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to tear down video export session", e);
            result = Result.FAILED;
            failure = appendFailure(failure, e);
        }

        try
        {
            this.reset();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to reset video export session", e);
            result = Result.FAILED;
            failure = appendFailure(failure, e);
        }
        finally
        {
            this.releaseRecorderReservation();
        }

        FinishedListener listener = this.finishedListener;
        this.finishedListener = null;
        this.lastResult = result;
        this.lastFailure = result == Result.FAILED ? failure : null;
        this.finishing = false;

        if (result == Result.SUCCESS && recorder != null)
        {
            recorder.announceSuccessfulCompletion();
        }

        this.notifyFinishedListener(listener, result != Result.SUCCESS);

        if (result == Result.FAILED && failure != null)
        {
            LOGGER.error("Video export failed", failure);
        }
    }

    private void failBeforeStart(Throwable failure)
    {
        Throwable terminalFailure = failure;

        try
        {
            this.reset();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to reset video export after preparation failure", e);
            terminalFailure = appendFailure(terminalFailure, e);
        }
        finally
        {
            this.releaseRecorderReservation();
        }

        this.lastResult = Result.FAILED;
        this.lastFailure = terminalFailure == null ? new IllegalStateException("Video export preparation failed") : terminalFailure;
        FinishedListener listener = this.finishedListener;

        this.finishedListener = null;
        this.notifyFinishedListener(listener, true);
    }

    private void notifyFinishedListener(FinishedListener listener, boolean aborted)
    {
        if (listener == null)
        {
            return;
        }

        if (this.beginning)
        {
            this.deferredFinishedListener = listener;
            this.deferredFinishedAborted = aborted;

            return;
        }

        try
        {
            listener.onFinished(aborted);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Video export completion listener failed", e);
        }
    }

    private void dispatchDeferredFinishedListener()
    {
        FinishedListener listener = this.deferredFinishedListener;
        boolean aborted = this.deferredFinishedAborted;

        this.deferredFinishedListener = null;
        this.deferredFinishedAborted = false;
        this.notifyFinishedListener(listener, aborted);
    }

    private void releaseRecorderReservation()
    {
        if (this.reservedRecorder != null)
        {
            this.reservedRecorder.releaseReservation(this);
            this.reservedRecorder = null;
        }
    }

    private VideoRecorder getSessionRecorder()
    {
        return this.reservedRecorder == null ? this.getRecorder() : this.reservedRecorder;
    }

    private static Result mapOutcome(VideoExportProcess.Outcome outcome)
    {
        if (outcome == VideoExportProcess.Outcome.SUCCEEDED)
        {
            return Result.SUCCESS;
        }

        if (outcome == VideoExportProcess.Outcome.CANCELLED)
        {
            return Result.CANCELLED;
        }

        return Result.FAILED;
    }

    private static Throwable appendFailure(Throwable current, Throwable next)
    {
        if (next == null)
        {
            return current;
        }

        if (current == null)
        {
            return next;
        }

        if (current != next)
        {
            current.addSuppressed(next);
        }

        return current;
    }

    /**
     * Run independent teardown actions without letting one failure skip the
     * remaining session-owned cleanup. The first failure is rethrown after
     * later failures have been attached as suppressed exceptions.
     */
    protected final void runCleanupSteps(CleanupStep... steps)
    {
        Throwable failure = null;

        for (CleanupStep step : steps)
        {
            try
            {
                step.run();
            }
            catch (Exception | LinkageError e)
            {
                failure = appendFailure(failure, e);
            }
        }

        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }

        if (failure instanceof LinkageError linkageError)
        {
            throw linkageError;
        }

        if (failure != null)
        {
            throw new IllegalStateException("Video export cleanup failed", failure);
        }
    }

    private void reset()
    {
        try
        {
            this.deleteTemporaryAudio();
        }
        finally
        {
            this.state = State.IDLE;
            this.warmupEndsAtMs = 0L;
            this.audioFile = null;
            this.textureId = 0;
            this.width = 0;
            this.height = 0;
            this.movieName = null;
            this.deferredAudioFile = null;
            this.recordingFrameRate = 0D;
            this.recordingStartedAtMs = 0L;
            this.capturingMinecraftSounds = false;
        }
    }

    private void finishCapturedSounds(MinecraftSoundCapture capture, int recordedFrames)
    {
        File mixed = null;
        boolean merged = false;

        try
        {
            if (recordedFrames <= 0)
            {
                return;
            }

            File folder = BBSRendering.getVideoFolder();
            Files.createDirectories(folder.toPath());
            mixed = new File(folder, this.movieName + ".wav");

            if (!MinecraftSoundMixer.mixToFile(mixed, capture.getSounds(), capture.getFrames(), readWave(this.deferredAudioFile),
                48000, this.recordingFrameRate, recordedFrames))
            {
                return;
            }

            File video = this.findRecordedVideo(folder);

            if (video == null || VideoMuxer.mux(video, mixed, this.movieName, BBSSettings.videoArgumentsMux.get()) == null)
            {
                LOGGER.warn("Minecraft sound mix completed, but FFmpeg could not merge it into {}; keeping {} for recovery",
                    this.movieName, mixed);

                return;
            }

            merged = true;
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("Failed to mix captured Minecraft sounds into {}", this.movieName, e);
        }
        finally
        {
            if (merged && mixed != null && mixed.exists() && !mixed.delete())
            {
                LOGGER.warn("Failed to delete temporary Minecraft sound mix {}", mixed);
            }
        }
    }

    private static Wave readWave(File file)
    {
        if (file == null || !file.isFile()) return null;

        try (InputStream stream = new FileInputStream(file))
        {
            return new WaveReader().read(stream);
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to read the deferred film audio track", e);
            return null;
        }
    }

    private File findRecordedVideo(File folder)
    {
        File[] files = folder.listFiles();
        if (files == null || this.movieName == null) return null;
        String prefix = this.movieName + ".";
        long notBefore = this.recordingStartedAtMs - 10_000L;
        File found = null;

        for (File file : files)
        {
            if (!file.isFile() || !file.getName().startsWith(prefix) || isExportArtifact(file.getName().substring(prefix.length())) || file.lastModified() < notBefore) continue;
            if (found == null || file.lastModified() > found.lastModified()) found = file;
        }

        return found;
    }

    protected static boolean isExportArtifact(String rest)
    {
        String lower = rest.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("wav") || lower.equals("log") || lower.endsWith(".log") || lower.startsWith("tmp.");
    }

    /** Create and track a uniquely owned WAV path for this session. */
    protected final File createTemporaryAudio() throws java.io.IOException
    {
        this.deleteTemporaryAudio();
        this.temporaryAudioFile = VideoExportUtils.createTemporaryAudioFile(
            mchorse.bbs_mod.client.BBSRendering.getVideoFolder()
        );

        return this.temporaryAudioFile;
    }

    /** Use the tracked temporary WAV as ffmpeg's audio input. */
    protected final void attachTemporaryAudio(File file)
    {
        if (file != this.temporaryAudioFile)
        {
            throw new IllegalArgumentException("Audio file is not owned by this export session");
        }

        this.audioFile = file;
    }

    /** Delete only the uniquely created file owned by this session. */
    protected final void deleteTemporaryAudio()
    {
        File temporary = this.temporaryAudioFile;

        if (!VideoExportUtils.tryDeleteTemporaryFile(temporary))
        {
            throw new IllegalStateException("Failed to delete temporary export audio " + temporary);
        }

        this.temporaryAudioFile = null;
        this.audioFile = null;
    }

    /** Base filename without an extension. Film-panel exports override this for templates. */
    protected String getMovieName()
    {
        return StringUtils.createTimestampFilename();
    }

    /** Shared settings integration enables this without changing the session lifecycle. */
    protected boolean shouldCaptureMinecraftSounds()
    {
        return BBSSettings.videoExportMinecraftSounds != null && BBSSettings.videoExportMinecraftSounds.get();
    }

    /** Shared client integration supplies the singleton capture listener. */
    protected MinecraftSoundCapture getMinecraftSoundCapture()
    {
        return BBSModClient.getMinecraftSoundCapture();
    }

    protected abstract boolean prepare();

    protected void applyExportTarget()
    {}

    protected void onWarmupStarted()
    {}

    protected boolean shouldAbortWarmup()
    {
        return false;
    }

    protected boolean isWarmupReady()
    {
        return true;
    }

    protected abstract void onRecordingStarted();

    protected abstract boolean isFinished();

    protected abstract void teardown(boolean cancelled);

    @FunctionalInterface
    protected interface CleanupStep
    {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface FinishedListener
    {
        /** {@code aborted} is true for cancellation and failure, never success. */
        void onFinished(boolean aborted);
    }
}
