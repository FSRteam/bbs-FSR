package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;

/**
 * Legacy compatibility alias for the new world render context contract.
 */
public interface BBSWorldRenderContext extends IBbsWorldRenderContext
{
    static BBSWorldRenderContext bridge(IBbsWorldRenderContext context)
    {
        if (context instanceof BBSWorldRenderContext compat)
        {
            return compat;
        }

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
