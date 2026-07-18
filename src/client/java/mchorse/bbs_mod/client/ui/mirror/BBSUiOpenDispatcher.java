package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.client.ui.BBSUiOpenResult;
import mchorse.bbs_mod.api.client.ui.BBSUiOpenStatus;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Bounded any-thread mailbox for the fixed native BBS Dashboard target. */
public final class BBSUiOpenDispatcher
{
    private static final int MAX_PENDING_REQUESTS = 16;
    private static final int DRAIN_BUDGET = 4;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<PendingOpen> PENDING = new ArrayDeque<>();
    private static final Set<String> PENDING_ADDONS = new HashSet<>();

    private static boolean accepting = true;
    private static long lifecycleGeneration;
    private static volatile long observedStateGeneration;
    private static volatile boolean stateObserved;
    private static StateStamp observedState;
    private static PendingOpen active;

    private BBSUiOpenDispatcher()
    {}

    public static CompletableFuture<BBSUiOpenResult> requestDashboardOpen(BBSAddonDescriptor descriptor)
    {
        String accessIssue = validateAccess(descriptor);

        if (accessIssue != null)
        {
            return CompletableFuture.completedFuture(result(BBSUiOpenStatus.REJECTED, accessIssue));
        }
        String addonId = descriptor.addonId();
        OpenFuture future = new OpenFuture();

        synchronized (LOCK)
        {
            if (!accepting)
            {
                return CompletableFuture.completedFuture(result(BBSUiOpenStatus.STALE,
                    "Minecraft client UI lifecycle is stopping"));
            }
            if (!stateObserved)
            {
                return CompletableFuture.completedFuture(result(BBSUiOpenStatus.STALE,
                    "Minecraft client UI lifecycle is not ready"));
            }
            if (PENDING_ADDONS.contains(addonId))
            {
                return CompletableFuture.completedFuture(result(BBSUiOpenStatus.BUSY,
                    "this addon already has a pending Dashboard open request"));
            }
            if (PENDING.size() >= MAX_PENDING_REQUESTS)
            {
                return CompletableFuture.completedFuture(result(BBSUiOpenStatus.BUSY,
                    "Minecraft client UI open mailbox is full"));
            }

            PendingOpen pending = new PendingOpen(
                addonId,
                future,
                lifecycleGeneration,
                observedStateGeneration
            );
            future.pending = pending;
            PENDING.addLast(pending);
            PENDING_ADDONS.add(addonId);
        }

        return future;
    }

    /** Called from the normal Minecraft client tick. */
    public static void tick(Minecraft minecraft)
    {
        tick(new MinecraftOpenTarget(Objects.requireNonNull(minecraft, "minecraft")));
    }

    public static void start(Minecraft minecraft)
    {
        Objects.requireNonNull(minecraft, "minecraft");

        synchronized (LOCK)
        {
            accepting = true;
        }

        observe(new MinecraftOpenTarget(minecraft));
    }

    /** World disconnect: invalidate accepted work while allowing later requests. */
    public static void reset()
    {
        invalidate(false, "Minecraft client lifecycle changed before Dashboard open dispatch");
    }

    /** Client stop: invalidate accepted work and reject later requests. */
    public static void shutdown()
    {
        invalidate(true, "Minecraft client stopped before Dashboard open dispatch");
    }

    static int pendingCapacityForTesting()
    {
        return MAX_PENDING_REQUESTS;
    }

    static int pendingCountForTesting()
    {
        synchronized (LOCK)
        {
            return PENDING.size();
        }
    }

    static Object admissionLockForTesting()
    {
        return LOCK;
    }

    static void startForTesting(OpenTarget target)
    {
        synchronized (LOCK)
        {
            accepting = true;
        }

        observe(Objects.requireNonNull(target, "target"));
    }

    static void tickForTesting(OpenTarget target)
    {
        tick(Objects.requireNonNull(target, "target"));
    }

