package mchorse.bbs_mod.audio;

import java.util.Objects;

/** Explicit little-endian sample access shared by readers, renderers, and UI. */
public final class PcmSamples
{
    private PcmSamples()
    {}

    public static double readNormalized(PcmEncoding encoding, byte[] data, int offset)
    {
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(data, "data");

        if (offset < 0 || offset > data.length - encoding.bytesPerSample())
        {
            throw new IllegalArgumentException("Sample offset outside PCM data: " + offset);
        }

        return switch (encoding)
        {
            case PCM_U8 -> ((data[offset] & 0xff) - 128) / 128.0;
            case PCM_S16_LE -> (short) ((data[offset] & 0xff) | (data[offset + 1] << 8)) / 32768.0;
            case PCM_S24_LE -> signExtend24(data[offset] & 0xff
                | (data[offset + 1] & 0xff) << 8
                | (data[offset + 2] & 0xff) << 16) / 8388608.0;
            case PCM_S32_LE -> (data[offset] & 0xff
                | (data[offset + 1] & 0xff) << 8
                | (data[offset + 2] & 0xff) << 16
                | data[offset + 3] << 24) / 2147483648.0;
            case IEEE_FLOAT32_LE -> readFloat(data, offset);
        };
    }

    public static double readNormalized(Wave wave, long frame, int channel)
    {
        PcmFormat format = wave.getFormat();
        if (frame < 0 || frame >= wave.getFrameCount() || channel < 0 || channel >= format.channels())
        {
            throw new IllegalArgumentException("PCM frame/channel outside data");
        }

        long sampleOffset = Math.addExact(Math.multiplyExact(frame, format.bytesPerFrame()),
            Math.multiplyExact(channel, format.bytesPerSample()));

        if (sampleOffset > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException("PCM offset exceeds Java array range");
        }

        try
        {
            return readNormalized(format.encoding(), wave.data, (int) sampleOffset);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException("Could not read PCM frame " + frame
                + " channel " + channel, e);
        }
    }

    public static void writeNormalized(PcmEncoding encoding, byte[] data, int offset, double value)
    {
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(data, "data");

        if (offset < 0 || offset > data.length - encoding.bytesPerSample())
        {
            throw new IllegalArgumentException("Sample offset outside PCM data: " + offset);
        }

        if (!Double.isFinite(value))
        {
            throw new IllegalArgumentException("PCM sample is not finite");
        }

        double clamped = Math.max(-1.0, Math.min(1.0, value));

        switch (encoding)
        {
            case PCM_U8 -> data[offset] = (byte) Math.max(0, Math.min(255, (int) Math.round(clamped * 128.0 + 128.0)));
            case PCM_S16_LE -> writeLittleEndian(data, offset, quantize(clamped, -32768L, 32767L, 32768.0), 2);
            case PCM_S24_LE -> writeLittleEndian(data, offset, quantize(clamped, -8388608L, 8388607L, 8388608.0), 3);
            case PCM_S32_LE -> writeLittleEndian(data, offset, quantize(clamped, Integer.MIN_VALUE, Integer.MAX_VALUE, 2147483648.0), 4);
            case IEEE_FLOAT32_LE -> writeLittleEndian(data, offset, Float.floatToIntBits((float) clamped) & 0xffffffffL, 4);
        }
    }

    private static long quantize(double value, long min, long max, double scale)
    {
        if (value <= -1.0)
        {
            return min;
        }

        if (value >= 1.0)
        {
            return max;
        }

        return Math.max(min, Math.min(max, Math.round(value * scale)));
    }

    private static int signExtend24(int value)
    {
        return (value & 0x00800000) != 0 ? value | 0xff000000 : value;
    }

    private static double readFloat(byte[] data, int offset)
    {
        int bits = data[offset] & 0xff
            | (data[offset + 1] & 0xff) << 8
            | (data[offset + 2] & 0xff) << 16
            | data[offset + 3] << 24;
        float value = Float.intBitsToFloat(bits);

        if (!Float.isFinite(value))
        {
            throw new IllegalArgumentException("PCM float sample is not finite");
        }

        return value;
    }

    private static void writeLittleEndian(byte[] data, int offset, long value, int bytes)
    {
        for (int i = 0; i < bytes; i++)
        {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
    }
}
