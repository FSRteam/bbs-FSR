package mchorse.bbs_mod.utils.iris;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

import java.util.Map;

public interface IrisPipelineManagerAccess
{
    void bbs$setPipeline(WorldRenderingPipeline pipeline);

    Map<NamespacedId, WorldRenderingPipeline> bbs$getDimensionPipelines();
}
