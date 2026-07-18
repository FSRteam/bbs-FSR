package mchorse.bbs_mod.network.compat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Dual message/byte token budget for valid inbound addon broker frames before
 * they can enqueue arbitrary addon code. Separate instances own C2S and S2C
 * totals; each instance scopes debt by exact connection and receiver addon.
 */
final class AddonBrokerServerBudget
{
    private static final Limits DEFAULT_SERVER_LIMITS = new Limits(
        new Rate(32L, 16L),
        new Rate(512L * 1024L, 256L * 1024L),
        new Rate(1_024L, 512L),
        new Rate(16L * 1024L * 1024L, 8L * 1024L * 1024L),
        TimeUnit.SECONDS.toNanos(1L),
        TimeUnit.MINUTES.toNanos(2L),
        4_096
    );
    private static final Limits DEFAULT_CLIENT_LIMITS = new Limits(
        new Rate(32L, 16L),
        new Rate(4L * 1024L * 1024L, 2L * 1024L * 1024L),
        new Rate(256L, 128L),
        new Rate(32L * 1024L * 1024L, 16L * 1024L * 1024L),
        TimeUnit.SECONDS.toNanos(1L),
        TimeUnit.MINUTES.toNanos(2L),
        4_096
    );

    private final Map<ScopeKey, ScopeState> scopes = new HashMap<>();
    private final LongSupplier nanoClock;
    private final Limits limits;
    private final Bucket globalMessages;
    private final Bucket globalBytes;

    AddonBrokerServerBudget()
    {
        this(System::nanoTime, DEFAULT_SERVER_LIMITS);
    }

    static AddonBrokerServerBudget clientDefaults()
    {
        return new AddonBrokerServerBudget(System::nanoTime, DEFAULT_CLIENT_LIMITS);
    }

    AddonBrokerServerBudget(LongSupplier nanoClock, Limits limits)
    {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.limits = Objects.requireNonNull(limits, "limits");

        long now = this.nanoClock.getAsLong();

        this.globalMessages = new Bucket(limits.globalMessages.capacityUnits(limits.refillPeriodNanos), now);
        this.globalBytes = new Bucket(limits.globalBytes.capacityUnits(limits.refillPeriodNanos), now);
    }

    public synchronized boolean tryAcquire(
        UUID owner,
        Object connectionIdentity,
        String receiverAddonId,
        int bodyBytes
    )
    {
        if (owner == null
            || connectionIdentity == null
            || receiverAddonId == null
            || receiverAddonId.isBlank()
            || bodyBytes < 0)
        {
            return false;
        }

        long byteCost;

        try
        {
            byteCost = Math.multiplyExact((long) bodyBytes, this.limits.refillPeriodNanos);
        }
        catch (ArithmeticException exception)
        {
            return false;
        }

        long now = this.nanoClock.getAsLong();

        this.refill(this.globalMessages, this.limits.globalMessages, now);
        this.refill(this.globalBytes, this.limits.globalBytes, now);

        long messageCost = this.limits.refillPeriodNanos;

        if (this.globalMessages.creditUnits < messageCost || this.globalBytes.creditUnits < byteCost)
        {
            return false;
        }

        ScopeKey key = new ScopeKey(owner, connectionIdentity, receiverAddonId);
        ScopeState scope = this.scopes.get(key);
        boolean created = false;

        if (scope == null)
        {
            this.expireIdle(now);

            if (this.scopes.size() >= this.limits.maxScopes)
            {
                return false;
            }

            scope = new ScopeState(this.limits, now);
            created = true;
        }
        else
        {
            this.refill(scope.messages, this.limits.scopeMessages, now);
            this.refill(scope.bytes, this.limits.scopeBytes, now);
        }

        if (scope.messages.creditUnits < messageCost || scope.bytes.creditUnits < byteCost)
        {
            /* A rejected frame must not keep a scope alive. Otherwise a peer
             * that continuously sends over-budget frames can pin one scope
             * until the process reaches its global scope bound. Idle activity
             * is defined by admitted work, not attempted work. */
            return false;
        }

        if (now - scope.lastActivityNanos >= 0L)
        {
            scope.lastActivityNanos = now;
        }

        if (created)
        {
            this.scopes.put(key, scope);
        }

        scope.messages.creditUnits -= messageCost;
        scope.bytes.creditUnits -= byteCost;
        this.globalMessages.creditUnits -= messageCost;
        this.globalBytes.creditUnits -= byteCost;

        return true;
    }

    public synchronized int clearConnection(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return 0;
        }

        int removed = 0;
        Iterator<ScopeKey> iterator = this.scopes.keySet().iterator();

        while (iterator.hasNext())
        {
            ScopeKey key = iterator.next();

            if (key.owner.equals(owner) && key.connectionIdentity == connectionIdentity)
            {
                iterator.remove();
                removed += 1;
            }
        }

