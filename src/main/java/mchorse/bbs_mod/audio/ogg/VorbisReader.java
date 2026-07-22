package mchorse.bbs_mod.audio.ogg;

import mchorse.bbs_mod.audio.AudioDecodeException;
import mchorse.bbs_mod.audio.AudioDecodeLimitException;
import mchorse.bbs_mod.audio.AudioDecodeLimits;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.MalformedAudioException;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.UnsupportedAudioFormatException;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.resources.Link;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public class VorbisReader
{
    private static final int READ_BUFFER_SIZE = 8 * 1024;
    private static final int DECODE_BLOCK_FRAMES = 4096;

    public static Wave read(Link link, InputStream stream) throws IOException
    {
        return read(link, stream, AudioDecodeLimits.DEFAULT);
    }

    public static Wave read(Link link, InputStream stream, AudioDecodeLimits limits) throws IOException
    {
        String source = link == null ? "Vorbis audio" : link.toString();

        if (stream == null)
        {
            throw new AudioDecodeException(source, "Input stream is null");
        }

        if (limits == null)
        {
            throw new AudioDecodeException(source, "Decode limits are null");
        }

        ByteBuffer encoded = readBounded(stream, limits.maxContainerBytes(), source);

        if (!encoded.hasRemaining())
        {
            MemoryUtil.memFree(encoded);
            throw new MalformedAudioException(source, "Vorbis stream is empty");
        }

        ShortBuffer samples = null;

        try
        {
            try (MemoryStack stack = MemoryStack.stackPush())
            {
                IntBuffer error = stack.callocInt(1);
                STBVorbisInfo info = STBVorbisInfo.malloc(stack);
                long decoder = STBVorbis.stb_vorbis_open_memory(encoded, error, null);

                if (decoder == MemoryUtil.NULL)
                {
                    throw new MalformedAudioException(source,
                        "Could not open Vorbis stream (stb_vorbis error " + error.get(0) + ")");
                }

                try
                {
                    STBVorbis.stb_vorbis_get_info(decoder, info);

                    int channels = info.channels();
                    int sampleRate = info.sample_rate();

                    if (channels != 1 && channels != 2)
                    {
                        throw new UnsupportedAudioFormatException(source,
                            "Vorbis channel count must be mono or stereo, got " + channels);
                    }

                    if (sampleRate <= 0)
                    {
                        throw new MalformedAudioException(source,
                            "Vorbis sample rate must be positive, got " + sampleRate);
                    }

                    long byteRate = checkedMultiply(sampleRate, channels, source, "Vorbis byte rate");
                    byteRate = checkedMultiply(byteRate, Short.BYTES, source, "Vorbis byte rate");

                    if (byteRate > Integer.MAX_VALUE)
                    {
                        throw new UnsupportedAudioFormatException(source,
                            "Vorbis sample rate is too large for PCM output: " + sampleRate);
                    }

                    long reportedFrames = Integer.toUnsignedLong(
                        STBVorbis.stb_vorbis_stream_length_in_samples(decoder));
                    int lengthError = STBVorbis.stb_vorbis_get_error(decoder);

                    if (lengthError != 0)
                    {
                        throw new MalformedAudioException(source,
                            "Could not determine Vorbis frame count (stb_vorbis error " + lengthError + ")");
                    }

                    if (reportedFrames > limits.maxFrames())
                    {
                        throw new AudioDecodeLimitException(source,
                            "Vorbis frame count " + reportedFrames + " exceeds limit " + limits.maxFrames());
                    }

                    long sampleCount = checkedMultiply(reportedFrames, channels, source,
                        "Vorbis decoded sample count");
                    long decodedBytes = checkedMultiply(sampleCount, Short.BYTES, source,
                        "Vorbis decoded byte count");

                    if (decodedBytes > limits.maxDecodedBytes())
                    {
                        throw new AudioDecodeLimitException(source,
                            "Vorbis decoded data " + decodedBytes + " bytes exceeds limit "
                                + limits.maxDecodedBytes());
                    }

                    if (sampleCount > Integer.MAX_VALUE || decodedBytes > Integer.MAX_VALUE)
                    {
                        throw new AudioDecodeLimitException(source,
                            "Vorbis decoded data is too large for a Java PCM buffer: " + decodedBytes + " bytes");
                    }

                    if (reportedFrames > Integer.MAX_VALUE)
                    {
                        throw new AudioDecodeLimitException(source,
                            "Vorbis frame count cannot fit a Java frame counter: " + reportedFrames);
                    }

                    int expectedFrames = (int) reportedFrames;
                    int blockSamples = Math.multiplyExact(Math.max(1, Math.min(expectedFrames,
                        DECODE_BLOCK_FRAMES)), channels);
                    samples = MemoryUtil.memAllocShort(blockSamples);
                    byte[] data = new byte[Math.toIntExact(decodedBytes)];
                    int decodedFrames = 0;

                    while (decodedFrames < expectedFrames)
                    {
                        int requestedFrames = Math.min(DECODE_BLOCK_FRAMES, expectedFrames - decodedFrames);
                        int requestedSamples = Math.multiplyExact(requestedFrames, channels);
                        samples.clear();
                        samples.limit(requestedSamples);

                        int blockFrames = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                            decoder, channels, samples);
                        int decodeError = STBVorbis.stb_vorbis_get_error(decoder);

                        if (blockFrames < 0 || blockFrames > requestedFrames || decodeError != 0)
                        {
                            throw new MalformedAudioException(source,
                                "Vorbis decoder failed after " + decodedFrames
                                    + " frames (returned " + blockFrames
                                    + ", stb_vorbis error " + decodeError + ")");
                        }
                        if (blockFrames == 0)
                        {
                            throw new MalformedAudioException(source,
                                "Vorbis decoder returned a short decode after " + decodedFrames
                                    + " of " + expectedFrames + " frames");
                        }

                        int blockSampleCount = Math.multiplyExact(blockFrames, channels);

                        for (int i = 0; i < blockSampleCount; i++)
                        {
                            short sample = samples.get(i);
                            int offset = Math.multiplyExact(
                                Math.addExact(Math.multiplyExact(decodedFrames, channels), i), Short.BYTES);
                            data[offset] = (byte) sample;
                            data[offset + 1] = (byte) (sample >>> 8);
                        }

                        decodedFrames = Math.addExact(decodedFrames, blockFrames);
                    }

                    samples.clear();
                    samples.limit(channels);
                    int extraFrames = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                        decoder, channels, samples);
                    int decodeError = STBVorbis.stb_vorbis_get_error(decoder);

                    if (extraFrames < 0 || decodeError != 0)
                    {
                        throw new MalformedAudioException(source,
                            "Vorbis decoder failed at end of stream (frames=" + extraFrames
                                + ", stb_vorbis error " + decodeError + ")");
                    }

                    if (extraFrames != 0)
                    {
                        throw new MalformedAudioException(source,
                            "Vorbis stream contains more frames than its reported length " + reportedFrames);
                    }

                    ChannelLayout layout = channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO;

                    return new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, layout, sampleRate), data);
                }
                finally
                {
                    STBVorbis.stb_vorbis_close(decoder);
                }
            }
        }
        finally
        {
            if (samples != null)
            {
                MemoryUtil.memFree(samples);
            }

            MemoryUtil.memFree(encoded);
        }
    }

    private static ByteBuffer readBounded(InputStream stream, long limit, String source) throws IOException
    {
        int maxCapacity = (int) Math.min((long) Integer.MAX_VALUE, limit);
        int initialSize = Math.max(1, Math.min(READ_BUFFER_SIZE, maxCapacity));
        ByteBuffer output = null;
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long total = 0;

        try
        {
            output = MemoryUtil.memAlloc(initialSize);

            while (true)
            {
                int count = stream.read(buffer);

                if (count < 0)
                {
                    break;
                }

                if (count == 0)
                {
                    int value = stream.read();

                    if (value < 0)
                    {
                        break;
                    }

                    if (total >= limit || total >= Integer.MAX_VALUE)
                    {
                        throw containerLimit(source, limit);
                    }

                    output = grow(output, Math.toIntExact(total + 1L), maxCapacity);
                    output.put((byte) value);
                    total += 1;

                    continue;
                }

                if (count > limit - total || count > Integer.MAX_VALUE - total)
                {
                    throw containerLimit(source, limit);
                }

                output = grow(output, Math.toIntExact(total + count), maxCapacity);
                output.put(buffer, 0, count);
                total += count;
            }

            output.flip();
            return output;
        }
        catch (IOException | RuntimeException | Error e)
        {
            if (output != null)
            {
                MemoryUtil.memFree(output);
            }

            throw e;
        }
    }

    private static ByteBuffer grow(ByteBuffer buffer, int required, int maxCapacity)
    {
        if (required <= buffer.capacity())
        {
            return buffer;
        }

        long doubled = Math.min((long) maxCapacity, Math.max(1L, (long) buffer.capacity() * 2L));
        int capacity = (int) Math.max((long) required, doubled);

        return MemoryUtil.memRealloc(buffer, capacity);
    }

    private static AudioDecodeLimitException containerLimit(String source, long limit)
    {
        return new AudioDecodeLimitException(source,
            "Vorbis encoded input exceeds container limit " + limit + " bytes");
    }

    private static long checkedMultiply(long left, long right, String source, String description)
        throws AudioDecodeLimitException
    {
        try
        {
            return Math.multiplyExact(left, right);
        }
        catch (ArithmeticException e)
        {
            throw new AudioDecodeLimitException(source, description + " overflows", e);
        }
    }
}
