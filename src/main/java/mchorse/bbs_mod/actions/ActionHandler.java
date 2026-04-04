package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.blocks.PlaceBlockActionClip;
import mchorse.bbs_mod.actions.types.chat.ChatActionClip;
import mchorse.bbs_mod.actions.compat.ActionEventCompat;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class ActionHandler
{
    public static void registerHandlers(ActionManager actions)
    {
        ActionEventCompat.onChatMessage((String rawText, ServerPlayerEntity sender) ->
        {
            if (rawText != null)
            {
                actions.addAction(sender, () ->
                {
                    ChatActionClip clip = new ChatActionClip();

                    clip.message.set(rawText);

                    return clip;
                });
            }
        });

        ActionEventCompat.onBlockBreakAfter((World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) ->
        {
            if (player instanceof ServerPlayerEntity serverPlayer)
            {
                actions.addAction(serverPlayer, () ->
                {
                    PlaceBlockActionClip clip = new PlaceBlockActionClip();

                    clip.state.set(world.getBlockState(pos));
                    clip.x.set(pos.getX());
                    clip.y.set(pos.getY());
                    clip.z.set(pos.getZ());
                    clip.drop.set(serverPlayer.interactionManager.getGameMode() == GameMode.SURVIVAL);

                    return clip;
                });
            }
        });
    }
}
