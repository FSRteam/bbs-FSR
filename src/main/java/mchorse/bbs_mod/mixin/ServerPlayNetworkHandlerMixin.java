package mchorse.bbs_mod.mixin;

import com.mojang.brigadier.ParseResults;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.CommandRecordingResult;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.types.blocks.InteractBlockActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.actions.types.item.UseItemActionClip;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayNetworkHandlerMixin
{
    @Shadow
    public ServerPlayer player;

    @Inject(method = "parseCommand(Ljava/lang/String;)Lcom/mojang/brigadier/ParseResults;", at = @At("RETURN"), cancellable = true)
    private void bbs$attachCommandResultRecorder(String command, CallbackInfoReturnable<ParseResults<CommandSourceStack>> info)
    {
        CommandRecordingResult recording = new CommandRecordingResult();

        info.setReturnValue(Commands.mapSource(info.getReturnValue(), source -> source.withCallback((successful, result) ->
        {
            recording.tryRecord(successful, () -> BBSMod.getActions().addAction(this.player, () ->
            {
                CommandActionClip clip = new CommandActionClip();

                clip.command.set(command);

                return clip;
            }));
        }, CommandResultCallback::chain)));
    }

    @Redirect(method = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult redirectOnBlockInteract(ServerPlayerGameMode manager, ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hitResult)
    {
        ItemStack snapshot = stack.copy();
        boolean secondaryUse = player.isSecondaryUseActive();
        InteractionResult result = manager.useItemOn(player, level, stack, hand, hitResult);

        if (InteractionActionSemantics.shouldRecordPlayerInteraction(result, player.isSpectator()))
        {
            BBSMod.getActions().addAction(player, () ->
            {
                InteractBlockActionClip clip = new InteractBlockActionClip();

                clip.hit.setHitResult(hitResult);
                clip.hand.set(hand == InteractionHand.MAIN_HAND);
                clip.itemStack.set(snapshot);
                clip.fullDispatch.set(true);
                clip.secondaryUse.set(secondaryUse);

                return clip;
            });
        }

        return result;
    }

    @Redirect(method = "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult redirectOnItemUse(ServerPlayerGameMode manager, ServerPlayer player, Level level, ItemStack stack, InteractionHand hand)
    {
        ItemStack snapshot = stack.copy();
        boolean secondaryUse = player.isSecondaryUseActive();
        InteractionResult result = manager.useItem(player, level, stack, hand);

        if (InteractionActionSemantics.shouldRecordPlayerInteraction(result, player.isSpectator()))
        {
            BBSMod.getActions().addAction(player, () ->
            {
                UseItemActionClip clip = new UseItemActionClip();

                clip.itemStack.set(snapshot);
                clip.hand.set(hand == InteractionHand.MAIN_HAND);
                clip.secondaryUse.set(secondaryUse);

                return clip;
            });
        }

        return result;
    }
}
