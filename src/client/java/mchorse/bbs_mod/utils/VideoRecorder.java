package mchorse.bbs_mod.utils;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        if (this.exportOwner == null)
        {
            this.exportOwner = owner;
        }

        return this.exportOwner == owner;
    }

    /** Release only the matching reservation; stale sessions cannot release a new owner. */
    public synchronized void releaseReservation(Object owner)
    {
        if (this.exportOwner == owner)
        {
            this.exportOwner = null;
        }
    }

    public int getTextureId()
    {
        return this.textureId;
    }

    public int getCounter()
    {
        return this.counter;
    }

    private int[] pbos;
    private int pboIndex;

    /**
     * Start recording the video using ffmpeg
     */
    public void startRecording(String movieName, File audioFile, int textureId, int width, int height)
    {
        this.startRecordingInternal(movieName, audioFile, textureId, width, height);
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

    private boolean startRecordingInternal(String movieName, File audioFile, int textureId, int width, int height)
    {
        if (this.recording)
        {
            return false;
        }

        this.encoder.reset();
        this.completionAnnounced = false;
        this.counter = 0;
        this.textureId = textureId;
        this.textureWidth = width;
        this.textureHeight = height;
        this.resetTiming();

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

            Path path = Paths.get(movies.toString());

            if (movieName == null || movieName.isEmpty())
            {
                movieName = StringUtils.createTimestampFilename();
            }

            String params = audioFile == null
                ? BBSSettings.videoSettings.arguments.get()
                : BBSSettings.videoSettings.argumentsAudio.get();
            StringBuilder filters = new StringBuilder("vflip");
            float frameRate = (float) BBSRendering.getVideoFrameRate();

            int motionBlur = BBSRendering.getMotionBlur();

            for (int i = 0; i < motionBlur; i++)
            {
                filters.append(",tblend=all_mode=average,framestep=2");
            }

            Map<String, String> replacements = new LinkedHashMap<>();

            replacements.put("%WIDTH%", String.valueOf(width));
            replacements.put("%HEIGHT%", String.valueOf(height));
            replacements.put("%FPS%", String.valueOf(frameRate));
            replacements.put("%NAME%", movieName);
            replacements.put("%FILTERS%", filters.toString());

            if (audioFile != null)
            {
                replacements.put("%AUDIO_TRACK%", audioFile.getAbsolutePath());
            }

            List<String> args = new ArrayList<>();
            String encoder = FFMpegUtils.getFFMPEG();

            args.add(encoder);
            args.addAll(VideoExportUtils.resolveArguments(params, replacements));

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
            File log = path.resolve(movieName.concat(".log")).toFile();

            if (!BBSSettings.videoEncoderLog.get())
            {
                log = BBSMod.getSettingsPath("video.log");
            }

            builder.directory(path.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log);

            startedProcess = builder.start();

            if (!this.encoder.start(startedProcess))
            {
                startedProcess = null;
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

            this.cleanupCaptureResources();
        }

        return this.recording;
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

        if (outcome == VideoExportProcess.Outcome.FAILED)
        {
            this.logFailure();
        }
        else if (outcome == VideoExportProcess.Outcome.CANCELLED && this.encoder.getFailure() != null)
        {
            LOGGER.warn("Video recording was cancelled but FFmpeg cleanup reported an error", this.encoder.getFailure());
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
                File folder = BBSRendering.getVideoFolder();
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
