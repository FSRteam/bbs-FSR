package mchorse.bbs_mod.network;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Exact-connection token budgets for small C2S actions that can otherwise
 * amplify into world work or tracking broadcasts.
 */
final class NetworkDirectActionGate
{
    static final int MAX_ANIMATION_TRIGGER_LENGTH = 256;

    private static final Limits DEFAULT_LIMITS = new Limits(
        new ChannelLimit(8L, 8L),
        new ChannelLimit(64L, 64L),
        TimeUnit.SECONDS.toNanos(1L),
        TimeUnit.MINUTES.toNanos(2L),
        4_096
    );

    private final Map<ConnectionKey, ConnectionState> connections = new HashMap<>();
    private final LongSupplier nanoClock;
    private final Limits limits;

    NetworkDirectActionGate()
    {
        this(System::nanoTime, DEFAULT_LIMITS);
    }

    NetworkDirectActionGate(LongSupplier nanoClock, Limits limits)
    {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public synchronized boolean tryAcquire(UUID owner, Object connectionIdentity, Channel channel)
    {
        if (owner == null || connectionIdentity == null || channel == null)
        {
            return false;
        }

        long now = this.nanoClock.getAsLong();
        ConnectionKey key = new ConnectionKey(owner, connectionIdentity);
        ConnectionState connection = this.connections.get(key);

        if (connection == null)
        {
            this.expireIdle(now);

            if (this.connections.size() >= this.limits.maxConnections)
            {
                return false;
            }

            connection = new ConnectionState(now);
            this.connections.put(key, connection);
        }

        if (now - connection.lastActivityNanos >= 0L)
        {
            connection.lastActivityNanos = now;
        }

        ChannelLimit channelLimit = this.limits.limit(channel);
        Bucket bucket = connection.buckets.get(channel);

        if (bucket == null)
        {
            bucket = new Bucket(channelLimit.capacityUnits(this.limits.refillPeriodNanos), now);
            connection.buckets.put(channel, bucket);
        }
        else
        {
            this.refill(bucket, channelLimit, now);
        }

        long cost = this.limits.refillPeriodNanos;

        if (bucket.creditUnits < cost)
        {
            return false;
        }

        bucket.creditUnits -= cost;

        return true;
    }

    /** Remove only the buckets owned by one concrete disconnected connection. */
    public synchronized int clearConnection(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return 0;
        }

        ConnectionState removed = this.connections.remove(new ConnectionKey(owner, connectionIdentity));

        return removed == null ? 0 : removed.buckets.size();
    }

    public synchronized int expireIdle()
    {
        return this.expireIdle(this.nanoClock.getAsLong());
    }

    public synchronized void reset()
    {
        this.connections.clear();
    }

    synchronized int size()
    {
        return this.connections.size();
    }

    synchronized int bucketCount()
    {
        int count = 0;

        for (ConnectionState connection : this.connections.values())
        {
            count += connection.buckets.size();
        }

        return count;
    }

    static boolean isAnimationTriggerAllowed(String trigger)
    {
        if (trigger == null
            || trigger.length() > MAX_ANIMATION_TRIGGER_LENGTH
            || trigger.getBytes(StandardCharsets.UTF_8).length > MAX_ANIMATION_TRIGGER_LENGTH)
        {
            return false;
        }

        for (int i = 0; i < trigger.length(); i++)
        {
            char character = trigger.charAt(i);

            if (character == '\0' || character == '\r' || character == '\n')
            {
                return false;
            }
        }

        return true;
    }

    private void refill(Bucket bucket, ChannelLimit limit, long now)
    {
        long elapsed = now - bucket.lastRefillNanos;

        if (elapsed < 0L)
        {
            return;
        }

        bucket.lastRefillNanos = now;

        long capacity = limit.capacityUnits(this.limits.refillPeriodNanos);

        if (elapsed == 0L || bucket.creditUnits >= capacity)
        {
            return;
        }

        long missing = capacity - bucket.creditUnits;
        long complete = missing / limit.refillTokens;
        long remainder = missing % limit.refillTokens;

        if (elapsed > complete || (elapsed == complete && remainder == 0L))
        {
            bucket.creditUnits = capacity;
        }
        else
        {
            bucket.creditUnits += elapsed * limit.refillTokens;
        }
    }

