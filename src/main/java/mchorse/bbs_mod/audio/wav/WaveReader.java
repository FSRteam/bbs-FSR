package mchorse.bbs_mod.audio.wav;

import mchorse.bbs_mod.audio.AudioDecodeLimitException;
import mchorse.bbs_mod.audio.AudioDecodeLimits;
import mchorse.bbs_mod.audio.BinaryChunk;
import mchorse.bbs_mod.audio.BinaryReader;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.MalformedAudioException;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.UnsupportedAudioFormatException;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Strict, bounded RIFF/WAVE reader. */
public class WaveReader extends BinaryReader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WaveReader.class);
    private static final long RIFF_HEADER_BYTES = 8L;
    private static final long WAVE_TYPE_BYTES = 4L;
    private static final long MAX_METADATA_ENTRIES = 1_000_000L;

    /* GUIDs are stored in the byte order used by WAVE_FORMAT_EXTENSIBLE. */
    private static final byte[] PCM_SUBFORMAT_GUID = new byte[] {
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        (byte) 0x80, 0x00, 0x00, (byte) 0xaa, 0x00, 0x38, (byte) 0x9b, 0x71
    };
    private static final byte[] FLOAT_SUBFORMAT_GUID = new byte[] {
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
        (byte) 0x80, 0x00, 0x00, (byte) 0xaa, 0x00, 0x38, (byte) 0x9b, 0x71
    };

    private final AudioDecodeLimits limits;
    private final String source;
    private final Consumer<String> metadataDiagnostic;

    public WaveReader()
    {
        this(AudioDecodeLimits.DEFAULT, null);
    }

    public WaveReader(AudioDecodeLimits limits)
    {
        this(limits, null);
    }

    public WaveReader(AudioDecodeLimits limits, String source)
    {
        this(limits, source, LOGGER::warn);
    }

    /**
     * Construct a reader with an injectable optional-metadata diagnostic.
     * At most one diagnostic is emitted for each asset read.
     */
    public WaveReader(AudioDecodeLimits limits, String source, Consumer<String> metadataDiagnostic)
    {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.source = source;
        this.metadataDiagnostic = Objects.requireNonNull(metadataDiagnostic, "metadataDiagnostic");
    }

    /**
     * Read a WAV using the source/limits supplied to this reader.
     *
     * @throws IOException a typed decode error for malformed or unsupported WAV data
     */
    public Wave read(InputStream stream) throws IOException
    {
        return this.read(stream, this.source);
    }

    /** Read a WAV while attaching an asset path to any typed decode error. */
    public Wave read(InputStream stream, String source) throws IOException
    {
        Objects.requireNonNull(stream, "stream");
        String effectiveSource = source == null ? this.source : source;

        try
        {
            return this.readInternal(stream, effectiveSource);
        }
        catch (EOFException e)
        {
            throw new MalformedAudioException(effectiveSource,
                "Truncated RIFF/WAVE container", e);
        }
    }

    private Wave readInternal(InputStream stream, String source) throws IOException
    {
        String riffId = this.readFourString(stream);
        if (!"RIFF".equals(riffId))
        {
            throw new MalformedAudioException(source,
                "Expected RIFF container, found '" + riffId + "'");
        }

        long riffSize = this.readU32LE(stream);
        if (riffSize < WAVE_TYPE_BYTES)
        {
            throw new MalformedAudioException(source,
                "RIFF size must include the WAVE type and payload");
        }

        long totalContainerBytes = Math.addExact(riffSize, RIFF_HEADER_BYTES);
        if (totalContainerBytes > this.limits.maxContainerBytes())
        {
            throw new AudioDecodeLimitException(source,
                "RIFF container is " + totalContainerBytes + " bytes; limit is "
                    + this.limits.maxContainerBytes());
        }

        String waveType = this.readFourString(stream);
        if (!"WAVE".equals(waveType))
        {
            throw new MalformedAudioException(source,
                "RIFF form type must be WAVE, found '" + waveType + "'");
        }

        long remaining = riffSize - WAVE_TYPE_BYTES;
        FmtInfo fmt = null;
        byte[] data = null;
        List<WaveList> lists = new ArrayList<>();
        List<WaveCue> cues = new ArrayList<>();
        byte[] pad = new byte[1];
        boolean metadataDiagnosticReported = false;

        while (remaining > 0)
        {
            if (remaining < 8)
            {
                throw new MalformedAudioException(source,
                    "RIFF payload ends with an incomplete chunk header");
            }

            String chunkId = this.readFourString(stream);
            remaining -= 4;
            long chunkSize = this.readU32LE(stream);
            remaining -= 4;

            long paddedSize = Math.addExact(chunkSize, chunkSize & 1L);
            if (paddedSize > remaining)
            {
                throw new MalformedAudioException(source,
                    "Chunk '" + chunkId + "' extends beyond the RIFF boundary");
            }
            if (chunkSize > this.limits.maxChunkBytes())
            {
                throw new AudioDecodeLimitException(source,
                    "Chunk '" + chunkId + "' is " + chunkSize + " bytes; limit is "
                        + this.limits.maxChunkBytes());
            }

            BoundedInputStream chunk = new BoundedInputStream(stream, chunkSize);

            if ("fmt ".equals(chunkId))
            {
                if (fmt != null)
                {
                    throw new MalformedAudioException(source, "Duplicate fmt  chunk");
                }

                fmt = this.parseFormat(chunk, chunkSize, source);
            }
            else if ("data".equals(chunkId))
            {
                if (data != null)
                {
                    throw new MalformedAudioException(source, "Duplicate data chunk");
                }

                if (chunkSize > this.limits.maxDecodedBytes())
                {
                    throw new AudioDecodeLimitException(source,
                        "Decoded WAV data is " + chunkSize + " bytes; limit is "
                            + this.limits.maxDecodedBytes());
                }
                if (chunkSize > Integer.MAX_VALUE)
                {
                    throw new AudioDecodeLimitException(source,
                        "Decoded WAV data cannot fit in a Java byte array");
                }
                if (fmt != null)
                {
                    this.validateFrameCount(chunkSize, fmt.format, source);
                }

                data = new byte[(int) chunkSize];
                this.readFully(chunk, data, 0, data.length);
            }
            else if ("LIST".equals(chunkId))
            {
                try
                {
                    WaveList list = this.parseList(chunk);
                    if (list != null)
                    {
                        lists.add(list);
                    }
                }
                catch (MetadataMalformedException ignored)
                {
                    if (!metadataDiagnosticReported)
                    {
                        this.reportMalformedMetadata(source, "LIST");
                        metadataDiagnosticReported = true;
                    }
                }
            }
            else if ("cue ".equals(chunkId))
            {
                try
                {
                    List<WaveCue> parsedCues = new ArrayList<>();
                    this.parseCues(chunk, parsedCues);
                    cues.addAll(parsedCues);
                }
                catch (MetadataMalformedException ignored)
                {
                    if (!metadataDiagnosticReported)
                    {
                        this.reportMalformedMetadata(source, "cue ");
                        metadataDiagnosticReported = true;
                    }
                }
            }

            /* Every parser is allowed to consume only its local chunk. */
            this.skipFully(chunk, chunk.remaining());
            remaining -= chunkSize;

            if ((chunkSize & 1L) != 0)
            {
                this.readFully(stream, pad, 0, 1);
                remaining -= 1;
            }
        }

        if (remaining != 0)
        {
            throw new MalformedAudioException(source, "RIFF payload accounting underflow");
        }
        if (fmt == null)
        {
            throw new MalformedAudioException(source, "WAVE fmt  chunk is missing");
        }
        if (data == null)
        {
            throw new MalformedAudioException(source, "WAVE data chunk is missing");
        }

        this.validateFrameCount(data.length, fmt.format, source);

        /* Wave's compatibility fields are populated from the validated model. */
        Wave wave = new Wave(fmt.format, data);
        wave.lists = lists;
        wave.cues = cues;
        return wave;
    }

    private FmtInfo parseFormat(BoundedInputStream chunk, long chunkSize, String source)
        throws IOException
    {
        if (chunkSize < 16)
        {
            throw new MalformedAudioException(source, "WAVE fmt  chunk is shorter than 16 bytes");
        }

        int tag = this.readU16LE(chunk);
        int channels = this.readU16LE(chunk);
        long sampleRateLong = this.readU32LE(chunk);
        long byteRate = this.readU32LE(chunk);
        int blockAlign = this.readU16LE(chunk);
        int bitsPerSample = this.readU16LE(chunk);

        if (sampleRateLong == 0)
        {
            throw new MalformedAudioException(source, "WAV sample rate must be positive");
        }
        if (sampleRateLong > Integer.MAX_VALUE)
        {
            throw new UnsupportedAudioFormatException(source,
                "Unsupported WAV sample rate: " + sampleRateLong);
        }
        if (channels != 1 && channels != 2)
        {
            throw new UnsupportedAudioFormatException(source,
                "Only mono and stereo WAV layouts are supported (channels=" + channels + ")");
        }

        PcmEncoding encoding;
        int normalizedTag = tag;

        if (tag == 1 || tag == 3)
        {
            this.validateWaveFormatExTail(chunk, chunkSize, source);

            try
            {
                encoding = PcmEncoding.fromWaveFormat(tag, bitsPerSample);
            }
            catch (IllegalArgumentException e)
            {
                throw new UnsupportedAudioFormatException(source,
                    "Unsupported WAV encoding tag=" + tag + " bits=" + bitsPerSample, e);
            }
        }
        else if (tag == 0xfffe)
        {
            if (chunkSize < 40)
            {
                throw new MalformedAudioException(source,
                    "WAVE_FORMAT_EXTENSIBLE fmt  chunk is shorter than 40 bytes");
            }

            int extensionSize = this.readU16LE(chunk);
            if (extensionSize < 22 || extensionSize > chunk.remaining())
            {
                throw new MalformedAudioException(source,
                    "Invalid WAVE_FORMAT_EXTENSIBLE extension size: " + extensionSize);
            }
            if (extensionSize != chunk.remaining())
            {
                throw new MalformedAudioException(source,
                    "WAVE_FORMAT_EXTENSIBLE fmt  tail is " + chunk.remaining()
                        + " bytes but cbSize declares " + extensionSize);
            }

            int validBits = this.readU16LE(chunk);
            long channelMask = this.readU32LE(chunk);
            byte[] guid = new byte[16];
            this.readFully(chunk, guid, 0, guid.length);

            if (validBits != bitsPerSample)
            {
                throw new UnsupportedAudioFormatException(source,
                    "Extensible WAV valid bits " + validBits
                        + " do not match container bits " + bitsPerSample);
            }

            if (channelMask != 0
                && (channels == 1 ? channelMask != 0x4L : channelMask != 0x3L))
            {
                throw new UnsupportedAudioFormatException(source,
                    "Unsupported extensible WAV channel mask: 0x"
                        + Long.toHexString(channelMask));
            }

            if (Arrays.equals(guid, PCM_SUBFORMAT_GUID))
            {
                normalizedTag = 1;
            }
            else if (Arrays.equals(guid, FLOAT_SUBFORMAT_GUID))
            {
                normalizedTag = 3;
            }
            else
            {
                throw new UnsupportedAudioFormatException(source,
                    "Unsupported WAVE_FORMAT_EXTENSIBLE subformat GUID");
            }

            try
            {
                encoding = PcmEncoding.fromWaveFormat(normalizedTag, bitsPerSample);
            }
            catch (IllegalArgumentException e)
            {
                throw new UnsupportedAudioFormatException(source,
                    "Unsupported extensible WAV encoding bits=" + bitsPerSample, e);
            }

            /* cbSize may advertise bytes beyond the required 22-byte extension. */
            if (extensionSize > 22)
            {
                this.skipFully(chunk, extensionSize - 22L);
            }
        }
        else
        {
            throw new UnsupportedAudioFormatException(source,
                "Unsupported WAV format tag: " + tag);
        }

        ChannelLayout layout;
        try
        {
            layout = ChannelLayout.fromChannelCount(channels);
        }
        catch (IllegalArgumentException e)
        {
            throw new UnsupportedAudioFormatException(source,
                "Unsupported WAV channel layout: " + channels, e);
        }

        PcmFormat format;
        try
        {
            format = new PcmFormat(encoding, layout, (int) sampleRateLong);
        }
        catch (IllegalArgumentException e)
        {
            throw new UnsupportedAudioFormatException(source,
                "Unsupported WAV format configuration", e);
        }

        if (blockAlign != format.bytesPerFrame())
        {
            throw new MalformedAudioException(source,
                "Invalid WAV block alignment " + blockAlign
                    + "; expected " + format.bytesPerFrame());
        }
        if (byteRate != format.byteRate())
        {
            throw new MalformedAudioException(source,
                "Invalid WAV byte rate " + byteRate + "; expected " + format.byteRate());
        }
        if (byteRate > Integer.MAX_VALUE)
        {
            throw new UnsupportedAudioFormatException(source,
                "WAV byte rate cannot be represented by the compatibility Wave model");
        }

        return new FmtInfo(format);
    }

    private void reportMalformedMetadata(String source, String chunkId)
    {
        String location = source == null || source.isBlank() ? "WAV asset" : source;

        try
        {
            this.metadataDiagnostic.accept(location + ": ignored malformed optional "
                + chunkId + " metadata");
        }
        catch (RuntimeException ignored)
        {
            /* A diagnostic sink must not turn optional metadata into a decode failure. */
        }
    }

    private void validateWaveFormatExTail(BoundedInputStream chunk, long chunkSize, String source)
        throws IOException
    {
        if (chunkSize == 16)
        {
            return;
        }
        if (chunkSize == 17)
        {
            throw new MalformedAudioException(source,
                "WAVE fmt  chunk has one byte of a truncated WAVEFORMATEX cbSize");
        }

        int extensionSize = this.readU16LE(chunk);

        if (extensionSize != chunk.remaining())
        {
            throw new MalformedAudioException(source,
                "WAVE fmt  tail is " + chunk.remaining()
                    + " bytes but cbSize declares " + extensionSize);
        }

        this.skipFully(chunk, extensionSize);
    }

    private void validateFrameCount(long dataBytes, PcmFormat format, String source)
        throws IOException
    {
        long frameBytes = format.bytesPerFrame();
        if (dataBytes % frameBytes != 0)
        {
            throw new MalformedAudioException(source,
                "WAVE data length " + dataBytes + " is not frame-aligned");
        }

        long frameCount = dataBytes / frameBytes;
        if (frameCount > this.limits.maxFrames())
        {
            throw new AudioDecodeLimitException(source,
                "Decoded WAV has " + frameCount + " frames; limit is "
                    + this.limits.maxFrames());
        }
    }

    private WaveList parseList(BoundedInputStream chunk)
        throws IOException, MetadataMalformedException
    {
        if (chunk.remaining() < 4)
        {
            throw new MetadataMalformedException();
        }

        String type = this.readMetadataFourCC(chunk);
        WaveList list = new WaveList(type);
        java.nio.charset.Charset valueCharset = "INFO".equals(type)
            ? StandardCharsets.UTF_8
            : StandardCharsets.ISO_8859_1;
        long entries = 0;

        while (chunk.remaining() > 0)
        {
            if (entries++ >= MAX_METADATA_ENTRIES || chunk.remaining() < 8)
            {
                throw new MetadataMalformedException();
            }

            String id = this.readMetadataFourCC(chunk);
            long size = this.readU32LE(chunk);
            if (size > chunk.remaining() || size > Integer.MAX_VALUE)
            {
                throw new MetadataMalformedException();
            }

            byte[] value = new byte[(int) size];
            this.readFully(chunk, value, 0, value.length);
            list.entries.add(new Pair<>(id, new String(value, valueCharset)));

            if ((size & 1L) != 0)
            {
                if (chunk.remaining() < 1)
                {
                    throw new MetadataMalformedException();
                }

                chunk.read();
            }
        }

        return list;
    }

    private void parseCues(BoundedInputStream chunk, List<WaveCue> cues)
        throws IOException, MetadataMalformedException
    {
        if (chunk.remaining() < 4)
        {
            throw new MetadataMalformedException();
        }

        long count = this.readU32LE(chunk);
        if (count > MAX_METADATA_ENTRIES || count > chunk.remaining() / 24L)
        {
            throw new MetadataMalformedException();
        }

        for (long i = 0; i < count; i++)
        {
            WaveCue cue = new WaveCue();
            cue.id = (int) this.readU32LE(chunk);
            cue.position = (int) this.readU32LE(chunk);
            cue.dataChunkID = (int) this.readU32LE(chunk);
            cue.chunkStart = (int) this.readU32LE(chunk);
            cue.blockStart = (int) this.readU32LE(chunk);
            cue.sampleStart = (int) this.readU32LE(chunk);
            cues.add(cue);
        }

        if (chunk.remaining() != 0)
        {
            throw new MetadataMalformedException();
        }
    }

    private String readMetadataFourCC(BoundedInputStream stream)
        throws IOException, MetadataMalformedException
    {
        if (stream.remaining() < 4)
        {
            throw new MetadataMalformedException();
        }

        return this.readFourString(stream);
    }

    /**
     * Compatibility helper retained for older callers. New parsing uses an
     * unsigned, bounded chunk header internally.
     */
    public BinaryChunk readChunk(InputStream stream) throws IOException
    {
        String id = this.readFourString(stream);
        long size = this.readU32LE(stream);

        if (size > Integer.MAX_VALUE)
        {
            throw new AudioDecodeLimitException(this.source,
                "Chunk size cannot be represented by BinaryChunk: " + size);
        }

        return new BinaryChunk(id, (int) size);
    }

    private record FmtInfo(PcmFormat format)
    {
    }

    private static final class MetadataMalformedException extends Exception
    {
        private static final long serialVersionUID = 1L;
    }

    /** InputStream view that can never read past one RIFF chunk. */
    private static final class BoundedInputStream extends InputStream
    {
        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long remaining)
        {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        private long remaining()
        {
            return this.remaining;
        }

        @Override
        public int read() throws IOException
        {
            if (this.remaining == 0)
            {
                return -1;
            }

            int value = this.delegate.read();
            if (value < 0)
            {
                throw new EOFException("Unexpected end of RIFF chunk");
            }

            this.remaining -= 1;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException
        {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0)
            {
                return 0;
            }
            if (this.remaining == 0)
            {
                return -1;
            }

            int request = (int) Math.min((long) length, this.remaining);
            int count = this.delegate.read(bytes, offset, request);
            if (count < 0)
            {
                throw new EOFException("Unexpected end of RIFF chunk");
            }
            if (count == 0)
            {
                int value = this.read();
                if (value < 0)
                {
                    return -1;
                }
                bytes[offset] = (byte) value;
                return 1;
            }

            this.remaining -= count;
            return count;
        }

        @Override
        public long skip(long bytes) throws IOException
        {
            if (bytes <= 0 || this.remaining == 0)
            {
                return 0;
            }

            long request = Math.min(bytes, this.remaining);
            long skipped = this.delegate.skip(request);
            if (skipped > 0)
            {
                this.remaining -= skipped;
                return skipped;
            }

            int value = this.read();
            return value < 0 ? 0 : 1;
        }
    }
}
