package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.client.BBSRendering;
import net.irisshaders.iris.shadows.ShadowMatrices;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShadowMatrices.class)
public class ShadowMatricesMixin
{
    @Inject(method = "createBaselineModelViewMatrix", at = @At("RETURN"), remap = false)
    private static void rotateSunHorizontally(PoseStack matrices, float shadowAngle, float sunPathRotation, float nearPlane, float farPlane, CallbackInfo info)
    {
        float rotation = BBSRendering.getSunHorizontalRotation();

        if (rotation != 0F)
        {
            /* The shadow view needs the inverse of the light's world rotation. */
            matrices.mulPose(Axis.YP.rotationDegrees(-rotation));
        }
    }
}
