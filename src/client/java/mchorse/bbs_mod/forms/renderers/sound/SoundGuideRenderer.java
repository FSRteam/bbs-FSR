package mchorse.bbs_mod.forms.renderers.sound;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeGeometry;
import mchorse.bbs_mod.forms.forms.sound.SoundFalloff;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.VideoRecorder;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Draws the emission shape of a sound form while it is being edited.
 *
 * <p>Geometry comes from {@link SoundConeGeometry} and the intensity bands from
 * {@link SoundFalloff}, i.e. the very same functions the audio uses. That is
 * deliberate: a guide computed from its own copy of the maths could drift, and
 * then it would be showing the user a boundary the sound does not actually
 * respect.</p>
 *
 * <p>Lines are drawn as pairs of perpendicular quads rather than GL lines, so
 * they keep a controllable width and stay visible from any angle.</p>
 */
public final class SoundGuideRenderer
{
    private static final int CIRCLE_SEGMENTS = 48;
    private static final float WIRE_ALPHA = 0.92F;
    private static final float AXIS_ALPHA = 0.75F;

    /** Precomputed so the hot path allocates nothing. */
    private static final float[] SIN = new float[CIRCLE_SEGMENTS + 1];
    private static final float[] COS = new float[CIRCLE_SEGMENTS + 1];

    /** Relative gains the intensity bands are drawn at: -6 dB, -12 dB, -20 dB. */
    private static final float[] BAND_GAINS = {0.5F, 0.25F, 0.1F};

