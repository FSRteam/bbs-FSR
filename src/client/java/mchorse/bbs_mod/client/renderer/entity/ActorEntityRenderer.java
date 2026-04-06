package mchorse.bbs_mod.client.renderer.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.cubic.render.vanilla.ArmorRenderer;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.model.ArmorEntityModel;
import net.minecraft.client.renderer.entity.model.EntityModelLayers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import com.mojang.math.Axis;

public class ActorEntityRenderer extends EntityRenderer<ActorEntity>
{
    public static ArmorRenderer armorRenderer;

    public ActorEntityRenderer(EntityRendererProvider.Context ctx)
    {
        super(ctx);

        armorRenderer = new ArmorRenderer(
            new ArmorEntityModel(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
            new ArmorEntityModel(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
            ctx.getModelManager()
        );

        this.shadowRadius = 0.5F;
    }

    @Override
    public ResourceLocation getTextureLocation(ActorEntity entity)
    {
        return new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
    }

    @Override
    public void render(ActorEntity livingEntity, float yaw, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light)
    {
        matrices.pushPose();

        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, livingEntity.prevBodyYaw, livingEntity.bodyYaw);
        int overlay = LivingEntityRenderer.getOverlayCoords(livingEntity, 0F);

        this.setupTransforms(livingEntity, matrices, bodyYaw, tickDelta);

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        FormUtilsClient.render(livingEntity.getForm(), new FormRenderingContext()
            .set(FormRenderType.ENTITY, livingEntity.getEntity(), matrices, light, overlay, tickDelta)
            .camera(Minecraft.getInstance().gameRenderer.getCamera()));
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();

        matrices.popPose();

        super.render(livingEntity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    protected boolean isVisible(ActorEntity entity)
    {
        return !entity.isInvisible();
    }

    protected void setupTransforms(ActorEntity entity, PoseStack matrices, float bodyYaw, float tickDelta)
    {
        if (!entity.isInPose(EntityPose.SLEEPING))
        {
            matrices.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
        }

        if (entity.deathTime > 0)
        {
            float deathAngle = (entity.deathTime + tickDelta - 1F) / 20F * 1.6F;

            matrices.mulPose(Axis.ZP.rotationDegrees(Math.min(MathHelper.sqrt(deathAngle), 1F) * 90F));
        }
    }
}
