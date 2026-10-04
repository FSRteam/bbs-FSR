package mchorse.bbs_mod.mob;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * A pass-through vertex consumer whose only job is to be unrecognisable to Sodium.
 *
 * <p>Ported from upstream e341fa733 (FSR's {@code ModelPartMixin} already wraps consumers for its
 * own pass with the same trick — {@code DirectVertexConsumer} — so this is the bridge's twin of
 * that). Sodium accelerates entity rendering by injecting into {@code ModelPart.render}: it
 * converts the consumer to its own {@code VertexBufferWriter}, CANCELS the vanilla method and
 * draws the whole subtree itself from a copied part tree — passing one light value down to every
 * part it draws. That is fatal to per-bone picking, which is exactly a per-part light value, and
 * it also means vanilla's own recursion (and therefore any hook on it) never runs.
 *
 * <p>Sodium's conversion returns null for a consumer it does not know, and it then declines
 * instead of cancelling. So for the pick pass — one entity, once, off the hot path — the buffer
 * is handed over wrapped, Sodium steps aside, and vanilla walks the parts one at a time the way
 * the id mixin needs.</p>
 */
public class MobPickerVertexConsumer implements VertexConsumer
{
    private final VertexConsumer consumer;

    public MobPickerVertexConsumer(VertexConsumer consumer)
    {
        this.consumer = consumer;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z)
    {
        return this.consumer.addVertex(x, y, z);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha)
    {
        return this.consumer.setColor(red, green, blue, alpha);
    }

    @Override
    public VertexConsumer setUv(float u, float v)
    {
        return this.consumer.setUv(u, v);
    }

    @Override
    public VertexConsumer setUv1(int u, int v)
    {
        return this.consumer.setUv1(u, v);
    }

    @Override
    public VertexConsumer setUv2(int u, int v)
    {
        return this.consumer.setUv2(u, v);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z)
    {
        return this.consumer.setNormal(x, y, z);
    }
}
