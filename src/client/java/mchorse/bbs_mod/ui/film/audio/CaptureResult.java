package mchorse.bbs_mod.ui.film.audio;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable capture snapshot. READY transfers an owned file to the client commit transaction. */
public final class CaptureResult
{
    public enum Status
    {
        SUCCESS,
        CANCELLED,
        FAILED,
        /** Finalized file awaiting the owning client-thread commit transaction. */
        READY
    }

    private final Status status;
    private final CaptureFailure failure;
    private final Path temporaryFile;
    private final long frames;
    private final int sampleRate;
    private final int channels;
    private final Throwable cause;

    private CaptureResult(Status status, CaptureFailure failure, Path temporaryFile,
                          long frames, int sampleRate, int channels, Throwable cause)
    {
        this.status = Objects.requireNonNull(status, "status");
        this.failure = failure;
        this.temporaryFile = temporaryFile;
        this.frames = frames;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.cause = cause;
    }

    public static CaptureResult success(Path temporaryFile, long frames, int sampleRate, int channels)
    {
        validateCapturedAudio(temporaryFile, frames, sampleRate, channels);

        return new CaptureResult(Status.SUCCESS, null, temporaryFile, frames, sampleRate, channels, null);
    }

    public static CaptureResult ready(Path temporaryFile, long frames, int sampleRate, int channels)
    {
        validateCapturedAudio(temporaryFile, frames, sampleRate, channels);

        return new CaptureResult(Status.READY, null, temporaryFile, frames, sampleRate, channels, null);
    }

    private static void validateCapturedAudio(Path temporaryFile, long frames, int sampleRate, int channels)
    {
        if (frames <= 0L)
        {
            throw new IllegalArgumentException("A successful capture must contain at least one frame");
        }

        if (sampleRate <= 0 || (channels != 1 && channels != 2))
        {
            throw new IllegalArgumentException("A successful capture has an unsupported format");
        }

        Objects.requireNonNull(temporaryFile, "temporaryFile");
    }

    public static CaptureResult cancelled(int sampleRate, int channels)
    {
        return new CaptureResult(Status.CANCELLED, null, null, 0L, sampleRate, channels, null);
    }

    public static CaptureResult failed(CaptureFailure failure, Throwable cause, int sampleRate, int channels)
    {
        return new CaptureResult(Status.FAILED, Objects.requireNonNull(failure, "failure"), null,
            0L, sampleRate, channels, cause);
    }

    public Status status()
    {
        return this.status;
    }

    public CaptureFailure failure()
    {
        return this.failure;
    }

    public Path temporaryFile()
    {
        return this.temporaryFile;
    }

    public long frames()
    {
        return this.frames;
    }

    public int sampleRate()
    {
        return this.sampleRate;
    }

    public int channels()
    {
        return this.channels;
    }

    public Throwable cause()
    {
        return this.cause;
    }

    public boolean isSuccess()
    {
        return this.status == Status.SUCCESS;
    }

    public boolean isCancelled()
    {
        return this.status == Status.CANCELLED;
    }

    public boolean isReady()
    {
        return this.status == Status.READY;
    }

    public boolean isFailure()
    {
        return this.status == Status.FAILED;
    }

    public String userMessage()
    {
        if (this.status == Status.CANCELLED)
        {
            return "Microphone recording cancelled";
        }

        if (this.status == Status.SUCCESS)
        {
            return "Microphone recording finished";
        }

        if (this.status == Status.READY)
        {
            return "Microphone recording is ready to commit";
        }

        return switch (this.failure)
        {
            case NO_DEVICE -> "No microphone capture device is available";
            case DEVICE_ENUMERATION_FAILED -> "Microphone devices could not be enumerated";
            case UNSUPPORTED_MODE -> "The selected microphone channel mode is unsupported";
            case DEVICE_OPEN_FAILED -> "The microphone could not be opened";
            case DEVICE_START_FAILED -> "The microphone could not be started";
            case DEVICE_READ_FAILED -> "The microphone stopped providing samples";
            case DEVICE_STOP_FAILED -> "The microphone could not be stopped cleanly";
            case DEVICE_CLOSE_FAILED -> "The microphone could not be closed cleanly";
            case STORAGE_FAILED -> "The recording could not be written to disk";
            case CAPTURE_OVERFLOW -> "The recorder could not keep up with the microphone";
            case DURATION_LIMIT -> "The recording reached its duration limit";
            case CALLBACK_FAILED -> "The recording completion callback failed";
            case COMMIT_FAILED -> "The recording could not be committed";
        };
    }

    @Override
    public String toString()
    {
        return "CaptureResult{" + this.status + ", failure=" + this.failure + ", frames=" + this.frames +
            ", sampleRate=" + this.sampleRate + ", channels=" + this.channels + '}';
    }
}
