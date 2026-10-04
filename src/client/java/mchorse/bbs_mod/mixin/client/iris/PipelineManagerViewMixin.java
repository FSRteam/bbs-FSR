package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.client.render.view.IrisViewBackend;
import mchorse.bbs_mod.utils.iris.IrisPipelineManagerAccess;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = PipelineManager.class, remap = false)
public abstract class PipelineManagerViewMixin implements IrisPipelineManagerAccess
{
    @Shadow
    @Final
    private Map<NamespacedId, WorldRenderingPipeline> pipelinesPerDimension;

    @Shadow
    private WorldRenderingPipeline pipeline;

    @Override
    public void bbs$setPipeline(WorldRenderingPipeline pipeline)
    {
        this.pipeline = pipeline;
    }

    @Override
    public Map<NamespacedId, WorldRenderingPipeline> bbs$getDimensionPipelines()
    {
        return this.pipelinesPerDimension;
    }

    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true)
    private void bbs$prepareViewPipeline(NamespacedId dimension, CallbackInfoReturnable<WorldRenderingPipeline> callback)
    {
        WorldRenderingPipeline selected = IrisViewBackend.selectPipeline(dimension);

        if (selected != null)
        {
            this.pipeline = selected;
            callback.setReturnValue(selected);
        }
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void bbs$destroyViews(CallbackInfo callback)
    {
        IrisViewBackend.releaseAll();
    }
}
