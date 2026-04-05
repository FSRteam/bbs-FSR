package mchorse.bbs_mod.client.rendering.context;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

import java.util.Objects;

/**
 * Immutable world render context implementation.
 */
public final class BbsWorldRenderContext implements IBbsWorldRenderContext
{
    private final Camera camera;
    private final MatrixStack matrixStack;
    private final VertexConsumerProvider.Immediate consumers;
    private final float tickDelta;

    public BbsWorldRenderContext(
        Camera camera,
        MatrixStack matrixStack,
        VertexConsumerProvider.Immediate consumers,
        float tickDelta
    )
    {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.matrixStack = Objects.requireNonNull(matrixStack, "matrixStack");
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.tickDelta = tickDelta;
    }

    @Override
    public Camera camera()
    {
        return this.camera;
    }

    @Override
    public MatrixStack matrixStack()
    {
        return this.matrixStack;
    }

    @Override
    public VertexConsumerProvider.Immediate consumers()
    {
        return this.consumers;
    }

    @Override
    public float tickDelta()
    {
        return this.tickDelta;
    }
}
