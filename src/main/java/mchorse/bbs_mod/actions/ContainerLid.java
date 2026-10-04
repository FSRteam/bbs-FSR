package mchorse.bbs_mod.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Raises and lowers a container's lid for a film.
 *
 * <p>An actor can't open a real container: {@link SuperFakePlayer} turns every
 * screen down, because the film draws its own. Vanilla's lid, though, hangs off
 * the viewer count behind that screen, so turning the screen down also cost the
 * chest its animation and its sound.</p>
 *
 * <p>So the lid is driven straight instead: this sends the very block event
 * vanilla's viewer count would have sent (BaseContainerBlockEntity's
 * signalOpenCount block event), which is what every client turns into a moving
 * lid. The container's real viewer count is left alone, and that's the point -
 * opening it for real also schedules a recount five ticks later that counts the
 * players standing within five blocks of the chest, finds no actor there (an
 * actor is never in the world's entity list) and slams the lid shut mid-swing.</p>
 */
public class ContainerLid
{
    /** Vanilla's block event type for "this many players are looking inside". */
    private static final int VIEWER_COUNT = 1;

    /** Whether the block at this position has a lid worth animating at all. */
    public static boolean isLidded(Level level, BlockPos pos)
    {
        BlockEntity entity = level.getBlockEntity(pos);

        return entity instanceof ChestBlockEntity || entity instanceof EnderChestBlockEntity;
    }

    /** Use one stable key for both halves of a double chest. */
    public static BlockPos canonicalPos(Level level, BlockPos pos)
    {
        BlockEntity entity = level.getBlockEntity(pos);

        if (!(entity instanceof ChestBlockEntity))
        {
            return pos.immutable();
        }

        BlockState state = level.getBlockState(pos);
        ChestType type = chestType(state);

        if (type == ChestType.SINGLE)
        {
            return pos.immutable();
        }

        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));

        return compare(pos, other) <= 0 ? pos.immutable() : other.immutable();
    }

    private static int compare(BlockPos a, BlockPos b)
    {
        int x = Integer.compare(a.getX(), b.getX());

        if (x != 0) return x;

        int y = Integer.compare(a.getY(), b.getY());

        return y != 0 ? y : Integer.compare(a.getZ(), b.getZ());
    }

    public static void setOpen(Level level, BlockPos pos, boolean open)
    {
        BlockEntity entity = level.getBlockEntity(pos);

        if (entity instanceof ChestBlockEntity)
        {
            BlockState state = level.getBlockState(pos);

            setChestHalfOpen(level, pos, state, open);

            /* Both halves of a double chest keep their own lid, and vanilla
             * moves both of them - a half open chest reads as a broken one */
            if (chestType(state) != ChestType.SINGLE)
            {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                BlockState otherState = level.getBlockState(other);

                if (otherState.is(state.getBlock()))
                {
                    setChestHalfOpen(level, other, otherState, open);
                }
            }
        }
        else if (entity instanceof EnderChestBlockEntity)
        {
            BlockState state = level.getBlockState(pos);

            level.blockEvent(pos, state.getBlock(), VIEWER_COUNT, open ? 1 : 0);
            playSound(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, open ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE);
        }
    }

    private static void setChestHalfOpen(Level level, BlockPos pos, BlockState state, boolean open)
    {
        level.blockEvent(pos, state.getBlock(), VIEWER_COUNT, open ? 1 : 0);

        playChestSound(level, pos, state, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE);
    }

    /**
     * ChestBlockEntity's sound playing, which isn't ours to call: the left half
     * stays quiet so a double chest is heard once, from the middle of the two.
     */
    private static void playChestSound(Level level, BlockPos pos, BlockState state, SoundEvent sound)
    {
        ChestType type = chestType(state);

        if (type == ChestType.LEFT)
        {
            return;
        }

        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;

        if (type == ChestType.RIGHT)
        {
            Direction direction = ChestBlock.getConnectedDirection(state);

            x += direction.getStepX() * 0.5D;
            z += direction.getStepZ() * 0.5D;
        }

        playSound(level, x, y, z, sound);
    }

    private static void playSound(Level level, double x, double y, double z, SoundEvent sound)
    {
        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.1F + 0.9F);
    }

    /** A chest block entity doesn't have to sit in a vanilla chest block. */
    private static ChestType chestType(BlockState state)
    {
        return state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
    }
}
