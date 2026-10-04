package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.IrisViewState;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderTargets.class, remap = false)
public abstract class IrisRenderTargetsViewMixin
{
    @Shadow
    private int currentDepthTexture;

    @Shadow
    private int cachedDepthBufferVersion;

    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void bbs$trackDepthTarget(int depthBufferVersion, int depthTexture, int width, int height,
        DepthBufferFormat format, PackDirectives directives, CallbackInfoReturnable<Boolean> callback)
    {
        if (IrisViewState.current() != null && this.currentDepthTexture != depthTexture
            && this.cachedDepthBufferVersion == depthBufferVersion)
        {
            /* Separate preview targets can share a local version. Let Iris reattach
             * their actual depth texture without resizing or discarding history. */
            this.cachedDepthBufferVersion = ~depthBufferVersion;
        }
    }
}
