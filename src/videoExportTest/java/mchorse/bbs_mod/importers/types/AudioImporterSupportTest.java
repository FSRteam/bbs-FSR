package mchorse.bbs_mod.importers.types;

import mchorse.bbs_mod.audio.AudioImportPolicy;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.audio.PcmEncoding;
import mchorse.bbs_mod.audio.PcmFormat;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveCue;
import mchorse.bbs_mod.audio.wav.WaveList;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.utils.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Focused publication, collision, policy, and owned-cleanup checks. */
public final class AudioImporterSupportTest
{
    private AudioImporterSupportTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
        System.out.println("AudioImporterSupportTest: all tests passed");
    }

    public static void runAll() throws Exception
    {
        emptyLegacyImportIsNotSuccessful();
        sourceWavPreservesBytesAndMetadata();
        invalidSourceWavIsNotPublished();
        fakeConverterPublishesWithoutSourceDownmix();
        corruptSuccessfulConversionIsNotPublished();
        wrongExplicitLayoutIsNotPublished();
        explicitLayoutsAreTheOnlyAcArguments();
        lateCollisionNeverOverwrites();
        concurrentImportsPublishDistinctFiles();
        partialFailureCleansOnlyOwnedTemporary();
    }

    private static void emptyLegacyImportIsNotSuccessful()
    {
        AtomicBoolean invoked = new AtomicBoolean();
        IImporter importer = new IImporter()
        {
            @Override
            public IKey getName()
            {
                return IKey.EMPTY;
            }

            @Override
            public boolean canImport(ImporterContext context)
            {
                return true;
            }

            @Override
            public void importFiles(ImporterContext context)
            {
                invoked.set(true);
            }
        };
        ImportOutcome outcome = importer.importFilesOutcome(
            new ImporterContext(List.of(), new File(".")));

        check(!outcome.success() && outcome.imported() == 0,
            "empty legacy import returned success(0)");
        check(!invoked.get(), "empty legacy import invoked the importer");
        check(!ImportOutcome.success(0).success(),
            "ImportOutcome factory allowed success(0)");
    }

    private static void sourceWavPreservesBytesAndMetadata() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-source-copy-");

        try
        {
            Path sourceFolder = Files.createDirectory(root.resolve("source"));
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path source = sourceFolder.resolve("metadata.wav");
            Wave wave = stereoWave();
            WaveList info = new WaveList("INFO");
            WaveCue cue = new WaveCue();

            info.entries.add(new Pair<>("INAM", "unchanged source metadata"));
            wave.lists.add(info);
            cue.id = 7;
            cue.position = 1;
            cue.dataChunkID = 0x61746164;
            cue.sampleStart = 1;
            wave.cues.add(cue);
            WaveWriter.write(source.toFile(), wave);

            byte[] expected = Files.readAllBytes(source);
            AtomicBoolean converterCalled = new AtomicBoolean();
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), AudioImportPolicy.SOURCE,
                true, false, destination.toFile(), (folder, arguments) ->
                {
                    converterCalled.set(true);
                    return false;
                });
            Path imported = destination.resolve("metadata.wav");

            check(outcome.success() && outcome.imported() == 1,
                "WAV SOURCE import did not report one published file");
            check(!converterCalled.get(), "WAV SOURCE import invoked FFmpeg");
            check(Arrays.equals(expected, Files.readAllBytes(imported)),
                "WAV SOURCE import changed container bytes or metadata");

            Wave decoded;

            try (var stream = Files.newInputStream(imported))
            {
                decoded = new WaveReader().read(stream);
            }

            check(decoded.getFormat().layout() == ChannelLayout.STEREO,
                "WAV SOURCE import changed the source channel layout");
            check(decoded.lists.size() == 1 && decoded.cues.size() == 1,
                "WAV SOURCE import dropped LIST/cue metadata");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void invalidSourceWavIsNotPublished() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-invalid-source-");

        try
        {
            Path sourceFolder = Files.createDirectory(root.resolve("source"));
            Path destination = Files.createDirectory(root.resolve("destination"));
            byte[] invalid = new byte[] {'R', 'I', 'F'};
            Path source = Files.write(sourceFolder.resolve("broken.wav"), invalid);
            AtomicBoolean converterCalled = new AtomicBoolean();
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), AudioImportPolicy.SOURCE,
                true, false, destination.toFile(), (folder, arguments) ->
                {
                    converterCalled.set(true);
                    return true;
                });

            check(!outcome.success() && outcome.imported() == 0,
                "invalid SOURCE WAV was reported as imported");
            check(!converterCalled.get(), "invalid SOURCE WAV invoked FFmpeg");
            check(!Files.exists(destination.resolve("broken.wav")),
                "invalid SOURCE WAV was published");
            check(Arrays.equals(invalid, Files.readAllBytes(source)),
                "failed SOURCE WAV validation changed the source file");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void fakeConverterPublishesWithoutSourceDownmix() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-convert-success-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path source = Files.write(root.resolve("music.flac"), new byte[] {1, 2, 3});
            List<String> captured = new ArrayList<>();
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), AudioImportPolicy.SOURCE,
                false, true, destination.toFile(), (folder, arguments) ->
                {
                    captured.addAll(arguments);
                    WaveWriter.write(output(arguments).toFile(), stereoWave());

                    return true;
                });

            check(outcome.success() && outcome.imported() == 1,
                "fake converter success was not published");
            assertAc(captured, null);
            Wave decoded;

            try (var stream = Files.newInputStream(destination.resolve("music.wav")))
            {
                decoded = new WaveReader().read(stream);
            }

            check(decoded.getFormat().layout() == ChannelLayout.STEREO,
                "SOURCE conversion did not retain the fake stereo output");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void corruptSuccessfulConversionIsNotPublished() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-corrupt-success-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path source = Files.write(root.resolve("music.mp3"), new byte[] {1});
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), AudioImportPolicy.SOURCE,
                false, true, destination.toFile(), (folder, arguments) ->
                {
                    Files.write(output(arguments), new byte[] {'R', 'I', 'F'});

                    return true;
                });

            check(!outcome.success() && outcome.imported() == 0,
                "corrupt successful conversion was reported as imported");
            check(!Files.exists(destination.resolve("music.wav")),
                "corrupt successful conversion was published");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void wrongExplicitLayoutIsNotPublished() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-wrong-layout-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path source = Files.write(root.resolve("music.flac"), new byte[] {1});
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), AudioImportPolicy.MONO,
                false, true, destination.toFile(), (folder, arguments) ->
                {
                    WaveWriter.write(output(arguments).toFile(), stereoWave());

                    return true;
                });

            check(!outcome.success() && outcome.imported() == 0,
                "wrong explicit layout was reported as imported");
            check(outcome.message().contains("expected mono"),
                "wrong explicit layout did not propagate its validation failure");
            check(!Files.exists(destination.resolve("music.wav")),
                "wrong explicit layout was published");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void explicitLayoutsAreTheOnlyAcArguments() throws Exception
    {
        assertExplicitLayout(AudioImportPolicy.MONO, ChannelLayout.MONO, "1");
        assertExplicitLayout(AudioImportPolicy.STEREO, ChannelLayout.STEREO, "2");
    }

    private static void assertExplicitLayout(AudioImportPolicy policy, ChannelLayout layout,
                                             String expectedChannels) throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-explicit-layout-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path source = Files.write(root.resolve("source.bin"), new byte[] {9});
            List<String> captured = new ArrayList<>();
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), policy, false, true,
                destination.toFile(), (folder, arguments) ->
                {
                    captured.addAll(arguments);
                    WaveWriter.write(output(arguments).toFile(), wave(layout));

                    return true;
                });

            check(outcome.success(), policy + " fake conversion failed");
            assertAc(captured, expectedChannels);
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void lateCollisionNeverOverwrites() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-collision-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path source = Files.write(root.resolve("track.mp3"), new byte[] {4, 5});
            byte[] competingBytes = new byte[] {42, 24};
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                context(source, destination), AudioImportPolicy.SOURCE,
                false, true, destination.toFile(), (folder, arguments) ->
                {
                    WaveWriter.write(output(arguments).toFile(), stereoWave());
                    Files.write(destination.resolve("track.wav"), competingBytes);

                    return true;
                });

            check(outcome.success(), "conversion failed when a destination collision appeared");
            check(Arrays.equals(competingBytes, Files.readAllBytes(destination.resolve("track.wav"))),
                "publication overwrote a concurrently created destination");
            check(Files.isRegularFile(destination.resolve("track_1.wav")),
                "collision-safe publication did not select the next suffix");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void concurrentImportsPublishDistinctFiles() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-concurrent-publication-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path firstSource = Files.write(
                Files.createDirectory(root.resolve("first")).resolve("track.mp3"),
                new byte[] {1});
            Path secondSource = Files.write(
                Files.createDirectory(root.resolve("second")).resolve("track.mp3"),
                new byte[] {2});
            CountDownLatch convertersReady = new CountDownLatch(2);
            CountDownLatch publishTogether = new CountDownLatch(1);
            ImportOutcome[] outcomes = new ImportOutcome[2];
            Throwable[] failures = new Throwable[2];
            Thread first = concurrentImportThread(firstSource, destination, (byte) 32,
                convertersReady, publishTogether, outcomes, failures, 0);
            Thread second = concurrentImportThread(secondSource, destination, (byte) 224,
                convertersReady, publishTogether, outcomes, failures, 1);

            first.start();
            second.start();
            boolean ready = convertersReady.await(10, TimeUnit.SECONDS);
            publishTogether.countDown();
            first.join(TimeUnit.SECONDS.toMillis(10));
            second.join(TimeUnit.SECONDS.toMillis(10));

            check(ready, "concurrent converters did not reach the publication barrier");
            check(!first.isAlive() && !second.isAlive(),
                "concurrent import did not finish after publication was released");
            check(failures[0] == null && failures[1] == null,
                "concurrent import threw outside its outcome path");
            check(outcomes[0] != null && outcomes[0].success()
                    && outcomes[1] != null && outcomes[1].success(),
                "one concurrent import failed to publish");
            Path publishedFirst = destination.resolve("track.wav");
            Path publishedSecond = destination.resolve("track_1.wav");

            check(Files.isRegularFile(publishedFirst) && Files.isRegularFile(publishedSecond),
                "concurrent imports did not publish two collision-safe names");
            check(!Arrays.equals(Files.readAllBytes(publishedFirst),
                    Files.readAllBytes(publishedSecond)),
                "concurrent publication overwrote one converted artifact");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static Thread concurrentImportThread(
        Path source, Path destination, byte marker,
        CountDownLatch convertersReady, CountDownLatch publishTogether,
        ImportOutcome[] outcomes, Throwable[] failures, int index)
    {
        return new Thread(() ->
        {
            try
            {
                outcomes[index] = AudioImporterSupport.importFiles(
                    context(source, destination), AudioImportPolicy.SOURCE,
                    false, true, destination.toFile(), (folder, arguments) ->
                    {
                        WaveWriter.write(output(arguments).toFile(), markedStereoWave(marker));
                        convertersReady.countDown();

                        try
                        {
                            if (!publishTogether.await(10, TimeUnit.SECONDS))
                            {
                                throw new IOException("publication barrier timed out");
                            }
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                            throw new IOException("publication barrier interrupted", e);
                        }

                        return true;
                    });
            }
            catch (Throwable t)
            {
                failures[index] = t;
            }
        }, "audio-import-race-" + index);
    }

    private static void partialFailureCleansOnlyOwnedTemporary() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-audio-partial-failure-");

        try
        {
            Path destination = Files.createDirectory(root.resolve("destination"));
            Path first = Files.write(root.resolve("first.mp3"), new byte[] {1});
            Path second = Files.write(root.resolve("second.mp3"), new byte[] {2});
            Path userFile = Files.write(destination.resolve("keep.wav"), new byte[] {8, 6, 7, 5});
            byte[] userBytes = Files.readAllBytes(userFile);
            AtomicInteger invocation = new AtomicInteger();
            ImportOutcome outcome = AudioImporterSupport.importFiles(
                new ImporterContext(List.of(first.toFile(), second.toFile()), destination.toFile()),
                AudioImportPolicy.SOURCE, false, true, destination.toFile(),
                (folder, arguments) ->
                {
                    if (invocation.getAndIncrement() == 0)
                    {
                        WaveWriter.write(output(arguments).toFile(), stereoWave());

                        return true;
                    }

                    Files.write(output(arguments), new byte[] {'R', 'I', 'F'});

                    return false;
                });

            check(!outcome.success() && outcome.imported() == 1,
                "partial conversion failure lost the successfully imported count");
            check(Files.isRegularFile(destination.resolve("first.wav")),
                "successful file before a partial failure was not published");
            check(!Files.exists(destination.resolve("second.wav")),
                "failed partial conversion was published");
            check(Arrays.equals(userBytes, Files.readAllBytes(userFile)),
                "partial cleanup touched a user-owned file");
            assertNoOwnedTemporary(destination);
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static ImporterContext context(Path source, Path destination)
    {
        return new ImporterContext(List.of(source.toFile()), destination.toFile());
    }

    private static Path output(List<String> arguments)
    {
        return Path.of(arguments.get(arguments.size() - 1));
    }

    private static Wave stereoWave()
    {
        return wave(ChannelLayout.STEREO);
    }

    private static Wave markedStereoWave(byte marker)
    {
        return new Wave(new PcmFormat(PcmEncoding.PCM_U8, ChannelLayout.STEREO, 8000),
            new byte[] {marker, (byte) (255 - (marker & 0xff))});
    }

    private static Wave wave(ChannelLayout layout)
    {
        byte[] data = layout == ChannelLayout.MONO
            ? new byte[] {(byte) 128, (byte) 192}
            : new byte[] {0, (byte) 255, (byte) 128, 64};

        return new Wave(new PcmFormat(PcmEncoding.PCM_U8, layout, 8000), data);
    }

    private static void assertAc(List<String> arguments, String expectedChannels)
    {
        int index = arguments.indexOf("-ac");

        if (expectedChannels == null)
        {
            check(index < 0, "SOURCE conversion unexpectedly inserted -ac");
        }
        else
        {
            check(index >= 0 && index + 1 < arguments.size()
                    && expectedChannels.equals(arguments.get(index + 1)),
                "explicit conversion did not use -ac " + expectedChannels);
            check(arguments.lastIndexOf("-ac") == index,
                "explicit conversion inserted more than one -ac option");
        }
    }

    private static void assertNoOwnedTemporary(Path directory) throws IOException
    {
        try (Stream<Path> files = Files.list(directory))
        {
            check(files.noneMatch(path -> path.getFileName().toString().startsWith(".bbs-audio-")),
                "owned importer temporary file was not cleaned");
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }
}
