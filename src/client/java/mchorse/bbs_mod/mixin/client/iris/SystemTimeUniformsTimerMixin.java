package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.VideoRecorder;
import mchorse.bbs_mod.utils.iris.IrisViewState;
import mchorse.bbs_mod.utils.iris.ViewSampleClock;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SystemTimeUniforms.Timer.class)
public class SystemTimeUniformsTimerMixin
{
    @Shadow(remap = false)
    private float frameTimeCounter;

    @Shadow(remap = false)
    private float lastFrameTime;

    @Inject(method = "getLastFrameTime", at = @At("HEAD"), cancellable = true, remap = false)
    private void bbs$viewSampleTime(CallbackInfoReturnable<Float> callback)
    {
        ViewSampleClock.Sample sample = IrisViewState.sample();

        if (sample != null)
        {
            callback.setReturnValue(sample.frameTime());
        }
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true, remap = false)
    public void onBeginFrame(CallbackInfo info)
    {
        VideoRecorder videoRecorder = BBSModClient.getVideoRecorder();

        if (videoRecorder != null && videoRecorder.isRecording())
        {
            double capturedRate = videoRecorder.getCaptureFrameRate();
            float videoFrameRate = Double.isFinite(capturedRate) && capturedRate > 0D
                ? (float) capturedRate : BBSRendering.getVideoFrameRate();
            /* DeltaTracker already owns held frames and rate limiting for the scene. */
            this.lastFrameTime = ViewSampleClock.exportFrameTime(videoFrameRate, BBSRendering.canRender);
            this.frameTimeCounter = (this.frameTimeCounter + this.lastFrameTime) % 3600F;

            info.cancel();
        }
    }
}
