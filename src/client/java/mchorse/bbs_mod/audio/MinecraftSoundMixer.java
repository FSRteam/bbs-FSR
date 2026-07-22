package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.CapturedSound;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.ListenerFrame;
import mchorse.bbs_mod.audio.MinecraftSoundCapture.LoopFrame;
import mchorse.bbs_mod.audio.ogg.VorbisReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Offline block mixer for captured Minecraft sounds and the film track. */
public final class MinecraftSoundMixer
{
    private static final float CENTER_GAIN = (float) Math.sqrt(0.5D);
    private static final long MAX_SAMPLES = 500_000_000L;

    private MinecraftSoundMixer() {}

    /**
     * Compatibility entry point that decodes Minecraft resources synchronously.
     * Callers with worker-owned rendering must prepare a {@link SoundResourceSnapshot}
     * on the client thread and use the typed snapshot overload instead.
     */
    public static boolean mixToFile(File file, List<CapturedSound> sounds, List<ListenerFrame> frames,
                                    Wave filmAudio, int sampleRate, double frameRate, int totalFrames)
    {
        return mixToFileResult(file, sounds, frames, filmAudio, sampleRate, frameRate,
            totalFrames, resolveExportLayout(), () -> false, (completed, total) -> {}).success();
    }

    /**
     * Typed compatibility entry point that may decode Minecraft resources on
     * the calling thread. Worker callers should use the resource-snapshot
     * overload below.
     */
    public static AudioRenderResult mixToFileResult(File file, List<CapturedSound> sounds,
                                                     List<ListenerFrame> frames, Wave filmAudio,
                                                     int sampleRate, double frameRate, int totalFrames,
                                                     ChannelLayout layout, BooleanSupplier cancelled,
                                                     BiConsumer<Long, Long> progress)
    {
        return snapshotAndMix(file, sounds, frames, filmAudio, null, false,
            sampleRate, frameRate, totalFrames, layout, cancelled, progress);
    }

    /**
     * Worker-safe entry point for resources decoded on the client thread.
     * A missing key is reported as {@link AudioRenderResult.Status#MISSING_RESOURCE}.
     */
    public static AudioRenderResult mixToFileResult(File file, List<CapturedSound> sounds,
                                                     List<ListenerFrame> frames, Wave filmAudio,
                                                     Map<ResourceLocation, Wave> resourceSnapshot,
                                                     int sampleRate, double frameRate, int totalFrames,
                                                     ChannelLayout layout, BooleanSupplier cancelled,
                                                     BiConsumer<Long, Long> progress)
    {
        return snapshotAndMix(file, sounds, frames, filmAudio, resourceSnapshot, true,
            sampleRate, frameRate, totalFrames, layout, cancelled, progress);
    }

    /**
     * File-backed worker entry point. The resource snapshot is prepared on the
     * client thread and owns its files until the export terminal closes it.
     */
    public static AudioRenderResult mixFileSourcesToFileResult(File file,
                                                                List<CapturedSound> sounds,
                                                                List<ListenerFrame> frames,
                                                                Path filmAudio,
                                                                SoundResourceFiles resourceSnapshot,
                                                                int sampleRate, double frameRate,
                                                                int totalFrames, ChannelLayout layout,
                                                                BooleanSupplier cancelled,
                                                                BiConsumer<Long, Long> progress)
    {
        if (resourceSnapshot == null)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Minecraft sound file snapshot is missing", null);
        }
        if (!resourceSnapshot.success())
        {
            return AudioRenderResult.failure(resourceSnapshot.status(), file, layout, 0,
                resourceSnapshot.message(), resourceSnapshot.cause());
        }

        try (OpenedFileSources opened = resourceSnapshot.open();
             SampleSource film = filmAudio == null ? null
                 : SampleSource.file(PcmFileSource.openWave(filmAudio)))
        {
            return mixPreparedSourcesToFileResult(file, sounds, frames, film,
                sampleRate, frameRate, totalFrames, layout, cancelled, progress,
                opened.sources());
        }
        catch (UnsupportedAudioFormatException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
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
    }

    private static AudioRenderResult snapshotAndMix(File file, List<CapturedSound> sounds,
                                                      List<ListenerFrame> frames, Wave filmAudio,
                                                      Map<ResourceLocation, Wave> resourceSnapshot,
                                                      boolean resourcesPrepared,
                                                      int sampleRate, double frameRate, int totalFrames,
                                                      ChannelLayout layout, BooleanSupplier cancelled,
                                                      BiConsumer<Long, Long> progress)
    {
        if (cancelled == null)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Minecraft audio cancellation token is missing", null);
        }

