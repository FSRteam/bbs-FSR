package mchorse.bbs_mod.client.rendering.context;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * Minimal world render context contract.
 */
public interface IBbsWorldRenderContext
{
    Camera camera();

    PoseStack matrixStack();

    MultiBufferSource.BufferSource consumers();

    float tickDelta();

    default Matrix4f modelViewMatrix()
    {
        return this.matrixStack().last().pose();
    }

    default Matrix4f projectionMatrix()
    {
        return RenderSystem.getProjectionMatrix();
    }
}
