package mchorse.bbs_mod.utils;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.UIUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class VideoRecorder
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Link RENDER_COMPLETE_SOUND = Link.assets("sounds/render_complete.ogg");

    private final VideoExportProcess encoder = new VideoExportProcess();
    private Object exportOwner;
    private boolean completionAnnounced;
    private boolean recording;

    private ByteBuffer buffer;
    private int textureId = -1;
    private int textureWidth;
    private int textureHeight;
    private int counter;
    private File outputFile;
    private double captureFrameRate;
    private int capturedHeldFrames;
    private boolean captureLimitFrameRate;
    private boolean outputProducerStarted;
    private boolean outputPublished;
    private boolean logProducerStarted;
    private Path producerWorkDirectory;
    private Path producerOutputPath;
    private Path producerLogPath;
    private Path requestedOutputPath;
    private Path requestedLogPath;

    public int serverTicks;
    public int lastServerTicks;

    public boolean isRecording()
    {
        return this.recording;
    }

    public VideoExportProcess.Outcome getOutcome()
    {
        return this.encoder.getOutcome();
    }

    public Throwable getFailure()
    {
        return this.encoder.getFailure();
    }

    /** Reserve the shared recorder across warm-up as well as active encoding. */
    public synchronized boolean tryReserve(Object owner)
    {
        if (owner == null)
        {
            throw new IllegalArgumentException("Video export owner cannot be null");
        }

        if (this.encoder.hasLiveProcess())
        {
            return false;
        }

        if (this.exportOwner == null)
        {
            this.exportOwner = owner;
        }

        return this.exportOwner == owner;
    }

    /** Release only the matching reservation; stale sessions cannot release a new owner. */
    public synchronized void releaseReservation(Object owner)
    {
        this.tryReleaseReservation(owner);
    }

    /** Release only after the child is confirmed dead. */
    public synchronized boolean tryReleaseReservation(Object owner)
    {
        if (this.exportOwner == owner && !this.encoder.hasLiveProcess())
        {
            this.exportOwner = null;
            return true;
        }

        return this.exportOwner == null;
    }

    public int getTextureId()
    {
        return this.textureId;
    }

    public int getCounter()
    {
        return this.counter;
    }

    public double getCaptureFrameRate()
    {
        return this.captureFrameRate;
    }

    public int getCapturedHeldFrames()
    {
        return this.capturedHeldFrames;
    }

    public boolean isCaptureFrameRateLimited()
    {
        return this.captureLimitFrameRate;
    }

    public boolean didStartOutputProducer()
    {
        return this.outputProducerStarted;
    }

    /** True only after the private encoder output was published without replacement. */
    public boolean didPublishOutput()
    {
        /* The fallback keeps old injected recorder subclasses compatible: they
         * write their exact test artifact synchronously and report success from
         * completeRecording().  A real recorder only reaches SUCCEEDED after
         * its private path has been moved without replacement. */
        File output = this.getOutputFile();
        return this.outputPublished || (output != null
            && output.isFile() && output.length() > 0L
            && this.getOutcome() == VideoExportProcess.Outcome.SUCCEEDED);
    }

    /** True only after the private encoder log was published without replacement. */
    public boolean didPublishLog()
    {
        return this.logProducerStarted;
    }

    /** A timed-out child remains owned by this recorder and cannot be reused. */
    public boolean hasLiveProcess()
    {
        return this.encoder.hasLiveProcess();
    }

    /** The output artifact owned by the current recording session. */
    public File getOutputFile()
    {
        return this.outputFile;
    }

    /** Update the exact published artifact only when the expected raw output is still owned. */
    public synchronized boolean acceptPublishedOutput(File expectedRaw, File published)
    {
        if (expectedRaw == null || published == null || !published.isFile() || published.length() <= 0L
            || this.outputFile == null || !this.outputFile.equals(expectedRaw))
        {
            return false;
        }

        this.outputFile = published;

        return true;
    }

    private int[] pbos;
    private int pboIndex;

    /**
     * Start recording the video using ffmpeg
     */
    public void startRecording(String movieName, File audioFile, int textureId, int width, int height)
    {
        ChannelLayout layout = audioFile == null ? ChannelLayout.MONO : resolveSettingsLayout();
        this.startRecordingInternal(movieName, audioFile, null, null, layout,
            Double.NaN, -1, -1, false, null, null, textureId, width, height);
    }

    public boolean tryStartRecording(String movieName, File audioFile, int textureId, int width, int height)
    {
        if (this.recording)
        {
            return false;
        }

        this.startRecording(movieName, audioFile, textureId, width, height);

        return this.recording;
    }

    /**
     * Additive exact-artifact entry point used by owned export sessions.  The
     * legacy descriptor above remains for addons and injected test recorders.
     */
    public boolean tryStartRecording(String movieName, File audioFile, File outputFile, File logFile,
                                     ChannelLayout layout, int textureId, int width, int height)
    {
        if (this.recording)
        {
            return false;
        }

        return this.startRecordingInternal(movieName, audioFile, outputFile, logFile, layout,
            Double.NaN, -1, -1, false, null, null, textureId, width, height);
    }

    /** Exact-artifact overload with immutable timing/filter snapshots. */
    public boolean tryStartRecording(String movieName, File audioFile, File outputFile, File logFile,
                                     ChannelLayout layout, double frameRate, int motionBlurPasses,
                                     int textureId, int width, int height)
    {
        if (this.recording)
        {
            return false;
        }

        return this.startRecordingInternal(movieName, audioFile, outputFile, logFile, layout,
            frameRate, motionBlurPasses, -1, false, null, null, textureId, width, height);
    }

    /** Exact-artifact overload with all timing/template settings snapshotted. */
    public boolean tryStartRecording(String movieName, File audioFile, File outputFile, File logFile,
                                     ChannelLayout layout, double frameRate, int motionBlurPasses,
                                     int heldFrames, boolean limitFrameRate, String arguments,
                                     boolean logEnabled, int textureId, int width, int height)
    {
        if (this.recording)
        {
            return false;
        }

        return this.startRecordingInternal(movieName, audioFile, outputFile, logFile, layout,
            frameRate, motionBlurPasses, heldFrames, limitFrameRate, arguments, logEnabled,
            textureId, width, height);
    }

    private boolean startRecordingInternal(String movieName, File audioFile, File requestedOutput,
                                           File requestedLog, ChannelLayout requestedLayout,
                                           double requestedFrameRate, int requestedMotionBlur,
                                           int requestedHeldFrames, boolean requestedLimitFrameRate,
                                           String requestedArguments, Boolean requestedLogEnabled,
                                           int textureId, int width, int height)
    {
        if (this.recording)
        {
            return false;
        }

        this.encoder.reset();
        this.completionAnnounced = false;
        this.counter = 0;
        this.outputFile = null;
        this.outputProducerStarted = false;
        this.outputPublished = false;
        this.logProducerStarted = false;
        this.producerWorkDirectory = null;
        this.producerOutputPath = null;
        this.producerLogPath = null;
        this.requestedOutputPath = null;
        this.requestedLogPath = null;
        this.textureId = textureId;
        this.textureWidth = width;
        this.textureHeight = height;
        this.resetTiming();

        ChannelLayout layout = requestedLayout == null ? ChannelLayout.MONO : requestedLayout;

        try
        {
            VideoExportAudioProfile.channels(layout);
        }
        catch (Exception | LinkageError e)
        {
            this.encoder.fail(e);
            this.cleanupCaptureResources();

            return false;
        }

        int size;

        try
        {
            size = VideoExportUtils.frameBufferSize(width, height);
        }
        catch (Exception | LinkageError e)
        {
            this.encoder.fail(e);
            this.cleanupCaptureResources();

            return false;
        }

        Process startedProcess = null;

        try
        {
            if (this.buffer == null)
            {
                this.buffer = MemoryUtil.memAlloc(size);
            }

            File movies = BBSRendering.getVideoFolder();

            movies.mkdirs();

            Path path = Paths.get(movies.toString()).toAbsolutePath().normalize();

            if (movieName == null || movieName.isEmpty())
            {
                movieName = StringUtils.createTimestampFilename();
            }

            File output = requestedOutput == null
                ? path.resolve(movieName + "-" + UUID.randomUUID() + ".mp4").toFile()
                : requestedOutput.getAbsoluteFile();
            Path outputPath = output.toPath().toAbsolutePath().normalize();
            Path outputParent = outputPath.getParent();

            if (outputParent == null)
            {
                throw new IOException("Video output has no parent directory");
            }

            Files.createDirectories(outputParent);

            if (Files.exists(outputPath))
            {
                throw new IOException("Video output already exists: " + outputPath);
            }

            this.outputFile = outputPath.toFile();
            this.requestedOutputPath = outputPath;
            this.producerWorkDirectory = Files.createTempDirectory(outputParent, ".bbs-record-");
            this.producerOutputPath = this.producerWorkDirectory.resolve("video.mp4");

            String params = requestedArguments == null
                ? (audioFile == null ? BBSSettings.videoSettings.arguments.get()
                    : BBSSettings.videoSettings.argumentsAudio.get())
                : requestedArguments;
            VideoExportAudioProfile.validateTemplate(params, audioFile != null);
            StringBuilder filters = new StringBuilder("vflip");
            float frameRate = Double.isFinite(requestedFrameRate) && requestedFrameRate > 0D
                ? (float) requestedFrameRate
                : (float) BBSRendering.getVideoFrameRate();

            int motionBlur = requestedMotionBlur >= 0
                ? requestedMotionBlur
                : BBSRendering.getMotionBlur();

            int heldFrames = requestedHeldFrames > 0
                ? requestedHeldFrames
                : BBSSettings.videoSettings.heldFrames.get();
            boolean limitFrameRate = requestedHeldFrames > 0
                ? requestedLimitFrameRate
                : BBSSettings.videoLimitFrameRate.get();

            this.captureFrameRate = frameRate;
            this.capturedHeldFrames = Math.max(1, heldFrames);
            this.captureLimitFrameRate = limitFrameRate;

            for (int i = 0; i < motionBlur; i++)
            {
                filters.append(",tblend=all_mode=average,framestep=2");
            }

            Map<String, String> replacements = new LinkedHashMap<>();

            replacements.put("%WIDTH%", String.valueOf(width));
            replacements.put("%HEIGHT%", String.valueOf(height));
            replacements.put("%FPS%", String.valueOf(frameRate));
            String outputString = this.producerOutputPath.toString();
            String outputBase = outputString.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
                ? outputString.substring(0, outputString.length() - 4)
                : outputString;

            replacements.put("%NAME%", outputBase);
            replacements.put("%OUTPUT%", outputString);
            replacements.put("%FILTERS%", filters.toString());

            if (audioFile != null)
            {
                replacements.put("%AUDIO_TRACK%", audioFile.getAbsolutePath());
                replacements.put("%AUDIO_SAMPLE_RATE%", String.valueOf(VideoExportAudioProfile.SAMPLE_RATE));
                replacements.put("%AUDIO_CHANNELS%", String.valueOf(VideoExportAudioProfile.channels(layout)));
                replacements.put("%AUDIO_LAYOUT%", layout.id());
            }

            List<String> args = new ArrayList<>();
            String encoder = FFMpegUtils.getFFMPEG();

            args.add(encoder);
            List<String> resolved = VideoExportUtils.resolveArguments(params, replacements);

            if (resolved.stream().noneMatch("-n"::equalsIgnoreCase))
            {
                args.add("-n");
            }

            args.addAll(resolved);

            LOGGER.debug(
                "Starting FFmpeg video recording at {}x{} and {} FPS (audio: {}, argument count: {})",
                width, height, frameRate, audioFile != null, args.size() - 1
            );

            if (OS.CURRENT == OS.MACOS)
            {
                /* PBO readback produces black footage on macOS; use the direct buffer. */
                this.pbos = null;
            }
            else
            {
                this.pbos = new int[2];
                this.pboIndex = 0;

                for (int i = 0; i < 2; i++)
                {
                    this.pbos[i] = GL30.glGenBuffers();

                    GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[i]);
                    GL30.glBufferData(GL30.GL_PIXEL_PACK_BUFFER, size, GL30.GL_STREAM_READ);
                }

                GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }

            ProcessBuilder builder = new ProcessBuilder(args);

            builder.directory(path.toFile());
            builder.redirectErrorStream(true);

                boolean logEnabled = requestedLogEnabled == null
                    ? BBSSettings.videoEncoderLog.get() : requestedLogEnabled;

                if (logEnabled)
            {
                File log = requestedLog == null
                    ? outputParent.resolve(outputPath.getFileName().toString() + ".log").toFile()
                    : requestedLog.getAbsoluteFile();
                Path logPath = log.toPath().toAbsolutePath().normalize();
                Path logParent = logPath.getParent();

                if (logParent == null)
                {
                    throw new IOException("Video encoder log has no parent directory");
                }

                Files.createDirectories(logParent);

                if (Files.exists(logPath))
                {
                    throw new IOException("Video encoder log already exists: " + logPath);
                }

                this.requestedLogPath = logPath;
                this.producerLogPath = this.producerWorkDirectory.resolve("recording.log");
                builder.redirectOutput(this.producerLogPath.toFile());
            }
            else
            {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }

            startedProcess = builder.start();
            this.outputProducerStarted = true;

            if (!this.encoder.start(startedProcess))
            {
                startedProcess = null;
                this.cleanupProducerArtifacts();
                this.cleanupCaptureResources();

                return false;
            }

            Process process = startedProcess;

            /* From here onward every adapter/reflection error is lifecycle-owned. */
            startedProcess = null;

            /**
             * Java wraps the process output stream into a BufferedOutputStream,
             *
             * but its little buffer is just slowing everything down with the
             * huge amount of data we're dealing here, so unwrap it with this little
             * hack.
             */
            OutputStream os = process.getOutputStream();

            if (os instanceof FilterOutputStream)
            {
                try
                {
                    Unsafe unsafe = UnsafeUtils.getUnsafe();
                    Field outField = FilterOutputStream.class.getDeclaredField("out");

                    os = (OutputStream) unsafe.getObject(os, unsafe.objectFieldOffset(outField));
                }
                catch (Exception | LinkageError e)
                {
                    LOGGER.warn("Failed to unwrap FFmpeg stdin; using the buffered stream", e);
                }
            }

            WritableByteChannel channel = Channels.newChannel(os);

            if (!this.encoder.attachChannel(channel))
            {
                this.encoder.fail(new IOException("FFmpeg stdin channel could not be attached"));
                this.cleanupProducerArtifacts();
                this.cleanupCaptureResources();

                return false;
            }

            this.recording = true;

            UIUtils.playClick(2F);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to start FFmpeg video recording", e);
            this.encoder.fail(e);

            this.cleanupProducerArtifacts();
            this.cleanupCaptureResources();
        }

        return this.recording;
    }

    /** Preserve recognized reserved settings so the export boundary can reject them. */
    private static ChannelLayout resolveSettingsLayout()
    {
        try
        {
            String id = BBSSettings.videoAudioLayout != null
                ? BBSSettings.videoAudioLayout.get()
                : BBSSettings.videoSettings == null ? null : BBSSettings.videoSettings.audioLayout.get();
            ChannelLayout layout = ChannelLayout.fromId(id);

            return layout == null ? ChannelLayout.MONO : layout;
        }
        catch (RuntimeException ignored)
        {
            return ChannelLayout.MONO;
        }
    }

    private void cleanupCaptureResources()
    {
        this.recording = false;

        if (this.pbos != null)
        {
            for (int pbo : this.pbos)
            {
                try
                {
                    GL30.glDeleteBuffers(pbo);
                }
                catch (Exception | LinkageError e)
                {
                    LOGGER.warn("Failed to release video capture PBO {}", pbo, e);
                }
            }

            try
            {
                GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.warn("Failed to unbind the video capture PBO", e);
            }
        }

        this.pbos = null;
        this.textureId = -1;
        this.captureFrameRate = 0D;
        this.capturedHeldFrames = 0;
        this.captureLimitFrameRate = false;

        if (this.buffer != null)
        {
            try
            {
                MemoryUtil.memFree(this.buffer);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.warn("Failed to release video capture memory", e);
            }

            this.buffer = null;
        }
    }

    /** Remove only the recorder's private staging files; never the requested target. */
    private void cleanupProducerArtifacts()
    {
        if (this.encoder.hasLiveProcess())
        {
            return;
        }

        Path output = this.producerOutputPath;
        Path log = this.producerLogPath;
        Path work = this.producerWorkDirectory;

        for (Path path : new Path[]{output, log})
        {
            if (path == null)
            {
                continue;
            }

            try
            {
                Files.deleteIfExists(path);
            }
            catch (Exception | LinkageError e)
            {
                this.encoder.addDiagnosticFailure(e);
            }
        }

        if (work != null)
        {
            try
            {
                Files.deleteIfExists(work);
            }
            catch (Exception | LinkageError e)
            {
                this.encoder.addDiagnosticFailure(e);
            }
        }

        this.producerOutputPath = null;
        this.producerLogPath = null;
        this.producerWorkDirectory = null;
    }

    /** Publish private encoder artifacts exactly once, without replacing a target. */
    private VideoExportProcess.Outcome publishProducerArtifacts()
    {
        if (this.producerOutputPath == null || this.requestedOutputPath == null)
        {
            return this.encoder.failAfterCompletion(
                new IOException("FFmpeg completed without an owned output path"));
        }

        try
        {
            if (!Files.isRegularFile(this.producerOutputPath) || Files.size(this.producerOutputPath) <= 0L)
            {
                throw new IOException("FFmpeg reported success without a non-empty video artifact");
            }

            moveWithoutReplace(this.producerOutputPath, this.requestedOutputPath);
            this.outputPublished = true;

            if (this.producerLogPath != null && this.requestedLogPath != null)
            {
                try
                {
                    moveWithoutReplace(this.producerLogPath, this.requestedLogPath);
                    this.logProducerStarted = true;
                }
                catch (Exception | LinkageError e)
                {
                    /* Logs are optional diagnostics; preserve a foreign log and
                     * keep the successfully published media artifact. */
                    LOGGER.warn("Video export completed but its encoder log could not be published", e);
                }
            }

            this.cleanupProducerArtifacts();
            return this.encoder.getOutcome();
        }
        catch (Exception | LinkageError e)
        {
            this.cleanupProducerArtifacts();
            return this.encoder.failAfterCompletion(e);
        }
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException
    {
        if (source == null || target == null || Files.exists(target))
        {
            throw new java.nio.file.FileAlreadyExistsException(String.valueOf(target));
        }

        Files.move(source, target);
    }

    /**
     * Stop recording
     */
    public void stopRecording()
    {
        this.completeRecording();

        this.announceSuccessfulCompletion();
    }

    /** Complete encoding; the owning session announces only after its teardown succeeds. */
    public VideoExportProcess.Outcome completeRecording()
    {
        return this.finishRecording(false);
    }

    /** Cancel recording without completion sound or opening the output folder. */
    public VideoExportProcess.Outcome cancelRecording()
    {
        return this.finishRecording(true);
    }

    /** Fail recording because its owning session or frame pipe encountered an error. */
    public VideoExportProcess.Outcome failRecording(Throwable cause)
    {
        if (this.recording)
        {
            this.recording = false;
            this.cleanupCaptureResources();
        }

        VideoExportProcess.Outcome outcome = this.encoder.fail(cause);

        this.cleanupProducerArtifacts();

        this.resetTiming();

        return outcome;
    }

    /** Poll the child process so an early encoder exit reaches the session teardown path. */
    public boolean checkRecordingHealth()
    {
        if (!this.recording)
        {
            return false;
        }

        if (this.encoder.poll() != VideoExportProcess.Outcome.RUNNING)
        {
            this.recording = false;
            this.cleanupCaptureResources();
            this.cleanupProducerArtifacts();
            this.resetTiming();
            this.logFailure();

            return false;
        }

        return true;
    }

    private VideoExportProcess.Outcome finishRecording(boolean cancelled)
    {
        if (!this.recording)
        {
            return this.encoder.getOutcome();
        }

        if (!cancelled && OS.CURRENT != OS.MACOS)
        {
            this.drainPendingPbo();
        }

        this.recording = false;
        this.cleanupCaptureResources();

        VideoExportProcess.Outcome outcome = cancelled
            ? this.encoder.cancel()
            : this.encoder.getOutcome() == VideoExportProcess.Outcome.RUNNING
                ? this.encoder.complete()
                : this.encoder.getOutcome();

        if (outcome == VideoExportProcess.Outcome.SUCCEEDED)
        {
            outcome = this.publishProducerArtifacts();
        }
        else
        {
            this.cleanupProducerArtifacts();
        }

        if (outcome == VideoExportProcess.Outcome.FAILED)
        {
            this.logFailure();
        }
        else if (outcome == VideoExportProcess.Outcome.CANCELLED && this.encoder.getFailure() != null)
        {
            LOGGER.warn("Video recording was cancelled but FFmpeg cleanup reported an error", this.encoder.getFailure());
        }

        if (outcome == VideoExportProcess.Outcome.SUCCEEDED
            && (this.outputFile == null || !this.outputFile.isFile() || this.outputFile.length() <= 0L))
        {
            outcome = this.encoder.failAfterCompletion(
                new IOException("FFmpeg reported success without a non-empty video artifact"));
            this.logFailure();
        }

        this.resetTiming();

        return outcome;
    }

    /** Run success-only user effects once, without changing an encoder outcome on UI failure. */
    public void announceSuccessfulCompletion()
    {
        if (this.encoder.getOutcome() != VideoExportProcess.Outcome.SUCCEEDED || this.completionAnnounced)
        {
            return;
        }

        this.completionAnnounced = true;

        try
        {
            if (BBSSettings.videoSettings.playSoundAfterExport.get())
            {
                if (BBSModClient.getSounds().play(RENDER_COMPLETE_SOUND) == null)
                {
                    UIUtils.playClick(0.5F);
                }
            }

            if (BBSSettings.videoSettings.openFolderAfterExport.get())
            {
                File folder = this.outputFile == null || this.outputFile.getParentFile() == null
                    ? BBSRendering.getVideoFolder()
                    : this.outputFile.getParentFile();
                Minecraft.getInstance().execute(() -> UIUtils.openFolder(folder));
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("Video export succeeded but its completion notification failed", e);
        }
    }

    private void resetTiming()
    {
        this.serverTicks = this.lastServerTicks = 0;
    }

    private void logFailure()
    {
        Throwable failure = this.encoder.getFailure();

        if (failure == null)
        {
            LOGGER.error("FFmpeg video recording failed");
        }
        else
        {
            LOGGER.error("FFmpeg video recording failed", failure);
        }
    }

    /**
     * Record a frame
     */
    public void recordFrame()
    {
        if (!this.checkRecordingHealth())
        {
            return;
        }

        if (OS.CURRENT == OS.MACOS)
        {
            this.recordFrameDirect();
        }
        else
        {
            this.recordFramePbo();
        }

        if (!this.recording || this.encoder.getOutcome() != VideoExportProcess.Outcome.RUNNING)
        {
            this.recording = false;
            this.cleanupCaptureResources();
            this.resetTiming();
            this.logFailure();

            return;
        }

        this.counter += 1;
    }

    private void recordFramePbo()
    {
        boolean mapped = false;

        try
        {
            int pbo = this.pboIndex;
            int nextPbo = (this.pboIndex + 1) % this.pbos.length;

            GL30.glPixelStorei(GL30.GL_PACK_ALIGNMENT, 1);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[pbo]);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, this.textureId);
            GL30.glGetTexImage(GL30.GL_TEXTURE_2D, 0, GL30.GL_BGR, GL30.GL_UNSIGNED_BYTE, 0);

            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[nextPbo]);

            ByteBuffer mappedBuffer = GL30.glMapBuffer(GL30.GL_PIXEL_PACK_BUFFER, GL30.GL_READ_ONLY);

            if (mappedBuffer == null)
            {
                throw new IOException("Failed to map video capture buffer");
            }

            mapped = true;

            if (this.counter != 0 && this.encoder.write(mappedBuffer) != VideoExportProcess.Outcome.RUNNING)
            {
                this.recording = false;
            }

            this.pboIndex = nextPbo;
        }
        catch (Exception | LinkageError e)
        {
            this.encoder.fail(e);
            this.recording = false;
        }
        finally
        {
            if (mapped)
            {
                try
                {
                    if (!GL30.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER))
                    {
                        throw new IOException("Video capture buffer contents became invalid while unmapping");
                    }
                }
                catch (Exception | LinkageError e)
                {
                    this.encoder.fail(e);
                    this.recording = false;
                }
            }

            try
            {
                GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }
            catch (Exception | LinkageError e)
            {
                this.encoder.fail(e);
                this.recording = false;
            }
        }

    }

    private void recordFrameDirect()
    {
        try
        {
            this.buffer.clear();

            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.textureId);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, this.buffer);
            this.buffer.rewind();

            if (this.encoder.write(this.buffer) != VideoExportProcess.Outcome.RUNNING)
            {
                this.recording = false;
            }
        }
        catch (Exception | LinkageError e)
        {
            this.encoder.fail(e);
            this.recording = false;
        }
    }

    /** Flush the newest ping-pong PBO once so natural completion does not drop the tail frame. */
    private void drainPendingPbo()
    {
        if (this.pbos == null || this.counter <= 0 || this.encoder.poll() != VideoExportProcess.Outcome.RUNNING)
        {
            return;
        }

        boolean mapped = false;

        try
        {
            int pending = (this.pboIndex + this.pbos.length - 1) % this.pbos.length;

            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.pbos[pending]);

            ByteBuffer mappedBuffer = GL30.glMapBuffer(GL30.GL_PIXEL_PACK_BUFFER, GL30.GL_READ_ONLY);

            if (mappedBuffer == null)
            {
                throw new IOException("Failed to map final video capture buffer");
            }

            mapped = true;
            this.encoder.write(mappedBuffer);
        }
        catch (Exception | LinkageError e)
        {
            this.encoder.fail(e);
        }
        finally
        {
            if (mapped)
            {
                try
                {
                    if (!GL30.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER))
                    {
                        throw new IOException("Final video capture buffer contents became invalid while unmapping");
                    }
                }
                catch (Exception | LinkageError e)
                {
                    this.encoder.fail(e);
                }
            }

            try
            {
                GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            }
            catch (Exception | LinkageError e)
            {
                this.encoder.fail(e);
            }
        }
    }

    /**
     * Toggle recording of the video
     */
    public void toggleRecording(int textureId, int textureWidth, int textureHeight)
    {
        if (this.recording)
        {
            this.stopRecording();
        }
        else
        {
            this.startRecording(StringUtils.createTimestampFilename(), null, textureId, textureWidth, textureHeight);
        }

        UIUtils.playClick();
    }
}