        try
        {
            checkSnapshotCancelled(cancelled);

            if (file == null || sounds == null || frames == null || totalFrames <= 0
                || sampleRate <= 0 || !Double.isFinite(frameRate) || frameRate <= 0D
                || progress == null)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                    "Invalid Minecraft audio mix request", null);
            }

            if (layout == null || !layout.supported())
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
                    "Unsupported Minecraft output layout", null);
            }

            if (resourcesPrepared && resourceSnapshot == null)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                    "Minecraft sound resource snapshot is missing", null);
            }

            InputSnapshot inputs = copyInputs(sounds, frames, filmAudio, cancelled);
            Map<ResourceLocation, Wave> resources = resourcesPrepared
                ? copyResourceSnapshot(resourceSnapshot, cancelled)
                : null;

            checkSnapshotCancelled(cancelled);

            SampleSource filmSource = inputs.filmAudio() == null
                ? null : SampleSource.wave(inputs.filmAudio());
            Map<ResourceLocation, SampleSource> prepared = resources == null
                ? null : memorySources(resources);

            return mixPreparedSourcesToFileResult(file, inputs.sounds(), inputs.frames(), filmSource,
                sampleRate, frameRate, totalFrames, layout, cancelled, progress, prepared);
        }
        catch (SnapshotCancelledException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, 0,
                "Minecraft audio snapshot cancelled", e);
        }
        catch (UnsupportedSnapshotException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
                e.getMessage(), e);
        }
        catch (MalformedSnapshotException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Minecraft audio input snapshot is invalid", e);
        }
    }

    /** Decode each distinct captured resource while still on the client thread. */
    public static SoundResourceSnapshot snapshotResources(List<CapturedSound> sounds,
                                                           BooleanSupplier cancelled)
    {
        if (sounds == null || cancelled == null)
        {
            return SoundResourceSnapshot.failure(AudioRenderResult.Status.MIX_FAILURE,
                "Invalid Minecraft sound snapshot request", null);
        }

        Map<ResourceLocation, Wave> resources = new HashMap<>();

        try
        {
            if (cancelled.getAsBoolean())
            {
                return SoundResourceSnapshot.failure(AudioRenderResult.Status.CANCELLED,
                    "Minecraft sound snapshot cancelled", null);
            }

            for (CapturedSound sound : sounds)
            {
                if (cancelled.getAsBoolean())
                {
                    return SoundResourceSnapshot.failure(AudioRenderResult.Status.CANCELLED,
                        "Minecraft sound snapshot cancelled", null);
                }

                Wave wave = read(resources, sound.location);

                if (cancelled.getAsBoolean())
                {
                    return SoundResourceSnapshot.failure(AudioRenderResult.Status.CANCELLED,
                        "Minecraft sound snapshot cancelled", null);
                }
                if (wave == null)
                {
                    return SoundResourceSnapshot.failure(AudioRenderResult.Status.MISSING_RESOURCE,
                        "Missing Minecraft sound " + sound.location, null);
                }
                WaveSupport support = support(wave);
                if (support == WaveSupport.UNSUPPORTED)
                {
                    return SoundResourceSnapshot.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                        "Unsupported Minecraft sound " + sound.location, null);
                }
                if (support == WaveSupport.MALFORMED)
                {
                    return SoundResourceSnapshot.failure(AudioRenderResult.Status.MIX_FAILURE,
                        "Malformed Minecraft sound " + sound.location, null);
                }

                if (cancelled.getAsBoolean())
                {
                    return SoundResourceSnapshot.failure(AudioRenderResult.Status.CANCELLED,
                        "Minecraft sound snapshot cancelled", null);
                }
            }

            if (cancelled.getAsBoolean())
            {
                return SoundResourceSnapshot.failure(AudioRenderResult.Status.CANCELLED,
                    "Minecraft sound snapshot cancelled", null);
            }

            return SoundResourceSnapshot.success(resources);
        }
        catch (UnsupportedAudioFormatException e)
        {
            return SoundResourceSnapshot.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                e.getMessage(), e);
        }
        catch (AudioDecodeException e)
        {
            return SoundResourceSnapshot.failure(AudioRenderResult.Status.MIX_FAILURE,
                e.getMessage(), e);
        }
        catch (IOException e)
        {
            return SoundResourceSnapshot.failure(AudioRenderResult.Status.IO_FAILURE,
                e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            return SoundResourceSnapshot.failure(AudioRenderResult.Status.MIX_FAILURE,
                e.getMessage(), e);
        }
    }

    /**
     * Decode distinct Minecraft resources on the client thread, then transfer
     * ownership to bounded file-backed PCM descriptors for the worker.
     */
    public static SoundResourceFiles snapshotResourceFiles(List<CapturedSound> sounds,
                                                            BooleanSupplier cancelled)
    {
        if (sounds == null || cancelled == null)
        {
            return SoundResourceFiles.failure(AudioRenderResult.Status.MIX_FAILURE,
                "Invalid Minecraft sound file snapshot request", null);
        }

        Path directory = null;
        Map<ResourceLocation, PcmFileSource.Descriptor> resources = new HashMap<>();

        try
        {
            checkSnapshotCancelled(cancelled);
            directory = Files.createTempDirectory("bbs-minecraft-audio-");

            for (CapturedSound sound : sounds)
            {
                checkSnapshotCancelled(cancelled);
                CapturedSound captured = Objects.requireNonNull(sound, "captured sound");
                ResourceLocation location = Objects.requireNonNull(captured.location, "resource location");
                if (resources.containsKey(location)) continue;

                Wave wave = readResource(location);
                checkSnapshotCancelled(cancelled);
                if (wave == null)
                {
                    return failedFileSnapshot(directory, resources,
                        AudioRenderResult.Status.MISSING_RESOURCE,
                        "Missing Minecraft sound " + location, null);
                }

                WaveSupport state = support(wave);
                if (state == WaveSupport.UNSUPPORTED)
                {
                    return failedFileSnapshot(directory, resources,
                        AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                        "Unsupported Minecraft sound " + location, null);
                }
                if (state == WaveSupport.MALFORMED)
                {
                    return failedFileSnapshot(directory, resources,
                        AudioRenderResult.Status.MIX_FAILURE,
                        "Malformed Minecraft sound " + location, null);
                }

                PcmFormat format = wave.getFormat();
                Path pcm = Files.createTempFile(directory, "source-", ".pcm");
                boolean complete = false;

                try
                {
                    try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(pcm)))
                    {
                        int blockBytes = Math.multiplyExact(AudioRenderer.PcmBlockRenderer.BLOCK_FRAMES,
                            format.bytesPerFrame());

                        for (int offset = 0; offset < wave.data.length; offset += blockBytes)
                        {
                            checkSnapshotCancelled(cancelled);
                            int count = Math.min(blockBytes, wave.data.length - offset);
                            stream.write(wave.data, offset, count);
                        }
                    }
                    complete = true;
                }
                finally
                {
                    if (!complete) Files.deleteIfExists(pcm);
                }

                resources.put(location,
                    PcmFileSource.describeRaw(pcm, format, wave.data.length));
                checkSnapshotCancelled(cancelled);
            }

            return SoundResourceFiles.success(directory, resources);
        }
        catch (SnapshotCancelledException e)
        {
            return failedFileSnapshot(directory, resources, AudioRenderResult.Status.CANCELLED,
                "Minecraft sound file snapshot cancelled", e);
        }
        catch (UnsupportedAudioFormatException e)
        {
            return failedFileSnapshot(directory, resources, AudioRenderResult.Status.UNSUPPORTED_FORMAT,
                e.getMessage(), e);
        }
        catch (AudioDecodeException e)
        {
            return failedFileSnapshot(directory, resources, AudioRenderResult.Status.MIX_FAILURE,
                e.getMessage(), e);
        }
        catch (IOException e)
        {
            return failedFileSnapshot(directory, resources, AudioRenderResult.Status.IO_FAILURE,
                e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            return failedFileSnapshot(directory, resources, AudioRenderResult.Status.MIX_FAILURE,
                e.getMessage(), e);
        }
    }

    private static SoundResourceFiles failedFileSnapshot(
        Path directory, Map<ResourceLocation, PcmFileSource.Descriptor> resources,
        AudioRenderResult.Status status, String message, Throwable cause)
    {
        Throwable failure = cause;

        try
        {
            SoundResourceFiles.delete(directory, resources);
        }
        catch (Throwable cleanupFailure)
        {
            failure = appendFailure(failure, cleanupFailure);
        }

        return SoundResourceFiles.failure(status, message, failure);
    }

    private static AudioRenderResult mixPreparedSourcesToFileResult(File file,
                                                      List<CapturedSound> sounds,
                                                      List<ListenerFrame> frames, SampleSource filmAudio,
                                                      int sampleRate, double frameRate, int totalFrames,
                                                      ChannelLayout layout, BooleanSupplier cancelled,
                                                      BiConsumer<Long, Long> progress,
                                                      Map<ResourceLocation, SampleSource> resourceSnapshot)
    {
        if (file == null || sounds == null || frames == null || totalFrames <= 0 || sampleRate <= 0
            || !Double.isFinite(frameRate) || frameRate <= 0D || cancelled == null || progress == null)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Invalid Minecraft audio mix request", null);
        }

        if (layout == null || !layout.supported())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout, 0,
                "Unsupported Minecraft output layout", null);
        }

        if (cancelled.getAsBoolean())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout, 0,
                "Minecraft audio mix cancelled", null);
        }

        long totalSamples;

        try
        {
            totalSamples = outputFrameBoundary(totalFrames, frameRate, sampleRate);
        }
        catch (ArithmeticException | NumberFormatException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Minecraft audio frame count overflows", e);
        }

        if (totalSamples <= 0 || totalSamples > MAX_SAMPLES)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout, 0,
                "Minecraft audio frame count is out of bounds", null);
        }
        long filmFrames = 0L;

        if (filmAudio != null)
        {
            try
            {
                filmFrames = filmAudio.frames();
            }
            catch (RuntimeException e)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout,
                    totalSamples, "Film audio data is malformed", e);
            }
        }
        if (filmFrames == 0L && sounds.isEmpty())
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout,
                totalSamples, "No Minecraft or film audio sources", null);
        }

        Map<ResourceLocation, SampleSource> cache = resourceSnapshot == null
            ? new HashMap<>() : resourceSnapshot;
        Map<ResourceLocation, Wave> decodedCache = resourceSnapshot == null
            ? new HashMap<>() : Map.of();
        List<Cursor> cursors = new ArrayList<>();

        try
        {
            for (CapturedSound sound : sounds)
            {
                if (cancelled.getAsBoolean())
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout,
                        totalSamples, "Minecraft audio mix cancelled", null);
                }

                double timelineStart = outputFramePosition(sound.frame, frameRate, sampleRate);
                long firstOutput = Math.max(0L,
                    outputFrameBoundary(sound.frame, frameRate, sampleRate));
                long end = sound.endFrame < 0 ? totalSamples
                    : outputFrameBoundary(sound.endFrame, frameRate, sampleRate);

                /* Events outside the half-open output window cannot
                 * contribute and must not make the render depend on an
                 * otherwise irrelevant resource. */
                if (end <= firstOutput || firstOutput >= totalSamples)
                {
                    continue;
                }

                SampleSource source = cache.get(sound.location);
                if (source == null && resourceSnapshot == null)
                {
                    Wave wave = read(decodedCache, sound.location);
                    source = wave == null ? null : SampleSource.wave(wave);
                    if (source != null) cache.put(sound.location, source);
                }
                if (cancelled.getAsBoolean())
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout,
                        totalSamples, "Minecraft audio mix cancelled", null);
                }
                if (source == null)
                {
                    return AudioRenderResult.failure(AudioRenderResult.Status.MISSING_RESOURCE, file, layout,
                        totalSamples, "Missing Minecraft sound " + sound.location, null);
                }
                if (source.frames() == 0) continue;

                Cursor cursor = new Cursor(sound, source, timelineStart, firstOutput,
                    Math.min(totalSamples, end), sampleRate);

                if (!cursor.exhausted)
                {
                    cursors.add(cursor);
                }
            }

            cursors.sort(Comparator.comparingDouble((Cursor cursor) -> cursor.timelineStart)
                .thenComparing(cursor -> cursor.sound.location.toString()));

            if (filmFrames == 0L && cursors.isEmpty())
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout,
                    totalSamples, "No Minecraft or film audio in the requested window", null);
            }
        }
        catch (UnsupportedAudioFormatException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.UNSUPPORTED_FORMAT, file, layout,
                totalSamples, e.getMessage(), e);
        }
        catch (AudioDecodeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout,
                totalSamples, e.getMessage(), e);
        }
        catch (IOException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.IO_FAILURE, file, layout,
                totalSamples, e.getMessage(), e);
        }
        catch (Exception e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout,
                totalSamples, e.getMessage(), e);
        }

        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        Path temporary = null;
        final long renderedFilmFrames = filmFrames;

        try
        {
            if (parent == null) throw new IOException("Minecraft audio output has no parent directory");
            Files.createDirectories(parent);
            if (Files.exists(target)) throw new IOException("Minecraft audio output already exists: " + target);
            temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".wav");
            PcmFormat outputFormat = new PcmFormat(PcmEncoding.PCM_S16_LE, layout, sampleRate);
            long dataLength = Math.multiplyExact(totalSamples, outputFormat.bytesPerFrame());
            boolean mixed;

            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(temporary)))
            {
                WaveWriter.writeHeader(stream, outputFormat, dataLength);
                mixed = AudioRenderer.PcmBlockRenderer.render(stream, totalSamples, layout, cancelled, progress,
                    (blockStart, count, left, right) -> mixBlock(filmAudio,
                        renderedFilmFrames, cursors, blockStart, count,
                        sampleRate, frameRate, frames, layout, cancelled, left, right));
            }

            if (!mixed)
            {
                return AudioRenderResult.failure(AudioRenderResult.Status.EMPTY, file, layout,
                    totalSamples, "No Minecraft or film audio samples", null);
            }

            if (cancelled.getAsBoolean()) throw new AudioRenderer.PcmBlockRenderer.CancelledException();
            moveWithoutReplace(temporary, target);
            temporary = null;
            return AudioRenderResult.success(file, layout, totalSamples);
        }
        catch (AudioRenderer.PcmBlockRenderer.CancelledException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.CANCELLED, file, layout,
                totalSamples, "Minecraft audio mix cancelled", e);
        }
        catch (IOException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.IO_FAILURE, file, layout,
                totalSamples, e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            return AudioRenderResult.failure(AudioRenderResult.Status.MIX_FAILURE, file, layout,
                totalSamples, e.getMessage(), e);
        }
        finally
        {
            if (temporary != null)
            {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private static WaveSupport support(Wave wave)
    {
        if (wave == null || (wave.numChannels != 1 && wave.numChannels != 2))
        {
            return WaveSupport.UNSUPPORTED;
        }

        try
        {
            wave.getFormat();
            return WaveSupport.SUPPORTED;
        }
        catch (IllegalArgumentException e)
        {
            return WaveSupport.UNSUPPORTED;
        }
        catch (RuntimeException e)
        {
            return WaveSupport.MALFORMED;
        }
    }

    private static boolean mixBlock(SampleSource filmAudio, long filmFrames,
                                     List<Cursor> cursors, long blockStart, int count,
                                    int outputRate, double frameRate, List<ListenerFrame> listeners,
                                    ChannelLayout layout, BooleanSupplier cancelled,
                                    float[] left, float[] right) throws IOException
    {
        boolean mixed = false;

        if (filmFrames > 0L)
        {
            mixed |= mixFilm(filmAudio, filmFrames, blockStart, count,
                outputRate, layout, left, right);
        }

        for (Cursor cursor : cursors)
        {
            if (cancelled.getAsBoolean())
            {
                throw new AudioRenderer.PcmBlockRenderer.CancelledException();
            }

            mixed |= cursor.mix(blockStart, count, outputRate, frameRate,
                listeners, layout, left, right);
        }

        return mixed;
    }

    private static boolean mixFilm(SampleSource source, long frames,
                                   long outputStart, int count, int outputRate,
                                   ChannelLayout layout, float[] left, float[] right)
        throws IOException
    {
        PcmFormat format = source.format();
        double position = outputStart * format.sampleRate() / (double) outputRate;
        double step = format.sampleRate() / (double) outputRate;
        boolean mixed = false;

        for (int i = 0; i < count && position < frames; i++, position += step)
        {
            mixed = true;
            if (format.channels() == 1)
            {
                float sample = sample(source, frames, position, 0, false);
                AudioRenderer.PcmBlockRenderer.accumulate(left, right, i, layout, 1, sample, 0F, 1F);
            }
            else
            {
                float l = sample(source, frames, position, 0, false);
                float r = sample(source, frames, position, 1, false);
                AudioRenderer.PcmBlockRenderer.accumulate(left, right, i, layout, 2, l, r, 1F);
            }
        }

        return mixed;
    }

    private static float sample(SampleSource source, long frames,
                                double position, int channel, boolean loop)
        throws IOException
    {
        long first = (long) Math.floor(position);
        if (first < 0 || first >= frames) return 0F;
        long second = loop ? (first + 1) % frames : Math.min(first + 1, frames - 1);
        double fraction = position - first;
        double a = source.readNormalized(first, channel);
        double b = source.readNormalized(second, channel);
        return (float) (a + (b - a) * fraction);
    }

    private static float monoGain(CapturedSound sound, LoopFrame state, ListenerFrame listener, float volume)
    {
        if (sound.relative || listener == null) return volume;
        double x = state == null ? sound.x : state.x, y = state == null ? sound.y : state.y, z = state == null ? sound.z : state.z;
        double dx = x - listener.x, dy = y - listener.y, dz = z - listener.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (sound.attenuate && sound.range > 0F)
        {
            return volume * MathUtils.clamp(1F - (float) (distance / sound.range), 0F, 1F);
        }

        return volume;
    }

    private static ChannelLayout resolveExportLayout()
    {
        try
        {
            if (BBSSettings.videoAudioLayout != null)
            {
                /* Persisted values are a compatibility boundary: malformed,
                 * legacy, and reserved 5.1 values all migrate to mono.  A
                 * caller that explicitly supplies SURROUND_5_1 still reaches
                 * the typed validation above and is rejected. */
                return ChannelLayout.normalizeExport(BBSSettings.videoAudioLayout.get());
            }
        }
        catch (RuntimeException ignored) {}

        return ChannelLayout.MONO;
    }

    private static long outputFrameBoundary(long timelineFrame, double frameRate, int sampleRate)
    {
        BigDecimal numerator = BigDecimal.valueOf(timelineFrame)
            .multiply(BigDecimal.valueOf(sampleRate));
        BigDecimal denominator = BigDecimal.valueOf(frameRate);

        return numerator.divide(denominator, 0, RoundingMode.CEILING).longValueExact();
    }

    private static double outputFramePosition(long timelineFrame, double frameRate, int sampleRate)
    {
        BigDecimal numerator = BigDecimal.valueOf(timelineFrame)
            .multiply(BigDecimal.valueOf(sampleRate));
        BigDecimal denominator = BigDecimal.valueOf(frameRate);

        return numerator.divide(denominator, MathContext.DECIMAL64).doubleValue();
    }

    private static InputSnapshot copyInputs(List<CapturedSound> sounds,
                                            List<ListenerFrame> frames,
                                            Wave filmAudio,
                                            BooleanSupplier cancelled)
    {
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(frames, "listener frames");

        List<CapturedSound> soundCopy = new ArrayList<>(sounds.size());

        for (CapturedSound sound : sounds)
        {
            checkSnapshotCancelled(cancelled);
            Objects.requireNonNull(sound, "captured sound");

            CapturedSound copy = new CapturedSound(sound.location, sound.frame,
                sound.x, sound.y, sound.z, sound.relative, sound.attenuate,
                sound.volume, sound.pitch, sound.range, sound.loop);
            copy.endFrame = sound.endFrame;

            if (sound.track != null)
            {
                for (LoopFrame state : sound.track)
                {
                    checkSnapshotCancelled(cancelled);
                    Objects.requireNonNull(state, "captured sound loop frame");
                    copy.track.add(new LoopFrame(state.volume, state.pitch,
                        state.x, state.y, state.z));
                }
            }

            soundCopy.add(copy);
        }

        List<ListenerFrame> frameCopy = new ArrayList<>(frames.size());

        for (ListenerFrame frame : frames)
        {
            checkSnapshotCancelled(cancelled);
            Objects.requireNonNull(frame, "listener frame");
            frameCopy.add(new ListenerFrame(frame.x, frame.y, frame.z,
                frame.rightX, frame.rightY, frame.rightZ));
        }

        Wave filmCopy = copyWave(filmAudio, "Film audio");
        checkSnapshotCancelled(cancelled);

        return new InputSnapshot(List.copyOf(soundCopy), List.copyOf(frameCopy), filmCopy);
    }

    private static Wave copyWave(Wave wave, String label)
    {
        if (wave == null)
        {
            return null;
        }

        WaveSupport state = support(wave);

        if (state == WaveSupport.UNSUPPORTED)
        {
            throw new UnsupportedSnapshotException(label + " format is unsupported");
        }
        if (state == WaveSupport.MALFORMED)
        {
            throw new MalformedSnapshotException(label + " data is malformed");
        }

        return new Wave(wave.getFormat(), wave.data.clone());
    }

    private static Map<ResourceLocation, Wave> copyResourceSnapshot(Map<ResourceLocation, Wave> source)
    {
        return copyResourceSnapshot(source, () -> false);
    }

    private static Map<ResourceLocation, Wave> copyResourceSnapshot(Map<ResourceLocation, Wave> source,
                                                                    BooleanSupplier cancelled)
    {
        Objects.requireNonNull(source, "resource snapshot");
        Objects.requireNonNull(cancelled, "cancellation token");
        Map<ResourceLocation, Wave> copy = new HashMap<>();

        for (Map.Entry<ResourceLocation, Wave> entry : source.entrySet())
        {
            checkSnapshotCancelled(cancelled);
            ResourceLocation location = Objects.requireNonNull(entry.getKey(), "resource location");
            Wave wave = entry.getValue();

            if (wave == null)
            {
                throw new MalformedSnapshotException("Null Minecraft sound resource " + location);
            }

            WaveSupport state = support(wave);
            if (state == WaveSupport.UNSUPPORTED)
            {
                throw new UnsupportedSnapshotException("Unsupported Minecraft sound " + location);
            }
            if (state == WaveSupport.MALFORMED)
            {
                throw new MalformedSnapshotException("Malformed Minecraft sound " + location);
            }

            PcmFormat format = wave.getFormat();
            copy.put(location, new Wave(format, wave.data.clone()));
            checkSnapshotCancelled(cancelled);
        }

        return Map.copyOf(copy);
    }

    private static void checkSnapshotCancelled(BooleanSupplier cancelled)
    {
        if (cancelled.getAsBoolean())
        {
            throw new SnapshotCancelledException();
        }
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException
    {
        /* Do not use ATOMIC_MOVE here: the NIO contract permits an
         * implementation to replace an existing target when that option is
         * supplied.  A plain move has the required no-replace semantics. */
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Files.move(source, target);
    }

    private static Wave read(Map<ResourceLocation, Wave> cache, ResourceLocation location) throws IOException
    {
        if (cache.containsKey(location)) return cache.get(location);
        Optional<Resource> resource;

        try
        {
            resource = Minecraft.getInstance().getResourceManager().getResource(location);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Could not access Minecraft sound resource " + location, e);
        }

        if (resource.isEmpty())
        {
            cache.put(location, null);
            return null;
        }

        Wave wave;
        try (InputStream stream = resource.get().open())
        {
            wave = VorbisReader.read(new Link(location.getNamespace(), location.getPath()), stream);
        }
        cache.put(location, wave);
        return wave;
    }

    private static Wave readResource(ResourceLocation location) throws IOException
    {
        Optional<Resource> resource;

        try
        {
            resource = Minecraft.getInstance().getResourceManager().getResource(location);
        }
        catch (RuntimeException e)
        {
            throw new IOException("Could not access Minecraft sound resource " + location, e);
        }

        if (resource.isEmpty()) return null;

        try (InputStream stream = resource.get().open())
        {
            return VorbisReader.read(new Link(location.getNamespace(), location.getPath()), stream);
        }
    }

    private static Map<ResourceLocation, SampleSource> memorySources(Map<ResourceLocation, Wave> source)
    {
        Map<ResourceLocation, SampleSource> result = new HashMap<>();

        for (Map.Entry<ResourceLocation, Wave> entry : source.entrySet())
        {
            ResourceLocation location = Objects.requireNonNull(entry.getKey(), "resource location");
            Wave wave = entry.getValue();
            if (wave == null) throw new IllegalStateException("Null Minecraft sound resource " + location);
            result.put(location, SampleSource.wave(wave));
        }

        return Map.copyOf(result);
    }

    private static Throwable appendFailure(Throwable current, Throwable next)
    {
        if (next == null) return current;
        if (current == null) return next;
        if (current != next) current.addSuppressed(next);
        return current;
    }

    private static final class SampleSource implements AutoCloseable
    {
        private final Wave wave;
        private final PcmFileSource file;
        private final PcmFormat format;
        private final long frames;

        private SampleSource(Wave wave, PcmFileSource file, PcmFormat format, long frames)
        {
            this.wave = wave;
            this.file = file;
            this.format = format;
            this.frames = frames;
        }

        private static SampleSource wave(Wave wave)
        {
            WaveSupport state = support(wave);
            if (state == WaveSupport.UNSUPPORTED)
            {
                throw new UnsupportedSnapshotException("Unsupported PCM source");
            }
            if (state == WaveSupport.MALFORMED)
            {
                throw new MalformedSnapshotException("Malformed PCM source");
            }

            PcmFormat format = wave.getFormat();
            return new SampleSource(wave, null, format,
                wave.data.length / format.bytesPerFrame());
        }

        private static SampleSource file(PcmFileSource file)
        {
            Objects.requireNonNull(file, "PCM file source");
            return new SampleSource(null, file, file.format(), file.frames());
        }

        private PcmFormat format()
        {
            return this.format;
        }

        private long frames()
        {
            return this.frames;
        }

        private double readNormalized(long frame, int channel) throws IOException
        {
            if (this.file != null)
            {
                return this.file.readNormalized(frame, channel);
            }

            long offset = Math.addExact(Math.multiplyExact(frame, this.format.bytesPerFrame()),
                Math.multiplyExact((long) channel, this.format.bytesPerSample()));
            return PcmSamples.readNormalized(this.format.encoding(), this.wave.data,
                Math.toIntExact(offset));
        }

        @Override
        public void close() throws IOException
        {
            if (this.file != null) this.file.close();
        }
    }

    private static final class OpenedFileSources implements AutoCloseable
    {
        private final Map<ResourceLocation, SampleSource> sources;

        private OpenedFileSources(Map<ResourceLocation, SampleSource> sources)
        {
            this.sources = sources;
        }

        private Map<ResourceLocation, SampleSource> sources()
        {
            return this.sources;
        }

        @Override
        public void close() throws IOException
        {
            Throwable failure = null;
            for (SampleSource source : this.sources.values())
            {
                try { source.close(); }
                catch (Throwable closeFailure) { failure = appendFailure(failure, closeFailure); }
            }

            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IOException("Failed to close PCM file sources", failure);
        }
    }

    /** Client-thread snapshot whose raw PCM files are owned until close(). */
    public static final class SoundResourceFiles implements AutoCloseable
    {
        private final Path directory;
        private final Map<ResourceLocation, PcmFileSource.Descriptor> resources;
        private final AudioRenderResult.Status status;
        private final String message;
        private final Throwable cause;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SoundResourceFiles(Path directory,
                                   Map<ResourceLocation, PcmFileSource.Descriptor> resources,
                                   AudioRenderResult.Status status, String message, Throwable cause)
        {
            this.directory = directory;
            this.resources = resources == null ? Map.of() : Map.copyOf(resources);
            this.status = status == null ? AudioRenderResult.Status.MIX_FAILURE : status;
            this.message = message;
            this.cause = cause;
        }

        private static SoundResourceFiles success(Path directory,
                                                   Map<ResourceLocation, PcmFileSource.Descriptor> resources)
        {
            return new SoundResourceFiles(directory, resources,
                AudioRenderResult.Status.SUCCESS, null, null);
        }

        /** Empty successful handoff for exports whose fake mixer supplies all data. */
        public static SoundResourceFiles empty()
        {
            return success(null, Map.of());
        }

        public static SoundResourceFiles failure(AudioRenderResult.Status status,
                                                 String message, Throwable cause)
        {
            return new SoundResourceFiles(null, Map.of(), status, message, cause);
        }

        public AudioRenderResult.Status status() { return this.status; }
        public String message() { return this.message; }
        public Throwable cause() { return this.cause; }
        public boolean success() { return this.status == AudioRenderResult.Status.SUCCESS; }

        private OpenedFileSources open() throws IOException
        {
            if (!this.success()) throw new IOException(this.message == null
                ? "Minecraft sound file snapshot failed" : this.message);

            Map<ResourceLocation, SampleSource> opened = new HashMap<>();
            try
            {
                for (Map.Entry<ResourceLocation, PcmFileSource.Descriptor> entry : this.resources.entrySet())
                {
                    opened.put(entry.getKey(), SampleSource.file(PcmFileSource.open(entry.getValue())));
                }
                return new OpenedFileSources(Map.copyOf(opened));
            }
            catch (Throwable failure)
            {
                try { new OpenedFileSources(opened).close(); }
                catch (Throwable closeFailure) { if (closeFailure != failure) failure.addSuppressed(closeFailure); }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IOException("Failed to open PCM file sources", failure);
            }
        }

        @Override
        public void close() throws IOException
        {
            if (!this.closed.compareAndSet(false, true)) return;
            delete(this.directory, this.resources);
        }

        private static void delete(Path directory,
                                   Map<ResourceLocation, PcmFileSource.Descriptor> resources) throws IOException
        {
            Throwable failure = null;
            if (resources != null)
            {
                for (PcmFileSource.Descriptor descriptor : resources.values())
                {
                    try { Files.deleteIfExists(descriptor.path()); }
                    catch (Throwable deleteFailure) { failure = appendFailure(failure, deleteFailure); }
                }
            }
            if (directory != null)
            {
                try { Files.deleteIfExists(directory); }
                catch (Throwable deleteFailure) { failure = appendFailure(failure, deleteFailure); }
            }

            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IOException("Failed to delete PCM file snapshot", failure);
        }
    }

    private static final class Cursor
    {
        private final CapturedSound sound;
        private final SampleSource source;
        private final PcmFormat format;
        private final long sourceFrames;
        private final double timelineStart;
        private final long start;
        private final long end;
        private long nextOutput;
        private double position;
        private boolean exhausted;
        private int gainFrame = Integer.MIN_VALUE;
        private ChannelLayout gainLayout;
        private float leftGain;
        private float rightGain;

        private Cursor(CapturedSound sound, SampleSource source, double timelineStart, long start,
                       long end, int outputRate)
        {
            this.sound = sound;
            this.source = source;
            this.format = source.format();
            this.sourceFrames = source.frames();
            this.timelineStart = timelineStart;
            this.start = start;
            this.end = end;
            this.nextOutput = start;
            LoopFrame initialState = sound.getTrackFrame(sound.frame);
            float initialPitch = initialState == null ? sound.pitch : initialState.pitch;

            this.position = Math.max(0D, start - timelineStart) * initialPitch
                * this.format.sampleRate() / outputRate;

            if (!sound.loop && this.position >= this.sourceFrames)
            {
                this.exhausted = true;
                this.nextOutput = end;
            }
        }

        private boolean mix(long blockStart, int count, int outputRate, double frameRate,
                            List<ListenerFrame> listeners, ChannelLayout layout,
                            float[] left, float[] right) throws IOException
        {
            if (this.exhausted) return false;

            long from = Math.max(blockStart, this.start);
            long to = Math.min(blockStart + count, this.end);
            if (from >= to) return false;
            boolean mixed = false;

            /* Cursor state advances from its last consumed output frame; the
             * fractional source phase survives block boundaries and pitch 0. */
            if (from > this.nextOutput)
            {
                this.advance(this.nextOutput, from, outputRate, frameRate);

                if (this.exhausted)
                {
                    return false;
                }

                this.nextOutput = from;
            }

            for (long output = from; output < to; output++)
            {
                int timelineFrame = (int) Math.floor(output * frameRate / outputRate);
                LoopFrame state = this.sound.getTrackFrame(timelineFrame);
                float volume = state == null ? this.sound.volume : state.volume;
                float pitch = state == null ? this.sound.pitch : state.pitch;
                int destination = (int) (output - blockStart);
                if (!this.sound.loop && this.position >= this.sourceFrames)
                {
                    this.exhausted = true;
                    this.nextOutput = this.end;
                    break;
                }
                double sourcePosition = this.sound.loop ? this.position % this.sourceFrames : this.position;
                if (sourcePosition < 0D) sourcePosition += this.sourceFrames;

                if (this.format.channels() == 1)
                {
                    ListenerFrame listener = listeners.isEmpty() ? null
                        : listeners.get(MathUtils.clamp(timelineFrame, 0, listeners.size() - 1));
                    float value = sample(this.source, this.sourceFrames,
                        sourcePosition, 0, this.sound.loop);

                    if (this.gainFrame != timelineFrame || this.gainLayout != layout)
                    {
                        this.updateGains(timelineFrame, state, listener, volume, layout);
                    }

                    if (layout == ChannelLayout.MONO)
                    {
                        left[destination] += value * this.leftGain;
                    }
                    else
                    {
                        left[destination] += value * this.leftGain;
                        right[destination] += value * this.rightGain;
                    }
                }
                else
                {
                    float l = sample(this.source, this.sourceFrames,
                        sourcePosition, 0, this.sound.loop) * volume;
                    float r = sample(this.source, this.sourceFrames,
                        sourcePosition, 1, this.sound.loop) * volume;
                    AudioRenderer.PcmBlockRenderer.accumulate(left, right, destination,
                        layout, 2, l, r, 1F);
                }

                mixed = true;

                this.position += pitch * this.format.sampleRate() / (double) outputRate;
                this.nextOutput = output + 1L;
            }

            return mixed;
        }

        private void updateGains(int timelineFrame, LoopFrame state, ListenerFrame listener,
                                 float volume, ChannelLayout layout)
        {
            this.gainFrame = timelineFrame;
            this.gainLayout = layout;

            if (layout == ChannelLayout.MONO)
            {
                this.leftGain = monoGain(this.sound, state, listener, volume);
                this.rightGain = 0F;
                return;
            }

            if (this.sound.relative || listener == null)
            {
                this.leftGain = this.rightGain = volume * CENTER_GAIN;
                return;
            }

            double x = state == null ? this.sound.x : state.x;
            double y = state == null ? this.sound.y : state.y;
            double z = state == null ? this.sound.z : state.z;
            double dx = x - listener.x;
            double dy = y - listener.y;
            double dz = z - listener.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float gain = volume;

            if (this.sound.attenuate && this.sound.range > 0F)
            {
                gain *= MathUtils.clamp(1F - (float) (distance / this.sound.range), 0F, 1F);
            }

            if (distance < 0.001D)
            {
                this.leftGain = this.rightGain = gain * CENTER_GAIN;
                return;
            }

            double pan = MathUtils.clamp((dx * listener.rightX + dy * listener.rightY
                + dz * listener.rightZ) / distance, -1D, 1D);
            double angle = (pan + 1D) * Math.PI / 4D;

            this.leftGain = gain * (float) Math.cos(angle);
            this.rightGain = gain * (float) Math.sin(angle);
        }

        private void advance(long from, long to, int outputRate, double frameRate)
        {
            for (long output = from; output < to; output++)
            {
                if (!this.sound.loop && this.position >= this.sourceFrames)
                {
                    this.exhausted = true;
                    this.nextOutput = this.end;
                    return;
                }

                LoopFrame state = this.sound.getTrackFrame((int) Math.floor(output * frameRate / outputRate));
                float pitch = state == null ? this.sound.pitch : state.pitch;
                this.position += pitch * this.format.sampleRate() / (double) outputRate;
            }

            if (!this.sound.loop && this.position >= this.sourceFrames)
            {
                this.exhausted = true;
                this.nextOutput = this.end;
            }
        }
    }

    /** Immutable decoded-resource handoff from the client thread to an export worker. */
    public record SoundResourceSnapshot(Map<ResourceLocation, Wave> resources,
                                        AudioRenderResult.Status status,
                                        String message, Throwable cause)
    {
        public SoundResourceSnapshot
        {
            resources = resources == null ? Map.of() : copyResourceSnapshot(resources);
            status = status == null ? AudioRenderResult.Status.MIX_FAILURE : status;
        }

        public boolean success()
        {
            return this.status == AudioRenderResult.Status.SUCCESS;
        }

        private static SoundResourceSnapshot success(Map<ResourceLocation, Wave> resources)
        {
            return new SoundResourceSnapshot(resources, AudioRenderResult.Status.SUCCESS, null, null);
        }

        private static SoundResourceSnapshot failure(AudioRenderResult.Status status,
                                                     String message, Throwable cause)
        {
            return new SoundResourceSnapshot(Map.of(), status, message, cause);
        }
    }

    private enum WaveSupport
    {
        SUPPORTED,
        UNSUPPORTED,
        MALFORMED
    }

    private record InputSnapshot(List<CapturedSound> sounds,
                                 List<ListenerFrame> frames,
                                 Wave filmAudio) {}

    private static final class SnapshotCancelledException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    private static final class UnsupportedSnapshotException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;

        private UnsupportedSnapshotException(String message)
        {
            super(message);
        }
    }

    private static final class MalformedSnapshotException extends IllegalStateException
    {
        private static final long serialVersionUID = 1L;

        private MalformedSnapshotException(String message)
        {
            super(message);
        }
    }
}
