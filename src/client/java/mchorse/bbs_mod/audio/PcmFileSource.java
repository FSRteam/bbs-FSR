package mchorse.bbs_mod.audio;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

/** Bounded, positional PCM reader used by the export worker. */
final class PcmFileSource implements AutoCloseable
{
    private static final int CACHE_FRAMES = 8_192;
    private static final byte[] PCM_SUBFORMAT_GUID = new byte[] {
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        (byte) 0x80, 0x00, 0x00, (byte) 0xaa, 0x00, 0x38, (byte) 0x9b, 0x71
    };
    private static final byte[] FLOAT_SUBFORMAT_GUID = new byte[] {
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        (byte) 0x80, 0x00, 0x00, (byte) 0xaa, 0x00, 0x38, (byte) 0x9b, 0x71
    };

    private final FileChannel channel;
    private final PcmFormat format;
    private final long dataOffset;
    private final long dataBytes;
    private final long frames;
    private final byte[] cache;
    private long cachedFrame = -1L;
    private int cachedFrames;

    private PcmFileSource(FileChannel channel, Descriptor descriptor)
    {
        this.channel = channel;
        this.format = descriptor.format();
        this.dataOffset = descriptor.dataOffset();
        this.dataBytes = descriptor.dataBytes();
        this.frames = this.dataBytes / this.format.bytesPerFrame();
        long cacheBytes = Math.min(this.dataBytes,
            Math.multiplyExact((long) CACHE_FRAMES, this.format.bytesPerFrame()));
        this.cache = new byte[Math.toIntExact(cacheBytes)];
    }

    static Descriptor describeRaw(Path path, PcmFormat format, long dataBytes)
    {
        return new Descriptor(path, format, 0L, dataBytes);
    }

    static PcmFileSource open(Descriptor descriptor) throws IOException
    {
        Objects.requireNonNull(descriptor, "PCM file descriptor");
        FileChannel channel = FileChannel.open(descriptor.path(), StandardOpenOption.READ);

        try
        {
            long required = Math.addExact(descriptor.dataOffset(), descriptor.dataBytes());
            if (required > channel.size())
            {
                throw new EOFException("PCM source is shorter than its descriptor: " + descriptor.path());
            }

            return new PcmFileSource(channel, descriptor);
        }
        catch (Throwable failure)
        {
            try
            {
                channel.close();
            }
            catch (Throwable closeFailure)
            {
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }

            throw failure;
        }
    }

    static PcmFileSource openWave(Path path) throws IOException
    {
        Path source = Objects.requireNonNull(path, "WAV path").toAbsolutePath().normalize();
        FileChannel channel = FileChannel.open(source, StandardOpenOption.READ);

        try
        {
            return new PcmFileSource(channel, parseWave(channel, source));
        }
        catch (Throwable failure)
        {
            try
            {
                channel.close();
            }
            catch (Throwable closeFailure)
            {
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }

            throw failure;
        }
    }

    PcmFormat format()
    {
        return this.format;
    }

    long frames()
    {
        return this.frames;
    }

    synchronized double readNormalized(long frame, int channelIndex) throws IOException
    {
        if (frame < 0L || frame >= this.frames || channelIndex < 0
            || channelIndex >= this.format.channels())
        {
            return 0D;
        }

        if (this.cachedFrame < 0L || frame < this.cachedFrame
            || frame >= this.cachedFrame + this.cachedFrames)
        {
            this.load(frame);
        }

        int relativeFrame = Math.toIntExact(frame - this.cachedFrame);
        int offset = Math.addExact(Math.multiplyExact(relativeFrame, this.format.bytesPerFrame()),
            Math.multiplyExact(channelIndex, this.format.bytesPerSample()));

        return PcmSamples.readNormalized(this.format.encoding(), this.cache, offset);
    }

    private void load(long frame) throws IOException
    {
        long firstFrame = frame / CACHE_FRAMES * CACHE_FRAMES;
        long firstByte = Math.multiplyExact(firstFrame, this.format.bytesPerFrame());
        int bytes = Math.toIntExact(Math.min((long) this.cache.length, this.dataBytes - firstByte));
        ByteBuffer buffer = ByteBuffer.wrap(this.cache, 0, bytes);
        long position = Math.addExact(this.dataOffset, firstByte);

        while (buffer.hasRemaining())
        {
            int read = this.channel.read(buffer, position);
            if (read < 0)
            {
                throw new EOFException("PCM source ended while reading frame " + frame);
            }
            if (read == 0)
            {
                throw new IOException("PCM source made no progress while reading frame " + frame);
            }
            position += read;
        }

        this.cachedFrame = firstFrame;
        this.cachedFrames = bytes / this.format.bytesPerFrame();
    }

    @Override
    public void close() throws IOException
    {
        this.channel.close();
    }

