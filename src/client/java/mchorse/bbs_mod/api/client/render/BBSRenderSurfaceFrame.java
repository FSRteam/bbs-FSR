package mchorse.bbs_mod.api.client.render;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable encoded raster frame. The payload is deliberately opaque and
 * contains no OpenGL handles or mutable rendering objects.
 */
public final class BBSRenderSurfaceFrame
{
    public static final String JPEG_MEDIA_TYPE = "image/jpeg";

    private final Set<BBSRenderSurfaceKind> kinds;
    private final long generation;
    private final long sequence;
    private final long capturedAtNanos;
    private final int width;
    private final int height;
    private final boolean flipY;
    private final byte[] encodedBytes;

    public BBSRenderSurfaceFrame(
        Set<BBSRenderSurfaceKind> kinds,
        long sequence,
        long capturedAtNanos,
        int width,
        int height,
        boolean flipY,
        byte[] encodedBytes
    )
    {
        this(kinds, 0L, sequence, capturedAtNanos, width, height, flipY, encodedBytes);
    }

    /**
     * Creates a frame with an opaque stream generation. Core-produced frames
     * use a positive generation; zero remains reserved for callers of the
     * compatibility constructor above.
     */
    public BBSRenderSurfaceFrame(
        Set<BBSRenderSurfaceKind> kinds,
        long generation,
        long sequence,
        long capturedAtNanos,
        int width,
        int height,
        boolean flipY,
        byte[] encodedBytes
    )
    {
        Objects.requireNonNull(kinds, "kinds");
        Objects.requireNonNull(encodedBytes, "encodedBytes");

        if (kinds.isEmpty())
        {
            throw new IllegalArgumentException("surface frame must include at least one kind");
        }

        if (width < 1 || height < 1)
        {
            throw new IllegalArgumentException("surface frame dimensions must be positive");
        }

        if (generation < 0L)
        {
            throw new IllegalArgumentException("surface frame generation must not be negative");
        }

        if (sequence <= 0L)
        {
            throw new IllegalArgumentException("surface frame sequence must be positive");
        }

        this.kinds = Collections.unmodifiableSet(EnumSet.copyOf(kinds));
        this.generation = generation;
        this.sequence = sequence;
        this.capturedAtNanos = capturedAtNanos;
        this.width = width;
        this.height = height;
        this.flipY = flipY;
        this.encodedBytes = encodedBytes.clone();
    }

    public Set<BBSRenderSurfaceKind> kinds()
    {
        return this.kinds;
    }

    /**
     * Opaque capture-stream generation. A newer generation supersedes every
     * pending or encoded frame from an older one.
     */
    public long generation()
    {
        return this.generation;
    }

    /** Sequence is monotonic for the lifetime of this Minecraft client. */
    public long sequence()
    {
        return this.sequence;
    }

    public long capturedAtNanos()
    {
        return this.capturedAtNanos;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }

    public String mediaType()
    {
        return JPEG_MEDIA_TYPE;
    }

    /**
     * OpenGL returns rows bottom-up. Browser composition must apply the Y flip;
     * the encoder never changes STB's process-global flip setting.
     */
    public boolean flipY()
    {
        return this.flipY;
    }

    public int byteLength()
    {
        return this.encodedBytes.length;
    }

    public ByteBuffer encodedBytes()
    {
        return ByteBuffer.wrap(this.encodedBytes).asReadOnlyBuffer();
    }
}
