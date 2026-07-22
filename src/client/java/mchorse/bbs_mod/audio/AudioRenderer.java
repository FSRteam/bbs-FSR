package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;

import java.io.File;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

public class AudioRenderer
{
    private static final float WAVEFORM_PAST_BRIGHTNESS = 0.45F;
    private static final int WAVEFORM_PLAYHEAD_COLOR = 0xff57f52a;

    /**
     * One preview bar with every audible clip layered in the same strip, using the same scrolling window as
     * {@link #renderWaveform}: playhead stays centered; left = past (dim), right = future; the window moves with {@code tick}.
     */
    public static void renderPreviewCombined(Batcher2D batcher, List<AudioClip> clips, float tick, int x, int y, int w, int h, int sw, int sh)
    {
        int dimColor = waveformDimColor();

        drawWaveformPanelChrome(batcher, x, y, w, h);

        int innerX = x + 2;
        int innerY = y + 2;
        int innerW = w - 4;
        int half = w / 2;
        int centerX = x + half;

        int pps = resolveWaveformPixelsPerSecond(clips);

        /* Same visible time span as renderWaveform (full panel width w, not inner) */
        float duration = w / (float) pps;
        float windowTicks = duration * 20F;
        float visibleStart = tick - windowTicks / 2F;
        float visibleEnd = tick + windowTicks / 2F;

        batcher.clip(innerX, innerY, innerW, h - 4, sw, sh);

        for (AudioClip clip : clips)
        {
            SoundBuffer audio = BBSModClient.getSounds().get(clip.audio.get(), true);

            if (audio == null || audio.getWaveform() == null)
            {
                continue;
            }

            Waveform wave = audio.getWaveform();
            int clipDurTicks = Math.min((int) (wave.getDuration() * 20), clip.duration.get());
            float clipStart = clip.tick.get();
            float clipEnd = clipStart + clipDurTicks;

            float s1 = Math.max(visibleStart, clipStart);
            float s2 = Math.min(visibleEnd, clipEnd);

            if (s1 >= s2)
            {
                continue;
            }

            float rel1 = half + (s1 - tick) * w / (duration * 20F);
            float rel2 = half + (s2 - tick) * w / (duration * 20F);

            int drawX1 = x + (int) rel1;
            int drawX2 = x + (int) Math.ceil(rel2);

            drawX1 = Math.max(innerX, drawX1);
            drawX2 = Math.min(innerX + innerW, Math.max(drawX1 + 1, drawX2));

            float a1 = TimeUtils.toSeconds(s1 - clip.tick.get() + clip.offset.get());
            float a2 = TimeUtils.toSeconds(s2 - clip.tick.get() + clip.offset.get());
            float aCenter = TimeUtils.toSeconds(tick - clip.tick.get() + clip.offset.get());

            float maxA = wave.getDuration();
            a1 = Math.max(0F, Math.min(maxA, a1));
            a2 = Math.max(0F, Math.min(maxA, a2));
            aCenter = Math.max(0F, Math.min(maxA, aCenter));

            if (a2 <= a1)
            {
                continue;
            }

            if (drawX2 <= centerX)
            {
                wave.render(batcher, dimColor, drawX1, y, drawX2 - drawX1, h, a1, a2);
            }
            else if (drawX1 >= centerX)
            {
                wave.render(batcher, Colors.WHITE, drawX1, y, drawX2 - drawX1, h, a1, a2);
            }
            else
            {
                wave.render(batcher, dimColor, drawX1, y, centerX - drawX1, h, a1, aCenter);
                wave.render(batcher, Colors.WHITE, centerX, y, drawX2 - centerX, h, aCenter, a2);
            }
        }

        batcher.unclip(sw, sh);

        drawWaveformPlayhead(batcher, x, y, w, h);

        ActiveAudioAtTick active = findActiveAudioAtTick(clips, tick);

        if (BBSSettings.audioWaveformFilename.get() && active != null && active.buffer != null)
        {
            batcher.textCard(active.buffer.getId().toString(), x + 8, y + h / 2 - 4, 0xffffff, 0x99000000);
        }

        if (BBSSettings.audioWaveformTime.get())
        {
            float playback = active != null
                ? TimeUtils.toSeconds(tick - active.clip.tick.get() + active.clip.offset.get())
                : TimeUtils.toSeconds(tick);

            drawWaveformTimeLabel(batcher, tick, playback, x, y, w, h);
        }
    }

