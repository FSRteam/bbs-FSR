package bbssmokefixture.v2;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.graphics.Draw;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * 2.0 renderer swap for the vanilla chest: draws a solid green box instead
 * of the 1.0 red box.
 */
public final class SmokeBlockEntityRenderer implements BlockEntityRenderer<ChestBlockEntity>
{
    @Override
    public void render(ChestBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource buffers, int light, int overlay)
    {
        matrices.pushPose();
        Draw.renderBox(matrices, -0.1D, 0D, -0.1D, 1.2D, 1.2D, 1.2D, 0.1F, 0.9F, 0.3F, 0.85F);
        matrices.popPose();
    }
}
