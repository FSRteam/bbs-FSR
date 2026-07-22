package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;

import java.util.Objects;
import java.util.UUID;

/** Immutable timing and delivery snapshot for one export generation. */
public record VideoExportRequest(
    UUID sessionId,
    long generation,
    double sourceStart,
    double sourceEnd,
    boolean openEnd,
    double frameRate,
    int sampleRate,
    int motionBlurPasses,
    ChannelLayout layout,
    boolean filmAudio,
    boolean minecraftAudio,
    VideoExportArtifacts artifacts,
    String sourceId,
    double outputFrameRate,
    int heldFrames,
    boolean limitFrameRate,
    int width,
    int height,
    String videoArguments,
    String muxArguments,
    boolean encoderLog)
{
    /** Compatibility constructor for callers that do not snapshot blur passes. */
    public VideoExportRequest(UUID sessionId, long generation, double sourceStart, double sourceEnd,
                              boolean openEnd, double frameRate, int sampleRate, ChannelLayout layout,
                              boolean filmAudio, boolean minecraftAudio, VideoExportArtifacts artifacts)
    {
        this(sessionId, generation, sourceStart, sourceEnd, openEnd, frameRate, sampleRate, 0,
            layout, filmAudio, minecraftAudio, artifacts, "", frameRate, 1, false,
            0, 0, "", "", false);
    }

    /** Compatibility constructor carrying only the motion-blur snapshot. */
    public VideoExportRequest(UUID sessionId, long generation, double sourceStart, double sourceEnd,
                              boolean openEnd, double frameRate, int sampleRate, int motionBlurPasses,
                              ChannelLayout layout, boolean filmAudio, boolean minecraftAudio,
                              VideoExportArtifacts artifacts)
    {
        this(sessionId, generation, sourceStart, sourceEnd, openEnd, frameRate, sampleRate,
            motionBlurPasses, layout, filmAudio, minecraftAudio, artifacts, "", frameRate,
            1, false, 0, 0, "", "", false);
    }

    public VideoExportRequest
    {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(artifacts, "artifacts");

        sourceId = sourceId == null ? "" : sourceId;
        videoArguments = videoArguments == null ? "" : videoArguments;
        muxArguments = muxArguments == null ? "" : muxArguments;

        if (!Double.isFinite(sourceStart) || sourceStart < 0D
            || (!openEnd && (!Double.isFinite(sourceEnd) || sourceEnd <= sourceStart)))
        {
            throw new IllegalArgumentException("Video export range must be a non-empty half-open interval");
        }

        if (!Double.isFinite(frameRate) || frameRate <= 0D || !Double.isFinite(outputFrameRate)
            || outputFrameRate <= 0D || sampleRate != VideoExportAudioProfile.SAMPLE_RATE
            || motionBlurPasses < 0
            || heldFrames <= 0 || width < 0 || height < 0)
        {
            throw new IllegalArgumentException("Video export rate is invalid");
        }

        if (!layout.supported() || (layout != ChannelLayout.MONO && layout != ChannelLayout.STEREO))
        {
            throw new IllegalArgumentException("Unsupported video export layout: " + layout);
        }
    }

    /** Convert the half-open delivered video duration to the smallest covering PCM frame count. */
    public long audioFramesFor(long deliveredVideoFrames)
    {
        if (deliveredVideoFrames <= 0L)
        {
            return 0L;
        }

        double frames = deliveredVideoFrames * this.sampleRate / this.frameRate;

        /* A partial final sample must be retained; rounding down would shorten
         * the audio timeline and make the muxed stream end early. */
        return Math.max(1L, (long) Math.ceil(frames));
    }

    /** Effective capture rate used by the raw pipe and PCM timeline. */
    public double captureFrameRate()
    {
        return this.frameRate;
    }

    /** Number of final output frames implied by the delivered capture duration. */
    public long outputFramesFor(long deliveredCaptureFrames)
    {
        if (deliveredCaptureFrames <= 0L)
        {
            return 0L;
        }

        if (this.motionBlurPasses < Long.SIZE - 1)
        {
            long divisor = 1L << this.motionBlurPasses;
            double expectedCaptureRate = this.outputFrameRate * divisor;

            if (Math.abs(expectedCaptureRate - this.frameRate) <= Math.max(1D, this.frameRate) * 1.0E-9D)
            {
                return Math.max(1L, Math.floorDiv(deliveredCaptureFrames - 1L, divisor) + 1L);
            }
        }

        return Math.max(1L, Math.round(deliveredCaptureFrames * this.outputFrameRate / this.frameRate));
    }

    public double durationSecondsFor(long deliveredCaptureFrames)
    {
        return deliveredCaptureFrames <= 0L ? 0D : deliveredCaptureFrames / this.frameRate;
    }
}
