package mchorse.bbs_mod.client.compat;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Minimal world render context used by BBS rendering and UI pipelines.
 */
public interface BBSWorldRenderContext
{
    Camera camera();

    MatrixStack matrixStack();

    VertexConsumerProvider.Immediate consumers();

    float tickDelta();
}
