package mchorse.bbs_mod.mixin.client.iris;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = ShaderStorageBufferHolder.class, remap = false)
public interface ShaderStorageBufferHolderAccessor
{
    @Accessor("ACTIVE_BUFFERS")
    static List<ShaderStorageBuffer> bbs$getActiveBuffers()
    {
        throw new UnsupportedOperationException("Mixin accessor was not applied");
    }
}
