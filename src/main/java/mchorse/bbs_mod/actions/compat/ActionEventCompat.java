package mchorse.bbs_mod.actions.compat;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Transitional server action event facade.
 */
public final class ActionEventCompat
{
    private static final List<ChatMessageHandler> chatHandlers = new ArrayList<>();
    private static boolean registered;

    private ActionEventCompat() {}

    @FunctionalInterface
    public interface ChatMessageHandler
    {
        void handle(String rawText, ServerPlayer sender);
    }

    public static void onChatMessage(ChatMessageHandler handler)
    {
        ensureRegistered();
        chatHandlers.add(handler);
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

        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, ActionEventCompat::onServerChat);
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

}
