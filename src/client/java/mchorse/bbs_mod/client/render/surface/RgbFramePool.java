package mchorse.bbs_mod.client.render.surface;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Two-buffer native RGB pool shared by render readback and the encoder. */
final class RgbFramePool implements AutoCloseable
{
    private static final int MAX_BUFFERS = 2;

    private final ArrayList<Slot> slots = new ArrayList<>(MAX_BUFFERS);
    private boolean closed;

    synchronized Lease acquire(int size)
    {
        if (this.closed || size <= 0)
        {
            return null;
        }

        Slot replacement = null;

        for (Slot slot : this.slots)
        {
            if (slot.leased)
            {
                continue;
            }

            if (slot.buffer.capacity() >= size)
            {
                slot.leased = true;

                return new Lease(this, slot, size);
            }

            replacement = slot;
        }

        if (replacement != null)
        {
            ByteBuffer oldBuffer = replacement.buffer;
            ByteBuffer newBuffer = MemoryUtil.memAlloc(size);

            replacement.buffer = newBuffer;
            MemoryUtil.memFree(oldBuffer);
            replacement.leased = true;

            return new Lease(this, replacement, size);
        }

        if (this.slots.size() >= MAX_BUFFERS)
        {
            return null;
        }

        Slot slot = new Slot(MemoryUtil.memAlloc(size));

        slot.leased = true;
        this.slots.add(slot);

        return new Lease(this, slot, size);
    }

    private synchronized void release(Slot slot)
    {
        if (!slot.leased)
        {
            return;
        }

        slot.leased = false;

        if (this.closed && slot.buffer != null)
        {
            MemoryUtil.memFree(slot.buffer);
            slot.buffer = null;
        }
    }

    @Override
    public synchronized void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;

        for (Slot slot : this.slots)
        {
            if (!slot.leased && slot.buffer != null)
            {
                MemoryUtil.memFree(slot.buffer);
                slot.buffer = null;
            }
        }
    }

    static final class Lease implements AutoCloseable
    {
        private final RgbFramePool owner;
        private final Slot slot;
        private final int size;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(RgbFramePool owner, Slot slot, int size)
        {
            this.owner = owner;
            this.slot = slot;
            this.size = size;
        }

        ByteBuffer writableBuffer()
        {
            if (this.closed.get() || this.slot.buffer == null)
            {
                throw new IllegalStateException("RGB frame lease is closed");
            }

            ByteBuffer buffer = this.slot.buffer.duplicate();

            buffer.clear();
            buffer.limit(this.size);

            return buffer;
        }

        ByteBuffer readableBuffer()
        {
            return this.writableBuffer().asReadOnlyBuffer();
        }

        int size()
        {
            return this.size;
        }

        @Override
        public void close()
        {
            if (this.closed.compareAndSet(false, true))
            {
                this.owner.release(this.slot);
            }
        }
    }

    private static final class Slot
    {
        private ByteBuffer buffer;
        private boolean leased;

        private Slot(ByteBuffer buffer)
        {
            this.buffer = buffer;
        }
    }
}