    static void resetForTesting()
    {
        invalidate(false, "test lifecycle reset");

        synchronized (LOCK)
        {
            accepting = true;
            lifecycleGeneration = 0L;
            observedStateGeneration = 0L;
            observedState = null;
            stateObserved = false;
        }
    }

    private static void tick(OpenTarget target)
    {
        observe(target);

        for (int processed = 0; processed < DRAIN_BUDGET; processed++)
        {
            PendingOpen pending;
            BBSUiOpenResult outcome;

            synchronized (LOCK)
            {
                pending = PENDING.pollFirst();

                if (pending == null)
                {
                    return;
                }

                PENDING_ADDONS.remove(pending.addonId);

                if (pending.future.isCancelled())
                {
                    pending.state = PendingState.DONE;
                    continue;
                }

                if (!accepting || pending.lifecycleGeneration != lifecycleGeneration ||
                    pending.stateGeneration != observedStateGeneration)
                {
                    outcome = result(BBSUiOpenStatus.STALE,
                        "Minecraft client UI state changed before Dashboard open dispatch");
                }
                else
                {
                    /* Admission, lifecycle validation, native side effect, state
                     * observation, and result commit are one linearized operation.
                     * reset/shutdown and cancel cannot enter between them. */
                    pending.state = PendingState.ADMITTED;
                    active = pending;
                    pending.state = PendingState.COMMITTING;
                    outcome = apply(target);

                    if (pending.invalidated || !accepting || pending.lifecycleGeneration != lifecycleGeneration)
                    {
                        outcome = result(BBSUiOpenStatus.STALE,
                            "Minecraft client lifecycle changed during Dashboard open dispatch");
                    }

                    observeLocked(readState(target));
                    pending.outcome = outcome;
                    pending.state = PendingState.COMMITTED;
                }
            }

            /* CompletableFuture callbacks are addon code. Invoke them outside
             * the native commit lock, while ACTIVE keeps the committed outcome
             * visible to a concurrent lifecycle reset. */
            pending.future.complete(outcome);

            synchronized (LOCK)
            {
                if (active == pending)
                {
                    active = null;
                }

                pending.state = PendingState.DONE;
            }
        }
    }

    private static BBSUiOpenResult apply(OpenTarget target)
    {
        try
        {
            if (!target.hasWorld())
            {
                return result(BBSUiOpenStatus.NO_WORLD, "Minecraft is not currently in a world");
            }

            ScreenState screen = target.screenState();

            if (screen == ScreenState.DASHBOARD)
            {
                return result(BBSUiOpenStatus.ALREADY_OPEN, "BBS Dashboard is already open");
            }
            if (screen != ScreenState.NONE)
            {
                return result(BBSUiOpenStatus.BUSY, "another Minecraft screen is currently open");
            }

            /* Active world Replay is intentionally allowed: opening the native
             * Dashboard is how the browser reaches its original controls. */
            target.openDashboard();

            return result(BBSUiOpenStatus.OPENED, "BBS Dashboard opened");
        }
        catch (Exception | LinkageError exception)
        {
            return result(BBSUiOpenStatus.FAILED,
                "BBS Dashboard open failed: " + exception.getClass().getName());
        }
    }

    private static void observe(OpenTarget target)
    {
        StateStamp current = readState(target);

        synchronized (LOCK)
        {
            observeLocked(current);
        }
    }

    private static StateStamp readState(OpenTarget target)
    {
        return new StateStamp(target.hasWorld(), target.screenState(), target.worldReplayActive());
    }

    /** Caller must hold {@link #LOCK}. */
    private static void observeLocked(StateStamp current)
    {
        if (!stateObserved)
        {
            observedState = current;
            stateObserved = true;
            return;
        }
        if (!current.equals(observedState))
        {
            observedState = current;
            observedStateGeneration++;
        }
    }

