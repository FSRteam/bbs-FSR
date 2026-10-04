package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.irisshaders.iris.targets.backed.NativeImageBackedCustomTexture;
import net.irisshaders.iris.targets.backed.NativeImageBackedNoiseTexture;
import net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DynamicTexture.class)
public abstract class DynamicTextureViewMixin
{
    @Inject(method = {"<init>(Lcom/mojang/blaze3d/platform/NativeImage;)V", "<init>(IIZ)V"}, at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;pixels:Lcom/mojang/blaze3d/platform/NativeImage;",
        opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void bbs$ownTexture(CallbackInfo callback)
    {
        Object texture = this;

        if (texture instanceof NativeImageBackedCustomTexture || texture instanceof NativeImageBackedNoiseTexture
            || texture instanceof NativeImageBackedSingleColorTexture)
        {
            ViewResourceOwner.track(texture, ((DynamicTexture) texture)::close);
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void bbs$releasedTexture(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
