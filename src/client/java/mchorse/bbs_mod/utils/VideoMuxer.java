package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.ChannelLayout;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Performs the post-recording FFmpeg pass using exact session-owned paths. */
public final class VideoMuxer
{
    public static final String DEFAULT_ARGUMENTS = VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS;
    private static final Duration TIMEOUT = Duration.ofMinutes(10L);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(5L);
    private static final Duration GRACEFUL_TERMINATION_TIMEOUT = Duration.ofSeconds(1L);

    public enum Status
    {
        SUCCESS,
        CANCELLED,
        PREPARATION_FAILED,
        ENCODE_FAILED
    }

    public record MuxResult(Status status, Path output, Throwable cause, String message,
                            boolean logPublished, List<Throwable> cleanupFailures)
    {
        public MuxResult(Status status, Path output, Throwable cause, String message)
        {
            this(status, output, cause, message, false, List.of());
        }

        public MuxResult(Status status, Path output, Throwable cause, String message,
                         boolean logPublished)
        {
            this(status, output, cause, message, logPublished, List.of());
        }

        public MuxResult
        {
            cleanupFailures = cleanupFailures == null ? List.of()
                : Collections.unmodifiableList(cleanupFailures);
        }

        public boolean success()
        {
            return this.status == Status.SUCCESS;
        }
    }

    private VideoMuxer()
    {}

    /** Legacy adapter retained for callers which do not yet own artifact paths. */
    public static File mux(File video, File audio, String movieName)
    {
        return mux(video, audio, movieName, DEFAULT_ARGUMENTS);
    }

    /** Legacy adapter publishes to a collision-free name and never deletes its inputs. */
    public static File mux(File video, File audio, String movieName, String arguments)
    {
        return mux(video, audio, movieName, arguments, resolveSettingsLayout());
    }

    /** Additive legacy adapter with an explicit snapshotted layout. */
    public static File mux(File video, File audio, String movieName, String arguments, ChannelLayout layout)
    {
        if (video == null || video.getParentFile() == null)
        {
            return null;
        }

        Path work = null;

        try
        {
            Path folder = video.getParentFile().toPath().toAbsolutePath().normalize();
            work = Files.createTempDirectory(folder, ".bbs-mux-");
            Path partial = work.resolve("mux.mp4");
            Path log = work.resolve("mux.log");
            String base = movieName == null || movieName.isBlank() ? "video" : Path.of(movieName).getFileName().toString();
            Path result = folder.resolve(base + "-" + UUID.randomUUID() + ".mp4");
            MuxResult mux = mux(video, audio, partial, log, layout, arguments, () -> false);

            if (!mux.success())
            {
                return null;
            }

            moveWithoutReplace(partial, result);
            return result.toFile();
        }
        catch (Exception e)
        {
            return null;
        }
        finally
        {
            if (work != null)
            {
                deleteOwned(work.resolve("mux.mp4"));
                deleteOwned(work.resolve("mux.log"));
                deleteOwned(work);
            }
        }
    }

    /**
     * Encode into the exact owned partial path.  Publication is deliberately a
     * separate artifact-owner operation so a mux failure cannot replace or
     * delete the original video.
     */
    public static MuxResult mux(File video, File audio, Path partialOutput, Path logFile,
                                ChannelLayout layout, String arguments,
                                BooleanSupplier cancelled)
    {
        return mux(video, audio, partialOutput, logFile, layout, arguments, cancelled, isLogEnabled());
    }

