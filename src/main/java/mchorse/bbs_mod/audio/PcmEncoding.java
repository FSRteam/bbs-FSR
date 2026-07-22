package mchorse.bbs_mod.audio;

import java.nio.ByteOrder;

/** Supported little-endian PCM encodings at the audio boundary. */
public enum PcmEncoding
{
    PCM_U8(1, 8, 1, SampleType.UNSIGNED_INTEGER, ByteOrder.LITTLE_ENDIAN, true, true),
    PCM_S16_LE(1, 16, 2, SampleType.SIGNED_INTEGER, ByteOrder.LITTLE_ENDIAN, true, true),
    PCM_S24_LE(1, 24, 3, SampleType.SIGNED_INTEGER, ByteOrder.LITTLE_ENDIAN, false, true),
    PCM_S32_LE(1, 32, 4, SampleType.SIGNED_INTEGER, ByteOrder.LITTLE_ENDIAN, false, true),
    IEEE_FLOAT32_LE(3, 32, 4, SampleType.FLOATING_POINT, ByteOrder.LITTLE_ENDIAN, false, true);

    private final int waveTag;
    private final int bitsPerSample;
    private final int bytesPerSample;
    private final SampleType sampleType;
    private final ByteOrder byteOrder;
    private final boolean openAlCompatible;
    private final boolean exportSupported;

    PcmEncoding(int waveTag, int bitsPerSample, int bytesPerSample, SampleType sampleType,
        ByteOrder byteOrder, boolean openAlCompatible, boolean exportSupported)
    {
        this.waveTag = waveTag;
        this.bitsPerSample = bitsPerSample;
        this.bytesPerSample = bytesPerSample;
        this.sampleType = sampleType;
        this.byteOrder = byteOrder;
        this.openAlCompatible = openAlCompatible;
        this.exportSupported = exportSupported;
    }

    public int waveTag()
    {
        return this.waveTag;
    }

    public int bitsPerSample()
    {
        return this.bitsPerSample;
    }

    public int bytesPerSample()
    {
        return this.bytesPerSample;
    }

    public boolean floatingPoint()
    {
        return this.sampleType == SampleType.FLOATING_POINT;
    }

    public boolean signed()
    {
        return this.sampleType == SampleType.SIGNED_INTEGER;
    }

    public boolean unsigned()
    {
        return this.sampleType == SampleType.UNSIGNED_INTEGER;
    }

    public ByteOrder byteOrder()
    {
        return this.byteOrder;
    }

    public boolean openAlCompatible()
    {
        return this.openAlCompatible;
    }

    public boolean exportSupported()
    {
        return this.exportSupported;
    }

    public static PcmEncoding fromWaveFormat(int tag, int bitsPerSample)
    {
        for (PcmEncoding encoding : values())
        {
            if (encoding.waveTag == tag && encoding.bitsPerSample == bitsPerSample)
            {
                return encoding;
            }
        }

        throw new IllegalArgumentException("Unsupported WAV encoding tag=" + tag + " bits=" + bitsPerSample);
    }

    private enum SampleType
    {
        UNSIGNED_INTEGER,
        SIGNED_INTEGER,
        FLOATING_POINT
    }
}
