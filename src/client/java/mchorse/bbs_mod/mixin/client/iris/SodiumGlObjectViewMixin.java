package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.caffeinemc.mods.sodium.client.gl.GlObject;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlObject.class, remap = false)
public abstract class SodiumGlObjectViewMixin
{
    @Inject(method = "setHandle", at = @At("RETURN"))
    private void bbs$ownProgram(int handle, CallbackInfo callback)
    {
        Object resource = this;

        if (resource instanceof GlProgram<?> program)
        {
            ViewResourceOwner.track(resource, program::delete);
        }
        else if (resource instanceof GlShader shader)
        {
            ViewResourceOwner.track(resource, shader::delete);
        }
    }

    @Inject(method = "invalidateHandle", at = @At("HEAD"))
    private void bbs$releasedProgram(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
