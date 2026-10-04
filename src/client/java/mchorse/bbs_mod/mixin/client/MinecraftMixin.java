package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.forms.structure.StructureWand;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The structure wand takes the mouse buttons here, before the game decides what a click means:
 * this is the one place a click passes through whether it lands on a block, an entity or the air,
 * and the wand must act the same on all three.
 *
 * <p>NeoForge's own {@code InteractionKeyMappingTriggered} fires inside these two methods but only
 * after vanilla's early returns — survival's {@code missTime} counts ten ticks after every whiffed
 * swing, which is exactly the window the wand's next corner click lives in — so the hook sits at
 * the head, ahead of all of them. Yarn called these {@code doAttack}/{@code doItemUse}.</p>
 */
@Mixin(Minecraft.class)
public class MinecraftMixin
{
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    public void wandAttack(CallbackInfoReturnable<Boolean> cir)
    {
        if (StructureWand.onAttack())
        {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    public void wandUse(CallbackInfo ci)
    {
        if (StructureWand.onUse())
        {
            ci.cancel();
        }
    }
}
