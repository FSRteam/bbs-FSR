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
    enum TrackingRangeUnit
    {
        BLOCKS,
        CHUNKS
    }

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
        return buildEntityType(
            id,
            spawnGroup,
            factory,
            dimensions,
            toNeoForgeTrackingRange(trackingRangeBlocks, TrackingRangeUnit.BLOCKS),
            updateRate
        );
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
        return buildEntityType(
            id,
            spawnGroup,
            factory,
            dimensions,
            toNeoForgeTrackingRange(trackingRangeChunks, TrackingRangeUnit.CHUNKS),
            updateRate
        );
    }

    static int toNeoForgeTrackingRange(int trackingRange, TrackingRangeUnit unit)
    {
        return unit == TrackingRangeUnit.BLOCKS ? (trackingRange + 15) / 16 : trackingRange;
    }

    private static <T extends Entity> EntityType<T> buildEntityType(
        String id,
        MobCategory spawnGroup,
        EntityType.EntityFactory<T> factory,
        EntityDimensions dimensions,
        int trackingRangeChunks,
        int updateRate
    )
    {
        return EntityType.Builder.of(factory, spawnGroup)
            .sized(dimensions.width(), dimensions.height())
            .clientTrackingRange(trackingRangeChunks)
            .updateInterval(updateRate)
            .build(id);
    }

    public static <T extends BlockEntity> BlockEntityType<T> buildBlockEntityType(
        BlockEntityType.BlockEntitySupplier<? extends T> factory,
        Block... blocks
    )
    {
        @SuppressWarnings("unchecked")
        BlockEntityType.BlockEntitySupplier<T> typedFactory = (BlockEntityType.BlockEntitySupplier<T>) factory;

        return BlockEntityType.Builder.of(typedFactory, blocks).build(null);
    }

    public static CreativeModeTab.Builder itemGroupBuilder()
    {
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0);
    }

}
