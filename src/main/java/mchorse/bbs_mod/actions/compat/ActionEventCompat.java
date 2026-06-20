package mchorse.bbs_mod.actions.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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
        void handle(String rawText, ServerPlayer sender);
    }

    @FunctionalInterface
    public interface BlockBreakAfterHandler
    {
        void handle(Level level, Player player, BlockPos pos, BlockState state, @Nullable CompoundTag blockEntityTag);
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

    public static void register()
    {
        ensureRegistered();
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

        ServerPlayer sender = event.getPlayer();

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

        if (!(event.getPlayer() instanceof ServerPlayer serverPlayer))
        {
            return;
        }

        if (!(event.getLevel() instanceof Level level) || level.isClientSide())
        {
            return;
        }

        BlockPos pos = event.getPos().immutable();

        BlockEntity blockEntity = level.getBlockEntity(pos);
        CompoundTag blockEntityTag = blockEntity == null ? null : blockEntity.saveWithId(level.registryAccess());

        pendingBreaks.add(new PendingBreak(level, serverPlayer, pos, event.getState(), blockEntityTag));
    }

    public static void flushBlockBreakAfterQueue()
    {
        if (pendingBreaks.isEmpty())
        {
            return;
        }

        List<PendingBreak> breaks = new ArrayList<>(pendingBreaks);

        pendingBreaks.clear();

        if (blockBreakAfterHandlers.isEmpty())
        {
            return;
        }

        for (PendingBreak pendingBreak : breaks)
        {
            for (BlockBreakAfterHandler handler : blockBreakAfterHandlers)
            {
                handler.handle(
                    pendingBreak.world,
                    pendingBreak.player,
                    pendingBreak.pos,
                    pendingBreak.state,
                    pendingBreak.blockEntityTag
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
        private final Level world;
        private final Player player;
        private final BlockPos pos;
        private final BlockState state;
        @Nullable
        private final CompoundTag blockEntityTag;

        private PendingBreak(Level world, Player player, BlockPos pos, BlockState state, @Nullable CompoundTag blockEntityTag)
        {
            this.world = world;
            this.player = player;
            this.pos = pos;
            this.state = state;
            this.blockEntityTag = blockEntityTag;
        }
    }
}
