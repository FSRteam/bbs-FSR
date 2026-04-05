package mchorse.bbs_mod.network;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public abstract class PacketCrusher
{
    public static final int BUFFER_SIZE = 30_000;

    private Map<Integer, ByteArrayOutputStream> chunks = new HashMap<>();
    private int counter;

    public void reset()
    {
        this.chunks.clear();
        this.counter = 0;
    }

    public void receive(FriendlyByteBuf buf, IBufferReceiver receiver)
    {
        int id = buf.readInt();
        int index = buf.readInt();
        int total = buf.readInt();
        int size = buf.readInt();
        byte[] bytes = new byte[size];

        buf.readBytes(bytes);

        ByteArrayOutputStream map = this.chunks.computeIfAbsent(id, (k) -> new ByteArrayOutputStream(total * BUFFER_SIZE));

        map.writeBytes(bytes);

        if (index == total - 1)
        {
            byte[] finalBytes = map.toByteArray();

            if (finalBytes.length == 1 && finalBytes[0] == 69)
            {
                finalBytes = null;
            }

            receiver.receiveBuffer(finalBytes, buf);
            this.chunks.remove(id);
        }
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
}
