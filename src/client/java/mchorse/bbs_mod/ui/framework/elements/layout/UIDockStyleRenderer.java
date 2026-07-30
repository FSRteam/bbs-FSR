package mchorse.bbs_mod.ui.framework.elements.layout;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

/** Shared theme-aware rendering for dock layout interaction surfaces. */
public final class UIDockStyleRenderer
{
    private UIDockStyleRenderer()
    {}

    public static void renderPanelDragHandle(UIContext context, Area area, boolean active)
    {
        int idleFill = BBSSettings.areaTintColor();
        int activeFill = BBSSettings.areaTintLightColor();
        int idleBorder = BBSSettings.splitterIdleColor();
        int activeBorder = BBSSettings.splitterActiveColor();

        if (idleFill == 0 && activeFill == 0 && idleBorder == 0 && activeBorder == 0)
        {
            int color = active ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.6F);

            context.batcher.icon(Icons.ALL_DIRECTIONS, color, area.mx(), area.y + area.h / 2 + 4, 0.5F, 0.5F);

            return;
        }

        int width = Math.min(44, Math.max(0, area.w - 4));
        int height = Math.min(14, Math.max(0, area.h - 2));

        if (width <= 0 || height <= 0)
        {
            return;
        }

        int x = area.mx() - width / 2;
        int y = area.my() - height / 2;
        int fill = active && activeFill != 0 ? activeFill : idleFill;
        int border = active && activeBorder != 0 ? activeBorder : idleBorder;

        if (fill == 0)
        {
            fill = BBSSettings.baseSurface();
        }

        if (border == 0)
        {
            context.batcher.roundedBox(x, y, width, height, BBSSettings.cornerWidget(), fill);
        }
        else
        {
            context.batcher.roundedFrame(x, y, width, height, BBSSettings.cornerWidget(), 1F, border, fill);
        }

        context.batcher.icon(Icons.ALL_DIRECTIONS, active ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.72F), area.mx(), area.my(), 0.5F, 0.5F);
    }

    public static void renderSplitter(UIContext context, Area area, boolean horizontal, boolean active, boolean legacyVisible)
    {
        int activeColor = BBSSettings.splitterActiveColor();
        int idleColor = BBSSettings.splitterIdleColor();

        if (activeColor == 0 && idleColor == 0)
        {
            if (!legacyVisible)
            {
                return;
            }

            int lineColor = BBSSettings.primaryColor(Colors.A100);

            if (horizontal)
            {
                int cy = area.y + area.h / 2;

                context.batcher.box(area.x, cy, area.ex(), cy + 1, lineColor);
            }
            else
            {
                int cx = area.x + area.w / 2;

                context.batcher.box(cx, area.y, cx + 1, area.ey(), lineColor);
            }

            return;
        }

        int color = active && activeColor != 0 ? activeColor : idleColor;

        if (color == 0)
        {
            return;
        }

        float radius = Math.min(BBSSettings.cornerWidget(), 1.5F);

        if (horizontal)
        {
            context.batcher.roundedBox(area.x + 2F, area.my() - 1.5F, Math.max(0F, area.w - 4F), 3F, radius, color);
        }
        else
        {
            context.batcher.roundedBox(area.mx() - 1.5F, area.y + 2F, 3F, Math.max(0F, area.h - 4F), radius, color);
        }
    }

    public static void renderDropZone(UIContext context, Area area, int zone, float edgeMargin)
    {
        int themedBorder = BBSSettings.dropBorderColor();
        int themedFill = BBSSettings.dropFillColor();

        if (themedBorder == 0 && themedFill == 0)
        {
            renderLegacyDropZone(context, area, zone, edgeMargin);

            return;
        }

        int border = themedBorder == 0 ? BBSSettings.primaryColor(Colors.A50) : themedBorder;
        int fill = themedFill == 0 ? BBSSettings.primaryColor(Colors.A25) : themedFill;
        int x = area.x;
        int y = area.y;
        int w = area.w;
        int h = area.h;

        switch (zone)
        {
            case EditorLayoutNode.EDGE_LEFT:
                w = Math.max(1, (int) (area.w * edgeMargin));
                break;
            case EditorLayoutNode.EDGE_RIGHT:
                w = Math.max(1, (int) (area.w * edgeMargin));
                x = area.ex() - w;
                break;
            case EditorLayoutNode.EDGE_TOP:
                h = Math.max(1, (int) (area.h * edgeMargin));
                break;
            case EditorLayoutNode.EDGE_BOTTOM:
                h = Math.max(1, (int) (area.h * edgeMargin));
                y = area.ey() - h;
                break;
        }

        context.batcher.roundedFrame(x, y, w, h, BBSSettings.cornerPanel(), 2F, border, fill);
    }

    private static void renderLegacyDropZone(UIContext context, Area area, int zone, float edgeMargin)
    {
        int border = BBSSettings.primaryColor(Colors.A50);
        int fill = BBSSettings.primaryColor(Colors.A25);

        if (zone < 0)
        {
            renderLegacyDropZoneRect(context, area, border, fill);

            return;
        }

        int strip = 2;

        switch (zone)
        {
            case EditorLayoutNode.EDGE_LEFT:
                context.batcher.box(area.x, area.y, area.x + (int) (area.w * edgeMargin), area.ey(), fill);
                context.batcher.box(area.x + (int) (area.w * edgeMargin) - strip, area.y, area.x + (int) (area.w * edgeMargin) + strip, area.ey(), border);
                break;
            case EditorLayoutNode.EDGE_RIGHT:
                context.batcher.box(area.ex() - (int) (area.w * edgeMargin), area.y, area.ex(), area.ey(), fill);
                context.batcher.box(area.ex() - (int) (area.w * edgeMargin) - strip, area.y, area.ex() - (int) (area.w * edgeMargin) + strip, area.ey(), border);
                break;
            case EditorLayoutNode.EDGE_TOP:
                context.batcher.box(area.x, area.y, area.ex(), area.y + (int) (area.h * edgeMargin), fill);
                context.batcher.box(area.x, area.y + (int) (area.h * edgeMargin) - strip, area.ex(), area.y + (int) (area.h * edgeMargin) + strip, border);
                break;
            case EditorLayoutNode.EDGE_BOTTOM:
                context.batcher.box(area.x, area.ey() - (int) (area.h * edgeMargin), area.ex(), area.ey(), fill);
                context.batcher.box(area.x, area.ey() - (int) (area.h * edgeMargin) - strip, area.ex(), area.ey() - (int) (area.h * edgeMargin) + strip, border);
                break;
            default:
                renderLegacyDropZoneRect(context, area, border, fill);
                break;
        }
    }

    private static void renderLegacyDropZoneRect(UIContext context, Area area, int border, int fill)
    {
        context.batcher.box(area.x, area.y, area.ex(), area.ey(), fill);

        int thickness = 2;

        context.batcher.box(area.x, area.y, area.ex(), area.y + thickness, border);
        context.batcher.box(area.x, area.ey() - thickness, area.ex(), area.ey(), border);
        context.batcher.box(area.x, area.y, area.x + thickness, area.ey(), border);
        context.batcher.box(area.ex() - thickness, area.y, area.ex(), area.ey(), border);
    }
}
