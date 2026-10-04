package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class FFMpegUtils
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-video-export");
    private static final Set<String> SKIP_DIRS = Set.of("Windows", "Program Files", "Program Files (x86)", "$Recycle.Bin", "System Volume Information", "AppData");
    private static final long HEALTH_CHECK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5L);
    private static final long TERMINATION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(1L);
    private static final Pattern LIBX264_ENCODER = Pattern.compile("(?m)^\\s*V\\S*\\s+libx264\\s");
    private static final Pattern AAC_ENCODER = Pattern.compile("(?m)^\\s*A\\S*\\s+aac\\s");

    /**
     * People usually are not bright enough, even though everything is stated
     * in the tutorial, they still manage to specify either wrong path to ffmpeg, or
     * they specify the path to the folder...
     *
     * This little method should simplify their lives!
     */
    private static File findFFMPEG(String path)
    {
        return findFFMPEG(path, OS.CURRENT == OS.WINDOWS);
    }

    static File findFFMPEG(String path, boolean isWin)
    {
        File file = new File(path);

        if (file.isDirectory())
        {
            String subpath = isWin ? "ffmpeg.exe" : "ffmpeg";
            File bin = new File(file, subpath);

            if (bin.isFile())
            {
                return bin;
            }

            bin = new File(new File(file, "bin"), subpath);

            if (bin.isFile())
            {
                return bin;
            }
        }
        else if (isWin && !file.exists())
        {
            File exe = new File(path + ".exe");

            if (exe.exists())
            {
                return exe;
            }
        }

        return file;
    }

    public static String getFFMPEG()
    {
        String encoder = BBSSettings.videoEncoderPath.get();
        File encoderPath = findFFMPEG(encoder);

        if (encoderPath.isFile())
        {
            encoder = encoderPath.getAbsolutePath();
        }

        return encoder;
    }

    public static boolean checkFFMPEG()
    {
        try
        {
            return checkFFMPEG(Path.of(getFFMPEG()));
        }
        catch (InvalidPathException e)
        {
            return false;
        }
    }

    public static boolean checkFFMPEG(Path candidate)
    {
        File executable = resolveFFMPEG(candidate);
        List<String> args = new ArrayList<>();

        args.add(executable.getAbsolutePath());
        args.add("-version");

        return executeCommand(BBSMod.getGameFolder(), BBSMod.getSettingsPath("converter.log"), args, HEALTH_CHECK_TIMEOUT_MS);
    }

    public static File resolveFFMPEG(Path candidate)
    {
        return findFFMPEG(candidate.toAbsolutePath().normalize().toString());
    }

    public static Validation validateFFMPEG(Path candidate)
    {
        File executable = resolveFFMPEG(candidate);

        if (!executable.isFile())
        {
            return new Validation(executable.toPath(), false, false, false);
        }

        Path settings = BBSMod.getSettingsFolder().toPath();
        Path log = null;

        try
        {
            Files.createDirectories(settings);
            log = Files.createTempFile(settings, "ffmpeg-validation-", ".log");

            List<String> version = List.of(executable.getAbsolutePath(), "-hide_banner", "-version");

            if (!executeCommand(BBSMod.getGameFolder(), log.toFile(), version, HEALTH_CHECK_TIMEOUT_MS))
            {
                return new Validation(executable.toPath(), false, false, false);
            }

            List<String> encoders = List.of(executable.getAbsolutePath(), "-hide_banner", "-encoders");

            if (!executeCommand(BBSMod.getGameFolder(), log.toFile(), encoders, HEALTH_CHECK_TIMEOUT_MS))
            {
                return new Validation(executable.toPath(), true, false, false);
            }

            String output = Files.readString(log);
            boolean libx264 = LIBX264_ENCODER.matcher(output).find();
            boolean aac = AAC_ENCODER.matcher(output).find();

            return new Validation(executable.toPath(), true, libx264, aac);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=ffmpeg.process phase=validate result=failed error_class={}",
                e.getClass().getName());

            return new Validation(executable.toPath(), false, false, false);
        }
        finally
        {
            if (log != null)
            {
                try
                {
                    Files.deleteIfExists(log);
                }
                catch (IOException e)
                {
                    LOGGER.debug("Failed to delete FFmpeg validation log {}", log, e);
                }
            }
        }
    }

    public static boolean execute(File folder, String... arguments)
    {
        List<String> args = new ArrayList<String>();

        args.add(getFFMPEG());

        for (String arg : arguments)
        {
            args.add(arg);
        }

        File log = BBSMod.getSettingsPath("converter.log");

        return executeCommand(folder, log, args, 0L);
    }

    static boolean executeCommand(File folder, File log, List<String> arguments, long timeoutMs)
    {
        ProcessBuilder builder = new ProcessBuilder(arguments);

        builder.directory(folder);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log);

        try
        {
            Process start = builder.start();

            return waitForProcess(start, timeoutMs);
        }
        catch (Exception | LinkageError e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }

            LOGGER.warn("[BBS-SEM] topic=ffmpeg.process phase=launch result=failed error_class={}",
                e.getClass().getName());
        }

        return false;
    }

    static boolean waitForProcess(Process process, long timeoutMs)
    {
        boolean interrupted = false;

        try
        {
            boolean exited;

            if (timeoutMs > 0L)
            {
                exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            }
            else
            {
                process.waitFor();
                exited = true;
            }

            return exited && process.exitValue() == 0;
        }
        catch (InterruptedException e)
        {
            interrupted = true;

            return false;
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=ffmpeg.process phase=wait result=failed error_class={}",
                e.getClass().getName());

            return false;
        }
        finally
        {
            closeProcessStreams(process);

            try
            {
                if (process.isAlive())
                {
                    process.destroy();

                    if (!process.waitFor(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    {
                        process.destroyForcibly();
                    }
                }
            }
            catch (InterruptedException e)
            {
                interrupted = true;
            }
            catch (Exception | LinkageError e)
            {}

            if (process.isAlive())
            {
                try
                {
                    process.destroyForcibly();

                    if (!process.waitFor(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    {
                        LOGGER.warn("[BBS-SEM] topic=ffmpeg.process phase=terminate result=timeout");
                    }
                }
                catch (InterruptedException e1)
                {
                    interrupted = true;
                }
                catch (Exception | LinkageError ignored)
                {}
            }

            if (process.isAlive())
            {
                LOGGER.warn("[BBS-SEM] topic=ffmpeg.process phase=terminate result=alive");
            }

            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeProcessStreams(Process process)
    {
        try
        {
            process.getOutputStream().close();
        }
        catch (Exception | LinkageError ignored)
        {}

        try
        {
            process.getInputStream().close();
        }
        catch (Exception | LinkageError ignored)
        {}

        try
        {
            process.getErrorStream().close();
        }
        catch (Exception | LinkageError ignored)
        {}
    }

    public static Optional<Path> findFFMpeg(Path root)
    {
        Visitor visitor = new Visitor();

        try
        {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, visitor);
        }
        catch (FFMpegFoundException found)
        {
            return Optional.of(found.foundPath);
        }
        catch (IOException e)
        {
            LOGGER.warn("[BBS-SEM] topic=ffmpeg.discovery phase=executable_scan result=failed error_class={}",
                e.getClass().getName());
        }

        return Optional.empty();
    }

    private static class Visitor extends SimpleFileVisitor<Path>
    {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        {
            Path name = dir.getFileName();

            if (name != null && SKIP_DIRS.contains(name.toString()))
            {
                return FileVisitResult.SKIP_SUBTREE;
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
        {
            Path fileName = file.getFileName();

            if (fileName != null && fileName.toString().equalsIgnoreCase("ffmpeg.exe"))
            {
                Path parent = file.getParent();

                if (parent != null)
                {
                    Path parentName = parent.getFileName();

                    if (parentName != null && parentName.toString().equalsIgnoreCase("bin"))
                    {
                        throw new FFMpegFoundException(file.toAbsolutePath());
                    }
                }
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
        {
            return FileVisitResult.CONTINUE;
        }
    }

    private static class FFMpegFoundException extends RuntimeException
    {
        final Path foundPath;

        public FFMpegFoundException(Path foundPath)
        {
            this.foundPath = foundPath;
        }
    }

    public record Validation(Path executable, boolean runs, boolean libx264, boolean aac)
    {
        public boolean usable()
        {
            return this.runs && this.libx264 && this.aac;
        }

        public String missingCapabilities()
        {
            List<String> missing = new ArrayList<>();

            if (!this.runs)
            {
                missing.add("ffmpeg");
            }
            else
            {
                if (!this.libx264) missing.add("libx264");
                if (!this.aac) missing.add("aac");
            }

            return String.join(", ", missing).toLowerCase(Locale.ROOT);
        }
    }
}
