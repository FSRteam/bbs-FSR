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
    /* Form hooks replace the shader, texture, and blending selected by the layer. Run them only
     * after RenderType has installed its own state, otherwise setupRenderState() immediately
     * overwrites the form's custom texture and picker state. */
    @Inject(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;setupRenderState()V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    public void onDraw(MeshData meshData, CallbackInfo info)
    {
        if (CustomVertexConsumerProvider.drawLayer((RenderType) (Object) this, meshData))
        {
            /* The normal draw clears the state after submission. A deferred draw cancels that
             * path, so balance the already-completed setup before returning. */
            ((RenderType) (Object) this).clearRenderState();
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
