package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(BlockEntityRenderers.class)
public interface BlockEntityRenderersAccessor
{
    @Accessor("PROVIDERS")
    Map<BlockEntityType<?>, BlockEntityRendererProvider<?>> bbs$getProviders();
}