    private static int waveformDimColor()
    {
        return Colors.COLOR.set(WAVEFORM_PAST_BRIGHTNESS, WAVEFORM_PAST_BRIGHTNESS, WAVEFORM_PAST_BRIGHTNESS, 1F).getARGBColor();
    }

    private static int resolveWaveformPixelsPerSecond(List<AudioClip> clips)
    {
        for (AudioClip clip : clips)
        {
            SoundBuffer audio = BBSModClient.getSounds().get(clip.audio.get(), true);

            if (audio != null && audio.getWaveform() != null)
            {
                int pps = audio.getWaveform().getPixelsPerSecond();

                if (pps > 0)
                {
                    return pps;
                }
            }
        }

        return BBSSettings.audioWaveformDensity.get();
    }

    private static void drawWaveformPlayhead(Batcher2D batcher, int x, int y, int w, int h)
    {
        int half = w / 2;

        batcher.box(x + half, y + 1, x + half + 1, y + h - 1, WAVEFORM_PLAYHEAD_COLOR);
    }

    private static String formatWaveformTickLabel(float tick, float playbackSeconds)
    {
        int milliseconds = (int) (tick % 20 == 0 ? 0 : tick % 20 * 5D);

        return tick + "t (" + (int) playbackSeconds + "." + StringUtils.leftPad(String.valueOf(milliseconds), 2, "0") + "s)";
    }

    private static void drawWaveformTimeLabel(Batcher2D batcher, float tick, float playbackSeconds, int x, int y, int w, int h)
    {
        FontRenderer fontRenderer = batcher.getFont();
        String tickLabel = formatWaveformTickLabel(tick, playbackSeconds);

        batcher.textCard(tickLabel, x + w - 8 - fontRenderer.getWidth(tickLabel), y + h / 2 - 4, 0xffffff, 0x99000000);
    }

    private static ActiveAudioAtTick findActiveAudioAtTick(List<AudioClip> clips, float tick)
    {
        int t = (int) tick;

        for (AudioClip clip : clips)
        {
            if (clip.isInside(t))
            {
                SoundBuffer buffer = BBSModClient.getSounds().get(clip.audio.get(), true);

                return new ActiveAudioAtTick(clip, buffer);
            }
        }

        return null;
    }

    private record ActiveAudioAtTick(AudioClip clip, SoundBuffer buffer) {}

    private static void drawWaveformPanelChrome(Batcher2D batcher, int x, int y, int w, int h)
    {
        batcher.gradientVBox(x + 2, y + 2, x + w - 2, y + h, 0, Colors.A50);
        batcher.box(x + 1, y, x + 2, y + h, 0xaaffffff);
        batcher.box(x + w - 2, y, x + w - 1, y + h, 0xaaffffff);
        batcher.box(x, y + h - 1, x + w, y + h, 0xffffffff);
    }

    public static void renderAll(Batcher2D batcher, List<AudioClip> clips, float tick, int x, int y, int w, int h, int sw, int sh)
    {
        for (AudioClip clip : clips)
        {
            SoundBuffer audio = BBSModClient.getSounds().get(clip.audio.get(), true);

            if (audio != null && audio.getWaveform() != null && clip.isInside((int) tick))
            {
                renderWaveform(batcher, audio, clip, tick, x, y, w, h, sw, sh);

                y += h + 8;
            }
        }
    }

