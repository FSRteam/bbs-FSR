package mchorse.bbs_mod.audio;

import java.util.Objects;

/** Immutable, validated PCM metadata used between audio pipeline layers. */
public record PcmFormat(PcmEncoding encoding, ChannelLayout layout, int sampleRate)
{
    public PcmFormat
    {
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(layout, "layout");

        if (!layout.supported())
        {
            throw new IllegalArgumentException("Unsupported channel layout: " + layout.id());
        }

        if (sampleRate <= 0)
        {
            throw new IllegalArgumentException("Sample rate must be positive: " + sampleRate);
        }
    }

    public int channels()
    {
        return this.layout.channels();
    }

    public int bytesPerSample()
    {
        return this.encoding.bytesPerSample();
    }

    public int bytesPerFrame()
    {
        return Math.multiplyExact(this.channels(), this.bytesPerSample());
    }

    public long byteRate()
    {
        return Math.multiplyExact((long) this.sampleRate, this.bytesPerFrame());
    }

    public int waveTag()
    {
        return this.encoding.waveTag();
    }
}
