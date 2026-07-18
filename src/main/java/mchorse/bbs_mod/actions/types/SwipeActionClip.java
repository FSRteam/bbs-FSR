package mchorse.bbs_mod.actions.types;

import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

public class SwipeActionClip extends ActionClip
{
    public final ValueBoolean hand = new ValueBoolean("hand", true);

    public SwipeActionClip()
    {
        this.add(this.hand);
    }

    @Override
    protected boolean canPersist(BaseValue value)
    {
        if (value == this.hand && this.hand.get())
        {
            return false;
        }

        return super.canPersist(value);
    }

    @Override
    public boolean isClient()
    {
        return true;
    }

    @Override
    protected void applyClientAction(IEntity entity, Film film, Replay replay, int tick)
    {
        entity.swingArm(this.getHand());
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        super.applyAction(actor, player, film, replay, tick);

        if (actor instanceof ActorEntity replayActor)
        {
            replayActor.swing(this.getHand(), true);
        }
    }

    private InteractionHand getHand()
    {
        return this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    @Override
    protected Clip create()
    {
        return new SwipeActionClip();
    }
}
