package mchorse.bbs_mod.mixin.client.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(IrisRenderingPipeline.class)
public interface IrisRenderingPipelineAccessor
{
    @Accessor(value = "loadedShaders", remap = false)
    public Set bbs$loadedShaders();

    @Accessor(value = "shaderStorageBufferHolder", remap = false)
    ShaderStorageBufferHolder bbs$getStorageBuffers();

    @Accessor(value = "isMainBound", remap = false)
    boolean bbs$isMainBound();

    @Accessor(value = "initializedBlockIds", remap = false)
    boolean bbs$isBlockIdsInitialized();

    @Accessor(value = "initializedBlockIds", remap = false)
    void bbs$setInitializedBlockIds(boolean initialized);
}
