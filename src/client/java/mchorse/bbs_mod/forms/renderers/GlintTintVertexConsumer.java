package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.cubic.render.GlintRenderState;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.colors.Color;
import org.joml.Vector3f;

/**
 * Rewrites the vertex color of captured geometry to the glint's own color.
 *
 * <p>The geometry being captured was built to draw the block or item itself, so its
 * vertices carry that shape's colors. The glint pass wants the same shape in the glint's
 * color instead — the shader reads its tint and opacity from there.</p>
 */
public class GlintTintVertexConsumer implements VertexConsumer
{
    private final VertexConsumer target;
    private final int color;
    private final int mode;
    private final Vector3f viewOrigin;
    private final Vector3f position = new Vector3f();
    private final Vector3f normal = new Vector3f();

    public GlintTintVertexConsumer(VertexConsumer target, Color color, int mode)
    {
        this.target = target;
        this.color = color.getARGBColor();
        this.mode = mode;
        this.viewOrigin = GlintRenderState.getViewOrigin(RenderSystem.getModelViewMatrix());
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz)
    {
        this.target.addVertex(x, y, z, this.edgeColor(x, y, z, nx, ny, nz), u, v, overlay, light, nx, ny, nz);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z)
    {
        return this.target.addVertex(x, y, z);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha)
    {
        return this.target.setColor(this.color);
    }

    @Override
    public VertexConsumer setUv(float u, float v)
    {
        return this.target.setUv(u, v);
    }

    @Override
    public VertexConsumer setUv1(int u, int v)
    {
        return this.target.setUv1(u, v);
    }

    @Override
    public VertexConsumer setUv2(int u, int v)
    {
        return this.target.setUv2(u, v);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z)
    {
        return this.target.setNormal(x, y, z);
    }

    private int edgeColor(float x, float y, float z, float nx, float ny, float nz)
    {
        if (this.mode != Form.GLINT_EDGE)
        {
            return this.color;
        }

        /* Captured position, normal, and the inverse-model-view camera origin all use the
         * same PoseStack-baked coordinate space. */
        this.position.set(this.viewOrigin).sub(x, y, z);
        this.normal.set(nx, ny, nz);

        if (this.position.lengthSquared() <= 0.00001F || this.normal.lengthSquared() <= 0.00001F)
        {
            return this.color;
        }

        this.position.normalize();
        this.normal.normalize();

        float facing = Math.abs(this.normal.dot(this.position));
        float intensity = (float) Math.pow(1F - facing, 2.5F);
        int alpha = Math.round((this.color >>> 24) * intensity);

        return alpha << 24 | this.color & 0x00FFFFFF;
    }
}
