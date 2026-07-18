package mchorse.bbs_mod.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Server-authoritative transition state for gun zoom commands.
 *
 * <p>The zoom-on transition captures the matching off command from the exact
 * held stack that the server validated. A later off transition therefore does
 * not depend on what the player is holding at that time. Duplicate states are
 * ignored. Off is always allowed for an active session so cleanup cannot be
 * stranded by the rate limit; the following on transition must wait for the
 * minimum interval, which bounds repeated on/off command pairs.</p>
 */
final class NetworkZoomSessions
{
    static final long DEFAULT_MIN_TRANSITION_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private final Map<ConnectionKey, State> states = new HashMap<>();
    private final LongSupplier nanoClock;
    private final long minTransitionIntervalNanos;

    NetworkZoomSessions()
    {
        this(System::nanoTime, DEFAULT_MIN_TRANSITION_INTERVAL_NANOS);
    }

    NetworkZoomSessions(LongSupplier nanoClock, long minTransitionIntervalNanos)
    {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");

        if (minTransitionIntervalNanos <= 0L)
        {
            throw new IllegalArgumentException("Zoom transition interval must be positive");
        }

        this.minTransitionIntervalNanos = minTransitionIntervalNanos;
    }

    /**
     * Starts zooming and captures the matching off command from the validated
     * server-side item stack.
     */
    public synchronized Transition turnOn(
        UUID owner,
        Object connectionIdentity,
        String onCommand,
        String offCommand
    )
    {
        if (owner == null || connectionIdentity == null)
        {
            return Transition.IGNORED;
        }

        long now = this.nanoClock.getAsLong();
        ConnectionKey key = new ConnectionKey(owner, connectionIdentity);
        State state = this.states.get(key);

        if (state != null)
        {
            if (state.active || !hasElapsed(now, state.lastTransitionNanos, this.minTransitionIntervalNanos))
            {
                return Transition.IGNORED;
            }

            state.active = true;
            state.lastTransitionNanos = now;
            state.offCommand = normalize(offCommand);
        }
        else
        {
            state = new State(true, now, normalize(offCommand));
            this.states.put(key, state);
        }

        return Transition.accepted(onCommand);
    }

    /**
     * Stops zooming and returns the off command captured by {@link #turnOn}.
     * The current held item is intentionally irrelevant.
     */
    public synchronized Transition turnOff(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return Transition.IGNORED;
        }

        State state = this.states.get(new ConnectionKey(owner, connectionIdentity));

        if (state == null || !state.active)
        {
            return Transition.IGNORED;
        }

        String offCommand = state.offCommand;
        long now = this.nanoClock.getAsLong();

        state.active = false;

        if (now - state.lastTransitionNanos >= 0L)
        {
            state.lastTransitionNanos = now;
        }

        state.offCommand = "";

        return Transition.accepted(offCommand);
    }

    /**
     * Clears one disconnected owner. If it was active, the returned transition
     * lets the lifecycle caller execute the captured off command once.
     */
    public synchronized Transition clearOwner(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return Transition.IGNORED;
        }

        State removed = this.states.remove(new ConnectionKey(owner, connectionIdentity));

        return removed != null && removed.active
            ? Transition.accepted(removed.offCommand)
            : Transition.IGNORED;
    }

    public synchronized void reset()
    {
        this.states.clear();
    }

    /** Drain active off commands exactly once during graceful server stop. */
    public synchronized List<ActiveTransition> drainActive()
    {
        List<ActiveTransition> transitions = new ArrayList<>();

        for (Map.Entry<ConnectionKey, State> entry : this.states.entrySet())
        {
            State state = entry.getValue();

            if (state.active)
            {
                transitions.add(new ActiveTransition(
                    entry.getKey().owner,
                    entry.getKey().connectionIdentity,
                    Transition.accepted(state.offCommand)
                ));
            }
        }

        this.states.clear();

        return List.copyOf(transitions);
    }

    synchronized boolean isActive(UUID owner, Object connectionIdentity)
    {
        State state = owner == null || connectionIdentity == null
            ? null
            : this.states.get(new ConnectionKey(owner, connectionIdentity));

        return state != null && state.active;
    }

    synchronized int size()
    {
        return this.states.size();
    }

    private static boolean hasElapsed(long now, long then, long interval)
    {
        long elapsed = now - then;

        /* A negative delta represents clock rollback (or an interval outside
         * nanoTime's supported comparison window), so fail closed. */
        return elapsed >= 0L && elapsed >= interval;
    }

    private static String normalize(String command)
    {
        return command == null ? "" : command;
    }

    private static final class State
    {
        private boolean active;
        private long lastTransitionNanos;
        private String offCommand;

        private State(boolean active, long lastTransitionNanos, String offCommand)
        {
            this.active = active;
            this.lastTransitionNanos = lastTransitionNanos;
            this.offCommand = offCommand;
        }
    }

    record Transition(boolean accepted, String command)
    {
        private static final Transition IGNORED = new Transition(false, "");

        Transition
        {
            command = normalize(command);
        }

        private static Transition accepted(String command)
        {
            return new Transition(true, command);
        }

        public boolean hasCommand()
        {
            return this.accepted && !this.command.isBlank();
        }
    }

    record ActiveTransition(UUID owner, Object connectionIdentity, Transition transition)
    {
        ActiveTransition
        {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(connectionIdentity, "connectionIdentity");
            Objects.requireNonNull(transition, "transition");
        }
    }

    private static final class ConnectionKey
    {
        private final UUID owner;
        private final Object connectionIdentity;

        private ConnectionKey(UUID owner, Object connectionIdentity)
        {
            this.owner = owner;
            this.connectionIdentity = connectionIdentity;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof ConnectionKey other))
            {
                return false;
            }

            return this.owner.equals(other.owner)
                && this.connectionIdentity == other.connectionIdentity;
        }

        @Override
        public int hashCode()
        {
            return 31 * this.owner.hashCode() + System.identityHashCode(this.connectionIdentity);
        }
    }
}
