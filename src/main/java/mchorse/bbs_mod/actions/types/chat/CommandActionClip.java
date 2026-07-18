package mchorse.bbs_mod.actions.types.chat;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.entity.LivingEntity;
public class CommandActionClip extends ActionClip
{
    public final ValueString command = new ValueString("command", "");

    public CommandActionClip()
    {
        this.add(this.command);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        String command = this.command.get();

        if (!ActionCommandContext.isAuthorizedFor(player)
            || !FilmPlaybackPolicy.isCommandActionAllowed(command, this.frequency.get()))
        {
            return;
        }

        if (!this.tryApplyPositionRotation(player, replay, tick))
        {
            return;
        }

        ActionCommandContext.execute(command, this.frequency.get(), actor == null ? player : actor);
    }

    @Override
    protected Clip create()
    {
        return new CommandActionClip();
    }
}
