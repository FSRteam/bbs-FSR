package mchorse.bbs_mod.ui.film.audio;

import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.wav.WaveWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Fixed-memory WAV sink. PCM is streamed to a session-owned temporary file. */
final class StreamingWavSink
{
    private final Path path;
    private final PcmFormat format;
    private final long maxBytes;
    private final byte[] scratch = new byte[64 * 1024];
    private RandomAccessFile file;
    private long bytes;
    private boolean finished;

    private StreamingWavSink(Path path, PcmFormat format, long maxBytes, RandomAccessFile file) throws IOException
    {
        this.path = path;
        this.format = format;
        this.maxBytes = maxBytes;
        this.file = file;

        byte[] header = header(format, 0L);
        this.file.write(header);
    }

    static StreamingWavSink create(Path directory, CaptureSpec spec) throws IOException
    {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(spec, "spec");
        Files.createDirectories(directory);
        Path path = Files.createTempFile(directory, ".bbs-capture-", ".wav.tmp");
        RandomAccessFile file = null;

        try
        {
            long maxBytes = Math.multiplyExact(spec.maxFrames(), (long) spec.bytesPerFrame());
            file = new RandomAccessFile(path.toFile(), "rw");

            return new StreamingWavSink(path, spec.pcmFormat(), maxBytes, file);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            Throwable first = failure;

            if (file != null)
            {
                try
                {
                    file.close();
                }
                catch (IOException cleanup)
                {
                    first.addSuppressed(cleanup);
                }
            }

            try
            {
                Files.deleteIfExists(path);
            }
            catch (IOException cleanup)
            {
                first.addSuppressed(cleanup);
            }

            if (first instanceof IOException io)
            {
                throw io;
            }
            else if (first instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            else
            {
                throw (Error) first;
            }
        }
    }

    Path path()
    {
        return this.path;
    }

    long frames()
    {
        return this.bytes / this.format.bytesPerFrame();
    }

    long bytes()
    {
        return this.bytes;
    }

    void write(ByteBuffer source, int frames) throws IOException
    {
        if (this.finished)
        {
            throw new IOException("Capture sink is already finalized");
        }

        if (frames < 0 || source == null)
        {
            throw new IllegalArgumentException("Invalid capture chunk");
        }

        long chunkBytes = Math.multiplyExact((long) frames, this.format.bytesPerFrame());

        if (chunkBytes > source.remaining())
        {
            throw new IOException("Capture backend returned a partial frame chunk");
        }

        if (this.bytes > this.maxBytes - chunkBytes)
        {
            throw new IOException("Capture duration limit exceeded");
        }

        ByteBuffer copy = source.duplicate();
        copy.limit(copy.position() + Math.toIntExact(chunkBytes));
        while (copy.hasRemaining())
        {
            int amount = Math.min(copy.remaining(), this.scratch.length);
            copy.get(this.scratch, 0, amount);
            this.file.write(this.scratch, 0, amount);
        }

        this.bytes += chunkBytes;
        source.position(source.position() + Math.toIntExact(chunkBytes));
    }

    void finish() throws IOException
    {
        if (this.finished)
        {
            return;
        }

        if ((this.bytes % this.format.bytesPerFrame()) != 0)
        {
            throw new IOException("Capture sink contains a partial frame");
        }

        this.file.seek(0L);
        this.file.write(header(this.format, this.bytes));
        this.file.getFD().sync();
        this.file.close();
        this.file = null;
        this.finished = true;
    }

    void abort() throws IOException
    {
        IOException failure = null;

        if (!this.finished)
        {
            this.finished = true;

            if (this.file != null)
            {
                try
                {
                    this.file.close();
                }
                catch (IOException e)
                {
                    failure = e;
                }
                finally
                {
                    this.file = null;
                }
            }
        }

        try
        {
            Files.deleteIfExists(this.path);
        }
        catch (IOException e)
        {
            if (failure == null)
            {
                failure = e;
            }
            else
            {
                failure.addSuppressed(e);
            }
        }

        if (failure != null)
        {
            throw failure;
        }
    }

    private static byte[] header(PcmFormat format, long dataLength) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(44);
        WaveWriter.writeHeader(bytes, format, dataLength);

        return bytes.toByteArray();
    }
}
