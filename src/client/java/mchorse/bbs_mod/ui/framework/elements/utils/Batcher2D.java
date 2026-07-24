package mchorse.bbs_mod.ui.framework.elements.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.api.client.ui.BBSUiColoredMesh;
import mchorse.bbs_mod.api.client.ui.BBSUiTexturedMesh;
import mchorse.bbs_mod.api.client.ui.BBSUiTexturedMeshVertex;
import mchorse.bbs_mod.api.client.ui.BBSUiUnsupportedReason;
import mchorse.bbs_mod.api.client.ui.BBSUiVertex;
import mchorse.bbs_mod.client.ui.mirror.BBSUiFrameRecorder;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIArt;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class Batcher2D
{
    private static final float TEXT_ALPHA_SKIP_THRESHOLD = 0.03F;
    private static final int ROUNDED_CORNER_STEPS = 8;
    private static final float[] ROUNDED_RECT_ARC_SIN = new float[ROUNDED_CORNER_STEPS + 1];
    private static final float[] ROUNDED_RECT_ARC_COS = new float[ROUNDED_CORNER_STEPS + 1];
    private static FontRenderer fontRenderer = new FontRenderer();

    private GuiGraphics context;
    private FontRenderer font;
    private float globalAlpha = 1F;
    private float[] alphaStack = new float[8];
    private int alphaStackSize;

    public static FontRenderer getDefaultTextRenderer()
    {
        fontRenderer.setRenderer(Minecraft.getInstance().font);

        return fontRenderer;
    }

    public Batcher2D(GuiGraphics context)
    {
        this.context = context;
        this.font = getDefaultTextRenderer();
    }

    public GuiGraphics getContext()
    {
        return this.context;
    }

    public void setContext(GuiGraphics context)
    {
        this.context = context;
    }

    public FontRenderer getFont()
    {
        return this.font;
    }

    public void pushAlpha(float mul)
    {
        if (this.alphaStackSize >= this.alphaStack.length)
        {
            this.alphaStack = Arrays.copyOf(this.alphaStack, this.alphaStack.length * 2);
        }

        this.alphaStack[this.alphaStackSize++] = this.globalAlpha;
        this.globalAlpha *= Math.max(0F, mul);
    }

    public void popAlpha()
    {
        if (this.alphaStackSize <= 0)
        {
            this.globalAlpha = 1F;

            return;
        }

        this.globalAlpha = this.alphaStack[--this.alphaStackSize];
    }

    /**
     * Frame-start safety net: clears any alpha multiplier a caller leaked
     * through an exception path, so a missed popAlpha never bleeds into
     * subsequent frames.
     */
    public void resetAlpha()
    {
        this.globalAlpha = 1F;
        this.alphaStackSize = 0;
    }

    /* Screen space clipping */

    public void clip(Area area, UIContext context)
    {
        this.clip(area.x, area.y, area.w, area.h, context);
    }

    public void clip(int x, int y, int w, int h, UIContext context)
    {
        this.clip(context.globalX(x), context.globalY(y), w, h, context.menu.width, context.menu.height);
    }

    /**
     * Scissor (clip) the screen
     */
    public void clip(int x, int y, int w, int h, int sw, int sh)
    {
        BBSUiFrameRecorder.recordClipPush(x, y, w, h);
        this.context.enableScissor(x, y, x + w, y + h);
    }

    public void unclip(UIContext context)
    {
        this.unclip(context.menu.width, context.menu.height);
    }

    public void unclip(int sw, int sh)
    {
        BBSUiFrameRecorder.recordClipPop();
        this.context.disableScissor();
    }

    /* Solid rectangles */

    public void normalizedBox(float x1, float y1, float x2, float y2, int color)
    {
        float temp = x1;

        x1 = Math.min(x1, x2);
        x2 = Math.max(temp, x2);

        temp = y1;

        y1 = Math.min(y1, y2);
        y2 = Math.max(temp, y2);

        this.box(x1, y1, x2, y2, color);
    }

    public void box(float x1, float y1, float x2, float y2, int color)
    {
        this.box(x1, y1, x2 - x1, y2 - y1, color, color, color, color);
    }

    public void box(float x, float y, float w, float h, int color1, int color2, int color3, int color4)
    {
        color1 = this.applyGlobalAlpha(color1);
        color2 = this.applyGlobalAlpha(color2);
        color3 = this.applyGlobalAlpha(color3);
        color4 = this.applyGlobalAlpha(color4);

        Matrix4f matrix4f = this.context.pose().last().pose();

        flushBeforeTesselator();

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        this.fillRect(builder, matrix4f, x, y, w, h, color1, color2, color3, color4);

        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    public void fillRect(BufferBuilder builder, Matrix4f matrix4f, float x, float y, float w, float h, int color1, int color2, int color3, int color4)
    {
        /* This is the leaf used by graph/keyframe renderers that append to an
         * existing BufferBuilder. Recording here avoids silently missing those
         * direct batched quads while box() still records exactly once. */
        BBSUiFrameRecorder.recordQuad(matrix4f, x, y, w, h, color1, color2, color3, color4);

        /* c1 ---- c2
         * |        |
         * c3 ---- c4 */
        builder.addVertex(matrix4f, x, y, 0).setColor(color1);
        builder.addVertex(matrix4f, x, y + h, 0).setColor(color3);
        builder.addVertex(matrix4f, x + w, y + h, 0).setColor(color4);
        builder.addVertex(matrix4f, x + w, y, 0).setColor(color2);
    }

    private static float clampRoundedRectRadius(float w, float h, float radius)
    {
        return Math.max(0F, Math.min(radius, Math.min(w * 0.5F, h * 0.5F)));
    }

    private static boolean hasRoundedRectAxisGeometry(float w, float h, float radius)
    {
        float r = clampRoundedRectRadius(w, h, radius);

        return w - 2F * r > 0F || h - 2F * r > 0F;
    }

    private void fillRectTriangles(BufferBuilder builder, Matrix4f matrix4f, ColoredMeshBatch mirror, float x, float y, float w, float h, int color)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        if (mirror != null)
        {
            mirror.addQuad(
                x, y + h, color,
                x + w, y + h, color,
                x + w, y, color,
                x, y, color
            );
        }

        builder.addVertex(matrix4f, x, y + h, 0F).setColor(color);
        builder.addVertex(matrix4f, x + w, y + h, 0F).setColor(color);
        builder.addVertex(matrix4f, x + w, y, 0F).setColor(color);
        builder.addVertex(matrix4f, x, y + h, 0F).setColor(color);
        builder.addVertex(matrix4f, x + w, y, 0F).setColor(color);
        builder.addVertex(matrix4f, x, y, 0F).setColor(color);
    }

    private void fillRoundedRectAxisTriangles(BufferBuilder builder, Matrix4f matrix4f, ColoredMeshBatch mirror, float x, float y, float w, float h, float radius, int color)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        float r = clampRoundedRectRadius(w, h, radius);

        if (r < 0.5F)
        {
            this.fillRectTriangles(builder, matrix4f, mirror, x, y, w, h, color);

            return;
        }

        float innerW = w - 2F * r;
        float innerH = h - 2F * r;

        if (innerW > 0F && innerH > 0F)
        {
            this.fillRectTriangles(builder, matrix4f, mirror, x + r, y + r, innerW, innerH, color);
        }

        if (innerW > 0F)
        {
            this.fillRectTriangles(builder, matrix4f, mirror, x + r, y, innerW, r, color);
            this.fillRectTriangles(builder, matrix4f, mirror, x + r, y + h - r, innerW, r, color);
        }

        if (innerH > 0F)
        {
            this.fillRectTriangles(builder, matrix4f, mirror, x, y + r, r, innerH, color);
            this.fillRectTriangles(builder, matrix4f, mirror, x + w - r, y + r, r, innerH, color);
        }
    }

    public void roundedBox(float x, float y, float w, float h, float radius, int color)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        float r = clampRoundedRectRadius(w, h, radius);

        if (r < 0.5F)
        {
            this.box(x, y, x + w, y + h, color);

            return;
        }

        color = this.applyGlobalAlpha(color);

        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;
        Matrix4f matrix4f = this.context.pose().last().pose();

        if (hasRoundedRectAxisGeometry(w, h, r))
        {
            flushBeforeTesselator();

            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            ColoredMeshBatch mirror = new ColoredMeshBatch(matrix4f);

            this.fillRoundedRectAxisTriangles(builder, matrix4f, mirror, x, y, w, h, r, color);
            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
            this.restoreDepth();
        }

        Texture mask = UIArt.roundedRectMask();

        flushBeforeTesselator();

        if (mask != null)
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            TexturedMeshBatch mirror = new TexturedMeshBatch(matrix4f, mask);

            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x0 + r, y0 + r, r, color, 0, x0, y0, x1, y1);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x1 - r, y0 + r, r, color, 1, x0, y0, x1, y1);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x1 - r, y1 - r, r, color, 2, x0, y0, x1, y1);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x0 + r, y1 - r, r, color, 3, x0, y0, x1, y1);
            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, mask.id);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }
        else
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            ColoredMeshBatch mirror = new ColoredMeshBatch(matrix4f);

            this.fillRoundedCornerFan(builder, matrix4f, mirror, x0 + r, y0 + r, r, color, 0);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, x1 - r, y0 + r, r, color, 1);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, x1 - r, y1 - r, r, color, 2);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, x0 + r, y1 - r, r, color, 3);
            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        this.restoreDepth();
    }

    public void roundedFrame(float x, float y, float w, float h, float radius, float inset, int borderColor, int fillColor)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        float outerR = clampRoundedRectRadius(w, h, radius);

        if (outerR < 0.5F)
        {
            this.box(x, y, x + w, y + h, borderColor);

            float innerX = x + inset;
            float innerY = y + inset;
            float innerW = w - 2F * inset;
            float innerH = h - 2F * inset;

            if (inset > 0F && innerW > 0F && innerH > 0F)
            {
                this.box(innerX, innerY, innerX + innerW, innerY + innerH, fillColor);
            }

            return;
        }

        float innerX = x + inset;
        float innerY = y + inset;
        float innerW = w - 2F * inset;
        float innerH = h - 2F * inset;

        if (inset <= 0F || innerW <= 0F || innerH <= 0F)
        {
            this.roundedBox(x, y, w, h, outerR, borderColor);

            return;
        }

        borderColor = this.applyGlobalAlpha(borderColor);
        fillColor = this.applyGlobalAlpha(fillColor);

        float innerR = clampRoundedRectRadius(innerW, innerH, Math.max(0.5F, outerR - inset));
        Matrix4f matrix4f = this.context.pose().last().pose();

        if (hasRoundedRectAxisGeometry(w, h, outerR) || hasRoundedRectAxisGeometry(innerW, innerH, innerR))
        {
            flushBeforeTesselator();

            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            ColoredMeshBatch mirror = new ColoredMeshBatch(matrix4f);

            this.fillRoundedRectAxisTriangles(builder, matrix4f, mirror, x, y, w, h, outerR, borderColor);
            this.fillRoundedRectAxisTriangles(builder, matrix4f, mirror, innerX, innerY, innerW, innerH, innerR, fillColor);
            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
            this.restoreDepth();
        }

        Texture mask = UIArt.roundedRectMask();

        flushBeforeTesselator();

        if (mask != null)
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            TexturedMeshBatch mirror = new TexturedMeshBatch(matrix4f, mask);

            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x + outerR, y + outerR, outerR, borderColor, 0, x, y, x + w, y + h);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x + w - outerR, y + outerR, outerR, borderColor, 1, x, y, x + w, y + h);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x + w - outerR, y + h - outerR, outerR, borderColor, 2, x, y, x + w, y + h);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, x + outerR, y + h - outerR, outerR, borderColor, 3, x, y, x + w, y + h);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, innerX + innerR, innerY + innerR, innerR, fillColor, 0, innerX, innerY, innerX + innerW, innerY + innerH);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, innerX + innerW - innerR, innerY + innerR, innerR, fillColor, 1, innerX, innerY, innerX + innerW, innerY + innerH);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, innerX + innerW - innerR, innerY + innerH - innerR, innerR, fillColor, 2, innerX, innerY, innerX + innerW, innerY + innerH);
            this.fillRoundedCornerFanMask(builder, matrix4f, mirror, innerX + innerR, innerY + innerH - innerR, innerR, fillColor, 3, innerX, innerY, innerX + innerW, innerY + innerH);
            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, mask.id);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }
        else
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            ColoredMeshBatch mirror = new ColoredMeshBatch(matrix4f);

            this.fillRoundedCornerFan(builder, matrix4f, mirror, x + outerR, y + outerR, outerR, borderColor, 0);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, x + w - outerR, y + outerR, outerR, borderColor, 1);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, x + w - outerR, y + h - outerR, outerR, borderColor, 2);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, x + outerR, y + h - outerR, outerR, borderColor, 3);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, innerX + innerR, innerY + innerR, innerR, fillColor, 0);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, innerX + innerW - innerR, innerY + innerR, innerR, fillColor, 1);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, innerX + innerW - innerR, innerY + innerH - innerR, innerR, fillColor, 2);
            this.fillRoundedCornerFan(builder, matrix4f, mirror, innerX + innerR, innerY + innerH - innerR, innerR, fillColor, 3);
            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        this.restoreDepth();
    }

    public void filledCircle(float cx, float cy, float radius, int color, int segments)
    {
        if (radius <= 0F)
        {
            return;
        }

        color = this.applyGlobalAlpha(color);

        int n = Math.max(3, segments);
        Matrix4f matrix4f = this.context.pose().last().pose();
        Texture mask = UIArt.filledCircleMask();

        flushBeforeTesselator();

        if (mask != null)
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);
            TexturedMeshBatch mirror = new TexturedMeshBatch(matrix4f, mask);

            builder.addVertex(matrix4f, cx, cy, 0F).setUv(0.5F, 0.5F).setColor(color);

            double prevAngle = -Math.PI / 2D;
            float prevX = (float) (cx - Math.cos(prevAngle) * radius);
            float prevY = (float) (cy + Math.sin(prevAngle) * radius);
            float prevU = 0.5F + 0.5F * (prevX - cx) / radius;
            float prevV = 0.5F + 0.5F * (prevY - cy) / radius;

            builder.addVertex(matrix4f, prevX, prevY, 0F).setUv(prevU, prevV).setColor(color);

            for (int i = 1; i <= n; i++)
            {
                double angle = i / (double) n * Math.PI * 2D - Math.PI / 2D;
                float px = (float) (cx - Math.cos(angle) * radius);
                float py = (float) (cy + Math.sin(angle) * radius);
                float u = 0.5F + 0.5F * (px - cx) / radius;
                float v = 0.5F + 0.5F * (py - cy) / radius;

                builder.addVertex(matrix4f, px, py, 0F).setUv(u, v).setColor(color);
                mirror.addTriangle(
                    cx, cy, 0.5F * mask.width, 0.5F * mask.height, color,
                    prevX, prevY, prevU * mask.width, prevV * mask.height, color,
                    px, py, u * mask.width, v * mask.height, color
                );

                prevX = px;
                prevY = py;
                prevU = u;
                prevV = v;
            }

            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, mask.id);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }
        else
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            ColoredMeshBatch mirror = new ColoredMeshBatch(matrix4f);

            builder.addVertex(matrix4f, cx, cy, 0F).setColor(color);

            double prevAngle = -Math.PI / 2D;
            float prevX = (float) (cx - Math.cos(prevAngle) * radius);
            float prevY = (float) (cy + Math.sin(prevAngle) * radius);

            builder.addVertex(matrix4f, prevX, prevY, 0F).setColor(color);

            for (int i = 1; i <= n; i++)
            {
                double angle = i / (double) n * Math.PI * 2D - Math.PI / 2D;
                float px = (float) (cx - Math.cos(angle) * radius);
                float py = (float) (cy + Math.sin(angle) * radius);

                builder.addVertex(matrix4f, px, py, 0F).setColor(color);
                mirror.addTriangle(cx, cy, color, prevX, prevY, color, px, py, color);

                prevX = px;
                prevY = py;
            }

            mirror.finish();

            this.prepareUiBlend();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        this.restoreDepth();
    }

    public void bevelBox(int x1, int y1, int x2, int y2, int fill, boolean shadow, boolean border)
    {
        if (border)
        {
            this.box(x1, y1, x2, y2, Colors.A100);

            x1++;
            y1++;
            x2--;
            y2--;
        }

        this.box(x1, y1, x2, y2, fill);

        if (!BBSSettings.interfaceShadows.get())
        {
            return;
        }

        int light = Colors.lerp(fill, Colors.WHITE, 0.35F);

        this.box(x1, y1, x2, y1 + 1, light);
        this.box(x1, y1, x1 + 1, y2, light);

        if (shadow)
        {
            this.box(x1, y2 - 2, x2, y2, Colors.lerp(fill, Colors.A100, 0.4F));
        }
    }

    public void dropShadow(int left, int top, int right, int bottom, int offset, int opaque, int shadow)
    {
        opaque = this.applyGlobalAlpha(opaque);
        shadow = this.applyGlobalAlpha(shadow);

        left -= offset;
        top -= offset;
        right += offset;
        bottom += offset;

        Matrix4f matrix4f = this.context.pose().last().pose();
        ArrayList<BBSUiVertex> mirror = new ArrayList<>(30);

        addColoredQuad(mirror, left + offset, top + offset, opaque, left + offset, bottom - offset, opaque,
            right - offset, bottom - offset, opaque, right - offset, top + offset, opaque);
        addColoredQuad(mirror, left, top, shadow, left + offset, top + offset, opaque,
            right - offset, top + offset, opaque, right, top, shadow);
        addColoredQuad(mirror, left + offset, bottom - offset, opaque, left, bottom, shadow,
            right, bottom, shadow, right - offset, bottom - offset, opaque);
        addColoredQuad(mirror, left, top, shadow, left, bottom, shadow,
            left + offset, bottom - offset, opaque, left + offset, top + offset, opaque);
        addColoredQuad(mirror, right - offset, top + offset, opaque, right - offset, bottom - offset, opaque,
            right, bottom, shadow, right, top, shadow);
        recordColoredTriangles(matrix4f, mirror);

        flushBeforeTesselator();

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        /* Draw opaque part */
        builder.addVertex(matrix4f, left + offset, top + offset, 0).setColor(opaque);
        builder.addVertex(matrix4f,left + offset, bottom - offset, 0).setColor(opaque);
        builder.addVertex(matrix4f, right - offset, bottom - offset, 0).setColor(opaque);
        builder.addVertex(matrix4f, right - offset, top + offset, 0).setColor(opaque);

        /* Draw top shadow */
        builder.addVertex(matrix4f, left, top, 0).setColor(shadow);
        builder.addVertex(matrix4f,left + offset, top + offset, 0).setColor(opaque);
        builder.addVertex(matrix4f, right - offset, top + offset, 0).setColor(opaque);
        builder.addVertex(matrix4f, right, top, 0).setColor(shadow);

        /* Draw bottom shadow */
        builder.addVertex(matrix4f, left + offset, bottom - offset, 0).setColor(opaque);
        builder.addVertex(matrix4f,left, bottom, 0).setColor(shadow);
        builder.addVertex(matrix4f, right, bottom, 0).setColor(shadow);
        builder.addVertex(matrix4f, right - offset, bottom - offset, 0).setColor(opaque);

        /* Draw left shadow */
        builder.addVertex(matrix4f, left, top, 0).setColor(shadow);
        builder.addVertex(matrix4f, left, bottom, 0).setColor(shadow);
        builder.addVertex(matrix4f, left + offset, bottom - offset, 0).setColor(opaque);
        builder.addVertex(matrix4f,left + offset, top + offset, 0).setColor(opaque);

        /* Draw right shadow */
        builder.addVertex(matrix4f, right - offset, top + offset, 0).setColor(opaque);
        builder.addVertex(matrix4f, right - offset, bottom - offset, 0).setColor(opaque);
        builder.addVertex(matrix4f, right, bottom, 0).setColor(shadow);
        builder.addVertex(matrix4f,right, top, 0).setColor(shadow);

        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    /* Gradients */

    public void gradientHBox(float x1, float y1, float x2, float y2, int leftColor, int rightColor)
    {
        this.box(x1, y1, x2 - x1, y2 - y1, leftColor, rightColor, leftColor, rightColor);
    }

    public void gradientVBox(float x1, float y1, float x2, float y2, int topColor, int bottomColor)
    {
        this.box(x1, y1, x2 - x1, y2 - y1, topColor, topColor, bottomColor, bottomColor);
    }

    public void dropCircleShadow(int x, int y, int radius, int segments, int opaque, int shadow)
    {
        opaque = this.applyGlobalAlpha(opaque);
        shadow = this.applyGlobalAlpha(shadow);

        Matrix4f matrix4f = this.context.pose().last().pose();
        ColoredMeshBatch mirror = new ColoredMeshBatch(matrix4f);

        for (int i = 0; i < segments; i ++)
        {
            double a1 = i / (double) segments * Math.PI * 2 - Math.PI / 2;
            double a2 = (i + 1) / (double) segments * Math.PI * 2 - Math.PI / 2;

            mirror.addTriangle(
                x, y, opaque,
                (float) (x - Math.cos(a1) * radius), (float) (y + Math.sin(a1) * radius), shadow,
                (float) (x - Math.cos(a2) * radius), (float) (y + Math.sin(a2) * radius), shadow
            );
        }

        mirror.finish();

        flushBeforeTesselator();

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        builder.addVertex(matrix4f, x, y, 0F).setColor(opaque);

        for (int i = 0; i <= segments; i ++)
        {
            double a = i / (double) segments * Math.PI * 2 - Math.PI / 2;

            builder.addVertex(matrix4f, (float) (x - Math.cos(a) * radius), (float) (y + Math.sin(a) * radius), 0F).setColor(shadow);
        }

        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    public void dropCircleShadow(int x, int y, int radius, int offset, int segments, int opaque, int shadow)
    {
        if (offset >= radius)
        {
            this.dropCircleShadow(x, y, radius, segments, opaque, shadow);

            return;
        }

        opaque = this.applyGlobalAlpha(opaque);
        shadow = this.applyGlobalAlpha(shadow);

        Matrix4f matrix4f = this.context.pose().last().pose();
        ColoredMeshBatch baseMirror = new ColoredMeshBatch(matrix4f);

        for (int i = 0; i < segments; i ++)
        {
            double a1 = i / (double) segments * Math.PI * 2 - Math.PI / 2;
            double a2 = (i + 1) / (double) segments * Math.PI * 2 - Math.PI / 2;

            baseMirror.addTriangle(
                x, y, opaque,
                (float) (x - Math.cos(a1) * offset), (float) (y + Math.sin(a1) * offset), opaque,
                (float) (x - Math.cos(a2) * offset), (float) (y + Math.sin(a2) * offset), opaque
            );
        }

        baseMirror.finish();

        /* Draw opaque base */
        flushBeforeTesselator();

        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        builder.addVertex(matrix4f, x, y, 0F).setColor(opaque);

        for (int i = 0; i <= segments; i ++)
        {
            double a = i / (double) segments * Math.PI * 2 - Math.PI / 2;

            builder.addVertex(matrix4f, (int) (x - Math.cos(a) * offset), (int) (y + Math.sin(a) * offset), 0F).setColor(opaque);
        }

        BufferUploader.drawWithShader(builder.buildOrThrow());

        /* Draw outer shadow */
        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        ColoredMeshBatch shadowMirror = new ColoredMeshBatch(matrix4f);

        for (int i = 0; i < segments; i ++)
        {
            double alpha1 = i / (double) segments * Math.PI * 2 - Math.PI / 2;
            double alpha2 = (i + 1) / (double) segments * Math.PI * 2 - Math.PI / 2;

            shadowMirror.addTriangle(
                (float) (x - Math.cos(alpha2) * offset), (float) (y + Math.sin(alpha2) * offset), opaque,
                (float) (x - Math.cos(alpha1) * offset), (float) (y + Math.sin(alpha1) * offset), opaque,
                (float) (x - Math.cos(alpha1) * radius), (float) (y + Math.sin(alpha1) * radius), shadow
            );
            shadowMirror.addTriangle(
                (float) (x - Math.cos(alpha2) * offset), (float) (y + Math.sin(alpha2) * offset), opaque,
                (float) (x - Math.cos(alpha1) * radius), (float) (y + Math.sin(alpha1) * radius), shadow,
                (float) (x - Math.cos(alpha2) * radius), (float) (y + Math.sin(alpha2) * radius), shadow
            );

            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha2) * offset), (float) (y + Math.sin(alpha2) * offset), 0F).setColor(opaque);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha1) * offset), (float) (y + Math.sin(alpha1) * offset), 0F).setColor(opaque);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha1) * radius), (float) (y + Math.sin(alpha1) * radius), 0F).setColor(shadow);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha2) * offset), (float) (y + Math.sin(alpha2) * offset), 0F).setColor(opaque);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha1) * radius), (float) (y + Math.sin(alpha1) * radius), 0F).setColor(shadow);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha2) * radius), (float) (y + Math.sin(alpha2) * radius), 0F).setColor(shadow);
        }

        shadowMirror.finish();

        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    /* Outline methods */

    public void outlineCenter(float x, float y, float offset, int color)
    {
        this.outlineCenter(x, y, offset, color, 1);
    }

    public void outlineCenter(float x, float y, float offset, int color, int border)
    {
        this.outline(x - offset, y - offset, x + offset, y + offset, color, border);
    }

    public void outline(float x1, float y1, float x2, float y2, int color)
    {
        this.outline(x1, y1, x2, y2, color, 1);
    }

    /**
     * Draw rectangle outline with given border.
     */
    public void outline(float x1, float y1, float x2, float y2, int color, int border)
    {
        this.box(x1, y1, x1 + border, y2, color);
        this.box(x2 - border, y1, x2, y2, color);
        this.box(x1 + border, y1, x2 - border, y1 + border, color);
        this.box(x1 + border, y2 - border, x2 - border, y2, color);
    }

    /* Icon */

    private static int darkenWhite(int color)
    {
        return (color & 0xFFFFFF) == 0xFFFFFF ? (color & 0xFF000000) : color;
    }

    public void icon(Icon icon, float x, float y)
    {
        this.icon(icon, Colors.WHITE, x, y);
    }

    public void icon(Icon icon, int color, float x, float y)
    {
        this.icon(icon, color, x, y, 0F, 0F);
    }

    public void icon(Icon icon, float x, float y, float ax, float ay)
    {
        this.icon(icon, Colors.WHITE, x, y, ax, ay);
    }

    public void icon(Icon icon, int color, float x, float y, float ax, float ay)
    {
        if (icon.texture == null)
        {
            return;
        }

        if (BBSSettings.isLightTheme())
        {
            color = darkenWhite(color);
        }

        x -= icon.w * ax;
        y -= icon.h * ay;

        this.texturedBox(BBSModClient.getTextures().getTexture(ThemeManager.resolveIconAtlas(icon.texture)), color, x, y, icon.w, icon.h, icon.x, icon.y, icon.x + icon.w, icon.y + icon.h, icon.textureW, icon.textureH);
    }

    public void iconArea(Icon icon, float x, float y, float w, float h)
    {
        this.iconArea(icon, Colors.WHITE, x, y, w, h);
    }

    public void iconArea(Icon icon, int color, float x, float y, float w, float h)
    {
        if (BBSSettings.isLightTheme())
        {
            color = darkenWhite(color);
        }

        this.texturedArea(BBSModClient.getTextures().getTexture(ThemeManager.resolveIconAtlas(icon.texture)), color, x, y, w, h, icon.x, icon.y, icon.w, icon.h, icon.textureW, icon.textureH);
    }

    public void outlinedIcon(Icon icon, float x, float y, float ax, float ay)
    {
        this.outlinedIcon(icon, x, y, Colors.WHITE, ax, ay);
    }

    /**
     * Draw an icon with a black outline.
     */
    public void outlinedIcon(Icon icon, float x, float y, int color, float ax, float ay)
    {
        this.icon(icon, Colors.A100, x - 1, y, ax, ay);
        this.icon(icon, Colors.A100, x + 1, y, ax, ay);
        this.icon(icon, Colors.A100, x, y - 1, ax, ay);
        this.icon(icon, Colors.A100, x, y + 1, ax, ay);
        this.icon(icon, color, x, y, ax, ay);
    }

    /* Textured box */

    public void fullTexturedBox(Texture texture, float x, float y, float w, float h)
    {
        this.fullTexturedBox(texture, Colors.WHITE, x, y, w, h);
    }

    public void fullTexturedBox(Texture texture, int color, float x, float y, float w, float h)
    {
        this.texturedBox(texture, color, x, y, w, h, 0, 0, w, h, (int) w, (int) h);
    }

    public void texturedBox(Texture texture, int color, float x, float y, float w, float h, float u1, float v1, float u2, float v2)
    {
        this.texturedBox(texture, color, x, y, w, h, u1, v1, u2, v2, texture.width, texture.height);
    }

    public void texturedBox(Texture texture, int color, float x, float y, float w, float h, float u, float v)
    {
        this.texturedBox(texture, color, x, y, w, h, u, v, u + w, v + h, texture.width, texture.height);
    }

    public void texturedBox(Texture texture, int color, float x, float y, float w, float h, float u1, float v1, float u2, float v2, int textureW, int textureH)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        color = this.applyGlobalAlpha(color);

        Matrix4f matrix = this.context.pose().last().pose();

        BBSUiFrameRecorder.recordTextureQuad(
            matrix,
            texture,
            x,
            y,
            w,
            h,
            u1,
            v1,
            u2,
            v2,
            textureW,
            textureH,
            color
        );

        flushBeforeTesselator();

        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.id);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        this.fillTexturedBox(builder, matrix, color, x, y, w, h, u1, v1, u2, v2, textureW, textureH);

        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    public void texturedBox(int texture, int color, float x, float y, float w, float h, float u1, float v1, float u2, float v2, int textureW, int textureH)
    {
        color = this.applyGlobalAlpha(color);

        this.texturedBoxNative(
            GameRenderer::getPositionTexColorShader,
            texture, color, x, y, w, h, u1, v1, u2, v2, textureW, textureH,
            BBSUiUnsupportedReason.RAW_TEXTURE
        );
    }

    /**
     * Draw a native dynamic texture while recording only its stable logical
     * surface placement for UI mirror consumers.
     */
    public void surfaceBox(
        BBSRenderSurfaceKind surfaceKind,
        int texture,
        int color,
        float x,
        float y,
        float w,
        float h,
        float u1,
        float v1,
        float u2,
        float v2,
        int textureW,
        int textureH
    )
    {
        if (surfaceKind == null || w <= 0F || h <= 0F || textureW <= 0 || textureH <= 0)
        {
            return;
        }

        color = this.applyGlobalAlpha(color);

        Matrix4f matrix = this.context.pose().last().pose();

        BBSUiFrameRecorder.recordSurfaceQuad(
            matrix,
            surfaceKind,
            x,
            y,
            w,
            h,
            u1 / textureW,
            v1 / textureH,
            u2 / textureW,
            v2 / textureH,
            color
        );

        this.texturedBoxNative(
            GameRenderer::getPositionTexColorShader,
            texture, color, x, y, w, h, u1, v1, u2, v2, textureW, textureH,
            null
        );
    }

    public void texturedBox(Supplier<ShaderInstance> shader, int texture, int color, float x, float y, float w, float h, float u1, float v1, float u2, float v2, int textureW, int textureH)
    {
        color = this.applyGlobalAlpha(color);

        this.texturedBoxNative(
            shader,
            texture, color, x, y, w, h, u1, v1, u2, v2, textureW, textureH,
            BBSUiUnsupportedReason.CUSTOM_SHADER
        );
    }

    private void texturedBoxNative(
        Supplier<ShaderInstance> shader,
        int texture,
        int color,
        float x,
        float y,
        float w,
        float h,
        float u1,
        float v1,
        float u2,
        float v2,
        int textureW,
        int textureH,
        BBSUiUnsupportedReason unsupportedReason
    )
    {
        if (w <= 0F || h <= 0F)
        {
            return;
        }

        if (unsupportedReason != null)
        {
            BBSUiFrameRecorder.recordUnsupported(unsupportedReason);
        }

        Matrix4f matrix = this.context.pose().last().pose();

        flushBeforeTesselator();

        this.prepareUiBlend();
        RenderSystem.setShader(shader);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        this.fillTexturedBox(builder, matrix, color, x, y, w, h, u1, v1, u2, v2, textureW, textureH);

        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    private void fillTexturedBox(BufferBuilder builder, Matrix4f matrix, int color, float x, float y, float w, float h, float u1, float v1, float u2, float v2, int textureW, int textureH)
    {
        builder.addVertex(matrix, x, y + h, 0F).setUv(u1 / (float) textureW, v2 / (float) textureH).setColor(color);
        builder.addVertex(matrix, x + w, y + h, 0F).setUv(u2 / (float) textureW, v2 / (float) textureH).setColor(color);
        builder.addVertex(matrix, x + w, y, 0F).setUv(u2 / (float) textureW, v1 / (float) textureH).setColor(color);
        builder.addVertex(matrix, x, y + h, 0F).setUv(u1 / (float) textureW, v2 / (float) textureH).setColor(color);
        builder.addVertex(matrix, x + w, y, 0F).setUv(u2 / (float) textureW, v1 / (float) textureH).setColor(color);
        builder.addVertex(matrix, x, y, 0F).setUv(u1 / (float) textureW, v1 / (float) textureH).setColor(color);
    }

    /* Repeatable textured box */

    public void texturedArea(Texture texture, int color, float x, float y, float w, float h, float u, float v, float tileW, float tileH, int tw, int th)
    {
        if (w <= 0F || h <= 0F || tileW <= 0F || tileH <= 0F)
        {
            return;
        }

        color = this.applyGlobalAlpha(color);

        int countX = (int) (((w - 1) / tileW) + 1);
        int countY = (int) (((h - 1) / tileH) + 1);
        float fillerX = w - (countX - 1) * tileW;
        float fillerY = h - (countY - 1) * tileH;

        Matrix4f matrix = this.context.pose().last().pose();

        flushBeforeTesselator();

        this.prepareUiBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.id);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);

        for (int i = 0, c = countX * countY; i < c; i ++)
        {
            float ix = i % countX;
            float iy = i / countX;
            float xx = x + ix * tileW;
            float yy = y + iy * tileH;
            float xw = ix == countX - 1 ? fillerX : tileW;
            float yh = iy == countY - 1 ? fillerY : tileH;

            BBSUiFrameRecorder.recordTexturedMesh(
                matrix,
                texture,
                List.of(
                    new BBSUiTexturedMeshVertex(xx, yy + yh, u, v + yh, color),
                    new BBSUiTexturedMeshVertex(xx + xw, yy + yh, u + xw, v + yh, color),
                    new BBSUiTexturedMeshVertex(xx + xw, yy, u + xw, v, color),
                    new BBSUiTexturedMeshVertex(xx, yy + yh, u, v + yh, color),
                    new BBSUiTexturedMeshVertex(xx + xw, yy, u + xw, v, color),
                    new BBSUiTexturedMeshVertex(xx, yy, u, v, color)
                ),
                tw,
                th
            );

            this.fillTexturedBox(builder, matrix, color, xx, yy, xw, yh, u, v, u + xw, v + yh, tw, th);
        }

        BufferUploader.drawWithShader(builder.buildOrThrow());

        this.restoreDepth();
    }

    /* Component with default font */

    public void text(String label, float x, float y, int color)
    {
        this.text(label, x, y, color, false);
    }

    public void text(String label, float x, float y)
    {
        this.text(label, x, y, BBSSettings.textColor(), false);
    }

    public void textShadow(String label, float x, float y)
    {
        this.text(label, x, y, BBSSettings.textColor(), true);
    }

    public void textShadow(String label, float x, float y, int color)
    {
        this.text(label, x, y, color, true);
    }

    public void text(String label, float x, float y, int color, boolean shadow)
    {
        if (shadow && !BBSSettings.textShadow())
        {
            shadow = false;
        }

        color = this.applyTextAlpha(color);

        if (this.globalAlpha != 1F && Colors.getA(color) < TEXT_ALPHA_SKIP_THRESHOLD)
        {
            return;
        }

        if (BBSSettings.isLightTheme())
        {
            color = darkenWhite(color);
        }

        this.drawTextDirect(label, x, y, color, shadow);
    }

    private void drawTextDirect(String label, float x, float y, int color, boolean shadow)
    {
        if (Colors.getA(color) <= 0F)
        {
            color = Colors.opaque(color);
        }

        int drawX = (int) x;
        int drawY = (int) y;

        BBSUiFrameRecorder.recordGlyphRun(
            this.context.pose().last().pose(),
            label,
            drawX,
            drawY,
            this.font.getWidth(label),
            this.font.getHeight(),
            color,
            shadow
        );

        this.context.drawString(this.font.getRenderer(), label, drawX, drawY, color, shadow);
        /* drawString() calls flushIfUnmanaged() internally which calls flush().
         * flush() re-enables depth test and may change depth func via the text
         * RenderType's setupRenderState().  Restore the depth func BBS expects. */
        this.restoreDepth();
    }

    private int applyGlobalAlpha(int color)
    {
        return this.globalAlpha == 1F ? color : Colors.mulA(color, this.globalAlpha);
    }

    private int applyTextAlpha(int color)
    {
        if (this.globalAlpha == 1F)
        {
            return color;
        }

        if (Colors.getA(color) <= 0F)
        {
            color = Colors.opaque(color);
        }

        return Colors.mulA(color, this.globalAlpha);
    }

    /* Component helpers */

    public int wallText(String text, int x, int y, int color, int width)
    {
        return this.wallText(text, x, y, color, width, 12);
    }

    public int wallText(String text, int x, int y, int color, int width, int lineHeight)
    {
        return this.wallText(text, x, y, color, width, lineHeight, 0F, 0F);
    }

    public int wallText(String text, int x, int y, int color, int width, int lineHeight, float ax, float ay)
    {
        return this.wallText(text, x, y, color, width, lineHeight, ax, ay, true);
    }

    public int wallText(String text, int x, int y, int color, int width, int lineHeight, float ax, float ay, boolean shadow)
    {
        List<String> list = this.font.wrap(text, width);
        int h = (lineHeight * (list.size() - 1)) + this.font.getHeight();

        y -= h * ay;

        for (String string : list)
        {
            this.text(string.toString(), (int) (x + (width - this.font.getWidth(string)) * ax), y, color, shadow);

            y += lineHeight;
        }

        return h;
    }

    public void textCard(String text, float x, float y)
    {
        this.textCard(text, x, y, BBSSettings.textColor(), Colors.A50);
    }

    /**
     * In this context, text card is a text with some background behind it
     */
    public void textCard(String text, float x, float y, int color, int background)
    {
        this.textCard(text, x, y, color, background, 3);
    }

    public void textCard(String text, float x, float y, int color, int background, float offset)
    {
        this.textCard(text, x, y, color, background, offset, true);
    }

    public void textCard(String text, float x, float y, int color, int background, float offset, boolean shadow)
    {
        int a = background >> 24 & 0xff;

        if (a != 0)
        {
            if (BBSSettings.isLightTheme() && (background & 0xFFFFFF) == 0)
            {
                background = (background & 0xFF000000) | 0xFFFFFF;
            }

            this.box(x - offset, y - offset, x + this.font.getWidth(text) + offset - 1, y + this.font.getHeight() + offset, background);
        }

        this.text(text, x, y, color, shadow);
    }

    public void flush()
    {
        this.context.flush();
    }

    /* MC 1.21.1 compatibility helpers */

    /**
     * MC 1.21.1: bufferSource and Tesselator share the same underlying BufferBuilder.
     * Flush any pending text vertices before calling begin() so the Tesselator
     * does not discard them.
     */
    private void flushBeforeTesselator()
    {
        this.context.flush();
    }

    /**
     * Keep BBSFS 2.0 UI boxes on the normal alpha-blend path. World previews,
     * particles, and subtitle blur can leave custom blend functions active.
     */
    private void prepareUiBlend()
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
    }

    /**
     * MC 1.21.1: GuiGraphics.flush() re-enables depth test, and the text RenderType
     * may change the depth func.  UIBaseMenu sets depthFunc(GL_ALWAYS) for painter's
     * algorithm rendering.  Restore it after any operation that may call flush().
     */
    private void restoreDepth()
    {
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
    }

    private void fillRoundedCornerFan(BufferBuilder builder, Matrix4f matrix4f, ColoredMeshBatch mirror, float cx, float cy, float radius, int color, int corner)
    {
        for (int i = 0; i < ROUNDED_CORNER_STEPS; i++)
        {
            float ax = roundedCornerX(cx, radius, corner, i);
            float ay = roundedCornerY(cy, radius, corner, i);
            float bx = roundedCornerX(cx, radius, corner, i + 1);
            float by = roundedCornerY(cy, radius, corner, i + 1);

            if (corner == 1 || corner == 2)
            {
                builder.addVertex(matrix4f, cx, cy, 0F).setColor(color);
                builder.addVertex(matrix4f, bx, by, 0F).setColor(color);
                builder.addVertex(matrix4f, ax, ay, 0F).setColor(color);

                if (mirror != null)
                {
                    mirror.addTriangle(cx, cy, color, bx, by, color, ax, ay, color);
                }
            }
            else
            {
                builder.addVertex(matrix4f, cx, cy, 0F).setColor(color);
                builder.addVertex(matrix4f, ax, ay, 0F).setColor(color);
                builder.addVertex(matrix4f, bx, by, 0F).setColor(color);

                if (mirror != null)
                {
                    mirror.addTriangle(cx, cy, color, ax, ay, color, bx, by, color);
                }
            }
        }
    }

    private static float roundedCornerMaskU(float px, float py, float x0, float y0, float x1, float y1, float radius, int corner)
    {
        switch (corner)
        {
            case 1:
            case 2:
                return (x1 - px) / radius;

            default:
                return (px - x0) / radius;
        }
    }

    private static float roundedCornerMaskV(float px, float py, float x0, float y0, float x1, float y1, float radius, int corner)
    {
        switch (corner)
        {
            case 2:
            case 3:
                return (y1 - py) / radius;

            default:
                return (py - y0) / radius;
        }
    }

    private void fillRoundedCornerFanMask(BufferBuilder builder, Matrix4f matrix4f, TexturedMeshBatch mirror, float cx, float cy, float radius, int color, int corner, float x0, float y0, float x1, float y1)
    {
        for (int i = 0; i < ROUNDED_CORNER_STEPS; i++)
        {
            float ax = roundedCornerX(cx, radius, corner, i);
            float ay = roundedCornerY(cy, radius, corner, i);
            float bx = roundedCornerX(cx, radius, corner, i + 1);
            float by = roundedCornerY(cy, radius, corner, i + 1);
            float uC = roundedCornerMaskU(cx, cy, x0, y0, x1, y1, radius, corner);
            float vC = roundedCornerMaskV(cx, cy, x0, y0, x1, y1, radius, corner);
            float uA = roundedCornerMaskU(ax, ay, x0, y0, x1, y1, radius, corner);
            float vA = roundedCornerMaskV(ax, ay, x0, y0, x1, y1, radius, corner);
            float uB = roundedCornerMaskU(bx, by, x0, y0, x1, y1, radius, corner);
            float vB = roundedCornerMaskV(bx, by, x0, y0, x1, y1, radius, corner);

            if (corner == 1 || corner == 2)
            {
                builder.addVertex(matrix4f, cx, cy, 0F).setUv(uC, vC).setColor(color);
                builder.addVertex(matrix4f, bx, by, 0F).setUv(uB, vB).setColor(color);
                builder.addVertex(matrix4f, ax, ay, 0F).setUv(uA, vA).setColor(color);

                if (mirror != null)
                {
                    mirror.addTriangle(
                        cx, cy, uC * mirror.textureWidth, vC * mirror.textureHeight, color,
                        bx, by, uB * mirror.textureWidth, vB * mirror.textureHeight, color,
                        ax, ay, uA * mirror.textureWidth, vA * mirror.textureHeight, color
                    );
                }
            }
            else
            {
                builder.addVertex(matrix4f, cx, cy, 0F).setUv(uC, vC).setColor(color);
                builder.addVertex(matrix4f, ax, ay, 0F).setUv(uA, vA).setColor(color);
                builder.addVertex(matrix4f, bx, by, 0F).setUv(uB, vB).setColor(color);

                if (mirror != null)
                {
                    mirror.addTriangle(
                        cx, cy, uC * mirror.textureWidth, vC * mirror.textureHeight, color,
                        ax, ay, uA * mirror.textureWidth, vA * mirror.textureHeight, color,
                        bx, by, uB * mirror.textureWidth, vB * mirror.textureHeight, color
                    );
                }
            }
        }
    }

    private static float roundedCornerX(float cx, float radius, int corner, int index)
    {
        switch (corner)
        {
            case 0:
                return cx - radius * ROUNDED_RECT_ARC_SIN[index];

            case 1:
                return cx + radius * ROUNDED_RECT_ARC_SIN[index];

            case 2:
                return cx + radius * ROUNDED_RECT_ARC_COS[index];

            default:
                return cx - radius * ROUNDED_RECT_ARC_COS[index];
        }
    }

    private static float roundedCornerY(float cy, float radius, int corner, int index)
    {
        switch (corner)
        {
            case 0:
            case 1:
                return cy - radius * ROUNDED_RECT_ARC_COS[index];

            case 2:
            case 3:
                return cy + radius * ROUNDED_RECT_ARC_SIN[index];

            default:
                return cy;
        }
    }

    private static void recordColoredTriangles(Matrix4f matrix, List<BBSUiVertex> vertices)
    {
        for (int offset = 0; offset < vertices.size(); offset += BBSUiColoredMesh.MAX_VERTICES)
        {
            int end = Math.min(vertices.size(), offset + BBSUiColoredMesh.MAX_VERTICES);

            BBSUiFrameRecorder.recordColoredMesh(matrix, vertices.subList(offset, end));
        }
    }

    private static void addColoredQuad(
        List<BBSUiVertex> vertices,
        float x1, float y1, int c1,
        float x2, float y2, int c2,
        float x3, float y3, int c3,
        float x4, float y4, int c4
    )
    {
        addColoredTriangle(vertices, x1, y1, c1, x2, y2, c2, x3, y3, c3);
        addColoredTriangle(vertices, x1, y1, c1, x3, y3, c3, x4, y4, c4);
    }

    private static void addColoredTriangle(
        List<BBSUiVertex> vertices,
        float x1, float y1, int c1,
        float x2, float y2, int c2,
        float x3, float y3, int c3
    )
    {
        vertices.add(new BBSUiVertex(x1, y1, c1));
        vertices.add(new BBSUiVertex(x2, y2, c2));
        vertices.add(new BBSUiVertex(x3, y3, c3));
    }

    /** Keeps mirror-side procedural meshes bounded independently of segment count. */
    private static final class ColoredMeshBatch
    {
        private final Matrix4f matrix;
        private final ArrayList<BBSUiVertex> vertices = new ArrayList<>(BBSUiColoredMesh.MAX_VERTICES);

        private ColoredMeshBatch(Matrix4f matrix)
        {
            this.matrix = matrix;
        }

        private void addTriangle(
            float x1, float y1, int c1,
            float x2, float y2, int c2,
            float x3, float y3, int c3
        )
        {
            if (this.vertices.size() + 3 > BBSUiColoredMesh.MAX_VERTICES)
            {
                this.flush();
            }

            addColoredTriangle(this.vertices, x1, y1, c1, x2, y2, c2, x3, y3, c3);
        }

        private void addQuad(
            float x1, float y1, int c1,
            float x2, float y2, int c2,
            float x3, float y3, int c3,
            float x4, float y4, int c4
        )
        {
            this.addTriangle(x1, y1, c1, x2, y2, c2, x3, y3, c3);
            this.addTriangle(x1, y1, c1, x3, y3, c3, x4, y4, c4);
        }

        private void finish()
        {
            this.flush();
        }

        private void flush()
        {
            if (this.vertices.isEmpty())
            {
                return;
            }

            BBSUiFrameRecorder.recordColoredMesh(this.matrix, this.vertices);
            this.vertices.clear();
        }
    }

    private static final class TexturedMeshBatch
    {
        private final Matrix4f matrix;
        private final Texture texture;
        private final int textureWidth;
        private final int textureHeight;
        private final ArrayList<BBSUiTexturedMeshVertex> vertices = new ArrayList<>(BBSUiTexturedMesh.MAX_VERTICES);

        private TexturedMeshBatch(Matrix4f matrix, Texture texture)
        {
            this.matrix = matrix;
            this.texture = texture;
            this.textureWidth = texture.width;
            this.textureHeight = texture.height;
        }

        private void addTriangle(
            float x1, float y1, float u1, float v1, int c1,
            float x2, float y2, float u2, float v2, int c2,
            float x3, float y3, float u3, float v3, int c3
        )
        {
            if (this.vertices.size() + 3 > BBSUiTexturedMesh.MAX_VERTICES)
            {
                this.flush();
            }

            this.vertices.add(new BBSUiTexturedMeshVertex(x1, y1, u1, v1, c1));
            this.vertices.add(new BBSUiTexturedMeshVertex(x2, y2, u2, v2, c2));
            this.vertices.add(new BBSUiTexturedMeshVertex(x3, y3, u3, v3, c3));
        }

        private void finish()
        {
            this.flush();
        }

        private void flush()
        {
            if (this.vertices.isEmpty())
            {
                return;
            }

            BBSUiFrameRecorder.recordTexturedMesh(this.matrix, this.texture, this.vertices, this.textureWidth, this.textureHeight);
            this.vertices.clear();
        }
    }

    static
    {
        for (int i = 0; i <= ROUNDED_CORNER_STEPS; i++)
        {
            double angle = i / (double) ROUNDED_CORNER_STEPS * Math.PI * 0.5D;

            ROUNDED_RECT_ARC_SIN[i] = (float) Math.sin(angle);
            ROUNDED_RECT_ARC_COS[i] = (float) Math.cos(angle);
        }
    }
}
