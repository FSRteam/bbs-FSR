package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceFrame;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Single daemon encoder with a capacity-one latest-frame input slot. RGB rows
 * remain bottom-up; the public frame tells browser composition to flip Y.
 */
final class StbJpegSurfaceEncoder implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-render-surface");

    private final AtomicReference<RgbSurfaceFrame> latest = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong highestSubmittedSequence = new AtomicLong();
    private final Semaphore available = new Semaphore(0);
    private final Consumer<BBSRenderSurfaceFrame> output;
    private final Thread worker;

    StbJpegSurfaceEncoder(Consumer<BBSRenderSurfaceFrame> output)
    {
        this.output = output;
        this.worker = new Thread(this::run, "bbs-surface-jpeg-encoder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    void submit(RgbSurfaceFrame frame)
    {
        if (!this.running.get() || !advanceSequence(this.highestSubmittedSequence, frame.stamp().sequence()))
        {
            frame.close();

            return;
        }

        RgbSurfaceFrame replaced = this.latest.getAndSet(frame);

        if (replaced != null)
        {
            replaced.close();
        }

        /* close() may race between the first running check and getAndSet(). Remove
         * and release our frame if shutdown won; a newer submit owns replacement. */
        if (!this.running.get())
        {
            if (this.latest.compareAndSet(frame, null))
            {
                frame.close();
            }

            return;
        }

        this.available.release();
    }

    void discardBeforeGeneration(long minimumGeneration)
    {
        while (true)
        {
            RgbSurfaceFrame pending = this.latest.get();

            if (pending == null || pending.stamp().generation() >= minimumGeneration)
            {
                return;
            }

            if (this.latest.compareAndSet(pending, null))
            {
                pending.close();

                return;
            }
        }
    }

    boolean isRunning()
    {
        return this.running.get();
    }

    /** Releases the replaceable queued RGB frame so a newer ready PBO can win. */
    boolean discardPending()
    {
        RgbSurfaceFrame pending = this.latest.getAndSet(null);

        if (pending == null)
        {
            return false;
        }

        pending.close();

        return true;
    }

    static boolean advanceSequence(AtomicLong highestSequence, long candidate)
    {
        if (highestSequence == null || candidate <= 0L)
        {
            return false;
        }

        while (true)
        {
            long previous = highestSequence.get();

            if (candidate <= previous)
            {
                return false;
            }

            if (highestSequence.compareAndSet(previous, candidate))
            {
                return true;
            }
        }
    }

    private void run()
    {
        ByteAccumulator accumulator = new ByteAccumulator();

        try (STBIWriteCallback callback = STBIWriteCallback.create((context, data, size) ->
        {
            accumulator.append(MemoryUtil.memByteBuffer(data, size));
        }))
        {
            while (this.running.get())
            {
                try
                {
                    this.available.acquire();
                }
                catch (InterruptedException e)
                {
                    if (!this.running.get())
                    {
                        break;
                    }

                    continue;
                }

                this.available.drainPermits();

                if (!this.running.get())
                {
                    break;
                }

                RgbSurfaceFrame frame = this.latest.getAndSet(null);

                if (frame == null)
                {
                    continue;
                }

                try (frame)
                {
                    BBSRenderSurfaceFrame encoded = this.encode(callback, accumulator, frame);

                    if (encoded != null && this.running.get())
                    {
                        this.output.accept(encoded);
                    }
                }
                catch (Exception | LinkageError e)
                {
                    LOGGER.error("[bbs-client-render-surface] JPEG encoding failed", e);
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-client-render-surface] STB JPEG encoder could not start", e);
        }
        finally
        {
            this.running.set(false);
            RgbSurfaceFrame pending = this.latest.getAndSet(null);

            if (pending != null)
            {
                pending.close();
            }
        }
    }

    private BBSRenderSurfaceFrame encode(STBIWriteCallback callback, ByteAccumulator accumulator, RgbSurfaceFrame frame)
    {
        int rawSize = Math.multiplyExact(Math.multiplyExact(frame.width(), frame.height()), 3);

        accumulator.reset(rawSize + 65_536);

        int success = STBImageWrite.stbi_write_jpg_to_func(
            callback,
            0L,
            frame.width(),
            frame.height(),
            3,
            frame.pixels(),
            frame.jpegQuality()
        );

        if (success == 0 || accumulator.overflowed())
        {
            return null;
        }

        return new BBSRenderSurfaceFrame(
            frame.kinds(),
            frame.stamp().generation(),
            frame.stamp().sequence(),
            frame.capturedAtNanos(),
            frame.width(),
            frame.height(),
            true,
            accumulator.toByteArray()
        );
    }

    @Override
    public void close()
    {
        if (!this.running.compareAndSet(true, false))
        {
            return;
        }

        RgbSurfaceFrame pending = this.latest.getAndSet(null);

        if (pending != null)
        {
            pending.close();
        }

        this.available.release();
        this.worker.interrupt();
    }

    private static final class ByteAccumulator
    {
        private byte[] bytes = new byte[65_536];
        private int size;
        private int maximum;
        private boolean overflowed;

        private void reset(int maximum)
        {
            this.size = 0;
            this.maximum = maximum;
            this.overflowed = false;
        }

        private void append(ByteBuffer source)
        {
            int length = source.remaining();
            int required = this.size + length;

            if (required < 0 || required > this.maximum)
            {
                this.overflowed = true;

                return;
            }

            if (required > this.bytes.length)
            {
                int capacity = this.bytes.length;

                while (capacity < required)
                {
                    capacity = Math.min(this.maximum, capacity << 1);

                    if (capacity < required && capacity == this.maximum)
                    {
                        this.overflowed = true;

                        return;
                    }
                }

                this.bytes = Arrays.copyOf(this.bytes, capacity);
            }

            source.get(source.position(), this.bytes, this.size, length);
            this.size = required;
        }

        private boolean overflowed()
        {
            return this.overflowed;
        }

        private byte[] toByteArray()
        {
            return Arrays.copyOf(this.bytes, this.size);
        }
    }
}
