package mchorse.bbs_mod.mixin;

import mchorse.bbs_mod.actions.AttackRecordingContext;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.item.ReleaseUseItemActionClip;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
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

    @Inject(method = "stopUsingItem", at = @At("HEAD"))
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
        ItemStack projectile = ItemStack.EMPTY;

        if (projectile.isEmpty() && (stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT)) && player.getAbilities().instabuild)
        {
            projectile = new ItemStack(Items.ARROW);
        }

        ItemStack recordedProjectile = projectile;
        BBSMod.getActions().addAction(player, () ->
        {
            ReleaseUseItemActionClip clip = new ReleaseUseItemActionClip();
            clip.itemStack.set(stack);
            clip.hand.set(hand == InteractionHand.MAIN_HAND);
            clip.charge.set(charge);
            clip.projectile.set(recordedProjectile);
            clip.riptide.set(false);
            return clip;
        });
    }

    /* @Inject(method = "swingHand(Lnet/minecraft/util/Hand;Z)V", at = @At("HEAD"), cancellable = true)
    public void onSwingHand(Hand hand, boolean fromServerPlayer, CallbackInfo info)
    {
        info.cancel();
    } */
}
