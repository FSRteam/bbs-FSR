package mchorse.bbs_mod.api.compat;

import mchorse.bbs_mod.mixin.LivingEntityAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Vanilla item-use and riptide state exposed without requiring an addon's own mixins. */
public final class VanillaAccess
{
    private VanillaAccess()
    {}

    public static ItemStack getActiveItemStack(LivingEntity entity)
    {
        return ((LivingEntityAccess) entity).bbs$getUseItem();
    }

    public static void setActiveItemStack(LivingEntity entity, ItemStack stack)
    {
        ((LivingEntityAccess) entity).bbs$setUseItem(stack);
    }

    public static int getItemUseTimeLeft(LivingEntity entity)
    {
        return ((LivingEntityAccess) entity).bbs$getUseItemRemaining();
    }

    public static void setItemUseTimeLeft(LivingEntity entity, int ticks)
    {
        ((LivingEntityAccess) entity).bbs$setUseItemRemaining(ticks);
    }

    public static void setLivingFlag(LivingEntity entity, int index, boolean value)
    {
        ((LivingEntityAccess) entity).bbs$setLivingFlag(index, value);
    }

    public static int getRiptideTicks(LivingEntity entity)
    {
        return ((LivingEntityAccess) entity).bbs$getRiptideTicks();
    }

    public static void setRiptideTicks(LivingEntity entity, int ticks)
    {
        ((LivingEntityAccess) entity).bbs$setRiptideTicks(ticks);
    }
}
