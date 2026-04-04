package mchorse.bbs_mod.compat;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.world.GameRules;

/**
 * Transitional registration facade for APIs that still rely on Fabric helpers.
 */
public final class FabricRegistryCompat
{
    private FabricRegistryCompat() {}

    public static FabricBlockSettings blockSettings()
    {
        return FabricBlockSettings.create();
    }

    public static <T extends Entity> EntityType<T> buildEntityTypeWithBlockRange(
        SpawnGroup spawnGroup,
        EntityType.EntityFactory<T> factory,
        EntityDimensions dimensions,
        int trackingRangeBlocks,
        int updateRate
    )
    {
        return FabricEntityTypeBuilder.create(spawnGroup, factory)
            .dimensions(dimensions)
            .trackRangeBlocks(trackingRangeBlocks)
            .trackedUpdateRate(updateRate)
            .build();
    }

    public static <T extends Entity> EntityType<T> buildEntityTypeWithChunkRange(
        SpawnGroup spawnGroup,
        EntityType.EntityFactory<T> factory,
        EntityDimensions dimensions,
        int trackingRangeChunks,
        int updateRate
    )
    {
        return FabricEntityTypeBuilder.create(spawnGroup, factory)
            .dimensions(dimensions)
            .trackRangeChunks(trackingRangeChunks)
            .trackedUpdateRate(updateRate)
            .build();
    }

    public static <T extends BlockEntity> BlockEntityType<T> buildBlockEntityType(
        FabricBlockEntityTypeBuilder.Factory<T> factory,
        Block... blocks
    )
    {
        return FabricBlockEntityTypeBuilder.create(factory, blocks).build();
    }

    public static FabricItemGroup.Builder itemGroupBuilder()
    {
        return FabricItemGroup.builder();
    }

    public static GameRules.Key<GameRules.BooleanRule> registerBooleanRule(
        String id,
        GameRules.Category category,
        boolean defaultValue
    )
    {
        return GameRuleRegistry.register(id, category, GameRuleFactory.createBooleanRule(defaultValue));
    }

    public static <T extends Entity> void registerAttributes(
        EntityType<T> entityType,
        net.minecraft.entity.attribute.DefaultAttributeContainer.Builder attributes
    )
    {
        FabricDefaultAttributeRegistry.register(entityType, attributes);
    }
}
