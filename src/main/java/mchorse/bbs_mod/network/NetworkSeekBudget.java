package mchorse.bbs_mod.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Cumulative server-side work budget for film seek operations.
 *
 * <p>Every request must have credit in the concrete connection's aggregate
 * bucket, the bucket for the requested film, and the server-global bucket. The
 * connection aggregate prevents switching between films from bypassing the
 * limit without making a replacement connection inherit stale debt. Buckets
 * intentionally survive a film stop/restart cycle; only exact-connection or
 * owner cleanup, a server reset, or an idle expiry discards accumulated
 * debt.</p>
 */
final class NetworkSeekBudget
{
    static final long DEFAULT_WORK_UNITS_PER_STEP = 256L;
    static final long DEFAULT_CAPACITY_WORK_UNITS = NetworkMutationPolicy.MAX_FILM_SEEK_STEPS * DEFAULT_WORK_UNITS_PER_STEP;

    private static final Object LEGACY_CONNECTION_IDENTITY = new Object();
    private static final Limits DEFAULT_LIMITS = new Limits(
        DEFAULT_CAPACITY_WORK_UNITS,
        DEFAULT_CAPACITY_WORK_UNITS,
        TimeUnit.SECONDS.toNanos(1L),
        TimeUnit.MINUTES.toNanos(2L),
        64,
        4_096
    );

    private final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
    private final LongSupplier nanoClock;
    private final Limits limits;
    private final long capacityUnits;
    private final long globalCapacityUnits;
    private final Bucket global;
    private int filmBuckets;

    NetworkSeekBudget()
    {
        this(System::nanoTime, DEFAULT_LIMITS);
    }

    NetworkSeekBudget(LongSupplier nanoClock, Limits limits)
    {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.capacityUnits = Math.multiplyExact(limits.capacitySteps, limits.refillPeriodNanos);
        this.globalCapacityUnits = Math.multiplyExact(limits.globalCapacitySteps, limits.refillPeriodNanos);

        long now = this.nanoClock.getAsLong();

        this.global = new Bucket(this.globalCapacityUnits, now);
    }

    /**
     * Attempts to reserve the requested amount of seek work.
     *
     * @param owner canonical authenticated owner
     * @param filmId canonical film id
     * @param workSteps complexity-weighted work units reserved for the
     *                  {@code ActionPlayer.goTo} traversal
     */
    public synchronized boolean tryConsume(UUID owner, String filmId, long workSteps)
    {
        return this.tryConsume(owner, LEGACY_CONNECTION_IDENTITY, filmId, workSteps);
    }

    /**
     * Attempts to reserve seek work for one concrete connection. The
     * connection token is compared by identity ({@code ==}), never by
     * {@link Object#equals(Object)}.
     */
    public synchronized boolean tryConsume(UUID owner, Object connectionIdentity, String filmId, long workSteps)
    {
        if (owner == null
            || connectionIdentity == null
            || filmId == null
            || filmId.isBlank()
            || workSteps < 0L
            || workSteps > this.limits.capacitySteps)
        {
            return false;
        }

        if (workSteps == 0L)
        {
            return true;
        }

        long now = this.nanoClock.getAsLong();
        ConnectionKey key = new ConnectionKey(owner, connectionIdentity);

        this.refill(this.global, now, this.globalCapacityUnits, this.limits.globalRefillSteps);

        long costUnits = workSteps * this.limits.refillPeriodNanos;

        if (this.global.creditUnits < costUnits)
        {
            return false;
        }

        ConnectionState connectionState = this.connections.get(key);
        Bucket filmBucket = connectionState == null ? null : connectionState.films.get(filmId);

        if (connectionState == null || filmBucket == null)
        {
            this.expireIdle(now);
            connectionState = this.connections.get(key);
            filmBucket = connectionState == null ? null : connectionState.films.get(filmId);
        }

        boolean createdConnection = false;

        if (connectionState == null)
        {
            if (this.filmBuckets >= this.limits.globalFilmBuckets)
            {
                return false;
            }

            connectionState = new ConnectionState(new Bucket(this.capacityUnits, now));
            this.connections.put(key, connectionState);
            createdConnection = true;
        }

        this.refill(connectionState.aggregate, now, this.capacityUnits, this.limits.refillSteps);

        if (connectionState.aggregate.creditUnits < costUnits)
        {
            return false;
        }

        if (filmBucket == null)
        {
            if (connectionState.films.size() >= this.limits.ownerFilmBuckets
                || this.filmBuckets >= this.limits.globalFilmBuckets)
            {
                if (createdConnection)
                {
                    this.connections.remove(key);
                }

                return false;
            }

            filmBucket = new Bucket(this.capacityUnits, now);
            connectionState.films.put(filmId, filmBucket);
            this.filmBuckets += 1;
        }
        else
        {
            this.refill(filmBucket, now, this.capacityUnits, this.limits.refillSteps);
        }

        if (filmBucket.creditUnits < costUnits)
        {
            return false;
        }

        connectionState.aggregate.creditUnits -= costUnits;
        filmBucket.creditUnits -= costUnits;
        this.global.creditUnits -= costUnits;

        return true;
    }

