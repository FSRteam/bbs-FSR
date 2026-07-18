package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.chat.ChatActionClip;
import mchorse.bbs_mod.actions.compat.ActionEventCompat;
import net.minecraft.server.level.ServerPlayer;

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

    }
}
