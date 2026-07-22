package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.PcmSamples;
import mchorse.bbs_mod.audio.wav.WaveWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Focused bounded-stream and block-boundary checks for export PCM normalization. */
public final class VideoExportAudioNormalizerTest
{
    private VideoExportAudioNormalizerTest()
    {}

    public static void runAll() throws Exception
    {
        declaredLongSourceDoesNotMaterializeBeforeCancellation();
        truncatedSourceIsNotSilentlyPadded();
        routingRemainsExactAcrossBlockBoundary();
    }

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("VideoExportAudioNormalizerTest: all tests passed");
    }

    /**
     * The RIFF declaration is about 4 GB, but the physical fixture contains
     * only one output block plus lookahead. A whole-file conversion would
     * either allocate unbounded memory or read past the cancellation fence.
     */
    private static void declaredLongSourceDoesNotMaterializeBeforeCancellation() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-normalizer-bounded-");

        try
        {
            PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE,
                ChannelLayout.STEREO, 48000);
            long declaredFrames = 1_000_000_000L;
            Path source = root.resolve("declared-long.wav");
            Path output = root.resolve("cancelled.wav");
            int physicalFrames = VideoExportAudioNormalizer.BLOCK_FRAMES + 2;

            writeDeclaredSource(source, format, declaredFrames, physicalFrames);
            check(Files.size(source) < 100_000L,
                "bounded fixture unexpectedly materialized its declared payload");

            AtomicInteger cancellationChecks = new AtomicInteger();
            try
            {
                VideoExportAudioNormalizer.normalize(source, output,
                    ChannelLayout.STEREO, format.sampleRate(), declaredFrames,
                    () -> cancellationChecks.incrementAndGet() >= 2);
                throw new AssertionError("long normalization returned success after cancellation");
            }
            catch (CancellationException expected)
            {
                // The first block is allowed to finish; no later block may be committed.
            }

            check(cancellationChecks.get() == 2,
                "normalizer did not check cancellation at the block boundary");
            check(!Files.exists(output),
                "cancelled normalization published a partial destination");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void truncatedSourceIsNotSilentlyPadded() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-normalizer-truncated-");

        try
        {
            PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE,
                ChannelLayout.MONO, 48000);
            Path source = root.resolve("truncated.wav");
            Path output = root.resolve("normalized.wav");

            writeDeclaredSource(source, format, 2L, 1);

            try
            {
                VideoExportAudioNormalizer.normalize(source, output,
                    ChannelLayout.MONO, format.sampleRate(), 2L, () -> false);
                throw new AssertionError("truncated source was reported as successful audio");
            }
            catch (IOException expected)
            {
                // A declared-but-missing frame is malformed input, not silence.
            }

            check(!Files.exists(output),
                "failed normalization left a partial destination");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void routingRemainsExactAcrossBlockBoundary() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-normalizer-boundary-");

        try
        {
            int frames = VideoExportAudioNormalizer.BLOCK_FRAMES + 3;
            PcmFormat sourceFormat = new PcmFormat(PcmEncoding.PCM_S16_LE,
                ChannelLayout.STEREO, 48000);
            byte[] data = new byte[Math.multiplyExact(frames, sourceFormat.bytesPerFrame())];

            for (int frame = 0; frame < frames; frame++)
            {
                double left = frame == VideoExportAudioNormalizer.BLOCK_FRAMES - 1 ? 0.75D
                    : frame == VideoExportAudioNormalizer.BLOCK_FRAMES ? -0.5D : 0.125D;
                double right = frame == VideoExportAudioNormalizer.BLOCK_FRAMES - 1 ? -0.25D
                    : frame == VideoExportAudioNormalizer.BLOCK_FRAMES ? 0.25D : -0.125D;
                int offset = frame * sourceFormat.bytesPerFrame();

                PcmSamples.writeNormalized(sourceFormat.encoding(), data, offset, left);
                PcmSamples.writeNormalized(sourceFormat.encoding(), data, offset + 2, right);
            }

            Path source = root.resolve("boundary-source.wav");
            Path stereoOutput = root.resolve("boundary-stereo.wav");
            Path monoOutput = root.resolve("boundary-mono.wav");
            writeSource(source, sourceFormat, data);

            VideoExportAudioNormalizer.normalize(source, stereoOutput,
                ChannelLayout.STEREO, sourceFormat.sampleRate(), frames, () -> false);
            VideoExportAudioNormalizer.normalize(source, monoOutput,
                ChannelLayout.MONO, sourceFormat.sampleRate(), frames, () -> false);

            byte[] stereo = Files.readAllBytes(stereoOutput);
            byte[] mono = Files.readAllBytes(monoOutput);
            int boundary = VideoExportAudioNormalizer.BLOCK_FRAMES;

            check(pcmFrames(stereo, 2) == frames && pcmFrames(mono, 1) == frames,
                "block-boundary normalization changed the requested duration");
            assertNear(0.75D, sample(stereo, boundary - 1, 0, 2), 1D / 32768D,
                "left channel changed before the block boundary");
            assertNear(-0.25D, sample(stereo, boundary - 1, 1, 2), 1D / 32768D,
                "right channel changed before the block boundary");
            assertNear(-0.5D, sample(stereo, boundary, 0, 2), 1D / 32768D,
                "left channel changed at the block boundary");
            assertNear(0.25D, sample(stereo, boundary, 1, 2), 1D / 32768D,
                "right channel changed at the block boundary");
            assertNear(0.25D, sample(mono, boundary - 1, 0, 1), 1D / 32768D,
                "stereo-to-mono routing changed before the block boundary");
            assertNear(-0.125D, sample(mono, boundary, 0, 1), 1D / 32768D,
                "stereo-to-mono routing changed at the block boundary");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void writeDeclaredSource(Path path, PcmFormat format,
                                            long declaredFrames, int physicalFrames)
        throws IOException
    {
        long dataLength = Math.multiplyExact(declaredFrames, (long) format.bytesPerFrame());
        int physicalBytes = Math.multiplyExact(physicalFrames, format.bytesPerFrame());
        byte[] data = new byte[physicalBytes];

        for (int frame = 0; frame < physicalFrames; frame++)
        {
            int offset = frame * format.bytesPerFrame();
            for (int channel = 0; channel < format.channels(); channel++)
            {
                double value = ((frame + channel) & 1) == 0 ? 0.25D : -0.25D;
                PcmSamples.writeNormalized(format.encoding(), data,
                    offset + channel * format.bytesPerSample(), value);
            }
        }

        writeSource(path, format, dataLength, data);
    }

    private static void writeSource(Path path, PcmFormat format, byte[] data) throws IOException
    {
        writeSource(path, format, data.length, data);
    }

    private static void writeSource(Path path, PcmFormat format,
                                    long declaredDataLength, byte[] data) throws IOException
    {
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(path)))
        {
            WaveWriter.writeHeader(stream, format, declaredDataLength);
            stream.write(data);
        }
    }

    private static long pcmFrames(byte[] wave, int channels)
    {
        check(wave.length >= 44 && wave[36] == 'd' && wave[37] == 'a'
                && wave[38] == 't' && wave[39] == 'a',
            "normalizer output is not canonical PCM WAV");
        long dataLength = (wave[40] & 0xffL)
            | (wave[41] & 0xffL) << 8
            | (wave[42] & 0xffL) << 16
            | (wave[43] & 0xffL) << 24;

        check(dataLength == wave.length - 44L,
            "normalizer WAV header does not match its physical payload");
        check(dataLength % (channels * 2L) == 0L,
            "normalizer WAV payload ends with a partial frame");

        return dataLength / (channels * 2L);
    }

    private static double sample(byte[] wave, int frame, int channel, int channels)
    {
        int offset = Math.addExact(44,
            Math.addExact(Math.multiplyExact(frame, channels * 2), channel * 2));
        short value = (short) ((wave[offset] & 0xff) | wave[offset + 1] << 8);

        return value / 32768.0D;
    }

    private static void assertNear(double expected, double actual,
                                   double tolerance, String message)
    {
        if (Math.abs(expected - actual) > tolerance)
        {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static void check(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;

        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
