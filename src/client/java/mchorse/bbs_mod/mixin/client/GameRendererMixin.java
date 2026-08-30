package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.camera.controller.ICameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.items.GunZoom;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin
{
    /**
     * This injection cancels bobbing when camera controller takes over
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    public void onBob(PoseStack poseStack, float partialTick, CallbackInfo ci)
    {
        if (BBSModClient.getCameraController().getCurrent() != null
            || BBSRendering.isApplyingSecondaryCamera())
        {
            ci.cancel();
        }
    }

    /**
     * This injection replaces the camera FOV when camera controller takes over
     */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    public void onGetFov(Camera camera, float partialTick, boolean useConfiguredFov, CallbackInfoReturnable<Double> info)
    {
        if (BBSRendering.isApplyingSecondaryCamera())
        {
            double secondaryFov = BBSRendering.getSecondaryCameraFov();

            if (Double.isFinite(secondaryFov) && secondaryFov > 0D)
            {
                info.setReturnValue(secondaryFov);
            }

            return;
        }

        GunZoom gunZoom = BBSModClient.getGunZoom();

        if (gunZoom != null)
        {
            info.setReturnValue((double) gunZoom.getFOV(info.getReturnValue().floatValue()));

            return;
        }

        CameraController controller = BBSModClient.getCameraController();

        /* A secondary pass already owns its Camera and projection. Applying the
         * main controller's FOV here would pair the secondary view matrix with
         * the main monitor's lens and distort entity placement. */
        if (controller.getCurrent() != null
            && !BBSRendering.isIrisShadowPass()
            && !BBSRendering.isApplyingSecondaryCamera())
        {
            info.setReturnValue(controller.getFOV());
        }
    }

    /**
     * This injection replaces the camera roll when camera controller takes over
     */
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    public void onBobHurt(PoseStack poseStack, float tickDelta, CallbackInfo info)
    {
        CameraController controller = BBSModClient.getCameraController();

        if (controller.getCurrent() != null
            && !BBSRendering.isIrisShadowPass()
            && !BBSRendering.isApplyingSecondaryCamera())
        {
            poseStack.mulPose(Axis.ZP.rotationDegrees(controller.getRoll()));

            info.cancel();
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    public void onRenderHand(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo info)
    {
        if (BBSRendering.isApplyingSecondaryCamera())
        {
            info.cancel();

            return;
        }

        ICameraController current = BBSModClient.getCameraController().getCurrent();

        if (current instanceof PlayCameraController)
        {
            info.cancel();
        }
    }

    /**
     * This injection replaces the projection matrix during the secondary
     * off-screen pass. Fitting at the single source (getProjectionMatrix)
     * means the matrix stored into RenderSystem by resetProjectionMatrix and
     * the matrix passed as the renderLevel argument are the same object, so
     * entities (BufferUploader reads RenderSystem) and terrain (argument)
     * can never disagree after a window resize or export-size state flip.
     */
    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    public void onGetProjectionMatrix(double fov, CallbackInfoReturnable<Matrix4f> info)
    {
        if (BBSRendering.isApplyingSecondaryCamera())
        {
            info.setReturnValue(BBSRendering.fitSecondaryProjection(info.getReturnValue()));
        }
    }

    @Inject(at = @At("HEAD"), method = "renderLevel")
    private void onWorldRenderBegin(DeltaTracker deltaTracker, CallbackInfo callbackInfo)
    {
        BBSRendering.onWorldRenderBegin();
    }

    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
        ),
        index = 2
    )
    private Matrix4f onSetupFrustumProjection(Matrix4f projection)
    {
        /* The secondary pass gets its aspect-correct perspective matrix from
         * getProjectionMatrix(). Applying the main view's orthographic helper
         * here would make the frustum use a different projection than entity
         * buffers after a window resize. */
        if (BBSRendering.isApplyingSecondaryCamera())
        {
            return projection;
        }

        return BBSRendering.getOrthoProjection((GameRenderer) (Object) this, projection, 20F);
    }

    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
        ),
        index = 6
    )
    private Matrix4f onRenderProjection(Matrix4f projection)
    {
        /* Keep the secondary pass on the single projection returned by
         * getProjectionMatrix(). The main-view ortho conversion is a separate
         * concern and must not run for preview2. */
        if (BBSRendering.isApplyingSecondaryCamera())
        {
            return projection;
        }

        Matrix4f ortho = BBSRendering.getOrthoProjection((GameRenderer) (Object) this, projection, 0F);
        return ortho;
    }

    @Inject(at = @At("RETURN"), method = "renderLevel")
    private void onWorldRenderEnd(DeltaTracker deltaTracker, CallbackInfo callbackInfo)
    {
        if (!BBSRendering.isIrisShadowPass())
        {
            FormTranslucentQueue.flush();
        }

        BBSRendering.onWorldRenderEnd();
    }

    @Inject(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;hideGui:Z", opcode = Opcodes.GETFIELD, ordinal = 0))
    private void onBeforeHudRendering(CallbackInfo info)
    {
        ICameraController current = BBSModClient.getCameraController().getCurrent();

        /* The secondary off-screen pass runs whenever the Shift+M debug toggle
         * is on, so it is visible in normal gameplay with a HUD. The export
         * path additionally needs hideGui && current == null, which still holds
         * below. */
        if (BBSRendering.isSecondaryViewEnabled() || (Minecraft.getInstance().options.hideGui && current == null))
        {
            BBSRendering.onRenderBeforeScreen();
        }
    }
}