    /** Exact mux entry point with all mutable settings snapshotted by the owner. */
    public static MuxResult mux(File video, File audio, Path partialOutput, Path logFile,
                                ChannelLayout layout, String arguments,
                                BooleanSupplier cancelled, boolean logEnabled)
    {
        Process process = null;
        Path requestedOutput = null;
        Path output = null;
        Path muxWork = null;
        Path requestedLog = null;
        Path ownedLog = null;
        List<Throwable> cleanupFailures = new ArrayList<>();

        try
        {
            VideoExportAudioProfile.channels(layout);

            if (video == null || audio == null || !video.isFile() || !audio.isFile()
                || video.length() <= 0L || audio.length() <= 0L
                || partialOutput == null || cancelled == null)
            {
                throw new IllegalArgumentException("Mux input or owned output is missing");
            }

            VideoExportAudioProfile.validateTemplate(arguments, true, true);

            requestedOutput = partialOutput.toAbsolutePath().normalize();
            Path parent = requestedOutput.getParent();
            if (parent == null) throw new IOException("Mux output has no parent directory");
            Files.createDirectories(parent);

            if (Files.exists(requestedOutput))
            {
                throw new IOException("Mux output already exists: " + requestedOutput);
            }

            /* FFmpeg must never write directly to a caller-controlled path:
             * another producer can appear after the existence check. A unique
             * directory gives this process exclusive cleanup provenance; the
             * requested artifact is published only after FFmpeg exits. */
            muxWork = Files.createTempDirectory(parent, ".bbs-mux-");
            output = muxWork.resolve("output.mp4");

            if (cancelled.getAsBoolean())
            {
                return new MuxResult(Status.CANCELLED, null, null,
                    "Mux cancelled before launch", false, cleanupFailures);
            }

            Map<String, String> replacements = new LinkedHashMap<>();
            String outputString = output.toString();
            String outputBase = outputString.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
                ? outputString.substring(0, outputString.length() - 4)
                : outputString;
            replacements.put("%VIDEO%", video.getAbsolutePath());
            replacements.put("%AUDIO_TRACK%", audio.getAbsolutePath());
            replacements.put("%OUTPUT%", outputString);
            replacements.put("%NAME%", outputBase);
            replacements.put("%AUDIO_SAMPLE_RATE%", String.valueOf(VideoExportAudioProfile.SAMPLE_RATE));
            replacements.put("%AUDIO_CHANNELS%", String.valueOf(VideoExportAudioProfile.channels(layout)));
            replacements.put("%AUDIO_LAYOUT%", layout.id());

            List<String> command = new ArrayList<>();
            command.add(FFMpegUtils.getFFMPEG());
            List<String> resolved = VideoExportUtils.resolveArguments(arguments, replacements);
            if (resolved.stream().noneMatch("-n"::equalsIgnoreCase)) command.add("-n");
            command.addAll(resolved);

            ProcessBuilder builder = new ProcessBuilder(command)
                .directory(parent.toFile())
                .redirectErrorStream(true);

            if (logEnabled && logFile != null)
            {
                requestedLog = logFile.toAbsolutePath().normalize();
                Path logParent = requestedLog.getParent();
                if (logParent == null) throw new IOException("Mux log has no parent directory");
                Files.createDirectories(logParent);
                if (Files.exists(requestedLog)) throw new IOException("Mux log already exists: " + requestedLog);
                ownedLog = muxWork.resolve("mux.log");
                builder.redirectOutput(ownedLog.toFile());
            }
            else
            {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }

            process = builder.start();
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();

            while (process.isAlive())
            {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                {
                    terminate(process, cleanupFailures);
                    deleteOwned(output, cleanupFailures);
                    deleteOwned(ownedLog, cleanupFailures);
                    return new MuxResult(Status.CANCELLED, null, null, "Mux cancelled", false, cleanupFailures);
                }

                if (System.nanoTime() >= deadline)
                {
                    terminate(process, cleanupFailures);
                    deleteOwned(output, cleanupFailures);
                    deleteOwned(ownedLog, cleanupFailures);
                    return new MuxResult(Status.ENCODE_FAILED, null,
                        new IOException("Timed out waiting for FFmpeg mux"), "Mux timed out", false, cleanupFailures);
                }

                process.waitFor(100L, TimeUnit.MILLISECONDS);
            }

            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) <= 0L)
            {
                int code = process.exitValue();
                deleteOwned(output, cleanupFailures);
                deleteOwned(ownedLog, cleanupFailures);
                return new MuxResult(Status.ENCODE_FAILED, null,
                    new IOException("FFmpeg mux exited with code " + code), "FFmpeg mux failed", false, cleanupFailures);
            }

            /* Publish only after the process has produced a non-empty file.
             * A collision leaves both the foreign target and our private
             * output untouched. */
            moveWithoutReplace(output, requestedOutput);
            boolean logPublished = false;
            String message = "";
            if (ownedLog != null)
            {
                try
                {
                    moveWithoutReplace(ownedLog, requestedLog);
                    logPublished = true;
                }
                catch (IOException e)
                {
                    /* Logging is diagnostic and must not invalidate a media
                     * file already published without replacement. The private
                     * log is cleaned below; any target collision is preserved. */
                    message = "Mux succeeded, but its log could not be published: " + e.getMessage();
                }
            }

