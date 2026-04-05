package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.blocks.PlaceBlockActionClip;
import mchorse.bbs_mod.actions.types.chat.ChatActionClip;
import mchorse.bbs_mod.actions.compat.ActionEventCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ActionHandler
{
    public static void registerHandlers(ActionManager actions)
    {
        ActionEventCompat.onChatMessage((String rawText, ServerPlayer sender) ->
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

        ActionEventCompat.onBlockBreakAfter((Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) ->
        {
            if (player instanceof ServerPlayer serverPlayer)
            {
                actions.addAction(serverPlayer, () ->
                {
                    PlaceBlockActionClip clip = new PlaceBlockActionClip();

                    clip.state.set(level.getBlockState(pos));
                    clip.x.set(pos.getX());
                    clip.y.set(pos.getY());
                    clip.z.set(pos.getZ());
                    clip.drop.set(serverPlayer.gameMode.getGameModeForPlayer() == GameType.SURVIVAL);

                    return clip;
                });
            }
        });
    }
}
