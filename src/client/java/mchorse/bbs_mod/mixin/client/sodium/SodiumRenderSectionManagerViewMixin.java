package mchorse.bbs_mod.mixin.client.sodium;

import mchorse.bbs_mod.client.render.multiview.SodiumChunkScheduling;
import mchorse.bbs_mod.client.render.multiview.SodiumViewAdapter;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentLinkedDeque;

/** Shared region/cache maintenance advances once while visibility remains per pass. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumRenderSectionManagerViewMixin
{
    @Shadow @Final private ChunkBuilder builder;
    @Shadow @Final private ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults;
    @Shadow private ChunkJobCollector lastBlockingCollector;

    @Inject(method = "updateChunks", at = @At("HEAD"), cancellable = true)
    private void bbs$finishSortingWithoutRebuildingTheWorld(boolean immediate, CallbackInfo info)
    {
        if (!SodiumViewAdapter.isMultiViewFrame())
        {
            return;
        }

        SodiumChunkScheduling.framePrepared(this);

        if (immediate)
        {
            return;
        }

        /* Finish work inherited from a preceding normal frame once. Subsequent
         * view passes never accumulate blocking mesh rebuilds. */
        ChunkJobCollector previous = this.lastBlockingCollector;
        this.lastBlockingCollector = null;

        if (previous != null)
        {
            previous.awaitCompletion(this.builder);
        }

        SodiumChunkScheduling.updateChunks(this, this.builder, this.buildResults::add);
        info.cancel();
    }

    @Inject(method = "cleanupAndFlip", at = @At("HEAD"), cancellable = true)
    private void bbs$maintainRegionsOnce(CallbackInfo info)
    {
        if (!SodiumViewAdapter.claimMaintenance(this))
        {
            info.cancel();
        }
    }
}
