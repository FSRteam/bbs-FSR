package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class RgbSurfaceFrame implements AutoCloseable
{
    private final Set<BBSRenderSurfaceKind> kinds;
    private final BBSRenderSurfaceStamp stamp;
    private final long capturedAtNanos;
    private final int width;
    private final int height;
    private final int jpegQuality;
    private final RgbFramePool.Lease pixels;
    private final AtomicBoolean closed = new AtomicBoolean();

    RgbSurfaceFrame(
        Set<BBSRenderSurfaceKind> kinds,
        BBSRenderSurfaceStamp stamp,
        long capturedAtNanos,
        int width,
        int height,
        int jpegQuality,
        RgbFramePool.Lease pixels
    )
    {
        this.kinds = Collections.unmodifiableSet(EnumSet.copyOf(kinds));
        this.stamp = stamp;
        this.capturedAtNanos = capturedAtNanos;
        this.width = width;
        this.height = height;
        this.jpegQuality = jpegQuality;
        this.pixels = pixels;
    }

    Set<BBSRenderSurfaceKind> kinds()
    {
        return this.kinds;
    }

    BBSRenderSurfaceStamp stamp()
    {
        return this.stamp;
    }

    long capturedAtNanos()
    {
        return this.capturedAtNanos;
    }

    int width()
    {
        return this.width;
    }

    int height()
    {
        return this.height;
    }

    int jpegQuality()
    {
        return this.jpegQuality;
    }

    ByteBuffer pixels()
    {
        return this.pixels.readableBuffer();
    }

    @Override
    public void close()
    {
        if (this.closed.compareAndSet(false, true))
        {
            this.pixels.close();
        }
    }
}
