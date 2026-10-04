package mchorse.bbs_mod.blocks;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelBody;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.utils.PermissionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

public class ModelBlock extends Block implements EntityBlock, SimpleWaterloggedBlock
{
    public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("light_level", 0, 15);
    public static final EnumProperty<ModelBlockSound> SOUND = EnumProperty.create("sound", ModelBlockSound.class);

    /**
     * Client-side hook: whether the player is currently editing (dashboard
     * open, or a model block item in hand). While editing, the outline grows
     * to at least the full cube so a block with a tiny or offset hitbox stays
     * easy to target. On the dedicated server it stays false — the server
     * never targets blocks visually.
     */
    public static BooleanSupplier editingCheck = () -> false;

    public static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> validateTicker(BlockEntityType<A> givenType, BlockEntityType<E> expectedType, BlockEntityTicker<? super E> ticker)
    {
        return expectedType == givenType ? (BlockEntityTicker<A>) ticker : null;
    }

    public ModelBlock(Properties settings)
    {
        super(settings);

        this.registerDefaultState(this.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, false)
            .setValue(LIGHT_LEVEL, 0)
            .setValue(SOUND, ModelBlockSound.STONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
    {
        builder.add(BlockStateProperties.WATERLOGGED, LIGHT_LEVEL, SOUND);
    }

    /**
     * Pushes the body's light level and sound material from the block entity
     * into the block state, where the engine actually reads them. Server side;
     * the state change then syncs to clients on its own.
     */
    public static void mirrorBlockState(Level world, BlockPos pos)
    {
        BlockEntity be = world.getBlockEntity(pos);
        BlockState state = world.getBlockState(pos);

        if (!(be instanceof ModelBlockEntity model) || !(state.getBlock() instanceof ModelBlock))
        {
            return;
        }

        ModelBody body = model.getProperties().getBody();
        BlockState updated = state
            .setValue(LIGHT_LEVEL, body.getLightLevel())
            .setValue(SOUND, body.getSound());

        if (updated != state)
        {
            world.setBlock(pos, updated, Block.UPDATE_ALL);
        }
    }

    @Nullable
    private static ModelBlockEntity getModelBlockEntity(BlockGetter world, BlockPos pos)
    {
        return world.getBlockEntity(pos) instanceof ModelBlockEntity model ? model : null;
    }

    /* Shape (the block's hitbox is authored per block in its body settings) */

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context)
    {
        ModelBlockEntity model = getModelBlockEntity(world, pos);
        VoxelShape shape = model == null ? Shapes.block() : model.getShape();

        /* While editing, the block stays targetable as at least a full cube
         * even when its actual hitbox is tiny or offset. CUBE mode returns the
         * block() singleton, so the reference check skips a wasteful union. */
        if (shape != Shapes.block() && editingCheck.getAsBoolean())
        {
            return Shapes.or(shape, Shapes.block());
        }

        return shape;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context)
    {
        ModelBlockEntity model = getModelBlockEntity(world, pos);

        if (model != null && model.getProperties().getBody().isSolid())
        {
            return model.getShape();
        }

        return Shapes.empty();
    }

    /**
     * Upstream's "camera collision" (Yarn {@code getCameraCollisionShape}) is the
     * shape the detached camera's clipping ray reads — through ClipContext.Block.VISUAL
     * on this version — so it lands on {@code getVisualShape} here.
     */
    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context)
    {
        ModelBlockEntity model = getModelBlockEntity(world, pos);

        if (model != null && model.getProperties().getBody().isCameraCollision())
        {
            return model.getShape();
        }

        return Shapes.empty();
    }

    /**
     * Navigation mirror of the collision shape, on NeoForge's per-position
     * hook (the vanilla {@code isPathfindable} receives no position, so it
     * cannot consult the body): mobs may path through a model block unless
     * its body marks it solid — then it is a wall for them too.
     */
    @Override
    public PathType getBlockPathType(BlockState state, BlockGetter world, BlockPos pos, Mob mob)
    {
        ModelBlockEntity model = getModelBlockEntity(world, pos);
        boolean solid = model != null && model.getProperties().getBody().isSolid();

        return solid ? PathType.BLOCKED : PathType.OPEN;
    }

