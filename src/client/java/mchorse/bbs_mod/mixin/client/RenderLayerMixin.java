package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.vertex.MeshData;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderType.class)
public class RenderLayerMixin
{
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    public void onDraw(MeshData meshData, CallbackInfo info)
    {
        if (CustomVertexConsumerProvider.drawLayer((RenderType) (Object) this, meshData))
        {
            info.cancel();
        }
    }

    @Inject(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void bbs$prepareDraw(MeshData meshData, CallbackInfo info)
    {
        CustomVertexConsumerProvider.prepareLayer((RenderType) (Object) this);
    }
}
