package mchorse.bbs_mod.client.rendering.context;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Minimal world render context contract.
 */
public interface IBbsWorldRenderContext
{
    Camera camera();

    MatrixStack matrixStack();

    VertexConsumerProvider.Immediate consumers();

    float tickDelta();
}