    public static void renderWaveform(Batcher2D batcher, SoundBuffer audio, AudioClip clip, float tick, int x, int y, int w, int h, int sw, int sh)
    {
        int half = w / 2;

        drawWaveformPanelChrome(batcher, x, y, w, h);

        batcher.clip(x + 2, y + 2, w - 4, h - 4, sw, sh);

        Waveform wave = audio.getWaveform();

        float duration = w / (float) wave.getPixelsPerSecond();
        float playback = TimeUtils.toSeconds(tick - clip.tick.get() + clip.offset.get());
        int offset = (int) (playback * wave.getPixelsPerSecond());
        int waveW = wave.getWidth();

        /* Draw the waveform */
        int runningOffset = waveW - offset;

        if (runningOffset > 0)
        {
            wave.render(batcher, Colors.WHITE, x + half, y, half, h, playback, playback + duration / 2);
        }

        /* Draw the passed waveform */
        if (offset > 0)
        {
            wave.render(batcher, waveformDimColor(), x, y, half, h, playback - duration / 2, playback);
        }

        batcher.unclip(sw, sh);

        drawWaveformPlayhead(batcher, x, y, w, h);

        if (BBSSettings.audioWaveformFilename.get())
        {
            batcher.textCard(audio.getId().toString(), x + 8, y + h / 2 - 4, 0xffffff, 0x99000000);
        }

        if (BBSSettings.audioWaveformTime.get())
        {
            drawWaveformTimeLabel(batcher, tick, playback, x, y, w, h);
        }
    }

    public static boolean renderAudio(File file, List<AudioClip> clips, int totalDuration, int sampleRate, float from, float to)
    {
        /* The legacy UI used [0,0] as its full-range sentinel. Keep that only
         * at this compatibility boundary; typed requests use a strict range. */
        float resolvedTo = from == 0F && to == 0F ? totalDuration / 20F : to;

        return renderAudioResult(file, clips, totalDuration, sampleRate, from, resolvedTo,
            resolveExportLayout(), () -> false, (completed, total) -> {}).success();
    }

    /** Render film audio with an explicit output layout and typed terminal result. */
    public static AudioRenderResult renderAudioResult(File file, List<AudioClip> clips,
                                                       int totalDuration, int sampleRate,
                                                       float from, float to,
                                                       ChannelLayout layout,
                                                       BooleanSupplier cancelled,
                                                       BiConsumer<Long, Long> progress)
    {
        if (file == null || clips == null || sampleRate <= 0 || totalDuration < 0
            || cancelled == null || progress == null)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Invalid audio render request", null);
        }

