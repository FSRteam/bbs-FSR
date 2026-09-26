package mchorse.bbs_mod.forms.structure;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Applies a fixed position/normal transform (plus a per-block offset) to incoming vertices.
 * Needed for vanilla fluid rendering: {@code BlockRenderDispatcher.renderLiquid} ignores the
 * {@code PoseStack} and emits chunk-local coordinates ({@code pos & 15}), so this wrapper
 * re-adds the chunk base offset and applies the current pose matrix manually.
 */
public class TransformingVertexConsumer implements VertexConsumer
{
    private final Matrix4f positionMatrix;
    private final Matrix3f normalMatrix;
    private final Vector4f position = new Vector4f();
    private final Vector3f normal = new Vector3f();

    private VertexConsumer delegate;
    private float offsetX;
    private float offsetY;
    private float offsetZ;

    public TransformingVertexConsumer(Matrix4f positionMatrix, Matrix3f normalMatrix)
    {
        this.positionMatrix = positionMatrix;
        this.normalMatrix = normalMatrix;
    }

    public TransformingVertexConsumer target(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ)
    {
        this.delegate = delegate;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;

        return this;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z)
    {
        this.position.set(x + this.offsetX, y + this.offsetY, z + this.offsetZ, 1F);
        this.positionMatrix.transform(this.position);
        this.delegate.addVertex(this.position.x, this.position.y, this.position.z);

        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha)
    {
        this.delegate.setColor(red, green, blue, alpha);

        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v)
    {
        this.delegate.setUv(u, v);

        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v)
    {
        this.delegate.setUv1(u, v);

        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v)
    {
        this.delegate.setUv2(u, v);

        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z)
    {
        this.normal.set(x, y, z);
        this.normalMatrix.transform(this.normal);
        this.delegate.setNormal(this.normal.x, this.normal.y, this.normal.z);

        return this;
    }
}
