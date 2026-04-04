package mchorse.bbs_mod.network.compat;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
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
    private static final Map<Identifier, ClientReceiver> CLIENT_RECEIVERS = new HashMap<>();

    private NetworkCompatClient() {}

    @FunctionalInterface
    public interface ClientReceiver
    {
        void receive(PacketByteBuf buf);
    }

    public static void registerClientReceiver(Identifier id, ClientReceiver receiver)
    {
        CLIENT_RECEIVERS.put(id, receiver);
    }

    public static void sendToServer(Identifier id, PacketByteBuf buf)
    {
        NetworkCompat.sendToServer(id, buf);
    }

    public static void dispatchClientPayload(Identifier id, PacketByteBuf buf)
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
