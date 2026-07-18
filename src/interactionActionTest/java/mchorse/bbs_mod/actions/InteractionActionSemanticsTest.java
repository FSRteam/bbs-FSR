package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.compat.ActionEventCompat;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.DamageActionClip;
import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.actions.types.blocks.BreakBlockActionClip;
import mchorse.bbs_mod.actions.types.blocks.InteractBlockActionClip;
import mchorse.bbs_mod.actions.types.blocks.PlaceBlockActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.actions.types.item.ItemDropActionClip;
import mchorse.bbs_mod.actions.types.item.UseBlockItemActionClip;
import mchorse.bbs_mod.actions.types.item.UseItemActionClip;
import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.blocks.ModelBlock;
import mchorse.bbs_mod.cubic.animation.ProceduralAnimator;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayActionAuthorityTest;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.items.GunItem;
import mchorse.bbs_mod.mixin.LivingEntityMixin;
import mchorse.bbs_mod.mixin.PlayerEntityMixin;
import mchorse.bbs_mod.mixin.ServerPlayNetworkHandlerMixin;
import mchorse.bbs_mod.mixin.ServerPlayerGameModeMixin;
import mchorse.bbs_mod.mixin.ServerPlayerInteractionMixin;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable regressions for interaction branch and target-selection contracts. */
public final class InteractionActionSemanticsTest
{
    private InteractionActionSemanticsTest()
    {}

