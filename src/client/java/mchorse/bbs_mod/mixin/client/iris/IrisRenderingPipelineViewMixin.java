package mchorse.bbs_mod.mixin.client.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import mchorse.bbs_mod.utils.iris.IrisPipelineResources;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.ClearPass;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class IrisRenderingPipelineViewMixin implements IrisPipelineResources
{
    @Shadow
    private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Shadow
    private ImmutableList<ClearPass> shadowClearPassesFull;

    @Unique
    private ViewResourceOwner bbs$resources;

    @Unique
    private boolean bbs$resetHistory;

    @Override
    public void bbs$setResourceOwner(ViewResourceOwner owner)
    {
        this.bbs$resources = owner;
    }

    @Override
    public void bbs$releaseResources()
    {
        ViewResourceOwner owner = this.bbs$resources;

        this.bbs$resources = null;

        if (owner != null)
        {
            owner.close();
        }
    }

    @Override
    public void bbs$resetTemporalHistory()
    {
        this.bbs$resetHistory = true;
    }

    @ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/targets/RenderTargets;isFullClearRequired()Z"))
    private boolean bbs$clearViewHistory(boolean fullClear)
    {
        return fullClear || this.bbs$resetHistory;
    }

    @ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "FIELD",
        target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;shadowClearPasses:Lcom/google/common/collect/ImmutableList;",
        opcode = Opcodes.GETFIELD))
    private ImmutableList<ClearPass> bbs$clearShadowHistory(ImmutableList<ClearPass> passes)
    {
        /* Reuse Iris' existing clear framebuffers instead of rebuilding shadow targets. */
        return this.bbs$resetHistory ? this.shadowClearPassesFull : passes;
    }

    @Inject(method = "beginLevelRendering", at = @At("RETURN"))
    private void bbs$finishHistoryReset(CallbackInfo callback)
    {
        this.bbs$resetHistory = false;
    }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void bbs$bindViewStorage(CallbackInfo callback)
    {
        if (this.shaderStorageBufferHolder != null)
        {
            this.shaderStorageBufferHolder.setupBuffers();
        }
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void bbs$releaseRemainingResources(CallbackInfo callback)
    {
        this.bbs$releaseResources();
    }
}
