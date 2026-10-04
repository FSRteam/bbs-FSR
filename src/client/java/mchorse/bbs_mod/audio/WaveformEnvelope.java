package mchorse.bbs_mod.audio;

/** Sample-accurate peak/mean queries, with a compact tree over 64-frame blocks. */
final class WaveformEnvelope
{
    private static final int BLOCK = 64;
    private final Wave wave;
    private final int frames;
    private final int leaves;
    private final float[] peaks;
    private final double[] sums;

    static final class Range
    {
        float maximum;
        float average;
        private double sum;
    }

    WaveformEnvelope(Wave wave)
    {
        if (wave.getBytesPerSample() != 2 || wave.numChannels <= 0 || wave.sampleRate <= 0)
        {
            throw new IllegalArgumentException("Waveform requires 16-bit PCM with a valid sample rate and channels");
        }

        this.wave = wave;
        this.frames = wave.data.length / (2 * wave.numChannels);
        this.leaves = Math.max(1, (this.frames + BLOCK - 1) / BLOCK);
        this.peaks = new float[this.leaves * 2];
        this.sums = new double[this.leaves * 2];

        for (int frame = 0; frame < this.frames; frame++)
        {
            int node = this.leaves + frame / BLOCK;

            for (int channel = 0; channel < wave.numChannels; channel++)
            {
                float value = this.amplitude(frame, channel);

                this.peaks[node] = Math.max(this.peaks[node], value);
                this.sums[node] += value;
            }
        }

        for (int node = this.leaves - 1; node > 0; node--)
        {
            this.peaks[node] = Math.max(this.peaks[node * 2], this.peaks[node * 2 + 1]);
            this.sums[node] = this.sums[node * 2] + this.sums[node * 2 + 1];
        }
    }

    private float amplitude(int frame, int channel)
    {
        int offset = (frame * this.wave.numChannels + channel) * 2;
        int sample = (short) ((this.wave.data[offset] & 255) | (this.wave.data[offset + 1] << 8));

        return Math.abs(sample) / 32768F;
    }

    void sample(double from, double to, Range out)
    {
        out.maximum = out.average = 0;
        out.sum = 0;

        if (!(to > from) || to <= 0 || from >= this.frames / (double) this.wave.sampleRate)
        {
            return;
        }

        int start = (int) Math.max(0, Math.floor(from * this.wave.sampleRate));
        int end = (int) Math.min(this.frames, Math.ceil(to * this.wave.sampleRate));
        int count = end - start;

        /* Only the two partial edge blocks read PCM; the interior uses the tree. */
        while (start < end && start % BLOCK != 0)
        {
            this.addFrame(start++, out);
        }

        while (end > start && end % BLOCK != 0)
        {
            this.addFrame(--end, out);
        }

        int left = this.leaves + start / BLOCK;
        int right = this.leaves + end / BLOCK;

        while (left < right)
        {
            if ((left & 1) != 0)
            {
                this.addNode(left++, out);
            }

            if ((right & 1) != 0)
            {
                this.addNode(--right, out);
            }

            left /= 2;
            right /= 2;
        }

        out.average = count > 0 ? (float) (out.sum / ((double) count * this.wave.numChannels)) : 0;
    }

    private void addFrame(int frame, Range out)
    {
        for (int channel = 0; channel < this.wave.numChannels; channel++)
        {
            float value = this.amplitude(frame, channel);

            out.maximum = Math.max(out.maximum, value);
            out.sum += value;
        }
    }

    private void addNode(int node, Range out)
    {
        out.maximum = Math.max(out.maximum, this.peaks[node]);
        out.sum += this.sums[node];
    }
}
