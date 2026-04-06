package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.MobForm;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin
{
    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    public void onRenderLabelIfPresent(Entity entity, Component component, PoseStack poseStack, MultiBufferSource multiBufferSource, int packedLight, float partialTick, CallbackInfo info)
    {
        if (FormUtilsClient.getCurrentForm() instanceof MobForm form && form.isPlayer())
        {
            info.cancel();
        }
    }
}
