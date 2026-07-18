package mchorse.bbs_mod.mixin;

import mchorse.bbs_mod.actions.AttackRecordingContext;
import mchorse.bbs_mod.actions.SuperFakePlayer;
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

    /* @Inject(method = "swingHand(Lnet/minecraft/util/Hand;Z)V", at = @At("HEAD"), cancellable = true)
    public void onSwingHand(Hand hand, boolean fromServerPlayer, CallbackInfo info)
    {
        info.cancel();
    } */
}
