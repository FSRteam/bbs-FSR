package mchorse.bbs_mod.client.render.surface;

import com.mojang.blaze3d.pipeline.RenderTarget;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/** Render-thread-only GPU downscale and three-slot asynchronous PBO readback. */
final class GpuSurfaceReadback implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-render-surface");
    private static final int PBO_COUNT = 3;

    private final RgbFramePool pool;
    private final Slot[] slots = new Slot[PBO_COUNT];

    private int framebuffer = -1;
    private int texture = -1;
    private int width;
    private int height;
    private int pixelBytes;
    private int nextSlot;
    private long highestSelectedSequence;

    GpuSurfaceReadback(RgbFramePool pool)
    {
        this.pool = pool;

        for (int i = 0; i < this.slots.length; i++)
        {
            this.slots[i] = new Slot();
        }
    }

    void poll(
        LongPredicate currentGeneration,
        BooleanSupplier discardPendingEncoderFrame,
        Consumer<RgbSurfaceFrame> output
    )
    {
        int previousPackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);

        try
        {
            Slot selectedSlot = null;
            PendingFrame selected = null;

            for (Slot slot : this.slots)
            {
                if (slot.pending == null)
                {
                    continue;
                }

                int wait = GL32.glClientWaitSync(slot.fence, 0, 0L);

                if (wait == GL32.GL_TIMEOUT_EXPIRED)
                {
                    continue;
                }

                PendingFrame pending = slot.pending;

                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
                slot.pending = null;

                if (wait == GL32.GL_WAIT_FAILED)
                {
                    LOGGER.warn("[bbs-client-render-surface] GPU fence wait failed; dropping surface frame");

                    continue;
                }

                if (!belongsToCurrentStream(pending.stamp, currentGeneration))
                {
                    continue;
                }
                long selectedSequence = selected == null ? 0L : selected.stamp.sequence();

                if (shouldSelectSequence(
                    pending.stamp.sequence(),
                    this.highestSelectedSequence,
                    selectedSequence
                ))
                {
                    selectedSlot = slot;
                    selected = pending;
                }
            }

            if (selectedSlot == null)
            {
                return;
            }

            this.highestSelectedSequence = selected.stamp.sequence();

            RgbFramePool.Lease lease = this.pool.acquire(this.pixelBytes);

            /* One lease may be encoding while the second is the encoder's
             * replaceable latest slot. Reclaim that stale pending frame before
             * dropping the newest ready PBO. */
            if (lease == null && discardPendingEncoderFrame.getAsBoolean())
            {
                lease = this.pool.acquire(this.pixelBytes);
            }
            if (lease == null)
            {
                return;
            }

            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, selectedSlot.buffer);

            ByteBuffer mapped = GL30.glMapBufferRange(
                GL30.GL_PIXEL_PACK_BUFFER,
                0L,
                this.pixelBytes,
                GL30.GL_MAP_READ_BIT
            );

            if (mapped == null)
            {
                lease.close();

                return;
            }

            boolean valid;

            try
            {
                ByteBuffer source = mapped.duplicate();
                ByteBuffer destination = lease.writableBuffer();

                source.clear();
                source.limit(this.pixelBytes);
                destination.put(source);
            }
            finally
            {
                valid = GL30.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER);
            }

            if (!valid)
            {
                lease.close();

                return;
            }

            RgbSurfaceFrame frame = new RgbSurfaceFrame(
                selected.kinds,
                selected.stamp,
                selected.capturedAtNanos,
                this.width,
                this.height,
                selected.jpegQuality,
                lease
            );

            try
            {
                output.accept(frame);
            }
            catch (RuntimeException | LinkageError e)
            {
                frame.close();

                throw e;
            }
        }
        finally
        {
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
        }
    }

    boolean issue(
        RenderTarget source,
        int targetWidth,
        int targetHeight,
        Set<BBSRenderSurfaceKind> kinds,
        BBSRenderSurfaceStamp stamp,
        long capturedAtNanos,
        int jpegQuality
    )
    {
        return this.issue(source, 0, 0, source.viewWidth, source.viewHeight, targetWidth, targetHeight,
            kinds, stamp, capturedAtNanos, jpegQuality);
    }

    static boolean shouldSelectSequence(long candidate, long deliveredFloor, long selected)
    {
        return candidate > deliveredFloor && candidate > selected;
    }

    boolean issue(
        RenderTarget source,
        int sourceX,
        int sourceY,
        int sourceWidth,
        int sourceHeight,
        int targetWidth,
        int targetHeight,
        Set<BBSRenderSurfaceKind> kinds,
        BBSRenderSurfaceStamp stamp,
        long capturedAtNanos,
        int jpegQuality
    )
    {
        GlState state = GlState.capture();
        int sourceReadBuffer = 0;
        boolean restoreSourceReadBuffer = false;

        try
        {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (this.framebuffer >= 0 && (this.width != targetWidth || this.height != targetHeight) && this.hasPendingFrames())
            {
                return false;
            }

            this.ensureSize(targetWidth, targetHeight);

            Slot slot = this.findFreeSlot();

            if (slot == null || source.frameBufferId < 0)
            {
                return false;
            }

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
            sourceReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            restoreSourceReadBuffer = source.frameBufferId != state.readFramebuffer;
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.framebuffer);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(
                sourceX,
                sourceY,
                sourceX + sourceWidth,
                sourceY + sourceHeight,
                0,
                0,
                this.width,
                this.height,
                GL11.GL_COLOR_BUFFER_BIT,
                GL11.GL_LINEAR
            );

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.framebuffer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, slot.buffer);
            GL11.glReadPixels(0, 0, this.width, this.height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L);

            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

            if (fence == 0L)
            {
                return false;
            }

            slot.fence = fence;
            slot.pending = new PendingFrame(kinds, stamp, capturedAtNanos, jpegQuality);

            return true;
        }
        finally
        {
            if (restoreSourceReadBuffer)
            {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
                GL11.glReadBuffer(sourceReadBuffer);
            }

            state.restore();
        }
    }

    private void ensureSize(int width, int height)
    {
        if (this.framebuffer >= 0 && this.width == width && this.height == height)
        {
            return;
        }

        this.deleteGpuResources();

        this.width = width;
        this.height = height;
        this.pixelBytes = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        this.texture = GL11.glGenTextures();
        this.framebuffer = GL30.glGenFramebuffers();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGB8,
            width,
            height,
            0,
            GL11.GL_RGB,
            GL11.GL_UNSIGNED_BYTE,
            0L
        );

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            this.texture,
            0
        );
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
        {
            this.deleteGpuResources();

            throw new IllegalStateException("surface capture framebuffer is incomplete: " + status);
        }

        for (Slot slot : this.slots)
        {
            slot.buffer = GL30.glGenBuffers();
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, slot.buffer);
            GL30.glBufferData(GL30.GL_PIXEL_PACK_BUFFER, this.pixelBytes, GL30.GL_STREAM_READ);
        }

        this.nextSlot = 0;
    }

    private Slot findFreeSlot()
    {
        for (int i = 0; i < this.slots.length; i++)
        {
            int index = (this.nextSlot + i) % this.slots.length;
            Slot slot = this.slots[index];

            if (slot.pending == null)
            {
                this.nextSlot = (index + 1) % this.slots.length;

                return slot;
            }
        }

        return null;
    }

    private boolean hasPendingFrames()
    {
        for (Slot slot : this.slots)
        {
            if (slot.pending != null)
            {
                return true;
            }
        }

        return false;
    }

    static boolean belongsToCurrentStream(BBSRenderSurfaceStamp stamp, LongPredicate currentGeneration)
    {
        return stamp != null
            && currentGeneration != null
            && currentGeneration.test(stamp.generation());
    }

    private void deleteGpuResources()
    {
        for (Slot slot : this.slots)
        {
            if (slot.fence != 0L)
            {
                GL32.glDeleteSync(slot.fence);
                slot.fence = 0L;
            }

            slot.pending = null;

            if (slot.buffer > 0)
            {
                GL30.glDeleteBuffers(slot.buffer);
                slot.buffer = -1;
            }
        }

        if (this.framebuffer >= 0)
        {
            GL30.glDeleteFramebuffers(this.framebuffer);
            this.framebuffer = -1;
        }

        if (this.texture >= 0)
        {
            GL11.glDeleteTextures(this.texture);
            this.texture = -1;
        }

        this.width = 0;
        this.height = 0;
        this.pixelBytes = 0;
        this.highestSelectedSequence = 0L;
    }

    @Override
    public void close()
    {
        this.deleteGpuResources();
    }

    private static final class Slot
    {
        private int buffer = -1;
        private long fence;
        private PendingFrame pending;
    }

    private record PendingFrame(
        Set<BBSRenderSurfaceKind> kinds,
        BBSRenderSurfaceStamp stamp,
        long capturedAtNanos,
        int jpegQuality
    )
    {}

    private record GlState(
        int readFramebuffer,
        int drawFramebuffer,
        int readBuffer,
        int drawBuffer,
        int packBuffer,
        int packAlignment,
        int activeTexture,
        int texture2D,
        boolean scissorEnabled,
        int scissorX,
        int scissorY,
        int scissorWidth,
        int scissorHeight
    )
    {
        private static GlState capture()
        {
            int[] scissor = new int[4];
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);

            return new GlState(
                GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                GL11.glGetInteger(GL11.GL_READ_BUFFER),
                GL11.glGetInteger(GL11.GL_DRAW_BUFFER),
                GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING),
                GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),
                GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                scissor[0], scissor[1], scissor[2], scissor[3]
            );
        }

        private void restore()
        {
            GL13.glActiveTexture(this.activeTexture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture2D);
            GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, this.packBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, this.packAlignment);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.readFramebuffer);
            GL11.glReadBuffer(this.readBuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
            GL11.glDrawBuffer(this.drawBuffer);
            GL11.glScissor(this.scissorX, this.scissorY, this.scissorWidth, this.scissorHeight);
            if (this.scissorEnabled)
            {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
            else
            {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
    }
}
