package mchorse.bbs_mod.forms.renderers.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * A world of exactly one block, sitting at {@link BlockPos#ZERO} with air all around it.
 *
 * <p>Fluids are the one thing vanilla never bakes into a block model: a water or lava state
 * renders as {@link net.minecraft.world.level.block.RenderShape#INVISIBLE}, and its geometry
 * gets generated per chunk section by
 * {@link net.minecraft.client.renderer.block.LiquidBlockRenderer}, which needs a world to ask
 * about neighbours, light and the biome tint. A form has no world, so it hands the liquid
 * renderer this one: air on every side (so every face draws), the form's own packed light,
 * and the biome tint read from wherever the camera stands.</p>
 *
 * <p>The origin is not arbitrary — the liquid renderer emits vertices at
 * {@code pos.getX() & 15}, chunk local coordinates, so only a position at a section corner
 * puts the block into 0..1.</p>
 */
public class SingleBlockRenderView implements BlockAndTintGetter
{
    /** Water tint of the plains biome — the fallback when there is no world to sample. */
    private static final int DEFAULT_TINT = 0x3F76E4;

    private BlockState state = Blocks.AIR.defaultBlockState();
    private FluidState fluidState = Fluids.EMPTY.defaultFluidState();
    private int light;

    public SingleBlockRenderView set(BlockState state, int light)
    {
        this.state = state;
        this.fluidState = state.getFluidState();
        this.light = light;

        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        return pos.equals(BlockPos.ZERO) ? this.state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return pos.equals(BlockPos.ZERO) ? this.fluidState : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos)
    {
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shaded)
    {
        if (!shaded)
        {
            return 1F;
        }

        return switch (direction)
        {
            case DOWN -> 0.5F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
            default -> 1F;
        };
    }

    @Override
    public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine()
    {
        /* Never reached: the light lookups are overridden, and they are the only things that
         * would go through a lighting provider. */
        return null;
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos)
    {
        return type == LightLayer.SKY ? (this.light >> 20) & 0xF : (this.light >> 4) & 0xF;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver)
    {
        ClientLevel level = Minecraft.getInstance().level;

        if (level != null)
        {
            /* The form has no place in the world of its own — biome tint comes from where
             * the camera is, so a water form matches the water it stands next to. */
            return level.getBlockTint(BlockPos.containing(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()), colorResolver);
        }

        return DEFAULT_TINT;
    }

    @Override
    public int getHeight()
    {
        return 384;
    }

    @Override
    public int getMinBuildHeight()
    {
        return -64;
    }
}
