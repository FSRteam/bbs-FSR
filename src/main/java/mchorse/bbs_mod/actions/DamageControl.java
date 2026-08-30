package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DamageControl
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-actions");

    /* Insertion ordered, so blocks are put back in the order they were first changed, and
     * keyed by position, so re-touching a block during a long take is a lookup rather than
     * a scan of everything captured so far - an explosion or a /fill used to cost time
     * quadratic in the size of the snapshot. */
    private Map<BlockPos, BlockCapture> blocks = new LinkedHashMap<>();
    private List<Entity> entities = new ArrayList<>();

    private ServerLevel world;

    public int nested;
    public boolean enable;

    public DamageControl(ServerLevel world)
    {
        this.world = world;
        this.enable = BBSSettings.damageControl.get();
    }

    public boolean hasPendingChanges()
    {
        return !this.blocks.isEmpty() || !this.entities.isEmpty();
    }

    public ServerLevel getWorld()
    {
        return this.world;
    }

    public void addBlock(BlockPos pos, BlockState state, CompoundTag blockEntity)
    {
        if (!this.enable || this.blocks.containsKey(pos))
        {
            return;
        }

        /* The position handed over by the chunk is reused between calls, so both the key
         * and the capture get a copy of their own. */
        BlockPos key = new BlockPos(pos);

        this.blocks.put(key, new BlockCapture(key, state, blockEntity));
    }

    public void addEntity(Entity entity)
    {
        if (!this.enable)
        {
            return;
        }

        this.entities.add(entity);
    }

    /**
     * Put the world back the way it was found.
     *
     * <p>The caller is expected to have dropped this snapshot from the manager before
     * calling: restoring a block is itself a block change, and a snapshot still reachable
     * from the manager would be written to while it is being walked.</p>
     *
     * <p>One entry that cannot be restored - a block entity whose type no longer exists, a
     * position outside the world - must not cost the rest of them, so every entry is put
     * back on its own and the snapshot is emptied whatever happens.</p>
     */
    public void restore()
    {
        for (BlockCapture block : new ArrayList<>(this.blocks.values()))
        {
            if (this.restoreBlock(block))
            {
                this.blocks.remove(block.pos);
            }
        }

        for (Entity entity : new ArrayList<>(this.entities))
        {
            try
            {
                if (entity.isRemoved())
                {
                    this.entities.remove(entity);
                }
                else
                {
                    entity.remove(Entity.RemovalReason.DISCARDED);
                    if (entity.isRemoved()) this.entities.remove(entity);
                }
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=dc.restore phase=entity result=retry", e);
            }
        }
    }

    private boolean restoreBlock(BlockCapture block)
    {
        try
        {
            this.world.setBlock(block.pos, block.lastState, 2);

            if (block.blockEntity != null)
            {
                BlockEntity blockEntity = BlockEntity.loadStatic(block.pos, block.lastState, block.blockEntity, this.world.registryAccess());

                /* Null when the block entity's type is gone - a mod removed since the take
                 * was captured. The block itself is already back, which is the most that
                 * can be done for it. */
                if (blockEntity != null)
                {
                    this.world.setBlockEntity(blockEntity);
                }
            }

            return true;
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=dc.restore phase=block result=retry pos={}", block.pos, e);

            return false;
        }
    }

    private static class BlockCapture
    {
        public BlockPos pos;
        public BlockState lastState;
        public CompoundTag blockEntity;

        public BlockCapture(BlockPos pos, BlockState lastState, CompoundTag blockEntity)
        {
            this.pos = pos;
            this.lastState = lastState;
            this.blockEntity = blockEntity;
        }
    }
}
