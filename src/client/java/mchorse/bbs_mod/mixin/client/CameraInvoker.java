package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Allows the isolated particle preview to provide its stand-in camera. */
@Mixin(Camera.class)
public interface CameraInvoker
{
    @Invoker("setPosition")
    void bbs$setPosition(double x, double y, double z);

    @Invoker("setRotation")
    void bbs$setRotation(float yaw, float pitch);
<<<<<<< HEAD

    @Invoker(value = "setRotation", remap = false)
    void bbs$setRotation(float yaw, float pitch, float roll);
=======
>>>>>>> origin/master
}
