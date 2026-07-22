package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.MinecraftSoundCapture.CapturedSound;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Deterministic performance and cancellation checks through the production
 * renderer/mixer entry points. The resource-snapshot overload keeps the check
 * independent of a running Minecraft client, OpenAL device, and codecs.
 */
final class AudioProductionPerformanceTest
{
    private static final int MAX_BLOCK_FRAMES = 8_192;
    private static final int SAMPLE_RATE = 48_000;
    private static final double TIMELINE_RATE = 20D;
    private static final int TEN_MINUTE_TIMELINE_FRAMES = 12_000;
    private static final long TEN_MINUTE_STEREO_FRAMES = 48_000L * 60L * 10L;
    private static final long MAX_WORKING_BYTES = 64L * 1024L * 1024L;
    private static final int HIGH_EVENT_COUNT = 4_096;
    private static final ResourceLocation FIXTURE_LOCATION =
        ResourceLocation.fromNamespaceAndPath("bbs_audio_verification", "stereo-event");

    private AudioProductionPerformanceTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("AudioProductionPerformanceTest: production gates passed");
    }

    static void runAll() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-production-performance-");

        try
        {
            Wave fixture = proceduralStereoFixture();
            warmProductionEntrypoints(root, fixture);
            rendererCancellationIsBounded(root, fixture);
            mixerCancellationIsBounded(root, fixture);
            tenMinuteStereoHighEventMixIsBounded(root, fixture);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void warmProductionEntrypoints(Path root, Wave fixture) throws Exception
    {
        Path rendererOutput = root.resolve("warm-renderer.wav");
        AudioRenderResult renderer = AudioRenderer.renderPreparedAudio(rendererOutput.toFile(),
            List.of(new AudioRenderer.PreparedFilmSource(fixture, 0D, 0D, 0.01D, 1F)),
            SAMPLE_RATE, 0D, 0.01D, ChannelLayout.STEREO, () -> false,
            (completed, total) -> {});
        check(renderer.success(), "renderer warm-up failed: " + renderer.message());
        Files.deleteIfExists(rendererOutput);

        Path mixerOutput = root.resolve("warm-mixer.wav");
        CapturedSound sound = loopingSound(1);
        AudioRenderResult mixer = MinecraftSoundMixer.mixToFileResult(mixerOutput.toFile(),
            List.of(sound), List.of(), null, Map.of(FIXTURE_LOCATION, fixture),
            SAMPLE_RATE, TIMELINE_RATE, 1, ChannelLayout.STEREO, () -> false,
            (completed, total) -> {});
        check(mixer.success(), "mixer warm-up failed: " + mixer.message());
        Files.deleteIfExists(mixerOutput);
    }

    private static void rendererCancellationIsBounded(Path root, Wave fixture)
    {
        Path output = root.resolve("renderer-cancelled.wav");
        ProgressProbe progress = ProgressProbe.cancelAfterFirstBlock();
        AudioRenderResult result = AudioRenderer.renderPreparedAudio(output.toFile(),
            List.of(new AudioRenderer.PreparedFilmSource(fixture, 0D, 0D, 2D, 1F)),
            SAMPLE_RATE, 0D, 2D, ChannelLayout.STEREO, progress, progress);

        assertCancelled(result, progress, "AudioRenderer");
        check(!Files.exists(output), "cancelled renderer published an output file");
        assertNoTemporary(output);
    }

    private static void mixerCancellationIsBounded(Path root, Wave fixture)
    {
        Path output = root.resolve("mixer-cancelled.wav");
        CapturedSound sound = loopingSound(40);
        ProgressProbe progress = ProgressProbe.cancelAfterFirstBlock();
        AudioRenderResult result = MinecraftSoundMixer.mixToFileResult(output.toFile(),
            List.of(sound), List.of(), null, Map.of(FIXTURE_LOCATION, fixture),
            SAMPLE_RATE, TIMELINE_RATE, 40, ChannelLayout.STEREO, progress, progress);

        assertCancelled(result, progress, "MinecraftSoundMixer");
        check(!Files.exists(output), "cancelled mixer published an output file");
        assertNoTemporary(output);
    }

    private static void tenMinuteStereoHighEventMixIsBounded(Path root, Wave fixture)
        throws Exception
    {
        List<CapturedSound> sounds = new ArrayList<>(HIGH_EVENT_COUNT + 1);
        CapturedSound continuous = loopingSound(TEN_MINUTE_TIMELINE_FRAMES);
        sounds.add(continuous);

        for (int index = 0; index < HIGH_EVENT_COUNT; index++)
        {
            int frame = (int) ((long) index * TEN_MINUTE_TIMELINE_FRAMES / HIGH_EVENT_COUNT);
            CapturedSound event = new CapturedSound(FIXTURE_LOCATION, frame,
                0D, 0D, 0D, true, false, 0.05F, 1F, 0F, false);
            event.endFrame = Math.min(TEN_MINUTE_TIMELINE_FRAMES, frame + 1);
            sounds.add(event);
        }

        check(sounds.size() == HIGH_EVENT_COUNT + 1,
            "high-event fixture did not retain its complete event set");
        Map<ResourceLocation, Wave> resources = Map.of(FIXTURE_LOCATION, fixture);
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        settleHeap();
        long baseline = usedHeap(memory);
        ProgressProbe progress = ProgressProbe.success(memory, baseline);
        Path output = root.resolve("ten-minute-stereo-high-event.wav");

        AudioRenderResult result = MinecraftSoundMixer.mixToFileResult(output.toFile(),
            sounds, List.of(), null, resources, SAMPLE_RATE, TIMELINE_RATE,
            TEN_MINUTE_TIMELINE_FRAMES, ChannelLayout.STEREO, progress, progress);

        check(result.success(), "ten-minute production mixer failed: " + result.message());
        check(result.frames() == TEN_MINUTE_STEREO_FRAMES,
            "ten-minute production mixer reported the wrong frame count: " + result.frames());
        check(progress.total() == TEN_MINUTE_STEREO_FRAMES,
            "ten-minute production mixer changed the progress total");
        check(progress.blocks() == expectedBlocks(TEN_MINUTE_STEREO_FRAMES),
            "ten-minute production mixer committed an unexpected block count: " + progress.blocks());
        check(progress.maxBlockFrames() <= MAX_BLOCK_FRAMES,
            "production mixer exceeded the 8,192-frame block contract: "
                + progress.maxBlockFrames());
        check(AudioRenderer.PcmBlockRenderer.BLOCK_FRAMES <= MAX_BLOCK_FRAMES,
            "production block renderer constant exceeds the 8,192-frame contract");
        check(progress.peakWorkingBytes() - baseline <= MAX_WORKING_BYTES,
            "production mixer working-set growth exceeded 64 MiB: "
                + Math.max(0L, progress.peakWorkingBytes() - baseline));

        long expectedBytes = 44L + TEN_MINUTE_STEREO_FRAMES * 2L * Short.BYTES;
        check(Files.size(output) == expectedBytes,
            "ten-minute production mixer wrote an unexpected WAV size");
        check(progress.lastCompleted() == TEN_MINUTE_STEREO_FRAMES,
            "ten-minute production mixer progress did not reach the end");
        Files.deleteIfExists(output);
        assertNoTemporary(output);
    }

    private static Wave proceduralStereoFixture()
    {
        PcmFormat format = new PcmFormat(PcmEncoding.PCM_S16_LE,
            ChannelLayout.STEREO, SAMPLE_RATE);
        int frames = 257;
        byte[] data = new byte[frames * format.bytesPerFrame()];
        long state = 0x4d595df4d0f33173L;

        for (int frame = 0; frame < frames; frame++)
        {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            float left = ((state >>> 40) / 8_388_608F - 1F) * 0.35F;
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
            float right = ((state >>> 40) / 8_388_608F - 1F) * 0.2F;
            int offset = frame * format.bytesPerFrame();
            PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, data, offset, left);
            PcmSamples.writeNormalized(PcmEncoding.PCM_S16_LE, data, offset + 2, right);
        }

        return new Wave(format, data);
    }

    private static CapturedSound loopingSound(int endFrame)
    {
        CapturedSound sound = new CapturedSound(FIXTURE_LOCATION, 0,
            0D, 0D, 0D, true, false, 0.2F, 1F, 0F, true);
        sound.endFrame = endFrame;
        return sound;
    }

    private static void assertCancelled(AudioRenderResult result, ProgressProbe progress,
                                        String owner)
    {
        check(result.status() == AudioRenderResult.Status.CANCELLED,
            owner + " cancellation reported " + result.status());
        check(progress.cancelRequested(), owner + " did not observe its cancellation request");
        check(progress.maxBlockFrames() <= MAX_BLOCK_FRAMES,
            owner + " exceeded the 8,192-frame block contract");
        check(progress.postCancellationBlocks() <= 1,
            owner + " committed more than one block after cancellation");
        check(progress.postCancellationFrames() <= MAX_BLOCK_FRAMES,
            owner + " committed more than one block of frames after cancellation");
        check(progress.total() > 0L, owner + " did not report a production progress total");
    }

    private static long expectedBlocks(long frames)
    {
        return (frames + MAX_BLOCK_FRAMES - 1L) / MAX_BLOCK_FRAMES;
    }

    private static void settleHeap()
    {
        for (int attempt = 0; attempt < 3; attempt++)
        {
            System.gc();
        }
    }

    private static long usedHeap(MemoryMXBean memory)
    {
        return Math.max(0L, memory.getHeapMemoryUsage().getUsed());
    }

    private static void assertNoTemporary(Path output)
    {
        Path directory = output.toAbsolutePath().getParent();
        String prefix = "." + output.getFileName() + ".";

        try (var paths = Files.list(directory))
        {
            check(paths.noneMatch(path -> path.getFileName().toString().startsWith(prefix)),
                "production cancellation retained a temporary WAV");
        }
        catch (IOException e)
        {
            throw new AssertionError("could not inspect production temporary files", e);
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ProgressProbe implements BooleanSupplier,
        BiConsumer<Long, Long>
    {
        private final boolean cancelOnFirstBlock;
        private final MemoryMXBean memory;
        private boolean cancelled;
        private long total = -1L;
        private long lastCompleted;
        private long blocks;
        private long maxBlockFrames;
        private long postCancellationBlocks;
        private long postCancellationFrames;
        private long peakWorkingBytes;

        private ProgressProbe(boolean cancelOnFirstBlock, MemoryMXBean memory, long baseline)
        {
            this.cancelOnFirstBlock = cancelOnFirstBlock;
            this.memory = memory;
            this.peakWorkingBytes = baseline;
        }

        private static ProgressProbe cancelAfterFirstBlock()
        {
            return new ProgressProbe(true, null, 0L);
        }

        private static ProgressProbe success(MemoryMXBean memory, long baseline)
        {
            return new ProgressProbe(false, memory, baseline);
        }

        @Override
        public boolean getAsBoolean()
        {
            return this.cancelled;
        }

        @Override
        public void accept(Long completedValue, Long totalValue)
        {
            long completed = completedValue;
            long reportedTotal = totalValue;
            check(reportedTotal > 0L, "production progress reported a non-positive total");
            if (this.total < 0L) this.total = reportedTotal;
            check(this.total == reportedTotal, "production progress total changed");
            check(completed > this.lastCompleted && completed <= reportedTotal,
                "production progress was not strictly monotonic");

            long delta = completed - this.lastCompleted;
            this.maxBlockFrames = Math.max(this.maxBlockFrames, delta);
            if (this.cancelled)
            {
                this.postCancellationBlocks += 1L;
                this.postCancellationFrames += delta;
            }
            this.lastCompleted = completed;
            this.blocks += 1L;

            if (this.memory != null)
            {
                this.peakWorkingBytes = Math.max(this.peakWorkingBytes, usedHeap(this.memory));
            }

            if (this.cancelOnFirstBlock && this.blocks == 1L)
            {
                this.cancelled = true;
            }
        }

        private boolean cancelRequested() { return this.cancelled; }
        private long total() { return this.total; }
        private long lastCompleted() { return this.lastCompleted; }
        private long blocks() { return this.blocks; }
        private long maxBlockFrames() { return this.maxBlockFrames; }
        private long postCancellationBlocks() { return this.postCancellationBlocks; }
        private long postCancellationFrames() { return this.postCancellationFrames; }
        private long peakWorkingBytes() { return this.peakWorkingBytes; }
    }
}
