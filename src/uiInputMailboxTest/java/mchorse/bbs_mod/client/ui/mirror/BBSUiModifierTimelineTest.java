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
import mchorse.bbs_mod.api.client.ui.BBSUiMouseButtonEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.api.client.ui.BBSUiScrollEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Ordered held-state and lease-fence regressions for multi-event UI input. */
public final class BBSUiModifierTimelineTest
{
    private static final long SESSION = 73L;

    private BBSUiModifierTimelineTest()
    {}

    public static void main(String[] args)
    {
        runAll();
        System.out.println("BBSUiModifierTimelineTest: all tests passed");
    }

    static void runAll()
    {
        runIsolated(BBSUiModifierTimelineTest::shiftClickReleaseUsesEventTimeline);
        runIsolated(BBSUiModifierTimelineTest::newOwnerRetainsUntouchedPreHeldInputs);
        runIsolated(BBSUiModifierTimelineTest::finalShiftDoesNotLeakBackward);
        runIsolated(BBSUiModifierTimelineTest::scrollModifiersRemainOrdered);
        runIsolated(BBSUiModifierTimelineTest::physicalModifierReleaseOrderWinsOverStaleMasks);
        runIsolated(BBSUiModifierTimelineTest::disconnectResetConvergesMultiHeldState);
        runIsolated(BBSUiModifierTimelineTest::dispatchExceptionReleasesTemporaryState);
        runIsolated(BBSUiModifierTimelineTest::reentrantClearStopsOldBatch);
    }

