package mchorse.bbs_mod.actions.types.item;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
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
        if (!ActionCommandContext.isAuthorizedFor(player))
        {
            return;
        }

        InteractionActionSemantics.withIsolatedInteractionState(player, () ->
            this.applyIsolatedAction(actor, player, film, replay, tick));
    }

    private void applyIsolatedAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        InteractionHand hand = this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack copy = this.itemStack.get().copy();

        if (!this.applyInteractionPositionRotation(player, replay, tick))
        {
            return;
        }
        if (!InteractionActionSemantics.canReplayBlock(player, this.hit.getHitResult())
            || !copy.isItemEnabled(player.serverLevel().enabledFeatures()))
        {
            return;
        }

        ItemStack previous = player.getItemInHand(hand);
        player.setItemInHand(hand, copy);

        try
        {
            InteractionActionSemantics.withIsolatedItemCooldown(player, copy, () ->
                GunItem.withActor(actor, () -> copy.useOn(new UseOnContext(player.level(), player, hand, copy, this.hit.getHitResult()))));
        }
        finally
        {
            player.setItemInHand(hand, previous);
        }
    }

    @Override
    protected Clip create()
    {
        return new UseBlockItemActionClip();
    }
}
