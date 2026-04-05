package mchorse.bbs_mod.actions.types.item;

import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.items.GunItem;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public class UseItemActionClip extends ItemActionClip
{
    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        InteractionHand hand = this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

        GunItem.actor = actor;

        this.applyPositionRotation(player, replay, tick);
        player.setItemInHand(hand, this.itemStack.get().copy());
        this.itemStack.get().use(player.level(), player, hand);
        player.setItemInHand(hand, ItemStack.EMPTY);

        GunItem.actor = null;
    }

    @Override
    protected Clip create()
    {
        return new UseItemActionClip();
    }
}
