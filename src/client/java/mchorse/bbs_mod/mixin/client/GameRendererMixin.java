package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
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
    @WrapMethod(method = "render")
    private void bbs$ownRenderFrame(DeltaTracker deltaTracker, boolean renderLevel, Operation<Void> original)
    {
        try
        {
            BBSRendering.onRenderFrameBegin();
            original.call(deltaTracker, renderLevel);
        }
        finally
        {
            BBSRendering.onRenderFrameEnd();
        }
    }

    /**
     * This injection cancels bobbing when camera controller takes over
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    public void onBob(PoseStack poseStack, float partialTick, CallbackInfo ci)
    {
        if (BBSModClient.getCameraController().getCurrent() != null
            || BBSRendering.hasViewCamera())
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
        if (BBSRendering.hasViewCamera() && !BBSRendering.isIrisShadowPass())
        {
            double viewFov = BBSRendering.getSecondaryCameraFov();

            if (Double.isFinite(viewFov) && viewFov > 0D)
            {
                info.setReturnValue(viewFov);
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
        if (BBSRendering.hasViewCamera())
        {
            /* NeoForge's Camera rotation already contains this view's roll. */
            info.cancel();
            return;
        }

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
        if (BBSRendering.hasViewCamera())
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

    /** Use the view's camera and actual target aspect for terrain, entities and picking. */
    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    public void onGetProjectionMatrix(double fov, CallbackInfoReturnable<Matrix4f> info)
    {
        if (BBSRendering.hasViewCamera() && !BBSRendering.isIrisShadowPass())
        {
            info.setReturnValue(BBSRendering.getViewProjection((GameRenderer) (Object) this, info.getReturnValue()));
        }
    }

    @Inject(method = "pick(F)V", at = @At("HEAD"), cancellable = true)
    private void bbs$keepPrimaryHitResult(float transition, CallbackInfo info)
    {
        if (BBSRendering.isApplyingSecondaryCamera())
        {
            info.cancel();
        }
    }

    @ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"))
    private float bbs$disableCameraNausea(float strength)
    {
        return BBSRendering.hasViewCamera() ? 0F : strength;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void bbs$keepCameraDepth(int mask, boolean mac, Operation<Void> original)
    {
        /* The managed camera omits the hand pass, so its preparatory depth clear
         * must also be omitted before deferred forms and depth-based effects. */
        if (!BBSRendering.hasViewCamera())
        {
            original.call(mask, mac);
        }
    }

    @Inject(at = @At("HEAD"), method = "renderLevel")
    private void onWorldRenderBegin(DeltaTracker deltaTracker, CallbackInfo callbackInfo)
    {
        BBSRendering.onWorldRenderBegin();
    }

    /**
     * Own the frame boundary around the single vanilla renderLevel call. The
     * wrapper renders auxiliary Film views before invoking the original call;
     * renderLevel itself therefore never recursively schedules another view.
     */
    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void bbs$renderFilmViews(GameRenderer renderer, DeltaTracker deltaTracker, Operation<Void> original)
    {
        BBSRendering.renderWorldFrame(renderer, deltaTracker, () -> original.call(renderer, deltaTracker));
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
        /* Managed views already selected perspective or orthographic projection
         * at the common projection source. */
        if (BBSRendering.hasViewCamera())
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
        /* Only legacy controller rendering still needs the global ortho helper. */
        if (BBSRendering.hasViewCamera())
        {
            return projection;
        }

        return BBSRendering.getOrthoProjection((GameRenderer) (Object) this, projection, 0F);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void bbs$captureViewMatrices(LevelRenderer renderer, DeltaTracker tracker, boolean blockOutline, Camera camera,
                                        GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f view, Matrix4f projection,
                                        Operation<Void> original)
    {
        BBSRendering.captureViewMatrices(camera, view, projection);
        original.call(renderer, tracker, blockOutline && !BBSRendering.isApplyingSecondaryCamera(),
            camera, gameRenderer, lightTexture, view, projection);
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
        if (BBSRendering.isToggleFramebuffer() || Minecraft.getInstance().options.hideGui)
        {
            BBSRendering.onRenderBeforeScreen();
        }
    }
}
