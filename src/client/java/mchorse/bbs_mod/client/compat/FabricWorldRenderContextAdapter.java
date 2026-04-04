package mchorse.bbs_mod.client.compat;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

/**
 * Adapter from Fabric world render context to the project-local context interface.
 */
public final class FabricWorldRenderContextAdapter
{
    private FabricWorldRenderContextAdapter() {}

    public static BBSWorldRenderContext wrap(WorldRenderContext context)
    {
        return new BBSWorldRenderContext()
        {
            @Override
            public net.minecraft.client.render.Camera camera()
            {
                return context.camera();
            }

            @Override
            public net.minecraft.client.util.math.MatrixStack matrixStack()
            {
                return context.matrixStack();
            }

            @Override
            public net.minecraft.client.render.VertexConsumerProvider.Immediate consumers()
            {
                return context.consumers();
            }

            @Override
            public float tickDelta()
            {
                return context.tickDelta();
            }
        };
    }
}