        return removed;
    }

    public synchronized int expireIdle()
    {
        return this.expireIdle(this.nanoClock.getAsLong());
    }

    public synchronized void reset()
    {
        this.scopes.clear();

        long now = this.nanoClock.getAsLong();

        this.globalMessages.reset(this.limits.globalMessages.capacityUnits(this.limits.refillPeriodNanos), now);
        this.globalBytes.reset(this.limits.globalBytes.capacityUnits(this.limits.refillPeriodNanos), now);
    }

    synchronized int size()
    {
        return this.scopes.size();
    }

    private void refill(Bucket bucket, Rate rate, long now)
    {
        long elapsed = now - bucket.lastRefillNanos;

        if (elapsed < 0L)
        {
            return;
        }

        bucket.lastRefillNanos = now;

        long capacity = rate.capacityUnits(this.limits.refillPeriodNanos);

        if (elapsed == 0L || bucket.creditUnits >= capacity)
        {
            return;
        }

        long missing = capacity - bucket.creditUnits;
        long complete = missing / rate.refillTokens;
        long remainder = missing % rate.refillTokens;

        if (elapsed > complete || (elapsed == complete && remainder == 0L))
        {
            bucket.creditUnits = capacity;
        }
        else
        {
            bucket.creditUnits += elapsed * rate.refillTokens;
        }
    }

    private int expireIdle(long now)
    {
        int removed = 0;
        Iterator<ScopeState> iterator = this.scopes.values().iterator();

        while (iterator.hasNext())
        {
            ScopeState scope = iterator.next();
            long elapsed = now - scope.lastActivityNanos;

            if (elapsed >= 0L && elapsed >= this.limits.idleTimeoutNanos)
            {
                iterator.remove();
                removed += 1;
            }
        }

        return removed;
    }

    static final class Limits
    {
        private final Rate scopeMessages;
        private final Rate scopeBytes;
        private final Rate globalMessages;
        private final Rate globalBytes;
        private final long refillPeriodNanos;
        private final long idleTimeoutNanos;
        private final int maxScopes;

        Limits(
            Rate scopeMessages,
            Rate scopeBytes,
            Rate globalMessages,
            Rate globalBytes,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int maxScopes
        )
        {
            if (scopeMessages == null
                || scopeBytes == null
                || globalMessages == null
                || globalBytes == null
                || refillPeriodNanos <= 0L
                || idleTimeoutNanos <= 0L
                || maxScopes <= 0)
            {
                throw new IllegalArgumentException("Addon broker budget limits must be positive");
            }

            scopeMessages.validate(refillPeriodNanos);
            scopeBytes.validate(refillPeriodNanos);
            globalMessages.validate(refillPeriodNanos);
            globalBytes.validate(refillPeriodNanos);

            if (globalMessages.capacityTokens < scopeMessages.capacityTokens
                || globalBytes.capacityTokens < scopeBytes.capacityTokens)
            {
                throw new IllegalArgumentException("Global addon broker capacity must include one addon scope");
            }

            this.scopeMessages = scopeMessages;
            this.scopeBytes = scopeBytes;
            this.globalMessages = globalMessages;
            this.globalBytes = globalBytes;
            this.refillPeriodNanos = refillPeriodNanos;
            this.idleTimeoutNanos = idleTimeoutNanos;
            this.maxScopes = maxScopes;
        }
    }

    static final class Rate
    {
        private final long capacityTokens;
        private final long refillTokens;

        Rate(long capacityTokens, long refillTokens)
        {
            this.capacityTokens = capacityTokens;
            this.refillTokens = refillTokens;
        }

        private void validate(long refillPeriodNanos)
        {
            if (this.capacityTokens <= 0L
                || this.refillTokens <= 0L
                || this.capacityTokens > Long.MAX_VALUE / refillPeriodNanos)
            {
                throw new IllegalArgumentException("Addon broker token limits exceed the fixed-point range");
            }
        }

        private long capacityUnits(long refillPeriodNanos)
        {
            return this.capacityTokens * refillPeriodNanos;
        }
    }

    private static final class ScopeState
    {
        private final Bucket messages;
        private final Bucket bytes;
        private long lastActivityNanos;

        private ScopeState(Limits limits, long now)
        {
            this.messages = new Bucket(limits.scopeMessages.capacityUnits(limits.refillPeriodNanos), now);
            this.bytes = new Bucket(limits.scopeBytes.capacityUnits(limits.refillPeriodNanos), now);
            this.lastActivityNanos = now;
        }
    }

    private static final class Bucket
    {
        private long creditUnits;
        private long lastRefillNanos;

        private Bucket(long creditUnits, long now)
        {
            this.creditUnits = creditUnits;
            this.lastRefillNanos = now;
        }

        private void reset(long creditUnits, long now)
        {
            this.creditUnits = creditUnits;
            this.lastRefillNanos = now;
        }
    }

    private static final class ScopeKey
    {
        private final UUID owner;
        private final Object connectionIdentity;
        private final String receiverAddonId;

        private ScopeKey(UUID owner, Object connectionIdentity, String receiverAddonId)
        {
            this.owner = owner;
            this.connectionIdentity = connectionIdentity;
            this.receiverAddonId = receiverAddonId;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof ScopeKey other))
            {
                return false;
            }

            return this.owner.equals(other.owner)
                && this.connectionIdentity == other.connectionIdentity
                && this.receiverAddonId.equals(other.receiverAddonId);
        }

        @Override
        public int hashCode()
        {
            int hash = 31 * this.owner.hashCode() + System.identityHashCode(this.connectionIdentity);

            return 31 * hash + this.receiverAddonId.hashCode();
        }
    }
}
