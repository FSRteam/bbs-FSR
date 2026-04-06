package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EntityRenderDispatcher.class)
public interface EntityRendererDispatcherInvoker
{
    @Invoker("renderShadow")
    public static void bbs$renderShadow(PoseStack matrices, MultiBufferSource vertexConsumers, Entity entity, float opacity, float tickDelta, LevelReader world, float radius)
    {}
}
