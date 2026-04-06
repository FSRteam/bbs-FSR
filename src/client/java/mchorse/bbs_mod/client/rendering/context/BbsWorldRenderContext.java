package mchorse.bbs_mod.client.rendering.context;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.Objects;

/**
 * Immutable world render context implementation.
 */
public final class BbsWorldRenderContext implements IBbsWorldRenderContext
{
    private final Camera camera;
    private final PoseStack matrixStack;
    private final MultiBufferSource.BufferSource consumers;
    private final float tickDelta;

    public BbsWorldRenderContext(
        Camera camera,
        PoseStack matrixStack,
        MultiBufferSource.BufferSource consumers,
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
    public PoseStack matrixStack()
    {
        return this.matrixStack;
    }

    @Override
    public MultiBufferSource.BufferSource consumers()
    {
        return this.consumers;
    }

    @Override
    public float tickDelta()
    {
        return this.tickDelta;
    }
}
