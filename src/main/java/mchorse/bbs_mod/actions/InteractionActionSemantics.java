package mchorse.bbs_mod.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Shared boundary decisions for recording and replaying vanilla interactions.
 */
public final class InteractionActionSemantics
{
    private static final double HIT_COMPONENT_LIMIT = 1.0000001D;
    private static final double ENTITY_HIT_EPSILON = 1.0E-4D;

    private InteractionActionSemantics()
    {}

    public static boolean shouldRecord(InteractionResult result)
    {
        return result != null && result.consumesAction();
    }

    public static boolean shouldRecordPlayerInteraction(InteractionResult result, boolean spectator)
    {
        /* Spectator block consumption is a menu-view branch, not the normal
         * block/item mutation branch that a non-spectator fake player replays. */
        return !spectator && shouldRecord(result);
    }

    /**
     * A block-break action is recordable only after the game-mode method has
     * returned success, stayed on the same invocation owner, and changed the
     * actual block state.  The caller supplies the owner check because those
     * values are identity-sensitive runtime objects.
     */
    public static boolean shouldRecordCommittedBlockBreak(
        boolean destroyResult,
        boolean sameInvocationOwner,
        BlockState before,
        BlockState after
    )
    {
        return destroyResult
            && sameInvocationOwner
            && before != null
            && after != null
            && !before.equals(after);
    }

    public static boolean canReplayItemUse(boolean empty, boolean enabled)
    {
        return !empty && enabled;
    }

    public static <T> T selectScopedTarget(Map<String, ? extends T> targets, String targetId, Predicate<T> allowed)
    {
        T target = targets == null ? null : targets.get(targetId);

        return target != null && allowed.test(target) ? target : null;
    }

    public static void withSecondaryUse(ServerPlayer player, boolean secondaryUse, Runnable runnable)
    {
        boolean previous = player.isShiftKeyDown();

        player.setShiftKeyDown(secondaryUse);

        try
        {
            runnable.run();
        }
        finally
        {
            player.setShiftKeyDown(previous);
        }
    }

    public static void withIsolatedItemCooldown(ServerPlayer player, ItemStack stack, Runnable runnable)
    {
        if (stack.isEmpty())
        {
            runnable.run();

            return;
        }

        Item item = stack.getItem();
        ItemCooldowns cooldowns = player.getCooldowns();

        withIsolatedCooldown(() -> cooldowns.isOnCooldown(item), () -> cooldowns.removeCooldown(item), runnable);
    }

    public static void withIsolatedInteractionState(SuperFakePlayer player, Runnable runnable)
    {
        withIsolatedInteractionState(
            player::stopUsingItem,
            player::isSleeping,
            () -> player.stopSleepInBed(true, true),
            () -> clearInteractionInventory(player),
            runnable
        );
    }

    static void withIsolatedInteractionState(
        Runnable stopUsingItem,
        BooleanSupplier isSleeping,
        Runnable stopSleeping,
        Runnable clearInventory,
        Runnable runnable
    )
    {
        Throwable failure = clearTransientInteractionState(
            null,
            stopUsingItem,
            isSleeping,
            stopSleeping,
            clearInventory
        );

        if (failure == null)
        {
            try
            {
                runnable.run();
            }
            catch (RuntimeException | Error e)
            {
                failure = e;
            }
        }

        failure = clearTransientInteractionState(
            failure,
            stopUsingItem,
            isSleeping,
            stopSleeping,
            clearInventory
        );

        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }
        else if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static Throwable clearTransientInteractionState(
        Throwable failure,
        Runnable stopUsingItem,
        BooleanSupplier isSleeping,
        Runnable stopSleeping,
        Runnable clearInventory
    )
    {
        failure = runCleanupStep(failure, stopUsingItem);
        boolean sleeping = false;

        try
        {
            sleeping = isSleeping.getAsBoolean();
        }
        catch (RuntimeException | Error e)
        {
            failure = appendFailure(failure, e);
            sleeping = true;
        }

        if (sleeping)
        {
            failure = runCleanupStep(failure, stopSleeping);
        }

        return runCleanupStep(failure, clearInventory);
    }

    private static Throwable runCleanupStep(Throwable failure, Runnable cleanup)
    {
        try
        {
            cleanup.run();
        }
        catch (RuntimeException | Error e)
        {
            return appendFailure(failure, e);
        }

        return failure;
    }

