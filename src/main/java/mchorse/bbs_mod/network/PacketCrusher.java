package mchorse.bbs_mod.network;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public abstract class PacketCrusher
{
    public static final int BUFFER_SIZE = 30_000;
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    private static final Object LEGACY_CONNECTION_IDENTITY = new Object();
    private static final int HEADER_BYTES = Integer.BYTES * 4;
    static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;
    static final int DROP_WARNING_BURST = 8;
    static final long DROP_WARNING_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final Limits DEFAULT_LIMITS = new Limits(
        8,
        64,
        32L * 1024L * 1024L,
        64L * 1024L * 1024L,
        TimeUnit.SECONDS.toNanos(30L)
    );

    private final Object transferLock = new Object();
    private final Map<TransferKey, TransferState> chunks = new HashMap<>();
    private final Map<UUID, OwnerState> owners = new HashMap<>();
    private final LongSupplier nanoClock;
    private final Limits limits;
    private final WarningLimiter warningLimiter = new WarningLimiter(DROP_WARNING_BURST, DROP_WARNING_WINDOW_NANOS);
    private long inFlightBytes;
    private int counter;

    public PacketCrusher()
    {
        this(System::nanoTime, DEFAULT_LIMITS);
    }

    PacketCrusher(LongSupplier nanoClock, Limits limits)
    {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public void reset()
    {
        synchronized (this.transferLock)
        {
            this.chunks.clear();
            this.owners.clear();
            this.inFlightBytes = 0L;
            this.counter = 0;
            this.warningLimiter.reset();
        }
    }

    /**
     * Remove every incomplete transfer owned by a disconnected peer.
     *
     * @return how many transfers were removed
     */
    public int clearOwner(UUID owner)
    {
        if (owner == null)
        {
            return 0;
        }

        synchronized (this.transferLock)
        {
            int removed = 0;
            Iterator<Map.Entry<TransferKey, TransferState>> iterator = this.chunks.entrySet().iterator();

            while (iterator.hasNext())
            {
                Map.Entry<TransferKey, TransferState> entry = iterator.next();

                if (entry.getKey().owner.equals(owner))
                {
                    iterator.remove();
                    this.releaseState(entry.getKey(), entry.getValue());
                    removed += 1;
                }
            }

            return removed;
        }
    }

    /**
     * Remove incomplete transfers queued by one concrete connection while
     * retaining transfers started by a replacement connection with the same
     * authenticated UUID.
     */
    public int clearConnection(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return 0;
        }

        synchronized (this.transferLock)
        {
            int removed = 0;
            Iterator<Map.Entry<TransferKey, TransferState>> iterator = this.chunks.entrySet().iterator();

            while (iterator.hasNext())
            {
                Map.Entry<TransferKey, TransferState> entry = iterator.next();
                TransferKey key = entry.getKey();

                if (key.owner.equals(owner) && key.connectionIdentity == connectionIdentity)
                {
                    iterator.remove();
                    this.releaseState(key, entry.getValue());
                    removed += 1;
                }
            }

            return removed;
        }
    }

    /**
     * Remove transfers that have not received a valid chunk within the idle window.
     * Receive paths invoke this automatically; the method is also available to a
     * server tick or lifecycle owner that wants eager reclamation during silence.
     *
     * @return how many transfers were removed
     */
    public int expireIdleTransfers()
    {
        synchronized (this.transferLock)
        {
            return this.expireIdleTransfers(this.nanoClock.getAsLong());
        }
    }

    /**
     * Legacy public ABI retained for addons compiled before transfers were
     * scoped to an authenticated owner, connection, and channel. Unscoped
     * frames cannot be safely reassembled, so this entry point fails closed.
     */
    @Deprecated(forRemoval = false)
    public void receive(FriendlyByteBuf buf, IBufferReceiver receiver)
    {
        WarningDecision warning = this.nextWarningDecision();

        if (warning == WarningDecision.DETAIL)
        {
            LOGGER.warn("[BBS-SEM] topic=net.crusher phase=receive result=drop reason=legacy_unscoped_receiver");
        }
        else if (warning == WarningDecision.LIMIT_NOTICE)
        {
            this.logWarningLimitNotice();
        }
    }

    /**
     * Value-scoped compatibility overload used by client connection-generation
     * UUIDs. Server callers with a concrete connection object must use the
     * identity-scoped overload below.
     */
    public void receive(UUID owner, ResourceLocation channel, FriendlyByteBuf buf, IBufferReceiver receiver)
    {
        this.receive(owner, LEGACY_CONNECTION_IDENTITY, channel, buf, receiver);
    }

    /** The connection identity is compared by {@code ==}, never {@code equals}. */
    public void receive(
        UUID owner,
        Object connectionIdentity,
        ResourceLocation channel,
        FriendlyByteBuf buf,
        IBufferReceiver receiver
    )
    {
        if (owner == null || connectionIdentity == null || channel == null || buf == null || receiver == null)
        {
            WarningDecision warning = this.nextWarningDecision();

            if (warning == WarningDecision.DETAIL)
            {
                LOGGER.warn("[BBS-SEM] topic=net.crusher phase=receive result=drop reason=missing_scope_or_input");
            }
            else if (warning == WarningDecision.LIMIT_NOTICE)
            {
                this.logWarningLimitNotice();
            }

            return;
        }

        this.expireIdleTransfers();

        if (buf.readableBytes() < HEADER_BYTES)
        {
            WarningDecision warning = this.nextWarningDecision();

            if (warning == WarningDecision.DETAIL)
            {
                LOGGER.warn("[BBS-SEM] topic=net.crusher phase=receive result=drop reason=short_header channel={} readable={}",
                    channel,
                    buf.readableBytes());
            }
            else if (warning == WarningDecision.LIMIT_NOTICE)
            {
                this.logWarningLimitNotice();
            }

            return;
        }

        int id = buf.readInt();
        int index = buf.readInt();
        int total = buf.readInt();
        int size = buf.readInt();
        boolean finalChunk = index == total - 1;
        TransferKey key = new TransferKey(owner, connectionIdentity, channel, id);

        if (!this.validateFrameHeader(key, index, total, size, buf.readableBytes()))
        {
            return;
        }

        if (!finalChunk && size != BUFFER_SIZE)
        {
            this.dropTransfer(key, "non_final_size", index, total, size, buf.readableBytes());
            return;
        }

        if (!finalChunk && size != buf.readableBytes())
        {
            this.dropTransfer(key, "trailing_bytes", index, total, size, buf.readableBytes());
            return;
        }

        byte[] finalBytes = null;
        boolean complete = false;

        synchronized (this.transferLock)
        {
            if (connectionIdentity instanceof NetworkConnectionGate.RetirementAwareConnectionIdentity identity
                && identity.isRetired())
            {
                this.dropTransfer(key, "retired_connection", index, total, size, buf.readableBytes());
                return;
            }

            TransferState state = this.chunks.get(key);

            if (state == null)
            {
                if (index != 0)
                {
                    this.dropTransfer(key, "missing_start", index, total, size, buf.readableBytes());
                    return;
                }

                String capacityReason = this.transferCapacityReason(owner);

                if (capacityReason != null)
                {
                    this.dropTransfer(key, capacityReason, index, total, size, buf.readableBytes());
                    return;
                }

                state = new TransferState(total, this.nanoClock.getAsLong());
                this.chunks.put(key, state);
                this.acquireState(owner);
            }
            else if (index == 0)
            {
                this.dropTransfer(key, "duplicate_start", index, total, size, buf.readableBytes());
                return;
            }

            if (state.total != total)
            {
                this.dropTransfer(key, "total_changed", index, total, size, buf.readableBytes());
                return;
            }

            if (state.seen.get(index))
            {
                this.dropTransfer(key, "duplicate_chunk", index, total, size, buf.readableBytes());
                return;
            }

            if (index != state.nextIndex)
            {
                this.dropTransfer(key, "out_of_order", index, total, size, buf.readableBytes());
                return;
            }

            if (!state.canAccept(size))
            {
                this.dropTransfer(key, "transfer_capacity", index, total, size, buf.readableBytes());
                return;
            }

            String byteCapacityReason = this.byteCapacityReason(owner, size);

            if (byteCapacityReason != null)
            {
                this.dropTransfer(key, byteCapacityReason, index, total, size, buf.readableBytes());
                return;
            }

            byte[] bytes = new byte[size];
            buf.readBytes(bytes);

            state.write(bytes, index, this.nanoClock.getAsLong());
            this.acquireBytes(owner, size);

            if (finalChunk)
            {
                finalBytes = state.bytes.toByteArray();
                complete = true;
                this.removeState(key);
            }
        }

        if (complete)
        {
            if (finalBytes.length == 1 && finalBytes[0] == 69)
            {
                finalBytes = null;
            }

            receiver.receiveBuffer(finalBytes, buf);
        }
    }

    private boolean validateFrameHeader(TransferKey key, int index, int total, int size, int readableBytes)
    {
        if (total <= 0)
        {
            this.dropTransfer(key, "invalid_total", index, total, size, readableBytes);
            return false;
        }

        if (index < 0 || index >= total)
        {
            this.dropTransfer(key, "invalid_index", index, total, size, readableBytes);
            return false;
        }

        if (size <= 0 || size > BUFFER_SIZE)
        {
            this.dropTransfer(key, "invalid_size", index, total, size, readableBytes);
            return false;
        }

        if (size > readableBytes)
        {
            this.dropTransfer(key, "truncated_chunk", index, total, size, readableBytes);
            return false;
        }

        long minimumBytes = ((long) total - 1L) * (long) BUFFER_SIZE + 1L;

        if (minimumBytes <= 0L || minimumBytes > MAX_TRANSFER_BYTES)
        {
            this.dropTransfer(key, "declared_capacity", index, total, size, readableBytes);
            return false;
        }

        return true;
    }

    private String transferCapacityReason(UUID owner)
    {
        if (this.chunks.size() >= this.limits.globalTransfers)
        {
            return "global_transfer_capacity";
        }

        OwnerState ownerState = this.owners.get(owner);

        return ownerState != null && ownerState.transfers >= this.limits.ownerTransfers
            ? "owner_transfer_capacity"
            : null;
    }

    private String byteCapacityReason(UUID owner, int size)
    {
        if (this.inFlightBytes > this.limits.globalBytes - size)
        {
            return "global_byte_capacity";
        }

        OwnerState ownerState = this.owners.get(owner);
        long ownerBytes = ownerState == null ? 0L : ownerState.bytes;

        return ownerBytes > this.limits.ownerBytes - size ? "owner_byte_capacity" : null;
    }

    private void acquireState(UUID owner)
    {
        this.owners.computeIfAbsent(owner, (key) -> new OwnerState()).transfers += 1;
    }

    private void acquireBytes(UUID owner, int size)
    {
        this.inFlightBytes += size;
        this.owners.get(owner).bytes += size;
    }

    private void removeState(TransferKey key)
    {
        TransferState state = this.chunks.remove(key);

        if (state != null)
        {
            this.releaseState(key, state);
        }
    }

    private void releaseState(TransferKey key, TransferState state)
    {
        OwnerState ownerState = this.owners.get(key.owner);

        this.inFlightBytes -= state.written;

        if (ownerState != null)
        {
            ownerState.bytes -= state.written;
            ownerState.transfers -= 1;

            if (ownerState.transfers == 0)
            {
                this.owners.remove(key.owner);
            }
        }
    }

    private int expireIdleTransfers(long now)
    {
        int removed = 0;
        Iterator<Map.Entry<TransferKey, TransferState>> iterator = this.chunks.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<TransferKey, TransferState> entry = iterator.next();

            if (now - entry.getValue().lastActivityNanos >= this.limits.timeoutNanos)
            {
                iterator.remove();
                this.releaseState(entry.getKey(), entry.getValue());
                removed += 1;
            }
        }

        if (removed > 0)
        {
            WarningDecision warning = this.nextWarningDecision();

            if (warning == WarningDecision.DETAIL)
            {
                LOGGER.warn("[BBS-SEM] topic=net.crusher phase=expire result=drop reason=idle_timeout transfers={}", removed);
            }
            else if (warning == WarningDecision.LIMIT_NOTICE)
            {
                this.logWarningLimitNotice();
            }
        }

        return removed;
    }

    private void dropTransfer(TransferKey key, String reason, int index, int total, int size, int readableBytes)
    {
        synchronized (this.transferLock)
        {
            this.removeState(key);
        }

        WarningDecision warning = this.nextWarningDecision();

        if (warning == WarningDecision.DETAIL)
        {
            LOGGER.warn("[BBS-SEM] topic=net.crusher phase=receive result=drop reason={} channel={} id={} index={} total={} size={} readable={}",
                reason,
                key.channel,
                key.id,
                index,
                total,
                size,
                readableBytes);
        }
        else if (warning == WarningDecision.LIMIT_NOTICE)
        {
            this.logWarningLimitNotice();
        }
    }

    private WarningDecision nextWarningDecision()
    {
        return this.warningLimiter.acquire(this.nanoClock.getAsLong());
    }

    private void logWarningLimitNotice()
    {
        LOGGER.warn("[BBS-SEM] topic=net.crusher phase=diagnostic result=rate_limited detail_limit={} window_seconds={}",
            DROP_WARNING_BURST,
            TimeUnit.NANOSECONDS.toSeconds(DROP_WARNING_WINDOW_NANOS));
    }

    public void send(Player entity, ResourceLocation identifier, BaseType baseType, Consumer<FriendlyByteBuf> consumer)
    {
        this.send(Collections.singleton(entity), identifier, baseType, consumer);
    }

    public void send(Player entity, ResourceLocation identifier, byte[] bytes, Consumer<FriendlyByteBuf> consumer)
    {
        this.send(Collections.singleton(entity), identifier, bytes, consumer);
    }

    public void send(Collection<Player> entities, ResourceLocation identifier, BaseType baseType, Consumer<FriendlyByteBuf> consumer)
    {
        this.send(entities, identifier, DataStorageUtils.writeToBytes(baseType), consumer);
    }

    public void send(Collection<Player> entities, ResourceLocation identifier, byte[] bytes, Consumer<FriendlyByteBuf> consumer)
    {
        this.sendChunked(bytes, consumer, (buf) ->
        {
            for (Player playerEntity : entities)
            {
                this.sendBuffer(playerEntity, identifier, buf);
            }
        });
    }

    public void sendToPlayersTrackingEntity(Entity entity, ResourceLocation identifier, BaseType baseType, Consumer<FriendlyByteBuf> consumer)
    {
        this.sendToPlayersTrackingEntity(entity, identifier, DataStorageUtils.writeToBytes(baseType), consumer);
    }

    public void sendToPlayersTrackingEntity(Entity entity, ResourceLocation identifier, byte[] bytes, Consumer<FriendlyByteBuf> consumer)
    {
        this.sendChunked(bytes, consumer, (buf) -> NetworkCompat.sendToPlayersTrackingEntity(entity, identifier, buf));
    }

    public void sendToPlayersTrackingEntityAndSelf(ServerPlayer player, ResourceLocation identifier, BaseType baseType, Consumer<FriendlyByteBuf> consumer)
    {
        this.sendToPlayersTrackingEntityAndSelf(player, identifier, DataStorageUtils.writeToBytes(baseType), consumer);
    }

    public void sendToPlayersTrackingEntityAndSelf(ServerPlayer player, ResourceLocation identifier, byte[] bytes, Consumer<FriendlyByteBuf> consumer)
    {
        this.sendChunked(bytes, consumer, (buf) -> NetworkCompat.sendToPlayersTrackingEntityAndSelf(player, identifier, buf));
    }

    private void sendChunked(byte[] bytes, Consumer<FriendlyByteBuf> consumer, Consumer<FriendlyByteBuf> sender)
    {
        byte[] chunkBytes = Objects.requireNonNull(bytes, "bytes");

        if (chunkBytes.length > MAX_TRANSFER_BYTES)
        {
            throw new IllegalArgumentException("Packet crusher payload exceeds " + MAX_TRANSFER_BYTES + " bytes");
        }

        if (chunkBytes.length == 0)
        {
            chunkBytes = new byte[]{69};
        }

        /* Integer arithmetic keeps the wire count exact at the 16 MiB limit.
         * A floating-point ceil can round a value just above an integral
         * boundary down, producing a negative final chunk or an advertised
         * count that does not cover the payload. */
        int total = Math.max((chunkBytes.length + BUFFER_SIZE - 1) / BUFFER_SIZE, 1);
        int transferId;

        synchronized (this.transferLock)
        {
            transferId = this.counter;
            this.counter += 1;
        }

        for (int index = 0; index < total; index++)
        {
            int offset = index * BUFFER_SIZE;
            int size = Math.min(BUFFER_SIZE, chunkBytes.length - offset);
            FriendlyByteBuf buf = NetworkCompat.createBuffer();

            buf.writeInt(transferId);
            buf.writeInt(index);
            buf.writeInt(total);
            buf.writeInt(size);
            buf.writeBytes(chunkBytes, offset, size);

            if (consumer != null && index == total - 1)
            {
                consumer.accept(buf);
            }

            sender.accept(buf);
        }
    }

    protected abstract void sendBuffer(Player entity, ResourceLocation identifier, FriendlyByteBuf buf);

    private static final class TransferState
    {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(BUFFER_SIZE);
        private final BitSet seen = new BitSet();
        private final int total;
        private int nextIndex;
        private int written;
        private long lastActivityNanos;

        private TransferState(int total, long lastActivityNanos)
        {
            this.total = total;
            this.lastActivityNanos = lastActivityNanos;
        }

        private boolean canAccept(int size)
        {
            return this.written <= MAX_TRANSFER_BYTES - size;
        }

        private void write(byte[] bytes, int index, long now)
        {
            this.bytes.writeBytes(bytes);
            this.seen.set(index);
            this.nextIndex += 1;
            this.written += bytes.length;
            this.lastActivityNanos = now;
        }
    }

    private static final class OwnerState
    {
        private int transfers;
        private long bytes;
    }

    private static final class TransferKey
    {
        private final UUID owner;
        private final Object connectionIdentity;
        private final ResourceLocation channel;
        private final int id;

        private TransferKey(UUID owner, Object connectionIdentity, ResourceLocation channel, int id)
        {
            this.owner = owner;
            this.connectionIdentity = connectionIdentity;
            this.channel = channel;
            this.id = id;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof TransferKey other))
            {
                return false;
            }

            return this.id == other.id
                && this.connectionIdentity == other.connectionIdentity
                && this.owner.equals(other.owner)
                && this.channel.equals(other.channel);
        }

        @Override
        public int hashCode()
        {
            int result = this.owner.hashCode();

            result = 31 * result + System.identityHashCode(this.connectionIdentity);
            result = 31 * result + this.channel.hashCode();
            result = 31 * result + this.id;

            return result;
        }
    }

    static final class Limits
    {
        private final int ownerTransfers;
        private final int globalTransfers;
        private final long ownerBytes;
        private final long globalBytes;
        private final long timeoutNanos;

        Limits(int ownerTransfers, int globalTransfers, long ownerBytes, long globalBytes, long timeoutNanos)
        {
            if (ownerTransfers <= 0 || globalTransfers < ownerTransfers)
            {
                throw new IllegalArgumentException("Transfer limits must be positive and globally inclusive");
            }

            if (ownerBytes < BUFFER_SIZE || globalBytes < ownerBytes)
            {
                throw new IllegalArgumentException("Byte limits must fit one full chunk and be globally inclusive");
            }

            if (timeoutNanos <= 0L)
            {
                throw new IllegalArgumentException("Timeout must be positive");
            }

            this.ownerTransfers = ownerTransfers;
            this.globalTransfers = globalTransfers;
            this.ownerBytes = ownerBytes;
            this.globalBytes = globalBytes;
            this.timeoutNanos = timeoutNanos;
        }
    }

    enum WarningDecision
    {
        DETAIL,
        LIMIT_NOTICE,
        SUPPRESS
    }

    static final class WarningLimiter
    {
        private final int burst;
        private final long windowNanos;
        private boolean initialized;
        private long windowStartNanos;
        private int detailed;
        private boolean limitNoticeIssued;

        WarningLimiter(int burst, long windowNanos)
        {
            if (burst <= 0 || windowNanos <= 0L)
            {
                throw new IllegalArgumentException("Warning limiter bounds must be positive");
            }

            this.burst = burst;
            this.windowNanos = windowNanos;
        }

        synchronized WarningDecision acquire(long now)
        {
            long elapsed = now - this.windowStartNanos;

            if (!this.initialized || elapsed < 0L || elapsed >= this.windowNanos)
            {
                this.initialized = true;
                this.windowStartNanos = now;
                this.detailed = 0;
                this.limitNoticeIssued = false;
            }

            if (this.detailed < this.burst)
            {
                this.detailed += 1;

                return WarningDecision.DETAIL;
            }

            if (!this.limitNoticeIssued)
            {
                this.limitNoticeIssued = true;

                return WarningDecision.LIMIT_NOTICE;
            }

            return WarningDecision.SUPPRESS;
        }

        synchronized void reset()
        {
            this.initialized = false;
            this.windowStartNanos = 0L;
            this.detailed = 0;
            this.limitNoticeIssued = false;
        }
    }
}