    static
    {
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++)
        {
            double angle = (Math.PI * 2D * i) / CIRCLE_SEGMENTS;

            SIN[i] = (float) Math.sin(angle);
            COS[i] = (float) Math.cos(angle);
        }
    }

    private SoundGuideRenderer()
    {}

    /** Draw whichever shape this form emits, plus its intensity bands. */
    public static void render(PoseStack stack, AbstractSoundForm form)
    {
        if (!form.showGuide.get() || isCapturing())
        {
            return;
        }

        Color color = form.guideColor.get();

        if (form instanceof SoundConeForm cone)
        {
            renderCone(stack, color, cone);
        }
        else if (form instanceof SoundSphereForm sphere)
        {
            renderSphere(stack, color, sphere);
        }
    }

    /** Draw enlarged shape handles into the form-picking framebuffer. */
    public static void renderStencilHandles(PoseStack stack, AbstractSoundForm form, StencilMap stencilMap)
    {
        if (form instanceof SoundSphereForm sphere)
        {
            renderSphereStencil(stack, sphere, stencilMap);
        }
        else if (form instanceof SoundConeForm cone)
        {
            renderConeStencil(stack, cone, stencilMap);
        }
    }

    private static void renderSphereStencil(PoseStack stack, SoundSphereForm form, StencilMap stencilMap)
    {
        float radius = Math.max(form.getMaxDistance(), 0.05F);
        float thickness = grabThickness(radius);
        Color color = stencilColor(stencilMap.objectIndex);

        beginStencil();

        BufferBuilder builder = buffer();

        circle(builder, stack, Axis.X, 0F, radius, thickness, color, 1F);
        circle(builder, stack, Axis.Y, 0F, radius, thickness, color, 1F);
        circle(builder, stack, Axis.Z, 0F, radius, thickness, color, 1F);
        stencilMap.addPicking(form, SoundGuideInteraction.HANDLE_SPHERE_RADIUS);

        endStencil(builder);
    }

    private static void renderConeStencil(PoseStack stack, SoundConeForm form, StencilMap stencilMap)
    {
        float cap = SoundConeGeometry.capDistance(form.range.get());
        float outer = form.outerAngle.get();
        float inner = SoundConeGeometry.clampInnerAngle(form.innerAngle.get(), outer);
        float thickness = grabThickness(cap);

        beginStencil();

        BufferBuilder builder = buffer();
        Color color = stencilColor(stencilMap.objectIndex);

        circle(builder, stack, Axis.Z, cap, SoundConeGeometry.coneRadius(cap, outer),
            thickness, color, 1F);
        stencilMap.addPicking(form, SoundGuideInteraction.HANDLE_CONE_OUTER);

        color = stencilColor(stencilMap.objectIndex);
        circle(builder, stack, Axis.Z, cap, SoundConeGeometry.coneRadius(cap, inner),
            thickness, color, 1F);
        stencilMap.addPicking(form, SoundGuideInteraction.HANDLE_CONE_INNER);

        color = stencilColor(stencilMap.objectIndex);
        disc(builder, stack, cap, Math.max(cap * 0.1F, 0.05F), color);
        stencilMap.addPicking(form, SoundGuideInteraction.HANDLE_CONE_RANGE);

        endStencil(builder);
    }

    /* Sphere: three perpendicular circles plus the axes, which reads more
     * cleanly than a latitude/longitude ball and costs far fewer triangles */
    private static void renderSphere(PoseStack stack, Color color, SoundSphereForm form)
    {
        float r = Math.max(form.getMaxDistance(), 0.05F);
        float t = thickness(r);

        begin();

        BufferBuilder builder = buffer();

        circle(builder, stack, Axis.X, 0F, r, t, color, WIRE_ALPHA);
        circle(builder, stack, Axis.Y, 0F, r, t, color, WIRE_ALPHA);
        circle(builder, stack, Axis.Z, 0F, r, t, color, WIRE_ALPHA);

        line(builder, stack, -r, 0F, 0F, r, 0F, 0F, t, color, AXIS_ALPHA);
        line(builder, stack, 0F, -r, 0F, 0F, r, 0F, t, color, AXIS_ALPHA);
        line(builder, stack, 0F, 0F, -r, 0F, 0F, r, t, color, AXIS_ALPHA);

        /* Intensity bands, as circles on the horizontal plane */
        SoundFalloff falloff = form.getFalloff();

        for (float gain : BAND_GAINS)
        {
            float d = falloff.distanceForGain(gain, form.refDistance.get(), r, form.rolloff.get());

            if (d > 0F && d < r)
            {
                circle(builder, stack, Axis.Y, 0F, d, t * 0.7F, color, AXIS_ALPHA * gain + 0.15F);
            }
        }

        end(builder);
    }

    /* Cone: apex at the origin, i.e. exactly where the sound is emitted */
    private static void renderCone(PoseStack stack, Color color, SoundConeForm form)
    {
        float cap = SoundConeGeometry.capDistance(form.range.get());
        float outer = form.outerAngle.get();
        float inner = SoundConeGeometry.clampInnerAngle(form.innerAngle.get(), outer);
        float outerR = SoundConeGeometry.coneRadius(cap, outer);
        float t = thickness(cap);

        begin();

        BufferBuilder builder = buffer();

        /* Axis from the apex to the centre of the cap */
        line(builder, stack, 0F, 0F, 0F, 0F, 0F, cap, t, color, AXIS_ALPHA);

        /* Four edges from the apex out to the rim */
        line(builder, stack, 0F, 0F, 0F, outerR, 0F, cap, t, color, WIRE_ALPHA);
        line(builder, stack, 0F, 0F, 0F, -outerR, 0F, cap, t, color, WIRE_ALPHA);
        line(builder, stack, 0F, 0F, 0F, 0F, outerR, cap, t, color, WIRE_ALPHA);
        line(builder, stack, 0F, 0F, 0F, 0F, -outerR, cap, t, color, WIRE_ALPHA);

        circle(builder, stack, Axis.Z, cap, outerR, t, color, WIRE_ALPHA);

        if (inner < outer)
        {
            circle(builder, stack, Axis.Z, cap, SoundConeGeometry.coneRadius(cap, inner), t, color, AXIS_ALPHA * 0.7F);
        }

        /* Intensity bands as cross-sections along the axis */
        SoundFalloff falloff = form.getFalloff();

        for (float gain : BAND_GAINS)
        {
            float d = falloff.distanceForGain(gain, form.refDistance.get(), cap, form.rolloff.get());

            if (d > 0F && d < cap)
            {
                circle(builder, stack, Axis.Z, d, SoundConeGeometry.coneRadius(d, outer), t * 0.7F, color, AXIS_ALPHA * gain + 0.15F);
            }
        }

        end(builder);
    }

    /**
     * A circle in the plane perpendicular to {@code axis}, offset along it.
     *
     * @param offset distance along the axis at which the circle sits
     */
    private static void circle(BufferBuilder builder, PoseStack stack, Axis axis, float offset,
        float radius, float t, Color color, float alpha)
    {
        if (radius <= 0.0001F)
        {
            return;
        }

        for (int i = 0; i < CIRCLE_SEGMENTS; i++)
        {
            float c1 = COS[i] * radius;
            float s1 = SIN[i] * radius;
            float c2 = COS[i + 1] * radius;
            float s2 = SIN[i + 1] * radius;

            if (axis == Axis.X)
            {
                line(builder, stack, offset, c1, s1, offset, c2, s2, t, color, alpha);
            }
            else if (axis == Axis.Y)
            {
                line(builder, stack, c1, offset, s1, c2, offset, s2, t, color, alpha);
            }
            else
            {
                line(builder, stack, c1, s1, offset, c2, s2, offset, t, color, alpha);
            }
        }
    }

    /**
     * A line segment with width, drawn as two perpendicular quads so it stays
     * visible whichever way the camera looks at it.
     */
    private static void line(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1,
        float x2, float y2, float z2, float t, Color color, float alpha)
    {
        float r = color.r;
        float g = color.g;
        float b = color.b;
        float a = color.a * alpha;

        Draw.fillQuad(builder, stack,
            x1 - t, y1, z1, x2 - t, y2, z2, x2 + t, y2, z2, x1 + t, y1, z1, r, g, b, a);
        Draw.fillQuad(builder, stack,
            x1, y1 - t, z1, x2, y2 - t, z2, x2, y2 + t, z2, x1, y1 + t, z1, r, g, b, a);
    }

    private static void disc(BufferBuilder builder, PoseStack stack, float z, float radius, Color color)
    {
        org.joml.Matrix4f matrix = stack.last().pose();

        for (int i = 0; i < CIRCLE_SEGMENTS; i++)
        {
            builder.addVertex(matrix, 0F, 0F, z).setColor(color.r, color.g, color.b, 1F);
            builder.addVertex(matrix, COS[i] * radius, SIN[i] * radius, z).setColor(color.r, color.g, color.b, 1F);
            builder.addVertex(matrix, COS[i + 1] * radius, SIN[i + 1] * radius, z).setColor(color.r, color.g, color.b, 1F);
        }
    }

    /** Guides are meant to be readable through geometry, hence no depth test. */
    private static void begin()
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void beginStencil()
    {
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static BufferBuilder buffer()
    {
        return Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
    }

    private static void end(BufferBuilder builder)
    {
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private static void endStencil(BufferBuilder builder)
    {
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableBlend();
    }

    /**
     * Whether a video is being recorded or exported right now.
     *
     * <p>Guides are an editing aid and must never end up in the finished
     * footage, so they are suppressed for the whole capture rather than relying
     * on the user remembering to switch them off first.</p>
     */
    private static boolean isCapturing()
    {
        VideoRecorder recorder = BBSModClient.getVideoRecorder();

        return recorder != null && recorder.isRecording();
    }

    /** Thin enough to read, thick enough to see, across a wide range of sizes. */
    private static float thickness(float size)
    {
        float t = size * 0.002F;

        return t < 0.002F ? 0.002F : (t > 0.009F ? 0.009F : t);
    }

    private static float grabThickness(float size)
    {
        return Math.max(thickness(size) * 6F, size * 0.015F);
    }

    private static Color stencilColor(int index)
    {
        return new Color(
            (index & 0xff) / 255F,
            (index >> 8 & 0xff) / 255F,
            (index >> 16 & 0xff) / 255F
        );
    }
}
