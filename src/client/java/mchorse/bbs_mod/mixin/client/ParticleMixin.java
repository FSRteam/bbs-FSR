package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.particles.vanilla.VanillaParticleScene;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps preview particles at the same full-bright level as the form viewport. */
@Mixin(Particle.class)
public class ParticleMixin
{
    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true)
    private void bbs$previewFullBright(float partialTick, CallbackInfoReturnable<Integer> callback)
    {
        if (VanillaParticleScene.isRendering())
        {
            callback.setReturnValue(LightTexture.FULL_BRIGHT);
        }
    }
}