    @Override
    protected SoundType getSoundType(BlockState state)
    {
        return state.getValue(SOUND).group;
    }

    /**
     * Vanilla's break-speed formula, but with the hardness taken from the
     * block's body instead of the registration-time settings — so every model
     * block can take its own time to mine. Runs identically on both sides
     * (the server steps its own break progress), fed by the synced entity.
     */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter world, BlockPos pos)
    {
        ModelBlockEntity model = getModelBlockEntity(world, pos);
        float hardness = model == null ? 0F : model.getProperties().getBody().getHardness();

        if (hardness <= 0F)
        {
            /* A whole break per tick: the instant break this block always had. */
            return 1F;
        }

        int divisor = player.hasCorrectToolForDrops(state) ? 30 : 100;

        return player.getDigSpeed(state, pos) / hardness / divisor;
    }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack)
    {
        super.setPlacedBy(world, pos, state, placer, itemStack);

        /* A placed item may carry body data in its BlockEntityTag (already
         * poured into the block entity by now) — mirror it into the state. */
        if (!world.isClientSide)
        {
            mirrorBlockState(world, pos);
        }
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx)
    {
        return this.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, ctx.getLevel().getFluidState(ctx.getClickedPos()).getType() == Fluids.WATER);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state)
    {
        BlockEntity entity = world.getBlockEntity(pos);

        if (entity instanceof ModelBlockEntity modelBlock)
        {
            ItemStack stack = new ItemStack(this);
            BlockItem.setBlockEntityData(stack, BBSMod.MODEL_BLOCK_ENTITY.get(), modelBlock.saveWithId(world.registryAccess()));

            return stack;
        }

        return super.getCloneItemStack(world, pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state)
    {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter world, BlockPos pos)
    {
        return true;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type)
    {
        if (world.isClientSide())
        {
            return validateTicker(type, BBSMod.MODEL_BLOCK_ENTITY.get(), (theWorld, blockPos, blockState, blockEntity) -> blockEntity.tick(theWorld, blockPos, blockState));
        }

        return null;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
    {
        return new ModelBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit)
    {
        if (world.isClientSide)
        {
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer && canEdit(serverPlayer))
        {
            ServerNetwork.sendClickedModelBlock(serverPlayer, pos);
        }

        return InteractionResult.CONSUME;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
    {
        if (hand == InteractionHand.MAIN_HAND)
        {
            if (world.isClientSide)
            {
                return ItemInteractionResult.SUCCESS;
            }

            if (player instanceof ServerPlayer serverPlayer && canEdit(serverPlayer))
            {
                ServerNetwork.sendClickedModelBlock(serverPlayer, pos);
            }

            return ItemInteractionResult.CONSUME;
        }

        return super.useItemOn(stack, state, world, pos, player, hand, hit);
    }

    private static boolean canEdit(ServerPlayer player)
    {
        return player.getServer() != null && PermissionUtils.arePanelsAllowed(player.getServer(), player);
    }

    /* SimpleWaterloggedBlock implementation */

    @Override
    public FluidState getFluidState(BlockState state)
    {
        return state.getValue(BlockStateProperties.WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState facingState, LevelAccessor world, BlockPos pos, BlockPos facingPos)
    {
        if (state.getValue(BlockStateProperties.WATERLOGGED))
        {
            world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }

        return super.updateShape(state, direction, facingState, world, pos, facingPos);
    }

    @Override
    public void playerDestroy(Level world, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity be, ItemStack tool)
    {
        if (!world.isClientSide && !player.getAbilities().instabuild)
        {
            if (be instanceof ModelBlockEntity model)
            {
                ItemStack stack = new ItemStack(this);
                BlockItem.setBlockEntityData(stack, BBSMod.MODEL_BLOCK_ENTITY.get(), model.saveWithId(world.registryAccess()));
                Block.popResource(world, pos, stack);
            }
        }

        super.playerDestroy(world, player, pos, state, be, tool);
    }
}
