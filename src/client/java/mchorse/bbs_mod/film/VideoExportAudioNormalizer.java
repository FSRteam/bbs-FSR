package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.PcmSamples;
import mchorse.bbs_mod.audio.wav.WaveWriter;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Exact-duration PCM conversion owned by the video export lifecycle. */
public final class VideoExportAudioNormalizer
{
    /* Keep the working set independent of the requested duration. */
    static final int BLOCK_FRAMES = 4096;
    private static final int MAX_CONSECUTIVE_ZERO_READS = 16;
    private static final double CENTER_GAIN = Math.sqrt(0.5D);

    private VideoExportAudioNormalizer()
    {}

    public static void writeSilence(Path output, ChannelLayout layout, int sampleRate,
                                    long frames, BooleanSupplier cancelled) throws Exception
    {
        write(output, null, layout, sampleRate, frames, cancelled);
    }

    public static long normalize(Path source, Path output, ChannelLayout layout, int sampleRate,
                                 long frames, BooleanSupplier cancelled) throws Exception
    {
        if (source == null || !Files.isRegularFile(source))
        {
            throw new IOException("PCM source is missing: " + source);
        }

        try (AudioInputStream stream = AudioSystem.getAudioInputStream(source.toFile()))
        {
            StreamingSource decoded = StreamingSource.open(stream);
            write(output, decoded, layout, sampleRate, frames, cancelled);
        }

        return frames;
    }

    private static void write(Path output, StreamingSource source, ChannelLayout layout,
                               int sampleRate, long frames, BooleanSupplier cancelled) throws Exception
    {
        if (output == null || cancelled == null || frames < 0L || sampleRate <= 0
            || layout == null || !layout.supported()
            || (layout != ChannelLayout.MONO && layout != ChannelLayout.STEREO))
        {
            throw new IllegalArgumentException("Invalid PCM normalization request");
        }

        PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE, layout, sampleRate);
        long dataLength = Math.multiplyExact(frames, (long) format.bytesPerFrame());
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("PCM output has no parent directory");
        Files.createDirectories(parent);

