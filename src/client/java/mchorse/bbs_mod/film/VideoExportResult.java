package mchorse.bbs_mod.film;

import java.util.List;

/** Typed terminal outcome.  The legacy boolean callback maps every non-success to aborted. */
public record VideoExportResult(
    Kind kind,
    Stage stage,
    VideoExportArtifact artifact,
    Throwable cause,
    List<Throwable> cleanupFailures,
    String message,
    FailureKind failureKind)
{
    public enum Kind
    {
        SUCCESS,
        DEGRADED,
        CANCELLED,
        PREPARATION_FAILED,
        START_FAILED,
        ENCODE_FAILED,
        AUDIO_FAILED,
        MUX_FAILED,
        PUBLISH_FAILED,
        CLEANUP_FAILED
    }

    public enum Stage
    {
        PREPARATION,
        START,
        RECORDING,
        AUDIO_RENDER,
        AUDIO_MIX,
        MISSING_RESOURCE,
        MUX,
        PUBLISH,
        CLEANUP,
        CANCELLED,
        COMPLETE
    }

    public enum FailureKind
    {
        NONE,
        AUDIO_RENDER,
        AUDIO_MIX,
        MISSING_RESOURCE,
        MUX,
        PUBLISH,
        CLEANUP,
        PREPARATION,
        START,
        ENCODE
    }

    /** Compatibility constructor for callers written against the initial typed shape. */
    public VideoExportResult(Kind kind, Stage stage, VideoExportArtifact artifact,
                             Throwable cause, List<Throwable> cleanupFailures, String message)
    {
        this(kind, stage, artifact, cause, cleanupFailures, message, inferFailureKind(kind, stage));
    }

    public VideoExportResult
    {
        cleanupFailures = cleanupFailures == null ? List.of() : List.copyOf(cleanupFailures);
        message = message == null ? "" : message;
        failureKind = failureKind == null ? inferFailureKind(kind, stage) : failureKind;
    }

    public boolean isSuccess()
    {
        return this.kind == Kind.SUCCESS;
    }

    public boolean isDegraded()
    {
        return this.kind == Kind.DEGRADED;
    }

    public boolean isAborted()
    {
        return !this.isSuccess();
    }

    /** Stable identity used by consumers to reject stale completions. */
    public java.util.UUID sessionId()
    {
        return this.artifact == null ? null : this.artifact.sessionId();
    }

    public long generation()
    {
        return this.artifact == null ? 0L : this.artifact.generation();
    }

    private static FailureKind inferFailureKind(Kind kind, Stage stage)
    {
        if (kind == Kind.CANCELLED) return FailureKind.NONE;
        if (stage == Stage.AUDIO_RENDER) return FailureKind.AUDIO_RENDER;
        if (stage == Stage.AUDIO_MIX) return FailureKind.AUDIO_MIX;
        if (stage == Stage.MISSING_RESOURCE) return FailureKind.MISSING_RESOURCE;
        if (stage == Stage.MUX) return FailureKind.MUX;
        if (stage == Stage.PUBLISH) return FailureKind.PUBLISH;
        if (stage == Stage.CLEANUP) return FailureKind.CLEANUP;
        if (stage == Stage.PREPARATION) return FailureKind.PREPARATION;
        if (stage == Stage.START) return FailureKind.START;
        if (stage == Stage.RECORDING) return FailureKind.ENCODE;
        return FailureKind.NONE;
    }
}
