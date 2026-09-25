package mchorse.bbs_mod.forms.renderers.utils;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Feeds the liquid renderer's chunk format vertices into an entity format buffer.
 *
 * <p>{@link net.minecraft.client.renderer.block.LiquidBlockRenderer} writes position, colour,
 * texture, light and normal straight into the buffer, in world (well, chunk local) coordinates
 * and without a matrix, because chunk geometry never needs one. Forms need the entity format
 * instead: it carries an overlay, and it is the format the picking shader is compiled for.
 * So this applies the form's matrix to the vertices and slips the overlay in exactly where
 * the entity format expects it, right after the texture coordinates.</p>
 *
 * <p>Every method returns {@code this}: the liquid renderer chains its calls, and handing back
 * the wrapped buffer at any point in the chain would let the rest of the vertex bypass the
 * overlay.</p>
 */
public class FluidVertexConsumer implements VertexConsumer
{
    private final VertexConsumer consumer;
    private final Matrix4f position;
    private final Matrix3f normal;
    private final int overlay;

    private final Vector3f temporary = new Vector3f();

    public FluidVertexConsumer(VertexConsumer consumer, PoseStack.Pose entry, int overlay)
    {
        this.consumer = consumer;
        this.position = entry.pose();
        this.normal = entry.normal();
        this.overlay = overlay;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z)
    {
        this.position.transformPosition(x, y, z, this.temporary);
        this.consumer.addVertex(this.temporary.x, this.temporary.y, this.temporary.z);

        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha)
    {
        this.consumer.setColor(red, green, blue, alpha);

        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v)
    {
        this.consumer.setUv(u, v);
        this.consumer.setUv1(this.overlay & 0xFFFF, this.overlay >> 16 & 0xFFFF);

        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v)
    {
        /* The overlay is injected by setUv; the liquid renderer never sets one itself. */
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v)
    {
        this.consumer.setUv2(u, v);

        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z)
    {
        this.normal.transform(x, y, z, this.temporary);
        this.consumer.setNormal(this.temporary.x, this.temporary.y, this.temporary.z);

        return this;
    }
}
