package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.film.FilmPlayerPose;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers crouch queries from the film while keyboard input and vanilla ticks continue to run. */
@Mixin(LocalPlayer.class)
public class ClientPlayerEntityFilmPoseMixin
{
    @Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
    private void bbsFilmIsCrouching(CallbackInfoReturnable<Boolean> info)
    {
        FilmPlayerPose pose = FilmPlayerPose.get(((LocalPlayer) (Object) this).getId());

        if (pose != null)
        {
            info.setReturnValue(pose.sneaking());
        }
    }

    @Inject(method = "hasPose", at = @At("HEAD"), cancellable = true)
    private void bbsFilmHasPose(Pose requested, CallbackInfoReturnable<Boolean> info)
    {
        FilmPlayerPose pose = FilmPlayerPose.get(((LocalPlayer) (Object) this).getId());

        if (pose != null)
        {
            info.setReturnValue(pose.pose() == requested);
        }
    }
}
