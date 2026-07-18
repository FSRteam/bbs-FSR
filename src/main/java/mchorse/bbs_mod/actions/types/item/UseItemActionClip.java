package mchorse.bbs_mod.actions.types.item;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.items.GunItem;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public class UseItemActionClip extends ItemActionClip
{
    public final ValueBoolean secondaryUse = new ValueBoolean("secondary_use", false);

    public UseItemActionClip()
    {
        this.add(this.secondaryUse);
    }

    @Override
    protected boolean canPersist(BaseValue value)
    {
        if (value == this.secondaryUse && !this.secondaryUse.get())
        {
            return false;
        }

        return super.canPersist(value);
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

        if (!InteractionActionSemantics.canReplayItemUse(copy.isEmpty(), copy.isItemEnabled(player.serverLevel().enabledFeatures())))
        {
            return;
        }

        if (!this.applyInteractionPositionRotation(player, replay, tick))
        {
            return;
        }
        ItemStack previous = player.getItemInHand(hand);
        player.setItemInHand(hand, copy);

        try
        {
            InteractionActionSemantics.withSecondaryUse(player, this.secondaryUse.get(), () ->
                InteractionActionSemantics.withIsolatedItemCooldown(player, copy, () ->
                    GunItem.withActor(actor, () -> player.gameMode.useItem(player, player.level(), copy, hand))));
        }
        finally
        {
            player.setItemInHand(hand, previous);
        }
    }

    @Override
    protected Clip create()
    {
        return new UseItemActionClip();
    }
}