            return new MuxResult(Status.SUCCESS, requestedOutput, null, message, logPublished, cleanupFailures);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            terminate(process, cleanupFailures);
            deleteOwned(output, cleanupFailures);
            deleteOwned(ownedLog, cleanupFailures);
            return new MuxResult(Status.CANCELLED, null, e, "Mux interrupted", false, cleanupFailures);
        }
        catch (Exception | LinkageError e)
        {
            terminate(process, cleanupFailures);
            deleteOwned(output, cleanupFailures);
            deleteOwned(ownedLog, cleanupFailures);
            return new MuxResult(Status.PREPARATION_FAILED, null, e, e.getMessage(), false, cleanupFailures);
        }
        finally
        {
            closeProcessStreams(process, cleanupFailures);
            deleteOwned(output, cleanupFailures);
            deleteOwned(ownedLog, cleanupFailures);
            deleteOwned(muxWork, cleanupFailures);
        }
    }

    private static boolean isLogEnabled()
    {
        return BBSSettings.videoEncoderLog != null && BBSSettings.videoEncoderLog.get();
    }

    /** Preserve recognized reserved settings so the mux boundary can reject them. */
    private static ChannelLayout resolveSettingsLayout()
    {
        try
        {
            ChannelLayout layout = BBSSettings.videoAudioLayout == null
                ? null : ChannelLayout.fromId(BBSSettings.videoAudioLayout.get());

            return layout == null ? ChannelLayout.MONO : layout;
        }
        catch (RuntimeException ignored)
        {
            return ChannelLayout.MONO;
        }
    }

    private static void terminate(Process process, List<Throwable> cleanupFailures)
    {
        terminate(process, cleanupFailures, TERMINATION_TIMEOUT);
    }

    static void terminate(Process process, List<Throwable> cleanupFailures, Duration timeout)
    {
        if (process == null) return;

        Duration effectiveTimeout = timeout == null ? TERMINATION_TIMEOUT : timeout;
        long timeoutNanos = Math.max(0L, effectiveTimeout.toNanos());
        long deadline = deadlineAfter(timeoutNanos);
        boolean interrupted = false;

        closeProcessStreams(process, cleanupFailures);

        try
        {
            if (probeLiveness(process, cleanupFailures, deadline) == Liveness.DEAD)
            {
                return;
            }

            boolean gracefulDestroyRequested = destroy(process, false, cleanupFailures, deadline);

            if (gracefulDestroyRequested)
            {
                long halfBudget = timeoutNanos <= 1L ? timeoutNanos : timeoutNanos / 2L;
                long gracefulBudget = Math.min(remainingNanos(deadline),
                    Math.min(GRACEFUL_TERMINATION_TIMEOUT.toNanos(), halfBudget));
                long gracefulDeadline = Math.min(deadline, deadlineAfter(gracefulBudget));
                WaitResult graceful = waitForExit(process, gracefulDeadline, cleanupFailures);
                interrupted |= graceful.interrupted();

                if (graceful.exited()) return;
            }

            destroy(process, true, cleanupFailures, deadline);
            WaitResult forced = waitForExit(process, deadline, cleanupFailures);
            interrupted |= forced.interrupted();

            if (forced.exited()
                || probeLiveness(process, cleanupFailures, deadline) == Liveness.DEAD)
            {
                return;
            }

            cleanupFailures.add(new IOException("FFmpeg mux process termination was not confirmed before the "
                + effectiveTimeout.toMillis() + " ms teardown deadline"));
        }
        finally
        {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean destroy(Process process, boolean forcibly,
                                   List<Throwable> cleanupFailures, long deadline)
    {
        if (remainingNanos(deadline) <= 0L) return false;

        try
        {
            if (forcibly) process.destroyForcibly();
            else process.destroy();

            return true;
        }
        catch (Exception | LinkageError e)
        {
            cleanupFailures.add(e);
            return false;
        }
    }

    private static Liveness probeLiveness(Process process, List<Throwable> cleanupFailures,
                                          long deadline)
    {
        if (remainingNanos(deadline) <= 0L) return Liveness.UNKNOWN;

        try
        {
            return process.isAlive() ? Liveness.ALIVE : Liveness.DEAD;
        }
        catch (Exception | LinkageError e)
        {
            cleanupFailures.add(e);
            return Liveness.UNKNOWN;
        }
    }

    private static WaitResult waitForExit(Process process, long deadline,
                                          List<Throwable> cleanupFailures)
    {
        long remaining = remainingNanos(deadline);

        if (remaining <= 0L) return new WaitResult(false, false);

        try
        {
            return new WaitResult(process.waitFor(remaining, TimeUnit.NANOSECONDS), false);
        }
        catch (InterruptedException e)
        {
            cleanupFailures.add(e);
            return new WaitResult(false, true);
        }
        catch (Exception | LinkageError e)
        {
            cleanupFailures.add(e);
            return new WaitResult(false, false);
        }
    }

    private static long deadlineAfter(long timeoutNanos)
    {
        long now = System.nanoTime();

        if (timeoutNanos <= 0L) return now;

        try
        {
            return Math.addExact(now, timeoutNanos);
        }
        catch (ArithmeticException overflow)
        {
            return Long.MAX_VALUE;
        }
    }

    private static long remainingNanos(long deadline)
    {
        if (deadline == Long.MAX_VALUE) return Long.MAX_VALUE;

        return Math.max(0L, deadline - System.nanoTime());
    }

    private enum Liveness
    {
        ALIVE,
        DEAD,
        UNKNOWN
    }

    private record WaitResult(boolean exited, boolean interrupted)
    {}

    private static void closeProcessStreams(Process process, List<Throwable> cleanupFailures)
    {
        if (process == null) return;

        try { process.getOutputStream().close(); }
        catch (Exception e) { cleanupFailures.add(e); }
        try { process.getInputStream().close(); }
        catch (Exception e) { cleanupFailures.add(e); }
        try { process.getErrorStream().close(); }
        catch (Exception e) { cleanupFailures.add(e); }
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException
    {
        if (Files.exists(target)) throw new java.nio.file.FileAlreadyExistsException(target.toString());
        Files.move(source, target);
    }

    private static void deleteOwned(Path path)
    {
        deleteOwned(path, new ArrayList<>());
    }

    private static void deleteOwned(Path path, List<Throwable> cleanupFailures)
    {
        if (path == null) return;

        try
        {
            Files.deleteIfExists(path);
        }
        catch (Exception e)
        {
            cleanupFailures.add(e);
        }
    }
}
