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
import java.util.Map;
import java.util.function.Consumer;

public abstract class PacketCrusher
{
    public static final int BUFFER_SIZE = 30_000;
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    private static final int HEADER_BYTES = Integer.BYTES * 4;
    private static final int MAX_TRANSFER_BYTES = 16 * 1024 * 1024;

    private Map<Integer, TransferState> chunks = new HashMap<>();
    private int counter;

    public void reset()
    {
        this.chunks.clear();
        this.counter = 0;
    }

    public void receive(FriendlyByteBuf buf, IBufferReceiver receiver)
    {
        if (buf == null || receiver == null)
        {
            return;
        }

        if (buf.readableBytes() < HEADER_BYTES)
        {
            LOGGER.warn("[BBS-SEM] topic=net.crusher phase=receive result=drop reason=short_header readable={}",
                buf.readableBytes());
            return;
        }

        int id = buf.readInt();
        int index = buf.readInt();
        int total = buf.readInt();
        int size = buf.readInt();
        boolean finalChunk = index == total - 1;

        if (!this.validateFrameHeader(id, index, total, size, buf.readableBytes()))
        {
            return;
        }

        if (!finalChunk && size != BUFFER_SIZE)
        {
            this.dropTransfer(id, "non_final_size", index, total, size, buf.readableBytes());
            return;
        }

        if (!finalChunk && size != buf.readableBytes())
        {
            this.dropTransfer(id, "trailing_bytes", index, total, size, buf.readableBytes());
            return;
        }

        TransferState state = this.chunks.get(id);

        if (state == null)
        {
            if (index != 0)
            {
                this.dropTransfer(id, "missing_start", index, total, size, buf.readableBytes());
                return;
            }

            state = new TransferState(total);
            this.chunks.put(id, state);
        }
        else if (index == 0)
        {
            this.dropTransfer(id, "duplicate_start", index, total, size, buf.readableBytes());
            return;
        }

        if (state.total != total)
        {
            this.dropTransfer(id, "total_changed", index, total, size, buf.readableBytes());
            return;
        }

        if (state.seen.get(index))
        {
            this.dropTransfer(id, "duplicate_chunk", index, total, size, buf.readableBytes());
            return;
        }

        if (index != state.nextIndex)
        {
            this.dropTransfer(id, "out_of_order", index, total, size, buf.readableBytes());
            return;
        }

        if (!state.canAccept(size))
        {
            this.dropTransfer(id, "transfer_capacity", index, total, size, buf.readableBytes());
            return;
        }

        byte[] bytes = new byte[size];
        buf.readBytes(bytes);

        state.write(bytes, index);

        if (finalChunk)
        {
            byte[] finalBytes = state.bytes.toByteArray();

            this.chunks.remove(id);

            if (finalBytes.length == 1 && finalBytes[0] == 69)
            {
                finalBytes = null;
            }

            receiver.receiveBuffer(finalBytes, buf);
        }
    }

    private boolean validateFrameHeader(int id, int index, int total, int size, int readableBytes)
    {
        if (total <= 0)
        {
            this.dropTransfer(id, "invalid_total", index, total, size, readableBytes);
            return false;
        }

        if (index < 0 || index >= total)
        {
            this.dropTransfer(id, "invalid_index", index, total, size, readableBytes);
            return false;
        }

        if (size <= 0 || size > BUFFER_SIZE)
        {
            this.dropTransfer(id, "invalid_size", index, total, size, readableBytes);
            return false;
        }

        if (size > readableBytes)
        {
            this.dropTransfer(id, "truncated_chunk", index, total, size, readableBytes);
            return false;
        }

        long capacity = (long) total * (long) BUFFER_SIZE;

        if (capacity <= 0L || capacity > MAX_TRANSFER_BYTES)
        {
            this.dropTransfer(id, "declared_capacity", index, total, size, readableBytes);
            return false;
        }

        return true;
    }

    private void dropTransfer(int id, String reason, int index, int total, int size, int readableBytes)
    {
        this.chunks.remove(id);
        LOGGER.warn("[BBS-SEM] topic=net.crusher phase=receive result=drop reason={} id={} index={} total={} size={} readable={}",
            reason,
            id,
            index,
            total,
            size,
            readableBytes);
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
        byte[] chunkBytes = bytes;

        if (chunkBytes.length == 0)
        {
            chunkBytes = new byte[]{69};
        }

        int total = Math.max((int) Math.ceil(chunkBytes.length / (float) BUFFER_SIZE), 1);
        int transferId = this.counter;

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

        this.counter += 1;
    }

    protected abstract void sendBuffer(Player entity, ResourceLocation identifier, FriendlyByteBuf buf);

    private static final class TransferState
    {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(BUFFER_SIZE);
        private final BitSet seen = new BitSet();
        private final int total;
        private int nextIndex;
        private int written;

        private TransferState(int total)
        {
            this.total = total;
        }

        private boolean canAccept(int size)
        {
            return this.written <= MAX_TRANSFER_BYTES - size;
        }

        private void write(byte[] bytes, int index)
        {
            this.bytes.writeBytes(bytes);
            this.seen.set(index);
            this.nextIndex += 1;
            this.written += bytes.length;
        }
    }
}
