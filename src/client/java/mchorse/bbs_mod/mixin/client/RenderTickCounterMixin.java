package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.VideoRecorder;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeltaTracker.Timer.class)
public class RenderTickCounterMixin
{
    @Shadow
    private float deltaTicks;

    @Shadow
    private float deltaTickResidual;

    @Shadow
    private long lastMs;

    private int heldFrames;
    private long lastFrameTime;

    @Inject(method = "advanceTime", at = @At("HEAD"), cancellable = true)
    public void onAdvanceTime(long timeMillis, boolean processGameTime, CallbackInfoReturnable<Integer> info)
    {
        VideoRecorder videoRecorder = BBSModClient.getVideoRecorder();

        if (videoRecorder != null && videoRecorder.isRecording())
        {
            double captureFrameRate = videoRecorder.getCaptureFrameRate();
            int heldFrameLimit = Math.max(1, videoRecorder.getCapturedHeldFrames());

            if (!Double.isFinite(captureFrameRate) || captureFrameRate <= 0D)
            {
                captureFrameRate = BBSRendering.getVideoFrameRate();
            }

            if (videoRecorder.getCounter() == 0)
            {
                this.deltaTicks = 0F;
                this.deltaTickResidual = 0F;
            }

            if (this.heldFrames == 0)
            {
                if (videoRecorder.isCaptureFrameRateLimited())
                {
                    long frameInterval = Math.max(1L, (long) (1000F / captureFrameRate));

                    if (timeMillis - this.lastFrameTime < frameInterval)
                    {
                        BBSRendering.canRender = false;

                        info.setReturnValue(0);

                        return;
                    }

                    this.lastFrameTime = timeMillis;
                }

                this.lastMs = timeMillis;
                this.deltaTickResidual += 20F / (float) captureFrameRate;

                int ticks = (int) this.deltaTickResidual;

                this.deltaTickResidual -= (float) ticks;
                this.deltaTicks = this.deltaTickResidual;

                videoRecorder.serverTicks += ticks;
                BBSRendering.canRender = true;

                info.setReturnValue(ticks);
            }
            else
            {
                BBSRendering.canRender = false;

                info.setReturnValue(0);
            }

            this.heldFrames += 1;

            if (this.heldFrames >= heldFrameLimit)
            {
                this.heldFrames = 0;
            }
        }
        else
        {
            this.heldFrames = 0;
            this.lastFrameTime = 0L;
        }
    }
}
