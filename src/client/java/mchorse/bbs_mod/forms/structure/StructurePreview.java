package mchorse.bbs_mod.forms.structure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The wand's region as a structure before it is saved: read out of the client world through the
 * same {@link StructureTemplate} the server will use, so every block in the dialog is the block
 * that lands in the file. The client world is enough — block states and block entity data are all
 * the renderer wants.
 *
 * <p>Entities are the one thing left out where the server takes them. Nothing renders them from a
 * structure, so capturing them would cost a walk over the region's entities to draw nothing; the
 * file still gets them.</p>
 */
public class StructurePreview
{
    /** Regions above this many blocks are saved blind: reading and tesselating them would stall the dialog. */
    public static final long LIMIT = 200_000;

    /** @return the region parsed the way a structure file is, or null without a world */
    public static StructureRenderData capture(String id, BlockPos min, BlockPos size)
    {
        ClientLevel world = Minecraft.getInstance().level;

        if (world == null)
        {
            return null;
        }

        StructureTemplate template = new StructureTemplate();

        template.fillFromWorld(world, min, size, false, Blocks.STRUCTURE_VOID);

        return StructureRenderData.parse(id, template.save(new CompoundTag()));
    }
}
