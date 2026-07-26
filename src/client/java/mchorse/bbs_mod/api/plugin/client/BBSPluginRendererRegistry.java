package mchorse.bbs_mod.api.plugin.client;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public interface BBSPluginRendererRegistry
{
    <T extends Entity> BBSRegistrationResult registerEntity(EntityType<T> type, EntityRendererProvider<T> provider);

    <T extends BlockEntity> BBSRegistrationResult registerBlockEntity(BlockEntityType<T> type, BlockEntityRendererProvider<? super T> provider);
}
