package mchorse.bbs_mod.mixin;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.morphing.IMorphProvider;
import mchorse.bbs_mod.morphing.Morph;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * For some unknown reason to me, if these methods are used in {@link PlayerEntityMorphMixin}
 * then the world will be locked for some reason... by extracting write/read NBT method to
 * a separate mixin fixes it...
 */
@Mixin(Player.class)
public class PlayerEntityMixin
{
    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    public void onAddAdditionalSaveData(CompoundTag nbt, CallbackInfo info)
    {
        if (this instanceof IMorphProvider provider)
        {
            nbt.put("BBSMorph", provider.getMorph().toNbt());
        }
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    public void onReadAdditionalSaveData(CompoundTag nbt, CallbackInfo info)
    {
        if (this instanceof IMorphProvider provider)
        {
            if (nbt.contains("BBSMorph"))
            {
                provider.getMorph().fromNbt(nbt.getCompound("BBSMorph"));
            }
        }
    }

    @Inject(method = "getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;", at = @At("RETURN"), cancellable = true)
    public void onGetDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> info)
    {
        if (this instanceof IMorphProvider provider)
        {
            Form form = provider.getMorph().getForm();

            if (form != null && form.hitbox.get())
            {
                Player player = (Player) (Object) this;
                EntityDimensions dimensions = info.getReturnValue();
                float height = form.hitboxHeight.get() * (player.isCrouching() ? form.hitboxSneakMultiplier.get() : 1F);

                if (dimensions.fixed())
                {
                    info.setReturnValue(EntityDimensions.fixed(form.hitboxWidth.get(), height));
                }
                else
                {
                    info.setReturnValue(EntityDimensions.scalable(form.hitboxWidth.get(), height));
                }
            }
        }
    }

    @Inject(method = "getEyeHeight(Lnet/minecraft/world/entity/Pose;)F", at = @At("HEAD"), cancellable = true)
    public void onGetEyeHeight(Pose pose, CallbackInfoReturnable<Float> info)
    {
        if (this instanceof IMorphProvider provider)
        {
            Morph morph = provider.getMorph();

            if (morph != null)
            {
                Form form = morph.getForm();

                if (form != null && form.hitbox.get())
                {
                    Player player = (Player) (Object) this;
                    float height = form.hitboxHeight.get() * (player.isCrouching() ? form.hitboxSneakMultiplier.get() : 1F);

                    info.setReturnValue(form.hitboxEyeHeight.get() * height);
                }
            }
        }
    }
}
