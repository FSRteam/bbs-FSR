package mchorse.bbs_mod.compat;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.ItemGroup;

/**
 * Transitional registration facade that exposes vanilla builders.
 */
public final class FabricRegistryCompat
{
    private FabricRegistryCompat() {}

    public static AbstractBlock.Settings blockSettings()
    {
        return AbstractBlock.Settings.create();
    }

    public static <T extends Entity> EntityType<T> buildEntityTypeWithBlockRange(
        String id,
        SpawnGroup spawnGroup,
        EntityType.EntityFactory<T> factory,
        EntityDimensions dimensions,
        int trackingRangeBlocks,
        int updateRate
    )
    {
        return EntityType.Builder.create(factory, spawnGroup)
            .setDimensions(dimensions.width, dimensions.height)
            .maxTrackingRange(trackingRangeBlocks)
            .trackingTickInterval(updateRate)
            .build(id);
    }

    public static <T extends Entity> EntityType<T> buildEntityTypeWithChunkRange(
        String id,
        SpawnGroup spawnGroup,
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
        BlockEntityType.BlockEntityFactory<? extends T> factory,
        Block... blocks
    )
    {
        return BlockEntityType.Builder.create(factory, blocks).build(null);
    }

    public static ItemGroup.Builder itemGroupBuilder()
    {
        return ItemGroup.create(ItemGroup.Row.TOP, 0);
    }

}
