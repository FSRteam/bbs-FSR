package mchorse.bbs_mod.ui.framework.elements.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
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

import java.util.List;
import java.util.function.Supplier;

public class Batcher2D
{
    private static FontRenderer fontRenderer = new FontRenderer();

    private GuiGraphics context;
    private FontRenderer font;

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
        this.context.enableScissor(x, y, x + w, y + h);
    }

    public void unclip(UIContext context)
    {
        this.unclip(context.menu.width, context.menu.height);
    }

    public void unclip(int sw, int sh)
    {
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
        /* c1 ---- c2
         * |        |
         * c3 ---- c4 */
        builder.addVertex(matrix4f, x, y, 0).setColor(color1);
        builder.addVertex(matrix4f, x, y + h, 0).setColor(color3);
        builder.addVertex(matrix4f, x + w, y + h, 0).setColor(color4);
        builder.addVertex(matrix4f, x + w, y, 0).setColor(color2);
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
        left -= offset;
        top -= offset;
        right += offset;
        bottom += offset;

        Matrix4f matrix4f = this.context.pose().last().pose();

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
        Matrix4f matrix4f = this.context.pose().last().pose();

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

        Matrix4f matrix4f = this.context.pose().last().pose();

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

        for (int i = 0; i < segments; i ++)
        {
            double alpha1 = i / (double) segments * Math.PI * 2 - Math.PI / 2;
            double alpha2 = (i + 1) / (double) segments * Math.PI * 2 - Math.PI / 2;

            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha2) * offset), (float) (y + Math.sin(alpha2) * offset), 0F).setColor(opaque);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha1) * offset), (float) (y + Math.sin(alpha1) * offset), 0F).setColor(opaque);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha1) * radius), (float) (y + Math.sin(alpha1) * radius), 0F).setColor(shadow);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha2) * offset), (float) (y + Math.sin(alpha2) * offset), 0F).setColor(opaque);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha1) * radius), (float) (y + Math.sin(alpha1) * radius), 0F).setColor(shadow);
            builder.addVertex(matrix4f, (float) (x - Math.cos(alpha2) * radius), (float) (y + Math.sin(alpha2) * radius), 0F).setColor(shadow);
        }

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

        this.texturedBox(BBSModClient.getTextures().getTexture(icon.texture), color, x, y, icon.w, icon.h, icon.x, icon.y, icon.x + icon.w, icon.y + icon.h, icon.textureW, icon.textureH);
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

        this.texturedArea(BBSModClient.getTextures().getTexture(icon.texture), color, x, y, w, h, icon.x, icon.y, icon.w, icon.h, icon.textureW, icon.textureH);
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

        Matrix4f matrix = this.context.pose().last().pose();

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
        this.texturedBox(GameRenderer::getPositionTexColorShader, texture, color, x, y, w, h, u1, v1, u2, v2, textureW, textureH);
    }

    public void texturedBox(Supplier<ShaderInstance> shader, int texture, int color, float x, float y, float w, float h, float u1, float v1, float u2, float v2, int textureW, int textureH)
    {
        if (w <= 0F || h <= 0F)
        {
            return;
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
        this.text(label, x, y, Colors.WHITE, false);
    }

    public void textShadow(String label, float x, float y)
    {
        this.text(label, x, y, Colors.WHITE, true);
    }

    public void textShadow(String label, float x, float y, int color)
    {
        this.text(label, x, y, color, true);
    }

    public void text(String label, float x, float y, int color, boolean shadow)
    {
        if (BBSSettings.isLightTheme())
        {
            shadow = false;
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

        this.context.drawString(this.font.getRenderer(), label, (int) x, (int) y, color, shadow);
        /* drawString() calls flushIfUnmanaged() internally which calls flush().
         * flush() re-enables depth test and may change depth func via the text
         * RenderType's setupRenderState().  Restore the depth func BBS expects. */
        this.restoreDepth();
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
        this.textCard(text, x, y, Colors.WHITE, Colors.A50);
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
}
