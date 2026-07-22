package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Exact paths plus the requested/expected media contract handed to consumers.
 * When {@code deliveryVerified} is false, delivered layout/rate/codec/duration
 * values are expectations and must be checked against the final path before
 * they are treated as observed media facts.
 */
public record VideoExportArtifact(
    Path finalVideo,
    Path rawVideo,
    Path workDirectory,
    Path recoveryAudio,
    ChannelLayout requestedLayout,
    ChannelLayout deliveredLayout,
    boolean audioPresent,
    long videoFrames,
    long audioFrames,
    int sampleRate,
    String codec,
    String profile,
    int bitrate,
    UUID sessionId,
    long generation,
    String sourceId,
    double sourceStart,
    double sourceEnd,
    boolean openEnd,
    int width,
    int height,
    double captureFrameRate,
    double outputFrameRate,
    int motionBlurPasses,
    long capturedFrames,
    double durationSeconds,
    boolean deliveryVerified)
{
    /** Compatibility constructor for the original rich artifact descriptor. */
    public VideoExportArtifact(Path finalVideo, Path rawVideo, Path workDirectory, Path recoveryAudio,
                               ChannelLayout requestedLayout, ChannelLayout deliveredLayout,
                               boolean audioPresent, long videoFrames, long audioFrames, int sampleRate,
                               String codec, String profile, int bitrate, UUID sessionId, long generation,
                               String sourceId, double sourceStart, double sourceEnd, boolean openEnd,
                               int width, int height, double captureFrameRate, double outputFrameRate,
                               int motionBlurPasses, long capturedFrames, double durationSeconds)
    {
        this(finalVideo, rawVideo, workDirectory, recoveryAudio, requestedLayout, deliveredLayout,
            audioPresent, videoFrames, audioFrames, sampleRate, codec, profile, bitrate,
            sessionId, generation, sourceId, sourceStart, sourceEnd, openEnd, width, height,
            captureFrameRate, outputFrameRate, motionBlurPasses, capturedFrames, durationSeconds, false);
    }

    /** Compatibility constructor for the original artifact shape. */
    public VideoExportArtifact(Path finalVideo, Path rawVideo, Path workDirectory, Path recoveryAudio,
                               ChannelLayout requestedLayout, ChannelLayout deliveredLayout,
                               boolean audioPresent, long videoFrames, long audioFrames, int sampleRate,
                               String codec, String profile, int bitrate)
    {
        this(finalVideo, rawVideo, workDirectory, recoveryAudio, requestedLayout, deliveredLayout,
            audioPresent, videoFrames, audioFrames, sampleRate, codec, profile, bitrate,
            null, 0L, "", 0D, 0D, false, 0, 0, 0D, 0D, 0, 0L, 0D, false);
    }

    public static VideoExportArtifact empty(ChannelLayout requestedLayout)
    {
        return new VideoExportArtifact(null, null, null, null, requestedLayout, null,
            false, 0L, 0L, 0, null, null, 0,
            null, 0L, "", 0D, 0D, false, 0, 0, 0D, 0D, 0, 0L, 0D, false);
    }
}
