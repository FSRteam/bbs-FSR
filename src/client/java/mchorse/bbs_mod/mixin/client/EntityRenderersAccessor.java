package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(EntityRenderers.class)
public interface EntityRenderersAccessor
{
    @Accessor("PROVIDERS")
    Map<EntityType<?>, EntityRendererProvider<?>> bbs$getProviders();
}