    /** Removes seek state owned by one concrete disconnected connection. */
    public synchronized int clearConnection(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return 0;
        }

        ConnectionState removed = this.connections.remove(new ConnectionKey(owner, connectionIdentity));

        if (removed == null)
        {
            return 0;
        }

        int amount = removed.films.size();

        this.filmBuckets -= amount;

        return amount;
    }

    /** Removes all seek state across every connection for one owner. */
    public synchronized int clearOwner(UUID owner)
    {
        if (owner == null)
        {
            return 0;
        }

        int amount = 0;
        Iterator<Map.Entry<ConnectionKey, ConnectionState>> iterator = this.connections.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<ConnectionKey, ConnectionState> entry = iterator.next();

            if (entry.getKey().owner.equals(owner))
            {
                amount += entry.getValue().films.size();
                iterator.remove();
            }
        }

        this.filmBuckets -= amount;

        return amount;
    }

    /** Removes buckets that have received no request during the idle window. */
    public synchronized int expireIdle()
    {
        return this.expireIdle(this.nanoClock.getAsLong());
    }

    public synchronized void reset()
    {
        this.connections.clear();
        this.filmBuckets = 0;

        long now = this.nanoClock.getAsLong();

        this.global.creditUnits = this.globalCapacityUnits;
        this.global.lastRefillNanos = now;
        this.global.lastActivityNanos = now;
    }

    synchronized int size()
    {
        return this.filmBuckets;
    }

    private int expireIdle(long now)
    {
        int removed = 0;
        Iterator<Map.Entry<ConnectionKey, ConnectionState>> connectionIterator = this.connections.entrySet().iterator();

        while (connectionIterator.hasNext())
        {
            ConnectionState connectionState = connectionIterator.next().getValue();
            Iterator<Bucket> filmIterator = connectionState.films.values().iterator();

            while (filmIterator.hasNext())
            {
                Bucket bucket = filmIterator.next();

                if (hasElapsed(now, bucket.lastActivityNanos, this.limits.idleTimeoutNanos))
                {
                    filmIterator.remove();
                    this.filmBuckets -= 1;
                    removed += 1;
                }
            }

            if (connectionState.films.isEmpty()
                && hasElapsed(now, connectionState.aggregate.lastActivityNanos, this.limits.idleTimeoutNanos))
            {
                connectionIterator.remove();
            }
        }

        return removed;
    }

    private void refill(Bucket bucket, long now, long maximumCreditUnits, long refillSteps)
    {
        long elapsed = now - bucket.lastRefillNanos;

        if (elapsed < 0L)
        {
            /* Do not move the baseline backwards: doing so would count the
             * recovery interval twice once a test or injected clock catches up. */
            return;
        }

        bucket.lastRefillNanos = now;
        bucket.lastActivityNanos = now;

        if (elapsed == 0L || bucket.creditUnits >= maximumCreditUnits)
        {
            return;
        }

        long missing = maximumCreditUnits - bucket.creditUnits;
        long complete = missing / refillSteps;
        long remainder = missing % refillSteps;

        if (elapsed > complete || (elapsed == complete && remainder == 0L))
        {
            bucket.creditUnits = maximumCreditUnits;
        }
        else
        {
            /* This multiplication cannot overflow: this branch proves that
             * elapsed * refillSteps is no greater than the missing credit. */
            bucket.creditUnits += elapsed * refillSteps;
        }
    }

    private static boolean hasElapsed(long now, long then, long duration)
    {
        long elapsed = now - then;

        /* A negative delta represents a test-clock rollback (or an interval
         * beyond nanoTime's supported comparison window), so fail closed and
         * retain the bucket instead of granting fresh credit. */
        return elapsed >= 0L && elapsed >= duration;
    }

    private static final class ConnectionState
    {
        private final Bucket aggregate;
        private final Map<String, Bucket> films = new HashMap<>();

        private ConnectionState(Bucket aggregate)
        {
            this.aggregate = aggregate;
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

    private static final class Bucket
    {
        private long creditUnits;
        private long lastRefillNanos;
        private long lastActivityNanos;

        private Bucket(long creditUnits, long now)
        {
            this.creditUnits = creditUnits;
            this.lastRefillNanos = now;
            this.lastActivityNanos = now;
        }
    }

    static final class Limits
    {
        private final long capacitySteps;
        private final long refillSteps;
        private final long globalCapacitySteps;
        private final long globalRefillSteps;
        private final long refillPeriodNanos;
        private final long idleTimeoutNanos;
        private final int ownerFilmBuckets;
        private final int globalFilmBuckets;

        Limits(
            long capacitySteps,
            long refillSteps,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int ownerFilmBuckets,
            int globalFilmBuckets
        )
        {
            this(
                capacitySteps,
                refillSteps,
                Math.multiplyExact(capacitySteps, 2L),
                Math.multiplyExact(refillSteps, 2L),
                refillPeriodNanos,
                idleTimeoutNanos,
                ownerFilmBuckets,
                globalFilmBuckets
            );
        }

        Limits(
            long capacitySteps,
            long refillSteps,
            long globalCapacitySteps,
            long globalRefillSteps,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int ownerFilmBuckets,
            int globalFilmBuckets
        )
        {
            if (capacitySteps <= 0L
                || refillSteps <= 0L
                || globalCapacitySteps <= 0L
                || globalRefillSteps <= 0L
                || refillPeriodNanos <= 0L
                || idleTimeoutNanos <= 0L)
            {
                throw new IllegalArgumentException("Seek budget limits must be positive");
            }

            if (capacitySteps > Long.MAX_VALUE / refillPeriodNanos)
            {
                throw new IllegalArgumentException("Seek budget capacity exceeds fixed-point range");
            }

            if (globalCapacitySteps > Long.MAX_VALUE / refillPeriodNanos)
            {
                throw new IllegalArgumentException("Global seek budget capacity exceeds fixed-point range");
            }

            if (ownerFilmBuckets <= 0 || globalFilmBuckets < ownerFilmBuckets)
            {
                throw new IllegalArgumentException("Seek bucket limits must be positive and globally inclusive");
            }

            this.capacitySteps = capacitySteps;
            this.refillSteps = refillSteps;
            this.globalCapacitySteps = globalCapacitySteps;
            this.globalRefillSteps = globalRefillSteps;
            this.refillPeriodNanos = refillPeriodNanos;
            this.idleTimeoutNanos = idleTimeoutNanos;
            this.ownerFilmBuckets = ownerFilmBuckets;
            this.globalFilmBuckets = globalFilmBuckets;
        }
    }
}
