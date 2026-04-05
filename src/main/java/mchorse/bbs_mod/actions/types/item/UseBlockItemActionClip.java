package mchorse.bbs_mod.actions.types.item;

import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.values.ValueBlockHitResult;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.items.GunItem;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

public class UseBlockItemActionClip extends ItemActionClip
{
    public final ValueBlockHitResult hit = new ValueBlockHitResult("hit");

    public UseBlockItemActionClip()
    {
        super();

        this.add(this.hit);
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        super.shift(dx, dy, dz);

        this.hit.shift(dx, dy, dz);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        InteractionHand hand = this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack copy = this.itemStack.get().copy();

        GunItem.actor = actor;

        this.applyPositionRotation(player, replay, tick);
        player.setItemInHand(hand, copy);
        this.itemStack.get().useOn(new UseOnContext(player.level(), player, hand, copy, this.hit.getHitResult()));
        player.setItemInHand(hand, ItemStack.EMPTY);

        GunItem.actor = null;
    }

    @Override
    protected Clip create()
    {
        return new UseBlockItemActionClip();
    }
}