        if (layout == null || !layout.supported())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
                "Unsupported audio output layout", null);
        }

        if (cancelled.getAsBoolean())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, 0,
                "Audio render cancelled", null);
        }

        if (!Float.isFinite(from) || !Float.isFinite(to) || from < 0F || to < 0F || from > to)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Audio render range is invalid", null);
        }

        float totalSeconds = totalDuration / 20F;
        float start = Math.min(totalSeconds, from);
        float end = Math.min(totalSeconds, to);

        if (end <= start)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout, 0,
                "Audio render range is empty", null);
        }

        double startSeconds = decimalSeconds(start);
        double endSeconds = decimalSeconds(end);
        List<PreparedFilmSource> sources = new ArrayList<>();
        Map<Link, Wave> decoded = new HashMap<>();

        try
        {
            for (AudioClip clip : clips)
            {
                if (clip == null || !clip.enabled.get())
                {
                    continue;
                }

                if (cancelled.getAsBoolean())
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, 0,
                        "Audio render cancelled", null);
                }

                double clipStart = decimalSeconds(TimeUtils.toSeconds(clip.tick.get()));
                double sourceOffset = decimalSeconds(TimeUtils.toSeconds(clip.offset.get()));
                double clipDuration = decimalSeconds(Math.max(0F, TimeUtils.toSeconds(clip.duration.get())));
                float gain = clip.volume.get();

                if (!Double.isFinite(clipStart) || !Double.isFinite(sourceOffset)
                    || !Double.isFinite(clipDuration) || !Float.isFinite(gain))
                {
                    throw new IllegalArgumentException("Audio clip timing and gain must be finite");
                }

                double clipEnd = clipStart + clipDuration;
                if (clipDuration <= 0D || clipEnd <= startSeconds || clipStart >= endSeconds)
                {
                    continue;
                }

                Link audio = clip.audio.get();
                Wave wave = decoded.get(audio);

                if (wave == null)
                {
                    wave = AudioReader.read(BBSMod.getProvider(), audio);
                    decoded.put(audio, wave);
                }

                if (cancelled.getAsBoolean())
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, 0,
                        "Audio render cancelled", null);
                }

                sources.add(new PreparedFilmSource(wave, clipStart, sourceOffset, clipDuration, gain));
            }
        }
        catch (UnsupportedAudioFormatException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
                e.getMessage(), e);
        }
        catch (FileNotFoundException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MISSING_RESOURCE, file, layout, 0,
                e.getMessage(), e);
        }
        catch (AudioDecodeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                e.getMessage(), e);
        }
        catch (IOException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.IO_FAILURE, file, layout, 0,
                e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                e.getMessage(), e);
        }

        return renderPreparedAudio(file, sources, sampleRate, startSeconds, endSeconds,
            layout, cancelled, progress);
    }

    static AudioRenderResult renderPreparedAudio(File file, List<PreparedFilmSource> preparedSources,
                                                  int sampleRate, double start, double end,
                                                  ChannelLayout layout, BooleanSupplier cancelled,
                                                  BiConsumer<Long, Long> progress)
    {
        if (file == null || preparedSources == null || sampleRate <= 0
            || cancelled == null || progress == null)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Invalid prepared audio render request", null);
        }
        if (layout == null || !layout.supported())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
                "Unsupported audio output layout", null);
        }
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0D || end < start)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Audio render range is invalid", null);
        }
        if (end == start)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout, 0,
                "Audio render range is empty", null);
        }
        if (cancelled.getAsBoolean())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, 0,
                "Audio render cancelled", null);
        }

        long frames;
        try
        {
            frames = frameCount(start, end, sampleRate);
        }
        catch (ArithmeticException | NumberFormatException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Audio frame count overflows", e);
        }

        if (frames <= 0L)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout, 0,
                "Audio render range contains no output frames", null);
        }

        List<FilmSource> sources = new ArrayList<>();

        try
        {
            for (PreparedFilmSource source : preparedSources)
            {
                if (cancelled.getAsBoolean())
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout,
                        frames, "Audio render cancelled", null);
                }
                if (source == null)
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout,
                        frames, "Prepared audio source is null", null);
                }
                if (!Double.isFinite(source.clipStartSeconds())
                    || !Double.isFinite(source.sourceOffsetSeconds())
                    || !Double.isFinite(source.clipDurationSeconds())
                    || source.clipDurationSeconds() < 0D || !Float.isFinite(source.gain()))
                {
                    throw new IllegalArgumentException("Prepared audio timing and gain must be finite");
                }
                if (source.clipDurationSeconds() == 0D)
                {
                    continue;
                }
                if (source.wave() == null)
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.MISSING_RESOURCE, file,
                        layout, frames, "Prepared audio source is missing", null);
                }
                double clipEnd = source.clipStartSeconds() + source.clipDurationSeconds();
                if (clipEnd <= start || source.clipStartSeconds() >= end)
                {
                    continue;
                }
                if (source.wave().numChannels != 1 && source.wave().numChannels != 2)
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file,
                        layout, frames, "Prepared audio source channel layout is unsupported", null);
                }

                PcmFormat format;

                try
                {
                    format = source.wave().getFormat();
                }
                catch (IllegalArgumentException e)
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file,
                        layout, frames, e.getMessage(), e);
                }
                long sourceFrames = source.wave().data.length / format.bytesPerFrame();
                if (sourceFrames == 0L)
                {
                    continue;
                }

                double sourceDuration = sourceFrames / (double) format.sampleRate();
                double audibleStart = Math.max(start, Math.max(source.clipStartSeconds(),
                    source.clipStartSeconds() - source.sourceOffsetSeconds()));
                double audibleEnd = Math.min(end, Math.min(clipEnd,
                    source.clipStartSeconds() - source.sourceOffsetSeconds() + sourceDuration));

                if (audibleEnd <= audibleStart)
                {
                    continue;
                }

                sources.add(new FilmSource(source.wave(), format, sourceFrames,
                    source.clipStartSeconds(), source.sourceOffsetSeconds(),
                    source.clipDurationSeconds(), source.gain()));
            }
        }
        catch (RuntimeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout,
                frames, e.getMessage(), e);
        }

        if (sources.isEmpty())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout, frames,
                "No audio sources intersect the requested window", null);
        }

        if (cancelled.getAsBoolean())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, frames,
                "Audio render cancelled", null);
        }

        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.IO_FAILURE, file, layout, 0,
                "Audio output has no parent directory", null);
        }

        Path temporary = null;
        try
        {
            Files.createDirectories(parent);
            if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
            temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".wav");
            PcmFormat outputFormat = new PcmFormat(PcmEncoding.PCM_S16_LE, layout, sampleRate);
            long bytesPerFrame = outputFormat.bytesPerFrame();
            long dataLength = Math.multiplyExact(frames, bytesPerFrame);
            boolean mixed;

            try (BufferedOutputStream stream = new BufferedOutputStream(Files.newOutputStream(temporary)))
            {
                WaveWriter.writeHeader(stream, outputFormat, dataLength);
                mixed = PcmBlockRenderer.render(stream, frames, layout, cancelled, progress,
                    (blockStart, count, left, right) -> mixFilmBlock(sources, start,
                        sampleRate, layout, blockStart, count, left, right));
            }

            if (!mixed)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout, frames,
                    "No audio samples intersect the requested window", null);
            }

            if (cancelled.getAsBoolean())
            {
                throw new PcmBlockRenderer.CancelledException();
            }

            moveWithoutReplace(temporary, target);
            temporary = null;
            return AudioRenderResult.success(file, layout, frames);
        }
        catch (PcmBlockRenderer.CancelledException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, frames,
                "Audio render cancelled", e);
        }
        catch (IOException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.IO_FAILURE, file, layout, frames,
                e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, frames,
                e.getMessage(), e);
        }
        finally
        {
            if (temporary != null)
            {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private static boolean mixFilmBlock(List<FilmSource> sources, double start,
                                        int sampleRate, ChannelLayout layout,
                                        long blockStart, int count,
                                        float[] left, float[] right)
    {
        boolean mixed = false;

        for (int i = 0; i < count; i++)
        {
            double timeline = start + (blockStart + i) / (double) sampleRate;
            for (FilmSource source : sources)
            {
                double elapsed = timeline - source.clipStart;
                if (elapsed < 0D || elapsed >= source.clipDuration) continue;
                double relative = elapsed + source.sourceOffset;
                double sourcePosition = relative * source.wave.sampleRate;
                if (sourcePosition < 0D || sourcePosition >= source.sourceFrames) continue;

                mixed = true;
                float first = (float) sample(source, sourcePosition, 0);
                float second = source.wave.numChannels == 2
                    ? (float) sample(source, sourcePosition, 1) : 0F;

                PcmBlockRenderer.accumulate(left, right, i, layout,
                    source.wave.numChannels, first, second, source.gain);
            }
        }

        return mixed;
    }

    private static double sample(FilmSource source, double position, int channel)
    {
        long first = (long) Math.floor(position);
        if (first < 0 || first >= source.sourceFrames) return 0D;
        long second = Math.min(first + 1L, source.sourceFrames - 1L);
        double fraction = position - first;
        double a = readSample(source, first, channel);
        double b = readSample(source, second, channel);

        return a + (b - a) * fraction;
    }

    private static double readSample(FilmSource source, long frame, int channel)
    {
        long offset = Math.addExact(Math.multiplyExact(frame, source.format.bytesPerFrame()),
            Math.multiplyExact((long) channel, source.format.bytesPerSample()));

        return PcmSamples.readNormalized(source.format.encoding(), source.wave.data,
            Math.toIntExact(offset));
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException
    {
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Files.move(source, target);
    }

    private static ChannelLayout resolveExportLayout()
    {
        try
        {
            if (BBSSettings.videoAudioLayout != null)
            {
                return ChannelLayout.normalizeExport(BBSSettings.videoAudioLayout.get());
            }
        }
        catch (RuntimeException ignored) {}

        return ChannelLayout.MONO;
    }

    private static long frameCount(double start, double end, int sampleRate)
    {
        BigDecimal duration = decimalSeconds(end).subtract(decimalSeconds(start));
        BigDecimal samples = duration.multiply(BigDecimal.valueOf(sampleRate));

        return samples.setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private static BigDecimal decimalSeconds(double seconds)
    {
        float asFloat = (float) seconds;
        String value = Double.compare((double) asFloat, seconds) == 0
            ? Float.toString(asFloat)
            : Double.toString(seconds);

        return new BigDecimal(value);
    }

    private static double decimalSeconds(float seconds)
    {
        return Double.parseDouble(Float.toString(seconds));
    }

    record PreparedFilmSource(Wave wave, double clipStartSeconds, double sourceOffsetSeconds,
                              double clipDurationSeconds, float gain) {}

    private record FilmSource(Wave wave, PcmFormat format, long sourceFrames,
                              double clipStart, double sourceOffset,
                              double clipDuration, float gain) {}

    /** Shared bounded accumulation and single master-output boundary. */
    static final class PcmBlockRenderer
    {
        static final int BLOCK_FRAMES = 8192;
        private static final float CENTER_GAIN = (float) Math.sqrt(0.5D);

        private PcmBlockRenderer()
        {}

        static boolean render(OutputStream stream, long frames, ChannelLayout layout,
                              BooleanSupplier cancelled, BiConsumer<Long, Long> progress,
                              BlockAccumulator accumulator) throws IOException
        {
            int channels = layout.channels();
            float[] left = new float[BLOCK_FRAMES];
            float[] right = channels == 2 ? new float[BLOCK_FRAMES] : null;
            byte[] packed = new byte[BLOCK_FRAMES * channels * 2];
            boolean mixed = false;

            for (long blockStart = 0L; blockStart < frames; blockStart += BLOCK_FRAMES)
            {
                checkCancelled(cancelled);

                int count = (int) Math.min(BLOCK_FRAMES, frames - blockStart);
                Arrays.fill(left, 0, count, 0F);
                if (right != null) Arrays.fill(right, 0, count, 0F);

                mixed |= accumulator.accumulate(blockStart, count, left, right);
                checkCancelled(cancelled);
                pack(packed, left, right, count, channels);
                stream.write(packed, 0, count * channels * 2);
                progress.accept(Math.min(frames, blockStart + count), frames);
            }

            return mixed;
        }

        static void accumulate(float[] left, float[] right, int destination,
                               ChannelLayout layout, int sourceChannels,
                               float first, float second, float gain)
        {
            if (sourceChannels == 1)
            {
                float sample = first * gain;

                if (layout == ChannelLayout.MONO)
                {
                    left[destination] += sample;
                }
                else
                {
                    float centered = sample * CENTER_GAIN;
                    left[destination] += centered;
                    right[destination] += centered;
                }

                return;
            }

            if (sourceChannels != 2)
            {
                throw new IllegalArgumentException("Unsupported source channel count: " + sourceChannels);
            }

            float l = first * gain;
            float r = second * gain;

            if (layout == ChannelLayout.MONO)
            {
                left[destination] += (l + r) * 0.5F;
            }
            else
            {
                left[destination] += l;
                right[destination] += r;
            }
        }

        private static void pack(byte[] packed, float[] left, float[] right,
                                 int count, int channels)
        {
            for (int i = 0; i < count; i++)
            {
                int offset = i * channels * 2;
                PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, packed, offset, limit(left[i]));

                if (channels == 2)
                {
                    PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, packed,
                        offset + 2, limit(right[i]));
                }
            }
        }

        private static float limit(float value)
        {
            if (Float.isNaN(value))
            {
                throw new IllegalArgumentException("Mixed PCM sample is NaN");
            }

            return Math.max(-1F, Math.min(1F, value));
        }

        private static void checkCancelled(BooleanSupplier cancelled) throws CancelledException
        {
            if (cancelled.getAsBoolean())
            {
                throw new CancelledException();
            }
        }

        @FunctionalInterface
        interface BlockAccumulator
        {
            boolean accumulate(long blockStart, int count,
                               float[] left, float[] right) throws IOException;
        }

        static final class CancelledException extends IOException
        {
            private static final long serialVersionUID = 1L;
        }
    }

}
