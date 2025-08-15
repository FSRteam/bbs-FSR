package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ShaderCurves;
import net.irisshaders.iris.Iris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Iris.class)
public class IrisMixin
{
    @Inject(method = "loadExternalShaderpack", at = @At("HEAD"), remap = false)
    private static void onLoadExternalShaderpack(String name, CallbackInfoReturnable<Boolean> info)
    {
        ShaderCurves.reset();
    }

    @Inject(method = "setShadersDisabled", at = @At("HEAD"), remap = false)
    private static void onLoadExternalShaderpack(CallbackInfo info)
    {
        ShaderCurves.reset();
    }
}