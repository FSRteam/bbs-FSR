package bbssmokefixture.v2;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.graphics.Draw;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Pig;

/**
 * 2.0 renderer swap for the vanilla pig: draws a larger solid green box
 * instead of the 1.0 red box, so overriding the plugin is unmistakable even
 * from across a room.
 */
public final class SmokeEntityRenderer extends EntityRenderer<Pig>
{
    public SmokeEntityRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(Pig entity)
    {
        return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/pig/pig.png");
    }

    @Override
    public void render(Pig entity, float yaw, float tickDelta, PoseStack matrices, MultiBufferSource buffers, int light)
    {
        matrices.pushPose();
        Draw.renderBox(matrices, -0.8D, 0D, -0.8D, 1.6D, 2.2D, 1.6D, 0.1F, 0.9F, 0.3F, 0.85F);
        matrices.popPose();

        super.render(entity, yaw, tickDelta, matrices, buffers, light);
    }
}
