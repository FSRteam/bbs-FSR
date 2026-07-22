package mchorse.bbs_mod.audio;

import java.io.File;
import java.util.Objects;

/** Typed terminal result for client-side PCM rendering. */
public record AudioRenderResult(Status status, File output, ChannelLayout layout,
                                long frames, String message, Throwable cause)
{
    public AudioRenderResult
    {
        Objects.requireNonNull(status, "status");

        if (frames < 0L)
        {
            throw new IllegalArgumentException("Rendered frame count cannot be negative");
        }

        if (status == Status.SUCCESS
            && (output == null || layout == null || !layout.supported()))
        {
            throw new IllegalArgumentException("Successful audio render result requires an output and supported layout");
        }
    }

    public enum Status
    {
        SUCCESS,
        EMPTY,
        CANCELLED,
        UNSUPPORTED_FORMAT,
        MISSING_RESOURCE,
        IO_FAILURE,
        MIX_FAILURE
    }

    public boolean success()
    {
        return this.status == Status.SUCCESS;
    }

    public static AudioRenderResult success(File output, ChannelLayout layout, long frames)
    {
        return new AudioRenderResult(Status.SUCCESS, output, layout, frames, null, null);
    }

    public static AudioRenderResult failure(Status status, File output, ChannelLayout layout,
                                             long frames, String message, Throwable cause)
    {
        if (status == Status.SUCCESS)
        {
            throw new IllegalArgumentException("Use success() for a successful audio render result");
        }

        return new AudioRenderResult(status, output, layout, frames, message, cause);
    }
}
