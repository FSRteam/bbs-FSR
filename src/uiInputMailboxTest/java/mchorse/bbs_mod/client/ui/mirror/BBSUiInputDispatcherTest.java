package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonSide;
import mchorse.bbs_mod.api.client.ui.BBSUiInputAction;
import mchorse.bbs_mod.api.client.ui.BBSUiInputBatch;
import mchorse.bbs_mod.api.client.ui.BBSUiInputEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiInputResult;
import mchorse.bbs_mod.api.client.ui.BBSUiInputStatus;
import mchorse.bbs_mod.api.client.ui.BBSUiKeyEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.context.UIContextMenu;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragEndEvent;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.framework.elements.utils.IViewportStack;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.framework.elements.utils.UICanvas;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scroll;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.lang.reflect.Field;

/** Deterministic mailbox, lifecycle, and synthetic-release regression checks. */
public final class BBSUiInputDispatcherTest
{
    private static final long SESSION = 41L;
    private static final BBSUiRemoteInputState IDLE_STATE = state(0D, 0D, 0, Set.of(), 0);

    private BBSUiInputDispatcherTest()
    {}

    public static void main(String[] args)
    {
        bootstrapStandaloneModLoadingRuntime();
        assertProductionBBSModLoaded();
        Runnable restoreClipFactories = installHeadlessClipFactories();
        Runnable restoreL10n = installHeadlessL10n();

        try
        {
            runAll();
        }
        finally
        {
            restoreL10n.run();
            restoreClipFactories.run();
        }
    }

    private static void assertProductionBBSModLoaded()
    {
        try
        {
            Class<?> modClass = Class.forName("mchorse.bbs_mod.BBSMod");
            Object modId = modClass.getField("MOD_ID").get(null);

            modClass.getDeclaredMethod("getSettingsFolder");
            check("bbs".equals(modId),
                "loaded BBSMod production signature has an unexpected mod id");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(
                "BBSUiInputDispatcherTest must run against the production BBSMod class, not a test stub",
                exception
            );
        }
    }

