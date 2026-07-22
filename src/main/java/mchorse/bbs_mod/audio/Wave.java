package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.wav.WaveCue;
import mchorse.bbs_mod.audio.wav.WaveList;
import mchorse.bbs_mod.utils.MathUtils;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Wave
{
    private static final double CENTER_GAIN = Math.sqrt(0.5D);

    public int audioFormat;
    public int numChannels;
    public int sampleRate;
    public int byteRate;
    public int blockAlign;
    public int bitsPerSample;

    public byte[] data;

    public List<WaveList> lists = new ArrayList<>();
    public List<WaveCue> cues = new ArrayList<>();

    public Wave(PcmFormat format, byte[] data)
    {
        this(format.waveTag(), format.channels(), format.sampleRate(),
            Math.toIntExact(format.byteRate()), format.bytesPerFrame(),
            format.encoding().bitsPerSample(), data);
    }

    public Wave(int audioFormat, int numChannels, int sampleRate, int bitsPerSample, byte[] data)
    {
        this(new PcmFormat(PcmEncoding.fromWaveFormat(audioFormat, bitsPerSample),
            ChannelLayout.fromChannelCount(numChannels), sampleRate), data);
    }

    public Wave(int audioFormat, int numChannels, int sampleRate, int byteRate, int blockAlign, int bitsPerSample, byte[] data)
    {
        this.audioFormat = audioFormat;
        this.numChannels = numChannels;
        this.sampleRate = sampleRate;
        this.byteRate = byteRate;
        this.blockAlign = blockAlign;
        this.bitsPerSample = bitsPerSample;
        this.data = Objects.requireNonNull(data, "data");

        PcmFormat format = new PcmFormat(PcmEncoding.fromWaveFormat(audioFormat, bitsPerSample),
            ChannelLayout.fromChannelCount(numChannels), sampleRate);

        if (byteRate != format.byteRate())
        {
            throw new IllegalArgumentException("Invalid byte rate " + byteRate + ", expected " + format.byteRate());
        }

        if (blockAlign != format.bytesPerFrame())
        {
            throw new IllegalArgumentException("Invalid block alignment " + blockAlign + ", expected " + format.bytesPerFrame());
        }

        if (data.length % blockAlign != 0)
        {
            throw new IllegalArgumentException("PCM data ends with a partial frame");
        }
    }

    public PcmFormat getFormat()
    {
        PcmFormat format = new PcmFormat(PcmEncoding.fromWaveFormat(this.audioFormat, this.bitsPerSample),
            ChannelLayout.fromChannelCount(this.numChannels), this.sampleRate);

        if (this.byteRate != format.byteRate())
        {
            throw new IllegalStateException("Invalid byte rate " + this.byteRate + ", expected " + format.byteRate());
        }

        if (this.blockAlign != format.bytesPerFrame())
        {
            throw new IllegalStateException("Invalid block alignment " + this.blockAlign + ", expected " + format.bytesPerFrame());
        }

        if (this.data == null)
        {
            throw new IllegalStateException("PCM data is null");
        }

        if (this.data.length % format.bytesPerFrame() != 0)
        {
            throw new IllegalStateException("PCM data ends with a partial frame");
        }

        return format;
    }

    public int getBytesPerSample()
    {
        return this.getFormat().bytesPerSample();
    }

    public long getFrameCount()
    {
        PcmFormat format = this.getFormat();

        return this.data.length / format.bytesPerFrame();
    }

    public float getDuration()
    {
        return this.getFrameCount() / (float) this.sampleRate;
    }

    public int getALFormat()
    {
        PcmFormat format = this.getFormat();
        int bytes = format.bytesPerSample();

        if (format.encoding() == PcmEncoding.PCM_U8)
        {
            if (format.layout() == ChannelLayout.STEREO)
            {
                return AL10.AL_FORMAT_STEREO8;
            }
            else
            {
                return AL10.AL_FORMAT_MONO8;
            }
        }
        else if (format.encoding() == PcmEncoding.PCM_S16_LE)
        {
            if (format.layout() == ChannelLayout.STEREO)
            {
                return AL10.AL_FORMAT_STEREO16;
            }
            else
            {
                return AL10.AL_FORMAT_MONO16;
            }
        }

        throw new IllegalStateException("Current WAV file has unusual configuration... channels: " + this.numChannels + ", BPS: " + bytes);
    }

    public int getScanRegion(float pixelsPerSecond)
    {
        return (int) (this.sampleRate / pixelsPerSecond) * this.getBytesPerSample() * this.numChannels;
    }

    public Wave convertTo16()
    {
        return this.convert(PcmEncoding.PCM_S16_LE);
    }

    public Wave convert(PcmEncoding targetEncoding)
    {
        Objects.requireNonNull(targetEncoding, "targetEncoding");
        PcmFormat sourceFormat = this.getFormat();

        if (sourceFormat.encoding() == targetEncoding)
        {
            return this;
        }

        PcmFormat targetFormat = new PcmFormat(targetEncoding, sourceFormat.layout(), sourceFormat.sampleRate());
        int samples = Math.multiplyExact(Math.toIntExact(this.getFrameCount()), sourceFormat.channels());
        byte[] converted = new byte[Math.multiplyExact(samples, targetEncoding.bytesPerSample())];

        for (int i = 0; i < samples; i++)
        {
            int sourceOffset = i * sourceFormat.bytesPerSample();
            int targetOffset = i * targetEncoding.bytesPerSample();

            try
            {
                double sample = PcmSamples.readNormalized(sourceFormat.encoding(), this.data, sourceOffset);

                PcmSamples.writeNormalized(targetEncoding, converted, targetOffset, sample);
            }
            catch (IllegalArgumentException e)
            {
                int frame = i / sourceFormat.channels();
                int channel = i % sourceFormat.channels();

                throw new IllegalArgumentException("Could not convert PCM frame " + frame
                    + ", channel " + channel + ": " + e.getMessage(), e);
            }
        }

        return this.copyMetadataTo(new Wave(targetFormat, converted));
    }

    public Wave convertLayout(ChannelLayout targetLayout)
    {
        Objects.requireNonNull(targetLayout, "targetLayout");

        if (!targetLayout.supported())
        {
            throw new IllegalArgumentException("Unsupported channel layout: " + targetLayout.id());
        }

        PcmFormat sourceFormat = this.getFormat();

        if (sourceFormat.layout() == targetLayout)
        {
            return this;
        }

        PcmFormat targetFormat = new PcmFormat(sourceFormat.encoding(), targetLayout, sourceFormat.sampleRate());
        int frames = Math.toIntExact(this.getFrameCount());
        byte[] converted = new byte[Math.multiplyExact(frames, targetFormat.bytesPerFrame())];

        for (int frame = 0; frame < frames; frame++)
        {
            if (targetLayout == ChannelLayout.MONO)
            {
                double left = readSample(this, sourceFormat, frame, 0);
                double right = readSample(this, sourceFormat, frame, 1);

                PcmSamples.writeNormalized(sourceFormat.encoding(), converted,
                    frame * targetFormat.bytesPerFrame(), (left + right) * 0.5D);
            }
            else
            {
                /* A file-format conversion duplicates the source channel at
                 * unity. Export renderers apply their own equal-power output
                 * matrix when a mono source is placed on a stereo bus. */
                double sample = readSample(this, sourceFormat, frame, 0);
                int offset = frame * targetFormat.bytesPerFrame();

                PcmSamples.writeNormalized(sourceFormat.encoding(), converted, offset, sample);
                PcmSamples.writeNormalized(sourceFormat.encoding(), converted,
                    offset + targetFormat.bytesPerSample(), sample);
            }
        }

        return this.copyMetadataTo(new Wave(targetFormat, converted));
    }

    public float[] getCues()
    {
        float[] cues = new float[this.cues.size()];
        int i = 0;

        for (WaveCue cue : this.cues)
        {
            /* dwSampleOffset is the sample position in the referenced data
             * chunk.  dwPosition is only the playlist order position and may
             * legitimately differ when a cue references a segmented file. */
            cues[i] = Integer.toUnsignedLong(cue.sampleStart) / (float) this.sampleRate;

            i += 1;
        }

        return cues;
    }

    /**
     * Improved sample rate conversion with linear interpolation
     * Reduces aliasing distortion and frequency loss during audio mixing
     * Fixed stereo to mono conversion and sample position calculation
     */
    public void add(ByteBuffer buffer, Wave wave, float offset, float shift, float duration)
    {
        this.add(buffer, wave, offset, shift, duration, 1F);
    }

    /**
     * Compatibility one-source mixer. Export rendering uses the block mixer so
     * accumulation is limited and quantized only once.
     */
    @Deprecated
    public void add(ByteBuffer buffer, Wave wave, float offset, float shift, float duration, float gain)
    {
        Objects.requireNonNull(wave, "wave");
        if (!Float.isFinite(offset) || !Float.isFinite(shift) || !Float.isFinite(duration) || !Float.isFinite(gain))
        {
            throw new IllegalArgumentException("Mix parameters must be finite");
        }

        PcmFormat targetFormat = this.getFormat();
        PcmFormat sourceFormat = wave.getFormat();
        long targetFrames = this.getFrameCount();
        long sourceFrames = wave.getFrameCount();

        /* A non-positive window has no samples, even when its start lies
         * between two output frames.  Using floor/ceil without this guard
         * would unexpectedly mix one frame for a zero-length clip. */
        if (duration <= 0F || targetFrames == 0L || sourceFrames == 0L)
        {
            return;
        }

        double targetOffset = offset * (double) targetFormat.sampleRate();
        double targetEnd = ((double) offset + duration) * targetFormat.sampleRate();
        long startFrame = targetOffset <= 0D ? 0L
            : targetOffset >= targetFrames ? targetFrames : (long) Math.ceil(targetOffset);
        long endFrame = targetEnd <= 0D ? 0L
            : targetEnd >= targetFrames ? targetFrames : (long) Math.ceil(targetEnd);

        if (endFrame <= startFrame)
        {
            return;
        }

        /* The active interval is half-open.  Ceil the start so a fractional
         * clip never contributes before its requested onset, even when the
         * source shift is positive. */
        double sourcePosition = shift * sourceFormat.sampleRate()
            + (startFrame - targetOffset) * sourceFormat.sampleRate() / targetFormat.sampleRate();
        double sourceStep = sourceFormat.sampleRate() / (double) targetFormat.sampleRate();

        for (long targetFrame = startFrame; targetFrame < endFrame && sourcePosition < sourceFrames; targetFrame++, sourcePosition += sourceStep)
        {
            if (sourcePosition < 0D)
            {
                continue;
            }

            for (int channel = 0; channel < targetFormat.channels(); channel++)
            {
                double source = this.sampleForChannel(wave, sourceFormat, sourceFrames,
                    sourcePosition, channel, targetFormat.layout());
                int byteOffset = Math.toIntExact(targetFrame * targetFormat.bytesPerFrame()
                    + (long) channel * targetFormat.bytesPerSample());
                double target = PcmSamples.readNormalized(targetFormat.encoding(), this.data, byteOffset);

                PcmSamples.writeNormalized(targetFormat.encoding(), this.data, byteOffset, target + source * gain);
            }
        }
    }

    private double sampleForChannel(Wave wave, PcmFormat sourceFormat, long sourceFrames,
                                    double position, int targetChannel, ChannelLayout targetLayout)
    {
        if (targetLayout == ChannelLayout.MONO && wave.numChannels == 2)
        {
            return (this.interpolate(wave, sourceFormat, sourceFrames, position, 0)
                + this.interpolate(wave, sourceFormat, sourceFrames, position, 1)) * 0.5D;
        }

        double sample = this.interpolate(wave, sourceFormat, sourceFrames, position,
            wave.numChannels == 1 ? 0 : targetChannel);

        return targetLayout == ChannelLayout.STEREO && wave.numChannels == 1
            ? sample * CENTER_GAIN
            : sample;
    }

    private double interpolate(Wave wave, PcmFormat format, long frames, double position, int channel)
    {
        if (frames <= 0L || !Double.isFinite(position) || position < 0D || position >= frames
            || channel < 0 || channel >= wave.numChannels)
        {
            return 0D;
        }

        long first = (long) Math.floor(position);
        long second = Math.min(first + 1L, frames - 1L);
        double fraction = position - first;
        double a = readSample(wave, format, first, channel);
        double b = readSample(wave, format, second, channel);

        return a + (b - a) * fraction;
    }

    private static double readSample(Wave wave, PcmFormat format, long frame, int channel)
    {
        long offset = Math.addExact(Math.multiplyExact(frame, format.bytesPerFrame()),
            Math.multiplyExact((long) channel, format.bytesPerSample()));

        try
        {
            return PcmSamples.readNormalized(format.encoding(), wave.data, Math.toIntExact(offset));
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException("Could not read PCM frame " + frame
                + ", channel " + channel + ": " + e.getMessage(), e);
        }
    }

    /** Creates a channel-preserving copy of the time range [fromSeconds, toSeconds). */
    public Wave excerpt(float fromSeconds, float toSeconds)
    {
        if (!Float.isFinite(fromSeconds) || !Float.isFinite(toSeconds))
        {
            throw new IllegalArgumentException("Excerpt bounds must be finite");
        }

        float duration = this.getDuration();
        float from = MathUtils.clamp(fromSeconds, 0F, duration);
        float to = MathUtils.clamp(toSeconds, 0F, duration);

        if (to < from)
        {
            float swap = from;

            from = to;
            to = swap;
        }

        PcmFormat format = this.getFormat();
        long frameCount = this.getFrameCount();
        long startFrame = Math.max(0L, Math.min(frameCount, (long) Math.floor(from * format.sampleRate())));

        if (to <= from)
        {
            return this.copyExcerptMetadata(new Wave(format, new byte[0]), startFrame, startFrame);
        }

        long endFrame = Math.max(0L, Math.min(frameCount, (long) Math.ceil(to * format.sampleRate())));

        if (endFrame <= startFrame)
        {
            return this.copyExcerptMetadata(new Wave(format, new byte[0]), startFrame, endFrame);
        }

        int startByte = Math.toIntExact(Math.multiplyExact(startFrame, format.bytesPerFrame()));
        int endByte = Math.toIntExact(Math.multiplyExact(endFrame, format.bytesPerFrame()));
        byte[] output = new byte[endByte - startByte];

        System.arraycopy(this.data, startByte, output, 0, output.length);

        return this.copyExcerptMetadata(new Wave(format, output), startFrame, endFrame);
    }

    /**
     * Compatibility adapter for callers that explicitly request a mono excerpt.
     */
    @Deprecated
    public Wave excerptMono(float fromSeconds, float toSeconds)
    {
        return this.excerpt(fromSeconds, toSeconds).convertLayout(ChannelLayout.MONO);
    }

    private Wave copyMetadataTo(Wave wave)
    {
        wave.lists = this.lists;
        wave.cues = this.cues;

        return wave;
    }

    private Wave copyExcerptMetadata(Wave wave, long startFrame, long endFrame)
    {
        wave.lists = this.lists;
        wave.cues = new ArrayList<>();

        for (WaveCue cue : this.cues)
        {
            long sampleStart = Integer.toUnsignedLong(cue.sampleStart);

            if (sampleStart < startFrame || sampleStart >= endFrame)
            {
                continue;
            }

            WaveCue copy = new WaveCue();
            copy.id = cue.id;
            long position = Integer.toUnsignedLong(cue.position);
            copy.position = (int) Math.max(0L, position - startFrame);
            copy.dataChunkID = cue.dataChunkID;
            copy.chunkStart = cue.chunkStart;
            copy.blockStart = cue.blockStart;
            copy.sampleStart = (int) (sampleStart - startFrame);
            wave.cues.add(copy);
        }

        return wave;
    }
}
