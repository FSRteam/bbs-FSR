package mchorse.bbs_mod.actions.types.blocks;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.actions.values.ValueBlockHitResult;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.mc.ValueItemStack;
import mchorse.bbs_mod.items.GunItem;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

public class InteractBlockActionClip extends ActionClip
{
    public final ValueBlockHitResult hit = new ValueBlockHitResult("hit");
    public final ValueBoolean hand = new ValueBoolean("hand", true);
    public final ValueItemStack itemStack = new ValueItemStack("stack");
    public final ValueBoolean fullDispatch = new ValueBoolean("full_dispatch", false);
    public final ValueBoolean secondaryUse = new ValueBoolean("secondary_use", false);

    public InteractBlockActionClip()
    {
        super();

        this.add(this.hit);
        this.add(this.hand);
        this.add(this.itemStack);
        this.add(this.fullDispatch);
        this.add(this.secondaryUse);
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        super.shift(dx, dy, dz);

        this.hit.shift(dx, dy, dz);
    }

    @Override
    protected boolean canPersist(BaseValue value)
    {
        if (!this.fullDispatch.get() && (value == this.itemStack || value == this.fullDispatch || value == this.secondaryUse))
        {
            return false;
        }

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
        if (!this.applyInteractionPositionRotation(player, replay, tick))
        {
            return;
        }

        BlockHitResult result = this.hit.getHitResult();

        if (!InteractionActionSemantics.canReplayBlock(player, result))
        {
            return;
        }

        if (!this.fullDispatch.get())
        {
            player.level().getBlockState(result.getBlockPos()).useWithoutItem(player.level(), player, result);

            return;
        }

        InteractionHand hand = this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = this.itemStack.get().copy();

        if (!stack.isItemEnabled(player.serverLevel().enabledFeatures()))
        {
            return;
        }

        ItemStack previous = player.getItemInHand(hand);
        player.setItemInHand(hand, stack);

        try
        {
            InteractionActionSemantics.withSecondaryUse(player, this.secondaryUse.get(), () ->
                InteractionActionSemantics.withIsolatedItemCooldown(player, stack, () ->
                    GunItem.withActor(actor, () -> player.gameMode.useItemOn(player, player.level(), stack, hand, result))));
        }
        finally
        {
            player.setItemInHand(hand, previous);
        }
    }

    @Override
    protected Clip create()
    {
        return new InteractBlockActionClip();
    }
}
