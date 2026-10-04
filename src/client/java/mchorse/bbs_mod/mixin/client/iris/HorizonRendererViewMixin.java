package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.irisshaders.iris.pathways.HorizonRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HorizonRenderer.class, remap = false)
public abstract class HorizonRendererViewMixin
{
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs$ownHorizon(CallbackInfo callback)
    {
        HorizonRenderer horizon = (HorizonRenderer) (Object) this;

        ViewResourceOwner.track(horizon, horizon::destroy);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void bbs$releasedHorizon(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
