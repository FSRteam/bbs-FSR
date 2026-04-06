package mchorse.bbs_mod.client.rendering.context;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Minimal world render context contract.
 */
public interface IBbsWorldRenderContext
{
    Camera camera();

    PoseStack matrixStack();

    MultiBufferSource.BufferSource consumers();

    float tickDelta();
}
