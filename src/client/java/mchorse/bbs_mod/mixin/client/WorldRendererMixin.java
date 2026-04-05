package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin
{
    @Shadow
    public abstract RenderTarget entityTarget();

    @Inject(method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    public void onRenderSky(CallbackInfo info)
    {
        if (BBSSettings.chromaSkyEnabled.get())
        {
            Integer fromCurve = BBSRendering.getChromaSkyColorArgb();
            int argb = fromCurve != null ? fromCurve : BBSSettings.chromaSkyColor.get();
            Color color = Color.rgba(argb);

            GL11.glClearColor(color.r, color.g, color.b, 1F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            RenderSystem.setShaderFogColor(color.r, color.g, color.b, 1F);

            info.cancel();
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    public void onRenderSectionLayer(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo info)
    {
        if (BBSSettings.chromaSkyEnabled.get() && !BBSSettings.chromaSkyTerrain.get())
        {
            BBSRendering.onRenderChunkLayer(new MatrixStack());

            info.cancel();
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("TAIL"))
    public void onRenderChunkLayer(RenderType layer, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo info)
    {
        if (layer == RenderType.solid())
        {
            BBSRendering.onRenderChunkLayer(new MatrixStack());
        }
    }

    @Inject(at = @At("RETURN"), method = "allChanged")
    private void onAllChanged(CallbackInfo info)
    {
        BBSRendering.resizeExtraFramebuffers();
    }

    @Inject(at = @At("RETURN"), method = "resize")
    private void onResize(CallbackInfo info)
    {
        if (this.entityTarget() == null)
        {
            return;
        }

        BBSRendering.resizeExtraFramebuffers();
    }
}
