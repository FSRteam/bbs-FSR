package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;

import java.util.List;

public class Waveform
{
    private WaveformEnvelope envelope;
    private List<ColorCode> colorCodes;
    private float[] cues;
    private int pixelsPerSecond;
    private int height;
    private float duration;

    public void generate(Wave data, List<ColorCode> colorCodes, int pixelsPerSecond, int height)
    {
        /* The envelope reads raw 16-bit PCM; FSR's audio pipeline may hand out
         * 8-bit or float WAVs, so convert up front (same as the texture-baked
         * version this replaces). */
        Wave envelopeData = data.getFormat().encoding() == PcmEncoding.PCM_S16_LE
            ? data
            : data.convertTo16();

        this.populate(envelopeData, pixelsPerSecond, height);
        this.render(colorCodes, envelopeData.getCues());
    }

    /** Update annotations without rebuilding the audio envelope. */
    public void render(List<ColorCode> colorCodes, float[] cues)
    {
        this.colorCodes = colorCodes;
        this.cues = cues;
    }

    public void populate(Wave data, int pixelsPerSecond, int height)
    {
        this.envelope = new WaveformEnvelope(data);
        this.pixelsPerSecond = Math.max(1, pixelsPerSecond);
        this.height = height;
        this.duration = data.getDuration();
    }

    public void delete()
    {
        this.envelope = null;
        this.colorCodes = null;
        this.cues = null;
    }

    public boolean isCreated()
    {
        return this.envelope != null;
    }

    /** Preview scale only; waveform detail is determined by the visible pixels. */
    public int getPixelsPerSecond()
    {
        return this.pixelsPerSecond;
    }

    public int getWidth()
    {
        return (int) Math.ceil(this.duration * this.pixelsPerSecond);
    }

    public int getHeight()
    {
        return this.height;
    }

    public float getDuration()
    {
        return this.duration;
    }

    public void render(Batcher2D batcher, int color, int x, int y, int w, int h, float startTime, float endTime)
    {
        if (this.envelope == null || w <= 0 || h <= 0 || !(endTime > startTime))
        {
            return;
        }

        double secondsPerPixel = (endTime - (double) startTime) / w;
        /* Physical width also covers BBS's custom GUI scale. Limit CPU work
         * when a zoomed timeline extends far beyond the screen. */
        int first = (int) Math.max(0L, -(long) x);
        int last = (int) Math.min(w, (long) Minecraft.getInstance().getWindow().getWidth() - x);
        float center = y + h / 2F;
        WaveformEnvelope.Range range = new WaveformEnvelope.Range();
        boolean wasBatching = batcher.isBatching();

        batcher.clip(x, y, w, h, 0, 0);
        batcher.beginBatch();

        try
        {
            for (int i = first; i < last; i++)
            {
                double from = startTime + i * secondsPerPixel;
                double to = from + secondsPerPixel;

                if (to <= 0 || from >= this.duration)
                {
                    continue;
                }

                this.envelope.sample(from, to, range);
                int columnColor = color;

                if (this.colorCodes != null)
                {
                    for (ColorCode code : this.colorCodes)
                    {
                        if (code.isInside((float) Math.max(0, from)))
                        {
                            columnColor = tint(code.color | 0xff000000, color);
                            batcher.gradientVBox(x + i, y, x + i + 1, y + h, Colors.mulA(columnColor, 0.125F), Colors.mulA(columnColor, 0.375F));

                            break;
                        }
                    }
                }

                if (this.cues != null)
                {
                    for (float cue : this.cues)
                    {
                        if (cue >= from && cue < to)
                        {
                            batcher.box(x + i, y, x + i + 1, y + h, tint((BBSSettings.activeColor() & Colors.RGB) | Colors.A75, color));

                            break;
                        }
                    }
                }

                float peak = range.maximum * h / 2F;
                float average = range.average * h / 2F;

                batcher.box(x + i, center - peak, x + i + 1, center + peak, columnColor);
                batcher.box(x + i, center - average, x + i + 1, center + average, Colors.mulRGB(columnColor, 0.8F));
            }
        }
        finally
        {
            /* A scope this code opened must never leak into the shared batcher,
             * even if sampling throws midway through. */
            batcher.unclip(0, 0);

            if (!wasBatching)
            {
                batcher.endBatch();
            }
        }
    }

    private static int tint(int a, int b)
    {
        int result = 0;

        for (int shift = 0; shift <= 24; shift += 8)
        {
            result |= (((a >>> shift) & 255) * ((b >>> shift) & 255) / 255) << shift;
        }

        return result;
    }
}
