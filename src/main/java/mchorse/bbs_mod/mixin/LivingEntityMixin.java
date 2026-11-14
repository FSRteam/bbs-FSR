package mchorse.bbs_mod.mixin;

import mchorse.bbs_mod.actions.AttackRecordingContext;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.item.ReleaseUseItemActionClip;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingEntityMixin
{
    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V", at = @At("HEAD"))
    public void onActuallyHurt(DamageSource source, float amount, CallbackInfo info)
    {
        Entity attacker = source.getEntity();

        if (attacker instanceof ServerPlayer player && !(attacker instanceof SuperFakePlayer) && attacker == source.getDirectEntity())
        {
            Entity target = (Entity) (Object) this;

            AttackRecordingContext.recordDamage(player, target, amount);
        }
    }

    /* NeoForge may cancel a release before the item callback is reached. */
    @Inject(method = "releaseUsingItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;releaseUsing(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V"))
    private void bbsRecordRelease(CallbackInfo info)
    {
        if (!((Object) this instanceof ServerPlayer player) || ((Object) this).getClass() != ServerPlayer.class)
        {
            return;
        }

        ItemStack active = player.getUseItem();

        if (active.isEmpty())
        {
            return;
        }

        InteractionHand hand = player.getUsedItemHand();
        int charge = active.getUseDuration(player) - player.getUseItemRemainingTicks();
        ItemStack stack = active.copy();
        ItemStack projectile = player.getProjectile(active).copy();

        ItemStack recordedProjectile = projectile;
        boolean riptide = charge >= 10
            && EnchantmentHelper.getTridentSpinAttackStrength(active, player) > 0F
            && player.isInWaterOrRain();

        BBSMod.getActions().addAction(player, () ->
        {
            ReleaseUseItemActionClip clip = new ReleaseUseItemActionClip();
            clip.itemStack.set(stack);
            clip.hand.set(hand == InteractionHand.MAIN_HAND);
            clip.charge.set(charge);
            clip.projectile.set(recordedProjectile);
            clip.riptide.set(riptide);
            return clip;
        });
    }

    /* @Inject(method = "swingHand(Lnet/minecraft/util/Hand;Z)V", at = @At("HEAD"), cancellable = true)
    public void onSwingHand(Hand hand, boolean fromServerPlayer, CallbackInfo info)
    {
        info.cancel();
    } */
}
