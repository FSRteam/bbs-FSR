package mchorse.bbs_mod.actions.types.chat;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class ChatActionClip extends ActionClip
{
    public final ValueString message = new ValueString("message", "");

    public ChatActionClip()
    {
        this.add(this.message);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        String message = this.message.get();

        if (!ActionCommandContext.isAuthorizedFor(player)
            || !FilmPlaybackPolicy.isChatActionAllowed(message, this.frequency.get()))
        {
            return;
        }

        for (Player entity : player.level().players())
        {
            entity.sendSystemMessage(Component.literal(StringUtils.processColoredText(message)));
        }
    }

    @Override
    protected Clip create()
    {
        return new ChatActionClip();
    }
}
