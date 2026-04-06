package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public class WindowMixin
{
    @Shadow
    private int width;

    @Shadow
    private int height;

    @Shadow
    private int framebufferWidth;

    @Shadow
    private int framebufferHeight;

    @Shadow
    private int guiScaledWidth;

    @Shadow
    private int guiScaledHeight;

    @Shadow
    private double guiScale;

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    public void onGetWidth(CallbackInfoReturnable<Integer> info)
    {
        if (BBSRendering.canReplaceFramebuffer())
        {
            info.setReturnValue(BBSRendering.getVideoWidth());
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    public void onGetHeight(CallbackInfoReturnable<Integer> info)
    {
        if (BBSRendering.canReplaceFramebuffer())
        {
            info.setReturnValue(BBSRendering.getVideoHeight());
        }
    }

    @Inject(method = "getScreenWidth", at = @At("HEAD"), cancellable = true)
    public void onGetFramebufferWidth(CallbackInfoReturnable<Integer> info)
    {
        if (BBSRendering.canReplaceFramebuffer())
        {
            info.setReturnValue((int) (BBSRendering.getVideoWidth() * BBSModClient.getOriginalFramebufferScale()));
        }
    }

    @Inject(method = "getScreenHeight", at = @At("HEAD"), cancellable = true)
    public void onGetFramebufferHeight(CallbackInfoReturnable<Integer> info)
    {
        if (BBSRendering.canReplaceFramebuffer())
        {
            info.setReturnValue((int) (BBSRendering.getVideoHeight() * BBSModClient.getOriginalFramebufferScale()));
        }
    }

    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    public void onGetScaledWidth(CallbackInfoReturnable<Integer> info)
    {
        if (BBSRendering.canReplaceFramebuffer())
        {
            info.setReturnValue((int) (BBSRendering.getVideoWidth() / this.guiScale * BBSModClient.getOriginalFramebufferScale()));
        }
    }

    @Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
    public void onGetScaledHeight(CallbackInfoReturnable<Integer> info)
    {
        if (BBSRendering.canReplaceFramebuffer())
        {
            info.setReturnValue((int) (BBSRendering.getVideoHeight() / this.guiScale * BBSModClient.getOriginalFramebufferScale()));
        }
    }
}
