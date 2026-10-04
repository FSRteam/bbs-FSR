package mchorse.bbs_mod.mixin.client.sodium;

import mchorse.bbs_mod.client.render.multiview.SodiumViewAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shared chunk events are consumed once; per-camera visibility remains Sodium's normal setup. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererViewMixin
{
    @Inject(method = "processChunkEvents", at = @At("HEAD"), cancellable = true)
    private void bbs$processEventsOnce(CallbackInfo info)
    {
        if (!SodiumViewAdapter.claimChunkEvents(this))
        {
            info.cancel();
        }
    }
}