    private static void invalidate(boolean stop, String message)
    {
        ArrayDeque<PendingOpen> rejected;

        synchronized (LOCK)
        {
            lifecycleGeneration++;
            accepting = !stop;
            observedState = null;
            stateObserved = false;
            observedStateGeneration++;
            rejected = new ArrayDeque<>(PENDING);
            PENDING.clear();
            PENDING_ADDONS.clear();

            for (PendingOpen pending : rejected)
            {
                pending.invalidated = true;
                pending.state = PendingState.DONE;
            }
            if (active != null && active.state == PendingState.ADMITTED)
            {
                active.invalidated = true;
                active.state = PendingState.DONE;
                rejected.addLast(active);
                active = null;
            }
        }

        BBSUiOpenResult stale = result(BBSUiOpenStatus.STALE, message);

        for (PendingOpen pending : rejected)
        {
            if (!pending.future.isCancelled())
            {
                pending.future.complete(stale);
            }
        }
    }

    private static String validateAccess(BBSAddonDescriptor descriptor)
    {
        if (descriptor == null)
        {
            return "addon descriptor is null";
        }
        if (descriptor.addonId() == null || descriptor.addonId().isBlank())
        {
            return "addon id is blank";
        }
        if (!descriptor.capabilities().contains(BBSAddonCapability.CLIENT_UI))
        {
            return "addon did not declare CLIENT_UI capability";
        }

        return null;
    }

    private static BBSUiOpenResult result(BBSUiOpenStatus status, String message)
    {
        return new BBSUiOpenResult(status, message);
    }

    enum ScreenState
    {
        NONE,
        DASHBOARD,
        OTHER
    }

    interface OpenTarget
    {
        boolean hasWorld();

        ScreenState screenState();

        boolean worldReplayActive();

        void openDashboard();
    }

    private static final class MinecraftOpenTarget implements OpenTarget
    {
        private final Minecraft minecraft;

        private MinecraftOpenTarget(Minecraft minecraft)
        {
            this.minecraft = minecraft;
        }

        @Override
        public boolean hasWorld()
        {
            return this.minecraft.level != null && this.minecraft.player != null;
        }

        @Override
        public ScreenState screenState()
        {
            if (this.minecraft.screen == null)
            {
                return ScreenState.NONE;
            }
            if (this.minecraft.screen instanceof UIScreen && UIScreen.getCurrentMenu() instanceof UIDashboard)
            {
                return ScreenState.DASHBOARD;
            }

            return ScreenState.OTHER;
        }

        @Override
        public boolean worldReplayActive()
        {
            return BBSRendering.isWorldReplayActive();
        }

        @Override
        public void openDashboard()
        {
            UIScreen.open(BBSModClient.getDashboard());
        }
    }

    private enum PendingState
    {
        QUEUED,
        ADMITTED,
        COMMITTING,
        COMMITTED,
        DONE
    }

    private record StateStamp(boolean hasWorld, ScreenState screen, boolean worldReplayActive)
    {}

    private static final class PendingOpen
    {
        private final String addonId;
        private final OpenFuture future;
        private final long lifecycleGeneration;
        private final long stateGeneration;
        private PendingState state = PendingState.QUEUED;
        private BBSUiOpenResult outcome;
        private boolean invalidated;

        private PendingOpen(String addonId, OpenFuture future, long lifecycleGeneration, long stateGeneration)
        {
            this.addonId = addonId;
            this.future = future;
            this.lifecycleGeneration = lifecycleGeneration;
            this.stateGeneration = stateGeneration;
        }
    }

    /** Cancellation linearizes with drain admission under the mailbox lock. */
    private static final class OpenFuture extends CompletableFuture<BBSUiOpenResult>
    {
        private PendingOpen pending;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            synchronized (LOCK)
            {
                if (this.pending == null || this.pending.state != PendingState.QUEUED)
                {
                    return false;
                }

                return super.cancel(false);
            }
        }
    }
}