    private int expireIdle(long now)
    {
        int removed = 0;
        Iterator<ConnectionState> iterator = this.connections.values().iterator();

        while (iterator.hasNext())
        {
            ConnectionState connection = iterator.next();

            if (hasElapsed(now, connection.lastActivityNanos, this.limits.idleTimeoutNanos))
            {
                iterator.remove();
                removed += 1;
            }
        }

        return removed;
    }

    private static boolean hasElapsed(long now, long then, long duration)
    {
        long elapsed = now - then;

        return elapsed >= 0L && elapsed >= duration;
    }

    enum Channel
    {
        TELEPORT,
        ANIMATION_TRIGGER,
        PAUSE_FILM,
        FILM_START,
        RECORDING_START,
        SHARE_FORM
    }

    static final class Limits
    {
        private final EnumMap<Channel, ChannelLimit> channels = new EnumMap<>(Channel.class);
        private final long refillPeriodNanos;
        private final long idleTimeoutNanos;
        private final int maxConnections;

        Limits(
            ChannelLimit teleport,
            ChannelLimit animationTrigger,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int maxConnections
        )
        {
            this(
                teleport,
                animationTrigger,
                new ChannelLimit(8L, 8L),
                new ChannelLimit(2L, 1L),
                new ChannelLimit(2L, 1L),
                refillPeriodNanos,
                idleTimeoutNanos,
                maxConnections
            );
        }

        Limits(
            ChannelLimit teleport,
            ChannelLimit animationTrigger,
            ChannelLimit pauseFilm,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int maxConnections
        )
        {
            this(
                teleport,
                animationTrigger,
                pauseFilm,
                new ChannelLimit(2L, 1L),
                new ChannelLimit(2L, 1L),
                new ChannelLimit(4L, 1L),
                refillPeriodNanos,
                idleTimeoutNanos,
                maxConnections
            );
        }

        Limits(
            ChannelLimit teleport,
            ChannelLimit animationTrigger,
            ChannelLimit pauseFilm,
            ChannelLimit filmStart,
            ChannelLimit recordingStart,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int maxConnections
        )
        {
            this(
                teleport,
                animationTrigger,
                pauseFilm,
                filmStart,
                recordingStart,
                new ChannelLimit(4L, 1L),
                refillPeriodNanos,
                idleTimeoutNanos,
                maxConnections
            );
        }

        Limits(
            ChannelLimit teleport,
            ChannelLimit animationTrigger,
            ChannelLimit pauseFilm,
            ChannelLimit filmStart,
            ChannelLimit recordingStart,
            ChannelLimit shareForm,
            long refillPeriodNanos,
            long idleTimeoutNanos,
            int maxConnections
        )
        {
            if (teleport == null
                || animationTrigger == null
                || pauseFilm == null
                || filmStart == null
                || recordingStart == null
                || shareForm == null
                || refillPeriodNanos <= 0L
                || idleTimeoutNanos <= 0L
                || maxConnections <= 0)
            {
                throw new IllegalArgumentException("Direct-action limits must be positive");
            }

            teleport.validate(refillPeriodNanos);
            animationTrigger.validate(refillPeriodNanos);
            pauseFilm.validate(refillPeriodNanos);
            filmStart.validate(refillPeriodNanos);
            recordingStart.validate(refillPeriodNanos);
            shareForm.validate(refillPeriodNanos);

            this.channels.put(Channel.TELEPORT, teleport);
            this.channels.put(Channel.ANIMATION_TRIGGER, animationTrigger);
            this.channels.put(Channel.PAUSE_FILM, pauseFilm);
            this.channels.put(Channel.FILM_START, filmStart);
            this.channels.put(Channel.RECORDING_START, recordingStart);
            this.channels.put(Channel.SHARE_FORM, shareForm);
            this.refillPeriodNanos = refillPeriodNanos;
            this.idleTimeoutNanos = idleTimeoutNanos;
            this.maxConnections = maxConnections;
        }

        private ChannelLimit limit(Channel channel)
        {
            return this.channels.get(channel);
        }
    }

    static final class ChannelLimit
    {
        private final long capacityTokens;
        private final long refillTokens;

        ChannelLimit(long capacityTokens, long refillTokens)
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
                throw new IllegalArgumentException("Channel token limits exceed the fixed-point range");
            }
        }

        private long capacityUnits(long refillPeriodNanos)
        {
            return this.capacityTokens * refillPeriodNanos;
        }
    }

    private static final class ConnectionState
    {
        private final EnumMap<Channel, Bucket> buckets = new EnumMap<>(Channel.class);
        private long lastActivityNanos;

        private ConnectionState(long now)
        {
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
