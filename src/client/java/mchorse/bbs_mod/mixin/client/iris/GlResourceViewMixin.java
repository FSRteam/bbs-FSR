package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.sampler.GlSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlResource.class, remap = false)
public abstract class GlResourceViewMixin
{
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bbs$ownAllocation(int id, CallbackInfo callback)
    {
        GlResource resource = (GlResource) (Object) this;

        /* Sampler objects belong to Iris' global cache, not to the constructing view. */
        if (!(resource instanceof GlSampler))
        {
            ViewResourceOwner.track(resource, resource::destroy);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void bbs$releasedAllocation(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
