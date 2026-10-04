package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.IrisViewState;
import mchorse.bbs_mod.utils.iris.ViewSampleClock;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SystemTimeUniforms.FrameCounter.class, remap = false)
public class SystemTimeUniformsCounterMixin
{
    @Inject(method = "getAsInt", at = @At("HEAD"), cancellable = true)
    private void bbs$viewSampleCounter(CallbackInfoReturnable<Integer> callback)
    {
        ViewSampleClock.Sample sample = IrisViewState.sample();

        if (sample != null)
        {
            callback.setReturnValue(sample.frameCounter());
        }
    }
}
