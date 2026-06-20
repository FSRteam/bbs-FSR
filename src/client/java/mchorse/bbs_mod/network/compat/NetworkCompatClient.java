package mchorse.bbs_mod.network.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Transitional client network compatibility facade.
 */
public final class NetworkCompatClient
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network-client");
    private static final Map<ResourceLocation, ClientReceiver> CLIENT_RECEIVERS = new HashMap<>();

    private NetworkCompatClient() {}

    @FunctionalInterface
    public interface ClientReceiver
    {
        void receive(FriendlyByteBuf buf);
    }

    public static synchronized void registerClientReceiver(ResourceLocation id, ClientReceiver receiver)
    {
        if (id == null)
        {
            throw new IllegalArgumentException("S2C channel id is null");
        }

        if (receiver == null)
        {
            throw new IllegalArgumentException("Client receiver is null for S2C channel id: " + id);
        }

        if (!NetworkCompat.isClientboundPayloadId(id))
        {
            LOGGER.warn("[BBS-SEM] topic=net.client_receiver phase=register result=reject reason=unknown_channel id={}",
                id);
            throw new IllegalArgumentException("Unknown S2C channel id: " + id);
        }

        ClientReceiver existing = CLIENT_RECEIVERS.get(id);

        if (existing != null)
        {
            LOGGER.warn("[BBS-SEM] topic=net.client_receiver phase=register result=reject reason=duplicate_receiver id={} existing={} incoming={}",
                id,
                existing.getClass().getName(),
                receiver.getClass().getName());
            throw new IllegalStateException("S2C channel already has a client receiver: " + id);
        }

        CLIENT_RECEIVERS.put(id, receiver);
    }

    public static void sendToServer(ResourceLocation id, FriendlyByteBuf buf)
    {
        NetworkCompat.sendToServer(id, buf);
    }

    public static void dispatchClientPayload(ResourceLocation id, FriendlyByteBuf buf)
    {
        ClientReceiver receiver = CLIENT_RECEIVERS.get(id);

        if (receiver == null)
        {
            LOGGER.warn("Received unbound S2C payload: {}", id);
            return;
        }

        receiver.receive(buf);
    }
}
