package mchorse.bbs_mod.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import mchorse.bbs_mod.BBSMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class WorldChunkMixin
{
    /*
     * Level.setBlock overloads funnel into this chunk method. The replaced
     * block entity must be serialized before BlockState#onRemove clears it,
     * while the previous state is only reliable from the method return value.
     */
    private static final String SET_BLOCK_STATE = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;";

    @Inject(method = SET_BLOCK_STATE, at = @At("HEAD"))
    private void captureReplacedBlockEntity(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> info, @Share("replaced") LocalRef<CompoundTag> replaced)
    {
        LevelChunk chunk = (LevelChunk) (Object) this;

        if (chunk.getLevel() instanceof ServerLevel level)
        {
            BlockEntity blockEntity = chunk.getBlockEntity(pos);

            if (blockEntity != null)
            {
                replaced.set(blockEntity.saveWithId(level.registryAccess()));
            }
        }
    }

    @Inject(method = SET_BLOCK_STATE, at = @At("RETURN"))
    private void recordChangedBlock(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> info, @Share("replaced") LocalRef<CompoundTag> replaced)
    {
        BlockState previous = info.getReturnValue();
        LevelChunk chunk = (LevelChunk) (Object) this;

        if (previous != null && chunk.getLevel() instanceof ServerLevel)
        {
            BBSMod.getActions().changedBlock(pos, previous, replaced.get());
        }
    }
}
