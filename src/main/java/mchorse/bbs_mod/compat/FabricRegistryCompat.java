package mchorse.bbs_mod.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Transitional registration facade that exposes vanilla builders.
 */
public final class FabricRegistryCompat
{
    private FabricRegistryCompat() {}

    public static BlockBehaviour.Properties blockSettings()
    {
        return BlockBehaviour.Properties.of();
    }

    public static <T extends Entity> EntityType<T> buildEntityTypeWithBlockRange(
        String id,
        MobCategory spawnGroup,
        EntityType.EntityFactory<T> factory,
        EntityDimensions dimensions,
        int trackingRangeBlocks,
        int updateRate
    )
    {
        return EntityType.Builder.of(factory, spawnGroup)
            .sized(dimensions.width(), dimensions.height())
            .clientTrackingRange(trackingRangeBlocks)
            .updateInterval(updateRate)
            .build(id);
    }

    public static <T extends Entity> EntityType<T> buildEntityTypeWithChunkRange(
        String id,
        MobCategory spawnGroup,
        EntityType.EntityFactory<T> factory,
        EntityDimensions dimensions,
        int trackingRangeChunks,
        int updateRate
    )
    {
        return buildEntityTypeWithBlockRange(
            id,
            spawnGroup,
            factory,
            dimensions,
            trackingRangeChunks * 16,
            updateRate
        );
    }

    public static <T extends BlockEntity> BlockEntityType<T> buildBlockEntityType(
        BlockEntityType.BlockEntitySupplier<? extends T> factory,
        Block... blocks
    )
    {
        return BlockEntityType.Builder.of(factory, blocks).build(null);
    }

    public static CreativeModeTab.Builder itemGroupBuilder()
    {
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0);
    }

}
