package mchorse.bbs_mod.ui.film.audio;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;

import java.util.Objects;

/** Immutable microphone format and resource limits for one capture session. */
public record CaptureSpec(int sampleRate, int channels, int ringSamples, long maxFrames)
{
    public static final int DEFAULT_SAMPLE_RATE = 44100;
    public static final int DEFAULT_RING_SECONDS = 1;
    public static final long DEFAULT_MAX_FRAMES = (long) DEFAULT_SAMPLE_RATE * 60L * 60L;

    public CaptureSpec
    {
        if (sampleRate <= 0)
        {
            throw new IllegalArgumentException("Sample rate must be positive");
        }

        if (channels != 1 && channels != 2)
        {
            throw new IllegalArgumentException("Only mono and stereo capture are supported");
        }

        int minimumRingSamples = (int) Math.max(1L, ((long) sampleRate + 9L) / 10L);

        if (ringSamples < minimumRingSamples)
        {
            throw new IllegalArgumentException("Capture ring is too small for polling cadence");
        }

        if (maxFrames <= 0)
        {
            throw new IllegalArgumentException("Capture frame limit must be positive");
        }
    }

    public CaptureSpec(int sampleRate, int channels)
    {
        this(sampleRate, channels, sampleRate * DEFAULT_RING_SECONDS, (long) sampleRate * 60L * 60L);
    }

    public static CaptureSpec mono()
    {
        return new CaptureSpec(DEFAULT_SAMPLE_RATE, 1);
    }

    public static CaptureSpec stereo()
    {
        return new CaptureSpec(DEFAULT_SAMPLE_RATE, 2);
    }

    public int bytesPerFrame()
    {
        return Math.multiplyExact(this.channels, PcmEncoding.PCM_S16_LE.bytesPerSample());
    }

    public PcmFormat pcmFormat()
    {
        return new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.fromChannelCount(this.channels), this.sampleRate);
    }

    public void validate()
    {
        Objects.requireNonNull(this.pcmFormat(), "pcmFormat");
    }
}
