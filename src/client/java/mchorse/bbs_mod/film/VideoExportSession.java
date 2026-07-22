package mchorse.bbs_mod.film;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.MinecraftSoundCapture;
import mchorse.bbs_mod.audio.MinecraftSoundMixer;
import mchorse.bbs_mod.audio.AudioRenderResult;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.VideoExportUtils;
import mchorse.bbs_mod.utils.VideoExportProcess;
import mchorse.bbs_mod.utils.VideoMuxer;
import mchorse.bbs_mod.utils.VideoRecorder;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

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
        RECORDING,
        FINALIZING,
        POSTPROCESSING
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
    private TypedFinishedListener deferredTypedFinishedListener;
    private VideoExportResult deferredTypedResult;
    private boolean beginning;
    private boolean beginningReservation;
    private boolean finishing;
    private String movieName;
    private File deferredAudioFile;
    private double recordingFrameRate;
    private long recordingStartedAtMs;
    private boolean capturingMinecraftSounds;
    private Throwable minecraftCaptureFailure;
    private LegacyAudioSnapshot legacyAudioSnapshot;

    /* New owned-pipeline state.  A null request keeps the legacy test/addon
     * adapter on the original synchronous lifecycle. */
    private VideoExportRequest exportRequest;
    private VideoExportArtifacts exportArtifacts;
    private VideoExportResult lastExportResult;
    private TypedFinishedListener typedFinishedListener;
    /** Persistent additive observers; the legacy setter above remains one-shot. */
    private final CopyOnWriteArrayList<TypedFinishedListener> finishedResultListeners = new CopyOnWriteArrayList<>();
    private VideoExportResult deferredSubscriberResult;
    private long generationCounter;
    private long activeGeneration;
    /** Cancellation intent survives the FINALIZING -> POSTPROCESSING handoff. */
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private AtomicBoolean postprocessCancelled;
    private java.util.concurrent.Future<?> postprocessFuture;
    private PostprocessContext activePostprocessContext;
    private int deliveredFrames;
    private List<MinecraftSoundCapture.CapturedSound> capturedSounds = List.of();
    private List<MinecraftSoundCapture.ListenerFrame> capturedFrames = List.of();
    private volatile double postprocessProgress;
    private final Map<Long, VideoExportArtifacts> generationArtifacts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> clientCallbacks = new ConcurrentLinkedQueue<>();
    private record PostprocessContext(
        long generation,
        VideoExportRequest request,
        VideoExportArtifacts artifacts,
        int deliveredFrames,
        List<MinecraftSoundCapture.CapturedSound> capturedSounds,
        List<MinecraftSoundCapture.ListenerFrame> capturedFrames,
        MinecraftSoundMixer.SoundResourceFiles resourceSnapshot,
        AtomicBoolean cancelled,
        AtomicBoolean executionClaimed,
        AtomicBoolean publicationCommitted,
        AtomicBoolean terminalDelivered,
        AtomicBoolean cleanupDone)
    {}
    /** Immutable contract captured before the legacy synchronous path starts. */
    private record LegacyAudioSnapshot(Path videoFolder, ChannelLayout layout,
                                       int sampleRate, String muxArguments)
    {}
    private static final ExecutorService POSTPROCESS_EXECUTOR = Executors.newSingleThreadExecutor((runnable) ->
    {
        Thread thread = new Thread(runnable, "bbs-video-export-postprocess");
        thread.setDaemon(true);
        return thread;
    });

    protected VideoRecorder getRecorder()
    {
        return BBSModClient.getVideoRecorder();
    }

    public boolean isExporting()
    {
        /* The beginning flag is the ownership fence before a subclass has
         * entered WARMUP. It prevents re-entrant integrations from mutating a
         * second request while the first request is still being prepared. */
        return this.state != State.IDLE || this.beginning;
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

    /** Typed terminal result; the legacy {@link #getLastResult()} remains stable. */
    public VideoExportResult getLastExportResult()
    {
        return this.lastExportResult;
    }

    public double getPostprocessProgress()
    {
        return this.postprocessProgress;
    }

    protected final VideoExportRequest getExportRequest()
    {
        return this.exportRequest;
    }

    /** Immutable active request for typed integrations which need an early ownership fence. */
    public final VideoExportRequest getActiveExportRequest()
    {
        return this.exportRequest;
    }

    /** Generation that the next successfully allocated owned request will claim. */
    protected final long getNextExportGeneration()
    {
        return this.generationCounter + 1L;
    }

    public void setFinishedResultListener(TypedFinishedListener listener)
    {
        this.typedFinishedListener = listener;
    }

    /**
     * Add a persistent typed observer without replacing the legacy one-shot
     * listener.  The same immutable result instance is delivered to every
     * observer for a terminal generation.
     */
    public final boolean addFinishedResultListener(TypedFinishedListener listener)
    {
        if (listener == null)
        {
            return false;
        }

        return this.finishedResultListeners.addIfAbsent(listener);
    }

    public final boolean removeFinishedResultListener(TypedFinishedListener listener)
    {
        return listener != null && this.finishedResultListeners.remove(listener);
    }

    /** Convenience removable handle for integrations that own a subscription. */
    public final Subscription subscribeFinishedResult(TypedFinishedListener listener)
    {
        if (!this.addFinishedResultListener(listener))
        {
            return Subscription.EMPTY;
        }

        return () -> this.removeFinishedResultListener(listener);
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
        if ((this.isExporting() && !this.beginningReservation)
            || (this.beginning && !this.beginningReservation) || this.finishing)
        {
            return false;
        }

        if (this.reservedRecorder != null)
        {
            return this.reservedRecorder.tryReserve(this);
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
        if (this.isExporting() || this.beginning || this.finishing)
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
        this.lastExportResult = null;
        this.deferredSubscriberResult = null;
        this.cancelRequested.set(false);
        this.exportRequest = null;
        this.exportArtifacts = null;
        this.deliveredFrames = 0;
        this.capturedSounds = List.of();
        this.capturedFrames = List.of();
        this.minecraftCaptureFailure = null;
        this.legacyAudioSnapshot = null;
        this.postprocessProgress = 0D;

        try
        {
            /* Snapshot timing/layout/artifact ownership before this begin call
             * acquires the shared recorder.  A pre-reserved deferred lease is
             * retained but cannot alter this immutable snapshot. */
            this.exportRequest = this.createExportRequest(width, height);
            this.exportArtifacts = this.exportRequest == null ? null : this.exportRequest.artifacts();
            this.activeGeneration = this.exportRequest == null
                ? ++this.generationCounter
                : this.exportRequest.generation();
            if (this.exportArtifacts != null)
            {
                this.generationArtifacts.put(this.activeGeneration, this.exportArtifacts);
            }

            boolean reserved;
            this.beginningReservation = true;
            try { reserved = this.reserveRecorder(); }
            finally { this.beginningReservation = false; }

            if (!reserved)
            {
                this.cleanupUnstartedArtifacts();
                return false;
            }

            if (this.exportRequest != null)
            {
                /* Publish the immutable ownership fence before preparation or
                 * encoder startup can synchronously produce a terminal result. */
                this.onOwnedExportStarted(this.exportRequest);
            }

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
        this.drainClientCallbacks();

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

        boolean captureRequested = (this.exportRequest == null
            ? this.shouldCaptureMinecraftSounds()
            : this.exportRequest.minecraftAudio());
        this.capturingMinecraftSounds = captureRequested && this.isMinecraftSoundCaptureAvailable();
        this.minecraftCaptureFailure = captureRequested && !this.capturingMinecraftSounds
            ? new IllegalStateException("Minecraft sound capture is unavailable for this export")
            : null;
        File encoderAudio = this.audioFile;

        if (this.exportRequest != null)
        {
            /* Owned sessions always encode AAC once, after all PCM sources are
             * rendered/mixed.  The recorder therefore captures video-only. */
            encoderAudio = null;
        }
        else if (this.capturingMinecraftSounds)
        {
            this.deferredAudioFile = this.audioFile;
            encoderAudio = null;
        }

        if (this.exportRequest == null && this.capturingMinecraftSounds)
        {
            try
            {
                this.legacyAudioSnapshot = this.snapshotLegacyAudioContract();
            }
            catch (Exception | LinkageError e)
            {
                this.minecraftCaptureFailure = e;
                this.capturingMinecraftSounds = false;
            }
        }

        try
        {
            boolean started;
            VideoExportArtifacts artifacts = this.exportRequest == null ? null : this.exportArtifacts;

            if (this.exportRequest != null)
            {
                artifacts.claim(artifacts.rawVideo());
                if (this.exportRequest.encoderLog())
                {
                    artifacts.claim(artifacts.recordingLog());
                }
                started = recorder.tryStartRecording(movieName, null, artifacts.rawVideo().toFile(),
                    artifacts.recordingLog().toFile(), this.exportRequest.layout(),
                    this.exportRequest.frameRate(), this.exportRequest.motionBlurPasses(),
                    this.exportRequest.heldFrames(), this.exportRequest.limitFrameRate(),
                    this.exportRequest.videoArguments(), this.exportRequest.encoderLog(),
                    this.textureId, this.width, this.height);
            }
            else
            {
                started = recorder.tryStartRecording(movieName, encoderAudio, this.textureId, this.width, this.height);
            }

            if (!started)
            {
                if (artifacts != null && recorder.didPublishOutput())
                {
                    artifacts.markProduced(artifacts.rawVideo());
                    if (this.exportRequest.encoderLog() && recorder.didPublishLog())
                    {
                        artifacts.markProduced(artifacts.recordingLog());
                    }
                }
                Throwable failure = recorder.getFailure();

                this.fail(failure == null ? new IllegalStateException("FFmpeg failed to start") : failure);

                return;
            }

            if (artifacts != null)
            {
                if (recorder.didPublishOutput())
                {
                    artifacts.markProduced(artifacts.rawVideo());
                    if (this.exportRequest.encoderLog() && recorder.didPublishLog())
                    {
                        artifacts.markProduced(artifacts.recordingLog());
                    }
                }
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
                this.recordingFrameRate = this.exportRequest == null
                    ? BBSRendering.getVideoFrameRate()
                    : this.exportRequest.captureFrameRate();
                this.startMinecraftSoundCapture();
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
        if (this.state == State.POSTPROCESSING || this.state == State.FINALIZING)
        {
            return;
        }

        this.finish(Result.SUCCESS, null);
    }

    public final void cancel()
    {
        if (this.state == State.POSTPROCESSING || this.state == State.FINALIZING)
        {
            this.cancelRequested.set(true);
            PostprocessContext context = this.activePostprocessContext;
            if (context == null)
            {
                if (this.postprocessCancelled != null) this.postprocessCancelled.set(true);
                return;
            }

            context.cancelled().set(true);

            /* Claiming execution is the only safe way to distinguish a queued
             * FutureTask from a worker which is already using the artifacts.
             * Future.cancel(false) alone is true for both cases. */
            if (context.executionClaimed().compareAndSet(false, true))
            {
                java.util.concurrent.Future<?> future = this.postprocessFuture;
                if (future != null) future.cancel(false);

                VideoRecorder recorder = this.getSessionRecorder();
                this.postToClient(() -> this.completeOwnedPostprocess(context,
                    this.cancelledResult(context), recorder));
            }

            return;
        }

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
        if (this.exportRequest != null)
        {
            this.finishOwned(requested, failure);

            return;
        }

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

            if (capture == null)
            {
                result = Result.FAILED;
                failure = appendFailure(failure,
                    new IllegalStateException("Minecraft sound capture disappeared during export"));
            }
            else
            {
                try
                {
                    capture.end();
                    Throwable captureResultFailure = this.captureResultFailure(capture);
                    if (captureResultFailure != null)
                    {
                        result = Result.FAILED;
                        failure = appendFailure(failure, captureResultFailure);
                    }
                }
                catch (Exception | LinkageError e)
                {
                    result = Result.FAILED;
                    failure = appendFailure(failure, e);
                }

                if (result == Result.SUCCESS)
                {
                    Throwable captureFailure = this.finishCapturedSounds(capture, recordedFrames);
                    if (captureFailure != null)
                    {
                        result = Result.FAILED;
                        failure = appendFailure(failure, captureFailure);
                    }
                }
            }
        }

        if (this.minecraftCaptureFailure != null)
        {
            result = Result.FAILED;
            failure = appendFailure(failure, this.minecraftCaptureFailure);
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
            try
            {
                recorder.announceSuccessfulCompletion();
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.warn("Video recorder completion announcement failed", e);
            }
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
        Throwable fatal = rememberFatal(null, failure);
        VideoExportArtifact artifact = this.describeOwnedArtifact(false, 0L);
        List<Throwable> artifactCleanup;
        try
        {
            artifactCleanup = this.exportArtifacts == null ? List.of() : this.exportArtifacts.cleanup();
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            terminalFailure = appendFailure(terminalFailure, e);
            artifactCleanup = List.of(e);
        }

        try
        {
            this.reset();
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            LOGGER.error("Failed to reset video export after preparation failure", e);
            terminalFailure = appendFailure(terminalFailure, e);
        }
        finally
        {
            try { this.releaseRecorderReservation(); }
            catch (Throwable e)
            {
                fatal = rememberFatal(fatal, e);
                terminalFailure = appendFailure(terminalFailure, e);
            }
        }

        this.lastResult = Result.FAILED;
        this.lastFailure = terminalFailure == null ? new IllegalStateException("Video export preparation failed") : terminalFailure;
        FinishedListener listener = this.finishedListener;
        TypedFinishedListener typed = this.typedFinishedListener;
        this.lastExportResult = new VideoExportResult(VideoExportResult.Kind.PREPARATION_FAILED,
            VideoExportResult.Stage.PREPARATION, artifact, this.lastFailure, artifactCleanup,
            this.lastFailure.getMessage());

        this.finishedListener = null;
        this.typedFinishedListener = null;
        this.generationArtifacts.remove(this.activeGeneration);
        this.exportRequest = null;
        this.exportArtifacts = null;

        try
        {
            this.onTerminalResult(this.lastExportResult);
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            LOGGER.warn("Video export terminal UI hook failed", e);
        }

        fatal = rememberFatal(fatal, this.notifyTerminalObservers(this.lastExportResult));
        fatal = rememberFatal(fatal, this.notifyFinishedListener(listener, true));
        fatal = rememberFatal(fatal, this.notifyTypedFinishedListener(typed, this.lastExportResult));
        rethrowFatal(fatal);
    }

    /** Complete an owned export while keeping recorder/UI ownership through postprocess. */
    private void finishOwned(Result requested, Throwable failure)
    {
        if (requested == Result.CANCELLED)
        {
            this.cancelRequested.set(true);
        }

        if (this.state == State.IDLE || this.finishing)
        {
            return;
        }

        if (this.state == State.POSTPROCESSING || this.state == State.FINALIZING)
        {
            if (requested == Result.CANCELLED && this.postprocessCancelled != null)
            {
                this.postprocessCancelled.set(true);
            }

            return;
        }

        this.finishing = true;
        this.state = State.FINALIZING;
        VideoRecorder recorder = this.getSessionRecorder();
        Throwable terminalFailure = failure;
        VideoExportProcess.Outcome outcome = VideoExportProcess.Outcome.FAILED;

        try
        {
            if (recorder == null)
            {
                terminalFailure = appendFailure(terminalFailure,
                    new IllegalStateException("Video recorder disappeared during export"));
            }
            else if (requested == Result.SUCCESS)
            {
                outcome = recorder.completeRecording();
                terminalFailure = appendFailure(terminalFailure, recorder.getFailure());
            }
            else if (requested == Result.CANCELLED)
            {
                outcome = recorder.cancelRecording();
                terminalFailure = appendFailure(terminalFailure, recorder.getFailure());
            }
            else
            {
                outcome = recorder.failRecording(terminalFailure);
                terminalFailure = appendFailure(terminalFailure, recorder.getFailure());
            }
        }
        catch (Exception | LinkageError e)
        {
            terminalFailure = appendFailure(terminalFailure, e);
            outcome = VideoExportProcess.Outcome.FAILED;

            if (recorder != null)
            {
                try { recorder.failRecording(e); }
                catch (Exception | LinkageError cleanup) { terminalFailure = appendFailure(terminalFailure, cleanup); }
            }
        }

        try
        {
            this.markRecorderArtifacts(recorder);
        }
        catch (Exception | LinkageError e)
        {
            terminalFailure = appendFailure(terminalFailure, e);
        }

        /* The recorder drains its final PBO inside completeRecording; freeze the
         * delivered frame count only after that call returns. */
        this.deliveredFrames = recorder == null ? 0 : recorder.getCounter();

        boolean captureFailure = this.minecraftCaptureFailure != null;
        if (captureFailure)
        {
            terminalFailure = appendFailure(terminalFailure, this.minecraftCaptureFailure);
        }

        if (this.capturingMinecraftSounds)
        {
            try
            {
                CapturedAudioSnapshot snapshot = this.finishOwnedMinecraftSoundCapture();
                if (snapshot == null)
                {
                    throw new IllegalStateException("Minecraft sound capture returned no snapshot");
                }
                if (snapshot.failure() != null)
                {
                    captureFailure = true;
                    terminalFailure = appendFailure(terminalFailure, snapshot.failure());
                }
                else
                {
                    this.capturedSounds = snapshot.sounds();
                    this.capturedFrames = snapshot.frames();
                }
            }
            catch (Throwable e)
            {
                captureFailure = true;
                terminalFailure = appendFailure(terminalFailure, e);
            }
        }

        /* Cancellation has terminal precedence over capture/audio failures
         * discovered while FINALIZING. */
        if (this.cancelRequested.get())
        {
            List<Throwable> cleanupFailures = terminalFailure == null
                ? List.of() : List.of(terminalFailure);
            this.completeOwnedTerminal(new VideoExportResult(VideoExportResult.Kind.CANCELLED,
                VideoExportResult.Stage.CANCELLED, this.describeOwnedArtifact(false, 0L),
                null, cleanupFailures, "Export cancelled", VideoExportResult.FailureKind.NONE), recorder);

            return;
        }

        if (requested == Result.SUCCESS && outcome == VideoExportProcess.Outcome.SUCCEEDED && this.deliveredFrames <= 0)
        {
            outcome = VideoExportProcess.Outcome.FAILED;
            terminalFailure = appendFailure(terminalFailure,
                new IllegalStateException("Video export completed without a delivered frame"));
        }

        /* A cancel can arrive while the recorder is draining and before the
         * postprocess context exists.  Honor that intent before publishing a
         * success or submitting any worker work. */
        if (this.cancelRequested.get())
        {
            this.completeOwnedTerminal(new VideoExportResult(VideoExportResult.Kind.CANCELLED,
                VideoExportResult.Stage.CANCELLED, this.describeOwnedArtifact(false, 0L),
                null, List.of(), "Export cancelled", VideoExportResult.FailureKind.NONE), recorder);

            return;
        }

        if (requested != Result.SUCCESS || outcome != VideoExportProcess.Outcome.SUCCEEDED)
        {
            VideoExportResult.Kind kind = requested == Result.CANCELLED
                ? VideoExportResult.Kind.CANCELLED
                : (this.movieName == null ? VideoExportResult.Kind.START_FAILED : VideoExportResult.Kind.ENCODE_FAILED);
            VideoExportResult.Stage stage = requested == Result.CANCELLED
                ? VideoExportResult.Stage.CANCELLED
                : (this.movieName == null ? VideoExportResult.Stage.START : VideoExportResult.Stage.RECORDING);
            VideoExportResult result = new VideoExportResult(kind, stage,
                this.describeOwnedArtifact(false, 0L), terminalFailure, List.of(),
                terminalFailure == null ? kind.name() : terminalFailure.getMessage());
            this.completeOwnedTerminal(result, recorder);

            return;
        }

        if (captureFailure)
        {
            this.completeOwnedTerminal(new VideoExportResult(VideoExportResult.Kind.AUDIO_FAILED,
                VideoExportResult.Stage.AUDIO_MIX, this.describeOwnedArtifact(false, 0L, true),
                terminalFailure, List.of(), "Minecraft sound capture failed",
                this.minecraftCaptureFailure == null
                    ? VideoExportResult.FailureKind.AUDIO_MIX
                    : VideoExportResult.FailureKind.MISSING_RESOURCE), recorder);

            return;
        }

        this.postprocessCancelled = new AtomicBoolean(this.cancelRequested.get());
        this.state = State.POSTPROCESSING;
        long generation = this.activeGeneration;
        MinecraftSoundMixer.SoundResourceFiles resourceSnapshot;
        Throwable snapshotFatal = null;

        if (this.exportRequest != null && this.exportRequest.minecraftAudio())
        {
            try
            {
                resourceSnapshot = this.snapshotMinecraftSoundResources(
                    this.capturedSounds, this.postprocessCancelled::get);
            }
            catch (Throwable e)
            {
                snapshotFatal = rememberFatal(snapshotFatal, e);
                resourceSnapshot = MinecraftSoundMixer.SoundResourceFiles.failure(
                    AudioRenderResult.Status.MIX_FAILURE,
                    "Minecraft sound resource snapshot failed", e);
            }
        }
        else
        {
            resourceSnapshot = null;
        }

        PostprocessContext context = new PostprocessContext(generation, this.exportRequest,
            this.exportArtifacts, this.deliveredFrames, this.capturedSounds, this.capturedFrames,
            resourceSnapshot, this.postprocessCancelled, new AtomicBoolean(false),
            new AtomicBoolean(false), new AtomicBoolean(false), new AtomicBoolean(false));
        this.activePostprocessContext = context;

        if (snapshotFatal != null)
        {
            this.finishing = false;
            this.completeOwnedTerminal(this.workerFailureResult(context, snapshotFatal), recorder);
            rethrowFatal(snapshotFatal);
            return;
        }

        try
        {
            this.postprocessFuture = this.submitPostprocess(() ->
            {
                if (!context.executionClaimed().compareAndSet(false, true))
                {
                    return;
                }

                VideoExportResult result = null;
                Throwable fatal = null;

                try
                {
                    this.onPostprocessExecutionClaimed();
                    result = this.runOwnedPostprocess(context);
                }
                catch (java.util.concurrent.CancellationException e)
                {
                    result = this.cancelledResult(context);
                }
                catch (Throwable e)
                {
                    fatal = rememberFatal(fatal, e);
                    result = this.workerFailureResult(context, e);
                }
                finally
                {
                    VideoExportResult terminal = result == null
                        ? this.workerFailureResult(context,
                            new IllegalStateException("Video export worker ended without a result"))
                        : result;
                    Runnable completion = () -> this.completeOwnedPostprocess(context, terminal, recorder);
                    try
                    {
                        this.postToClient(completion);
                    }
                    catch (Throwable postFailure)
                    {
                        fatal = rememberFatal(fatal, postFailure);
                        this.clientCallbacks.add(completion);
                    }
                }

                rethrowFatal(fatal);
            });
            this.finishing = false;
        }
        catch (Throwable e)
        {
            this.finishing = false;
            Throwable fatal = rememberFatal(null, e);
            try
            {
                this.completeOwnedTerminal(new VideoExportResult(VideoExportResult.Kind.ENCODE_FAILED,
                    VideoExportResult.Stage.COMPLETE, this.describeOwnedArtifact(false, 0L), e,
                    List.of(), e.getMessage()), recorder);
            }
            catch (Throwable completionFailure)
            {
                fatal = rememberFatal(fatal, completionFailure);
                if (completionFailure != e) e.addSuppressed(completionFailure);
            }
            rethrowFatal(fatal);
        }
    }

    private VideoExportResult workerFailureResult(PostprocessContext context, Throwable failure)
    {
        Throwable cause = failure == null
            ? new IllegalStateException("Video export worker failed without a cause") : failure;

        try
        {
            return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_RENDER, cause, null);
        }
        catch (Throwable conversionFailure)
        {
            cause = appendFailure(cause, conversionFailure);
            ChannelLayout layout = context != null && context.request() != null
                ? context.request().layout() : ChannelLayout.MONO;
            return new VideoExportResult(VideoExportResult.Kind.AUDIO_FAILED,
                VideoExportResult.Stage.AUDIO_RENDER, VideoExportArtifact.empty(layout), cause,
                List.of(), "Video export worker failed", VideoExportResult.FailureKind.AUDIO_RENDER);
        }
    }

    private VideoExportResult runOwnedPostprocess(PostprocessContext context)
    {
        long generation = context.generation();
        VideoExportRequest request = context.request();
        VideoExportArtifacts artifacts = context.artifacts();
        BooleanSupplier cancelled = context.cancelled()::get;
        BiConsumer<Long, Long> progress = (done, total) ->
            this.reportPostprocessProgress(generation, total <= 0L ? 0.05D : 0.05D + 0.45D * done / (double) total);

        if (request == null || artifacts == null)
        {
            return new VideoExportResult(VideoExportResult.Kind.ENCODE_FAILED,
                VideoExportResult.Stage.COMPLETE, VideoExportArtifact.empty(ChannelLayout.MONO),
                new IllegalStateException("Owned export request disappeared"), List.of(), "Missing export request");
        }

        if (cancelled.getAsBoolean())
        {
            return cancelledResult(context);
        }

        if (!request.filmAudio() && !request.minecraftAudio())
        {
            try
            {
                if (cancelled.getAsBoolean()) return cancelledResult(context);
                context.publicationCommitted().set(true);
                artifacts.publishRawVideo();
                reportPostprocessProgress(generation, 1D);
                return new VideoExportResult(VideoExportResult.Kind.SUCCESS, VideoExportResult.Stage.COMPLETE,
                    this.describeOwnedArtifact(context, false, 0L), null, List.of(), "");
            }
            catch (Exception e)
            {
                context.publicationCommitted().set(false);
                return new VideoExportResult(VideoExportResult.Kind.PUBLISH_FAILED,
                    VideoExportResult.Stage.PUBLISH, this.describeOwnedArtifact(context, false, 0L, true),
                    e, List.of(), "Failed to publish video");
            }
        }

        Path audio = null;
        long audioFrames = 0L;

        if (request.filmAudio())
        {
            reportPostprocessProgress(generation, 0.05D);
            try
            {
                artifacts.claim(artifacts.filmAudio());
            }
            catch (Exception e)
            {
                return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_RENDER, e, null);
            }

            AudioRenderResult rendered = this.renderFilmAudio(request, artifacts.filmAudio().toFile(), cancelled, progress);

            if (rendered == null || (!rendered.success() && rendered.status() != AudioRenderResult.Status.EMPTY))
            {
                Throwable cause = rendered == null ? new IllegalStateException("Film audio renderer returned no result") : rendered.cause();
                if (cause == null && rendered != null)
                {
                    cause = new IllegalStateException(rendered.message() == null
                        ? "Film audio renderer failed" : rendered.message());
                }
                if (rendered != null && rendered.status() == AudioRenderResult.Status.CANCELLED)
                {
                    return this.cancelledResult(context);
                }
                VideoExportResult.FailureKind failureKind = rendered != null
                    && rendered.status() == AudioRenderResult.Status.MISSING_RESOURCE
                    ? VideoExportResult.FailureKind.MISSING_RESOURCE
                    : VideoExportResult.FailureKind.AUDIO_RENDER;
                VideoExportResult.Stage failureStage = failureKind == VideoExportResult.FailureKind.MISSING_RESOURCE
                    ? VideoExportResult.Stage.MISSING_RESOURCE
                    : VideoExportResult.Stage.AUDIO_RENDER;
                return this.degradedOrFailed(context, failureStage,
                    cause, artifacts.filmAudio(), failureKind);
            }

            if (rendered.status() == AudioRenderResult.Status.EMPTY)
            {
                try
                {
                    VideoExportAudioNormalizer.writeSilence(artifacts.filmAudio(), request.layout(),
                        request.sampleRate(), request.audioFramesFor(context.deliveredFrames()), cancelled);
                    artifacts.markProduced(artifacts.filmAudio());
                }
                catch (Exception e)
                {
                    return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_RENDER, e, artifacts.filmAudio());
                }
            }
            else
            {
                try
                {
                    artifacts.markProduced(artifacts.filmAudio());
                }
                catch (Exception e)
                {
                    return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_RENDER, e, null);
                }
            }

            audio = artifacts.filmAudio();
        }

        if (request.minecraftAudio())
        {
            if (cancelled.getAsBoolean()) return cancelledResult(context);

            MinecraftSoundMixer.SoundResourceFiles snapshot = context.resourceSnapshot();
            if (snapshot == null || !snapshot.success())
            {
                if (snapshot != null && snapshot.status() == AudioRenderResult.Status.CANCELLED)
                {
                    return this.cancelledResult(context);
                }

                Throwable snapshotFailure = snapshot == null ? null : snapshot.cause();
                if (snapshotFailure == null)
                {
                    snapshotFailure = new IllegalStateException(snapshot == null
                        ? "Minecraft sound resource snapshot is missing"
                        : snapshot.message());
                }

                VideoExportResult.FailureKind failureKind = snapshot != null
                    && snapshot.status() == AudioRenderResult.Status.MISSING_RESOURCE
                    ? VideoExportResult.FailureKind.MISSING_RESOURCE
                    : VideoExportResult.FailureKind.AUDIO_MIX;
                return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_MIX,
                    snapshotFailure, audio, failureKind);
            }

            reportPostprocessProgress(generation, 0.52D);
            BiConsumer<Long, Long> mixProgress = (done, total) ->
                this.reportPostprocessProgress(generation,
                    total <= 0L ? 0.52D : 0.52D + 0.18D * done / (double) total);

            try
            {
                artifacts.claim(artifacts.mixedAudio());
            }
            catch (Exception e)
            {
                return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_MIX, e, audio);
            }

            AudioRenderResult mixed = this.mixMinecraftSoundSources(
                artifacts.mixedAudio().toFile(), context.capturedSounds(),
                context.capturedFrames(), audio, snapshot, request.sampleRate(),
                request.captureFrameRate(), context.deliveredFrames(), request.layout(), cancelled, mixProgress);

            if (mixed.status() == AudioRenderResult.Status.CANCELLED)
            {
                return this.cancelledResult(context);
            }

            if (mixed.status() == AudioRenderResult.Status.EMPTY)
            {
                try
                {
                    VideoExportAudioNormalizer.writeSilence(artifacts.mixedAudio(), request.layout(),
                        request.sampleRate(), request.audioFramesFor(context.deliveredFrames()), cancelled);
                    artifacts.markProduced(artifacts.mixedAudio());
                }
                catch (Exception e)
                {
                    return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_MIX, e, audio);
                }
            }
            else if (!mixed.success())
            {
                Throwable mixFailure = mixed.cause() == null
                    ? new IllegalStateException(mixed.message() == null ? "Minecraft sound mixer failed" : mixed.message())
                    : mixed.cause();
                VideoExportResult.FailureKind failureKind = mixed.status() == AudioRenderResult.Status.MISSING_RESOURCE
                    ? VideoExportResult.FailureKind.MISSING_RESOURCE
                    : VideoExportResult.FailureKind.AUDIO_MIX;
                return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_MIX,
                    mixFailure, audio, failureKind);
            }
            else
            {
                try
                {
                    artifacts.markProduced(artifacts.mixedAudio());
                }
                catch (Exception e)
                {
                    return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_MIX, e, audio);
                }
            }

            audio = artifacts.mixedAudio();
        }

        if (audio == null)
        {
            return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_RENDER,
                new IllegalStateException("Requested audio produced no PCM artifact"), null);
        }

        try
        {
            artifacts.claim(artifacts.normalizedAudio());
            audioFrames = VideoExportAudioNormalizer.normalize(audio, artifacts.normalizedAudio(), request.layout(),
                request.sampleRate(), request.audioFramesFor(context.deliveredFrames()), cancelled);
            artifacts.markProduced(artifacts.normalizedAudio());
            audio = artifacts.normalizedAudio();
        }
        catch (Exception e)
        {
            return this.degradedOrFailed(context, VideoExportResult.Stage.AUDIO_MIX, e, audio);
        }

        if (cancelled.getAsBoolean()) return cancelledResult(context);
        reportPostprocessProgress(generation, 0.75D);
        try
        {
            artifacts.claim(artifacts.muxPartial());
            if (request.encoderLog()) artifacts.claim(artifacts.muxLog());
        }
        catch (Exception e)
        {
            return this.degradedOrFailed(context, VideoExportResult.Stage.MUX, e, audio);
        }

        VideoMuxer.MuxResult mux = this.muxOwnedExport(artifacts.rawVideo().toFile(), audio.toFile(),
            artifacts.muxPartial(), artifacts.muxLog(), request.layout(), request.muxArguments(), cancelled,
            request.encoderLog());

        if (!mux.success())
        {
            if (mux.status() == VideoMuxer.Status.CANCELLED)
            {
                return this.withCleanupFailures(cancelledResult(context), mux.cleanupFailures());
            }
            return this.withCleanupFailures(this.degradedOrFailed(context, VideoExportResult.Stage.MUX,
                mux.cause() == null ? new IllegalStateException(mux.message()) : mux.cause(), audio),
                mux.cleanupFailures());
        }

        try
        {
            artifacts.markProduced(artifacts.muxPartial());
            if (request.encoderLog() && mux.logPublished()) artifacts.markProduced(artifacts.muxLog());
        }
        catch (Exception e)
        {
            return this.degradedOrFailed(context, VideoExportResult.Stage.MUX, e, audio);
        }

        if (cancelled.getAsBoolean()) return cancelledResult(context);

        try
        {
            context.publicationCommitted().set(true);
            artifacts.publishMuxedVideo();
        }
        catch (Exception e)
        {
            context.publicationCommitted().set(false);
            try { artifacts.promoteRecovery(audio); }
            catch (Exception recoveryFailure) { e.addSuppressed(recoveryFailure); }
            return new VideoExportResult(VideoExportResult.Kind.PUBLISH_FAILED, VideoExportResult.Stage.PUBLISH,
                this.describeOwnedArtifact(context, false, audioFrames, true), e, List.of(),
                "Failed to publish muxed video", VideoExportResult.FailureKind.PUBLISH);
        }

        reportPostprocessProgress(generation, 1D);
        VideoExportResult success = new VideoExportResult(VideoExportResult.Kind.SUCCESS,
            VideoExportResult.Stage.COMPLETE, this.describeOwnedArtifact(context, true, audioFrames),
            null, List.of(), "");
        if (mux.cleanupFailures().isEmpty())
        {
            return success;
        }

        return new VideoExportResult(VideoExportResult.Kind.CLEANUP_FAILED,
            VideoExportResult.Stage.CLEANUP, success.artifact(), mux.cleanupFailures().get(0),
            mux.cleanupFailures(), "Mux cleanup failed", VideoExportResult.FailureKind.CLEANUP);
    }

    private VideoExportResult withCleanupFailures(VideoExportResult result, List<Throwable> failures)
    {
        if (result == null || failures == null || failures.isEmpty())
        {
            return result;
        }

        List<Throwable> cleanupFailures = new ArrayList<>(result.cleanupFailures());
        cleanupFailures.addAll(failures);
        Throwable cause = result.cause();
        for (Throwable failure : failures)
        {
            cause = appendFailure(cause, failure);
        }

        return new VideoExportResult(result.kind(), result.stage(), result.artifact(), cause,
            cleanupFailures, result.message(), result.failureKind());
    }

    private VideoExportResult degradedOrFailed(PostprocessContext context, VideoExportResult.Stage stage,
                                               Throwable cause, Path recoverySource)
    {
        VideoExportResult.FailureKind failureKind = stage == VideoExportResult.Stage.MUX
            ? VideoExportResult.FailureKind.MUX
            : stage == VideoExportResult.Stage.AUDIO_MIX
                ? VideoExportResult.FailureKind.AUDIO_MIX
                : VideoExportResult.FailureKind.AUDIO_RENDER;
        return this.degradedOrFailed(context, stage, cause, recoverySource, failureKind);
    }

    private VideoExportResult degradedOrFailed(PostprocessContext context, VideoExportResult.Stage stage,
                                               Throwable cause, Path recoverySource,
                                               VideoExportResult.FailureKind failureKind)
    {
        VideoExportRequest request = context.request();
        VideoExportArtifacts artifacts = context.artifacts();
        List<Throwable> extra = new ArrayList<>();

        if (request == null || artifacts == null)
        {
            return new VideoExportResult(VideoExportResult.Kind.AUDIO_FAILED, stage,
                VideoExportArtifact.empty(ChannelLayout.MONO), cause, extra, "Audio postprocess failed");
        }

        if (context.cancelled().get())
        {
            return this.cancelledResult(context);
        }

        if (recoverySource != null && Files.isRegularFile(recoverySource))
        {
            try { artifacts.promoteRecovery(recoverySource); }
            catch (Exception e) { extra.add(e); }
        }

        try
        {
            if (context.cancelled().get()) return this.cancelledResult(context);
            context.publicationCommitted().set(true);
            artifacts.publishRawVideo();
        }
        catch (Exception publishFailure)
        {
            context.publicationCommitted().set(false);
            Throwable primary = cause == null ? publishFailure : cause;
            if (primary != publishFailure) primary.addSuppressed(publishFailure);
            return new VideoExportResult(VideoExportResult.Kind.PUBLISH_FAILED, VideoExportResult.Stage.PUBLISH,
                this.describeOwnedArtifact(context, false, 0L, true), primary, extra,
                "Failed to retain video after audio failure", VideoExportResult.FailureKind.PUBLISH);
        }

        return new VideoExportResult(VideoExportResult.Kind.DEGRADED, stage,
            this.describeOwnedArtifact(context, false, 0L), cause, extra,
            cause == null ? "Audio unavailable; retained video-only output" : cause.getMessage(),
            failureKind);
    }

    private VideoExportResult cancelledResult(PostprocessContext context)
    {
        return new VideoExportResult(VideoExportResult.Kind.CANCELLED, VideoExportResult.Stage.CANCELLED,
            this.describeOwnedArtifact(context, false, 0L), null, List.of(), "Export cancelled",
            VideoExportResult.FailureKind.NONE);
    }

    private void completeOwnedPostprocess(PostprocessContext context, VideoExportResult result, VideoRecorder recorder)
    {
        if (context.cancelled().get() && !context.publicationCommitted().get()
            && (result.kind() == VideoExportResult.Kind.SUCCESS
                || result.kind() == VideoExportResult.Kind.DEGRADED))
        {
            result = this.cancelledResult(context);
        }

        if (!context.terminalDelivered().compareAndSet(false, true))
        {
            return;
        }

        long generation = context.generation();
        if (generation != this.activeGeneration || this.state != State.POSTPROCESSING)
        {
            Throwable sourceCleanup = this.closePostprocessResources(context);
            Throwable fatal = rememberFatal(null, sourceCleanup);
            VideoExportArtifacts staleArtifacts = context.artifacts();
            if (staleArtifacts != null && staleArtifacts != this.exportArtifacts
                && context.cleanupDone().compareAndSet(false, true))
            {
                try
                {
                    List<Throwable> failures = staleArtifacts.cleanup(
                        result.kind() == VideoExportResult.Kind.PUBLISH_FAILED);
                    if (sourceCleanup != null) failures = appendCleanupFailure(failures, sourceCleanup);
                    for (Throwable failure : failures)
                    {
                        fatal = rememberFatal(fatal, failure);
                        LOGGER.warn("Failed to clean stale video export {}", generation, failure);
                    }
                }
                catch (Throwable cleanupFailure)
                {
                    fatal = rememberFatal(fatal, cleanupFailure);
                    LOGGER.warn("Failed to clean stale video export {}", generation, cleanupFailure);
                }
                this.generationArtifacts.remove(generation, staleArtifacts);
            }
            else if (sourceCleanup != null)
            {
                LOGGER.warn("Failed to release stale audio snapshot for export {}", generation, sourceCleanup);
            }
            rethrowFatal(fatal);
            return;
        }

        this.finishing = true;
        this.completeOwnedTerminal(result, recorder);
    }

    private void completeOwnedTerminal(VideoExportResult result, VideoRecorder recorder)
    {
        VideoExportResult terminal = result == null
            ? new VideoExportResult(VideoExportResult.Kind.ENCODE_FAILED,
                VideoExportResult.Stage.COMPLETE, VideoExportArtifact.empty(ChannelLayout.MONO),
                new IllegalStateException("Video export reached terminal without a result"),
                List.of(), "Missing terminal result")
            : result;
        Throwable fatal = null;
        PostprocessContext completedContext = this.activePostprocessContext;
        Throwable sourceCleanup = this.closePostprocessResources(completedContext);
        fatal = rememberFatal(fatal, sourceCleanup);
        if (sourceCleanup != null)
        {
            terminal = this.withTerminalCleanupFailure(terminal, sourceCleanup,
                "Audio source snapshot cleanup failed");
        }
        boolean retainsRawVideo = terminal.artifact() != null && terminal.artifact().rawVideo() != null;
        boolean preserveRawVideo = terminal.kind() == VideoExportResult.Kind.PUBLISH_FAILED
            || ((terminal.kind() == VideoExportResult.Kind.AUDIO_FAILED
                || terminal.kind() == VideoExportResult.Kind.DEGRADED) && retainsRawVideo);
        this.state = State.IDLE;

        try
        {
            this.teardown(!terminal.isSuccess());
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            terminal = this.withTerminalCleanupFailure(terminal, e, "Export teardown failed");
        }

        if (recorder != null && terminal.artifact() != null && terminal.artifact().finalVideo() != null)
        {
            try
            {
                File published = terminal.artifact().finalVideo().toFile();
                File raw = recorder.getOutputFile();

                if (raw != null && !published.equals(raw) && !recorder.acceptPublishedOutput(raw, published))
                {
                    LOGGER.warn("Video export published {}, but recorder ownership metadata could not be updated", published);
                }
            }
            catch (Throwable e)
            {
                fatal = rememberFatal(fatal, e);
                terminal = this.withTerminalCleanupFailure(terminal, e,
                    "Recorder publication metadata cleanup failed");
            }
        }

        List<Throwable> cleanup = List.of();
        try
        {
            cleanup = this.exportArtifacts == null ? List.of()
                : this.exportArtifacts.cleanup(preserveRawVideo);
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            cleanup = List.of(e);
        }

        if (!cleanup.isEmpty())
        {
            for (Throwable cleanupFailure : cleanup)
            {
                fatal = rememberFatal(fatal, cleanupFailure);
                terminal = this.withTerminalCleanupFailure(terminal, cleanupFailure,
                    "Export cleanup failed");
            }
        }

        try
        {
            this.reset();
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            terminal = this.withTerminalCleanupFailure(terminal, e, "Export reset failed");
        }
        try
        {
            this.releaseRecorderReservation();
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            terminal = this.withTerminalCleanupFailure(terminal, e,
                "Recorder ownership release failed");
        }

        if (this.reservedRecorder != null)
        {
            boolean live = true;
            try { live = this.reservedRecorder.hasLiveProcess(); }
            catch (Throwable e)
            {
                fatal = rememberFatal(fatal, e);
                terminal = this.withTerminalCleanupFailure(terminal, e,
                    "Recorder ownership state check failed");
            }
            Throwable ownershipFailure = new IllegalStateException(live
                ? "FFmpeg process remained alive; recorder ownership was retained"
                : "Recorder reservation remained owned after terminal cleanup");
            terminal = this.withTerminalCleanupFailure(terminal, ownershipFailure,
                "Recorder ownership could not be released");
        }

        this.lastExportResult = terminal;
        this.lastResult = terminal.isSuccess() ? Result.SUCCESS
            : terminal.kind() == VideoExportResult.Kind.CANCELLED ? Result.CANCELLED : Result.FAILED;
        this.lastFailure = terminal.isSuccess() ? null : terminal.cause();
        if (terminal.isSuccess()) this.postprocessProgress = 1D;
        if (completedContext != null) completedContext.cleanupDone().set(true);
        this.exportRequest = null;
        this.exportArtifacts = null;
        this.generationArtifacts.remove(this.activeGeneration);
        this.postprocessCancelled = null;
        this.postprocessFuture = null;
        this.activePostprocessContext = null;
        this.cancelRequested.set(false);
        this.finishing = false;

        if (terminal.kind() == VideoExportResult.Kind.DEGRADED)
        {
            LOGGER.warn("Video export {} completed with degraded video-only output (stage={}, artifact={})",
                this.activeGeneration, terminal.stage(), terminal.artifact() == null ? null : terminal.artifact().finalVideo());
        }
        else if (!terminal.isSuccess() && terminal.kind() != VideoExportResult.Kind.CANCELLED)
        {
            LOGGER.error("Video export {} failed at stage {}: {}", this.activeGeneration,
                terminal.stage(), terminal.message(), terminal.cause());
        }

        FinishedListener listener = this.finishedListener;
        TypedFinishedListener typed = this.typedFinishedListener;
        this.finishedListener = null;
        this.typedFinishedListener = null;

        try
        {
            this.onTerminalResult(terminal);
        }
        catch (Throwable e)
        {
            fatal = rememberFatal(fatal, e);
            LOGGER.warn("Video export terminal UI hook failed", e);
        }

        if (terminal.isSuccess() && recorder != null)
        {
            try
            {
                recorder.announceSuccessfulCompletion();
            }
            catch (Throwable e)
            {
                fatal = rememberFatal(fatal, e);
                LOGGER.warn("Video recorder completion announcement failed", e);
            }
        }

        fatal = rememberFatal(fatal, this.notifyFinishedListener(listener, !terminal.isSuccess()));
        fatal = rememberFatal(fatal, this.notifyTypedFinishedListener(typed, terminal));
        fatal = rememberFatal(fatal, this.notifyTerminalObservers(terminal));
        rethrowFatal(fatal);
    }

    private Throwable closePostprocessResources(PostprocessContext context)
    {
        if (context == null || context.resourceSnapshot() == null) return null;

        try
        {
            context.resourceSnapshot().close();
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    private VideoExportResult withTerminalCleanupFailure(VideoExportResult result,
                                                         Throwable failure, String message)
    {
        if (failure == null) return result;
        VideoExportResult base = result == null
            ? new VideoExportResult(VideoExportResult.Kind.CLEANUP_FAILED,
                VideoExportResult.Stage.CLEANUP, VideoExportArtifact.empty(ChannelLayout.MONO),
                failure, List.of(failure), message, VideoExportResult.FailureKind.CLEANUP)
            : result;
        List<Throwable> cleanup = new ArrayList<>(base.cleanupFailures());
        cleanup.add(failure);
        return new VideoExportResult(VideoExportResult.Kind.CLEANUP_FAILED,
            VideoExportResult.Stage.CLEANUP, base.artifact(), appendFailure(base.cause(), failure),
            cleanup, message, VideoExportResult.FailureKind.CLEANUP);
    }

    private static List<Throwable> appendCleanupFailure(List<Throwable> failures, Throwable failure)
    {
        List<Throwable> result = failures == null ? new ArrayList<>() : new ArrayList<>(failures);
        if (failure != null) result.add(failure);
        return result;
    }

    private VideoExportArtifact describeOwnedArtifact(boolean audioPresent, long audioFrames)
    {
        return this.describeOwnedArtifact(audioPresent, audioFrames, false);
    }

    private VideoExportArtifact describeOwnedArtifact(boolean audioPresent, long audioFrames,
                                                      boolean includeRaw)
    {
        if (this.exportArtifacts == null || this.exportRequest == null)
        {
            return VideoExportArtifact.empty(ChannelLayout.MONO);
        }

        return this.exportArtifacts.describe(this.exportRequest, audioPresent,
            this.deliveredFrames, audioFrames, includeRaw);
    }

    private VideoExportArtifact describeOwnedArtifact(PostprocessContext context,
                                                       boolean audioPresent, long audioFrames)
    {
        return this.describeOwnedArtifact(context, audioPresent, audioFrames, false);
    }

    private VideoExportArtifact describeOwnedArtifact(PostprocessContext context,
                                                       boolean audioPresent, long audioFrames,
                                                       boolean includeRaw)
    {
        if (context == null || context.artifacts() == null || context.request() == null)
        {
            return VideoExportArtifact.empty(ChannelLayout.MONO);
        }

        return context.artifacts().describe(context.request(), audioPresent,
            context.deliveredFrames(), audioFrames, includeRaw);
    }

    private void reportPostprocessProgress(long generation, double progress)
    {
        double bounded = Math.max(0D, Math.min(1D, progress));
        this.postToClient(() ->
        {
            if (generation == this.activeGeneration && this.state == State.POSTPROCESSING)
            {
                this.postprocessProgress = Math.max(this.postprocessProgress, bounded);
            }
        });
    }

    protected void postToClient(Runnable runnable)
    {
        if (runnable == null)
        {
            return;
        }

        Minecraft minecraft;
        try
        {
            minecraft = Minecraft.getInstance();
            if (minecraft == null)
            {
                throw new IllegalStateException("Minecraft client is unavailable");
            }
        }
        catch (Throwable e)
        {
            /* Never run UI teardown inline on a worker.  The owning session's
             * update() drains this queue on the client thread. */
            this.clientCallbacks.add(runnable);
            rethrowFatal(rememberFatal(null, e));
            return;
        }

        if (minecraft.isSameThread())
        {
            runnable.run();
            return;
        }

        try
        {
            minecraft.execute(runnable);
        }
        catch (Throwable e)
        {
            this.clientCallbacks.add(runnable);
            rethrowFatal(rememberFatal(null, e));
        }
    }

    /** Test seam for deterministic queued/running postprocess ownership checks. */
    protected java.util.concurrent.Future<?> submitPostprocess(Runnable runnable)
    {
        return POSTPROCESS_EXECUTOR.submit(runnable);
    }

    /** Called on the worker after it owns execution and before artifact work. */
    protected void onPostprocessExecutionClaimed() throws Exception
    {}

    private void drainClientCallbacks()
    {
        Runnable callback;
        int drained = 0;
        Throwable fatal = null;

        while (drained < 64 && (callback = this.clientCallbacks.poll()) != null)
        {
            drained += 1;
            try
            {
                callback.run();
            }
            catch (Throwable e)
            {
                fatal = rememberFatal(fatal, e);
                LOGGER.warn("Video export client callback failed", e);
            }
        }

        rethrowFatal(fatal);
    }

    private void snapshotCapturedSounds()
    {
        MinecraftSoundCapture capture = this.getMinecraftSoundCapture();
        if (capture != null) this.snapshotCapturedSounds(capture);
    }

    private void snapshotCapturedSounds(MinecraftSoundCapture capture)
    {
        List<MinecraftSoundCapture.CapturedSound> sounds = new ArrayList<>();
        for (MinecraftSoundCapture.CapturedSound sound : capture.getSounds())
        {
            MinecraftSoundCapture.CapturedSound copy = new MinecraftSoundCapture.CapturedSound(sound.location,
                sound.frame, sound.x, sound.y, sound.z, sound.relative, sound.attenuate,
                sound.volume, sound.pitch, sound.range, sound.loop);
            copy.endFrame = sound.endFrame;
            if (sound.track != null)
            {
                for (MinecraftSoundCapture.LoopFrame frame : sound.track)
                {
                    copy.track.add(new MinecraftSoundCapture.LoopFrame(frame.volume, frame.pitch,
                        frame.x, frame.y, frame.z));
                }
            }
            sounds.add(copy);
        }

        this.capturedSounds = List.copyOf(sounds);
        this.capturedFrames = List.copyOf(capture.getFrames());
    }

    private Throwable notifyFinishedListener(FinishedListener listener, boolean aborted)
    {
        if (listener == null)
        {
            return null;
        }

        if (this.beginning)
        {
            this.deferredFinishedListener = listener;
            this.deferredFinishedAborted = aborted;

            return null;
        }

        try
        {
            listener.onFinished(aborted);
        }
        catch (Throwable e)
        {
            LOGGER.error("Video export completion listener failed", e);
            return e;
        }

        return null;
    }

    private Throwable notifyTypedFinishedListener(TypedFinishedListener listener, VideoExportResult result)
    {
        if (listener == null)
        {
            return null;
        }

        if (this.beginning)
        {
            this.deferredTypedFinishedListener = listener;
            this.deferredTypedResult = result;
            return null;
        }

        try
        {
            listener.onFinished(result);
        }
        catch (Throwable e)
        {
            LOGGER.error("Typed video export completion listener failed", e);
            return e;
        }

        return null;
    }

    private Throwable notifyTerminalObservers(VideoExportResult result)
    {
        if (result == null)
        {
            return null;
        }

        if (this.beginning)
        {
            this.deferredSubscriberResult = result;
            return null;
        }

        Throwable failure = null;
        for (TypedFinishedListener listener : this.finishedResultListeners)
        {
            try
            {
                listener.onFinished(result);
            }
            catch (Throwable e)
            {
                LOGGER.error("Persistent video export completion listener failed", e);
                failure = appendFailure(failure, e);
            }
        }

        return failure;
    }

    private void dispatchDeferredFinishedListener()
    {
        FinishedListener listener = this.deferredFinishedListener;
        boolean aborted = this.deferredFinishedAborted;
        TypedFinishedListener typed = this.deferredTypedFinishedListener;
        VideoExportResult typedResult = this.deferredTypedResult;
        VideoExportResult subscriberResult = this.deferredSubscriberResult;

        this.deferredFinishedListener = null;
        this.deferredFinishedAborted = false;
        this.deferredTypedFinishedListener = null;
        this.deferredTypedResult = null;
        this.deferredSubscriberResult = null;
        Throwable fatal = null;
        fatal = rememberFatal(fatal, this.notifyFinishedListener(listener, aborted));
        fatal = rememberFatal(fatal, this.notifyTypedFinishedListener(typed, typedResult));
        fatal = rememberFatal(fatal, this.notifyTerminalObservers(subscriberResult));
        rethrowFatal(fatal);
    }

    private void releaseRecorderReservation()
    {
        if (this.reservedRecorder != null)
        {
            if (this.reservedRecorder.tryReleaseReservation(this))
            {
                this.reservedRecorder = null;
            }
        }
    }

    private void markRecorderArtifacts(VideoRecorder recorder) throws Exception
    {
        if (recorder == null || this.exportArtifacts == null || !recorder.didPublishOutput())
        {
            return;
        }

        this.exportArtifacts.markProduced(this.exportArtifacts.rawVideo());
        if (this.exportRequest != null && this.exportRequest.encoderLog() && recorder.didPublishLog())
        {
            this.exportArtifacts.markProduced(this.exportArtifacts.recordingLog());
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

    private static Throwable rememberFatal(Throwable current, Throwable candidate)
    {
        if (!(candidate instanceof VirtualMachineError))
        {
            return current;
        }

        return appendFailure(current, candidate);
    }

    private static void rethrowFatal(Throwable fatal)
    {
        if (fatal instanceof VirtualMachineError virtualMachineError)
        {
            throw virtualMachineError;
        }
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
            catch (Throwable e)
            {
                failure = appendFailure(failure, e);
            }
        }

        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }

        if (failure instanceof Error error)
        {
            throw error;
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
            this.minecraftCaptureFailure = null;
            this.legacyAudioSnapshot = null;
            this.deliveredFrames = 0;
            this.capturedSounds = List.of();
            this.capturedFrames = List.of();
        }
    }

    private Throwable finishCapturedSounds(MinecraftSoundCapture capture, int recordedFrames)
    {
        File mixed = null;
        boolean merged = false;

        try
        {
            if (recordedFrames <= 0)
            {
                return new IllegalStateException("Minecraft sound export completed without a delivered video frame");
            }

            LegacyAudioSnapshot snapshot = this.legacyAudioSnapshot;
            if (snapshot == null)
            {
                return new IllegalStateException("Legacy Minecraft audio contract is missing");
            }

            File folder = snapshot.videoFolder().toFile();
            Files.createDirectories(folder.toPath());
            mixed = new File(folder, this.movieName + ".wav");

            MinecraftSoundMixer.SoundResourceFiles resources =
                this.snapshotMinecraftSoundResources(capture.getSounds(), () -> false);
            if (resources == null || !resources.success())
            {
                return resources == null || resources.cause() == null
                    ? new IllegalStateException(resources == null
                        ? "Minecraft sound resource snapshot is missing" : resources.message())
                    : resources.cause();
            }

            AudioRenderResult mixResult;
            try (resources)
            {
                mixResult = this.mixMinecraftSoundSources(mixed, capture.getSounds(), capture.getFrames(),
                    this.deferredAudioFile == null ? null : this.deferredAudioFile.toPath(), resources,
                    snapshot.sampleRate(), this.recordingFrameRate, recordedFrames,
                    snapshot.layout(), () -> false, (completed, total) -> {});
            }
            if (!mixResult.success())
            {
                Throwable cause = mixResult.cause() == null
                    ? new IllegalStateException(mixResult.message() == null
                        ? "Minecraft sound mix did not produce an output" : mixResult.message())
                    : mixResult.cause();
                return cause;
            }

            VideoRecorder recorder = this.getSessionRecorder();
            File video = recorder == null ? null : recorder.getOutputFile();

            File published = video == null ? null
                : VideoMuxer.mux(video, mixed, this.movieName, snapshot.muxArguments(), snapshot.layout());

            if (published == null)
            {
                LOGGER.warn("Minecraft sound mix completed, but FFmpeg could not merge it into {}; keeping {} for recovery",
                    this.movieName, mixed);

                return new IllegalStateException("FFmpeg could not merge captured Minecraft sounds");
            }

            if (recorder != null)
            {
                recorder.acceptPublishedOutput(video, published);
            }

            merged = true;
            return null;
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("Failed to mix captured Minecraft sounds into {}", this.movieName, e);
            return e;
        }
        finally
        {
            if (merged && mixed != null && mixed.exists() && !mixed.delete())
            {
                LOGGER.warn("Failed to delete temporary Minecraft sound mix {}", mixed);
            }
        }
    }

    private LegacyAudioSnapshot snapshotLegacyAudioContract() throws Exception
    {
        ChannelLayout layout = BBSSettings.videoSettings == null
            ? ChannelLayout.MONO
            : BBSSettings.videoSettings.resolveAudioLayout();
        String muxArguments = BBSSettings.videoArgumentsMux == null
            ? VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS : BBSSettings.videoArgumentsMux.get();
        VideoExportAudioProfile.validateTemplate(muxArguments, true, true);

        Path folder = BBSRendering.getVideoFolder().toPath().toAbsolutePath().normalize();
        return new LegacyAudioSnapshot(folder, layout, VideoExportAudioProfile.SAMPLE_RATE, muxArguments);
    }

    private Throwable captureResultFailure(MinecraftSoundCapture capture)
    {
        if (capture == null)
        {
            return new IllegalStateException("Minecraft sound capture is missing");
        }

        MinecraftSoundCapture.Result result = capture.getResult();
        if (result != null && result.success())
        {
            return null;
        }

        if (result != null && result.cause() != null)
        {
            return result.cause();
        }

        String status = result == null || result.status() == null
            ? "unknown" : result.status().name();
        String failure = result == null || result.failure() == null
            ? "unknown" : result.failure().name();

        return new IllegalStateException("Minecraft sound capture ended with "
            + status + "/" + failure);
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

    /** Compatibility helper for collision checks; this never discovers artifacts. */
    protected static boolean isExportArtifact(String rest)
    {
        String lower = rest == null ? "" : rest.toLowerCase(java.util.Locale.ROOT);

        return lower.equals("wav") || lower.equals("log") || lower.endsWith(".log")
            || lower.startsWith("tmp.");
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

    /** Owned-export seam for deterministic capture fakes; production delegates to Minecraft. */
    protected boolean isMinecraftSoundCaptureAvailable()
    {
        return this.getMinecraftSoundCapture() != null;
    }

    /** Owned-export seam for deterministic capture fakes; production starts the singleton capture. */
    protected void startMinecraftSoundCapture() throws Exception
    {
        MinecraftSoundCapture capture = this.getMinecraftSoundCapture();
        if (capture == null) throw new IllegalStateException("Minecraft sound capture is unavailable");
        capture.begin();
    }

    /** Owned-export seam for deterministic capture fakes; production drains and snapshots the capture. */
    protected CapturedAudioSnapshot finishOwnedMinecraftSoundCapture() throws Exception
    {
        MinecraftSoundCapture capture = this.getMinecraftSoundCapture();
        if (capture == null)
        {
            return new CapturedAudioSnapshot(List.of(), List.of(),
                new IllegalStateException("Minecraft sound capture disappeared during export"));
        }

        capture.end();
        Throwable failure = this.captureResultFailure(capture);
        if (failure != null) return new CapturedAudioSnapshot(List.of(), List.of(), failure);

        this.snapshotCapturedSounds(capture);
        return new CapturedAudioSnapshot(this.capturedSounds, this.capturedFrames, null);
    }

    /** Captured audio payload used only by the owned-export seam above. */
    protected record CapturedAudioSnapshot(List<MinecraftSoundCapture.CapturedSound> sounds,
                                           List<MinecraftSoundCapture.ListenerFrame> frames,
                                           Throwable failure)
    {
        protected CapturedAudioSnapshot
        {
            sounds = sounds == null ? List.of() : List.copyOf(sounds);
            frames = frames == null ? List.of() : List.copyOf(frames);
        }
    }

    /** Client-thread resource snapshot hook; subclasses may inject a hermetic success fixture. */
    protected MinecraftSoundMixer.SoundResourceFiles snapshotMinecraftSoundResources(
        List<MinecraftSoundCapture.CapturedSound> sounds, BooleanSupplier cancelled)
    {
        return MinecraftSoundMixer.snapshotResourceFiles(sounds, cancelled);
    }

    /** Worker mix hook; subclasses may inject a deterministic F6 mixer fixture. */
    protected AudioRenderResult mixMinecraftSoundSources(File output,
        List<MinecraftSoundCapture.CapturedSound> sounds,
        List<MinecraftSoundCapture.ListenerFrame> frames,
        Path filmAudio,
        MinecraftSoundMixer.SoundResourceFiles resources,
        int sampleRate, double frameRate, int totalFrames, ChannelLayout layout,
        BooleanSupplier cancelled, BiConsumer<Long, Long> progress)
    {
        return MinecraftSoundMixer.mixFileSourcesToFileResult(output, sounds, frames, filmAudio,
            resources, sampleRate, frameRate, totalFrames, layout, cancelled, progress);
    }

    /** Worker mux hook; subclasses may inject a hermetic mux result. */
    protected VideoMuxer.MuxResult muxOwnedExport(File video, File audio, Path output, Path log,
        ChannelLayout layout, String arguments, BooleanSupplier cancelled, boolean keepLog)
    {
        return VideoMuxer.mux(video, audio, output, log, layout, arguments, cancelled, keepLog);
    }

    /** Real Panel/World sessions override this; null retains the legacy adapter. */
    protected VideoExportRequest createExportRequest(int width, int height) throws Exception
    {
        return null;
    }

    protected final VideoExportRequest createOwnedRequest(double sourceStart, double sourceEnd,
                                                           boolean openEnd, boolean filmAudio,
                                                           boolean minecraftAudio) throws Exception
    {
        return this.createOwnedRequest("", sourceStart, sourceEnd, openEnd, filmAudio, minecraftAudio);
    }

    protected final VideoExportRequest createOwnedRequest(String sourceId, double sourceStart,
                                                           double sourceEnd, boolean openEnd,
                                                           boolean filmAudio,
                                                           boolean minecraftAudio) throws Exception
    {
        ChannelLayout layout = BBSSettings.videoSettings == null
            ? ChannelLayout.MONO
            : BBSSettings.videoSettings.resolveAudioLayout();
        int outputFrameRate = BBSSettings.videoSettings.frameRate.get();
        int motionBlurPasses = BBSRendering.getMotionBlur(outputFrameRate,
            BBSRendering.getMotionBlurFactor(BBSSettings.videoSettings.motionBlur.get()));
        double captureFrameRate = outputFrameRate * (double) (1 << motionBlurPasses);
        int heldFrames = BBSSettings.videoSettings.heldFrames.get();
        boolean limitFrameRate = BBSSettings.videoLimitFrameRate.get();
        String videoArguments = BBSSettings.videoSettings.arguments.get();
        String muxArguments = BBSSettings.videoArgumentsMux == null
            ? VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS : BBSSettings.videoArgumentsMux.get();
        boolean encoderLog = BBSSettings.videoEncoderLog != null && BBSSettings.videoEncoderLog.get();

        VideoExportAudioProfile.validateTemplate(videoArguments, false);
        if (filmAudio || minecraftAudio)
        {
            VideoExportAudioProfile.validateTemplate(muxArguments, true, true);
        }

        VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(
            BBSRendering.getVideoFolder().toPath(), this.getMovieName());
        long generation = ++this.generationCounter;

        try
        {
            return new VideoExportRequest(artifacts.sessionId(), generation, sourceStart, sourceEnd,
                openEnd, captureFrameRate, VideoExportAudioProfile.SAMPLE_RATE, motionBlurPasses, layout,
                filmAudio, minecraftAudio, artifacts, sourceId, outputFrameRate, heldFrames,
                limitFrameRate, this.width, this.height, videoArguments, muxArguments, encoderLog);
        }
        catch (Exception | LinkageError e)
        {
            for (Throwable cleanupFailure : artifacts.cleanup())
            {
                if (cleanupFailure != e)
                {
                    e.addSuppressed(cleanupFailure);
                }
            }

            throw e;
        }
    }

    /** Render only lossless film PCM; base postprocess owns duration and AAC. */
    protected AudioRenderResult renderFilmAudio(VideoExportRequest request, File output,
                                                BooleanSupplier cancelled,
                                                BiConsumer<Long, Long> progress)
    {
        return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, output, request.layout(),
            0L, "No film audio renderer", null);
    }

    /** UI owners may surface degraded/failure status without changing lifecycle ownership. */
    protected void onTerminalResult(VideoExportResult result)
    {}

    /** Called once after an owned request reserves the encoder and before preparation. */
    protected void onOwnedExportStarted(VideoExportRequest request)
    {}

    private void cleanupUnstartedArtifacts()
    {
        if (this.exportArtifacts != null)
        {
            List<Throwable> failures = this.exportArtifacts.cleanup();
            if (!failures.isEmpty()) LOGGER.warn("Failed to clean an unstarted video export", failures.get(0));
        }

        this.exportRequest = null;
        this.exportArtifacts = null;
        this.generationArtifacts.remove(this.activeGeneration);
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

    @FunctionalInterface
    public interface TypedFinishedListener
    {
        void onFinished(VideoExportResult result);
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable
    {
        Subscription EMPTY = () -> {};

        @Override
        void close();
    }
}
