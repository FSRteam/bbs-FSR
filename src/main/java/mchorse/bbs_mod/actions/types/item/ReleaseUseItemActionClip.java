package mchorse.bbs_mod.actions.types.item;

import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.mc.ValueItemStack;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;

/**
 * Vanilla's "released the use button" moment: a drawn bow firing its arrow, a
 * trident flying off, a crossbow snapping loaded. Playback re-runs
 * ItemStack.onStoppedUsing() on the fake player, so the projectile is a real
 * server entity with real physics, aimed by the replay's recorded rotations.
 */
public class ReleaseUseItemActionClip extends ItemActionClip
{
    /** Ticks the item was held drawn; restores the vanilla charge on playback. */
    public final ValueInt charge = new ValueInt("charge", 0, 0, 72000);

    /**
     * The ammo vanilla picked during the take. Lent to the fake player's other
     * hand, because its own inventory has no arrows to shoot.
     */
    public final ValueItemStack projectile = new ValueItemStack("projectile");

    /**
     * The take riptided instead of throwing: a riptide trident released while
     * touching water or rain launches its owner and stays in their hand. That
     * condition is the WORLD's, not the item's, and the fake player replaying
     * the release stands dry in whatever world the film plays in - so it's
     * decided at record time and carried here.
     */
    public final ValueBoolean riptide = new ValueBoolean("riptide", false);

    public ReleaseUseItemActionClip()
    {
        super();

        this.add(this.charge);
        this.add(this.projectile);
        this.add(this.riptide);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        InteractionHand hand = this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        InteractionHand other = this.hand.get() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = this.itemStack.get().copy();
        ItemStack projectile = this.projectile.get();

        this.applyPositionRotation(player, replay, tick);

        if (this.riptide.get())
        {
            this.applyRiptide(actor, player, stack);

            return;
        }

        /* Use clips leave the fake player's "using an item" flag raised, and a
         * raised flag makes setCurrentHand() refuse to work */
        player.stopUsingItem();
        player.setItemInHand(hand, stack);

        if (!projectile.isEmpty())
        {
            player.setItemInHand(other, projectile.copy());
        }

        player.startUsingItem(hand);
        stack.releaseUsing(player.level(), player, Math.max(0, stack.getUseDuration(player) - this.charge.get()));
        player.stopUsingItem();
        player.setItemInHand(hand, ItemStack.EMPTY);
        player.setItemInHand(other, ItemStack.EMPTY);
    }

    /**
     * The riptide half of {@code TridentItem.onStoppedUsing} (javap 1.21.1):
     * no trident flies, the owner spins for 20 ticks and the sound the riptide
     * enchantment carries plays. Vanilla's shove ({@code addVelocity}) is
     * deliberately left out - the film already knows where the body goes, and
     * a push on top of that would fight the recorded flight.
     *
     * <p>{@code onStoppedUsing} is not re-run at all here: the fake player
     * stands dry in the playback world, so vanilla would take the other branch
     * and throw the trident the take never let go of.</p>
     */
    private void applyRiptide(LivingEntity actor, SuperFakePlayer player, ItemStack stack)
    {
        /* 1.21.1 keeps the three riptide sounds in the enchantment's own data
         * instead of picking one by level, and answers with the throw sound for
         * a trident that spins without one. */
        /* The spin is tracked data, so this is what makes every client show
         * the body whirling - the animator poses it from the same flag.
         * useRiptide() belongs to PlayerEntity; an actor is not one, so it
         * gets the two fields that method sets (javap 1.21.1), and vanilla's
         * own tick counts the spin down and clears the flag from there. */
        if (actor instanceof Player playerActor)
        {
            playerActor.startAutoSpinAttack(20, 8F, stack);
        }
        /* Non-player morph actors have no vanilla riptide state to expose. */

    }

    @Override
    protected Clip create()
    {
        return new ReleaseUseItemActionClip();
    }
}
