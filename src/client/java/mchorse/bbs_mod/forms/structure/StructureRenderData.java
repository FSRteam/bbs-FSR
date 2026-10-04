package mchorse.bbs_mod.forms.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed structure NBT (the vanilla structure block format): size + non-air blocks. Parsing is
 * done by hand instead of {@code StructureTemplate.place()} because placing requires a
 * {@code ServerLevelAccessor} while we only need block states for client-side rendering.
 */
public class StructureRenderData
{
    public final String id;
    public final Vec3i size;

    /** Structure-local position → state, insertion order = file order. Air and structure void excluded. */
    private final Map<BlockPos, BlockState> blocks;

    /** Structure-local position → block entity NBT (chests, signs, beds, ...). */
    private final Map<BlockPos, CompoundTag> blockEntities;

    /** Traced on first use: light depends on the blocks alone, so it outlives biome changes and rebakes. */
    private StructureLighting lighting;

    private StructureRenderData(String id, Vec3i size, Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities)
    {
        this.id = id;
        this.size = size;
        this.blocks = Collections.unmodifiableMap(blocks);
        this.blockEntities = Collections.unmodifiableMap(blockEntities);
    }

    public static StructureRenderData create(String id, Vec3i size, Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> entities)
    {
        Map<BlockPos, BlockState> blockCopy = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> entityCopy = new LinkedHashMap<>();
        blocks.forEach((pos, state) -> blockCopy.put(pos.immutable(), state));
        entities.forEach((pos, nbt) -> entityCopy.put(pos.immutable(), nbt.copy()));
        return new StructureRenderData(id, new Vec3i(size.getX(), size.getY(), size.getZ()), blockCopy, entityCopy);
    }

    public Map<BlockPos, BlockState> getBlocks()
    {
        return this.blocks;
    }

    public Map<BlockPos, CompoundTag> getBlockEntities()
    {
        return this.blockEntities;
    }

    /** How this structure is lit — shared by both fake worlds, so they agree; see {@link StructureLighting}. */
    public StructureLighting getLighting()
    {
        if (this.lighting == null)
        {
            this.lighting = StructureLighting.compute(this);
        }

        return this.lighting;
    }

    public BlockState getBlockState(BlockPos pos)
    {
        BlockState state = this.blocks.get(pos);

        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    public boolean isEmpty()
    {
        return this.blocks.isEmpty();
    }

    public static StructureRenderData parse(String id, CompoundTag root)
    {
        ListTag sizeList = root.getList("size", Tag.TAG_INT);
        Vec3i size = new Vec3i(sizeList.getInt(0), sizeList.getInt(1), sizeList.getInt(2));

        ListTag paletteNbt;

        if (root.contains("palette", Tag.TAG_LIST))
        {
            paletteNbt = root.getList("palette", Tag.TAG_COMPOUND);
        }
        else
        {
            /* "palettes" variant: several random palettes, the first one is good enough */
            ListTag palettes = root.getList("palettes", Tag.TAG_LIST);

            paletteNbt = palettes.isEmpty() ? new ListTag() : palettes.getList(0);
        }

        BlockState[] palette = new BlockState[paletteNbt.size()];

        for (int i = 0; i < palette.length; i++)
        {
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteNbt.getCompound(i));
        }

        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        ListTag blocksNbt = root.getList("blocks", Tag.TAG_COMPOUND);

        for (int i = 0; i < blocksNbt.size(); i++)
        {
            CompoundTag block = blocksNbt.getCompound(i);
            int stateIndex = block.getInt("state");

            if (stateIndex < 0 || stateIndex >= palette.length)
            {
                continue;
            }

            BlockState state = palette[stateIndex];

            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID))
            {
                continue;
            }

            ListTag posList = block.getList("pos", Tag.TAG_INT);
            BlockPos pos = new BlockPos(posList.getInt(0), posList.getInt(1), posList.getInt(2));

            blocks.put(pos, state);

            if (block.contains("nbt", Tag.TAG_COMPOUND))
            {
                blockEntities.put(pos, block.getCompound("nbt"));
            }
        }

        return new StructureRenderData(id, size, blocks, blockEntities);
    }
}
