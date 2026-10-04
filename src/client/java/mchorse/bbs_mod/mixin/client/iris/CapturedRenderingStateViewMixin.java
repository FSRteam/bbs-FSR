package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.IrisStateSnapshot;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = CapturedRenderingState.class, remap = false)
public abstract class CapturedRenderingStateViewMixin implements IrisStateSnapshot
{
    @Shadow
    private Matrix4fc gbufferModelView;

    @Shadow
    private Matrix4fc gbufferProjection;

    @Shadow
    private Vector3d fogColor;

    @Shadow
    private float fogDensity;

    @Shadow
    private float darknessLightFactor;

    @Shadow
    private float tickDelta;

    @Shadow
    private float realTickDelta;

    @Shadow
    private int currentRenderedBlockEntity;

    @Shadow
    private int currentRenderedEntity;

    @Shadow
    private int currentRenderedItem;

    @Shadow
    private float currentAlphaTest;

    @Shadow
    private float cloudTime;

    @Override
    public Runnable bbs$captureState()
    {
        Matrix4fc savedGbufferModelView = this.gbufferModelView == null ? null : new Matrix4f(this.gbufferModelView);
        Matrix4fc savedGbufferProjection = this.gbufferProjection == null ? null : new Matrix4f(this.gbufferProjection);
        Vector3d savedFogColor = this.fogColor == null ? null : new Vector3d(this.fogColor);
        float savedFogDensity = this.fogDensity;
        float savedDarknessLightFactor = this.darknessLightFactor;
        float savedTickDelta = this.tickDelta;
        float savedRealTickDelta = this.realTickDelta;
        int savedCurrentRenderedBlockEntity = this.currentRenderedBlockEntity;
        int savedCurrentRenderedEntity = this.currentRenderedEntity;
        int savedCurrentRenderedItem = this.currentRenderedItem;
        float savedCurrentAlphaTest = this.currentAlphaTest;
        float savedCloudTime = this.cloudTime;

        return () ->
        {
            this.gbufferModelView = savedGbufferModelView;
            this.gbufferProjection = savedGbufferProjection;
            this.fogColor = savedFogColor;
            this.fogDensity = savedFogDensity;
            this.darknessLightFactor = savedDarknessLightFactor;
            this.tickDelta = savedTickDelta;
            this.realTickDelta = savedRealTickDelta;
            this.currentRenderedBlockEntity = savedCurrentRenderedBlockEntity;
            this.currentRenderedEntity = savedCurrentRenderedEntity;
            this.currentRenderedItem = savedCurrentRenderedItem;
            this.currentAlphaTest = savedCurrentAlphaTest;
            this.cloudTime = savedCloudTime;
        };
    }
}
