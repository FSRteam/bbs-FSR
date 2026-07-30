package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Funnels every render type a vanilla renderer asks for into one buffer.
 *
 * <p>Blocks and items are drawn by vanilla, which splits its geometry across render types
 * (solid, cutout, translucent…) and hands each to a different buffer. Drawing a glint over
 * that shape means having the shape's vertices in one place, in the format the glint shader
 * expects — so the renderer is run a second time against this, which answers every request
 * with the same consumer.</p>
 *
 * <p>Vertices arrive through {@link VertexConsumer}'s combined {@code addVertex}, which
 * carries position, color, uv, overlay, light and normal — everything the entity vertex
 * format needs, whatever render type the caller thought it was writing to.</p>
 */
public class GlintCaptureBufferSource implements MultiBufferSource
{
    private static final VertexConsumer DISCARD = new VertexConsumer()
    {
        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz)
        {}

        @Override
        public VertexConsumer addVertex(float x, float y, float z)
        {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z)
        {
            return this;
        }
    };

    private final VertexConsumer consumer;

    public GlintCaptureBufferSource(VertexConsumer consumer)
    {
        this.consumer = consumer;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType)
    {
        /* An enchanted item asks for both its base RenderType and a vanilla glint
         * RenderType through a VertexMultiConsumer. Capturing both into the same target
         * duplicates every vertex and makes this independent glint pass twice as bright.
         * Keep only the base geometry; vanilla already drew its own glint in the normal
         * item pass. */
        if (renderType == RenderType.armorEntityGlint()
            || renderType == RenderType.glintTranslucent()
            || renderType == RenderType.glint()
            || renderType == RenderType.entityGlint()
            || renderType == RenderType.entityGlintDirect())
        {
            return DISCARD;
        }

        return this.consumer;
    }
}
