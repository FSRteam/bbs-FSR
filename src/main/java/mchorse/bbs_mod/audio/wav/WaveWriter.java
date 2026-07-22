package mchorse.bbs_mod.audio.wav;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.utils.Pair;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class WaveWriter
{
    private static final long UINT32_MAX = 0xffffffffL;
    private static final long PCM_FORMAT_CHUNK_SIZE = 16L;
    private static final long WAVE_FORMAT_EX_CHUNK_SIZE = 18L;
    private static final long FACT_CHUNK_SIZE = 4L;
    private static final long CUE_RECORD_SIZE = 24L;

    public static void write(File file, Wave wave) throws IOException
    {
        Objects.requireNonNull(file, "file");
        PreparedWave prepared = prepare(wave);
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();

        if (parent == null)
        {
            throw new IOException("WAV output has no parent directory: " + file);
        }

        Files.createDirectories(parent);

        String prefix = "." + target.getFileName() + ".";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");

        try
        {
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(temporary)))
            {
                write(stream, prepared);
            }

            try
            {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException | Error e)
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException cleanup)
            {
                e.addSuppressed(cleanup);
            }

            throw e;
        }
    }

    /**
     * Writes a complete WAV to a caller-owned stream. The stream is flushed so
     * all WAV bytes reach downstream wrappers, but it is never closed.
     */
    public static void write(OutputStream stream, Wave wave) throws IOException
    {
        Objects.requireNonNull(stream, "stream");
        write(stream, prepare(wave));
        stream.flush();
    }

    /**
     * Writes a RIFF/WAVE header for sample data that immediately follows it.
     * The caller owns the stream, sample body, and the trailing pad byte when
     * {@code dataLength} is odd.
     */
    public static void writeHeader(OutputStream stream, PcmFormat format, long dataLength) throws IOException
    {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(format, "format");
        validateFormat(format);
        validateDataLength(format, dataLength);

        long riffSize = 4L;

        riffSize = checkedAdd(riffSize, chunkStorageSize(formatChunkSize(format)), "RIFF size");
        if (requiresFactChunk(format))
        {
            riffSize = checkedAdd(riffSize, chunkStorageSize(FACT_CHUNK_SIZE), "RIFF size");
        }
        riffSize = checkedAdd(riffSize, chunkStorageSize(dataLength), "RIFF size");

        requireUInt32(riffSize, "RIFF size");
        writeHeader(stream, format, dataLength, riffSize);
    }

    /** Writes a validating integer-PCM header for legacy streaming callers. */
    public static void writeHeader(OutputStream stream, int numChannels, int sampleRate, int bitsPerSample, int dataLength) throws IOException
    {
        PcmEncoding encoding = PcmEncoding.fromWaveFormat(1, bitsPerSample);
        ChannelLayout layout = ChannelLayout.fromChannelCount(numChannels);

        writeHeader(stream, new PcmFormat(encoding, layout, sampleRate), dataLength);
    }

    private static PreparedWave prepare(Wave wave)
    {
        Objects.requireNonNull(wave, "wave");
        PcmFormat format = wave.getFormat();
        byte[] data = Objects.requireNonNull(wave.data, "wave.data");

        validateFormat(format);
        validateDataLength(format, data.length);

        List<EncodedList> lists = encodeLists(wave.lists);
        List<EncodedCue> cues = encodeCues(wave.cues);
        long riffSize = 4L;

        riffSize = checkedAdd(riffSize, chunkStorageSize(formatChunkSize(format)), "RIFF size");
        if (requiresFactChunk(format))
        {
            riffSize = checkedAdd(riffSize, chunkStorageSize(FACT_CHUNK_SIZE), "RIFF size");
        }
        riffSize = checkedAdd(riffSize, chunkStorageSize(data.length), "RIFF size");

        for (EncodedList list : lists)
        {
            riffSize = checkedAdd(riffSize, chunkStorageSize(list.payloadSize), "RIFF size");
        }

        if (!cues.isEmpty())
        {
            long cuePayloadSize = checkedAdd(4L,
                checkedMultiply(cues.size(), CUE_RECORD_SIZE, "cue chunk size"), "cue chunk size");

            riffSize = checkedAdd(riffSize, chunkStorageSize(cuePayloadSize), "RIFF size");
        }

        requireUInt32(riffSize, "RIFF size");

        return new PreparedWave(format, data, lists, cues, riffSize);
    }

    private static void write(OutputStream stream, PreparedWave wave) throws IOException
    {
        writeString(stream, "RIFF");
        writeUInt32(stream, wave.riffSize);
        writeString(stream, "WAVE");
        writeFormatChunk(stream, wave.format);
        if (requiresFactChunk(wave.format))
        {
            writeFactChunk(stream, wave.data.length / wave.format.bytesPerFrame());
        }

        writeString(stream, "data");
        writeUInt32(stream, wave.data.length);
        stream.write(wave.data);
        writePadding(stream, wave.data.length);

        for (EncodedList list : wave.lists)
        {
            writeListChunk(stream, list);
        }

        if (!wave.cues.isEmpty())
        {
            writeCueChunk(stream, wave.cues);
        }
    }

    private static void writeHeader(OutputStream stream, PcmFormat format, long dataLength, long riffSize) throws IOException
    {
        writeString(stream, "RIFF");
        writeUInt32(stream, riffSize);
        writeString(stream, "WAVE");
        writeFormatChunk(stream, format);
        if (requiresFactChunk(format))
        {
            writeFactChunk(stream, dataLength / format.bytesPerFrame());
        }
        writeString(stream, "data");
        writeUInt32(stream, dataLength);
    }

    private static void writeFormatChunk(OutputStream stream, PcmFormat format) throws IOException
    {
        writeString(stream, "fmt ");
        writeUInt32(stream, formatChunkSize(format));
        writeUInt16(stream, format.waveTag());
        writeUInt16(stream, format.channels());
        writeUInt32(stream, format.sampleRate());
        writeUInt32(stream, format.byteRate());
        writeUInt16(stream, format.bytesPerFrame());
        writeUInt16(stream, format.encoding().bitsPerSample());

        if (requiresFactChunk(format))
        {
            writeUInt16(stream, 0);
        }
    }

    private static void writeFactChunk(OutputStream stream, long frameCount) throws IOException
    {
        writeString(stream, "fact");
        writeUInt32(stream, FACT_CHUNK_SIZE);
        writeUInt32(stream, frameCount);
    }

    private static long formatChunkSize(PcmFormat format)
    {
        return requiresFactChunk(format) ? WAVE_FORMAT_EX_CHUNK_SIZE : PCM_FORMAT_CHUNK_SIZE;
    }

    private static boolean requiresFactChunk(PcmFormat format)
    {
        return format.encoding().floatingPoint();
    }

    private static List<EncodedList> encodeLists(List<WaveList> lists)
    {
        if (lists == null || lists.isEmpty())
        {
            return Collections.emptyList();
        }

        List<EncodedList> encoded = new ArrayList<>(lists.size());

        for (int listIndex = 0; listIndex < lists.size(); listIndex++)
        {
            WaveList list = Objects.requireNonNull(lists.get(listIndex), "wave.lists[" + listIndex + "]");
            String type = requireFourCC(list.type, "wave.lists[" + listIndex + "].type");
            List<EncodedEntry> entries = new ArrayList<>();
            long payloadSize = 4L;

            if (list.entries != null)
            {
                entries = new ArrayList<>(list.entries.size());

                for (int entryIndex = 0; entryIndex < list.entries.size(); entryIndex++)
                {
                    Pair<String, String> entry = Objects.requireNonNull(list.entries.get(entryIndex),
                        "wave.lists[" + listIndex + "].entries[" + entryIndex + "]");
                    String id = requireFourCC(entry.a,
                        "wave.lists[" + listIndex + "].entries[" + entryIndex + "].id");
                    String value = Objects.requireNonNull(entry.b,
                        "wave.lists[" + listIndex + "].entries[" + entryIndex + "].value");
                    byte[] bytes = encodeListValue(type, value,
                        "wave.lists[" + listIndex + "].entries[" + entryIndex + "].value");

                    requireUInt32(bytes.length, "LIST entry size");
                    payloadSize = checkedAdd(payloadSize, chunkStorageSize(bytes.length), "LIST chunk size");
                    entries.add(new EncodedEntry(id, bytes));
                }
            }

            requireUInt32(payloadSize, "LIST chunk size");
            encoded.add(new EncodedList(type, entries, payloadSize));
        }

        return encoded;
    }

    private static byte[] encodeListValue(String listType, String value, String label)
    {
        if ("INFO".equals(listType))
        {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        /* Non-INFO LIST payloads include binary adtl and sampler records. The
         * compatibility WaveList stores strings, so use a one-byte mapping
         * that round-trips decoded bytes and reject values it cannot represent. */
        byte[] bytes = new byte[value.length()];

        for (int i = 0; i < value.length(); i++)
        {
            char character = value.charAt(i);

            if (character > 0xff)
            {
                throw new IllegalArgumentException(label
                    + " contains a character outside the byte-preserving non-INFO LIST range");
            }

            bytes[i] = (byte) character;
        }

        return bytes;
    }

    private static List<EncodedCue> encodeCues(List<WaveCue> cues)
    {
        if (cues == null || cues.isEmpty())
        {
            return Collections.emptyList();
        }

        List<EncodedCue> encoded = new ArrayList<>(cues.size());

        for (int i = 0; i < cues.size(); i++)
        {
            WaveCue cue = Objects.requireNonNull(cues.get(i), "wave.cues[" + i + "]");

            encoded.add(new EncodedCue(cue.id, cue.position, cue.dataChunkID,
                cue.chunkStart, cue.blockStart, cue.sampleStart));
        }

        long payloadSize = checkedAdd(4L,
            checkedMultiply(encoded.size(), CUE_RECORD_SIZE, "cue chunk size"), "cue chunk size");

        requireUInt32(payloadSize, "cue chunk size");

        return encoded;
    }

    private static void writeListChunk(OutputStream stream, EncodedList list) throws IOException
    {
        writeString(stream, "LIST");
        writeUInt32(stream, list.payloadSize);
        writeString(stream, list.type);

        for (EncodedEntry entry : list.entries)
        {
            writeString(stream, entry.id);
            writeUInt32(stream, entry.value.length);
            stream.write(entry.value);
            writePadding(stream, entry.value.length);
        }

        writePadding(stream, list.payloadSize);
    }

    private static void writeCueChunk(OutputStream stream, List<EncodedCue> cues) throws IOException
    {
        long payloadSize = 4L + cues.size() * CUE_RECORD_SIZE;

        writeString(stream, "cue ");
        writeUInt32(stream, payloadSize);
        writeUInt32(stream, cues.size());

        for (EncodedCue cue : cues)
        {
            writeIntBits(stream, cue.id);
            writeIntBits(stream, cue.position);
            writeIntBits(stream, cue.dataChunkID);
            writeIntBits(stream, cue.chunkStart);
            writeIntBits(stream, cue.blockStart);
            writeIntBits(stream, cue.sampleStart);
        }

        writePadding(stream, payloadSize);
    }

    private static void validateFormat(PcmFormat format)
    {
        if (!format.layout().supported())
        {
            throw new IllegalArgumentException("Unsupported channel layout: " + format.layout().id());
        }
        if (!format.encoding().exportSupported())
        {
            throw new IllegalArgumentException("Unsupported WAV export encoding: " + format.encoding());
        }

        requireUInt16(format.waveTag(), "WAV format tag");
        requireUInt16(format.channels(), "channel count");
        requireUInt32(format.sampleRate(), "sample rate");
        requireUInt32(format.byteRate(), "byte rate");
        requireUInt16(format.bytesPerFrame(), "block alignment");
        requireUInt16(format.encoding().bitsPerSample(), "bits per sample");
    }

    private static void validateDataLength(PcmFormat format, long dataLength)
    {
        requireUInt32(dataLength, "data size");

        if (dataLength % format.bytesPerFrame() != 0L)
        {
            throw new IllegalArgumentException("PCM data ends with a partial frame: " + dataLength
                + " bytes for " + format.bytesPerFrame() + "-byte frames");
        }
    }

    private static long chunkStorageSize(long payloadSize)
    {
        requireUInt32(payloadSize, "chunk size");

        return checkedAdd(8L, paddedPayloadSize(payloadSize), "chunk storage size");
    }

    private static long paddedPayloadSize(long payloadSize)
    {
        return checkedAdd(payloadSize, payloadSize & 1L, "padded chunk size");
    }

    private static long checkedAdd(long left, long right, String label)
    {
        try
        {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException e)
        {
            throw new IllegalArgumentException(label + " overflow", e);
        }
    }

    private static long checkedMultiply(long left, long right, String label)
    {
        try
        {
            return Math.multiplyExact(left, right);
        }
        catch (ArithmeticException e)
        {
            throw new IllegalArgumentException(label + " overflow", e);
        }
    }

    private static void requireUInt32(long value, String label)
    {
        if (value < 0L || value > UINT32_MAX)
        {
            throw new IllegalArgumentException(label + " must fit unsigned 32-bit RIFF field: " + value);
        }
    }

    private static void requireUInt16(long value, String label)
    {
        if (value < 0L || value > 0xffffL)
        {
            throw new IllegalArgumentException(label + " must fit unsigned 16-bit WAV field: " + value);
        }
    }

    private static String requireFourCC(String value, String label)
    {
        Objects.requireNonNull(value, label);

        if (value.length() != 4)
        {
            throw new IllegalArgumentException(label + " must contain exactly four ASCII characters: " + value);
        }

        for (int i = 0; i < value.length(); i++)
        {
            if (value.charAt(i) > 0x7f)
            {
                throw new IllegalArgumentException(label + " must contain only ASCII characters: " + value);
            }
        }

        return value;
    }

    private static void writePadding(OutputStream stream, long payloadSize) throws IOException
    {
        if ((payloadSize & 1L) != 0L)
        {
            stream.write(0);
        }
    }

    private static void writeString(OutputStream stream, String string) throws IOException
    {
        stream.write(requireFourCC(string, "FourCC").getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeIntBits(OutputStream stream, int value) throws IOException
    {
        writeUInt32(stream, Integer.toUnsignedLong(value));
    }

    private static void writeUInt32(OutputStream stream, long value) throws IOException
    {
        requireUInt32(value, "unsigned 32-bit value");
        stream.write((int) (value & 0xffL));
        stream.write((int) ((value >>> 8) & 0xffL));
        stream.write((int) ((value >>> 16) & 0xffL));
        stream.write((int) ((value >>> 24) & 0xffL));
    }

    private static void writeUInt16(OutputStream stream, long value) throws IOException
    {
        requireUInt16(value, "unsigned 16-bit value");
        stream.write((int) (value & 0xffL));
        stream.write((int) ((value >>> 8) & 0xffL));
    }

    private static class PreparedWave
    {
        public final PcmFormat format;
        public final byte[] data;
        public final List<EncodedList> lists;
        public final List<EncodedCue> cues;
        public final long riffSize;

        private PreparedWave(PcmFormat format, byte[] data, List<EncodedList> lists, List<EncodedCue> cues, long riffSize)
        {
            this.format = format;
            this.data = data;
            this.lists = lists;
            this.cues = cues;
            this.riffSize = riffSize;
        }
    }

    private static class EncodedList
    {
        public final String type;
        public final List<EncodedEntry> entries;
        public final long payloadSize;

        private EncodedList(String type, List<EncodedEntry> entries, long payloadSize)
        {
            this.type = type;
            this.entries = entries;
            this.payloadSize = payloadSize;
        }
    }

    private static class EncodedEntry
    {
        public final String id;
        public final byte[] value;

        private EncodedEntry(String id, byte[] value)
        {
            this.id = id;
            this.value = value;
        }
    }

    private static class EncodedCue
    {
        public final int id;
        public final int position;
        public final int dataChunkID;
        public final int chunkStart;
        public final int blockStart;
        public final int sampleStart;

        private EncodedCue(int id, int position, int dataChunkID, int chunkStart, int blockStart, int sampleStart)
        {
            this.id = id;
            this.position = position;
            this.dataChunkID = dataChunkID;
            this.chunkStart = chunkStart;
            this.blockStart = blockStart;
            this.sampleStart = sampleStart;
        }
    }
}