    private static void shiftClickReleaseUsesEventTimeline()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-shift-click");
        BBSUiRemoteInputState idle = state(40D, 50D, 0, Set.of(), 0);
        BBSUiInputBatch batch = batch(addon, 1L, idle, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.PRESS,
                BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiMouseButtonEvent(12D, 13D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.PRESS, BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiMouseButtonEvent(14D, 15D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.RELEASE, BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.RELEASE, 0)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, batch), "shift-click batch").applied(),
            "shift-click batch was not applied");
        check(target.observations.size() == 4, "shift-click batch did not dispatch four events");

        Observation shiftPress = target.observations.get(0);
        Observation mousePress = target.observations.get(1);
        Observation mouseRelease = target.observations.get(2);
        Observation shiftRelease = target.observations.get(3);

        check(shiftPress.shift && shiftPress.shiftKey && !shiftPress.left,
            "Shift press callback did not observe its post-event held state");
        check(mousePress.shift && mousePress.shiftKey && mousePress.left
                && mousePress.mouseX == 12 && mousePress.mouseY == 13,
            "Shift-click callback did not observe Shift, the pressed button, and event coordinates");
        check(mouseRelease.shift && mouseRelease.shiftKey && !mouseRelease.left
                && mouseRelease.mouseX == 14 && mouseRelease.mouseY == 15,
            "mouse release callback did not preserve Shift while releasing only its button");
        check(!shiftRelease.shift && !shiftRelease.shiftKey && !shiftRelease.left,
            "Shift release callback observed the batch's earlier held state");
        check(onePositiveLease(target.observations), "one batch minted more than one event lease");
        check(BBSUiRemoteHeldState.isActive()
                && !BBSUiRemoteHeldState.isShiftPressed()
                && !BBSUiRemoteHeldState.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                && BBSUiInputDispatcher.effectiveMouseX(SESSION, -1) == 40
                && BBSUiInputDispatcher.effectiveMouseY(SESSION, -1) == 50,
            "post-batch polling did not converge to the authoritative idle snapshot");
    }

    private static void finalShiftDoesNotLeakBackward()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-no-backward-leak");
        BBSUiRemoteInputState previous = state(3D, 4D, 0, Set.of(GLFW.GLFW_KEY_A), 0);

        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 1L, previous, List.of())),
            "previous held state").applied(), "previous held state was not applied");
        target.observations.clear();

        BBSUiRemoteInputState finalShift = state(
            9D,
            10D,
            0,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        BBSUiInputBatch batch = batch(addon, 2L, finalShift, List.of(
            new BBSUiMouseButtonEvent(7D, 8D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.PRESS, 0),
            new BBSUiMouseButtonEvent(7D, 8D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.RELEASE, 0),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.PRESS,
                BBSUiRemoteInputState.MOD_SHIFT)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, batch), "final-Shift batch").applied(),
            "final-Shift batch was not applied");
        check(target.observations.size() == 3, "final-Shift batch did not dispatch three events");

        Observation mousePress = target.observations.get(0);
        Observation mouseRelease = target.observations.get(1);
        Observation shiftPress = target.observations.get(2);

        check(!mousePress.shift && mousePress.aKey && mousePress.left,
            "final Shift leaked backward into an earlier mouse press or previous A was lost");
        check(!mouseRelease.shift && mouseRelease.aKey && !mouseRelease.left,
            "final Shift leaked backward into an earlier mouse release or previous A was lost");
        check(shiftPress.shift && shiftPress.shiftKey && shiftPress.aKey,
            "timeline did not evolve from the same owner's previous held keys");
        check(onePositiveLease(target.observations), "final-Shift batch changed lease between events");
        check(BBSUiRemoteHeldState.isShiftPressed()
                && BBSUiRemoteHeldState.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT)
                && !BBSUiRemoteHeldState.isKeyPressed(GLFW.GLFW_KEY_A),
            "authoritative final Shift state did not replace the temporary timeline state");
    }

    private static void newOwnerRetainsUntouchedPreHeldInputs()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-new-owner-pre-held");
        BBSUiRemoteInputState finalState = state(
            19D,
            20D,
            1 << GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            Set.of(GLFW.GLFW_KEY_A),
            0
        );
        BBSUiInputBatch batch = batch(addon, 1L, finalState, List.of(
            new BBSUiScrollEvent(15D, 16D, 0D, 1D, 0),
            new BBSUiMouseButtonEvent(17D, 18D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.PRESS, 0),
            new BBSUiMouseButtonEvent(17D, 18D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.RELEASE, 0)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, batch), "new-owner pre-held batch").applied(),
            "new-owner pre-held batch was not applied");
        check(target.observations.size() == 3, "new-owner pre-held batch did not dispatch three events");

        Observation beforeLeftPress = target.observations.get(0);
        Observation leftPress = target.observations.get(1);
        Observation leftRelease = target.observations.get(2);

        check(beforeLeftPress.aKey && beforeLeftPress.right && !beforeLeftPress.left,
            "new owner's untouched pre-held key/button were lost or a future press leaked backward");
        check(leftPress.aKey && leftPress.right && leftPress.left,
            "forward timeline did not add the newly pressed button to pre-held state");
        check(leftRelease.aKey && leftRelease.right && !leftRelease.left,
            "forward timeline did not remove only the newly released button");
        check(onePositiveLease(target.observations), "new-owner timeline changed lease between events");
        check(BBSUiRemoteHeldState.isKeyPressed(GLFW.GLFW_KEY_A)
                && BBSUiRemoteHeldState.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                && !BBSUiRemoteHeldState.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "new-owner batch did not converge to its authoritative pre-held state");
    }

    private static void scrollModifiersRemainOrdered()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-scroll-order");
        BBSUiRemoteInputState idle = state(0D, 0D, 0, Set.of(), 0);
        BBSUiInputBatch explicit = batch(addon, 1L, idle, List.of(
            new BBSUiScrollEvent(5D, 6D, 0D, 1D, BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.RELEASE, 0)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, explicit), "explicit scroll modifiers").applied(),
            "explicit scroll modifier batch was not applied");
        check(target.observations.get(0).shift && !target.observations.get(1).shift,
            "explicit scroll modifier did not remain ordered before Shift release");

        target.observations.clear();
        BBSUiRemoteInputState finalShift = state(
            8D,
            9D,
            0,
            Set.of(GLFW.GLFW_KEY_LEFT_SHIFT),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        BBSUiInputBatch legacySingle = batch(addon, 2L, finalShift, List.of(
            new BBSUiScrollEvent(8D, 9D, 0D, 1D)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, legacySingle), "legacy single scroll").applied(),
            "legacy single scroll batch was not applied");
        check(target.observations.size() == 1 && target.observations.get(0).shift,
            "legacy single scroll did not recover modifiers from its authoritative snapshot");

        target.observations.clear();
        BBSUiRemoteInputState finalControl = state(
            11D,
            12D,
            0,
            Set.of(GLFW.GLFW_KEY_LEFT_CONTROL),
            BBSUiRemoteInputState.MOD_CONTROL
        );
        BBSUiInputBatch legacyMulti = batch(addon, 3L, finalControl, List.of(
            new BBSUiScrollEvent(10D, 11D, 0D, 1D),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_CONTROL, 0, BBSUiInputAction.PRESS,
                BBSUiRemoteInputState.MOD_CONTROL)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, legacyMulti), "legacy multi scroll").applied(),
            "legacy multi scroll batch was not applied");
        check(target.observations.size() == 2
                && target.observations.get(0).shift
                && !target.observations.get(0).control
                && target.observations.get(1).control,
            "legacy multi-event scroll guessed a future modifier instead of evolving prior state");
    }

    private static void disconnectResetConvergesMultiHeldState()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-multi-held-disconnect");
        int buttons = 1 << GLFW.GLFW_MOUSE_BUTTON_LEFT | 1 << GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        Set<Integer> keys = new LinkedHashSet<>(List.of(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_A));
        BBSUiRemoteInputState held = state(21D, 22D, buttons, keys, BBSUiRemoteInputState.MOD_SHIFT);

        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 1L, held, List.of())),
            "multi-held state").applied(), "multi-held state was not applied");
        long leaseId = BBSUiRemoteHeldState.leaseIdForTesting();

        /* Client disconnect/world teardown reaches the dispatcher-wide reset
         * path rather than one addon's ordinary control release. */
        BBSUiInputDispatcher.reset();

        check(target.count("mouse-cancel:" + GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.count("mouse-cancel:" + GLFW.GLFW_MOUSE_BUTTON_RIGHT) == 1
                && target.count("key-release:" + GLFW.GLFW_KEY_LEFT_SHIFT) == 1
                && target.count("key-release:" + GLFW.GLFW_KEY_A) == 1,
            "disconnect reset did not release every held button and key exactly once");
        check(target.observations.stream().allMatch((observation) -> observation.leaseId == leaseId
                && observation.shift && observation.shiftKey && observation.aKey
                && observation.left && observation.right),
            "disconnect reset changed the lease or dropped held state before callbacks completed");
        check(!BBSUiRemoteHeldState.isActive(), "disconnect reset left a remote lease active");
    }

    private static void physicalModifierReleaseOrderWinsOverStaleMasks()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-physical-release-order");
        Set<Integer> shiftAndA = new LinkedHashSet<>(List.of(
            GLFW.GLFW_KEY_LEFT_SHIFT,
            GLFW.GLFW_KEY_A
        ));
        BBSUiRemoteInputState held = state(
            0D,
            0D,
            0,
            shiftAndA,
            BBSUiRemoteInputState.MOD_SHIFT
        );
        BBSUiRemoteInputState idle = state(0D, 0D, 0, Set.of(), 0);

        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 1L, held, List.of())),
            "Shift+A initial state").applied(), "Shift+A initial state was not applied");
        target.observations.clear();

        BBSUiInputBatch shiftThenA = batch(addon, 2L, idle, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_A, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, shiftThenA), "Shift-then-A release").applied(),
            "Shift-then-A release batch was not applied");
        check(target.observations.size() == 2
                && !target.observations.get(0).shift
                && !target.observations.get(0).shiftKey
                && target.observations.get(0).aKey
                && !target.observations.get(1).shift
                && !target.observations.get(1).aKey,
            "a stale A-release modifier re-added Shift after its physical key was released");

        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 3L, held, List.of())),
            "Shift+A reordered initial state").applied(), "reordered Shift+A initial state was not applied");
        target.observations.clear();

        BBSUiInputBatch aThenShift = batch(addon, 4L, idle, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_A, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, aThenShift), "A-then-Shift release").applied(),
            "A-then-Shift release batch was not applied");
        check(target.observations.size() == 2
                && target.observations.get(0).shift
                && target.observations.get(0).shiftKey
                && !target.observations.get(0).aKey
                && !target.observations.get(1).shift
                && !target.observations.get(1).shiftKey,
            "physical Shift state did not follow both Shift+A release orders");

        BBSUiRemoteInputState virtualShiftAndA = state(
            0D,
            0D,
            0,
            Set.of(GLFW.GLFW_KEY_A),
            BBSUiRemoteInputState.MOD_SHIFT
        );
        BBSUiRemoteInputState virtualShift = state(
            0D,
            0D,
            0,
            Set.of(),
            BBSUiRemoteInputState.MOD_SHIFT
        );

        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 5L, virtualShiftAndA, List.of())),
            "virtual Shift initial state").applied(), "virtual Shift initial state was not applied");
        target.observations.clear();
        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 6L, virtualShift, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_A, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT)
        ))), "virtual Shift A release").applied(), "virtual Shift A release was not applied");
        check(target.observations.size() == 1
                && target.observations.get(0).shift
                && !target.observations.get(0).shiftKey,
            "virtual-only Shift did not preserve the exact event modifier field");

        check(completed(BBSUiInputDispatcher.submit(addon, batch(addon, 7L, held, List.of())),
            "physical plus virtual Shift initial state").applied(),
            "physical plus virtual Shift initial state was not applied");
        target.observations.clear();
        BBSUiInputBatch physicalToVirtual = batch(addon, 8L, virtualShift, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_A, 0, BBSUiInputAction.RELEASE,
                BBSUiRemoteInputState.MOD_SHIFT)
        ));

        check(completed(BBSUiInputDispatcher.submit(addon, physicalToVirtual),
            "physical-to-virtual Shift handoff").applied(),
            "physical-to-virtual Shift handoff batch was not applied");
        check(target.observations.size() == 2
                && target.observations.stream().noneMatch((observation) -> observation.shift)
                && BBSUiRemoteHeldState.isShiftPressed()
                && !BBSUiRemoteHeldState.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT),
            "physical Shift was resurrected during release or final virtual Shift did not converge");
    }

    private static void dispatchExceptionReleasesTemporaryState()
    {
        RecordingTarget target = install();
        target.throwOnObservation = 2;
        BBSAddonDescriptor addon = descriptor("modifier-dispatch-exception");
        BBSUiRemoteInputState idle = state(30D, 31D, 0, Set.of(), 0);
        BBSUiInputBatch batch = batch(addon, 1L, idle, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.PRESS,
                BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiMouseButtonEvent(32D, 33D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.PRESS, BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiMouseButtonEvent(32D, 33D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.RELEASE, BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.RELEASE, 0)
        ));

        BBSUiInputResult result = completed(BBSUiInputDispatcher.submit(addon, batch),
            "throwing modifier batch");

        check(result.status() == BBSUiInputStatus.REJECTED,
            "dispatch exception did not reject its batch");
        check(target.count("mouse-click:" + GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.count("mouse-cancel:" + GLFW.GLFW_MOUSE_BUTTON_LEFT) == 1
                && target.count("key-release:" + GLFW.GLFW_KEY_LEFT_SHIFT) == 1,
            "dispatch exception did not release exactly the temporary pressed state");
        check(target.observations.stream().noneMatch((observation) ->
                observation.action.equals("key-release:" + GLFW.GLFW_KEY_LEFT_SHIFT)
                    && !observation.left),
            "temporary mouse state was cleared before exception cleanup finished");
        check(onePositiveLease(target.observations), "exception cleanup escaped the admitted batch lease");
        check(!BBSUiRemoteHeldState.isActive(), "dispatch exception left the temporary lease active");
    }

    private static void reentrantClearStopsOldBatch()
    {
        RecordingTarget target = install();
        BBSAddonDescriptor addon = descriptor("modifier-reentrant-clear");
        target.afterNextObservation = () -> BBSUiInputDispatcher.clear(addon);
        BBSUiRemoteInputState idle = state(0D, 0D, 0, Set.of(), 0);
        BBSUiInputBatch batch = batch(addon, 1L, idle, List.of(
            new BBSUiKeyEvent(GLFW.GLFW_KEY_LEFT_SHIFT, 0, BBSUiInputAction.PRESS,
                BBSUiRemoteInputState.MOD_SHIFT),
            new BBSUiMouseButtonEvent(2D, 3D, GLFW.GLFW_MOUSE_BUTTON_LEFT,
                BBSUiInputAction.PRESS, BBSUiRemoteInputState.MOD_SHIFT)
        ));

        BBSUiInputResult result = completed(BBSUiInputDispatcher.submit(addon, batch),
            "reentrant-clear batch");

        check(result.status() == BBSUiInputStatus.STALE_SESSION,
            "reentrant clear did not stop the old batch as stale");
        check(target.count("key-press:" + GLFW.GLFW_KEY_LEFT_SHIFT) == 1
                && target.count("key-release:" + GLFW.GLFW_KEY_LEFT_SHIFT) == 1
                && target.count("mouse-click:" + GLFW.GLFW_MOUSE_BUTTON_LEFT) == 0,
            "old batch continued after reentrant clear or failed to release its temporary key");
        check(onePositiveLease(target.observations), "reentrant clear changed lease before cleanup");
        check(!BBSUiRemoteHeldState.isActive(), "reentrant clear left the old lease active");
    }

    private static RecordingTarget install()
    {
        RecordingTarget target = new RecordingTarget();

        BBSUiInputDispatcher.installForTesting(target, SESSION, Runnable::run);

        return target;
    }

    private static BBSAddonDescriptor descriptor(String addonId)
    {
        return BBSAddonDescriptor.builder(addonId)
            .side(BBSAddonSide.CLIENT)
            .capability(BBSAddonCapability.CLIENT_UI)
            .build();
    }

    private static BBSUiInputBatch batch(
        BBSAddonDescriptor addon,
        long sequence,
        BBSUiRemoteInputState state,
        List<BBSUiInputEvent> events
    )
    {
        check(addon != null, "addon descriptor is missing");

        return new BBSUiInputBatch(SESSION, sequence, state, events);
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

    private static boolean onePositiveLease(List<Observation> observations)
    {
        if (observations.isEmpty() || observations.get(0).leaseId <= 0L)
        {
            return false;
        }

        long leaseId = observations.get(0).leaseId;

        return observations.stream().allMatch((observation) -> observation.leaseId == leaseId);
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

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingTarget implements BBSUiInputDispatcher.InputTarget
    {
        private final List<Observation> observations = new ArrayList<>();
        private Runnable afterNextObservation;
        private int throwOnObservation = -1;
        private boolean thrown;

        @Override
        public boolean isCurrent()
        {
            return true;
        }

        @Override
        public void mouseClicked(double x, double y, int button)
        {
            this.record("mouse-click:" + button);
        }

        @Override
        public void mouseReleased(double x, double y, int button)
        {
            this.record("mouse-release:" + button);
        }

        @Override
        public void mouseCanceled(double x, double y, int button)
        {
            this.record("mouse-cancel:" + button);
        }

        @Override
        public void mouseScrolled(double x, double y, double horizontal, double vertical)
        {
            this.record("scroll");
        }

        @Override
        public void dispatchRemoteKey(int keyCode, int scanCode, int action, int modifiers)
        {
            String kind = action == GLFW.GLFW_RELEASE ? "release"
                : action == GLFW.GLFW_REPEAT ? "repeat" : "press";

            this.record("key-" + kind + ":" + keyCode);
        }

        @Override
        public void dispatchRemoteText(String text, int modifiers)
        {
            this.record("text:" + text);
        }

        private int count(String action)
        {
            return (int) this.observations.stream()
                .filter((observation) -> observation.action.equals(action))
                .count();
        }

        private void record(String action)
        {
            this.observations.add(new Observation(
                action,
                BBSUiRemoteHeldState.leaseIdForTesting(),
                BBSUiInputDispatcher.effectiveMouseX(SESSION, -1),
                BBSUiInputDispatcher.effectiveMouseY(SESSION, -1),
                BBSUiRemoteHeldState.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                BBSUiRemoteHeldState.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
                BBSUiRemoteHeldState.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT),
                BBSUiRemoteHeldState.isKeyPressed(GLFW.GLFW_KEY_A),
                BBSUiRemoteHeldState.isShiftPressed(),
                BBSUiRemoteHeldState.isControlPressed()
            ));

            Runnable callback = this.afterNextObservation;

            if (callback != null)
            {
                this.afterNextObservation = null;
                callback.run();
            }

            if (!this.thrown && this.throwOnObservation == this.observations.size())
            {
                this.thrown = true;
                throw new IllegalStateException("modifier timeline test dispatch failure");
            }
        }
    }

    private record Observation(
        String action,
        long leaseId,
        int mouseX,
        int mouseY,
        boolean left,
        boolean right,
        boolean shiftKey,
        boolean aKey,
        boolean shift,
        boolean control
    )
    {}
}
