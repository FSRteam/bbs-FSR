package mchorse.bbs_mod.importers.types;

import mchorse.bbs_mod.audio.AudioImportPolicy;
import mchorse.bbs_mod.audio.Wave;
import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.ImporterUtils;
import mchorse.bbs_mod.utils.FFMpegUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/** Shared, owned-temp publication path for WAV importers. */
public final class AudioImporterSupport
{
    private static final Object PUBLICATION_LOCK = new Object();

    private AudioImporterSupport()
    {}

    public static ImportOutcome importFiles(ImporterContext context, AudioImportPolicy policy,
                                            boolean wavInput, boolean outputWav, File directory)
    {
        return importFiles(context, policy, wavInput, outputWav, directory,
            (folder, arguments) -> FFMpegUtils.execute(folder, arguments.toArray(String[]::new)));
    }

    public static ImportOutcome importFiles(ImporterContext context, AudioImportPolicy policy,
                                            boolean wavInput, boolean outputWav, File directory,
                                            AudioConverter converter)
    {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(converter, "converter");

        if (directory == null)
        {
            return ImportOutcome.failure(0, "Audio destination is unavailable");
        }

        Path destination;

        try
        {
            destination = directory.toPath().toAbsolutePath().normalize();
            Files.createDirectories(destination);
        }
        catch (IOException e)
        {
            return ImportOutcome.failure(0, "Could not create audio destination: " + e.getMessage());
        }

        if (context.files == null || context.files.isEmpty())
        {
            return ImportOutcome.failure(0, "No audio files were selected");
        }

        int imported = 0;

        for (File source : context.files)
        {
            try
            {
                importOne(source, destination, policy, wavInput, outputWav, converter);
                imported++;
            }
            catch (Exception e)
            {
                return ImportOutcome.failure(imported,
                    "Could not import " + (source == null ? "audio" : source.getName())
                        + ": " + message(e));
            }
        }

        return ImportOutcome.success(imported);
    }

    private static void importOne(File source, Path directory, AudioImportPolicy policy,
                                  boolean wavInput, boolean outputWav,
                                  AudioConverter converter) throws IOException
    {
        if (source == null || !source.isFile())
        {
            throw new IOException("source file is missing");
        }

        String outputName = outputWav
            ? removeExtension(source.getName()) + ".wav"
            : source.getName();

        if (wavInput && policy == AudioImportPolicy.SOURCE)
        {
            /* Validate the owned copy before publishing it.  A source can be
             * changed while it is being copied, and a failed post-publish
             * validation must never leave a file that looks imported. */
            try (OwnedTemporary temporary = OwnedTemporary.create(directory, ".tmp"))
            {
                Files.copy(source.toPath(), temporary.path(), StandardCopyOption.REPLACE_EXISTING);
                validate(temporary.path());
                publish(temporary, directory, outputName);
            }

            return;
        }

        try (OwnedTemporary temporary = OwnedTemporary.create(directory, ".wav"))
        {
            List<String> arguments = policy.buildFfmpegArguments(
                source.getAbsolutePath(), temporary.path().toString());

            if (!converter.execute(directory.toFile(), arguments))
            {
                throw new IOException("FFmpeg exited with failure");
            }

            Wave wave = validate(temporary.path());
            if (policy.targetLayout() != null
                && wave.getFormat().layout() != policy.targetLayout())
            {
                throw new IOException("FFmpeg produced " + wave.getFormat().layout().id()
                    + "; expected " + policy.targetLayout().id());
            }

            publish(temporary, directory, outputName);
        }
    }

    private static Wave validate(Path file) throws IOException
    {
        try (InputStream stream = Files.newInputStream(file))
        {
            return new WaveReader().read(stream, file.toAbsolutePath().toString());
        }
    }

    /**
     * Move an owned, already validated temporary file without replacing an
     * existing user file.  Name selection is intentionally done immediately
     * before publication so a collision that appears during conversion is
     * handled by selecting the next suffix rather than by failing or
     * overwriting the existing file.
     */
    private static void publish(OwnedTemporary temporary, Path directory, String outputName)
        throws IOException
    {
        synchronized (PUBLICATION_LOCK)
        {
            for (;;)
            {
                File candidate = collisionSafe(directory, outputName);

                try
                {
                    /* Linux rename can otherwise replace a target created
                     * between name selection and move.  Serialize publication
                     * inside this process and retain the no-replace move for
                     * collisions from outside the importer. */
                    Files.move(temporary.path(), candidate.toPath());
                    temporary.release();

                    return;
                }
                catch (FileAlreadyExistsException e)
                {
                    /* Another process won this name after collisionSafe(). */
                }
            }
        }
    }

    private static File collisionSafe(Path directory, String name)
    {
        return new File(directory.toFile(), ImporterUtils.getName(directory.toFile(), name));
    }

    private static final class OwnedTemporary implements AutoCloseable
    {
        private final Path path;
        private boolean owned = true;

        private OwnedTemporary(Path path)
        {
            this.path = path;
        }

        private static OwnedTemporary create(Path directory, String suffix) throws IOException
        {
            return new OwnedTemporary(Files.createTempFile(directory, ".bbs-audio-", suffix));
        }

        private Path path()
        {
            return this.path;
        }

        private void release()
        {
            this.owned = false;
        }

        @Override
        public void close() throws IOException
        {
            if (this.owned)
            {
                Files.deleteIfExists(this.path);
                this.owned = false;
            }
        }
    }

    private static String removeExtension(String name)
    {
        int index = name.lastIndexOf('.');
        return index <= 0 ? name : name.substring(0, index);
    }

    private static String message(Exception e)
    {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    public interface AudioConverter
    {
        boolean execute(File workingDirectory, List<String> arguments) throws IOException;
    }
}
