package mchorse.bbs_mod.api.client.render;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe immutable capture demand. A registered addon should return
 * {@link #none()} whenever it has no connected viewers.
 */
public final class BBSRenderSurfaceDemand
{
    public static final int MAX_WIDTH = 1920;
    public static final int MAX_HEIGHT = 1080;
    public static final int MAX_FRAMES_PER_SECOND = 120;

    private static final BBSRenderSurfaceDemand NONE = new BBSRenderSurfaceDemand(
        Collections.emptySet(),
        0,
        0,
        0,
        0,
        false
    );

    private final Set<BBSRenderSurfaceKind> kinds;
    private final int maxWidth;
    private final int maxHeight;
    private final int framesPerSecond;
    private final int jpegQuality;

    public BBSRenderSurfaceDemand(
        Set<BBSRenderSurfaceKind> kinds,
        int maxWidth,
        int maxHeight,
        int framesPerSecond,
        int jpegQuality
    )
    {
        this(kinds, maxWidth, maxHeight, framesPerSecond, jpegQuality, true);
    }

    private BBSRenderSurfaceDemand(
        Set<BBSRenderSurfaceKind> kinds,
        int maxWidth,
        int maxHeight,
        int framesPerSecond,
        int jpegQuality,
        boolean validate
    )
    {
        Objects.requireNonNull(kinds, "kinds");

        if (validate)
        {
            if (kinds.isEmpty())
            {
                throw new IllegalArgumentException("active surface demand must include at least one kind");
            }

            if (maxWidth < 2 || maxWidth > MAX_WIDTH || maxHeight < 2 || maxHeight > MAX_HEIGHT)
            {
                throw new IllegalArgumentException("surface dimensions are out of range");
            }

            if (framesPerSecond < 1 || framesPerSecond > MAX_FRAMES_PER_SECOND)
            {
                throw new IllegalArgumentException("surface frame rate is out of range");
            }

            if (jpegQuality < 30 || jpegQuality > 95)
            {
                throw new IllegalArgumentException("JPEG quality is out of range");
            }
        }

        this.kinds = kinds.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(kinds));
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.framesPerSecond = framesPerSecond;
        this.jpegQuality = jpegQuality;
    }

    public static BBSRenderSurfaceDemand none()
    {
        return NONE;
    }

    public static BBSRenderSurfaceDemand mobile(Set<BBSRenderSurfaceKind> kinds)
    {
        return new BBSRenderSurfaceDemand(kinds, 960, 540, 30, 68);
    }

    public static BBSRenderSurfaceDemand desktop(Set<BBSRenderSurfaceKind> kinds)
    {
        return new BBSRenderSurfaceDemand(kinds, 1280, 720, 60, 72);
    }

    public boolean isActive()
    {
        return !this.kinds.isEmpty();
    }

    public Set<BBSRenderSurfaceKind> kinds()
    {
        return this.kinds;
    }

    public int maxWidth()
    {
        return this.maxWidth;
    }

    public int maxHeight()
    {
        return this.maxHeight;
    }

    public int framesPerSecond()
    {
        return this.framesPerSecond;
    }

    public int jpegQuality()
    {
        return this.jpegQuality;
    }
}
