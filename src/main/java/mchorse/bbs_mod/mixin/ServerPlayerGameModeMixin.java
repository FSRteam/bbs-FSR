package mchorse.bbs_mod.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.ActionRecorder;
import mchorse.bbs_mod.actions.BlockBreakRecordingContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.types.blocks.PlaceBlockActionClip;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records only block breaks committed by the exact recording session that began the call. */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin
{
    private static final String DESTROY_BLOCK = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z";

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    @Inject(method = DESTROY_BLOCK, at = @At("HEAD"))
    private void bbs$captureBlockBreak(
        BlockPos pos,
        CallbackInfoReturnable<Boolean> info,
        @Share("breakRecording") LocalRef<BlockBreakRecordingContext> recording
    )
    {
        ActionManager actions = BBSMod.getActions();

        if (actions == null)
        {
            return;
        }

        ActionRecorder recorder = actions.getRecorder(this.player);

        if (recorder == null)
        {
            return;
        }

        BlockPos immutablePos = pos.immutable();

        recording.set(new BlockBreakRecordingContext(
            actions,
            recorder,
            this.player,
            this.level,
            immutablePos,
            this.level.getBlockState(immutablePos)
        ));
    }

    @Inject(method = DESTROY_BLOCK, at = @At("RETURN"))
    private void bbs$recordCommittedBlockBreak(
        BlockPos pos,
        CallbackInfoReturnable<Boolean> info,
        @Share("breakRecording") LocalRef<BlockBreakRecordingContext> recording
    )
    {
        BlockBreakRecordingContext context = recording.get();

        if (context == null)
        {
            return;
        }

        boolean sameInvocationOwner = context.player() == this.player
            && context.level() == this.level
            && context.pos().equals(pos)
            && context.actions().getRecorder(context.player()) == context.recorder();

        if (!sameInvocationOwner)
        {
            return;
        }

        BlockState state = context.level().getBlockState(context.pos());

        if (!InteractionActionSemantics.shouldRecordCommittedBlockBreak(
            info.getReturnValueZ(),
            true,
            context.state(),
            state
        ))
        {
            return;
        }

        boolean drop = ((ServerPlayerGameMode) (Object) this).getGameModeForPlayer() == GameType.SURVIVAL;

        context.actions().addActionExact(context.player(), context.recorder(), () ->
        {
            PlaceBlockActionClip clip = new PlaceBlockActionClip();

            clip.state.set(state);
            clip.x.set(context.pos().getX());
            clip.y.set(context.pos().getY());
            clip.z.set(context.pos().getZ());
            clip.drop.set(drop);

            return clip;
        });
    }

}
