package mchorse.bbs_mod.actions.compat;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Transitional server action event facade.
 */
public final class ActionEventCompat
{
    private static final List<ChatMessageHandler> chatHandlers = new ArrayList<>();
    private static final List<BlockBreakAfterHandler> blockBreakAfterHandlers = new ArrayList<>();
    private static final List<PendingBreak> pendingBreaks = new ArrayList<>();
    private static boolean registered;

    private ActionEventCompat() {}

    @FunctionalInterface
    public interface ChatMessageHandler
    {
        void handle(String rawText, ServerPlayerEntity sender);
    }

    @FunctionalInterface
    public interface BlockBreakAfterHandler
    {
        void handle(World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity);
    }

    public static void onChatMessage(ChatMessageHandler handler)
    {
        ensureRegistered();
        chatHandlers.add(handler);
    }

    public static void onBlockBreakAfter(BlockBreakAfterHandler handler)
    {
        ensureRegistered();
        blockBreakAfterHandlers.add(handler);
    }

    private static void ensureRegistered()
    {
        if (registered)
        {
            return;
        }

        registered = true;

        NeoForge.EVENT_BUS.addListener(ActionEventCompat::onServerChat);
        NeoForge.EVENT_BUS.addListener(ActionEventCompat::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(ActionEventCompat::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(ActionEventCompat::onServerStopped);
    }

    private static void onServerChat(ServerChatEvent event)
    {
        if (event.isCanceled())
        {
            return;
        }

        String rawText = event.getRawText();

        if (rawText == null)
        {
            return;
        }

        ServerPlayerEntity sender = (ServerPlayerEntity) event.getPlayer();

        for (ChatMessageHandler handler : chatHandlers)
        {
            handler.handle(rawText, sender);
        }
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event)
    {
        if (event.isCanceled())
        {
            return;
        }

        if (!(event.getPlayer() instanceof ServerPlayerEntity serverPlayer))
        {
            return;
        }

        if (!(event.getLevel() instanceof World world) || world.isClient)
        {
            return;
        }

        BlockPos pos = event.getPos().toImmutable();

        pendingBreaks.add(new PendingBreak(world, serverPlayer, pos, event.getState(), world.getBlockEntity(pos)));
    }

    private static void onServerTickPost(ServerTickEvent.Post event)
    {
        if (pendingBreaks.isEmpty())
        {
            return;
        }

        List<PendingBreak> breaks = new ArrayList<>(pendingBreaks);

        pendingBreaks.clear();

        for (PendingBreak pendingBreak : breaks)
        {
            for (BlockBreakAfterHandler handler : blockBreakAfterHandlers)
            {
                handler.handle(
                    pendingBreak.world,
                    pendingBreak.player,
                    pendingBreak.pos,
                    pendingBreak.state,
                    pendingBreak.blockEntity
                );
            }
        }
    }

    private static void onServerStopped(ServerStoppedEvent event)
    {
        pendingBreaks.clear();
    }

    private static class PendingBreak
    {
        private final World world;
        private final PlayerEntity player;
        private final BlockPos pos;
        private final BlockState state;
        private final BlockEntity blockEntity;

        private PendingBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity)
        {
            this.world = world;
            this.player = player;
            this.pos = pos;
            this.state = state;
            this.blockEntity = blockEntity;
        }
    }
}
