package mchorse.bbs_mod.film;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.utils.VideoRecorder;
import org.slf4j.Logger;

import java.io.File;

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

    protected State state = State.IDLE;
    protected long warmupEndsAtMs;

    protected File audioFile;
    protected int textureId;
    protected int width;
    protected int height;

    private FinishedListener finishedListener;

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
        return this.state == State.RECORDING && this.getRecorder().isRecording();
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

    protected final boolean begin(int textureId, int width, int height, long delayMs)
    {
        VideoRecorder recorder = this.getRecorder();

        if (this.isExporting() || recorder == null || recorder.isRecording())
        {
            return false;
        }

        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.audioFile = null;

        if (!this.prepare())
        {
            this.reset();

            return false;
        }

        this.applyExportTarget();

        if (delayMs > 0L)
        {
            this.state = State.WARMUP;
            this.warmupEndsAtMs = System.currentTimeMillis() + delayMs;
            this.onWarmupStarted();
        }
        else
        {
            this.beginRecording();
        }

        return this.isExporting();
    }

    public final void update()
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
        else if (this.state == State.RECORDING && this.isFinished())
        {
            this.stop();
        }
    }

    private void beginRecording()
    {
        VideoRecorder recorder = this.getRecorder();

        /* Enter the state before starting so every failure takes the teardown path. */
        this.state = State.RECORDING;

        try
        {
            recorder.startRecording(this.audioFile, this.textureId, this.width, this.height);
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to start video export", e);
            this.cancel();

            return;
        }

        if (!recorder.isRecording())
        {
            this.cancel();

            return;
        }

        this.onRecordingStarted();
    }

    public final void stop()
    {
        this.finish(false);
    }

    public final void cancel()
    {
        this.finish(true);
    }

    private void finish(boolean cancelled)
    {
        if (this.state == State.IDLE)
        {
            return;
        }

        VideoRecorder recorder = this.getRecorder();

        if (recorder != null && recorder.isRecording())
        {
            try
            {
                recorder.stopRecording();
            }
            catch (Exception e)
            {
                LOGGER.error("Failed to stop video export cleanly", e);
            }
        }

        this.state = State.IDLE;

        try
        {
            this.teardown(cancelled);
        }
        finally
        {
            this.reset();
        }

        FinishedListener listener = this.finishedListener;
        this.finishedListener = null;

        if (listener != null)
        {
            listener.onFinished(cancelled);
        }
    }

    private void reset()
    {
        this.state = State.IDLE;
        this.warmupEndsAtMs = 0L;
        this.audioFile = null;
        this.textureId = 0;
        this.width = 0;
        this.height = 0;
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
    public interface FinishedListener
    {
        void onFinished(boolean cancelled);
    }
}