    private static Runnable installHeadlessClipFactories()
    {
        try
        {
            Class<?> modClass = Class.forName("mchorse.bbs_mod.BBSMod");
            Class<?> factoryClass = Class.forName("mchorse.bbs_mod.utils.factory.MapFactory");
            Field cameraClips = modClass.getDeclaredField("factoryCameraClips");
            Field actionClips = modClass.getDeclaredField("factoryActionClips");

            cameraClips.setAccessible(true);
            actionClips.setAccessible(true);

            Object previousCameraClips = cameraClips.get(null);
            Object previousActionClips = actionClips.get(null);

            if (previousCameraClips == null)
            {
                cameraClips.set(null, factoryClass.getDeclaredConstructor().newInstance());
            }
            if (previousActionClips == null)
            {
                actionClips.set(null, factoryClass.getDeclaredConstructor().newInstance());
            }

            return () ->
            {
                try
                {
                    cameraClips.set(null, previousCameraClips);
                    actionClips.set(null, previousActionClips);
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the clip-factory test state", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the headless clip-factory test state", exception);
        }
    }

    private static void bootstrapStandaloneModLoadingRuntime()
    {
        try
        {
            Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");

            if (loadingModList.getMethod("get").invoke(null) == null)
            {
                loadingModList.getMethod(
                    "of",
                    List.class,
                    List.class,
                    List.class,
                    List.class,
                    java.util.Map.class
                ).invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
            }

            Class.forName("net.minecraft.SharedConstants")
                .getMethod("tryDetectVersion")
                .invoke(null);
            Class.forName("net.minecraft.server.Bootstrap")
                .getMethod("bootStrap")
                .invoke(null);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not bootstrap the standalone mod-loading test runtime", exception);
        }
    }

    private static void runAll()
    {
        runIsolated(BBSUiInputDispatcherTest::assertReliableMailboxCapacity);
        runIsolated(BBSUiInputDispatcherTest::assertStateTailOnlyCoalescesNewerSequences);
        runIsolated(BBSUiInputDispatcherTest::assertScreenAndAddonGenerationsInvalidatePendingInput);
        runIsolated(BBSUiInputDispatcherTest::assertRemoteLeaseEndsLocalGestureFirst);
        runIsolated(BBSUiInputDispatcherTest::assertBatchStopsWhenTargetChanges);
        runIsolated(BBSUiInputDispatcherTest::assertRemoteLeaseExcludesLocalHeldState);
        runIsolated(BBSUiInputDispatcherTest::assertSyntheticMouseAndKeyRelease);
        runIsolated(BBSUiInputDispatcherTest::assertSyntheticReleaseStopsAfterTargetChange);
        runIsolated(BBSUiInputDispatcherTest::assertLifecycleReleaseRejectsReentrantLease);
        runIsolated(BBSUiInputDispatcherTest::assertClearReleaseRejectsReentrantLease);
        runIsolated(BBSUiInputDispatcherTest::assertThrowingCurrentCheckDoesNotStallMailbox);
        runIsolated(BBSUiInputDispatcherTest::assertDetachClearsTargetAfterSyntheticReleaseError);
        runIsolated(BBSUiInputDispatcherTest::assertDetachPreservesReentrantReplacement);
        runIsolated(BBSUiInputDispatcherTest::assertResetSynthesizesHeldReleases);
        runIsolated(BBSUiInputDispatcherTest::assertClearRunsBeforePendingInput);
        runIsolated(BBSUiInputDispatcherTest::assertNonOwnerClearCancelsQueuedGeneration);
        runIsolated(BBSUiInputDispatcherTest::assertSingleDrainBudget);
        runIsolated(BBSUiInputDispatcherTest::assertExecutorFailureCompletesFuture);
        runIsolated(BBSUiInputDispatcherTest::assertExecutorFailureReleasesExistingLease);
        runIsolated(BBSUiInputDispatcherTest::assertOffThreadExecutorFailureDefersReleaseUntilDrain);
        runIsolated(BBSUiInputDispatcherTest::assertOffThreadExecutorFailureLifecycleResetReleasesLease);
        runIsolated(BBSUiInputDispatcherTest::assertDispatchFailureReleasesLease);
        runIsolated(BBSUiInputDispatcherTest::assertCloseCompletesFuture);
        BBSUiModifierTimelineTest.runAll();
        assertMouseGestureButtonOwnership();
        assertTrackpadButtonInterleaving();
        assertDisabledRootBlocksNonTerminalKeyboardInput();
        assertFrameworkMouseCaptureAcrossBlockingOverlay();
        UIElementRemovalOwnershipTest.runAll();
        UIWrapperGestureOwnershipTest.runAll();
        UISecondaryGestureOwnershipTest.runAll();
        UISecondaryGestureOwnershipRuntimeTest.runAll();
        UIKeybindGestureOwnershipTest.runAll();
        UIFormCategoryHitTestTest.runAll();
        UISectionMouseButtonTest.runAll();
        DockPanelDragFailureTest.runAll();
        OrbitFilmGestureOwnershipTest.runAll();
        GizmoInteractionGenerationTest.runAll();
        BBSUiLifecycleSourceTest.runAll();
        BBSUiOpenDispatcherTest.runAll();

        System.out.println("BBSUiInputDispatcherTest: all tests passed");
    }

    private static void assertDisabledRootBlocksNonTerminalKeyboardInput()
    {
        TestMenu menu = new TestMenu();
        RecordingKeyboardElement element = new RecordingKeyboardElement();

        menu.main.add(element);
        menu.getRoot().setEnabled(false);

        check(!menu.handleKey(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_PRESS, 0),
            "disabled root reported a blocked key press as handled");
        check(!menu.handleKey(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_REPEAT, 0),
            "disabled root reported a blocked key repeat as handled");
        menu.handleTextInput('a');
        check(element.presses == 0 && element.repeats == 0 && element.textInputs == 0,
            "disabled root dispatched non-terminal keyboard or text input into its hierarchy");

        check(!menu.handleKey(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_RELEASE, 0),
            "disabled root changed the existing terminal-release return contract");
        check(element.releases == 1,
            "disabled root did not deliver the terminal key release needed for cleanup");
    }

    private static Runnable installHeadlessL10n()
    {
        try
        {
            Class<?> clientClass = Class.forName("mchorse.bbs_mod.BBSModClient");
            Field l10n = clientClass.getDeclaredField("l10n");

            l10n.setAccessible(true);

            Object previous = l10n.get(null);

            if (previous != null)
            {
                return () -> {};
            }

            Class<?> l10nClass = Class.forName("mchorse.bbs_mod.l10n.L10n");
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");

            theUnsafe.setAccessible(true);

            Object unsafe = theUnsafe.get(null);
            Object headless = unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, l10nClass);
            Field strings = l10nClass.getDeclaredField("strings");
            Field langFiles = l10nClass.getDeclaredField("langFiles");
            Field supportedLanguages = l10nClass.getDeclaredField("supportedLanguages");

            strings.setAccessible(true);
            langFiles.setAccessible(true);
            supportedLanguages.setAccessible(true);
            strings.set(headless, new java.util.HashMap<>());
            langFiles.set(headless, new java.util.LinkedHashSet<>());
            supportedLanguages.set(headless, new ArrayList<>());

            l10n.set(null, headless);

            return () ->
            {
                try
                {
                    l10n.set(null, previous);
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the localization test singleton", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the headless localization test singleton", exception);
        }
    }

    private static void assertReliableMailboxCapacity()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("mailbox-capacity");
        int capacity = BBSUiInputDispatcher.pendingCapacityForTesting();
        List<CompletableFuture<BBSUiInputResult>> accepted = new ArrayList<>(capacity);

        for (int i = 0; i < capacity; i++)
        {
            accepted.add(BBSUiInputDispatcher.submit(addon, reliableBatch(SESSION, i + 1L, GLFW.GLFW_KEY_A)));
        }

        check(BBSUiInputDispatcher.pendingCountForTesting() == capacity,
            "reliable mailbox did not retain exactly its advertised capacity");
        check(executor.queuedCount() == 1, "mailbox scheduled more than one initial drain");
        check(accepted.stream().noneMatch(CompletableFuture::isDone),
            "reliable input completed before the client executor drained it");

        CompletableFuture<BBSUiInputResult> overflow = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION, capacity + 1L, GLFW.GLFW_KEY_A)
        );
        BBSUiInputResult rejected = completed(overflow, "mailbox overflow");

        check(rejected.status() == BBSUiInputStatus.REJECTED,
            "mailbox overflow was not explicitly rejected");
        check(rejected.message().contains("mailbox is full") && rejected.message().contains("reliable input was not queued"),
            "mailbox overflow did not explain that reliable input was rejected");
        check(BBSUiInputDispatcher.pendingCountForTesting() == capacity,
            "mailbox overflow changed the accepted reliable queue");

        executor.runAll();
        check(accepted.stream().allMatch((future) -> completed(future, "accepted reliable input").applied()),
            "an accepted reliable batch was lost while draining a full mailbox");
        check(BBSUiInputDispatcher.pendingCountForTesting() == 0, "full mailbox did not drain completely");
    }

    private static void assertStateTailOnlyCoalescesNewerSequences()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("state-coalesce");
        CompletableFuture<BBSUiInputResult> first = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 10L, state(10D, 20D, 0, Set.of(), 0))
        );

        BBSUiInputResult equal = completed(
            BBSUiInputDispatcher.submit(addon, stateBatch(SESSION, 10L, state(11D, 21D, 0, Set.of(), 0))),
            "equal pending state sequence"
        );
        BBSUiInputResult older = completed(
            BBSUiInputDispatcher.submit(addon, stateBatch(SESSION, 9L, state(12D, 22D, 0, Set.of(), 0))),
            "older pending state sequence"
        );

        check(equal.status() == BBSUiInputStatus.STALE_SEQUENCE,
            "equal state sequence replaced the pending tail");
        check(older.status() == BBSUiInputStatus.STALE_SEQUENCE,
            "older state sequence replaced the pending tail");
        check(!first.isDone(), "the original state completed without a newer replacement or drain");

        CompletableFuture<BBSUiInputResult> newer = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 11L, state(13D, 23D, 0, Set.of(), 0))
        );
        BBSUiInputResult coalesced = completed(first, "coalesced state");

        check(coalesced.applied() && coalesced.message().contains("coalesced"),
            "a newer state did not explicitly complete the superseded tail");
        check(!newer.isDone(), "the newest coalesced state completed before drain");
        check(BBSUiInputDispatcher.pendingCountForTesting() == 1,
            "state coalescing did not retain exactly one pending tail");

        executor.runAll();
        check(completed(newer, "newest state").applied(), "newest state was not applied");
        check(BBSUiInputDispatcher.effectiveMouseX(SESSION, -1) == 13 &&
            BBSUiInputDispatcher.effectiveMouseY(SESSION, -1) == 23,
            "newest coalesced state was not the state installed by the drain");
    }

    private static void assertScreenAndAddonGenerationsInvalidatePendingInput()
    {
        RecordingTarget firstTarget = new RecordingTarget();
        ManualExecutor executor = install(firstTarget, SESSION);
        BBSAddonDescriptor addon = descriptor("generation-owner");
        CompletableFuture<BBSUiInputResult> oldScreen = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION, 1L, GLFW.GLFW_KEY_A)
        );
        RecordingTarget secondTarget = new RecordingTarget();

        BBSUiInputDispatcher.installForTesting(secondTarget, SESSION + 1L, executor);
        check(completed(oldScreen, "old screen generation").status() == BBSUiInputStatus.STALE_SESSION,
            "screen generation change did not reject the old pending input");

        CompletableFuture<BBSUiInputResult> currentScreen = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION + 1L, 2L, GLFW.GLFW_KEY_B)
        );
        executor.runAll();
        check(completed(currentScreen, "current screen generation").applied(),
            "current screen generation did not apply");
        check(firstTarget.actions.isEmpty(), "old screen received input after its generation was cleared");
        check(secondTarget.actions.equals(List.of(keyAction(GLFW.GLFW_KEY_B, GLFW.GLFW_RELEASE))),
            "new screen did not exclusively receive the current generation input");

        secondTarget.actions.clear();
        CompletableFuture<BBSUiInputResult> oldAddon = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION + 1L, 3L, GLFW.GLFW_KEY_C)
        );
        BBSUiInputDispatcher.clear(addon);
        check(completed(oldAddon, "old addon generation").status() == BBSUiInputStatus.REJECTED,
            "addon clear did not reject its pending generation");

        CompletableFuture<BBSUiInputResult> currentAddon = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION + 1L, 4L, GLFW.GLFW_KEY_D)
        );
        executor.runAll();
        check(completed(currentAddon, "current addon generation").applied(),
            "input submitted after addon clear did not apply");
        check(secondTarget.actions.equals(List.of(keyAction(GLFW.GLFW_KEY_D, GLFW.GLFW_RELEASE))),
            "cleared addon generation leaked an event into the active target");
    }

    private static void assertSyntheticMouseAndKeyRelease()
    {
        RecordingTarget target = new RecordingTarget();
        target.inspectSyntheticReleaseLease = true;
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("synthetic-release");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        CompletableFuture<BBSUiInputResult> applied = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 1L, held)
        );

        executor.runAll();
        check(completed(applied, "held state").applied(), "held input state did not apply");
        check(target.actions.isEmpty(), "state-only batch dispatched a discrete event");

        BBSUiInputDispatcher.clear(addon);
        check(BBSUiRemoteHeldState.isActive(),
            "remote lease was dropped before its client-thread clear released held gestures");
        executor.runAll();

        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE)
        )), "clear did not synthesize the held mouse and key releases exactly once");
        check(target.syntheticReleaseObservations == 2 && target.syntheticReleaseLeaseAuthoritative,
            "synthetic release callbacks did not retain the remote held/modifier snapshot");
        check(!target.localHeldSupplierPolled,
            "synthetic release callbacks polled physical local held-state suppliers");
        check(!BBSUiRemoteHeldState.isActive(),
            "remote held-state snapshot remained active after all synthetic releases");
    }

    private static void assertSyntheticReleaseStopsAfterTargetChange()
    {
        RecordingTarget original = new RecordingTarget();
        RecordingTarget replacement = new RecordingTarget();
        ManualExecutor executor = install(original, SESSION);
        BBSAddonDescriptor addon = descriptor("synthetic-release-screen-change");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_B),
            0
        );
        CompletableFuture<BBSUiInputResult> applied = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 1L, held)
        );

        executor.runAll();
        check(completed(applied, "screen-changing held state").applied(),
            "held state for screen-changing release did not apply");

        original.afterFirstAction = () ->
            BBSUiInputDispatcher.installForTesting(replacement, SESSION + 1L, executor);
        BBSUiInputDispatcher.clear(addon);
        executor.runAll();

        check(original.actions.equals(List.of(mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT))),
            "old target received synthetic releases after its first release switched the screen");
        check(replacement.actions.isEmpty(),
            "new target received held releases belonging to the detached screen");
        check(!BBSUiRemoteHeldState.isActive(),
            "screen-changing synthetic release retained the old remote snapshot");
    }

    private static void assertLifecycleReleaseRejectsReentrantLease()
    {
        RecordingTarget target = new RecordingTarget();
        BBSUiInputDispatcher.installForTesting(target, SESSION, Runnable::run);
        BBSAddonDescriptor owner = descriptor("lifecycle-release-owner");
        BBSAddonDescriptor reentrant = descriptor("lifecycle-release-reentrant");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        CompletableFuture<BBSUiInputResult> acquired = BBSUiInputDispatcher.submit(
            owner,
            stateBatch(SESSION, 1L, held)
        );

        check(completed(acquired, "reentrant lifecycle owner").applied(),
            "old lease did not apply before lifecycle release");

        AtomicReference<CompletableFuture<BBSUiInputResult>> reentrantResult = new AtomicReference<>();

        target.afterFirstAction = () -> reentrantResult.set(BBSUiInputDispatcher.submit(
            reentrant,
            stateBatch(
                SESSION,
                1L,
                state(31D, 37D, 1 << GLFW.GLFW_MOUSE_BUTTON_RIGHT, Set.of(GLFW.GLFW_KEY_B), 0)
            )
        ));

        BBSUiInputDispatcher.reset();

        CompletableFuture<BBSUiInputResult> attempted = reentrantResult.get();

        check(attempted != null, "first synthetic release did not execute its reentrant submit callback");
        BBSUiInputResult rejected = completed(attempted, "reentrant lifecycle acquisition");
        check(rejected.status() == BBSUiInputStatus.REJECTED
                && rejected.message().contains("being released"),
            "release callback reacquired the old target with the new lifecycle generation");
        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE)
        )), "reentrant lifecycle submit interrupted or duplicated the old lease releases");
        check(!BBSUiRemoteHeldState.isActive(),
            "reentrant lifecycle submit retained a held-state lease for the old session");
    }

    private static void assertClearReleaseRejectsReentrantLease()
    {
        RecordingTarget target = new RecordingTarget();
        BBSUiInputDispatcher.installForTesting(target, SESSION, Runnable::run);
        BBSAddonDescriptor owner = descriptor("clear-release-owner");
        BBSAddonDescriptor reentrant = descriptor("clear-release-reentrant");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        CompletableFuture<BBSUiInputResult> acquired = BBSUiInputDispatcher.submit(
            owner,
            stateBatch(SESSION, 1L, held)
        );

        check(completed(acquired, "reentrant clear owner").applied(),
            "old lease did not apply before addon clear");

        AtomicReference<CompletableFuture<BBSUiInputResult>> reentrantResult = new AtomicReference<>();

        target.afterFirstAction = () -> reentrantResult.set(BBSUiInputDispatcher.submit(
            reentrant,
            stateBatch(
                SESSION,
                1L,
                state(31D, 37D, 1 << GLFW.GLFW_MOUSE_BUTTON_RIGHT, Set.of(GLFW.GLFW_KEY_B), 0)
            )
        ));

        BBSUiInputDispatcher.clear(owner);

        CompletableFuture<BBSUiInputResult> attempted = reentrantResult.get();

        check(attempted != null, "addon clear did not execute its reentrant submit callback");
        BBSUiInputResult rejected = completed(attempted, "reentrant clear acquisition");
        check(rejected.status() == BBSUiInputStatus.REJECTED
                && rejected.message().contains("being released"),
            "clear callback queued a new lease behind its own release fence");
        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE)
        )), "reentrant clear submit interrupted or duplicated the old lease releases");
        check(!BBSUiRemoteHeldState.isActive(),
            "reentrant clear submit retained a remote held-state lease");
    }

    private static void assertThrowingCurrentCheckDoesNotStallMailbox()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor owner = descriptor("throwing-current-owner");
        BBSAddonDescriptor replacement = descriptor("throwing-current-replacement");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );

        CompletableFuture<BBSUiInputResult> acquired = BBSUiInputDispatcher.submit(
            owner,
            stateBatch(SESSION, 1L, held)
        );
        executor.runAll();
        check(completed(acquired, "throwing current owner").applied(),
            "throwing-current test did not establish its old lease");

        target.throwCurrentChecks = 1;
        BBSUiInputDispatcher.clear(owner);
        CompletableFuture<BBSUiInputResult> first = BBSUiInputDispatcher.submit(
            replacement,
            reliableBatch(SESSION, 1L, GLFW.GLFW_KEY_A)
        );

        executor.runAll();

        check(completed(first, "input after throwing current check").applied(),
            "throwing current check stopped the queued replacement batch");
        check(target.actions.equals(List.of(keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE))),
            "throwing current check dispatched stale synthetic releases or lost the replacement event");

        CompletableFuture<BBSUiInputResult> second = BBSUiInputDispatcher.submit(
            replacement,
            reliableBatch(SESSION, 2L, GLFW.GLFW_KEY_B)
        );
        check(executor.queuedCount() == 1,
            "throwing current check left drainScheduled stuck after the mailbox drained");
        executor.runAll();
        check(completed(second, "rescheduled input after throwing current check").applied(),
            "mailbox did not reschedule after a throwing current check");
    }

    private static void assertDetachClearsTargetAfterSyntheticReleaseError()
    {
        RecordingTarget target = new RecordingTarget();
        BBSUiInputDispatcher.installForTesting(target, SESSION, Runnable::run);
        BBSAddonDescriptor owner = descriptor("detach-release-error-owner");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );

        check(completed(
            BBSUiInputDispatcher.submit(owner, stateBatch(SESSION, 1L, held)),
            "detach release error owner"
        ).applied(), "detach release error test did not establish its lease");

        target.throwErrorAfterActions = 1;
        BBSUiInputDispatcher.detach(null, SESSION);

        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE)
        )), "synthetic release Error prevented the remaining held inputs from releasing");
        check(!BBSUiRemoteHeldState.isActive(),
            "detach retained its remote lease after a synthetic release Error");
        check(completed(
            BBSUiInputDispatcher.submit(owner, stateBatch(SESSION, 2L, IDLE_STATE)),
            "input after detach release error"
        ).status() == BBSUiInputStatus.NO_SCREEN,
            "detach retained the old input target after a synthetic release Error");
    }

    private static void assertDetachPreservesReentrantReplacement()
    {
        RecordingTarget original = new RecordingTarget();
        RecordingTarget replacement = new RecordingTarget();
        BBSUiInputDispatcher.installForTesting(original, SESSION, Runnable::run);
        BBSAddonDescriptor owner = descriptor("detach-reentrant-owner");
        BBSAddonDescriptor replacementOwner = descriptor("detach-reentrant-replacement");
        BBSUiRemoteInputState held = state(
            17D,
            29D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );

        check(completed(
            BBSUiInputDispatcher.submit(owner, stateBatch(SESSION, 1L, held)),
            "detach reentrant owner"
        ).applied(), "detach reentrant test did not establish its old lease");

        original.afterFirstAction = () ->
            BBSUiInputDispatcher.installForTesting(replacement, SESSION + 1L, Runnable::run);
        BBSUiInputDispatcher.detach(null, SESSION);

        check(original.actions.equals(List.of(mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT))),
            "detached target received releases after a replacement was installed");
        check(completed(
            BBSUiInputDispatcher.submit(
                replacementOwner,
                reliableBatch(SESSION + 1L, 1L, GLFW.GLFW_KEY_A)
            ),
            "reentrant replacement input"
        ).applied(), "detach cleared the replacement target installed by its release callback");
        check(replacement.actions.equals(List.of(keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE))),
            "reentrant replacement target did not receive its first input");
    }

    private static void assertBatchStopsWhenTargetChanges()
    {
        RecordingTarget target = new RecordingTarget();
        target.invalidateAfterActions = 1;
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("mid-batch-screen-change");
        BBSUiInputBatch batch = new BBSUiInputBatch(
            SESSION,
            1L,
            IDLE_STATE,
            List.of(
                new BBSUiKeyEvent(GLFW.GLFW_KEY_A, 0, BBSUiInputAction.PRESS, 0),
                new BBSUiKeyEvent(GLFW.GLFW_KEY_B, 0, BBSUiInputAction.PRESS, 0),
                new BBSUiKeyEvent(GLFW.GLFW_KEY_C, 0, BBSUiInputAction.PRESS, 0)
            )
        );
        CompletableFuture<BBSUiInputResult> future = BBSUiInputDispatcher.submit(addon, batch);

        executor.runAll();

        BBSUiInputResult result = completed(future, "mid-batch screen change");
        check(result.status() == BBSUiInputStatus.STALE_SESSION,
            "batch whose target changed was not reported as a stale session");
        check(target.actions.equals(List.of(keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS))),
            "old target received events after the first event invalidated its screen");
        check(!BBSUiRemoteHeldState.isActive(),
            "mid-batch screen change retained the remote held-state lease");
    }

    private static void assertRemoteLeaseEndsLocalGestureFirst()
    {
        RecordingTarget target = new RecordingTarget();
        target.localGestureHeld = true;
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("local-to-remote-transfer");
        CompletableFuture<BBSUiInputResult> future = BBSUiInputDispatcher.submit(
            addon,
            eventBatch(
                SESSION,
                1L,
                IDLE_STATE,
                new BBSUiKeyEvent(GLFW.GLFW_KEY_A, 0, BBSUiInputAction.PRESS, 0)
            )
        );

        executor.runAll();

        check(completed(future, "local-to-remote transfer").applied(),
            "remote lease did not apply after releasing the local gesture");
        check(target.actions.equals(List.of(
            "local-gesture-release",
            keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS)
        )), "remote event ran before the prior local gesture released ownership");
    }

    private static void assertRemoteLeaseExcludesLocalHeldState()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("exclusive-held-state");
        BBSUiRemoteInputState remote = state(
            7D,
            11D,
            1 << GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            Set.of(GLFW.GLFW_KEY_A),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        CompletableFuture<BBSUiInputResult> applied = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 1L, remote)
        );

        executor.runAll();
        check(completed(applied, "exclusive remote state").applied(),
            "remote state did not acquire its input lease");

        AtomicBoolean localPolled = new AtomicBoolean();
        check(!BBSUiRemoteHeldState.resolveMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT, () ->
        {
            localPolled.set(true);
            return true;
        }), "physical left button leaked into an idle remote left-button state");
        check(!localPolled.get(), "local mouse supplier was polled during a remote lease");
        check(BBSUiRemoteHeldState.resolveMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT, () -> false),
            "remote right button was not authoritative during its lease");
        check(BBSUiRemoteHeldState.resolveKeyPressed(GLFW.GLFW_KEY_A, () -> false),
            "remote held key was not authoritative during its lease");
        check(!BBSUiRemoteHeldState.resolveKeyPressed(GLFW.GLFW_KEY_B, () -> true),
            "physical key leaked into an idle remote key state");
        check(BBSUiRemoteHeldState.resolveModifierPressed(BBSUiRemoteInputState.MOD_SHIFT, () -> false),
            "remote modifier was not authoritative during its lease");
        check(!BBSUiRemoteHeldState.resolveModifierPressed(BBSUiRemoteInputState.MOD_CONTROL, () -> true),
            "physical modifier leaked into an idle remote modifier state");

        BBSUiInputDispatcher.clear(addon);
        executor.runAll();

        localPolled.set(false);
        check(BBSUiRemoteHeldState.resolveKeyPressed(GLFW.GLFW_KEY_B, () ->
        {
            localPolled.set(true);
            return true;
        }), "local held state was not restored after remote clear");
        check(localPolled.get(), "local supplier stayed suppressed after remote clear");
    }

    private static void assertResetSynthesizesHeldReleases()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("disconnect-release");
        BBSUiRemoteInputState held = state(
            23D,
            31D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_CONTROL),
            BBSUiRemoteInputState.MOD_CONTROL
        );
        CompletableFuture<BBSUiInputResult> applied = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 1L, held)
        );

        executor.runAll();
        check(completed(applied, "disconnect held state").applied(),
            "disconnect held state did not apply");

        BBSUiInputDispatcher.reset();

        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_RELEASE)
        )), "disconnect reset did not release its held mouse and key exactly once");
        check(!BBSUiRemoteHeldState.isActive(),
            "disconnect reset retained the remote held-state lease");
    }

    private static void assertMouseGestureButtonOwnership()
    {
        MouseGestureOwnership ownership = new MouseGestureOwnership();

        check(ownership.acquire(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "gesture did not acquire its initiating button");
        check(!ownership.release(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && ownership.isActive(),
            "unrelated button release terminated a gesture");
        check(ownership.release(GLFW.GLFW_MOUSE_BUTTON_LEFT) && !ownership.isActive(),
            "initiating button release did not terminate a gesture");
        check(ownership.acquire(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "released gesture could not be acquired again");
        ownership.cancel();
        check(!ownership.isActive(), "explicit cancellation retained gesture ownership");

        /* Pending Gizmo pick: left starts ownership, a right press becomes the
         * current UIContext button, promotion keeps the captured left owner,
         * and only the eventual left release may finish the trackball. */
        MouseGestureOwnership pendingGizmo = new MouseGestureOwnership();
        int initiatingButton = GLFW.GLFW_MOUSE_BUTTON_LEFT;
        int overwrittenContextButton = GLFW.GLFW_MOUSE_BUTTON_RIGHT;

        check(pendingGizmo.acquire(initiatingButton),
            "pending Gizmo pick did not acquire its initiating left button");
        check(!pendingGizmo.isOwnedBy(overwrittenContextButton) && pendingGizmo.isOwnedBy(initiatingButton),
            "right press stole pending Gizmo ownership before promotion");
        int promotedOwner = initiatingButton;
        check(pendingGizmo.isOwnedBy(promotedOwner),
            "pending Gizmo promotion did not use the captured initiating button");
        check(!pendingGizmo.release(overwrittenContextButton) && pendingGizmo.isActive(),
            "right release ended the promoted left-owned Gizmo");
        check(pendingGizmo.release(promotedOwner) && !pendingGizmo.isActive(),
            "left release did not end its promoted Gizmo exactly once");
        check(!pendingGizmo.release(promotedOwner),
            "repeated left release ended a promoted Gizmo more than once");

        AtomicInteger dragEnds = new AtomicInteger();
        UIDraggable draggable = new UIDraggable((context) -> {}).dragEnd(dragEnds::incrementAndGet);
        UIContext context = new UIContext(null);

        draggable.area.x = 0;
        draggable.area.y = 0;
        draggable.area.w = 20;
        draggable.area.h = 20;
        context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(draggable.mouseClicked(context) == draggable && draggable.isDragging(),
            "UIDraggable did not acquire its left-button press");

        context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check(draggable.mouseReleased(context) == null && draggable.isDragging(),
            "UIDraggable consumed or ended an unrelated button release");
        check(dragEnds.get() == 0, "UIDraggable fired drag-end for an unrelated button");

        context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(draggable.mouseReleased(context) == draggable && !draggable.isDragging(),
            "UIDraggable did not consume its owning button release");
        check(dragEnds.get() == 1, "UIDraggable did not fire exactly one matching drag-end");

        Area scrollArea = new Area(0, 0, 20, 20);
        Scroll scroll = new HeadlessScroll(scrollArea, 10);

        scroll.setSize(10);
        context.setMouse(19, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(scroll.mouseClicked(context) && scroll.dragging,
            "Scroll did not acquire its initiating left-button owner");
        context.setMouse(19, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check(!scroll.tryMouseReleased(context) && scroll.dragging,
            "Scroll ended or consumed an unrelated right-button release");
        context.setMouse(19, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(scroll.tryMouseReleased(context) && !scroll.dragging,
            "Scroll did not consume its matching left-button release");
    }

    private static void assertTrackpadButtonInterleaving()
    {
        long leaseId = BBSUiRemoteHeldState.install(SESSION, IDLE_STATE);
        Runnable restoreMinecraft = () -> {};

        try
        {
            restoreMinecraft = installHeadlessMinecraftInstance();
            UIContext context = new UIContext(null);
            UITrackpad trackpad = new UITrackpad();
            AtomicInteger trackpadDragEnds = new AtomicInteger();

            trackpad.getEvents().register(UITrackpadDragEndEvent.class, (event) ->
                trackpadDragEnds.incrementAndGet()
            );

            trackpad.area.set(0, 0, 100, 20);
            trackpad.setValue(5D);

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(trackpad.mouseClicked(context) == trackpad && trackpad.isDragging(),
                "UITrackpad did not begin its left-owned drag");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            trackpad.mouseClicked(context);
            check(trackpad.isDragging(),
                "UITrackpad right press cancelled a left-owned drag");
            check(trackpad.mouseReleased(context) == null && trackpad.isDragging(),
                "UITrackpad right release consumed or ended a left-owned drag");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            trackpad.mouseCanceled(context);
            check(!trackpad.isDragging(),
                "UITrackpad lifecycle cancellation retained its drag");
            check(trackpadDragEnds.get() == 0,
                "UITrackpad lifecycle cancellation emitted a normal drag-end commit");
            context.setMouseWheel(200, 200, 1D, 0D);
            check(trackpad.mouseScrolled(context) == null,
                "UITrackpad retained global wheel capture after lifecycle cancellation");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(trackpad.mouseClicked(context) == trackpad && trackpad.isDragging(),
                "UITrackpad could not reacquire after lifecycle cancellation");

            trackpad.setValue(8D);
            context.setKeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, GLFW.GLFW_PRESS);
            check(trackpad.keyPressed(context) == trackpad && !trackpad.isDragging(),
                "UITrackpad explicit Escape cancellation did not end its drag");
            check(trackpad.getValue() == 5D,
                "UITrackpad explicit cancellation did not restore its starting value");
            check(trackpadDragEnds.get() == 0,
                "UITrackpad Escape cancellation emitted a normal drag-end commit");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(trackpad.mouseClicked(context) == trackpad && trackpad.isDragging(),
                "UITrackpad could not reacquire after explicit cancellation");
            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(trackpad.mouseReleased(context) == null && trackpad.isDragging(),
                "UITrackpad unrelated release ended its reacquired drag");
            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(trackpad.mouseReleased(context) == trackpad && !trackpad.isDragging(),
                "UITrackpad owning left release did not finish its drag");
            check(trackpadDragEnds.get() == 1,
                "UITrackpad physical release did not emit exactly one drag-end commit");

            UISliderTrackpad slider = new UISliderTrackpad();

            slider.area.set(0, 0, 100, 20);
            slider.limit(0D, 10D);
            slider.setValue(5D);

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(slider.mouseClicked(context) == slider && slider.isDragging(),
                "UISliderTrackpad did not begin its left-owned drag");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            slider.mouseClicked(context);
            check(slider.isDragging(),
                "UISliderTrackpad right press cancelled a left-owned drag");
            check(slider.mouseReleased(context) == null && slider.isDragging(),
                "UISliderTrackpad right release consumed or ended a left-owned drag");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            slider.mouseCanceled(context);
            check(!slider.isDragging(),
                "UISliderTrackpad lifecycle cancellation retained its drag");
            context.setMouseWheel(200, 200, 1D, 0D);
            check(slider.mouseScrolled(context) == null,
                "UISliderTrackpad retained global wheel capture after lifecycle cancellation");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(slider.mouseClicked(context) == slider && slider.isDragging(),
                "UISliderTrackpad could not reacquire after lifecycle cancellation");

            slider.setValue(8D);
            context.setKeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, GLFW.GLFW_PRESS);
            check(slider.keyPressed(context) == slider && !slider.isDragging(),
                "UISliderTrackpad explicit Escape cancellation did not end its drag");
            check(slider.getValue() == 5D,
                "UISliderTrackpad explicit cancellation did not restore its starting value");

            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(slider.mouseClicked(context) == slider && slider.isDragging(),
                "UISliderTrackpad could not reacquire after explicit cancellation");
            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(slider.mouseReleased(context) == null && slider.isDragging(),
                "UISliderTrackpad unrelated release ended its reacquired drag");
            context.setMouse(50, 10, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(slider.mouseReleased(context) == slider && !slider.isDragging(),
                "UISliderTrackpad owning left release did not finish its drag");
        }
        finally
        {
            restoreMinecraft.run();
            BBSUiRemoteHeldState.clear(leaseId);
        }

        check(!BBSUiRemoteHeldState.isActive(),
            "Trackpad behavior test did not conditionally clear its headless remote lease");
    }

    private static Runnable installHeadlessMinecraftInstance()
    {
        try
        {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Field instance = minecraftClass.getDeclaredField("instance");

            instance.setAccessible(true);

            Object previous = instance.get(null);

            if (previous != null)
            {
                return () -> {};
            }

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");

            theUnsafe.setAccessible(true);

            Object unsafe = theUnsafe.get(null);
            Object headless = unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, minecraftClass);
            Field font = minecraftClass.getField("font");
            long fontOffset = (long) unsafeClass.getMethod("objectFieldOffset", Field.class)
                .invoke(unsafe, font);

            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, headless, fontOffset, new HeadlessFont());

            instance.set(null, headless);

            return () ->
            {
                try
                {
                    instance.set(null, previous);
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the Minecraft test singleton", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the headless Minecraft test singleton", exception);
        }
    }

    private static final class HeadlessFont extends Font
    {
        private HeadlessFont()
        {
            super((location) -> null, false);
        }

        @Override
        public int width(String text)
        {
            return text == null ? 0 : text.length();
        }
    }

    private static void assertFrameworkMouseCaptureAcrossBlockingOverlay()
    {
        long leaseId = BBSUiRemoteHeldState.install(SESSION, IDLE_STATE);

        try
        {
            assertDeferredBlockingOverlayCapture();
            assertNestedWheelDispatchRetainsOuterOwnerContext();
            assertReleaseStaysWithPressOwnerAcrossInterception();
            assertParentOwnerBypassesChildReleaseInterception();
            assertCapturedReleaseIgnoresVisibilityAndEnabledState();
            assertScrollViewScrollbarReleaseAndCancellation();
            assertThrowingCapturedReleaseCancelsOwner();
            assertMouseBarrierAdmissionFence();
            assertNormalReleaseReentrantCapture();
            assertLifecycleInvalidationRejectsReentrantCapture();
            assertLifecycleInvalidationReleasesAllCapturedButtons();
            assertFailedMultiButtonReleaseContinues();
            assertPreservedSiblingHierarchyCapture();
            assertOrderedHierarchyMutationCapture();
            assertFailedMouseDispatchDropsQueuedMutation();
            assertCanvasCaptureLifecycle();
            assertGizmoCaptureLifecycle();
            assertContextMenuIntentOrdering();
            assertContextMenuLifecycleCleanup();
        }
        finally
        {
            BBSUiRemoteHeldState.clear(leaseId);
        }

        check(!BBSUiRemoteHeldState.isActive(),
            "framework capture test did not conditionally clear its headless remote lease");
    }

    private static void assertDeferredBlockingOverlayCapture()
    {
        TestMenu menu = new TestMenu();
        HeadlessScrollView scroll = new HeadlessScrollView(20);
        RecordingDraggable draggable = new RecordingDraggable();
        UIElement blocker = new UIElement();
        AtomicBoolean mountedAfterRelease = new AtomicBoolean();

        scroll.area.set(0, 0, 100, 100);
        scroll.xy(0, 0).wh(100, 100);
        draggable.area.set(0, 20, 20, 20);
        draggable.xy(0, 20).wh(20, 20);
        blocker.area.set(0, 0, 100, 100);
        blocker.xy(0, 0).wh(100, 100);
        blocker.eventPropagataion(EventPropagation.BLOCK);
        draggable.onPress = () -> menu.runAfterCapturedMouseRelease(() ->
        {
            mountedAfterRelease.set(!draggable.isDragging() && draggable.cancels == 1);
            menu.overlay.add(blocker);
            menu.getRoot().moveToFront(menu.overlay);
        });
        scroll.add(draggable);
        menu.main.add(scroll);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "scrolled child did not consume its initiating press");
        check(draggable.presses == 1 && draggable.cancels == 1
                && draggable.releases == 0 && !draggable.isDragging(),
            "modal barrier did not cancel exactly one initiating generation");
        check(mountedAfterRelease.get() && blocker.hasParent(),
            "blocking overlay mounted before its old owner release completed");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && draggable.releases == 0 && draggable.cancels == 1,
            "release without a capture token reached a stale owner behind the overlay");

        blocker.removeFromParent();
        draggable.onPress = null;
        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "owner could not reacquire after the blocking overlay closed");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && !draggable.isDragging(),
            "reacquired owner did not finish through the normal ancestor chain");
    }

    private static void assertReleaseStaysWithPressOwnerAcrossInterception()
    {
        TestMenu menu = new TestMenu();
        HeadlessScrollView scroll = new HeadlessScrollView(20);
        RecordingDraggable draggable = new RecordingDraggable();
        AtomicInteger interceptorReleaseVisits = new AtomicInteger();
        UIElement interceptor = new UIElement()
        {
            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                interceptorReleaseVisits.incrementAndGet();

                return true;
            }
        };

        scroll.area.set(0, 0, 100, 100);
        scroll.xy(0, 0).wh(100, 100);
        draggable.area.set(0, 20, 20, 20);
        draggable.xy(0, 20).wh(20, 20);
        interceptor.area.set(0, 0, 100, 100);
        interceptor.xy(0, 0).wh(100, 100);
        scroll.add(draggable);
        menu.main.add(scroll);
        menu.overlay.add(interceptor);
        menu.getRoot().moveToFront(menu.overlay);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "release-interception regression did not acquire the initiating drag");

        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !draggable.isDragging()
                && draggable.releases == 1
                && draggable.releaseVisits == 1
                && draggable.releaseY == 25
                && interceptorReleaseVisits.get() == 0,
            "release skipped the press owner's captured ancestor path");
    }

    private static void assertParentOwnerBypassesChildReleaseInterception()
    {
        TestMenu menu = new TestMenu();
        AtomicInteger parentReleases = new AtomicInteger();
        AtomicInteger childReleases = new AtomicInteger();
        UIElement parent = new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }

            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                parentReleases.incrementAndGet();

                return true;
            }
        };
        UIElement child = new UIElement()
        {
            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                childReleases.incrementAndGet();

                return true;
            }
        };

        parent.area.set(0, 0, 100, 100);
        child.area.set(0, 0, 100, 100);
        parent.add(child);
        menu.main.add(parent);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "parent-owner regression did not acquire its press");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && parentReleases.get() == 1 && childReleases.get() == 0,
            "captured parent release was intercepted by one of its children");
    }

    private static void assertNestedWheelDispatchRetainsOuterOwnerContext()
    {
        TestMenu menu = new TestMenu();
        AtomicInteger visits = new AtomicInteger();
        AtomicBoolean outerContextRestored = new AtomicBoolean();
        UIElement wheelOwner = new UIElement()
        {
            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                int visit = visits.incrementAndGet();

                if (visit == 1)
                {
                    check(context.mouseX == 5 && context.mouseY == 6
                            && context.mouseWheelHorizontal == -0.5D && context.mouseWheel == 1.5D,
                        "outer wheel owner received the wrong coordinates or axes");

                    check(menu.mouseScrolled(15, 16, 2.5D, -3.5D),
                        "nested wheel owner did not consume its event");
                    outerContextRestored.set(context.mouseX == 5 && context.mouseY == 6
                        && context.mouseWheelHorizontal == -0.5D && context.mouseWheel == 1.5D);
                }
                else
                {
                    check(visit == 2 && context.mouseX == 15 && context.mouseY == 16
                            && context.mouseWheelHorizontal == 2.5D && context.mouseWheel == -3.5D,
                        "nested wheel owner inherited the outer event state");
                }

                return true;
            }
        };

        wheelOwner.area.set(0, 0, 100, 100);
        menu.main.add(wheelOwner);

        check(menu.mouseScrolled(5, 6, -0.5D, 1.5D)
                && visits.get() == 2 && outerContextRestored.get(),
            "nested wheel dispatch did not preserve one event owner/context per generation");
    }

    private static void assertCapturedReleaseIgnoresVisibilityAndEnabledState()
    {
        TestMenu menu = new TestMenu();
        UIElement parent = new UIElement();
        RecordingDraggable draggable = new RecordingDraggable();

        parent.area.set(0, 0, 100, 100);
        draggable.area.set(0, 0, 20, 20);
        parent.add(draggable);
        menu.main.add(parent);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "visibility capture regression did not acquire the initiating drag");
        draggable.setVisible(false);
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !draggable.isDragging() && draggable.releases == 1,
            "captured owner did not receive release after becoming invisible");

        draggable.setVisible(true);
        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "disabled capture regression did not reacquire the initiating drag");
        parent.setEnabled(false);
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !draggable.isDragging() && draggable.releases == 2,
            "captured owner did not receive release after an ancestor was disabled");
    }

    private static void assertScrollViewScrollbarReleaseAndCancellation()
    {
        TestMenu menu = new TestMenu();
        HeadlessScrollView scrollView = new HeadlessScrollView(0);

        scrollView.area.set(0, 0, 100, 100);
        scrollView.xy(0, 0).wh(100, 100);
        scrollView.scroll = new HeadlessScroll(scrollView.area, 10);
        scrollView.scroll.setSize(20);
        menu.main.add(scrollView);

        check(menu.mouseClicked(99, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && scrollView.scroll.dragging,
            "scrollbar press did not establish a captured scroll drag");
        check(menu.mouseReleased(99, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !scrollView.scroll.dragging,
            "captured scrollbar release did not clear scroll dragging");

        check(menu.mouseClicked(99, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && scrollView.scroll.dragging,
            "scrollbar could not reacquire after release");
        check(menu.mouseCanceled(99, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !scrollView.scroll.dragging,
            "scrollbar cancellation did not clear scroll dragging");
    }

    private static void assertThrowingCapturedReleaseCancelsOwner()
    {
        TestMenu menu = new TestMenu();
        AtomicBoolean dragging = new AtomicBoolean();
        AtomicBoolean throwRelease = new AtomicBoolean(true);
        AtomicInteger cancels = new AtomicInteger();
        MouseGestureOwnership ownership = new MouseGestureOwnership();
        long[] generation = {0L};
        UIElement owner = new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (!this.area.isInside(context))
                {
                    return false;
                }

                generation[0] = ownership.acquireToken(context.mouseButton);
                dragging.set(generation[0] != 0L);

                return dragging.get();
            }

            @Override
            protected boolean subMouseReleased(UIContext context)
            {
                if (!ownership.isOwnedBy(context.mouseButton, generation[0]))
                {
                    return false;
                }

                if (throwRelease.getAndSet(false))
                {
                    throw new IllegalStateException("expected captured release failure");
                }

                ownership.release(context.mouseButton, generation[0]);
                generation[0] = 0L;
                dragging.set(false);

                return true;
            }

            @Override
            protected void subMouseCanceled(UIContext context)
            {
                if (ownership.release(context.mouseButton, generation[0]))
                {
                    generation[0] = 0L;
                    dragging.set(false);
                    cancels.incrementAndGet();
                }
            }
        };

        owner.area.set(0, 0, 20, 20);
        menu.main.add(owner);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && dragging.get(),
            "throwing-release test did not acquire its press owner");

        try
        {
            menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            throw new AssertionError("throwing captured release did not propagate its failure");
        }
        catch (IllegalStateException exception)
        {
            check("expected captured release failure".equals(exception.getMessage()),
                "throwing captured release propagated the wrong failure");
        }

        check(!dragging.get() && cancels.get() == 1 && !ownership.isActive(),
            "throwing captured release left its old owner active");
        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !dragging.get(),
            "owner could not reacquire after throwing release cleanup");
    }

    private static void assertNormalReleaseReentrantCapture()
    {
        TestMenu menu = new TestMenu();
        RecordingDraggable draggable = new RecordingDraggable();
        AtomicBoolean reenter = new AtomicBoolean(true);

        draggable.area.set(0, 0, 20, 20);
        draggable.dragEnd(() ->
        {
            if (reenter.getAndSet(false))
            {
                menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            }
        });
        menu.main.add(draggable);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "normal capture did not acquire its initiating press");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT) && draggable.isDragging(),
            "unrelated release escaped the global initiating-button token");
        check(draggable.releaseVisits == 0,
            "unrelated release was dispatched into the captured hierarchy");
        check(!menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT) && draggable.isDragging(),
            "secondary press did not receive its own global initiating-button token");
        check(!menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT) && draggable.isDragging(),
            "secondary token release ended the left-owned drag");
        check(draggable.releaseVisits == 1 && draggable.releases == 0,
            "secondary token was not dispatched exactly once through the hierarchy");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "ordinary owner release did not preserve its callback's new capture epoch");
        check(draggable.presses == 2 && draggable.releases == 1,
            "ordinary release callback did not reacquire exactly once");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && !draggable.isDragging(),
            "reentrant capture epoch did not finish on its own owner release");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && draggable.releaseVisits == 3,
            "release without a capture token was dispatched to an arbitrary UI element");
    }

    private static void assertLifecycleInvalidationRejectsReentrantCapture()
    {
        TestMenu menu = new TestMenu();
        RecordingDraggable draggable = new RecordingDraggable();
        AtomicBoolean invalidateOnce = new AtomicBoolean(true);
        AtomicBoolean nestedPressConsumed = new AtomicBoolean();

        draggable.area.set(0, 0, 20, 20);
        draggable.dragEnd(() ->
        {
            if (invalidateOnce.getAndSet(false))
            {
                menu.invalidateInputState();
                nestedPressConsumed.set(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT));
            }
        });
        menu.main.add(draggable);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && draggable.isDragging(),
            "lifecycle reentry test did not acquire its physical press");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && !draggable.isDragging(),
            "lifecycle invalidation did not finish the physical owner release");
        check(nestedPressConsumed.get() && draggable.presses == 1 && draggable.releases == 1,
            "detached release callback installed a new capture in the invalidated lifecycle");

        menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(draggable.releaseVisits == 1,
            "invalidated reentrant press left a capture for a later physical release");
        boolean reacquired = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean released = menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);

        check(reacquired && released
                && draggable.presses == 2 && draggable.releases == 2,
            "menu did not reacquire after the invalidated dispatch fully unwound");
    }

    private static void assertMouseBarrierAdmissionFence()
    {
        TestMenu menu = new TestMenu();
        RecordingButtonsElement target = new RecordingButtonsElement();
        AtomicBoolean nestedPressAccepted = new AtomicBoolean();
        AtomicBoolean mutationRanAfterRelease = new AtomicBoolean();

        target.area.set(0, 0, 20, 20);
        target.onPress = (button) ->
        {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
            {
                menu.runAfterCapturedMouseRelease(() -> mutationRanAfterRelease.set(
                    target.cancels(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                ));
                nestedPressAccepted.set(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT));
            }
        };
        menu.main.add(target);

        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "barrier-fence test did not consume its initiating press");
        check(nestedPressAccepted.get(),
            "barrier admission fence did not consume the nested physical press");
        check(target.presses(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.presses(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0,
            "barrier admission fence dispatched a post-snapshot press into the hierarchy");
        check(target.cancels(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.cancels(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 0
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0
                && mutationRanAfterRelease.get(),
            "barrier mutation did not run after canceling exactly its captured snapshot");

        menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check(target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0,
            "release for a fenced press reached an uncaptured hierarchy owner");
        boolean reopenedPress = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean reopenedRelease = menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        check(reopenedPress && reopenedRelease
                && target.presses(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1,
            "barrier admission fence did not reopen after its mutation completed");
    }

    private static void assertLifecycleInvalidationReleasesAllCapturedButtons()
    {
        TestMenu menu = new TestMenu();
        RecordingButtonsElement target = new RecordingButtonsElement();
        UIElement staleChild = new UIElement();
        AtomicBoolean invalidateOnFirstCancel = new AtomicBoolean(true);
        AtomicBoolean staleMutationRan = new AtomicBoolean();
        AtomicInteger staleResizeVisits = new AtomicInteger();
        UIElement resizeProbe = new UIElement()
        {
            @Override
            public void resize()
            {
                staleResizeVisits.incrementAndGet();
                super.resize();
            }
        };

        target.area.set(0, 0, 20, 20);
        target.onCancel = (button) ->
        {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && invalidateOnFirstCancel.getAndSet(false))
            {
                menu.invalidateInputState();
                menu.main.add(staleChild);
            }
        };
        menu.main.add(target, resizeProbe);

        boolean leftPressed = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightPressed = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        check(leftPressed && rightPressed,
            "lifecycle invalidation test did not acquire both mouse buttons");
        menu.runAfterCapturedMouseRelease(() -> staleMutationRan.set(true));

        check(target.cancels(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.cancels(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 0
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0,
            "lifecycle invalidation during the first cancellation skipped another captured button");
        check(!staleMutationRan.get(),
            "old lifecycle mutation ran after a release callback invalidated its screen");
        check(!staleChild.hasParent() && staleResizeVisits.get() == 0,
            "stale hierarchy intent mutated or resized the invalidated menu");

        menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check(target.cancels(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.cancels(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 0
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0,
            "physical releases reached captures retired by lifecycle invalidation");

        boolean reacquiredLeft = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean reacquiredRight = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean releasedRight = menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean releasedLeft = menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);

        check(reacquiredLeft && reacquiredRight && releasedRight && releasedLeft,
            "menu did not reacquire both buttons after lifecycle invalidation");
        check(target.presses(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 2
                && target.presses(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 2
                && target.cancels(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.cancels(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1,
            "reacquired multi-button gesture did not release each owner exactly once");
    }

    private static void assertFailedMultiButtonReleaseContinues()
    {
        TestMenu menu = new TestMenu();
        RecordingButtonsElement target = new RecordingButtonsElement();
        AtomicBoolean throwOnLeftCancel = new AtomicBoolean(true);
        AtomicBoolean staleMutationRan = new AtomicBoolean();

        target.area.set(0, 0, 20, 20);
        target.onCancel = (button) ->
        {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && throwOnLeftCancel.getAndSet(false))
            {
                throw new IllegalStateException("first button cancellation failure");
            }
        };
        menu.main.add(target);

        boolean leftPressed = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean rightPressed = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        check(leftPressed && rightPressed,
            "throwing multi-button test did not acquire both buttons");

        try
        {
            menu.runAfterCapturedMouseRelease(() -> staleMutationRan.set(true));
            throw new AssertionError("throwing first button cancellation did not propagate");
        }
        catch (IllegalStateException exception)
        {
            check("first button cancellation failure".equals(exception.getMessage()),
                "multi-button cancellation propagated the wrong failure");
        }

        check(target.cancels(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.cancels(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_LEFT) == 0
                && target.releases(GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 0,
            "first cancellation failure prevented another captured button from canceling");
        check(!staleMutationRan.get(),
            "barrier mutation ran after a captured-button release failed");

        target.onCancel = null;
        boolean recoveryLeftPressed = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean recoveryRightPressed = menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean recoveryLeftReleased = menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean recoveryRightReleased = menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        check(recoveryLeftPressed && recoveryRightPressed
                && recoveryLeftReleased && recoveryRightReleased,
            "menu did not recover after a multi-button release failure");
    }

    private static void assertOrderedHierarchyMutationCapture()
    {
        TestMenu replaceMenu = new TestMenu();
        UIElement container = new UIElement();
        RecordingDraggable oldChild = new RecordingDraggable();
        UIElement replacement = new UIElement();

        oldChild.area.set(0, 0, 20, 20);
        replacement.area.set(0, 0, 20, 20);
        container.area.set(0, 0, 50, 40);
        container.xy(0, 0).wh(50, 40);
        replacement.full(container);
        container.add(oldChild);
        replaceMenu.main.add(container);
        oldChild.onPress = () ->
        {
            container.removeAll();
            container.add(replacement);
        };

        check(replaceMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "removeAll replacement press was not consumed");
        check(oldChild.cancels == 1 && oldChild.releases == 0 && oldChild.getParent() == null,
            "removeAll replacement detached its owner before cancellation");
        check(container.getChildren().size() == 1 && container.getChildren().get(0) == replacement
                && replacement.getParent() == container,
            "removeAll followed by add erased or orphaned the replacement child");
        check(replacement.area.w == 50 && replacement.area.h == 40,
            "deferred hierarchy replacement did not relayout its new child after the barrier");

        TestMenu reparentMenu = new TestMenu();
        UIElement oldParent = new UIElement();
        UIElement newParent = new UIElement();
        RecordingDraggable moved = new RecordingDraggable();

        moved.area.set(0, 0, 20, 20);
        oldParent.add(moved);
        reparentMenu.main.add(oldParent, newParent);
        moved.onPress = () ->
        {
            IUIElement typedAsInterface = moved;

            oldParent.remove(typedAsInterface);
            newParent.add(moved);
        };

        check(reparentMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "typed hierarchy replacement press was not consumed");
        check(moved.cancels == 1 && moved.releases == 0 && moved.getParent() == newParent,
            "ordered remove/readd left the moved child with a ghost parent");
        check(!oldParent.getChildren().contains(moved) && newParent.getChildren().contains(moved),
            "ordered remove/readd did not move the child exactly once");
    }

    private static void assertPreservedSiblingHierarchyCapture()
    {
        TestMenu releaseMenu = new TestMenu();
        UIElement releaseContainer = new UIElement();
        UIElement oldSibling = new UIElement();
        UIElement newSibling = new UIElement();
        RecordingDraggable releaseOwner = new RecordingDraggable();
        AtomicInteger releaseCommits = new AtomicInteger();

        releaseContainer.area.set(0, 0, 100, 100);
        oldSibling.area.set(30, 0, 20, 20);
        newSibling.area.set(30, 0, 20, 20);
        releaseOwner.area.set(0, 0, 20, 20);
        releaseOwner.dragEnd(releaseCommits::incrementAndGet);
        releaseContainer.add(oldSibling, releaseOwner);
        releaseMenu.main.add(releaseContainer);
        releaseOwner.onPress = () -> releaseMenu.runWithPreservedMouseCapture(releaseOwner, () ->
        {
            oldSibling.removeFromParent();
            releaseContainer.add(newSibling);
        });

        check(releaseMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "preserved sibling-refresh press was not consumed");
        check(releaseOwner.isDragging() && releaseOwner.cancels == 0
                && oldSibling.getParent() == null && newSibling.getParent() == releaseContainer,
            "sibling hierarchy refresh canceled or detached its preserved press owner");
        check(releaseCommits.get() == 0,
            "sibling hierarchy refresh committed before the physical release");
        check(releaseMenu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !releaseOwner.isDragging()
                && releaseOwner.releases == 1
                && releaseOwner.cancels == 0
                && releaseCommits.get() == 1,
            "preserved press owner did not commit exactly once on physical release");

        TestMenu cancelMenu = new TestMenu();
        RecordingDraggable cancelOwner = new RecordingDraggable();
        AtomicInteger cancelCommits = new AtomicInteger();

        cancelOwner.area.set(0, 0, 20, 20);
        cancelOwner.dragEnd(cancelCommits::incrementAndGet);
        cancelMenu.main.add(cancelOwner);

        check(cancelMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "cancellation regression did not acquire its press owner");
        cancelMenu.invalidateInputState();
        check(!cancelOwner.isDragging()
                && cancelOwner.cancels == 1
                && cancelOwner.releases == 0
                && cancelCommits.get() == 0,
            "lifecycle cancellation committed a preserved gesture");

        TestMenu removalMenu = new TestMenu();
        RecordingDraggable removedOwner = new RecordingDraggable();
        AtomicInteger removalCommits = new AtomicInteger();

        removedOwner.area.set(0, 0, 20, 20);
        removedOwner.dragEnd(removalCommits::incrementAndGet);
        removalMenu.main.add(removedOwner);
        removedOwner.onPress = () -> removalMenu.runWithPreservedMouseCapture(
            removedOwner,
            removedOwner::removeFromParent
        );

        check(removalMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "preserved owner-removal press was not consumed");
        check(removedOwner.getParent() == null
                && !removedOwner.isDragging()
                && removedOwner.cancels == 1
                && removedOwner.releases == 0
                && removalCommits.get() == 0,
            "preserve scope retained or committed an owner that was actually removed");
    }

    private static void assertContextMenuIntentOrdering()
    {
        TestMenu setCloseMenu = new TestMenu();
        DummyContextMenu pending = new DummyContextMenu();
        UIElement setThenClose = new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                context.setContextMenu(pending);
                context.closeContextMenu();

                return true;
            }
        };

        setThenClose.area.set(0, 0, 20, 20);
        setCloseMenu.main.add(setThenClose);
        check(setCloseMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            "set-then-close context intent was not consumed");
        check(!pending.hasParent() && !setCloseMenu.context.hasContextMenu(),
            "close did not invalidate a pending context-menu open");

        TestMenu replaceCloseMenu = new TestMenu();
        DummyContextMenu original = new DummyContextMenu();
        DummyContextMenu replacement = new DummyContextMenu();

        replaceCloseMenu.context.setContextMenu(original);
        check(original.hasParent(), "context-menu replacement test did not mount its original menu");

        UIElement replaceThenClose = new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                context.replaceContextMenu(replacement);
                context.closeContextMenu();

                return true;
            }
        };

        replaceThenClose.area.set(0, 0, 20, 20);
        /* Keep the intent source above the mounted menu so this callback, not
         * the context menu's outside-click close, owns the replace/close order. */
        replaceCloseMenu.overlay.add(replaceThenClose);
        check(replaceCloseMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            "replace-then-close context intent was not consumed");
        check(!original.hasParent() && !replacement.hasParent()
                && !replaceCloseMenu.context.hasContextMenu(),
            "close did not win over a pending context-menu replacement");

        TestMenu replaceOnRightClick = new TestMenu();
        DummyContextMenu first = new DummyContextMenu();
        DummyContextMenu second = new DummyContextMenu();
        AtomicInteger firstContextRequests = new AtomicInteger();
        AtomicInteger secondContextRequests = new AtomicInteger();
        UIElement firstTarget = new UIElement().context(() ->
        {
            firstContextRequests.incrementAndGet();

            return first;
        });
        UIElement secondTarget = new UIElement().context(() ->
        {
            secondContextRequests.incrementAndGet();

            return second;
        });

        firstTarget.area.set(0, 0, 20, 20);
        secondTarget.area.set(30, 0, 20, 20);
        replaceOnRightClick.main.add(firstTarget, secondTarget);
        check(replaceOnRightClick.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            "first right-click context menu did not open");
        check(first.hasParent() && replaceOnRightClick.context.contextMenu == first,
            "first right-click context menu was not mounted");

        boolean insideMenuPress = replaceOnRightClick.mouseClicked(6, 6, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        boolean insideMenuRelease = replaceOnRightClick.mouseReleased(6, 6, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        check(insideMenuPress && insideMenuRelease,
            "mounted context menu did not consume its inside click lifecycle");
        check(firstContextRequests.get() == 1 && secondContextRequests.get() == 0
                && first.hasParent() && replaceOnRightClick.context.contextMenu == first,
            "mounted context menu leaked an inside click to a lower target");
        check(replaceOnRightClick.mouseClicked(35, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            "single right-click outside the old menu did not reach the new target");
        check(!first.hasParent() && second.hasParent()
                && replaceOnRightClick.context.contextMenu == second
                && secondContextRequests.get() == 1,
            "right-clicking a second target required an extra click or left the old menu mounted");
    }

    private static void assertContextMenuLifecycleCleanup()
    {
        TestMenu menu = new TestMenu();
        DummyContextMenu original = new DummyContextMenu();
        DummyContextMenu replacement = new DummyContextMenu();
        DummyContextMenu reopened = new DummyContextMenu();
        UIElement lifecycleTarget = new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                context.replaceContextMenu(replacement);
                menu.invalidateInputState();

                return true;
            }
        };

        menu.context.setContextMenu(original);
        check(original.hasParent(),
            "context lifecycle test did not mount its original menu");

        lifecycleTarget.area.set(0, 0, 20, 20);
        menu.overlay.add(lifecycleTarget);
        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
            "context lifecycle invalidation was not consumed");
        check(!original.hasParent() && !replacement.hasParent()
                && !menu.context.hasContextMenu(),
            "lifecycle invalidation retained a pending context-menu overlay");

        menu.context.setContextMenu(reopened);
        check(reopened.hasParent() && menu.context.contextMenu == reopened,
            "reopened singleton menu was blocked by stale context-menu cleanup state");
        menu.context.closeContextMenu();
        check(!reopened.hasParent() && !menu.context.hasContextMenu(),
            "reopened context menu did not close after lifecycle cleanup");
    }

    private static void assertCanvasCaptureLifecycle()
    {
        TestMenu menu = new TestMenu();
        UICanvas canvas = new UICanvas() {};

        canvas.area.set(0, 0, 20, 20);
        menu.main.add(canvas);
        check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT) && canvas.dragging,
            "menu did not capture UICanvas's initiating press");
        check(menu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT) && canvas.dragging,
            "unrelated release ended UICanvas's physical owner");
        menu.releaseCapturedMouseGestures();
        check(!canvas.dragging,
            "pre-overlay handoff left UICanvas dragging after its owner release");
    }

    private static void assertFailedMouseDispatchDropsQueuedMutation()
    {
        TestMenu releaseMenu = new TestMenu();
        RecordingDraggable draggable = new RecordingDraggable();
        AtomicBoolean throwOnce = new AtomicBoolean(true);
        AtomicInteger staleMutations = new AtomicInteger();

        draggable.area.set(0, 0, 20, 20);
        draggable.dragEnd(() ->
        {
            if (throwOnce.getAndSet(false))
            {
                releaseMenu.runAfterCapturedMouseRelease(staleMutations::incrementAndGet);

                throw new IllegalStateException("release failure");
            }
        });
        releaseMenu.main.add(draggable);
        check(releaseMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "throwing release test did not acquire its owner");

        try
        {
            releaseMenu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            throw new AssertionError("throwing release callback did not propagate");
        }
        catch (IllegalStateException exception)
        {
            check("release failure".equals(exception.getMessage()),
                "throwing release callback propagated the wrong failure");
        }

        check(staleMutations.get() == 0,
            "throwing release callback executed its queued mutation");
        boolean recoveryPressed = releaseMenu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean recoveryReleased = releaseMenu.mouseReleased(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);

        check(recoveryPressed && recoveryReleased,
            "gesture did not recover after a throwing release callback");
        check(staleMutations.get() == 0,
            "later gesture executed a stale mutation from a failed release");

        TestMenu scrollMenu = new TestMenu();
        AtomicInteger staleScrollMutations = new AtomicInteger();
        ThrowingScrollElement throwingScroll = new ThrowingScrollElement(
            scrollMenu,
            staleScrollMutations::incrementAndGet
        );
        RecordingDraggable scrollRecovery = new RecordingDraggable();

        scrollRecovery.area.set(40, 40, 20, 20);
        scrollMenu.main.add(throwingScroll, scrollRecovery);

        try
        {
            scrollMenu.mouseScrolled(5, 5, 0D, 1D);
            throw new AssertionError("throwing scroll callback did not propagate");
        }
        catch (IllegalStateException exception)
        {
            check("scroll failure".equals(exception.getMessage()),
                "throwing scroll callback propagated the wrong failure");
        }

        check(scrollMenu.mouseClicked(50, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && scrollRecovery.isDragging(),
            "post-scroll gesture did not reacquire its press owner");
        check(scrollMenu.mouseReleased(50, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && !scrollRecovery.isDragging(),
            "post-scroll gesture did not release its reacquired owner");
        check(staleScrollMutations.get() == 0,
            "later gesture executed a stale mutation from a failed scroll");
    }

    private static void assertGizmoCaptureLifecycle()
    {
        try
        {
            GizmoInteraction interaction = new GizmoInteraction(null);
            Field ownershipField = GizmoInteraction.class.getDeclaredField("gestureOwnership");
            Field activeField = GizmoInteraction.class.getDeclaredField("gizmoActive");
            Field generationField = GizmoInteraction.class.getDeclaredField("gestureGeneration");

            ownershipField.setAccessible(true);
            activeField.setAccessible(true);
            generationField.setAccessible(true);

            MouseGestureOwnership ownership = (MouseGestureOwnership) ownershipField.get(interaction);
            TestMenu menu = new TestMenu();
            UIElement target = new UIElement()
            {
                @Override
                protected boolean subMouseClicked(UIContext context)
                {
                    return true;
                }

                @Override
                protected boolean subMouseReleased(UIContext context)
                {
                    return interaction.mouseReleased(context);
                }

                @Override
                protected void subMouseCanceled(UIContext context)
                {
                    interaction.stop();
                }
            };

            target.area.set(0, 0, 20, 20);
            menu.main.add(target);
            long generation = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

            check(generation != 0L,
                "Gizmo test could not arm its left owner");
            generationField.setLong(interaction, generation);
            activeField.setBoolean(interaction, true);
            check(menu.mouseClicked(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT),
                "menu did not capture the Gizmo release target");
            menu.releaseCapturedMouseGestures();
            check(!ownership.isActive() && !activeField.getBoolean(interaction),
                "pre-overlay handoff left Gizmo local active/ownership state stale");
            check(ownership.acquire(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                "Gizmo could not reacquire after captured overlay release");
            ownership.cancel();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect Gizmo capture lifecycle", exception);
        }
    }

    private static void assertClearRunsBeforePendingInput()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor first = descriptor("clear-first");
        BBSAddonDescriptor next = descriptor("clear-next");
        BBSUiRemoteInputState held = state(
            3D,
            5D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_A),
            0
        );

        CompletableFuture<BBSUiInputResult> owner = BBSUiInputDispatcher.submit(first, stateBatch(SESSION, 1L, held));
        executor.runAll();
        check(completed(owner, "initial controller state").applied(), "initial controller was not established");

        CompletableFuture<BBSUiInputResult> pendingNext = BBSUiInputDispatcher.submit(
            next,
            eventBatch(
                SESSION,
                1L,
                IDLE_STATE,
                new BBSUiKeyEvent(GLFW.GLFW_KEY_B, 0, BBSUiInputAction.PRESS, 0)
            )
        );
        BBSUiInputDispatcher.clear(first);
        executor.runAll();

        check(completed(pendingNext, "input following clear").applied(),
            "input queued behind a clear did not apply");
        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE),
            keyAction(GLFW.GLFW_KEY_B, GLFW.GLFW_PRESS)
        )), "pending input ran before clear released the previous controller state");
    }

    private static void assertNonOwnerClearCancelsQueuedGeneration()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor owner = descriptor("clear-owner-a");
        BBSAddonDescriptor queued = descriptor("clear-queued-b");
        BBSUiRemoteInputState ownerState = state(
            3D,
            5D,
            0,
            Set.of(GLFW.GLFW_KEY_A),
            0
        );

        CompletableFuture<BBSUiInputResult> acquired = BBSUiInputDispatcher.submit(
            owner,
            stateBatch(SESSION, 1L, ownerState)
        );
        executor.runAll();
        check(completed(acquired, "non-owner clear controller").applied(),
            "addon A did not acquire the controller lease");

        CompletableFuture<BBSUiInputResult> queuedInput = BBSUiInputDispatcher.submit(
            queued,
            eventBatch(
                SESSION,
                1L,
                IDLE_STATE,
                new BBSUiKeyEvent(GLFW.GLFW_KEY_B, 0, BBSUiInputAction.PRESS, 0)
            )
        );

        BBSUiInputDispatcher.clear(queued);
        check(completed(queuedInput, "non-owner queued clear").status() == BBSUiInputStatus.REJECTED,
            "clear(B) did not synchronously reject B's queued generation while A owned the lease");
        BBSUiInputDispatcher.clear(owner);
        executor.runAll();

        check(target.actions.equals(List.of(keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE))),
            "cleared addon B executed/acquired after addon A released its lease");
        check(!BBSUiRemoteHeldState.isActive(),
            "clearing A after cancelled B retained a controller lease");
    }

    private static void assertSingleDrainBudget()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("drain-budget");
        int budget = BBSUiInputDispatcher.drainBudgetForTesting();
        List<CompletableFuture<BBSUiInputResult>> futures = new ArrayList<>(budget + 1);

        for (int i = 0; i <= budget; i++)
        {
            futures.add(BBSUiInputDispatcher.submit(addon, reliableBatch(SESSION, i + 1L, GLFW.GLFW_KEY_A)));
        }

        check(executor.queuedCount() == 1, "initial drain was not singly scheduled");
        executor.runNext();

        for (int i = 0; i < budget; i++)
        {
            check(completed(futures.get(i), "batch inside drain budget").applied(),
                "batch inside the single-drain budget was not applied");
        }
        check(!futures.get(budget).isDone(), "one drain exceeded its advertised batch budget");
        check(BBSUiInputDispatcher.pendingCountForTesting() == 1,
            "one drain did not leave exactly the over-budget batch pending");
        check(executor.queuedCount() == 1, "over-budget input did not schedule exactly one continuation");

        executor.runNext();
        check(completed(futures.get(budget), "batch after drain budget").applied(),
            "continuation drain did not apply the remaining batch");
        check(executor.queuedCount() == 0, "drain continuation leaked another task");
    }

    private static void assertExecutorFailureCompletesFuture()
    {
        RecordingTarget target = new RecordingTarget();
        BBSUiInputDispatcher.installForTesting(target, SESSION, (runnable) ->
        {
            throw new IllegalStateException("executor closed");
        });
        BBSAddonDescriptor addon = descriptor("executor-failure");
        CompletableFuture<BBSUiInputResult> future = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION, 1L, GLFW.GLFW_KEY_A)
        );
        BBSUiInputResult result = completed(future, "executor failure");

        check(result.status() == BBSUiInputStatus.REJECTED,
            "executor failure did not reject its accepted mailbox future");
        check(result.message().contains("executor is unavailable"),
            "executor failure result did not identify the unavailable executor");
        check(BBSUiInputDispatcher.pendingCountForTesting() == 0,
            "executor failure left an unresolved mailbox entry");
    }

    private static void assertExecutorFailureReleasesExistingLease()
    {
        RecordingTarget target = new RecordingTarget();
        target.inspectSyntheticReleaseLease = true;
        SwitchableExecutor executor = new SwitchableExecutor();

        BBSUiInputDispatcher.installForTesting(target, SESSION, executor);

        BBSAddonDescriptor addon = descriptor("executor-failure-existing-lease");
        BBSUiRemoteInputState held = state(
            13D,
            17D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        CompletableFuture<BBSUiInputResult> acquired = BBSUiInputDispatcher.submit(
            addon,
            stateBatch(SESSION, 1L, held)
        );

        check(completed(acquired, "executor failure existing owner").applied(),
            "executor failure test did not establish its initial lease");

        executor.rejectTasks = true;

        CompletableFuture<BBSUiInputResult> failed = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION, 2L, GLFW.GLFW_KEY_A)
        );
        BBSUiInputResult result = completed(failed, "executor failure with existing lease");

        check(result.status() == BBSUiInputStatus.REJECTED
                && result.message().contains("executor is unavailable"),
            "executor failure did not reject the pending batch with its mailbox reason");
        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE)
        )), "executor failure cleared held state without exactly-once synthetic releases");
        check(target.syntheticReleaseObservations == 2 && target.syntheticReleaseLeaseAuthoritative,
            "executor failure dropped the remote snapshot before synthetic release callbacks");
        check(!target.localHeldSupplierPolled,
            "executor failure synthetic releases polled physical local held-state suppliers");
        check(!BBSUiRemoteHeldState.isActive(),
            "executor failure retained the existing remote held-state lease");
    }

    private static void assertOffThreadExecutorFailureDefersReleaseUntilDrain()
    {
        RecordingTarget target = new RecordingTarget();
        SwitchableExecutor executor = new SwitchableExecutor();

        BBSUiInputDispatcher.installForTesting(target, SESSION, executor);

        BBSAddonDescriptor owner = descriptor("off-thread-failure-owner");
        BBSAddonDescriptor replacement = descriptor("off-thread-failure-replacement");
        BBSUiRemoteInputState held = state(
            13D,
            17D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );

        check(completed(
            BBSUiInputDispatcher.submit(owner, stateBatch(SESSION, 1L, held)),
            "off-thread failure owner"
        ).applied(), "off-thread executor failure test did not establish its lease");

        executor.sameThread = false;
        executor.rejectTasks = true;

        BBSUiInputResult failure = completed(
            BBSUiInputDispatcher.submit(owner, reliableBatch(SESSION, 2L, GLFW.GLFW_KEY_A)),
            "off-thread executor failure"
        );

        check(failure.status() == BBSUiInputStatus.REJECTED,
            "off-thread executor failure did not reject its pending batch");
        check(target.actions.isEmpty(),
            "off-thread executor failure dispatched UI synthetic releases on the caller thread");
        check(BBSUiRemoteHeldState.isActive(),
            "off-thread executor failure dropped the lease before a client-thread release");

        executor.rejectTasks = false;
        executor.sameThread = true;

        CompletableFuture<BBSUiInputResult> recovered = BBSUiInputDispatcher.submit(
            replacement,
            eventBatch(
                SESSION,
                1L,
                IDLE_STATE,
                new BBSUiKeyEvent(GLFW.GLFW_KEY_B, 0, BBSUiInputAction.PRESS, 0)
            )
        );

        check(completed(recovered, "post-failure replacement").applied(),
            "first recovered client drain did not accept the replacement owner");
        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE),
            keyAction(GLFW.GLFW_KEY_B, GLFW.GLFW_PRESS)
        )), "recovered drain did not release the failed owner before applying the replacement");

        BBSUiInputDispatcher.clear(replacement);
        check(!BBSUiRemoteHeldState.isActive(),
            "replacement lease remained active after recovered-drain cleanup");
    }

    private static void assertOffThreadExecutorFailureLifecycleResetReleasesLease()
    {
        RecordingTarget target = new RecordingTarget();
        SwitchableExecutor executor = new SwitchableExecutor();

        BBSUiInputDispatcher.installForTesting(target, SESSION, executor);

        BBSAddonDescriptor owner = descriptor("off-thread-failure-lifecycle");
        BBSUiRemoteInputState held = state(
            13D,
            17D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );

        check(completed(
            BBSUiInputDispatcher.submit(owner, stateBatch(SESSION, 1L, held)),
            "off-thread lifecycle owner"
        ).applied(), "off-thread lifecycle test did not establish its lease");

        executor.sameThread = false;
        executor.rejectTasks = true;

        check(completed(
            BBSUiInputDispatcher.submit(owner, reliableBatch(SESSION, 2L, GLFW.GLFW_KEY_A)),
            "off-thread lifecycle executor failure"
        ).status() == BBSUiInputStatus.REJECTED,
            "off-thread lifecycle executor failure did not reject its pending batch");
        check(target.actions.isEmpty() && BBSUiRemoteHeldState.isActive(),
            "off-thread lifecycle failure released UI state before lifecycle teardown");

        BBSUiInputDispatcher.reset();

        check(target.actions.equals(List.of(
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_RELEASE)
        )), "client lifecycle reset did not release the deferred failed lease exactly once");
        check(!BBSUiRemoteHeldState.isActive(),
            "client lifecycle reset retained the deferred failed lease");
    }

    private static void assertDispatchFailureReleasesLease()
    {
        RecordingTarget target = new RecordingTarget();
        target.throwAfterActions = 1;
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("dispatch-failure");
        BBSUiRemoteInputState held = state(
            13D,
            17D,
            1 << GLFW.GLFW_MOUSE_BUTTON_LEFT,
            Set.of(GLFW.GLFW_KEY_A),
            0
        );
        CompletableFuture<BBSUiInputResult> future = BBSUiInputDispatcher.submit(
            addon,
            eventBatch(
                SESSION,
                1L,
                held,
                new BBSUiKeyEvent(GLFW.GLFW_KEY_B, 0, BBSUiInputAction.PRESS, 0)
            )
        );

        executor.runAll();

        check(completed(future, "dispatch failure").status() == BBSUiInputStatus.REJECTED,
            "throwing input target did not reject its batch");
        check(!BBSUiRemoteHeldState.isActive(),
            "throwing input target retained the remote held-state lease");
        check(target.actions.equals(List.of(
            keyAction(GLFW.GLFW_KEY_B, GLFW.GLFW_PRESS),
            mouseRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            keyAction(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE),
            keyAction(GLFW.GLFW_KEY_B, GLFW.GLFW_RELEASE)
        )), "dispatch failure did not release the state owned by its controller");
    }

    private static void assertCloseCompletesFuture()
    {
        RecordingTarget target = new RecordingTarget();
        ManualExecutor executor = install(target, SESSION);
        BBSAddonDescriptor addon = descriptor("session-close");
        CompletableFuture<BBSUiInputResult> future = BBSUiInputDispatcher.submit(
            addon,
            reliableBatch(SESSION, 1L, GLFW.GLFW_KEY_A)
        );

        check(!future.isDone(), "queued input completed before close");
        BBSUiInputDispatcher.reset();

        check(completed(future, "session close").status() == BBSUiInputStatus.STALE_SESSION,
            "session close did not complete its pending future as stale");
        check(BBSUiInputDispatcher.pendingCountForTesting() == 0,
            "session close left a pending mailbox entry");

        executor.runAll();
        check(target.actions.isEmpty(), "a queued drain dispatched input after session close");
    }

    private static ManualExecutor install(RecordingTarget target, long sessionId)
    {
        ManualExecutor executor = new ManualExecutor();

        BBSUiInputDispatcher.installForTesting(target, sessionId, executor);

        return executor;
    }

    private static BBSAddonDescriptor descriptor(String addonId)
    {
        return BBSAddonDescriptor.builder(addonId)
            .side(BBSAddonSide.CLIENT)
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();
    }

    private static BBSUiInputBatch reliableBatch(long sessionId, long sequence, int keyCode)
    {
        return eventBatch(
            sessionId,
            sequence,
            IDLE_STATE,
            new BBSUiKeyEvent(keyCode, 0, BBSUiInputAction.RELEASE, 0)
        );
    }

    private static BBSUiInputBatch stateBatch(long sessionId, long sequence, BBSUiRemoteInputState state)
    {
        return new BBSUiInputBatch(sessionId, sequence, state, List.of());
    }

    private static BBSUiInputBatch eventBatch(
        long sessionId,
        long sequence,
        BBSUiRemoteInputState state,
        BBSUiInputEvent event
    )
    {
        return new BBSUiInputBatch(sessionId, sequence, state, List.of(event));
    }

    private static BBSUiRemoteInputState state(
        double mouseX,
        double mouseY,
        int mouseButtons,
        Set<Integer> pressedKeys,
        int modifiers
    )
    {
        return new BBSUiRemoteInputState(mouseX, mouseY, mouseButtons, pressedKeys, modifiers);
    }

    private static BBSUiInputResult completed(CompletableFuture<BBSUiInputResult> future, String label)
    {
        check(future.isDone(), label + " future is still pending");
        check(!future.isCompletedExceptionally(), label + " future completed exceptionally");

        return future.join();
    }

    private static void runIsolated(Runnable assertion)
    {
        BBSUiInputDispatcher.resetForTesting();

        try
        {
            assertion.run();
        }
        finally
        {
            BBSUiInputDispatcher.resetForTesting();
        }
    }

    private static String keyAction(int keyCode, int action)
    {
        return "key:" + keyCode + ":" + action;
    }

    private static String mouseRelease(int button)
    {
        return "mouse-release:" + button;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class ManualExecutor implements BBSUiInputDispatcher.ClientExecutor
    {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable runnable)
        {
            this.tasks.addLast(runnable);
        }

        private int queuedCount()
        {
            return this.tasks.size();
        }

        private void runNext()
        {
            Runnable runnable = this.tasks.pollFirst();

            check(runnable != null, "manual executor has no queued task");
            runnable.run();
        }

        private void runAll()
        {
            int runs = 0;

            while (!this.tasks.isEmpty())
            {
                this.runNext();
                runs++;
                check(runs <= 1024, "manual executor did not quiesce");
            }
        }
    }

    private static final class SwitchableExecutor implements BBSUiInputDispatcher.ClientExecutor
    {
        private boolean rejectTasks;
        private boolean sameThread = true;

        @Override
        public void execute(Runnable runnable)
        {
            if (this.rejectTasks)
            {
                throw new IllegalStateException("executor closed");
            }

            runnable.run();
        }

        @Override
        public boolean isSameThread()
        {
            return this.sameThread;
        }
    }

    private static final class RecordingButtonsElement extends UIElement
    {
        private final int[] presses = new int[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
        private final int[] releases = new int[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
        private final int[] cancels = new int[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
        private IntConsumer onPress;
        private IntConsumer onRelease;
        private IntConsumer onCancel;

        private int presses(int button)
        {
            return this.presses[button];
        }

        private int releases(int button)
        {
            return this.releases[button];
        }

        private int cancels(int button)
        {
            return this.cancels[button];
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (!this.area.isInside(context) || !this.isSupportedButton(context.mouseButton))
            {
                return false;
            }

            this.presses[context.mouseButton] += 1;

            if (this.onPress != null)
            {
                this.onPress.accept(context.mouseButton);
            }

            return true;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            if (!this.area.isInside(context) || !this.isSupportedButton(context.mouseButton))
            {
                return false;
            }

            this.releases[context.mouseButton] += 1;

            if (this.onRelease != null)
            {
                this.onRelease.accept(context.mouseButton);
            }

            return true;
        }

        @Override
        protected void subMouseCanceled(UIContext context)
        {
            if (!this.isSupportedButton(context.mouseButton))
            {
                return;
            }

            this.cancels[context.mouseButton] += 1;

            if (this.onCancel != null)
            {
                this.onCancel.accept(context.mouseButton);
            }
        }

        private boolean isSupportedButton(int button)
        {
            return button >= 0 && button < this.presses.length;
        }
    }

    private static final class RecordingKeyboardElement extends UIElement
    {
        private int presses;
        private int repeats;
        private int releases;
        private int textInputs;

        @Override
        protected boolean subKeyPressed(UIContext context)
        {
            if (context.getKeyAction() == mchorse.bbs_mod.ui.utils.keys.KeyAction.PRESSED)
            {
                this.presses += 1;
            }
            else if (context.getKeyAction() == mchorse.bbs_mod.ui.utils.keys.KeyAction.REPEAT)
            {
                this.repeats += 1;
            }
            else
            {
                this.releases += 1;
            }

            return true;
        }

        @Override
        protected boolean subTextInput(UIContext context)
        {
            this.textInputs += 1;

            return true;
        }
    }

    private static final class ThrowingScrollElement extends UIElement
    {
        private final UIBaseMenu menu;
        private final Runnable mutation;

        private ThrowingScrollElement(UIBaseMenu menu, Runnable mutation)
        {
            this.menu = menu;
            this.mutation = mutation;
            this.area.set(0, 0, 20, 20);
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            this.menu.runAfterCapturedMouseRelease(this.mutation);

            throw new IllegalStateException("scroll failure");
        }
    }

    private static final class DummyContextMenu extends UIContextMenu
    {
        @Override
        public boolean isEmpty()
        {
            return false;
        }

        @Override
        public void setMouse(UIContext context)
        {
            this.area.set(context.mouseX, context.mouseY, 10, 10);
            this.xy(context.mouseX, context.mouseY).wh(10, 10);
        }
    }

    /** Headless equivalent of UIScrollView's vertical context/viewport transform. */
    private static final class HeadlessScrollView extends UIScrollView
    {
        private final int shift;

        private HeadlessScrollView(int shift)
        {
            this.shift = shift;
        }

        @Override
        public void apply(IViewportStack stack)
        {
            stack.pushViewport(this.area);

            if (stack instanceof UIContext)
            {
                UIContext context = (UIContext) stack;

                context.mouseY += this.shift;
                context.viewportStack.shiftY(this.shift);
            }
            else
            {
                stack.shiftY(this.shift);
            }
        }

        @Override
        public void unapply(IViewportStack stack)
        {
            if (stack instanceof UIContext)
            {
                UIContext context = (UIContext) stack;

                context.mouseY -= this.shift;
                context.viewportStack.shiftY(-this.shift);
            }
            else
            {
                stack.shiftY(-this.shift);
            }

            stack.popViewport();
        }
    }

    private static final class HeadlessScroll extends Scroll
    {
        private HeadlessScroll(Area area, int itemSize)
        {
            super(area, itemSize);
        }

        @Override
        public int getScrollbarWidth()
        {
            return 4;
        }
    }

    private static final class RecordingDraggable extends UIDraggable
    {
        private Runnable onPress;
        private int presses;
        private int releases;
        private int cancels;
        private int releaseVisits;
        private int releaseY;

        private RecordingDraggable()
        {
            super((context) -> {});
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            boolean handled = super.subMouseClicked(context);

            if (handled)
            {
                this.presses += 1;

                if (this.onPress != null)
                {
                    this.onPress.run();
                }
            }

            return handled;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            this.releaseVisits += 1;

            boolean wasDragging = this.isDragging();
            boolean handled = super.subMouseReleased(context);

            if (wasDragging && handled)
            {
                this.releases += 1;
                this.releaseY = context.mouseY;
            }

            return handled;
        }

        @Override
        protected void subMouseCanceled(UIContext context)
        {
            boolean wasDragging = this.isDragging();

            super.subMouseCanceled(context);

            if (wasDragging && !this.isDragging())
            {
                this.cancels += 1;
            }
        }
    }

    private static final class TestMenu extends UIBaseMenu
    {}

    private static final class RecordingTarget implements BBSUiInputDispatcher.InputTarget
    {
        private final List<String> actions = new ArrayList<>();
        private boolean current = true;
        private int invalidateAfterActions = -1;
        private int throwAfterActions = -1;
        private int throwCurrentChecks;
        private int throwErrorAfterActions = -1;
        private boolean localGestureHeld;
        private boolean inspectSyntheticReleaseLease;
        private boolean localHeldSupplierPolled;
        private boolean syntheticReleaseLeaseAuthoritative = true;
        private int syntheticReleaseObservations;
        private Runnable afterFirstAction;

        private void record(String action)
        {
            this.actions.add(action);

            if (this.invalidateAfterActions >= 0 && this.actions.size() >= this.invalidateAfterActions)
            {
                this.current = false;
            }

            if (this.actions.size() == 1 && this.afterFirstAction != null)
            {
                Runnable callback = this.afterFirstAction;

                this.afterFirstAction = null;
                callback.run();
            }

            if (this.throwAfterActions >= 0 && this.actions.size() == this.throwAfterActions)
            {
                throw new IllegalStateException("input target failure");
            }
            if (this.throwErrorAfterActions >= 0 && this.actions.size() == this.throwErrorAfterActions)
            {
                throw new AssertionError("input target Error");
            }
        }

        @Override
        public boolean isCurrent()
        {
            if (this.throwCurrentChecks > 0)
            {
                this.throwCurrentChecks -= 1;

                throw new IllegalStateException("input target current check failure");
            }

            return this.current;
        }

        @Override
        public void mouseClicked(double x, double y, int button)
        {
            this.record("mouse-click:" + button);
        }

        @Override
        public void mouseReleased(double x, double y, int button)
        {
            this.observeSyntheticReleaseLease();
            this.record(mouseRelease(button));
        }

        @Override
        public void mouseCanceled(double x, double y, int button)
        {
            this.observeSyntheticReleaseLease();
            this.record(mouseRelease(button));
        }

        @Override
        public void mouseScrolled(double x, double y, double horizontal, double vertical)
        {
            this.record("scroll");
        }

        @Override
        public void dispatchRemoteKey(int keyCode, int scanCode, int action, int modifiers)
        {
            if (action == GLFW.GLFW_RELEASE)
            {
                this.observeSyntheticReleaseLease();
            }

            this.record(keyAction(keyCode, action));
        }

        @Override
        public void dispatchRemoteText(String text, int modifiers)
        {
            this.record("text:" + text);
        }

        @Override
        public void releaseLocalGestures()
        {
            if (this.localGestureHeld)
            {
                this.localGestureHeld = false;
                this.record("local-gesture-release");
            }
        }

        private void observeSyntheticReleaseLease()
        {
            if (!this.inspectSyntheticReleaseLease)
            {
                return;
            }

            boolean mouseHeld = BBSUiRemoteHeldState.resolveMouseButtonPressed(
                GLFW.GLFW_MOUSE_BUTTON_LEFT,
                () ->
                {
                    this.localHeldSupplierPolled = true;
                    return false;
                }
            );
            boolean keyHeld = BBSUiRemoteHeldState.resolveKeyPressed(
                GLFW.GLFW_KEY_LEFT_SHIFT,
                () ->
                {
                    this.localHeldSupplierPolled = true;
                    return false;
                }
            );
            boolean modifierHeld = BBSUiRemoteHeldState.resolveModifierPressed(
                BBSUiRemoteInputState.MOD_SHIFT,
                () ->
                {
                    this.localHeldSupplierPolled = true;
                    return false;
                }
            );

            this.syntheticReleaseObservations += 1;
            this.syntheticReleaseLeaseAuthoritative &= BBSUiRemoteHeldState.isActive()
                && mouseHeld
                && keyHeld
                && modifierHeld;
        }
    }
}
