package mchorse.bbs_mod.utils;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns one FFmpeg process and its raw-video pipe. The first terminal outcome
 * wins so repeated UI, disconnect, and failure cleanup cannot turn a cancelled
 * or failed export into a successful one.
 */
public final class VideoExportProcess
{
    private static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1L);

    public enum Outcome
    {
        IDLE,
        RUNNING,
        SUCCEEDED,
        CANCELLED,
        FAILED
    }

    private final long shutdownTimeoutMs;

    private Process process;
    private WritableByteChannel channel;
    private Outcome outcome = Outcome.IDLE;
    private Throwable failure;

    public VideoExportProcess()
    {
        this(DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    VideoExportProcess(long shutdownTimeoutMs)
    {
        this.shutdownTimeoutMs = Math.max(0L, shutdownTimeoutMs);
    }

    public Outcome getOutcome()
    {
        return this.outcome;
    }

    public Throwable getFailure()
    {
        return this.failure;
    }

    public boolean isRunning()
    {
        return this.outcome == Outcome.RUNNING;
    }

    /** Reset a completed lifecycle before allocating resources for a new export. */
    public void reset()
    {
        if (this.isRunning())
        {
            throw new IllegalStateException("Cannot reset a running video export process");
        }

        this.process = null;
        this.channel = null;
        this.outcome = Outcome.IDLE;
        this.failure = null;
    }

    /**
     * Attach an already-started process and its stdin channel. A process which
     * has already exited is a startup failure even when its exit code is zero.
     */
    public boolean start(Process process, WritableByteChannel channel)
    {
        Objects.requireNonNull(channel, "channel");

        return this.startOwned(process, channel);
    }

    /** Take process ownership immediately, before adapting or unwrapping stdin. */
    public boolean start(Process process)
    {
        return this.startOwned(process, null);
    }

    private boolean startOwned(Process process, WritableByteChannel channel)
    {
        if (this.isRunning())
        {
            throw new IllegalStateException("Video export process is already running");
        }

        Objects.requireNonNull(process, "process");

        this.process = process;
        this.channel = channel;
        this.outcome = Outcome.RUNNING;
        this.failure = null;

        try
        {
            if (!this.process.isAlive())
            {
                this.fail(new IOException("FFmpeg exited before recording started"));
            }
        }
        catch (Exception | LinkageError e)
        {
            this.fail(e);
        }

        return this.isRunning();
    }

    /** Attach the process stdin adapter after ownership has already transferred. */
    public boolean attachChannel(WritableByteChannel channel)
    {
        Objects.requireNonNull(channel, "channel");

        if (!this.isRunning())
        {
            closeChannel(channel);

            return false;
        }

        if (this.channel != null)
        {
            throw new IllegalStateException("FFmpeg stdin channel is already attached");
        }

        this.channel = channel;

        return this.poll() == Outcome.RUNNING;
    }

    /** Detect an encoder which exited before its owning session completed. */
    public Outcome poll()
    {
        if (this.isRunning())
        {
            try
            {
                if (!this.process.isAlive())
                {
                    this.fail(new IOException("FFmpeg exited before video export completed"));
                }
            }
            catch (Exception | LinkageError e)
            {
                this.fail(e);
            }
        }

        return this.outcome;
    }

    /** Write one complete raw frame, treating any short/closed pipe as failure. */
    public Outcome write(ByteBuffer frame)
    {
        if (this.poll() != Outcome.RUNNING)
        {
            return this.outcome;
        }

        try
        {
            while (frame.hasRemaining())
            {
                int written = this.channel.write(frame);

                if (written < 0)
                {
                    throw new EOFException("FFmpeg closed the raw-video pipe");
                }

                if (written == 0)
                {
                    throw new IOException("FFmpeg raw-video pipe made no progress");
                }
            }
        }
        catch (Exception e)
        {
            this.fail(e);
        }

        return this.outcome;
    }

    /** Finish stdin and require a clean encoder exit before reporting success. */
    public Outcome complete()
    {
        if (this.poll() != Outcome.RUNNING)
        {
            return this.outcome;
        }

        return this.terminate(Outcome.SUCCEEDED, null);
    }

    /** Abort an export without ever producing completion side effects. */
    public Outcome cancel()
    {
        if (this.poll() != Outcome.RUNNING)
        {
            return this.outcome;
        }

        return this.terminate(Outcome.CANCELLED, null);
    }

    /** Abort an export because its owner or pipe failed. */
    public Outcome fail(Throwable cause)
    {
        return this.terminate(Outcome.FAILED, cause);
    }

    private Outcome terminate(Outcome requested, Throwable cause)
    {
        if (!this.isRunning())
        {
            if (this.outcome == Outcome.IDLE && requested == Outcome.FAILED)
            {
                this.outcome = Outcome.FAILED;
                this.failure = cause == null ? new IOException("Video export failed before FFmpeg started") : cause;
            }

            return this.outcome;
        }

        Throwable terminalFailure = cause;
        Process activeProcess = this.process;
        WritableByteChannel activeChannel = this.channel;

        /* Fence re-entrant cleanup before touching the process or channel. */
        this.outcome = requested;
        this.process = null;
        this.channel = null;

        try
        {
            if (activeChannel != null && activeChannel.isOpen())
            {
                activeChannel.close();
            }
        }
        catch (Exception e)
        {
            terminalFailure = appendFailure(terminalFailure, e);
        }

        if (activeProcess != null)
        {
            terminalFailure = closeProcessStreams(activeProcess, terminalFailure);
        }

        boolean exited = false;
        int exitCode = Integer.MIN_VALUE;

        if (activeProcess != null)
        {
            try
            {
                if (requested != Outcome.SUCCEEDED && activeProcess.isAlive())
                {
                    activeProcess.destroy();
                }

                long initialTimeout = requested == Outcome.SUCCEEDED
                    ? this.shutdownTimeoutMs
                    : Math.min(this.shutdownTimeoutMs, 1000L);

                exited = activeProcess.waitFor(initialTimeout, TimeUnit.MILLISECONDS);

                if (!exited)
                {
                    if (requested == Outcome.SUCCEEDED)
                    {
                        /* A process that required termination did not complete
                         * naturally, even if destroy later produces exit code 0. */
                        terminalFailure = appendFailure(
                            terminalFailure,
                            new IOException("Timed out waiting for FFmpeg to complete")
                        );
                    }

                    activeProcess.destroy();
                    exited = activeProcess.waitFor(Math.min(this.shutdownTimeoutMs, 1000L), TimeUnit.MILLISECONDS);

                    if (!exited)
                    {
                        activeProcess.destroyForcibly();
                        exited = activeProcess.waitFor(Math.min(this.shutdownTimeoutMs, 1000L), TimeUnit.MILLISECONDS);
                    }
                }

                if (exited)
                {
                    exitCode = activeProcess.exitValue();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                terminalFailure = appendFailure(terminalFailure, e);
            }
            catch (Exception e)
            {
                terminalFailure = appendFailure(terminalFailure, e);
            }
            finally
            {
                try
                {
                    if (activeProcess.isAlive())
                    {
                        activeProcess.destroyForcibly();
                        exited = activeProcess.waitFor(Math.min(this.shutdownTimeoutMs, 1000L), TimeUnit.MILLISECONDS);

                        if (exited)
                        {
                            exitCode = activeProcess.exitValue();
                        }
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    terminalFailure = appendFailure(terminalFailure, e);
                }
                catch (Exception e)
                {
                    terminalFailure = appendFailure(terminalFailure, e);
                }
            }
        }

        if (!exited)
        {
            terminalFailure = appendFailure(terminalFailure, new IOException("Timed out terminating FFmpeg"));
        }

        if (requested == Outcome.SUCCEEDED)
        {
            if (exited && exitCode != 0)
            {
                terminalFailure = appendFailure(terminalFailure, new IOException("FFmpeg exited with code " + exitCode));
            }

            this.outcome = terminalFailure == null ? Outcome.SUCCEEDED : Outcome.FAILED;
        }

        if (this.outcome == Outcome.FAILED)
        {
            this.failure = terminalFailure == null ? new IOException("Video export failed") : terminalFailure;
        }
        else
        {
            /* Keep cancellation cleanup errors diagnostic without changing its outcome. */
            this.failure = terminalFailure;
        }

        return this.outcome;
    }

    private static Throwable closeProcessStreams(Process process, Throwable failure)
    {
        try
        {
            process.getOutputStream().close();
        }
        catch (Exception e)
        {
            failure = appendFailure(failure, e);
        }

        try
        {
            process.getInputStream().close();
        }
        catch (Exception e)
        {
            failure = appendFailure(failure, e);
        }

        try
        {
            process.getErrorStream().close();
        }
        catch (Exception e)
        {
            failure = appendFailure(failure, e);
        }

        return failure;
    }

    private static void closeChannel(WritableByteChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (Exception ignored)
        {}
    }

    private static Throwable appendFailure(Throwable current, Throwable next)
    {
        if (current == null)
        {
            return next;
        }

        if (current != next)
        {
            current.addSuppressed(next);
        }

        return current;
    }
}
