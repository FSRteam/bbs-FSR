package mchorse.bbs_mod.cubic.animation;

import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.UseAnim;

/**
 * The item use state of a body, as everything that poses or dresses it needs it:
 * which vanilla use branch is running in a hand, for how long, and on which stack.
 *
 * <p>A film's actor never ticks an item use - the use is an action clip, and
 * {@link mchorse.bbs_mod.film.replays.ReplayItemUse} reads the state off it. Only
 * the client knows which replay drives which body, so the film controllers publish
 * the states through a {@link Source} they install, and everyone else asks here.</p>
 */
public class ItemUsePose
{
    /**
     * Which vanilla animation branch runs, how long it has been running
     * (fractional ticks), the stack driving it, how long the whole use lasts and
     * the body holding it - null when a replay's hand has none behind it (the
     * actor can be a bare form), which the vanilla timings below allow for.
     */
    public record Use(UseAnim action, float elapsed, ItemStack stack, float window, LivingEntity user)
    {}

    /** The living body behind an entity, if there is one - the timings below ask it. */
    public static LivingEntity livingOf(IEntity entity)
    {
        if (entity instanceof StubEntity stub)
        {
            return stub.getEntityOverride();
        }

        return entity instanceof MCEntity mc && mc.getMcEntity() instanceof LivingEntity living ? living : null;
    }

    /**
     * How long the item is used for, and how long a crossbow takes to pull.
     *
     * <p>Both run through the stack's enchantments, which read the holder's own
     * Random, so vanilla asks for the body holding it. A film's hand can have no
     * body behind it, and then the plain vanilla base answers - the very number
     * an unenchanted item gives.</p>
     */
    public static int maxUseTime(ItemStack stack, LivingEntity user)
    {
        if (user != null)
        {
            return stack.getUseDuration(user);
        }

        /* Only CrossbowItem reads the holder (its pull time plus three); every
         * other item answers from the stack alone. */
        return stack.getItem() instanceof CrossbowItem ? pullTime(stack, null) + 3 : stack.getUseDuration(null);
    }

    public static int pullTime(ItemStack stack, LivingEntity user)
    {
        /* CrossbowItem.getPullTime: floor(charge time * 20), and 1.25s is the base. */
        return user == null ? 25 : CrossbowItem.getChargeDuration(stack, user);
    }

    public interface Source
    {
        public Use get(IEntity entity, boolean mainHand);
    }

    private static Source source;
    private static boolean suppressed;

    public static void setSource(Source source)
    {
        ItemUsePose.source = source;
    }

    /**
     * The first person arm is drawn from the very same bones, and vanilla poses
     * it flat (its renderArm zeroes the arm's pitch) - so the use poses must not
     * leak into it.
     */
    public static void setSuppressed(boolean suppressed)
    {
        ItemUsePose.suppressed = suppressed;
    }

    public static Use get(IEntity entity, boolean mainHand)
    {
        if (suppressed || entity == null)
        {
            return null;
        }

        Use use = source == null ? null : source.get(entity, mainHand);

        return use == null ? live(entity, mainHand) : use;
    }

    /**
     * Whatever the entity is doing right now, for everyone the film doesn't
     * drive: a player morphed into a form outside of a film, and the player
     * being recorded (the clips that would answer for them are only written
     * when the take ends).
     */
    private static Use live(IEntity entity, boolean mainHand)
    {
        LivingEntity living = livingOf(entity);

        if (living == null || !living.isUsingItem())
        {
            return null;
        }

        if ((living.getUsedItemHand() == InteractionHand.MAIN_HAND) != mainHand)
        {
            return null;
        }

        ItemStack stack = living.getUseItem();
        UseAnim action = stack.getUseAnimation();

        return action == UseAnim.NONE ? null : new Use(action, living.getTicksUsingItem(), stack, maxUseTime(stack, living), living);
    }
}
