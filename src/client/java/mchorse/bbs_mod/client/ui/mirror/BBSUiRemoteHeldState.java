package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Immutable volatile snapshot read by the existing polling-based UI input
 * helpers. An inactive snapshot never changes local GLFW behavior.
 */
public final class BBSUiRemoteHeldState
{
    private static volatile Snapshot snapshot = Snapshot.EMPTY;
    private static long nextLeaseId;

    private BBSUiRemoteHeldState()
    {}

    static synchronized long install(long sessionId, BBSUiRemoteInputState state)
    {
        nextLeaseId = nextLeaseId == Long.MAX_VALUE ? 1L : nextLeaseId + 1L;
        snapshot = new Snapshot(nextLeaseId, sessionId, state);

        return nextLeaseId;
    }

    /**
     * Replace the held snapshot without changing its ownership token. This is
     * used while one admitted input batch advances through its ordered events
     * and while the same owner continues with a later state batch; a stale
     * callback cannot overwrite a newer owner or session.
     */
    static synchronized boolean replace(long leaseId, long sessionId, BBSUiRemoteInputState state)
    {
        Objects.requireNonNull(state, "state");

        if (leaseId <= 0L || snapshot.leaseId != leaseId || snapshot.sessionId != sessionId)
        {
            return false;
        }

        snapshot = new Snapshot(leaseId, sessionId, state);

        return true;
    }

    public static synchronized void clear()
    {
        snapshot = Snapshot.EMPTY;
    }

    /** Clear only the lease installed by the matching dispatch generation. */
    static synchronized void clear(long leaseId)
    {
        if (leaseId > 0L && snapshot.leaseId == leaseId)
        {
            snapshot = Snapshot.EMPTY;
        }
    }

    static boolean isLeaseCurrent(long leaseId)
    {
        Snapshot current = snapshot;

        return leaseId > 0L && current.leaseId == leaseId;
    }

    static long leaseIdForTesting()
    {
        return snapshot.leaseId;
    }

    public static boolean isActive(long sessionId)
    {
        Snapshot current = snapshot;

        return current.sessionId > 0L && current.sessionId == sessionId;
    }

    public static boolean isActive()
    {
        return snapshot.state != null;
    }

    /**
     * Resolve one held key from exactly one input owner. The local supplier is
     * not polled while a remote lease is active, preventing coordinates from
     * the remote controller being combined with physical key state.
     */
    public static boolean resolveKeyPressed(int keyCode, BooleanSupplier localPressed)
    {
        Snapshot current = snapshot;

        return current.state == null
            ? Objects.requireNonNull(localPressed, "localPressed").getAsBoolean()
            : current.state.isKeyPressed(keyCode);
    }

    public static boolean resolveMouseButtonPressed(int button, BooleanSupplier localPressed)
    {
        Snapshot current = snapshot;

        return current.state == null
            ? Objects.requireNonNull(localPressed, "localPressed").getAsBoolean()
            : current.state.isMouseButtonPressed(button);
    }

    public static boolean resolveModifierPressed(int modifier, BooleanSupplier localPressed)
    {
        Snapshot current = snapshot;

        return current.state == null
            ? Objects.requireNonNull(localPressed, "localPressed").getAsBoolean()
            : current.state.hasModifier(modifier);
    }

    public static boolean isKeyPressed(int keyCode)
    {
        Snapshot current = snapshot;

        return current.state != null && current.state.isKeyPressed(keyCode);
    }

    public static boolean isMouseButtonPressed(int button)
    {
        Snapshot current = snapshot;

        return current.state != null && current.state.isMouseButtonPressed(button);
    }

    public static boolean isShiftPressed()
    {
        return hasModifier(BBSUiRemoteInputState.MOD_SHIFT);
    }

    public static boolean isControlPressed()
    {
        return hasModifier(BBSUiRemoteInputState.MOD_CONTROL);
    }

    public static boolean isAltPressed()
    {
        return hasModifier(BBSUiRemoteInputState.MOD_ALT);
    }

    public static double mouseX(long sessionId, double fallback)
    {
        Snapshot current = snapshot;

        return current.sessionId == sessionId && current.state != null ? current.state.mouseX() : fallback;
    }

    public static double mouseY(long sessionId, double fallback)
    {
        Snapshot current = snapshot;

        return current.sessionId == sessionId && current.state != null ? current.state.mouseY() : fallback;
    }

    private static boolean hasModifier(int modifier)
    {
        Snapshot current = snapshot;

        return current.state != null && current.state.hasModifier(modifier);
    }

    private static final class Snapshot
    {
        private static final Snapshot EMPTY = new Snapshot(0L, 0L, null);

        private final long leaseId;
        private final long sessionId;
        private final BBSUiRemoteInputState state;

        private Snapshot(long leaseId, long sessionId, BBSUiRemoteInputState state)
        {
            this.leaseId = leaseId;
            this.sessionId = sessionId;
            this.state = state;
        }
    }
}
