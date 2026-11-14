package mchorse.bbs_mod.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccess
{
    @Accessor("useItem")
    ItemStack bbs$getUseItem();

    @Accessor("useItem")
    void bbs$setUseItem(ItemStack stack);

    @Accessor("useItemRemaining")
    int bbs$getUseItemRemaining();

    @Accessor("useItemRemaining")
    void bbs$setUseItemRemaining(int ticks);

    @Accessor("autoSpinAttackTicks")
    int bbs$getRiptideTicks();

    @Accessor("autoSpinAttackTicks")
    void bbs$setRiptideTicks(int ticks);

    @Invoker("setLivingEntityFlag")
    void bbs$setLivingFlag(int index, boolean value);
}
