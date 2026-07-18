package mchorse.bbs_mod.client.render.surface;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;

/** Collects the visible union of direct-GPU UI previews for one UI frame. */
public final class BBSFormPreviewCapture
{
    private static final ThreadLocal<Bounds> ACTIVE = new ThreadLocal<>();

    private BBSFormPreviewCapture()
    {}

    public static void beginFrame(int width, int height)
    {
        ACTIVE.set(new Bounds(width, height));
    }

    public static void include(UIContext context, int x1, int y1, int x2, int y2)
    {
        Bounds bounds = ACTIVE.get();

        if (bounds == null || context == null)
        {
            return;
        }

        int left = context.globalX(Math.min(x1, x2));
        int top = context.globalY(Math.min(y1, y2));
        int right = context.globalX(Math.max(x1, x2));
        int bottom = context.globalY(Math.max(y1, y2));
        Area viewport = context.getViewport();

        if (viewport != null)
        {
            left = Math.max(left, context.globalX(viewport.x));
            top = Math.max(top, context.globalY(viewport.y));
            right = Math.min(right, context.globalX(viewport.ex()));
            bottom = Math.min(bottom, context.globalY(viewport.ey()));
        }

        bounds.include(left, top, right, bottom);
    }

    public static Region finishFrame()
    {
        Bounds bounds = ACTIVE.get();

        ACTIVE.remove();

        return bounds == null ? null : bounds.region();
    }

    public static void abortFrame()
    {
        ACTIVE.remove();
    }

    public record Region(int x, int y, int width, int height)
    {}

    private static final class Bounds
    {
        private final int width;
        private final int height;
        private int x1 = Integer.MAX_VALUE;
        private int y1 = Integer.MAX_VALUE;
        private int x2 = Integer.MIN_VALUE;
        private int y2 = Integer.MIN_VALUE;

        private Bounds(int width, int height)
        {
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        private void include(int x1, int y1, int x2, int y2)
        {
            x1 = Math.max(0, Math.min(this.width, x1));
            y1 = Math.max(0, Math.min(this.height, y1));
            x2 = Math.max(0, Math.min(this.width, x2));
            y2 = Math.max(0, Math.min(this.height, y2));

            if (x2 <= x1 || y2 <= y1)
            {
                return;
            }

            this.x1 = Math.min(this.x1, x1);
            this.y1 = Math.min(this.y1, y1);
            this.x2 = Math.max(this.x2, x2);
            this.y2 = Math.max(this.y2, y2);
        }

        private Region region()
        {
            return this.x1 == Integer.MAX_VALUE
                ? null
                : new Region(this.x1, this.y1, this.x2 - this.x1, this.y2 - this.y1);
        }
    }
}
