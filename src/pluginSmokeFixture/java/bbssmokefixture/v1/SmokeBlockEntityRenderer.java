package bbssmokefixture.v1;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.graphics.Draw;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * 1.0 renderer swap for the vanilla chest: draws a solid red box instead of
 * the normal chest model.
 */
public final class SmokeBlockEntityRenderer implements BlockEntityRenderer<ChestBlockEntity>
{
    @Override
    public void render(ChestBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource buffers, int light, int overlay)
    {
        matrices.pushPose();
        Draw.renderBox(matrices, 0D, 0D, 0D, 1D, 1D, 1D, 1F, 0.1F, 0.1F, 0.85F);
        matrices.popPose();
    }
}
