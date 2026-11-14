package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.film.FilmPlayerPose;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps vanilla pose recomputation from replacing a film pose between world ticks. */
@Mixin(Player.class)
public class PlayerEntityFilmPoseMixin
{
    @Inject(method = "updatePose", at = @At("HEAD"), cancellable = true)
    private void bbsFilmUpdatePose(CallbackInfo info)
    {
        if ((Object) this instanceof LocalPlayer player)
        {
            FilmPlayerPose pose = FilmPlayerPose.get(player.getId());

            if (pose != null)
            {
                player.setPose(pose.pose());
                info.cancel();
            }
        }
    }
}