    public static void main(String[] args) throws Exception
    {
        bootstrapStandaloneMinecraftRuntime();

        settingsDefaultsAreSafeBeforeRegistration();
        preservesActionPlayerBinaryApi();
        recordsOnlyConsumedResults();
        recordsOnlyCommittedBlockBreaks();
        rejectsReplacedBlockBreakRecorder();
        rejectsUnavailableAirUseItems();
        actionScheduleHonorsClipWindow();
        airUseReplaysThroughGameMode();
        secondaryUseIsScopedAcrossDispatches();
        transientInteractionStateIsScopedAcrossDispatches();
        transientCleanupFailuresAreAggregated();
        ActionTeardownTest.runAll();
        playbackCooldownDoesNotLeakAcrossActions();
        scopesBreakProgressIdentity();
        preservesRecordedSwingHand();
        playbackSwipesBypassItemHooks();
        deduplicatesLegacySwipeDamageFallback();
        recordsSuccessfulNonLivingAttacksOnce();
        CommandRecordingResultTest.runAll();
        commandActionsRequireRealRequesterAuthority();
        EntityInteractionInputTest.runAll();
        TargetedFilmIssuerSourceTest.runAll();
        ReplayActionAuthorityTest.runAll();
        shiftsOnlyAbsoluteItemDropPositions();
        airPacketsHaveOneRecordingOwner();
        blockPacketsHaveOneRecordingOwner();
        recordingOnlyClipsDeserializeButAreNotAddable();
        validatesPacketHitBounds();
        validatesFinalActionSideEffectGuards();
        preservesExactTargetBeforeFallback();
        filtersFallbackCandidates();
        scopesActorTargetsToOnePlayback();
        preservesLegacyActionShapes();
        sourceShapesPreserveInteractionContracts();
        verifiesEntityDispatcherShape();
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    private static void settingsDefaultsAreSafeBeforeRegistration()
    {
        var previousShape = BBSSettings.keyframeDefaultShape;
        var previousDuration = BBSSettings.duration;

        try
        {
            BBSSettings.keyframeDefaultShape = null;
            BBSSettings.duration = null;
            check(BBSSettings.getDefaultKeyframeShape() == KeyframeShape.SQUARE,
                "unregistered keyframe shape setting no longer has a safe default");
            check(BBSSettings.getDefaultDuration() == 30,
                "unregistered clip duration setting no longer has its configured default");
        }
        finally
        {
            BBSSettings.keyframeDefaultShape = previousShape;
            BBSSettings.duration = previousDuration;
        }
    }

    private static void preservesActionPlayerBinaryApi() throws Exception
    {
        Method legacy = ActionPlayer.class.getMethod("updateReplayEntities");
        Method transactional = ActionPlayer.class.getDeclaredMethod("tryUpdateReplayEntities");

        check(legacy.getReturnType() == void.class,
            "ActionPlayer.updateReplayEntities changed its public ()V descriptor");
        check(transactional.getReturnType() == boolean.class,
            "ActionPlayer replay refresh lost its transactional result");
        check(classShape(ActionPlayer.class).contains("tryUpdateReplayEntities"),
            "ActionPlayer internal replay refresh still targets the legacy void wrapper");
    }

    private static void recordsOnlyConsumedResults()
    {
        check(InteractionActionSemantics.shouldRecord(InteractionResult.SUCCESS), "SUCCESS was not recorded");
        check(InteractionActionSemantics.shouldRecord(InteractionResult.SUCCESS_NO_ITEM_USED), "SUCCESS_NO_ITEM_USED was not recorded");
        check(InteractionActionSemantics.shouldRecord(InteractionResult.CONSUME), "CONSUME was not recorded");
        check(InteractionActionSemantics.shouldRecord(InteractionResult.CONSUME_PARTIAL), "CONSUME_PARTIAL was not recorded");
        check(!InteractionActionSemantics.shouldRecord(InteractionResult.PASS), "PASS created a replay action");
        check(!InteractionActionSemantics.shouldRecord(InteractionResult.FAIL), "FAIL created a replay action");
        check(!InteractionActionSemantics.shouldRecord(null), "null result created a replay action");
    }

    private static void recordsOnlyCommittedBlockBreaks()
    {
        var stone = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();

        check(!InteractionActionSemantics.shouldRecordCommittedBlockBreak(false, true, stone, air),
            "a canceled or restricted break was recorded");
        check(!InteractionActionSemantics.shouldRecordCommittedBlockBreak(true, true, stone, stone),
            "a successful return without a block-state change was recorded");
        check(!InteractionActionSemantics.shouldRecordCommittedBlockBreak(true, false, stone, air),
            "a break whose invocation owner changed was recorded");
        check(InteractionActionSemantics.shouldRecordCommittedBlockBreak(true, true, stone, air),
            "a committed block-state change was not recorded");
    }

    @SuppressWarnings("unchecked")
    private static void rejectsReplacedBlockBreakRecorder() throws Exception
    {
        ActionManager actions = new ActionManager();
        ServerPlayer player = allocateWithoutConstructor(ServerPlayer.class);
        ActionRecorder captured = new ActionRecorder(null, player, 0, 0);
        ActionRecorder replacement = new ActionRecorder(null, player, 0, 0);
        Field field = ActionManager.class.getDeclaredField("recorders");

        field.setAccessible(true);

        Map<ServerPlayer, ActionRecorder> recorders = (Map<ServerPlayer, ActionRecorder>) field.get(actions);
        AtomicInteger suppliers = new AtomicInteger();

        recorders.put(player, replacement);
        check(!actions.addActionExact(player, captured, () ->
        {
            suppliers.incrementAndGet();

            return new PlaceBlockActionClip();
        }), "a replaced recorder accepted the captured block break");
        check(suppliers.get() == 0, "a stale block-break supplier ran after recorder replacement");

        recorders.put(player, captured);
        check(actions.addActionExact(player, captured, () ->
        {
            suppliers.incrementAndGet();

            return new PlaceBlockActionClip();
        }), "the still-current recorder rejected a committed block break");
        check(suppliers.get() == 1 && captured.getClips().size() == 1,
            "the exact recorder did not receive exactly one block-break action");
    }

    private static void rejectsUnavailableAirUseItems()
    {
        check(InteractionActionSemantics.canReplayItemUse(false, true), "enabled non-empty item was rejected");
        check(!InteractionActionSemantics.canReplayItemUse(true, true), "packet-unreachable empty hand was replayed as an item use");
        check(!InteractionActionSemantics.canReplayItemUse(false, false), "disabled item bypassed the feature gate");
        check(!InteractionActionSemantics.canReplayItemUse(true, false), "empty disabled item bypassed the feature gate");
    }

    private static void actionScheduleHonorsClipWindow()
    {
        check(!ActionClip.isScheduledAt(true, 0, 20, 10, 5)
                && !ActionClip.isScheduledAt(true, 15, 20, 10, 5)
                && !ActionClip.isScheduledAt(true, 19, 20, 10, 5),
            "repeating action executed before its clip start tick");
        check(ActionClip.isScheduledAt(true, 20, 20, 10, 5)
                && !ActionClip.isScheduledAt(true, 24, 20, 10, 5)
                && ActionClip.isScheduledAt(true, 25, 20, 10, 5)
                && !ActionClip.isScheduledAt(true, 29, 20, 10, 5),
            "repeating action ignored its in-window frequency");
        check(!ActionClip.isScheduledAt(true, 30, 20, 10, 5)
                && !ActionClip.isScheduledAt(true, Integer.MAX_VALUE, 20, 10, 5),
            "repeating action executed at or beyond its exclusive end tick");
        check(!ActionClip.isScheduledAt(true, 19, 20, 10, 0)
                && ActionClip.isScheduledAt(true, 20, 20, 10, 0)
                && !ActionClip.isScheduledAt(true, 21, 20, 10, 0),
            "zero-frequency action no longer executes exactly once at its start tick");
        check(!ActionClip.isScheduledAt(false, 20, 20, 10, 0)
                && !ActionClip.isScheduledAt(true, 20, 20, 10, -1),
            "disabled or invalid-frequency action entered the schedule");
    }

    private static void airUseReplaysThroughGameMode() throws Exception
    {
        String bytecodeShape = classShape(UseItemActionClip.class);

        check(bytecodeShape.contains("net/minecraft/server/level/ServerPlayerGameMode"),
            "air-use replay bypassed ServerPlayerGameMode");
        check(bytecodeShape.contains("useItem"),
            "air-use replay no longer dispatches through the game-mode hook/cooldown path");
    }

    private static void secondaryUseIsScopedAcrossDispatches() throws Exception
    {
        check(classShape(InteractionActionSemantics.class).contains("setShiftKeyDown"),
            "secondary-use scope no longer restores the fake player's shift state");
        check(classShape(UseItemActionClip.class).contains("withSecondaryUse"),
            "air-use replay dropped its secondary-use scope");
        check(classShape(InteractBlockActionClip.class).contains("withSecondaryUse"),
            "block replay dropped its secondary-use scope");
        check(classShape(EntityInteractionActionClip.class).contains("withSecondaryUse"),
            "entity replay dropped its secondary-use scope");
        check(classShape(UseItemActionClip.class).contains("withIsolatedItemCooldown"),
            "air-use replay dropped cooldown isolation");
        check(classShape(InteractBlockActionClip.class).contains("withIsolatedItemCooldown"),
            "block replay dropped cooldown isolation");
        check(classShape(EntityInteractionActionClip.class).contains("withIsolatedItemCooldown"),
            "entity replay dropped cooldown isolation");
        check(classShape(UseBlockItemActionClip.class).contains("withIsolatedItemCooldown"),
            "legacy block-item replay can contaminate later cooldowns");
    }

    private static void playbackCooldownDoesNotLeakAcrossActions()
    {
        AtomicBoolean cooldown = new AtomicBoolean(false);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        Runnable clear = () ->
        {
            cooldown.set(false);
            clears.incrementAndGet();
        };
        Runnable consumedAction = () ->
        {
            check(!cooldown.get(), "recorded action was blocked by a previous fake-player cooldown");
            executions.incrementAndGet();
            cooldown.set(true);
        };

        InteractionActionSemantics.withIsolatedCooldown(cooldown::get, clear, consumedAction);
        InteractionActionSemantics.withIsolatedCooldown(cooldown::get, clear, consumedAction);

        cooldown.set(true); /* Simulate another replay sharing the world fake player. */
        InteractionActionSemantics.withIsolatedCooldown(cooldown::get, clear, consumedAction);

        check(executions.get() == 3, "cooldown suppressed a later or cross-replay recorded action");
        check(!cooldown.get(), "recorded action leaked its cooldown into another replay");
        check(clears.get() == 4, "cooldown scope did not clear both entry contamination and action-owned cooldowns");
    }

    private static void transientInteractionStateIsScopedAcrossDispatches() throws Exception
    {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger useStops = new AtomicInteger();
        AtomicInteger sleepStops = new AtomicInteger();
        AtomicInteger inventoryClears = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicBoolean sleeping = new AtomicBoolean(true);
        AtomicInteger inventoryItems = new AtomicInteger(36);
        AtomicInteger equipmentItems = new AtomicInteger(1);
        AtomicInteger selectedSlot = new AtomicInteger(7);

        try
        {
            InteractionActionSemantics.withIsolatedInteractionState(() ->
            {
                int stop = useStops.incrementAndGet();

                check(sequence.incrementAndGet() == (stop == 1 ? 1 : 5),
                    "item-use cleanup ran out of entry/action/finally order");
            }, sleeping::get, () ->
            {
                int stop = sleepStops.incrementAndGet();

                check(sequence.incrementAndGet() == (stop == 1 ? 2 : 6),
                    "sleep cleanup ran out of entry/action/finally order");
                sleeping.set(false);
            }, () ->
            {
                int clear = inventoryClears.incrementAndGet();

                check(sequence.incrementAndGet() == (clear == 1 ? 3 : 7),
                    "inventory cleanup ran out of entry/action/finally order");
                inventoryItems.set(0);
                equipmentItems.set(0);
                selectedSlot.set(0);
            }, () ->
            {
                executions.incrementAndGet();
                check(sequence.incrementAndGet() == 4,
                    "recorded interaction ran before entry contamination was stopped");
                check(inventoryItems.get() == 0 && equipmentItems.get() == 0 && selectedSlot.get() == 0,
                    "recorded interaction inherited full inventory, armor or selected slot from another film");

                /* Simulate one container result, equipped armor and a selected-slot mutation. */
                inventoryItems.set(1);
                equipmentItems.set(1);
                selectedSlot.set(8);
                sleeping.set(true);

                throw new IllegalStateException("expected action failure");
            });

            throw new AssertionError("interaction-state scope swallowed the action failure");
        }
        catch (IllegalStateException e)
        {
            check("expected action failure".equals(e.getMessage()),
                "interaction-state scope replaced the action failure");
        }

        check(executions.get() == 1, "interaction-state scope did not execute the recorded action exactly once");
        check(useStops.get() == 2, "interaction-state scope did not stop entry and action-started item use");
        check(sleepStops.get() == 2 && !sleeping.get(),
            "interaction-state scope did not stop entry and action-started sleeping");
        check(inventoryClears.get() == 2
                && inventoryItems.get() == 0
                && equipmentItems.get() == 0
                && selectedSlot.get() == 0,
            "interaction-state scope leaked container results, equipment or selected slot after failure");

        inventoryItems.set(36);
        equipmentItems.set(1);
        selectedSlot.set(6);

        Runnable clearInventory = () ->
        {
            inventoryItems.set(0);
            equipmentItems.set(0);
            selectedSlot.set(0);
        };

        InteractionActionSemantics.withIsolatedInteractionState(
            () -> {},
            () -> false,
            () -> { throw new AssertionError("non-sleeping cross-film action ran sleep cleanup"); },
            clearInventory,
            () ->
            {
                check(inventoryItems.get() == 0 && equipmentItems.get() == 0 && selectedSlot.get() == 0,
                    "cross-film interaction inherited another playback's fake inventory baseline");
                inventoryItems.set(1);
                equipmentItems.set(1);
                selectedSlot.set(4);
            }
        );

        check(inventoryItems.get() == 0 && equipmentItems.get() == 0 && selectedSlot.get() == 0,
            "normal cross-film interaction leaked its fake inventory baseline");

        String semantics = classShape(InteractionActionSemantics.class);

        check(semantics.contains("stopUsingItem") && semantics.contains("isSleeping") && semantics.contains("stopSleepInBed"),
            "fake-player interaction cleanup no longer clears active use, bed occupancy, pose and sleeping position");
        check(semantics.contains("clearContent") && semantics.contains("selected") && !semantics.contains("dropAll"),
            "fake-player inventory cleanup can retain state or drop unrecorded items into the world");

        String action = classShape(ActionClip.class);

        check(action.contains("applyInteractionPositionRotation") && action.contains("selectedSlot"),
            "interaction replay no longer applies its selected slot inside the isolated scope");
        check(action.contains("armorHead") && action.contains("armorChest")
                && action.contains("armorLegs") && action.contains("armorFeet"),
            "interaction replay no longer applies recorded armor inside the isolated scope");

        assertIsolatedAuthorizedInteraction(UseItemActionClip.class, "air-use replay");
        assertIsolatedAuthorizedInteraction(InteractBlockActionClip.class, "block replay");
        assertIsolatedAuthorizedInteraction(UseBlockItemActionClip.class, "legacy block-item replay");
        assertIsolatedAuthorizedInteraction(EntityInteractionActionClip.class, "entity replay");
    }

    private static void assertIsolatedAuthorizedInteraction(Class<?> type, String label) throws Exception
    {
        String shape = classShape(type);

        check(shape.contains("ActionCommandContext") && shape.contains("isAuthorizedFor"),
            label + " can mutate the shared fake player without requester authority");
        check(shape.contains("withIsolatedInteractionState") && shape.contains("applyInteractionPositionRotation"),
            label + " can leak inventory, equipment or transient fake-player state");
    }

    private static void transientCleanupFailuresAreAggregated()
    {
        AtomicInteger stopUsingCalls = new AtomicInteger();
        AtomicInteger sleepCheckCalls = new AtomicInteger();
        AtomicInteger stopSleepingCalls = new AtomicInteger();
        AtomicInteger inventoryCalls = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();

        try
        {
            InteractionActionSemantics.withIsolatedInteractionState(
                () -> { throw cleanupFailure("stop-using", stopUsingCalls.incrementAndGet()); },
                () -> { throw cleanupFailure("sleep-check", sleepCheckCalls.incrementAndGet()); },
                () -> { throw cleanupFailure("stop-sleeping", stopSleepingCalls.incrementAndGet()); },
                () -> { throw cleanupFailure("inventory", inventoryCalls.incrementAndGet()); },
                actions::incrementAndGet
            );

            throw new AssertionError("failing entry cleanup did not propagate");
        }
        catch (IllegalStateException e)
        {
            check("stop-using-1".equals(e.getMessage()),
                "entry cleanup did not preserve its first failure");
            check(e.getSuppressed().length == 7,
                "entry/final cleanup failures were not aggregated in order");
            check("sleep-check-1".equals(e.getSuppressed()[0].getMessage())
                    && "stop-sleeping-1".equals(e.getSuppressed()[1].getMessage())
                    && "inventory-1".equals(e.getSuppressed()[2].getMessage())
                    && "stop-using-2".equals(e.getSuppressed()[3].getMessage())
                    && "sleep-check-2".equals(e.getSuppressed()[4].getMessage())
                    && "stop-sleeping-2".equals(e.getSuppressed()[5].getMessage())
                    && "inventory-2".equals(e.getSuppressed()[6].getMessage()),
                "cleanup failures lost their deterministic step order");
        }

        check(actions.get() == 0,
            "action ran after its entry cleanup failed");
        check(stopUsingCalls.get() == 2 && sleepCheckCalls.get() == 2
                && stopSleepingCalls.get() == 2 && inventoryCalls.get() == 2,
            "a failing cleanup step skipped a later or final cleanup step");

        AtomicInteger finalStopCalls = new AtomicInteger();
        AtomicInteger finalInventoryCalls = new AtomicInteger();

        try
        {
            InteractionActionSemantics.withIsolatedInteractionState(
                () ->
                {
                    if (finalStopCalls.incrementAndGet() == 2)
                    {
                        throw new IllegalStateException("final-stop");
                    }
                },
                () -> false,
                () -> {},
                () ->
                {
                    if (finalInventoryCalls.incrementAndGet() == 2)
                    {
                        throw new IllegalStateException("final-inventory");
                    }
                },
                () -> { throw new IllegalStateException("action"); }
            );

            throw new AssertionError("action failure was swallowed");
        }
        catch (IllegalStateException e)
        {
            check("action".equals(e.getMessage()),
                "final cleanup replaced the action failure");
            check(e.getSuppressed().length == 2
                    && "final-stop".equals(e.getSuppressed()[0].getMessage())
                    && "final-inventory".equals(e.getSuppressed()[1].getMessage()),
                "final cleanup failures were not suppressed behind the action failure");
        }
    }

    private static IllegalStateException cleanupFailure(String step, int invocation)
    {
        return new IllegalStateException(step + "-" + invocation);
    }

    private static void scopesBreakProgressIdentity() throws Exception
    {
        int first = BreakProgressContext.allocate();
        int second = BreakProgressContext.allocate();

        check(first < 0 && second < 0 && first != second,
            "concurrent playback crack overlays received a colliding entity id");
        check(BreakProgressContext.currentOr(42) == 42,
            "unscoped break progress no longer preserves its compatibility id");

        int result = BreakProgressContext.withId(first, () ->
        {
            check(BreakProgressContext.currentOr(42) == first,
                "outer break-progress scope was not applied");
            BreakProgressContext.withId(second, () ->
            {
                check(BreakProgressContext.currentOr(42) == second,
                    "nested break-progress scope reused another playback id");
            });
            check(BreakProgressContext.currentOr(42) == first,
                "nested break-progress scope did not restore its parent");

            return 7;
        });

        check(result == 7, "break-progress scope discarded its action result");
        check(BreakProgressContext.currentOr(42) == 42,
            "break-progress scope leaked after playback action completion");

        try
        {
            BreakProgressContext.<Integer>withId(first, () ->
            {
                throw new IllegalStateException("expected break-progress failure");
            });

            throw new AssertionError("break-progress scope swallowed its action failure");
        }
        catch (IllegalStateException e)
        {
            check("expected break-progress failure".equals(e.getMessage()),
                "break-progress scope replaced its action failure");
        }

        check(BreakProgressContext.currentOr(42) == 42,
            "break-progress scope leaked after an action failure");

        Object firstLevel = new Object();
        Object secondLevel = new Object();
        BlockPos firstPos = new BlockPos(1, 64, 2);
        BlockPos secondPos = new BlockPos(3, 65, 4);
        List<ProgressEvent> events = new ArrayList<>();
        BreakProgressContext.Session movingSession = BreakProgressContext.createSession();

        movingSession.update(firstLevel,
            (id, pos, progress) -> events.add(new ProgressEvent(firstLevel, id, pos, progress)),
            firstPos,
            2);
        movingSession.update(firstLevel,
            (id, pos, progress) -> events.add(new ProgressEvent(firstLevel, id, pos, progress)),
            secondPos,
            3);
        movingSession.update(secondLevel,
            (id, pos, progress) -> events.add(new ProgressEvent(secondLevel, id, pos, progress)),
            secondPos,
            4);
        movingSession.clear();
        movingSession.clear();

        check(events.size() == 6,
            "break-progress session did not emit one update/clear transition per identity change");
        check(events.get(0).matches(firstLevel, movingSession.id(), firstPos, 2)
                && events.get(1).matches(firstLevel, movingSession.id(), firstPos, -1)
                && events.get(2).matches(firstLevel, movingSession.id(), secondPos, 3)
                && events.get(3).matches(firstLevel, movingSession.id(), secondPos, -1)
                && events.get(4).matches(secondLevel, movingSession.id(), secondPos, 4)
                && events.get(5).matches(secondLevel, movingSession.id(), secondPos, -1),
            "break-progress session left an overlay on a replaced block or level identity");
        check(!movingSession.isActive(),
            "break-progress session remained active after a successful clear");

        AtomicBoolean rejectFirstClear = new AtomicBoolean(true);
        List<ProgressEvent> retryEvents = new ArrayList<>();
        BreakProgressContext.Session retrySession = BreakProgressContext.createSession();
        retrySession.update(firstLevel, (id, pos, progress) ->
        {
            if (progress == -1 && rejectFirstClear.getAndSet(false))
            {
                throw new IllegalStateException("expected clear failure");
            }

            retryEvents.add(new ProgressEvent(firstLevel, id, pos, progress));
        }, firstPos, 1);

        try
        {
            retrySession.clear();

            throw new AssertionError("break-progress session swallowed a clear failure");
        }
        catch (IllegalStateException e)
        {
            check("expected clear failure".equals(e.getMessage()),
                "break-progress session replaced its clear failure");
        }

        check(retrySession.isActive(),
            "failed crack-overlay clear discarded the state required for retry");

        retrySession.clear();

        check(!retrySession.isActive()
                && retryEvents.size() == 2
                && retryEvents.get(1).matches(firstLevel, retrySession.id(), firstPos, -1),
            "crack-overlay clear did not retry the exact active identity");

        Replay original = new Replay("0");
        Replay duplicate = new Replay("1");
        Map<Replay, BreakProgressContext.Session> replaySessions = new IdentityHashMap<>();
        BreakProgressContext.Session originalSession = replaySessions.computeIfAbsent(
            original,
            (key) -> BreakProgressContext.createSession()
        );
        BreakProgressContext.Session reorderedOriginalSession = replaySessions.computeIfAbsent(
            List.of(duplicate, original).get(1),
            (key) -> BreakProgressContext.createSession()
        );
        BreakProgressContext.Session duplicateSession = replaySessions.computeIfAbsent(
            duplicate,
            (key) -> BreakProgressContext.createSession()
        );

        check(original.equals(duplicate), "break-progress identity fixture is no longer structurally equal");
        check(originalSession == reorderedOriginalSession,
            "reordering changed the exact Replay object's crack-overlay id");
        check(originalSession.id() != duplicateSession.id(),
            "structurally equal Replay objects shared a crack-overlay id");

        Replay retained = duplicate;

        for (int i = 0; i < 32; i++)
        {
            Replay replacement = new Replay(String.valueOf(i + 2));

            ActionPlayer.reconcileBreakProgressSessions(replaySessions, List.of(replacement));
            check(replaySessions.isEmpty(),
                "replaced Replay identities remained strongly reachable from crack-overlay ids");
            replaySessions.put(replacement, BreakProgressContext.createSession());
            retained = replacement;
        }

        check(replaySessions.size() == 1 && replaySessions.containsKey(retained),
            "repeated Replay replacement made crack-overlay identity storage unbounded");

        String actionPlayer = classShape(ActionPlayer.class);

        check(actionPlayer.contains("java/util/IdentityHashMap")
                && actionPlayer.contains("breakProgressSessions")
                && actionPlayer.contains("computeIfAbsent")
                && actionPlayer.contains("reconcileBreakProgressSessions")
                && actionPlayer.contains("clearAllBreakProgressSessions")
                && actionPlayer.contains("BreakProgressContext")
                && actionPlayer.contains("withSession"),
            "ActionPlayer no longer scopes and clears one crack-overlay session per exact Replay object");
        check(classShape(BreakBlockActionClip.class).contains("BreakProgressContext")
                && classShape(BreakBlockActionClip.class).contains("updateOrDirect")
                && classShape(BreakBlockActionClip.class).contains("clearCurrent"),
            "break-progress replay no longer updates and clears the playback-scoped overlay session");
    }

    private static void preservesRecordedSwingHand() throws Exception
    {
        SwipeActionClip legacy = new SwipeActionClip();

        check(legacy.hand.get(), "legacy swipe no longer defaults to the main hand");
        check(!legacy.toData().asMap().has("hand"), "default main-hand swipe changed the legacy serialization shape");

        SwipeActionClip offHand = new SwipeActionClip();

        offHand.hand.set(false);

        MapType offHandData = offHand.toData().asMap();

        check(offHandData.has("hand") && !offHandData.getBool("hand"), "off-hand swipe did not persist its selected hand");

        SwipeActionClip decoded = new SwipeActionClip();

        decoded.fromData(offHandData);

        check(!decoded.hand.get(), "off-hand swipe did not survive deserialization");
        check(!((SwipeActionClip) decoded.copy()).hand.get(), "off-hand swipe did not survive copying");

        StubEntity stub = new StubEntity();

        offHand.applyClient(stub, null, null, 0);

        check(stub.getSwingingArm() == InteractionHand.OFF_HAND, "client stub discarded the recorded swing hand");
        check(IEntity.class.getMethod("swingArm", InteractionHand.class).isDefault(),
            "hand-aware swing broke existing IEntity implementations");
        check(IEntity.class.getMethod("getSwingingArm").isDefault(),
            "swing-hand lookup broke existing IEntity implementations");
        check(classShape(ActionRecorder.class).contains("swingingArm"), "action recorder no longer captures vanilla's swinging arm");
        check(classShape(SwipeActionClip.class).contains("getHand"), "swipe replay no longer dispatches its persisted hand");
        check(classShape(IEntity.class).contains("getSwingingArm"), "form entity boundary dropped swing-hand state");
        check(classShape(MCEntity.class).contains("getSwingingArm"), "Minecraft entity adapter dropped swing-hand state");
        check(classShape(ProceduralAnimator.class).contains("getSwingingArm"),
            "procedural model replay no longer selects the recorded swing arm");
    }

    private static void playbackSwipesBypassItemHooks() throws Exception
    {
        SWING_HOOK_CALLS.set(0);

        HookCountingItem item = allocateWithoutConstructor(HookCountingItem.class);
        Field components = Item.class.getDeclaredField("components");

        components.setAccessible(true);
        components.set(item, DataComponentMap.EMPTY);

        ActorEntity actor = allocateWithoutConstructor(ActorEntity.class);
        Field equipment = ActorEntity.class.getDeclaredField("equipment");

        equipment.setAccessible(true);
        equipment.set(actor, new HashMap<EquipmentSlot, ItemStack>());
        ItemStack maliciousStack = new ItemStack(item);

        actor.setItemSlot(EquipmentSlot.OFFHAND, maliciousStack);
        actor.swinging = false;
        actor.swingTime = 0;
        actor.swingingArm = InteractionHand.MAIN_HAND;

        check(maliciousStack.onEntitySwing(actor, InteractionHand.OFF_HAND)
                && SWING_HOOK_CALLS.get() == 1,
            "malicious swing-hook positive control did not execute");
        SWING_HOOK_CALLS.set(0);

        SwipeActionClip offHandSwipe = new SwipeActionClip();

        offHandSwipe.hand.set(false);
        offHandSwipe.apply(actor, null, null, null, 0);

        check(SWING_HOOK_CALLS.get() == 0,
            "visual actor swipe invoked the held item's server swing hook");
        check(actor.swinging && actor.swingTime == -1 && actor.swingingArm == InteractionHand.OFF_HAND,
            "hook-free actor swipe no longer preserves vanilla animation state");
        check(ActorEntity.class.getDeclaredMethod("swing", InteractionHand.class, boolean.class).getDeclaringClass()
                == ActorEntity.class,
            "actor swipe fell back to NeoForge LivingEntity item-hook dispatch");

        String actorShape = classShape(ActorEntity.class);
        String swipeShape = classShape(SwipeActionClip.class);

        check(actorShape.contains("ClientboundAnimatePacket")
                && actorShape.contains("broadcastAndSend")
                && !actorShape.contains("onEntitySwing"),
            "actor's hook-free swing no longer owns the vanilla animation packet boundary");
        check(swipeShape.contains("mchorse/bbs_mod/entity/ActorEntity"),
            "swipe replay restored a generic LivingEntity server fallback");

        HookDispatchEntity nonActor = allocateWithoutConstructor(HookDispatchEntity.class);

        nonActor.equipment = new HashMap<>();
        nonActor.setItemSlot(EquipmentSlot.OFFHAND, maliciousStack);
        offHandSwipe.apply(nonActor, null, null, null, 0);

        check(SWING_HOOK_CALLS.get() == 0,
            "exact Swipe restored an unsafe generic LivingEntity server fallback");

        check(!ActionPlayer.shouldApplyServerActorEquipment(true, false, false),
            "unprivileged visual replay can install equipment on its server actor");
        check(ActionPlayer.shouldApplyServerActorEquipment(true, false, true),
            "administrator replay lost its public server actor equipment path");
        check(!ActionPlayer.shouldApplyServerActorEquipment(true, true, true)
                && !ActionPlayer.shouldApplyServerActorEquipment(true, true, false),
            "private replay projection can install server equipment");
        check(ActionPlayer.shouldApplyServerActorEquipment(false, false, false),
            "non-actor equipment behavior changed unexpectedly");
        check(classShape(ActionPlayer.class).contains("shouldApplyServerActorEquipment"),
            "ActionPlayer no longer gates server actor equipment at the replay application boundary");
    }

    private static void recordsSuccessfulNonLivingAttacksOnce() throws Exception
    {
        check(AttackRecordingContext.shouldRecordNonLivingAttack(true, false),
            "successful non-living attack was rejected");
        check(!AttackRecordingContext.shouldRecordNonLivingAttack(false, false),
            "failed non-living attack was recorded");
        check(!AttackRecordingContext.shouldRecordNonLivingAttack(true, true),
            "living attack entered both generic and living damage recorders");
        check(!AttackRecordingContext.shouldRecordNonLivingAttack(false, true),
            "failed living attack entered the generic recorder");

        String playerAttack = classShape(PlayerEntityMixin.class);

        check(playerAttack.contains("recordNonLivingAttack") && playerAttack.contains("shouldRecordNonLivingAttack"),
            "Player.attack no longer owns the generic Entity.hurt success boundary");
        check(playerAttack.contains("Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            "non-living recorder no longer wraps the exact generic Entity.hurt invocation");
        check(playerAttack.contains("recordDamage"),
            "successful non-living attack no longer creates a targeted damage clip");
        check(classShape(LivingEntityMixin.class).contains("recordDamage"),
            "living damage recorder no longer owns the complementary target path");
    }

    private static void deduplicatesLegacySwipeDamageFallback() throws Exception
    {
        check(ActionRecorder.shouldRecordFallbackAttack(true, false),
            "legacy swing-damage fallback was lost when no exact target was recorded");
        check(!ActionRecorder.shouldRecordFallbackAttack(true, true),
            "legacy swing-damage fallback duplicates an exact targeted attack");
        check(!ActionRecorder.shouldRecordFallbackAttack(false, false)
                && !ActionRecorder.shouldRecordFallbackAttack(false, true),
            "legacy swing-damage fallback records without a new swing");

        String recorder = classShape(ActionRecorder.class);

        check(recorder.contains("targetedAttackRecordedThisTick")
                && recorder.contains("target")
                && recorder.contains("isPresent")
                && recorder.contains("shouldRecordFallbackAttack"),
            "ActionRecorder no longer suppresses targetless fallback after an exact attack");
    }

    private static void commandActionsRequireRealRequesterAuthority() throws Exception
    {
        String command = classShape(CommandActionClip.class);
        String context = classShape(ActionCommandContext.class);
        String executor = classShape(AuthorizedCommandExecutor.class);
        String policy = classShape(FilmActionAuthorityPolicy.class);
        String actionPlayer = classShape(ActionPlayer.class);
        String actionClip = classShape(ActionClip.class);

        check(command.contains("ActionCommandContext") && command.contains("isCommandActionAllowed"),
            "command action no longer uses the authorized requester boundary");
        check(!command.contains("performPrefixedCommand"),
            "command action executes directly as an actor or fake player");
        check(command.contains("execute") && command.contains("SuperFakePlayer") && command.contains("LivingEntity"),
            "command action no longer anchors execution to actor == null ? fake player : actor");
        check(context.contains("ThreadLocal") && context.contains("withRequester"),
            "command requester scope is no longer nested/exception safe");
        check(actionPlayer.contains("ActionCommandContext") && actionPlayer.contains("withRequester"),
            "ActionPlayer no longer installs the real requester around replay actions");
        check(actionClip.contains("FilmActionAuthorityPolicy") && actionClip.contains("isAuthorizedFor"),
            "ActionClip no longer fails closed for privileged custom or built-in server actions");
        check(executor.contains("FilmActionAuthorityPolicy") && executor.contains("withEntity"),
            "command executor no longer delegates authority or anchors the command source entity");
        check(policy.contains("ServerPlayer")
                && policy.contains("SuperFakePlayer")
                && policy.contains("hasPermission")
                && policy.contains("getPlayer"),
            "film action authority no longer requires a current real administrator");
    }

    private static void airPacketsHaveOneRecordingOwner() throws Exception
    {
        int hookConsumed = InteractionActionSemantics.shouldRecord(InteractionResult.SUCCESS) ? 1 : 0;
        int itemConsumed = InteractionActionSemantics.shouldRecord(InteractionResult.CONSUME) ? 1 : 0;
        int passed = InteractionActionSemantics.shouldRecord(InteractionResult.PASS) ? 1 : 0;

        check(hookConsumed == 1, "hook-consumed air use was not recorded exactly once");
        check(itemConsumed == 1, "item-consumed air use was not recorded exactly once");
        check(passed == 0, "passing air use produced an action");

        String mixins = resourceText("bbs.mixins.json", StandardCharsets.UTF_8);

        check(!mixins.contains("\"ItemStackMixin\""), "legacy ItemStack air-use recorder is still active");

        String handlerShape = classShape(ServerPlayNetworkHandlerMixin.class);

        check(handlerShape.contains("redirectOnItemUse"), "server packet handler no longer owns final air-use recording");
        check(handlerShape.contains("ServerPlayerGameMode"), "air-use recorder no longer wraps the game-mode boundary");
    }

    private static void blockPacketsHaveOneRecordingOwner() throws Exception
    {
        int consumedActions = InteractionActionSemantics.shouldRecord(InteractionResult.SUCCESS) ? 1 : 0;
        int passedActions = InteractionActionSemantics.shouldRecord(InteractionResult.PASS) ? 1 : 0;
        int failedActions = InteractionActionSemantics.shouldRecord(InteractionResult.FAIL) ? 1 : 0;

        check(consumedActions == 1, "successful block use did not produce exactly one final action");
        check(passedActions == 0, "passing block use produced an action");
        check(failedActions == 0, "failed block placement produced an action");

        String mixins = resourceText("bbs.mixins.json", StandardCharsets.UTF_8);

        check(!mixins.contains("\"BlockItemMixin\""), "legacy block placement recorder is still active");

        PlaceBlockActionClip legacyPlaceBlock = new PlaceBlockActionClip();

        check(legacyPlaceBlock.copy() instanceof PlaceBlockActionClip, "legacy PlaceBlock action no longer copies through its registered shape");
    }

    private static void recordingOnlyClipsDeserializeButAreNotAddable()
    {
        Link type = Link.bbs("interact_entity");
        ClipFactoryData metadata = new ClipFactoryData(null, 0).recordingOnly();
        MapFactory<Clip, ClipFactoryData> factory = new MapFactory<>();

        factory.register(type, EntityInteractionActionClip.class, metadata);

        MapType data = new MapType();

        data.putString(factory.getTypeKey(), type.toString());

        check(factory.fromData(data) instanceof EntityInteractionActionClip,
            "recording-only entity interaction is no longer deserializable");
        check(!factory.getData(type).isAddable(),
            "recording-only entity interaction is exposed in Add Clip");
        check(new ClipFactoryData(null, 0).isAddable(),
            "ordinary clip metadata became hidden by default");

        try
        {
            String coreFactory = classShape(BBSMod.class);

            check(coreFactory.contains("interact_entity") && coreFactory.contains("recordingOnly"),
                "core entity-interaction registration is no longer marked recording-only");
        }
        catch (Exception e)
        {
            throw new AssertionError("core action factory shape was not available", e);
        }
    }

    private static void validatesPacketHitBounds()
    {
        BlockPos pos = new BlockPos(10, 64, -2);
        Vec3 center = Vec3.atCenterOf(pos);

        check(InteractionActionSemantics.isValidBlockHit(new BlockHitResult(center.add(1D, 0D, 0D), Direction.EAST, pos, false)),
            "vanilla boundary hit was rejected");
        check(!InteractionActionSemantics.isValidBlockHit(new BlockHitResult(center.add(1.01D, 0D, 0D), Direction.EAST, pos, false)),
            "out-of-block hit was accepted");
    }

    private static void validatesFinalActionSideEffectGuards() throws Exception
    {
        check(InteractionActionSemantics.isValidBreakProgress(-1), "break-progress clear value was rejected");
        check(InteractionActionSemantics.isValidBreakProgress(0), "initial break progress was rejected");
        check(InteractionActionSemantics.isValidBreakProgress(9), "final break progress was rejected");
        check(!InteractionActionSemantics.isValidBreakProgress(-2), "invalid negative break progress was accepted");
        check(!InteractionActionSemantics.isValidBreakProgress(10), "out-of-range break progress was accepted");

        String blockGuard = classShape(InteractionActionSemantics.class);

        check(blockGuard.contains("isInSpawnableBounds")
                && blockGuard.contains("isOutsideBuildHeight")
                && blockGuard.contains("isWithinBounds")
                && blockGuard.contains("hasChunkAt")
                && blockGuard.contains("canInteractWithBlock")
                && blockGuard.contains("mayInteract")
                && blockGuard.contains("mayBuild"),
            "block mutation guard dropped a world, loading, reach, spawn-protection, or build-permission boundary");

        String place = classShape(PlaceBlockActionClip.class);
        String breakBlock = classShape(BreakBlockActionClip.class);
        String itemDrop = classShape(ItemDropActionClip.class);
        String damage = classShape(DamageActionClip.class);

        check(place.contains("tryApplyPositionRotation")
                && place.contains("canReplayBlockAction")
                && place.contains("isEnabled")
                && place.contains("destroyBlock")
                && place.contains("setBlockAndUpdate"),
            "place-block replay bypassed validation or lost its world mutation");
        check(breakBlock.contains("tryApplyPositionRotation")
                && breakBlock.contains("canReplayBlockAction")
                && breakBlock.contains("isValidBreakProgress")
                && breakBlock.contains("updateOrDirect")
                && breakBlock.contains("clearCurrent"),
            "break-progress replay bypassed validation or lost its world update");
        check(itemDrop.contains("tryApplyPositionRotation")
                && itemDrop.contains("isItemDropInputAllowed")
                && itemDrop.contains("isEmpty")
                && itemDrop.contains("isItemEnabled")
                && itemDrop.contains("net/minecraft/world/entity/item/ItemEntity")
                && itemDrop.contains("addFreshEntity"),
            "item-drop replay bypassed validation or lost its entity spawn");
        check(damage.contains("tryApplyPositionRotation")
                && damage.contains("isDamageAllowed")
                && damage.contains("mobAttack")
                && damage.contains("hurt"),
            "damage replay bypassed validation or lost its attributed damage");
        check(!FilmPlaybackPolicy.isDamageAllowed(Float.NaN)
                && !FilmPlaybackPolicy.isDamageAllowed(Float.POSITIVE_INFINITY),
            "damage policy accepted a non-finite value");
    }

    private static void shiftsOnlyAbsoluteItemDropPositions()
    {
        ItemDropActionClip absolute = itemDropAt(1.25D, 2.5D, -3.75D, false);

        absolute.shift(4D, -1D, 0.5D);

        check(absolute.posX.get() == 5.25D
                && absolute.posY.get() == 1.5D
                && absolute.posZ.get() == -3.25D,
            "absolute item-drop position did not follow the film shift");

        ItemDropActionClip relative = itemDropAt(1.25D, 2.5D, -3.75D, true);

        relative.shift(4D, -1D, 0.5D);

        check(relative.posX.get() == 1.25D
                && relative.posY.get() == 2.5D
                && relative.posZ.get() == -3.75D,
            "actor-relative item-drop offset was shifted a second time");
    }

    private static ItemDropActionClip itemDropAt(double x, double y, double z, boolean relative)
    {
        ItemDropActionClip clip = new ItemDropActionClip();

        clip.posX.set(x);
        clip.posY.set(y);
        clip.posZ.set(z);
        clip.relative.set(relative);

        return clip;
    }

    private static void preservesExactTargetBeforeFallback()
    {
        Candidate exact = new Candidate("wanted", 20D, true);
        Candidate nearer = new Candidate("other", 1D, true);
        Candidate selected = InteractionActionSemantics.selectTarget(
            List.of(nearer, exact),
            "wanted",
            Candidate::allowed,
            Candidate::id,
            Candidate::distance
        );

        check(selected == exact, "nearest fallback replaced an exact UUID target");
    }

    private static void filtersFallbackCandidates()
    {
        Candidate rejected = new Candidate("wrong-replay", 0.1D, false);
        Candidate expected = new Candidate("matching-replay", 2D, true);
        Candidate selected = InteractionActionSemantics.selectTarget(
            List.of(rejected, expected),
            "missing",
            Candidate::allowed,
            Candidate::id,
            Candidate::distance
        );

        check(selected == expected, "fallback ignored the replay/type predicate");
    }

    private static void scopesActorTargetsToOnePlayback() throws Exception
    {
        Candidate firstPlayback = new Candidate("shared-replay", 1D, true);
        Candidate secondPlayback = new Candidate("shared-replay", 1D, true);
        Map<String, Candidate> firstActors = Map.of("1", firstPlayback);
        Map<String, Candidate> secondActors = Map.of("1", secondPlayback);

        check(InteractionActionSemantics.selectScopedTarget(firstActors, "1", Candidate::allowed) == firstPlayback,
            "first playback resolved another playback's actor");
        check(InteractionActionSemantics.selectScopedTarget(secondActors, "1", Candidate::allowed) == secondPlayback,
            "second playback resolved another playback's actor");
        check(InteractionActionSemantics.selectScopedTarget(firstActors, "2", Candidate::allowed) == null,
            "missing scoped actor fell through to another playback");
        check(classShape(ActionPlayer.class).contains("withReplayActors"),
            "ActionPlayer no longer scopes action targets to its actor map");
        check(classShape(ActionTarget.class).contains("REPLAY_ACTORS"),
            "ActionTarget no longer fails closed inside an active playback scope");
    }

    private static void preservesLegacyActionShapes()
    {
        AttackActionClip legacyAttack = new AttackActionClip();
        MapType legacyAttackData = legacyAttack.toData().asMap();

        check(!legacyAttackData.has("target"), "legacy attack unexpectedly serialized a target");
        check(!legacyAttackData.has("primary"), "legacy attack unexpectedly serialized target intent");

        legacyAttack.target.uuid.set("00000000-0000-0000-0000-000000000001");
        MapType targetedAttackData = legacyAttack.toData().asMap();

        check(targetedAttackData.has("target"), "targeted attack dropped its target snapshot");
        check(targetedAttackData.has("primary"), "targeted attack dropped primary/sweep intent");

        InteractBlockActionClip legacyBlock = new InteractBlockActionClip();
        MapType legacyBlockData = legacyBlock.toData().asMap();

        check(!legacyBlockData.has("stack"), "legacy block interaction unexpectedly serialized a hand stack");
        check(!legacyBlockData.has("full_dispatch"), "legacy block interaction unexpectedly changed dispatch mode");
        check(!legacyBlockData.has("secondary_use"), "legacy block interaction unexpectedly serialized secondary use");

        legacyBlock.fullDispatch.set(true);
        MapType dispatchedBlockData = legacyBlock.toData().asMap();

        check(dispatchedBlockData.has("stack"), "full block dispatch dropped its hand stack");
        check(dispatchedBlockData.getBool("full_dispatch"), "full block dispatch flag was not serialized");
        check(!dispatchedBlockData.has("secondary_use"), "ordinary full dispatch serialized a false secondary-use flag");

        legacyBlock.secondaryUse.set(true);
        MapType secondaryBlockData = legacyBlock.toData().asMap();

        check(secondaryBlockData.getBool("secondary_use"), "shift/full dispatch dropped secondary-use intent");

        UseItemActionClip airUse = new UseItemActionClip();

        check(!airUse.toData().asMap().has("full_dispatch"), "air-use action exposed a packet-unreachable empty-hand dispatch mode");
        check(!airUse.toData().asMap().has("secondary_use"), "legacy air-use action unexpectedly serialized secondary use");
        airUse.secondaryUse.set(true);
        check(airUse.toData().asMap().getBool("secondary_use"), "shift air-use action dropped secondary-use intent");
    }

    private static void sourceShapesPreserveInteractionContracts() throws Exception
    {
        String mixins = resourceText("bbs.mixins.json", StandardCharsets.UTF_8);

        check(mixins.contains("\"ServerPlayerInteractionMixin\""),
            "entity interaction recorder is no longer registered as a server mixin");
        check(mixins.contains("\"ServerPlayerGameModeMixin\""),
            "committed block-break recorder is no longer registered as a server mixin");

        String blockBreakRecorder = classShape(ServerPlayerGameModeMixin.class);

        check(blockBreakRecorder.contains("addActionExact")
                && blockBreakRecorder.contains("getReturnValueZ")
                && blockBreakRecorder.contains("getBlockState")
                && blockBreakRecorder.contains("shouldRecordCommittedBlockBreak"),
            "block-break recording no longer waits for a committed state change on the captured recorder");

        String gun = classShape(GunItem.class);

        check(gun.contains("sidedSuccess"), "gun no longer consumes the selected hand on both sides");
        check(gun.contains("setOwner"), "gun projectile owner is no longer assigned before spawning");
        check(classShape(GunProjectileEntity.class).contains("indirectMagic"),
            "gun projectile damage no longer attributes the indirect owner");

        String modelBlock = classShape(ModelBlock.class);

        check(modelBlock.contains("arePanelsAllowed"), "ModelBlock editor no longer checks panel permission");
        check(modelBlock.contains("SUCCESS") && modelBlock.contains("CONSUME"),
            "ModelBlock no longer uses client/server sided consumption");

        String entityRecorder = classShape(ServerPlayerInteractionMixin.class);
        String entityReplay = classShape(EntityInteractionActionClip.class);

        check(entityRecorder.contains("EntityInteractionActionClip") && entityRecorder.contains("secondaryUse"),
            "entity packet result no longer records target/hand interaction state");
        check(entityReplay.contains("onInteractEntityAt") && entityReplay.contains("interactAt") && entityReplay.contains("interactOn"),
            "entity replay no longer preserves interact/interactAt hook branches");
        check(entityReplay.contains("location") && entityReplay.contains("canReplayEntity"),
            "entity replay dropped relative hit or distance validation");

        String attack = classShape(AttackActionClip.class);
        String damageRecorder = classShape(LivingEntityMixin.class);
        String eventCompat = classShape(ActionEventCompat.class);

        check(attack.contains("ActionTarget") && attack.contains("primary") && attack.contains("playerAttack"),
            "attack replay dropped explicit target or primary/sweep attribution");
        check(attack.contains("isDamageAllowed"),
            "attack replay bypassed the shared final damage policy");
        check(attack.contains("onPlayerAttackTarget"), "primary attack replay bypassed the NeoForge attack hook");
        check(damageRecorder.contains("AttackRecordingContext") && damageRecorder.contains("recordDamage"),
            "damage recording no longer distinguishes primary and sweep targets");
        check(eventCompat.contains("LOWEST") && eventCompat.contains("isCanceled"),
            "chat recording no longer observes the final cancellable-event phase");
    }

    private static void verifiesEntityDispatcherShape() throws Exception
    {
        Class<?> handler = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl$1", false, InteractionActionSemanticsTest.class.getClassLoader());
        Method perform = null;
        int interactionOverloads = 0;

        for (Method method : handler.getDeclaredMethods())
        {
            if (method.getName().equals("performInteraction"))
            {
                perform = method;
            }
            else if (method.getName().equals("onInteraction"))
            {
                interactionOverloads += 1;
            }
        }

        check(perform != null, "entity packet handler no longer exposes performInteraction");
        check(perform.getParameterCount() == 2, "performInteraction signature changed");
        check(perform.getParameterTypes()[0] == InteractionHand.class, "performInteraction hand parameter changed");
        check(perform.getParameterTypes()[1].getName().equals("net.minecraft.server.network.ServerGamePacketListenerImpl$EntityInteraction"),
            "performInteraction callback type changed");
        check(interactionOverloads == 2, "entity interaction packet overloads changed");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static String classShape(Class<?> type) throws Exception
    {
        String resource = type.getName().replace('.', '/') + ".class";

        try (InputStream stream = type.getClassLoader().getResourceAsStream(resource))
        {
            check(stream != null, type.getSimpleName() + " bytecode was not available to the regression");

            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String resourceText(String resource, java.nio.charset.Charset charset) throws Exception
    {
        try (InputStream stream = InteractionActionSemanticsTest.class.getClassLoader().getResourceAsStream(resource))
        {
            check(stream != null, resource + " was not available to the regression");

            return new String(stream.readAllBytes(), charset);
        }
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) throws Exception
    {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");

        field.setAccessible(true);

        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static final AtomicInteger SWING_HOOK_CALLS = new AtomicInteger();

    private static final class HookCountingItem extends Item
    {
        private HookCountingItem()
        {
            super(new Item.Properties());
        }

        @Override
        public boolean onEntitySwing(ItemStack stack, LivingEntity entity, InteractionHand hand)
        {
            SWING_HOOK_CALLS.incrementAndGet();

            return true;
        }
    }

    private static final class HookDispatchEntity extends LivingEntity
    {
        private Map<EquipmentSlot, ItemStack> equipment;

        private HookDispatchEntity()
        {
            super(null, null);
        }

        @Override
        public Iterable<ItemStack> getArmorSlots()
        {
            return List.of();
        }

        @Override
        public Iterable<ItemStack> getHandSlots()
        {
            return List.of(
                this.getItemBySlot(EquipmentSlot.MAINHAND),
                this.getItemBySlot(EquipmentSlot.OFFHAND)
            );
        }

        @Override
        public ItemStack getItemBySlot(EquipmentSlot slot)
        {
            return this.equipment.getOrDefault(slot, ItemStack.EMPTY);
        }

        @Override
        public void setItemSlot(EquipmentSlot slot, ItemStack stack)
        {
            this.equipment.put(slot, stack == null ? ItemStack.EMPTY : stack);
        }

        @Override
        public HumanoidArm getMainArm()
        {
            return HumanoidArm.RIGHT;
        }
    }

    private record ProgressEvent(Object level, int id, BlockPos pos, int progress)
    {
        private boolean matches(Object level, int id, BlockPos pos, int progress)
        {
            return this.level == level
                && this.id == id
                && this.pos.equals(pos)
                && this.progress == progress;
        }
    }

    private record Candidate(String id, double distance, boolean allowed)
    {}

}