    private static Descriptor parseWave(FileChannel channel, Path source) throws IOException
    {
        long fileSize = channel.size();
        if (fileSize < 12L)
        {
            throw malformed(source, "Truncated RIFF/WAVE header");
        }

        ByteBuffer header = read(channel, 0L, 12);
        if (!"RIFF".equals(fourCC(header, 0)))
        {
            throw malformed(source, "Expected RIFF container");
        }

        long riffSize = uint32(header, 4);
        if (riffSize < 4L || !"WAVE".equals(fourCC(header, 8)))
        {
            throw malformed(source, "RIFF form type must be WAVE");
        }

        long containerBytes;
        try
        {
            containerBytes = Math.addExact(riffSize, 8L);
        }
        catch (ArithmeticException e)
        {
            throw malformed(source, "RIFF size overflows", e);
        }
        if (containerBytes > AudioDecodeLimits.DEFAULT.maxContainerBytes())
        {
            throw new AudioDecodeLimitException(source.toString(),
                "RIFF container exceeds the configured limit: " + containerBytes);
        }
        if (containerBytes > fileSize)
        {
            throw malformed(source, "RIFF container extends beyond the file");
        }

        long position = 12L;
        long remaining = riffSize - 4L;
        PcmFormat format = null;
        long dataOffset = -1L;
        long dataBytes = -1L;

        while (remaining > 0L)
        {
            if (remaining < 8L)
            {
                throw malformed(source, "RIFF payload ends with an incomplete chunk header");
            }

            ByteBuffer chunkHeader = read(channel, position, 8);
            String chunkId = fourCC(chunkHeader, 0);
            long chunkBytes = uint32(chunkHeader, 4);
            long paddedBytes;
            try
            {
                paddedBytes = Math.addExact(chunkBytes, chunkBytes & 1L);
            }
            catch (ArithmeticException e)
            {
                throw malformed(source, "WAV chunk size overflows", e);
            }
            if (paddedBytes > remaining - 8L)
            {
                throw malformed(source, "Chunk '" + chunkId + "' extends beyond the RIFF boundary");
            }
            if (chunkBytes > AudioDecodeLimits.DEFAULT.maxChunkBytes())
            {
                throw new AudioDecodeLimitException(source.toString(),
                    "Chunk '" + chunkId + "' exceeds the configured limit: " + chunkBytes);
            }

            long payload = position + 8L;
            if ("fmt ".equals(chunkId))
            {
                if (format != null) throw malformed(source, "Duplicate fmt  chunk");
                format = parseFormat(channel, payload, chunkBytes, source);
            }
            else if ("data".equals(chunkId))
            {
                if (dataOffset >= 0L) throw malformed(source, "Duplicate data chunk");
                if (chunkBytes > AudioDecodeLimits.DEFAULT.maxDecodedBytes())
                {
                    throw new AudioDecodeLimitException(source.toString(),
                        "Decoded WAV data exceeds the configured limit: " + chunkBytes);
                }
                dataOffset = payload;
                dataBytes = chunkBytes;
            }

            position = Math.addExact(payload, paddedBytes);
            remaining -= 8L + paddedBytes;
        }

        if (format == null) throw malformed(source, "WAVE fmt  chunk is missing");
        if (dataOffset < 0L) throw malformed(source, "WAVE data chunk is missing");
        validateFrames(format, dataBytes, source);

        return new Descriptor(source, format, dataOffset, dataBytes);
    }

