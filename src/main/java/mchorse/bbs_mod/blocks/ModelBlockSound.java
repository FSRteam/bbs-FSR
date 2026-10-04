package mchorse.bbs_mod.blocks;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.SoundType;

/**
 * Sound material of a model block. Lives in the block STATE (not the block
 * entity) because {@link net.minecraft.world.level.block.state.BlockBehaviour#getSoundType}
 * receives only a state — there is no position to reach the block entity from.
 */
public enum ModelBlockSound implements StringRepresentable
{
    STONE("stone", SoundType.STONE),
    WOOD("wood", SoundType.WOOD),
    METAL("metal", SoundType.METAL),
    GLASS("glass", SoundType.GLASS),
    WOOL("wool", SoundType.WOOL),
    GRASS("grass", SoundType.GRASS),
    NONE("none", SoundType.EMPTY);

    public final String id;
    public final SoundType group;

    ModelBlockSound(String id, SoundType group)
    {
        this.id = id;
        this.group = group;
    }

    public static ModelBlockSound byId(String id)
    {
        for (ModelBlockSound sound : values())
        {
            if (sound.id.equals(id))
            {
                return sound;
            }
        }

        return STONE;
    }

    @Override
    public String getSerializedName()
    {
        return this.id;
    }
}
