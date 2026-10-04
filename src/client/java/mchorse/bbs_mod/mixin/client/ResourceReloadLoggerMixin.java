package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.ResourceLoadStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResourceLoadStateTracker.class)
public class ResourceReloadLoggerMixin
{
    @Inject(method = "finishReload", at = @At("TAIL"))
    public void onOnFinishedLoading(CallbackInfo info)
    {
        BBSRendering.releaseViewResources();
        BBSModClient.getSounds().deleteSounds();

        /* Fires on every completed resource reload (initial load, F3+T, pack changes), which is the
         * NeoForge stand-in for Fabric's synchronous reload listener: pick up the resource packs'
         * CEM models and vanilla textures without re-entering the world. Sits out the first reload,
         * before the packs exist, on its own. */
        BBSModClient.onResourcePacksReloaded();
    }
}