        boolean created = false;
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))
        {
            created = true;
            WaveWriter.writeHeader(stream, format, dataLength);
            byte[] block = new byte[Math.multiplyExact(BLOCK_FRAMES, format.bytesPerFrame())];

            for (long first = 0L; first < frames; first += BLOCK_FRAMES)
            {
                if (cancelled.getAsBoolean())
                {
                    throw new CancellationException("Audio normalization cancelled");
                }

                int count = (int) Math.min((long) BLOCK_FRAMES, frames - first);
                int bytes = Math.multiplyExact(count, format.bytesPerFrame());

                for (int frame = 0; frame < count; frame++)
                {
                    long targetFrame = first + frame;
                    double sourcePosition = source == null ? -1D
                        : targetFrame * source.sampleRate() / (double) sampleRate;
                    double left = 0D;
                    double right = 0D;
                    int offset = frame * format.bytesPerFrame();

                    /* Apply the layout matrix in floating point. PCM16 writes
                     * below are the only output quantization boundary. */
                    if (source != null)
                    {
                        left = source.sample(sourcePosition, 0);

                        if (source.channels() == 1)
                        {
                            if (layout == ChannelLayout.STEREO)
                            {
                                left *= CENTER_GAIN;
                                right = left;
                            }
                        }
                        else
                        {
                            right = source.sample(sourcePosition, 1);

                            if (layout == ChannelLayout.MONO)
                            {
                                left = (left + right) * 0.5D;
                            }
                        }
                    }

                    if (layout == ChannelLayout.MONO)
                    {
                        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, block, offset, left);
                    }
                    else
                    {
                        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, block, offset, left);
                        PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, block,
                            offset + 2, right);
                    }
                }

                stream.write(block, 0, bytes);
            }

            if (cancelled.getAsBoolean())
            {
                throw new CancellationException("Audio normalization cancelled");
            }
        }
        catch (Exception | Error e)
        {
            if (created)
            {
                try
                {
                    Files.deleteIfExists(output);
                }
                catch (Exception cleanup)
                {
                    e.addSuppressed(cleanup);
                }
            }

            throw e;
        }
    }

    /** Small sequential PCM reader; it never materializes the source payload. */
    private static final class StreamingSource
    {
        private final AudioInputStream stream;
        private final int channels;
        private final int frameSize;
        private final boolean bigEndian;
        private final double sampleRate;
        private final long declaredFrames;
        private final byte[] bytes;
        private final double[] current;
        private final double[] next;
        private long currentIndex;
        private long framesRead;
        private boolean currentValid;
        private boolean nextValid;

        private StreamingSource(AudioInputStream stream, AudioFormat format) throws IOException
        {
            this.stream = stream;
            this.channels = format.getChannels();
            this.frameSize = format.getFrameSize();
            this.bigEndian = format.isBigEndian();
            this.sampleRate = format.getSampleRate();
            long frameLength = stream.getFrameLength();
            this.declaredFrames = frameLength >= 0L ? frameLength : -1L;
            this.bytes = new byte[this.frameSize];
            this.current = new double[this.channels];
            this.next = new double[this.channels];
            this.currentIndex = 0L;
            this.framesRead = 0L;
            this.currentValid = this.readFrame(this.current);
            this.nextValid = this.currentValid && this.readFrame(this.next);
        }

        static StreamingSource open(AudioInputStream stream) throws IOException
        {
            AudioFormat format = stream.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                || format.getSampleSizeInBits() != 16 || format.getChannels() < 1
                || format.getChannels() > 2 || format.getFrameSize() != format.getChannels() * 2
                || !Float.isFinite(format.getSampleRate()) || format.getSampleRate() <= 0F)
            {
                throw new IOException("Unsupported PCM normalization source format: " + format);
            }

            return new StreamingSource(stream, format);
        }

        double sampleRate()
        {
            return this.sampleRate;
        }

        int channels()
        {
            return this.channels;
        }

        double sample(double position, int requestedChannel) throws IOException
        {
            if (!Double.isFinite(position) || position < 0D || !this.currentValid)
            {
                return 0D;
            }

            long target = (long) Math.floor(position);
            while (target > this.currentIndex && this.nextValid)
            {
                System.arraycopy(this.next, 0, this.current, 0, this.channels);
                this.currentIndex += 1L;
                this.nextValid = this.readFrame(this.next);
            }

            if (target != this.currentIndex)
            {
                return 0D;
            }

            int channel = Math.min(requestedChannel, this.channels - 1);
            double a = this.current[channel];
            double b = this.nextValid ? this.next[channel] : a;
            return a + (b - a) * (position - target);
        }

        private boolean readFrame(double[] destination) throws IOException
        {
            if (this.declaredFrames >= 0L && this.framesRead >= this.declaredFrames)
            {
                return false;
            }

            int offset = 0;
            int zeroReads = 0;
            while (offset < this.frameSize)
            {
                int read = this.stream.read(this.bytes, offset, this.frameSize - offset);

                if (read < 0)
                {
                    if (offset != 0 || (this.declaredFrames >= 0L
                        && this.framesRead < this.declaredFrames))
                    {
                        throw new EOFException("Truncated PCM normalization source at frame "
                            + this.framesRead);
                    }

                    return false;
                }

                if (read == 0)
                {
                    if (++zeroReads >= MAX_CONSECUTIVE_ZERO_READS)
                    {
                        throw new IOException("PCM normalization source made no read progress");
                    }

                    continue;
                }

                if (read > this.frameSize - offset)
                {
                    throw new IOException("PCM normalization source returned an oversized frame read");
                }

                zeroReads = 0;
                offset += read;
            }

            for (int channel = 0; channel < this.channels; channel++)
            {
                int index = channel * 2;
                int first = this.bytes[index] & 0xff;
                int second = this.bytes[index + 1] & 0xff;
                int value = this.bigEndian ? (first << 8) | second : first | (second << 8);
                short signed = (short) value;
                destination[channel] = signed / 32768.0D;
            }

            this.framesRead += 1L;

            return true;
        }
    }
}
