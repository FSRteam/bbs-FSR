package mchorse.bbs_mod.actions.types;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.entity.LivingEntity;

public class DamageActionClip extends ActionClip
{
    public final ValueFloat damage = new ValueFloat("damage", 0F);

    public DamageActionClip()
    {
        super();

        this.add(this.damage);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!ActionCommandContext.isAuthorizedFor(player))
        {
            return;
        }

        float damage = this.damage.get();

        if (damage <= 0F || !FilmPlaybackPolicy.isDamageAllowed(damage))
        {
            return;
        }

        if (!this.tryApplyPositionRotation(player, replay, tick))
        {
            return;
        }

        if (actor != null)
        {
            actor.hurt(player.damageSources().mobAttack(player), damage);
        }
    }

    @Override
    protected Clip create()
    {
        return new DamageActionClip();
    }
}
