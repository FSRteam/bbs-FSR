package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.IrisStateSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.BlockRenderType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = WorldRenderingSettings.class, remap = false)
public abstract class WorldRenderingSettingsViewMixin implements IrisStateSnapshot
{
    @Shadow
    private boolean reloadRequired;

    @Shadow
    private Object2IntMap<BlockState> blockStateIds;

    @Shadow
    private Map<Block, BlockRenderType> blockTypeIds;

    @Shadow
    private Object2IntFunction<NamespacedId> entityIds;

    @Shadow
    private Object2IntFunction<NamespacedId> itemIds;

    @Shadow
    private float ambientOcclusionLevel;

    @Shadow
    private boolean disableDirectionalShading;

    @Shadow
    private boolean hasVillagerConversionId;

    @Shadow
    private boolean useSeparateAo;

    @Shadow
    private boolean separateEntityDraws;

    @Shadow
    private boolean voxelizeLightBlocks;

    @Shadow
    private ChunkVertexType chunkVertexFormat;

    @Override
    public Runnable bbs$captureState()
    {
        boolean savedReloadRequired = this.reloadRequired;
        Object2IntMap<BlockState> savedBlockStateIds = this.blockStateIds;
        Map<Block, BlockRenderType> savedBlockTypeIds = this.blockTypeIds;
        Object2IntFunction<NamespacedId> savedEntityIds = this.entityIds;
        Object2IntFunction<NamespacedId> savedItemIds = this.itemIds;
        float savedAmbientOcclusionLevel = this.ambientOcclusionLevel;
        boolean savedDisableDirectionalShading = this.disableDirectionalShading;
        boolean savedHasVillagerConversionId = this.hasVillagerConversionId;
        boolean savedUseSeparateAo = this.useSeparateAo;
        boolean savedSeparateEntityDraws = this.separateEntityDraws;
        boolean savedVoxelizeLightBlocks = this.voxelizeLightBlocks;
        ChunkVertexType savedChunkVertexFormat = this.chunkVertexFormat;

        return () ->
        {
            this.reloadRequired = savedReloadRequired;
            this.blockStateIds = savedBlockStateIds;
            this.blockTypeIds = savedBlockTypeIds;
            this.entityIds = savedEntityIds;
            this.itemIds = savedItemIds;
            this.ambientOcclusionLevel = savedAmbientOcclusionLevel;
            this.disableDirectionalShading = savedDisableDirectionalShading;
            this.hasVillagerConversionId = savedHasVillagerConversionId;
            this.useSeparateAo = savedUseSeparateAo;
            this.separateEntityDraws = savedSeparateEntityDraws;
            this.voxelizeLightBlocks = savedVoxelizeLightBlocks;
            this.chunkVertexFormat = savedChunkVertexFormat;
        };
    }
}
