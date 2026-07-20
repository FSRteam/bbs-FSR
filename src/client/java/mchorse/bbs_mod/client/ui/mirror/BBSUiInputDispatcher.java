package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.ui.BBSUiInputAction;
import mchorse.bbs_mod.api.client.ui.BBSUiInputBatch;
import mchorse.bbs_mod.api.client.ui.BBSUiInputEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiInputResult;
import mchorse.bbs_mod.api.client.ui.BBSUiInputStatus;
import mchorse.bbs_mod.api.client.ui.BBSUiKeyEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiMouseButtonEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiScrollEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiTextEvent;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class BBSUiInputDispatcher
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-ui-input");
    private static final int MAX_EVENTS_PER_BATCH = 256;
    private static final int MAX_PRESSED_KEYS = 256;
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_PENDING_BATCHES = 128;
    private static final int MAX_PENDING_CLEARS = 64;
    private static final int DRAIN_BUDGET = 32;

    private static final Map<String, Long> LAST_SEQUENCES = new HashMap<>();
    private static final Map<String, Long> ADDON_GENERATIONS = new HashMap<>();
    private static final Object MAILBOX_LOCK = new Object();
    private static final ArrayDeque<PendingInput> PENDING_INPUTS = new ArrayDeque<>();
    private static final Set<String> PENDING_CLEARS = new LinkedHashSet<>();
    private static volatile UIScreen activeScreen;
    private static volatile InputTarget activeTarget;
    private static volatile long activeSessionId;
    private static volatile String controllerAddonId;
    private static BBSUiRemoteInputState lastAppliedState;
    private static long lastAppliedSessionId;
    private static long lastAppliedLeaseId;
    private static boolean clearAllRequested;
    private static boolean drainScheduled;
    private static boolean releasingInputOwnership;
    private static long dispatchGeneration;
    private static final ClientExecutor DEFAULT_CLIENT_EXECUTOR = new ClientExecutor()
    {
        @Override
        public void execute(Runnable runnable)
        {
            Minecraft.getInstance().execute(runnable);
        }

        @Override
        public boolean isSameThread()
        {
            return Minecraft.getInstance().isSameThread();
        }
    };
    private static volatile ClientExecutor clientExecutor = DEFAULT_CLIENT_EXECUTOR;

    private BBSUiInputDispatcher()
    {}

    public static void attach(UIScreen screen, long sessionId)
    {
        if (activeScreen == screen && activeSessionId == sessionId)
        {
            return;
        }

        UIScreen previousScreen = activeScreen;
        InputTarget previousTarget = activeTarget;
        long previousSessionId = activeSessionId;

        rejectPending(invalidatePendingInputs(), BBSUiInputStatus.STALE_SESSION, "UI session changed before input dispatch");

        if (!activeTargetMatches(previousScreen, previousTarget, previousSessionId))
        {
            return;
        }

        try
        {
            releaseInputOwnership(null);
        }
        catch (RuntimeException | Error exception)
        {
            replaceActiveTargetIfMatches(
                previousScreen, previousTarget, previousSessionId,
                null, null, 0L
            );

            throw exception;
        }

        replaceActiveTargetIfMatches(
            previousScreen, previousTarget, previousSessionId,
            screen, new UIScreenTarget(screen), sessionId
        );
    }

    public static void detach(UIScreen screen, long sessionId)
    {
        if (activeScreen != screen || activeSessionId != sessionId)
        {
            return;
        }

        InputTarget detachedTarget = activeTarget;

        rejectPending(invalidatePendingInputs(), BBSUiInputStatus.STALE_SESSION, "UI session closed before input dispatch");

        if (!activeTargetMatches(screen, detachedTarget, sessionId))
        {
            return;
        }

        try
        {
            releaseInputOwnership(null);
        }
        finally
        {
            /* A release callback may synchronously install a replacement
             * screen. Clear only the target this detach call admitted. */
            replaceActiveTargetIfMatches(screen, detachedTarget, sessionId, null, null, 0L);
        }
    }

    public static CompletableFuture<BBSUiInputResult> submit(BBSAddonDescriptor descriptor, BBSUiInputBatch batch)
    {
        String accessIssue = validateAccess(descriptor, batch);

        if (accessIssue != null)
        {
            return CompletableFuture.completedFuture(result(BBSUiInputStatus.REJECTED, accessIssue));
        }

        String batchIssue = validateBatch(batch);

        if (batchIssue != null)
        {
            return CompletableFuture.completedFuture(result(BBSUiInputStatus.REJECTED, batchIssue));
        }
        if (activeTarget == null)
        {
            return CompletableFuture.completedFuture(result(BBSUiInputStatus.NO_SCREEN, "no active BBS UIScreen"));
        }
        if (batch.sessionId() != activeSessionId)
        {
            return CompletableFuture.completedFuture(result(BBSUiInputStatus.STALE_SESSION, "input targets a stale UI session"));
        }

        CompletableFuture<BBSUiInputResult> future = new CompletableFuture<>();
        PendingInput superseded = null;
        boolean schedule = false;

        synchronized (MAILBOX_LOCK)
        {
            if (releasingInputOwnership)
            {
                return CompletableFuture.completedFuture(result(
                    BBSUiInputStatus.REJECTED,
                    "UI input ownership is being released"
                ));
            }

            long generation = dispatchGeneration;
            long addonGeneration = ADDON_GENERATIONS.getOrDefault(descriptor.addonId(), 0L);
            PendingInput tail = PENDING_INPUTS.peekLast();

            if (batch.events().isEmpty() && tail != null &&
                tail.sameStateLane(descriptor, batch, generation, addonGeneration))
            {
                if (batch.sequence() <= tail.batch.sequence())
                {
                    return CompletableFuture.completedFuture(result(
                        BBSUiInputStatus.STALE_SEQUENCE,
                        "input state sequence is not newer than the pending state"
                    ));
                }

                superseded = tail.replace(batch, future);
            }
            else if (PENDING_INPUTS.size() >= MAX_PENDING_BATCHES)
            {
                return CompletableFuture.completedFuture(result(
                    BBSUiInputStatus.REJECTED,
                    "Minecraft client input mailbox is full; reliable input was not queued"
                ));
            }
            else
            {
                PENDING_INPUTS.addLast(new PendingInput(
                    descriptor,
                    batch,
                    future,
                    generation,
                    addonGeneration
                ));
            }

            if (!drainScheduled)
            {
                drainScheduled = true;
                schedule = true;
            }
        }

        if (superseded != null)
        {
            superseded.future.complete(result(BBSUiInputStatus.APPLIED, "input state coalesced into a newer pending state"));
        }
        if (schedule)
        {
            scheduleDrain();
        }

        return future;
    }

    public static void clear(BBSAddonDescriptor descriptor)
    {
        if (descriptor == null)
        {
            return;
        }

        String addonId = descriptor.addonId();

        List<PendingInput> rejected = new ArrayList<>();
        boolean schedule = false;

        synchronized (MAILBOX_LOCK)
        {
            ADDON_GENERATIONS.merge(addonId, 1L, (value, increment) -> value == Long.MAX_VALUE ? 0L : value + increment);
            Iterator<PendingInput> iterator = PENDING_INPUTS.iterator();

            while (iterator.hasNext())
            {
                PendingInput pending = iterator.next();

                if (pending.descriptor.addonId().equals(addonId))
                {
                    rejected.add(pending);
                    iterator.remove();
                }
            }

            if (!clearAllRequested)
            {
                if (PENDING_CLEARS.size() >= MAX_PENDING_CLEARS)
                {
                    clearAllRequested = true;
                    PENDING_CLEARS.clear();
                }
                else
                {
                    PENDING_CLEARS.add(addonId);
                }
            }

            if (!drainScheduled)
            {
                drainScheduled = true;
                schedule = true;
            }
        }

        rejectPending(rejected, BBSUiInputStatus.REJECTED, "input ownership was cleared before dispatch");

        if (schedule)
        {
            scheduleDrain();
        }
    }

    public static int effectiveMouseX(long sessionId, int fallback)
    {
        return (int) BBSUiRemoteHeldState.mouseX(sessionId, fallback);
    }

    public static int effectiveMouseY(long sessionId, int fallback)
    {
        return (int) BBSUiRemoteHeldState.mouseY(sessionId, fallback);
    }

    public static void reset()
    {
        UIScreen previousScreen = activeScreen;
        InputTarget previousTarget = activeTarget;
        long previousSessionId = activeSessionId;

        rejectPending(invalidatePendingInputs(), BBSUiInputStatus.STALE_SESSION, "UI input dispatcher was reset");

        if (!activeTargetMatches(previousScreen, previousTarget, previousSessionId))
        {
            return;
        }

        try
        {
            releaseInputOwnership(null);
        }
        finally
        {
            replaceActiveTargetIfMatches(
                previousScreen, previousTarget, previousSessionId,
                null, null, 0L
            );
        }
    }

    static int pendingCapacityForTesting()
    {
        return MAX_PENDING_BATCHES;
    }

    static int pendingCountForTesting()
    {
        synchronized (MAILBOX_LOCK)
        {
            return PENDING_INPUTS.size();
        }
    }

    static int drainBudgetForTesting()
    {
        return DRAIN_BUDGET;
    }

    static void installForTesting(InputTarget target, long sessionId, ClientExecutor executor)
    {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(executor, "executor");
        UIScreen previousScreen = activeScreen;
        InputTarget previousTarget = activeTarget;
        long previousSessionId = activeSessionId;

        rejectPending(invalidatePendingInputs(), BBSUiInputStatus.STALE_SESSION,
            "test UI session changed before input dispatch");

        if (!activeTargetMatches(previousScreen, previousTarget, previousSessionId))
        {
            return;
        }

        try
        {
            releaseInputOwnership(null);
        }
        catch (RuntimeException | Error exception)
        {
            replaceActiveTargetIfMatches(
                previousScreen, previousTarget, previousSessionId,
                null, null, 0L
            );

            throw exception;
        }

        synchronized (MAILBOX_LOCK)
        {
            if (activeScreen == previousScreen
                && activeTarget == previousTarget
                && activeSessionId == previousSessionId)
            {
                activeScreen = null;
                activeTarget = target;
                activeSessionId = sessionId;
                clientExecutor = executor;
            }
        }
    }

    static void resetForTesting()
    {
        reset();
        clientExecutor = DEFAULT_CLIENT_EXECUTOR;
    }

    private static void scheduleDrain()
    {
        try
        {
            clientExecutor.execute(BBSUiInputDispatcher::drainMailbox);
        }
        catch (RuntimeException | Error exception)
        {
            failMailbox("Minecraft client executor is unavailable");
        }
    }

    private static void drainMailbox()
    {
        try
        {
            applyPendingClears();
            int processed = 0;

            while (processed < DRAIN_BUDGET)
            {
                PendingInput pending;
                boolean current;

                synchronized (MAILBOX_LOCK)
                {
                    pending = PENDING_INPUTS.pollFirst();

                    if (pending == null)
                    {
                        break;
                    }

                    current = pending.dispatchGeneration == dispatchGeneration &&
                        pending.addonGeneration == ADDON_GENERATIONS.getOrDefault(pending.descriptor.addonId(), 0L);
                }

                if (!current)
                {
                    pending.future.complete(result(BBSUiInputStatus.REJECTED, "input was cancelled by lifecycle teardown"));
                    processed++;
                    continue;
                }

                try
                {
                    pending.future.complete(apply(pending));
                }
                catch (RuntimeException | Error exception)
                {
                    releaseInputOwnership(pending.descriptor.addonId());
                    LOGGER.error("[bbs-client-ui-input] input batch failed for addon '{}'",
                        pending.descriptor.addonId(), exception);
                    pending.future.complete(result(
                        BBSUiInputStatus.REJECTED,
                        "input dispatch failed: " + exception.getClass().getName()
                    ));
                }

                processed++;
                applyPendingClears();
            }
        }
        finally
        {
            finishDrain();
        }
    }

    private static void finishDrain()
    {
        boolean reschedule;

        synchronized (MAILBOX_LOCK)
        {
            reschedule = clearAllRequested || !PENDING_CLEARS.isEmpty() || !PENDING_INPUTS.isEmpty();

            if (!reschedule)
            {
                drainScheduled = false;
            }
        }

        if (reschedule)
        {
            scheduleDrain();
        }
    }

    private static void applyPendingClears()
    {
        boolean clearAll;
        Set<String> addonIds;

        synchronized (MAILBOX_LOCK)
        {
            clearAll = clearAllRequested;
            clearAllRequested = false;
            addonIds = Set.copyOf(PENDING_CLEARS);
            PENDING_CLEARS.clear();
        }

        if (clearAll)
        {
            releaseInputOwnership(null);
            return;
        }

        for (String addonId : addonIds)
        {
            if (controllerAddonId == null || controllerAddonId.equals(addonId))
            {
                releaseInputOwnership(addonId);
            }
        }
    }

    private static List<PendingInput> invalidatePendingInputs()
    {
        synchronized (MAILBOX_LOCK)
        {
            dispatchGeneration = dispatchGeneration == Long.MAX_VALUE ? 0L : dispatchGeneration + 1L;
            List<PendingInput> pending = new ArrayList<>(PENDING_INPUTS);
            PENDING_INPUTS.clear();
            PENDING_CLEARS.clear();
            clearAllRequested = false;

            return pending;
        }
    }

    private static void failMailbox(String message)
    {
        List<PendingInput> pending;
        boolean releaseNow = isClientThread();

        synchronized (MAILBOX_LOCK)
        {
            dispatchGeneration = dispatchGeneration == Long.MAX_VALUE ? 0L : dispatchGeneration + 1L;
            pending = new ArrayList<>(PENDING_INPUTS);
            PENDING_INPUTS.clear();
            PENDING_CLEARS.clear();
            clearAllRequested = !releaseNow && lastAppliedState != null;
            drainScheduled = false;
        }

        if (releaseNow)
        {
            releaseInputOwnership(null);
        }

        rejectPending(pending, BBSUiInputStatus.REJECTED, message);
    }

    private static boolean isClientThread()
    {
        try
        {
            return clientExecutor.isSameThread();
        }
        catch (RuntimeException | Error exception)
        {
            return false;
        }
    }

    private static void rejectPending(List<PendingInput> pending, BBSUiInputStatus status, String message)
    {
        for (PendingInput input : pending)
        {
            input.future.complete(result(status, message));
        }
    }

    private static BBSUiInputResult apply(PendingInput pending)
    {
        BBSAddonDescriptor descriptor = pending.descriptor;
        BBSUiInputBatch batch = pending.batch;
        InputTarget target = activeTarget;

        if (!isTargetCurrent(target))
        {
            return result(BBSUiInputStatus.NO_SCREEN, "no active BBS UIScreen");
        }

        if (batch.sessionId() != activeSessionId)
        {
            return result(BBSUiInputStatus.STALE_SESSION, "input targets a stale UI session");
        }

        String issue = validateBatch(batch);

        if (issue != null)
        {
            return result(BBSUiInputStatus.REJECTED, issue);
        }

        String addonId = descriptor.addonId();

        synchronized (MAILBOX_LOCK)
        {
            if (pending.dispatchGeneration != dispatchGeneration ||
                pending.addonGeneration != ADDON_GENERATIONS.getOrDefault(addonId, 0L))
            {
                return result(BBSUiInputStatus.REJECTED, "input was cancelled by lifecycle teardown");
            }
            if (releasingInputOwnership)
            {
                return result(BBSUiInputStatus.REJECTED,
                    "UI input ownership is being released");
            }
            if (controllerAddonId != null && !controllerAddonId.equals(addonId))
            {
                return result(BBSUiInputStatus.REJECTED,
                    "UI input is already controlled by addon '" + controllerAddonId + "'");
            }

            if (controllerAddonId == null)
            {
                releasingInputOwnership = true;

                try
                {
                    target.releaseLocalGestures();
                }
                finally
                {
                    releasingInputOwnership = false;
                }

                if (!isCurrentTarget(pending, target, addonId, batch.sessionId()))
                {
                    return result(BBSUiInputStatus.STALE_SESSION,
                        "UI session changed while local input ownership was released");
                }
            }

            Long lastSequence = LAST_SEQUENCES.get(addonId);

            if (lastSequence != null && batch.sequence() <= lastSequence)
            {
                return result(BBSUiInputStatus.STALE_SEQUENCE,
                    "input sequence is not newer than the last applied batch");
            }

            boolean continuingOwner = addonId.equals(controllerAddonId)
                && lastAppliedState != null
                && lastAppliedSessionId == batch.sessionId();
            BBSUiRemoteInputState initialState = continuingOwner
                ? lastAppliedState
                : InputStateTimeline.initialState(batch);

            controllerAddonId = addonId;
            LAST_SEQUENCES.put(addonId, batch.sequence());
            lastAppliedState = initialState;
            lastAppliedSessionId = batch.sessionId();
            long previousLeaseId = lastAppliedLeaseId;
            if (continuingOwner
                && BBSUiRemoteHeldState.replace(previousLeaseId, batch.sessionId(), initialState))
            {
                /* Keep one ownership token for the whole addon/session. A
                 * state-only batch must not invalidate callbacks or polling
                 * that still refer to the previous lease. */
                lastAppliedLeaseId = previousLeaseId;
            }
            else
            {
                /* The snapshot may have been cleared by an external
                 * lifecycle fence while the dispatcher still owns the
                 * controller. Re-establish a fresh token from the last
                 * committed state rather than inferring a partial gesture. */
                lastAppliedLeaseId = BBSUiRemoteHeldState.install(batch.sessionId(), initialState);
            }
            long leaseId = lastAppliedLeaseId;
            InputStateTimeline timeline = new InputStateTimeline(initialState);
            boolean singleEvent = batch.events().size() == 1;

            for (BBSUiInputEvent event : batch.events())
            {
                if (!isCurrentDispatch(pending, target, addonId, batch.sessionId(), leaseId))
                {
                    releaseInputOwnership(addonId);

                    return result(BBSUiInputStatus.STALE_SESSION,
                        "UI session changed during input batch dispatch");
                }

                BBSUiRemoteInputState eventState = timeline.advance(event, batch.state(), singleEvent);

                if (!replaceHeldState(addonId, batch.sessionId(), leaseId, eventState))
                {
                    releaseInputOwnership(addonId);

                    return result(BBSUiInputStatus.STALE_SESSION,
                        "UI input ownership changed during input batch dispatch");
                }

                try
                {
                    dispatch(target, event);
                }
                catch (RuntimeException | Error exception)
                {
                    /* eventState is already the authoritative state at the
                     * throwing callback. Re-adding a released input here would
                     * make ownership teardown deliver the same terminal event
                     * twice. Press failures remain present in eventState and
                     * are still canceled by releaseInputOwnership(). */
                    replaceHeldState(addonId, batch.sessionId(), leaseId, eventState);

                    throw exception;
                }

                /* A key or button handler may synchronously close or replace
                 * the screen. Never deliver the remainder of this batch to
                 * the detached UIScreen instance captured above. */
                if (!isCurrentDispatch(pending, target, addonId, batch.sessionId(), leaseId))
                {
                    releaseInputOwnership(addonId);

                    return result(BBSUiInputStatus.STALE_SESSION,
                        "UI session changed during input batch dispatch");
                }
            }

            /* Event callbacks observe their ordered temporary held state, but
             * polling after the batch must converge to the producer's exact
             * authoritative snapshot without minting a new ownership token. */
            if (!replaceHeldState(addonId, batch.sessionId(), leaseId, batch.state()))
            {
                releaseInputOwnership(addonId);

                return result(BBSUiInputStatus.STALE_SESSION,
                    "UI input ownership changed before final state convergence");
            }
        }

        return result(BBSUiInputStatus.APPLIED, "input applied");
    }

    private static boolean isCurrentTarget(
        PendingInput pending,
        InputTarget target,
        String addonId,
        long sessionId
    )
    {
        return pending.dispatchGeneration == dispatchGeneration &&
            pending.addonGeneration == ADDON_GENERATIONS.getOrDefault(addonId, 0L) &&
            activeTarget == target && activeSessionId == sessionId && isTargetCurrent(target);
    }

    private static boolean isCurrentDispatch(
        PendingInput pending,
        InputTarget target,
        String addonId,
        long sessionId,
        long leaseId
    )
    {
        return isCurrentTarget(pending, target, addonId, sessionId)
            && addonId.equals(controllerAddonId)
            && lastAppliedSessionId == sessionId
            && lastAppliedLeaseId == leaseId
            && BBSUiRemoteHeldState.isLeaseCurrent(leaseId);
    }

    private static boolean replaceHeldState(
        String addonId,
        long sessionId,
        long leaseId,
        BBSUiRemoteInputState state
    )
    {
        if (!addonId.equals(controllerAddonId)
            || lastAppliedSessionId != sessionId
            || lastAppliedLeaseId != leaseId
            || !BBSUiRemoteHeldState.replace(leaseId, sessionId, state))
        {
            return false;
        }

        lastAppliedState = state;

        return true;
    }

    private static void dispatch(InputTarget target, BBSUiInputEvent event)
    {
        if (event instanceof BBSUiMouseButtonEvent mouse)
        {
            if (mouse.action() == BBSUiInputAction.PRESS)
            {
                target.mouseClicked(mouse.x(), mouse.y(), mouse.button());
            }
            else if (mouse.action() == BBSUiInputAction.RELEASE)
            {
                target.mouseReleased(mouse.x(), mouse.y(), mouse.button());
            }
        }
        else if (event instanceof BBSUiScrollEvent scroll)
        {
            target.mouseScrolled(scroll.x(), scroll.y(), scroll.horizontalAmount(), scroll.verticalAmount());
        }
        else if (event instanceof BBSUiKeyEvent key)
        {
            target.dispatchRemoteKey(key.keyCode(), key.scanCode(), glfwAction(key.action()), key.modifiers());
        }
        else if (event instanceof BBSUiTextEvent text)
        {
            target.dispatchRemoteText(text.text(), text.modifiers());
        }
    }

    private static String validateAccess(BBSAddonDescriptor descriptor, BBSUiInputBatch batch)
    {
        if (descriptor == null)
        {
            return "addon descriptor is null";
        }

        if (!descriptor.capabilities().contains(BBSAddonCapability.CLIENT_UI))
        {
            return "addon did not declare CLIENT_UI capability";
        }

        if (batch == null)
        {
            return "UI input batch is null";
        }

        return null;
    }

    private static String validateBatch(BBSUiInputBatch batch)
    {
        BBSUiRemoteInputState state = batch.state();

        if (!Double.isFinite(state.mouseX()) || !Double.isFinite(state.mouseY()))
        {
            return "mouse coordinates must be finite";
        }

        if (state.pressedKeys().size() > MAX_PRESSED_KEYS)
        {
            return "too many held keys";
        }
        int mouseMask = (1 << (GLFW.GLFW_MOUSE_BUTTON_LAST + 1)) - 1;

        if ((state.pressedMouseButtons() & ~mouseMask) != 0)
        {
            return "held mouse buttons are out of range";
        }

        for (int keyCode : state.pressedKeys())
        {
            if (keyCode < GLFW.GLFW_KEY_UNKNOWN || keyCode > GLFW.GLFW_KEY_LAST)
            {
                return "held key code is out of range";
            }
        }

        if (batch.events().size() > MAX_EVENTS_PER_BATCH)
        {
            return "too many input events in one batch";
        }

        int textLength = 0;

        for (BBSUiInputEvent event : batch.events())
        {
            if (event instanceof BBSUiMouseButtonEvent mouse)
            {
                if (!Double.isFinite(mouse.x()) || !Double.isFinite(mouse.y())) return "mouse coordinates must be finite";
                if (mouse.button() < 0 || mouse.button() > GLFW.GLFW_MOUSE_BUTTON_LAST) return "mouse button is out of range";
                if (mouse.action() == BBSUiInputAction.REPEAT) return "mouse button events cannot repeat";
            }
            else if (event instanceof BBSUiScrollEvent scroll)
            {
                if (!Double.isFinite(scroll.x()) || !Double.isFinite(scroll.y()) ||
                    !Double.isFinite(scroll.horizontalAmount()) || !Double.isFinite(scroll.verticalAmount()))
                {
                    return "scroll values must be finite";
                }
            }
            else if (event instanceof BBSUiKeyEvent key)
            {
                if (key.keyCode() < GLFW.GLFW_KEY_UNKNOWN || key.keyCode() > GLFW.GLFW_KEY_LAST)
                {
                    return "key code is out of range";
                }
            }
            else if (event instanceof BBSUiTextEvent text)
            {
                textLength += text.text().length();

                if (textLength > MAX_TEXT_LENGTH)
                {
                    return "text input is too long";
                }
            }
            else
            {
                return "unsupported UI input event implementation";
            }
        }

        return null;
    }

    private static int glfwAction(BBSUiInputAction action)
    {
        if (action == BBSUiInputAction.PRESS) return GLFW.GLFW_PRESS;
        if (action == BBSUiInputAction.REPEAT) return GLFW.GLFW_REPEAT;

        return GLFW.GLFW_RELEASE;
    }

    /**
     * Ordered, batch-local held-state reducer. The reducer starts at the same
     * controller's previously committed state or a reverse-inferred pre-batch
     * key/button state for a new owner, never the unmodified final snapshot,
     * so future input cannot leak into an earlier callback.
     */
    private static final class InputStateTimeline
    {
        private double mouseX;
        private double mouseY;
        private int mouseButtons;
        private final Set<Integer> pressedKeys;
        private int modifiers;
        private int physicalModifierOwnership;

        private InputStateTimeline(BBSUiRemoteInputState state)
        {
            this.mouseX = state.mouseX();
            this.mouseY = state.mouseY();
            this.mouseButtons = state.pressedMouseButtons();
            this.pressedKeys = new LinkedHashSet<>(state.pressedKeys());
            this.modifiers = state.modifiers();
            this.physicalModifierOwnership = physicalModifiers(this.pressedKeys);
        }

        private static BBSUiRemoteInputState initialState(BBSUiInputBatch batch)
        {
            BBSUiRemoteInputState authoritativeState = batch.state();
            int mouseButtons = authoritativeState.pressedMouseButtons();
            Set<Integer> pressedKeys = new LinkedHashSet<>(authoritativeState.pressedKeys());
            List<BBSUiInputEvent> events = batch.events();

            /* A new controller has no previously committed remote snapshot.
             * Infer only the reversible held key/button sets from the final
             * state. Event-time modifiers remain forward-only because the
             * final mask cannot identify when a modifier transition occurred. */
            for (int i = events.size() - 1; i >= 0; i--)
            {
                BBSUiInputEvent event = events.get(i);

                if (event instanceof BBSUiMouseButtonEvent mouse)
                {
                    int button = 1 << mouse.button();

                    if (mouse.action() == BBSUiInputAction.PRESS)
                    {
                        mouseButtons &= ~button;
                    }
                    else if (mouse.action() == BBSUiInputAction.RELEASE)
                    {
                        mouseButtons |= button;
                    }
                }
                else if (event instanceof BBSUiKeyEvent key)
                {
                    if (key.action() == BBSUiInputAction.PRESS)
                    {
                        pressedKeys.remove(key.keyCode());
                    }
                    else if (key.action() == BBSUiInputAction.RELEASE)
                    {
                        pressedKeys.add(key.keyCode());
                    }
                }
            }

            return new BBSUiRemoteInputState(
                authoritativeState.mouseX(),
                authoritativeState.mouseY(),
                mouseButtons,
                pressedKeys,
                0
            );
        }

        private BBSUiRemoteInputState advance(
            BBSUiInputEvent event,
            BBSUiRemoteInputState authoritativeState,
            boolean singleEvent
        )
        {
            if (event instanceof BBSUiMouseButtonEvent mouse)
            {
                int button = 1 << mouse.button();

                this.mouseX = mouse.x();
                this.mouseY = mouse.y();
                this.applyModifiers(mouse.modifiers());

                if (mouse.action() == BBSUiInputAction.RELEASE)
                {
                    this.mouseButtons &= ~button;
                }
                else
                {
                    this.mouseButtons |= button;
                }
            }
            else if (event instanceof BBSUiScrollEvent scroll)
            {
                this.mouseX = scroll.x();
                this.mouseY = scroll.y();

                if (scroll.hasSpecifiedModifiers())
                {
                    this.applyModifiers(scroll.modifiers());
                }
                else if (singleEvent)
                {
                    /* The legacy scroll DTO had no event-time modifier. Its
                     * one-event form historically used the batch snapshot. */
                    this.applyModifiers(authoritativeState.modifiers());
                }
            }
            else if (event instanceof BBSUiKeyEvent key)
            {
                if (key.action() == BBSUiInputAction.RELEASE)
                {
                    this.pressedKeys.remove(key.keyCode());
                }
                else
                {
                    this.pressedKeys.add(key.keyCode());
                }

                this.physicalModifierOwnership |= physicalModifier(key.keyCode());
                this.applyModifiers(key.modifiers());
            }
            else if (event instanceof BBSUiTextEvent text)
            {
                this.applyModifiers(text.modifiers());
            }

            return new BBSUiRemoteInputState(
                this.mouseX,
                this.mouseY,
                this.mouseButtons,
                this.pressedKeys,
                this.modifiers
            );
        }

        private void applyModifiers(int eventModifiers)
        {
            int physicallyHeld = physicalModifiers(this.pressedKeys);

            /* The original event modifier value is still delivered to the key
             * or text callback. This reconciliation only governs polling-based
             * held state: once a standard bit has a physical key owner in this
             * batch, a later stale event mask cannot resurrect it after that
             * key's final release. */
            this.physicalModifierOwnership |= physicallyHeld;
            this.modifiers = (eventModifiers & ~this.physicalModifierOwnership) | physicallyHeld;
        }

        private static int physicalModifiers(Set<Integer> keys)
        {
            int modifiers = 0;

            if (keys.contains(GLFW.GLFW_KEY_LEFT_SHIFT) || keys.contains(GLFW.GLFW_KEY_RIGHT_SHIFT))
            {
                modifiers |= BBSUiRemoteInputState.MOD_SHIFT;
            }
            if (keys.contains(GLFW.GLFW_KEY_LEFT_CONTROL) || keys.contains(GLFW.GLFW_KEY_RIGHT_CONTROL))
            {
                modifiers |= BBSUiRemoteInputState.MOD_CONTROL;
            }
            if (keys.contains(GLFW.GLFW_KEY_LEFT_ALT) || keys.contains(GLFW.GLFW_KEY_RIGHT_ALT))
            {
                modifiers |= BBSUiRemoteInputState.MOD_ALT;
            }
            if (keys.contains(GLFW.GLFW_KEY_LEFT_SUPER) || keys.contains(GLFW.GLFW_KEY_RIGHT_SUPER))
            {
                modifiers |= BBSUiRemoteInputState.MOD_SUPER;
            }

            return modifiers;
        }

        private static int physicalModifier(int keyCode)
        {
            return switch (keyCode)
            {
                case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> BBSUiRemoteInputState.MOD_SHIFT;
                case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> BBSUiRemoteInputState.MOD_CONTROL;
                case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> BBSUiRemoteInputState.MOD_ALT;
                case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> BBSUiRemoteInputState.MOD_SUPER;
                default -> 0;
            };
        }
    }

    private static BBSUiInputResult result(BBSUiInputStatus status, String message)
    {
        return new BBSUiInputResult(status, message);
    }

    private static void releaseInputOwnership(String addonId)
    {
        BBSUiRemoteInputState state;
        InputTarget target;
        long sessionId;
        long leaseId;

        synchronized (MAILBOX_LOCK)
        {
            if (releasingInputOwnership)
            {
                return;
            }
            if (addonId != null && controllerAddonId != null && !controllerAddonId.equals(addonId))
            {
                return;
            }

            releasingInputOwnership = true;
            state = lastAppliedState;
            target = activeTarget;
            sessionId = lastAppliedSessionId;
            leaseId = lastAppliedLeaseId;
            controllerAddonId = null;
            LAST_SEQUENCES.clear();
            lastAppliedState = null;
            lastAppliedSessionId = 0L;
            lastAppliedLeaseId = 0L;
        }

        try
        {
            if (state == null || !isSyntheticReleaseTargetCurrent(target, sessionId, leaseId))
            {
                return;
            }

            for (int button = 0; button <= GLFW.GLFW_MOUSE_BUTTON_LAST; button++)
            {
                if (state.isMouseButtonPressed(button))
                {
                    try
                    {
                        target.mouseCanceled(state.mouseX(), state.mouseY(), button);
                    }
                    catch (RuntimeException | Error exception)
                    {
                        LOGGER.debug("[bbs-client-ui-input] mouse gesture cancellation failed", exception);
                    }

                    if (!isSyntheticReleaseTargetCurrent(target, sessionId, leaseId))
                    {
                        return;
                    }
                }
            }

            for (int keyCode : state.pressedKeys())
            {
                if (keyCode < GLFW.GLFW_KEY_UNKNOWN || keyCode > GLFW.GLFW_KEY_LAST)
                {
                    continue;
                }

                try
                {
                    target.dispatchRemoteKey(keyCode, 0, GLFW.GLFW_RELEASE, state.modifiers());
                }
                catch (RuntimeException | Error exception)
                {
                    LOGGER.debug("[bbs-client-ui-input] synthetic key release failed", exception);
                }

                if (!isSyntheticReleaseTargetCurrent(target, sessionId, leaseId))
                {
                    return;
                }
            }
        }
        finally
        {
            /* Release callbacks still resolve held inputs/modifiers from the
             * remote owner. A conditional clear cannot erase a newer lease. */
            BBSUiRemoteHeldState.clear(leaseId);

            synchronized (MAILBOX_LOCK)
            {
                releasingInputOwnership = false;
            }
        }
    }

    private static boolean isSyntheticReleaseTargetCurrent(InputTarget target, long sessionId, long leaseId)
    {
        return BBSUiRemoteHeldState.isLeaseCurrent(leaseId)
            && activeTarget == target
            && activeSessionId == sessionId
            && isTargetCurrent(target);
    }

    private static boolean isTargetCurrent(InputTarget target)
    {
        if (target == null)
        {
            return false;
        }

        try
        {
            return target.isCurrent();
        }
        catch (RuntimeException | Error exception)
        {
            LOGGER.debug("[bbs-client-ui-input] input target current check failed", exception);

            return false;
        }
    }

    private static boolean replaceActiveTargetIfMatches(
        UIScreen expectedScreen,
        InputTarget expectedTarget,
        long expectedSessionId,
        UIScreen replacementScreen,
        InputTarget replacementTarget,
        long replacementSessionId
    )
    {
        synchronized (MAILBOX_LOCK)
        {
            if (activeScreen != expectedScreen
                || activeTarget != expectedTarget
                || activeSessionId != expectedSessionId)
            {
                return false;
            }

            activeScreen = replacementScreen;
            activeTarget = replacementTarget;
            activeSessionId = replacementSessionId;

            return true;
        }
    }

    private static boolean activeTargetMatches(
        UIScreen expectedScreen,
        InputTarget expectedTarget,
        long expectedSessionId
    )
    {
        synchronized (MAILBOX_LOCK)
        {
            return activeScreen == expectedScreen
                && activeTarget == expectedTarget
                && activeSessionId == expectedSessionId;
        }
    }

    @FunctionalInterface
    interface ClientExecutor
    {
        void execute(Runnable runnable);

        default boolean isSameThread()
        {
            return false;
        }
    }

    interface InputTarget
    {
        boolean isCurrent();
        void mouseClicked(double x, double y, int button);
        void mouseReleased(double x, double y, int button);
        void mouseCanceled(double x, double y, int button);
        void mouseScrolled(double x, double y, double horizontal, double vertical);
        void dispatchRemoteKey(int keyCode, int scanCode, int action, int modifiers);
        void dispatchRemoteText(String text, int modifiers);

        default void releaseLocalGestures()
        {}
    }

    private static final class UIScreenTarget implements InputTarget
    {
        private final UIScreen screen;

        private UIScreenTarget(UIScreen screen)
        {
            this.screen = screen;
        }

        @Override
        public boolean isCurrent()
        {
            return Minecraft.getInstance().screen == this.screen;
        }

        @Override
        public void mouseClicked(double x, double y, int button)
        {
            this.screen.dispatchRemoteMouseClicked(x, y, button);
        }

        @Override
        public void mouseReleased(double x, double y, int button)
        {
            this.screen.dispatchRemoteMouseReleased(x, y, button);
        }

        @Override
        public void mouseCanceled(double x, double y, int button)
        {
            this.screen.dispatchRemoteMouseCanceled(x, y, button);
        }

        @Override
        public void mouseScrolled(double x, double y, double horizontal, double vertical)
        {
            this.screen.dispatchRemoteMouseScrolled(x, y, horizontal, vertical);
        }

        @Override
        public void dispatchRemoteKey(int keyCode, int scanCode, int action, int modifiers)
        {
            this.screen.dispatchRemoteKey(keyCode, scanCode, action, modifiers);
        }

        @Override
        public void dispatchRemoteText(String text, int modifiers)
        {
            this.screen.dispatchRemoteText(text, modifiers);
        }

        @Override
        public void releaseLocalGestures()
        {
            this.screen.releaseLocalInputGestures();
        }
    }

    private static final class PendingInput
    {
        private final BBSAddonDescriptor descriptor;
        private BBSUiInputBatch batch;
        private CompletableFuture<BBSUiInputResult> future;
        private final long dispatchGeneration;
        private final long addonGeneration;

        private PendingInput(
            BBSAddonDescriptor descriptor,
            BBSUiInputBatch batch,
            CompletableFuture<BBSUiInputResult> future,
            long dispatchGeneration,
            long addonGeneration
        )
        {
            this.descriptor = descriptor;
            this.batch = batch;
            this.future = future;
            this.dispatchGeneration = dispatchGeneration;
            this.addonGeneration = addonGeneration;
        }

        private boolean sameStateLane(
            BBSAddonDescriptor descriptor,
            BBSUiInputBatch batch,
            long dispatchGeneration,
            long addonGeneration
        )
        {
            return this.batch.events().isEmpty() && batch.events().isEmpty() &&
                this.descriptor.addonId().equals(descriptor.addonId()) &&
                this.batch.sessionId() == batch.sessionId() &&
                this.dispatchGeneration == dispatchGeneration &&
                this.addonGeneration == addonGeneration;
        }

        private PendingInput replace(BBSUiInputBatch batch, CompletableFuture<BBSUiInputResult> future)
        {
            PendingInput previous = new PendingInput(
                this.descriptor,
                this.batch,
                this.future,
                this.dispatchGeneration,
                this.addonGeneration
            );
            this.batch = batch;
            this.future = future;

            return previous;
        }
    }
}
