package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public class BackgroundRendererMixin
{
    /**
     * Under the orthographic projection the whole frame sits at roughly the
     * same depth, but blocks near the screen edges are laterally further from
     * the camera point than the view distance — the fog paints them sky
     * colored, which reads as geometry vanishing at the edges (and Sodium
     * additionally culls whole sections beyond the fog end it reads back from
     * RenderSystem). Push the fog out of reach for the ortho frame.
     */
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void onApplyFog(Camera camera, FogRenderer.FogMode fogType, float viewDistance, boolean thickFog, float tickDelta, CallbackInfo info)
    {
        if (BBSRendering.isOrthoActive())
        {
            RenderSystem.setShaderFogStart(1_000_000F);
            RenderSystem.setShaderFogEnd(1_001_000F);
        }
    }
}