    private static PcmFormat parseFormat(FileChannel channel, long offset, long size, Path source)
        throws IOException
    {
        if (size < 16L) throw malformed(source, "WAVE fmt  chunk is shorter than 16 bytes");
        int prefixBytes = Math.toIntExact(Math.min(size, 40L));
        ByteBuffer formatBytes = read(channel, offset, prefixBytes);
        int tag = uint16(formatBytes, 0);
        int channels = uint16(formatBytes, 2);
        long sampleRate = uint32(formatBytes, 4);
        long byteRate = uint32(formatBytes, 8);
        int blockAlign = uint16(formatBytes, 12);
        int bits = uint16(formatBytes, 14);

        if (sampleRate == 0L) throw malformed(source, "WAV sample rate must be positive");
        if (sampleRate > Integer.MAX_VALUE)
        {
            throw unsupported(source, "Unsupported WAV sample rate: " + sampleRate, null);
        }
        if (channels != 1 && channels != 2)
        {
            throw unsupported(source, "Only mono and stereo WAV layouts are supported", null);
        }

        int normalizedTag = tag;
        if (tag == 1 || tag == 3)
        {
            validateFormatTail(formatBytes, size, source);
        }
        else if (tag == 0xfffe)
        {
            if (size < 40L) throw malformed(source, "WAVE_FORMAT_EXTENSIBLE fmt  chunk is too short");
            int extensionSize = uint16(formatBytes, 16);
            if (extensionSize < 22 || extensionSize != size - 18L)
            {
                throw malformed(source, "Invalid WAVE_FORMAT_EXTENSIBLE extension size: " + extensionSize);
            }
            int validBits = uint16(formatBytes, 18);
            long channelMask = uint32(formatBytes, 20);
            byte[] guid = new byte[16];
            ByteBuffer guidView = formatBytes.duplicate();
            guidView.position(24);
            guidView.get(guid);
            if (validBits != bits)
            {
                throw unsupported(source, "Extensible WAV valid bits do not match container bits", null);
            }
            if (channelMask != 0L && (channels == 1 ? channelMask != 0x4L : channelMask != 0x3L))
            {
                throw unsupported(source, "Unsupported extensible WAV channel mask", null);
            }
            if (Arrays.equals(guid, PCM_SUBFORMAT_GUID)) normalizedTag = 1;
            else if (Arrays.equals(guid, FLOAT_SUBFORMAT_GUID)) normalizedTag = 3;
            else throw unsupported(source, "Unsupported WAVE_FORMAT_EXTENSIBLE subformat", null);
        }
        else
        {
            throw unsupported(source, "Unsupported WAV format tag: " + tag, null);
        }

        PcmEncoding encoding;
        try
        {
            encoding = PcmEncoding.fromWaveFormat(normalizedTag, bits);
        }
        catch (IllegalArgumentException e)
        {
            throw unsupported(source, "Unsupported WAV encoding tag=" + normalizedTag + " bits=" + bits, e);
        }

        PcmFormat format;
        try
        {
            format = new PcmFormat(encoding, ChannelLayout.fromChannelCount(channels), (int) sampleRate);
        }
        catch (IllegalArgumentException e)
        {
            throw unsupported(source, "Unsupported WAV format configuration", e);
        }
        if (blockAlign != format.bytesPerFrame())
        {
            throw malformed(source, "Invalid WAV block alignment " + blockAlign);
        }
        if (byteRate != format.byteRate())
        {
            throw malformed(source, "Invalid WAV byte rate " + byteRate);
        }

        return format;
    }

    private static void validateFormatTail(ByteBuffer bytes, long size, Path source) throws IOException
    {
        if (size == 16L) return;
        if (size == 17L) throw malformed(source, "Truncated WAVEFORMATEX extension size");
        int extensionSize = uint16(bytes, 16);
        if (extensionSize != size - 18L)
        {
            throw malformed(source, "WAVEFORMATEX extension size does not match fmt  chunk");
        }
    }

    private static void validateFrames(PcmFormat format, long bytes, Path source) throws IOException
    {
        if (bytes % format.bytesPerFrame() != 0L)
        {
            throw malformed(source, "WAV data ends with a partial PCM frame");
        }
        long frames = bytes / format.bytesPerFrame();
        if (frames > AudioDecodeLimits.DEFAULT.maxFrames())
        {
            throw new AudioDecodeLimitException(source.toString(),
                "WAV frame count exceeds the configured limit: " + frames);
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long offset = position;

        while (buffer.hasRemaining())
        {
            int count = channel.read(buffer, offset);
            if (count < 0) throw new EOFException("Unexpected end of WAV file");
            if (count == 0) throw new IOException("WAV file read made no progress");
            offset += count;
        }

        buffer.flip();
        return buffer;
    }

    private static String fourCC(ByteBuffer bytes, int offset)
    {
        byte[] value = new byte[4];
        ByteBuffer view = bytes.duplicate();
        view.position(offset);
        view.get(value);
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static int uint16(ByteBuffer bytes, int offset)
    {
        return Short.toUnsignedInt(bytes.getShort(offset));
    }

    private static long uint32(ByteBuffer bytes, int offset)
    {
        return Integer.toUnsignedLong(bytes.getInt(offset));
    }

    private static MalformedAudioException malformed(Path source, String message)
    {
        return new MalformedAudioException(source.toString(), message);
    }

    private static MalformedAudioException malformed(Path source, String message, Throwable cause)
    {
        return new MalformedAudioException(source.toString(), message, cause);
    }

    private static UnsupportedAudioFormatException unsupported(Path source, String message, Throwable cause)
    {
        return new UnsupportedAudioFormatException(source.toString(), message, cause);
    }

    record Descriptor(Path path, PcmFormat format, long dataOffset, long dataBytes)
    {
        Descriptor
        {
            path = Objects.requireNonNull(path, "PCM path").toAbsolutePath().normalize();
            format = Objects.requireNonNull(format, "PCM format");
            if (dataOffset < 0L || dataBytes < 0L || dataBytes % format.bytesPerFrame() != 0L)
            {
                throw new IllegalArgumentException("Invalid PCM file descriptor");
            }
        }
    }
}
