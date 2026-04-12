package mchorse.bbs_mod.mixin.client.sodium;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.caffeinemc.mods.sodium.client.render.vertex.buffer.BufferBuilderExtension;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BufferBuilder.class)
public interface SodiumBufferBuilderAccessor
    extends BufferBuilderExtension
{
}
