package mchorse.bbs_mod.forms.renderers.utils;

import com.mojang.blaze3d.vertex.VertexConsumer;

public final class DirectVertexConsumer implements VertexConsumer
{
    private final VertexConsumer consumer;

    public DirectVertexConsumer(VertexConsumer consumer)
    {
        this.consumer = consumer;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z)
    {
        this.consumer.addVertex(x, y, z);

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

        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v)
    {
        this.consumer.setUv1(u, v);

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
        this.consumer.setNormal(x, y, z);

        return this;
    }
}
