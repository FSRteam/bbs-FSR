package mchorse.bbs_mod.graphics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.data.Angle;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

public class Draw
{
    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d)
    {
        renderBox(stack, x, y, z, w, h, d, 1, 1, 1);
    }

    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d, float r, float g, float b)
    {
        renderBox(stack, x, y, z, w, h, d, r, g, b, 1F);
    }

    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d, float r, float g, float b, float a)
    {
        renderBox(stack, x, y, z, w, h, d, r, g, b, a, 1 / 96F + (float) (Math.sqrt(w * w + h + h + d + d) / 2000));
    }

    /** Draw an outline with an explicit half-thickness in model units. */
    public static void renderBox(PoseStack stack, double x, double y, double z, double w, double h, double d, float r, float g, float b, float a, float t)
    {
        stack.pushPose();
        stack.translate(x, y, z);
        float fw = (float) w;
        float fh = (float) h;
        float fd = (float) d;

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        /* Pillars: fillBox(builder, -t, -t, -t, t, t, t, r, g, b, a); */
        fillBox(builder, stack, -t, -t, -t, t, t + fh, t, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t, -t, t + fw, t + fh, t, r, g, b, a);
        fillBox(builder, stack, -t, -t, -t + fd, t, t + fh, t + fd, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t, -t + fd, t + fw, t + fh, t + fd, r, g, b, a);

        /* Top */
        fillBox(builder, stack, -t, -t + fh, -t, t + fw, t + fh, t, r, g, b, a);
        fillBox(builder, stack, -t, -t + fh, -t + fd, t + fw, t + fh, t + fd, r, g, b, a);
        fillBox(builder, stack, -t, -t + fh, -t, t, t + fh, t + fd, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t + fh, -t, t + fw, t + fh, t + fd, r, g, b, a);

        /* Bottom */
        fillBox(builder, stack, -t, -t, -t, t + fw, t, t, r, g, b, a);
        fillBox(builder, stack, -t, -t, -t + fd, t + fw, t, t + fd, r, g, b, a);
        fillBox(builder, stack, -t, -t, -t, t, t, t + fd, r, g, b, a);
        fillBox(builder, stack, -t + fw, -t, -t, t + fw, t, t + fd, r, g, b, a);

        BufferUploader.drawWithShader(builder.buildOrThrow());

        stack.popPose();
    }

    /**
     * Fill a quad for {@link com.mojang.blaze3d.vertex.DefaultVertexFormat#POSITION_TEX_COLOR_NORMAL}. Points should
     * be supplied in this order:
     *
     *     3 -------> 4
     *     ^
     *     |
     *     |
     *     2 <------- 1
     *
     * I.e. bottom left, bottom right, top left, top right, where left is -X and right is +X,
     * in case of a quad on fixed on Z axis.
     */
    public static void fillTexturedNormalQuad(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float u1, float v1, float u2, float v2, float r, float g, float b, float a, float nx, float ny, float nz)
    {
        Matrix4f matrix4f = stack.last().pose();

        /* 1 - BL, 2 - BR, 3 - TR, 4 - TL */
        builder.addVertex(matrix4f, x2, y2, z2).setUv(u1, v2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x1, y1, z1).setUv(u2, v2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x4, y4, z4).setUv(u2, v1).setColor(r, g, b, a).setNormal(nx, ny, nz);

        builder.addVertex(matrix4f, x2, y2, z2).setUv(u1, v2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x4, y4, z4).setUv(u2, v1).setColor(r, g, b, a).setNormal(nx, ny, nz);
        builder.addVertex(matrix4f, x3, y3, z3).setUv(u1, v1).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }

    public static void fillQuad(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a)
    {
        Matrix4f matrix4f = stack.last().pose();

        /* 1 - BR, 2 - BL, 3 - TL, 4 - TR */
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x2, y2, z2).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a);
        builder.addVertex(matrix4f, x4, y4, z4).setColor(r, g, b, a);
    }

    public static void fillBoxTo(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float thickness, float r, float g, float b, float a)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Angle angle = Angle.angle(dx, dy, dz);

        stack.pushPose();

        stack.translate(x1, y1, z1);
        stack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(angle.yaw));
        stack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(angle.pitch));

        fillBox(builder, stack, -thickness / 2, -thickness / 2, 0, thickness / 2, thickness / 2, (float) distance, r, g, b, a);

        stack.popPose();
    }

    public static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b)
    {
        fillBox(builder, stack, x1, y1, z1, x2, y2, z2, r, g, b, 1F);
    }

    public static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, int color)
    {
        float alpha = Colors.getA(color);

        fillBox(builder, stack, x1, y1, z1, x2, y2, z2, Colors.getR(color), Colors.getG(color), Colors.getB(color), alpha <= 0F ? 1F : alpha);
    }

    public static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a)
    {
        /* X */
        fillQuad(builder, stack, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        fillQuad(builder, stack, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);

        /* Y */
        fillQuad(builder, stack, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        fillQuad(builder, stack, x2, y2, z1, x1, y2, z1, x1, y2, z2, x2, y2, z2, r, g, b, a);

        /* Z */
        fillQuad(builder, stack, x2, y1, z1, x1, y1, z1, x1, y2, z1, x2, y2, z1, r, g, b, a);
        fillQuad(builder, stack, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, r, g, b, a);
    }

    public static void coolerAxes(PoseStack stack, float axisSize, float axisOffset, float outlineSize, float outlineOffset)
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();

        axisSize *= scale;
        axisOffset *= scale * thickness;
        outlineSize *= scale;
        outlineOffset *= scale * thickness;

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        fillBox(builder, stack, 0, -outlineOffset, -outlineOffset, outlineSize, outlineOffset, outlineOffset, 0, 0, 0);
        fillBox(builder, stack, -outlineOffset, 0, -outlineOffset, outlineOffset, outlineSize, outlineOffset, 0, 0, 0);
        fillBox(builder, stack, -outlineOffset, -outlineOffset, 0, outlineOffset, outlineOffset, outlineSize, 0, 0, 0);
        fillBox(builder, stack, -outlineOffset, -outlineOffset, -outlineOffset, outlineOffset, outlineOffset, outlineOffset, 0, 0, 0);

        fillBox(builder, stack, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, 1, 0, 0);
        fillBox(builder, stack, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, 0, 1, 0);
        fillBox(builder, stack, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, 0, 0, 1);
        fillBox(builder, stack, -axisOffset, -axisOffset, -axisOffset, axisOffset, axisOffset, axisOffset, 1, 1, 1);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();

        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    public static void coolerAxes(PoseStack stack, float axisSize, float axisOffset)
    {
        coolerAxes(stack, axisSize, axisOffset, axisSize + 0.01F, axisOffset + 0.01F);
    }

    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, int color)
    {
        arc3D(builder, stack, axis, radius, thickness, Colors.getR(color), Colors.getG(color), Colors.getB(color), 0F, 360F);
    }

    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, 0F, 360F);
    }

    /**
     * Based on ElGatoPro300's code from BBS mod CML edition
     */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, startDeg, sweepDeg, 64, 12);
    }

    /**
     * Tessellate an arc of a torus. The tube's cross-section circle is precomputed once and the
     * ring-angle trig lives outside the inner loop — the old shape recomputed both per quad,
     * which put ~18k trig calls into a single ring.
     */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg, int segU, int segV)
    {
        arc3D(builder, stack, axis, radius, thickness, r, g, b, startDeg, sweepDeg, segU, segV, 0F);
    }

    /** Tessellates an arc with its tube tapering to a point over taperDeg at either end. */
    public static void arc3D(BufferBuilder builder, PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b, float startDeg, float sweepDeg, int segU, int segV, float taperDeg)
    {
        double u0 = Math.toRadians(startDeg);
        double uStep = Math.toRadians(sweepDeg / (double) segU);
        double vStep = Math.PI * 2D / (double) segV;

        stack.pushPose();

        if (axis == Axis.X) stack.mulPose(com.mojang.math.Axis.ZP.rotation(MathUtils.PI / 2F));
        if (axis == Axis.Z) stack.mulPose(com.mojang.math.Axis.XP.rotation(MathUtils.PI / 2F));

        float tubeR = thickness * 0.5F;
        Matrix4f mat = stack.last().pose();

        /* The tube cross-section: ring-of-the-tube radii and heights, shared by every u step. */
        double[] ringR = new double[segV + 1];
        float[] ringY = new float[segV + 1];

        for (int iv = 0; iv <= segV; iv++)
        {
            double v = vStep * iv;

            ringR[iv] = radius + tubeR * Math.cos(v);
            ringY[iv] = (float) (tubeR * Math.sin(v));
        }

        float taper = Math.min(taperDeg, Math.abs(sweepDeg) * 0.5F);
        float width2 = taper > 0F ? 0F : 1F;
        double cosU2 = Math.cos(u0);
        double sinU2 = Math.sin(u0);

        for (int iu = 0; iu < segU; iu++)
        {
            float width1 = width2;
            float endDistance = Math.abs(sweepDeg) * Math.min(iu + 1, segU - iu - 1) / segU;
            float t = taper > 0F ? Math.min(endDistance / taper, 1F) : 1F;

            width2 = t * t * (3F - 2F * t);

            double cosU1 = cosU2;
            double sinU1 = sinU2;
            double u2 = u0 + uStep * (iu + 1);

            cosU2 = Math.cos(u2);
            sinU2 = Math.sin(u2);

            for (int iv = 0; iv < segV; iv++)
            {
                double r1 = ringR[iv];
                double r2 = ringR[iv + 1];
                float y1 = ringY[iv];
                float y2 = ringY[iv + 1];

                double r11 = radius + (r1 - radius) * width1;
                double r12 = radius + (r2 - radius) * width1;
                double r21 = radius + (r1 - radius) * width2;
                double r22 = radius + (r2 - radius) * width2;

                float x11 = (float) (r11 * cosU1);
                float z11 = (float) (r11 * sinU1);

                float x12 = (float) (r12 * cosU1);
                float z12 = (float) (r12 * sinU1);

                float x21 = (float) (r21 * cosU2);
                float z21 = (float) (r21 * sinU2);

                float x22 = (float) (r22 * cosU2);
                float z22 = (float) (r22 * sinU2);

                builder.addVertex(mat, x11, y1 * width1, z11).setColor(r, g, b, 1F);
                builder.addVertex(mat, x12, y2 * width1, z12).setColor(r, g, b, 1F);
                builder.addVertex(mat, x22, y2 * width2, z22).setColor(r, g, b, 1F);

                builder.addVertex(mat, x11, y1 * width1, z11).setColor(r, g, b, 1F);
                builder.addVertex(mat, x22, y2 * width2, z22).setColor(r, g, b, 1F);
                builder.addVertex(mat, x21, y1 * width2, z21).setColor(r, g, b, 1F);
            }
        }

        stack.popPose();
    }

    public static void sphere(BufferBuilder builder, PoseStack stack, float radius, int rings, int sectors, float r, float g, float b, float a)
    {
        float constR = 1.0F / (float) (rings - 1);
        float constS = 1.0F / (float) (sectors - 1);
        Matrix4f mat = stack.last().pose();

        for (int i = 0; i < rings - 1; i++)
        {
            for (int j = 0; j < sectors - 1; j++)
            {
                float y0 = (float) Math.sin(-Math.PI / 2 + Math.PI * i * constR);
                float x0 = (float) Math.cos(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * i * constR);
                float z0 = (float) Math.sin(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * i * constR);

                float y1 = (float) Math.sin(-Math.PI / 2 + Math.PI * (i + 1) * constR);
                float x1 = (float) Math.cos(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);
                float z1 = (float) Math.sin(2 * Math.PI * j * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);

                float y2 = (float) Math.sin(-Math.PI / 2 + Math.PI * (i + 1) * constR);
                float x2 = (float) Math.cos(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);
                float z2 = (float) Math.sin(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * (i + 1) * constR);

                float y3 = (float) Math.sin(-Math.PI / 2 + Math.PI * i * constR);
                float x3 = (float) Math.cos(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * i * constR);
                float z3 = (float) Math.sin(2 * Math.PI * (j + 1) * constS) * (float) Math.sin(Math.PI * i * constR);

                builder.addVertex(mat, x0 * radius, y0 * radius, z0 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x1 * radius, y1 * radius, z1 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x2 * radius, y2 * radius, z2 * radius).setColor(r, g, b, a);

                builder.addVertex(mat, x0 * radius, y0 * radius, z0 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x2 * radius, y2 * radius, z2 * radius).setColor(r, g, b, a);
                builder.addVertex(mat, x3 * radius, y3 * radius, z3 * radius).setColor(r, g, b, a);
            }
        }
    }
}
