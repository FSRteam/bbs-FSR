package bbssmokefixture.v1;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.graphics.Draw;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Pig;

/**
 * 1.0 renderer swap for the vanilla pig: draws a solid red box instead of
 * the normal pig model. EntityType/BlockEntityType registration is frozen
 * (see prd Out of Scope), so the fixture overrides the renderer of an
 * already-registered vanilla type instead of adding a new one.
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
        Draw.renderBox(matrices, -0.6D, 0D, -0.6D, 1.2D, 1.8D, 1.2D, 1F, 0.1F, 0.1F, 0.85F);
        matrices.popPose();

        super.render(entity, yaw, tickDelta, matrices, buffers, light);
    }
}