    private static Throwable appendFailure(Throwable failure, Throwable addition)
    {
        if (addition == null)
        {
            return failure;
        }

        if (failure == null)
        {
            return addition;
        }

        if (failure != addition)
        {
            failure.addSuppressed(addition);
        }

        return failure;
    }

    private static void clearInteractionInventory(SuperFakePlayer player)
    {
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
    }

    public static void withIsolatedCooldown(BooleanSupplier onCooldown, Runnable clearCooldown, Runnable runnable)
    {
        if (onCooldown.getAsBoolean())
        {
            clearCooldown.run();
        }

        try
        {
            runnable.run();
        }
        finally
        {
            if (onCooldown.getAsBoolean())
            {
                clearCooldown.run();
            }
        }
    }

    public static boolean isValidBlockHit(BlockHitResult hit)
    {
        if (hit == null)
        {
            return false;
        }

        BlockPos pos = hit.getBlockPos();
        Vec3 delta = hit.getLocation().subtract(Vec3.atCenterOf(pos));

        return Math.abs(delta.x) < HIT_COMPONENT_LIMIT
            && Math.abs(delta.y) < HIT_COMPONENT_LIMIT
            && Math.abs(delta.z) < HIT_COMPONENT_LIMIT;
    }

    public static boolean canReplayBlock(ServerPlayer player, BlockHitResult hit)
    {
        if (!(player.level() instanceof ServerLevel level) || !isValidBlockHit(hit))
        {
            return false;
        }

        BlockPos pos = hit.getBlockPos();

        return isReplayBlockReachable(player, level, pos);
    }

    public static boolean canReplayBlockAction(ServerPlayer player, BlockPos pos)
    {
        if (!(player.level() instanceof ServerLevel level)
            || !FilmPlaybackPolicy.isBlockPositionAllowed(pos.getX(), pos.getY(), pos.getZ()))
        {
            return false;
        }

        return player.mayBuild() && isReplayBlockReachable(player, level, pos);
    }

    public static boolean isValidBreakProgress(int progress)
    {
        return progress >= -1 && progress <= 9;
    }

    private static boolean isReplayBlockReachable(ServerPlayer player, ServerLevel level, BlockPos pos)
    {
        return Level.isInSpawnableBounds(pos)
            && !level.isOutsideBuildHeight(pos)
            && level.getWorldBorder().isWithinBounds(pos)
            && level.hasChunkAt(pos)
            && player.canInteractWithBlock(pos, 1D)
            && level.mayInteract(player, pos);
    }

    public static boolean canReplayEntity(ServerPlayer player, Entity target)
    {
        if (target == null || target.isRemoved() || target.level() != player.level() || target == player)
        {
            return false;
        }

        ServerLevel level = player.serverLevel();

        return level.getWorldBorder().isWithinBounds(target.blockPosition())
            && player.canInteractWithEntity(target.getBoundingBox(), 1D);
    }

    /**
     * Entity interaction packets carry a point relative to the target's origin.
     * Keep the value finite and inside the target's current bounds before
     * exposing it to an entity hook.
     */
    public static boolean isValidEntityHit(Entity target, Vec3 location)
    {
        if (target == null || location == null
            || !FilmPlaybackPolicy.isEntityInteractionOffsetAllowed(location.x, location.y, location.z))
        {
            return false;
        }

        try
        {
            AABB localBounds = target.getBoundingBox().move(-target.getX(), -target.getY(), -target.getZ());

            return isValidEntityHit(location, localBounds);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    static boolean isValidEntityHit(Vec3 location, AABB localBounds)
    {
        return location != null
            && localBounds != null
            && FilmPlaybackPolicy.isEntityInteractionOffsetAllowed(location.x, location.y, location.z)
            && localBounds.inflate(ENTITY_HIT_EPSILON).contains(location);
    }

    public static <T> T selectTarget(Iterable<T> candidates, String targetId, Predicate<T> allowed, java.util.function.Function<T, String> id, ToDoubleFunction<T> distance)
    {
        T nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (T candidate : candidates)
        {
            if (candidate == null || !allowed.test(candidate))
            {
                continue;
            }

            if (!targetId.isEmpty() && targetId.equals(id.apply(candidate)))
            {
                return candidate;
            }

            double candidateDistance = distance.applyAsDouble(candidate);

            if (candidateDistance < nearestDistance)
            {
                nearest = candidate;
                nearestDistance = candidateDistance;
            }
        }

        return nearest;
    }
}
