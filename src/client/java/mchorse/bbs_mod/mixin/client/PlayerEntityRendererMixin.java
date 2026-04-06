package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.renderer.MorphRenderer;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.morphing.Morph;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerRenderer.class)
public class PlayerEntityRendererMixin
{
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public void onRender(AbstractClientPlayer abstractClientPlayerEntity, float f, float g, PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, int i, CallbackInfo info)
    {
        if (MorphRenderer.renderPlayer(abstractClientPlayerEntity, f, g, matrixStack, vertexConsumerProvider, i))
        {
            info.cancel();
        }
    }

    @Inject(method = "getRenderOffset", at = @At("HEAD"), cancellable = true)
    public void onPositionOffset(AbstractClientPlayer abstractClientPlayerEntity, float f, CallbackInfoReturnable<Vec3> info)
    {
        Morph morph = Morph.getMorph(abstractClientPlayerEntity);

        if (morph != null && morph.getForm() != null)
        {
            info.setReturnValue(Vec3.ZERO);
        }
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    public void onRenderArmBegin(PoseStack matrices, MultiBufferSource vertexConsumers, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, CallbackInfo info)
    {
        Morph morph = Morph.getMorph(player);

        if (morph != null)
        {
            Form form = morph.getForm();

            if (form != null)
            {
                FormRenderer renderer = FormUtilsClient.getRenderer(form);
                InteractionHand hand = ((PlayerRenderer) (Object) this).getModel().rightArm == arm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

                if (renderer != null && renderer.renderArm(matrices, light, player, hand))
                {
                    info.cancel();
                }
            }
        }
    }
}
