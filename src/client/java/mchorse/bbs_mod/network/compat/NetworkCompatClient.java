package mchorse.bbs_mod.network.compat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
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
    private static final Map<ResourceLocation, ScopedClientReceiver> CLIENT_RECEIVERS = new HashMap<>();

    private NetworkCompatClient() {}

    @FunctionalInterface
    public interface ClientReceiver
    {
        void receive(FriendlyByteBuf buf);
    }

    @FunctionalInterface
    public interface ScopedClientReceiver
    {
        void receive(FriendlyByteBuf buf, Connection connection, LocalPlayer player);
    }

    /**
     * Legacy API descriptor retained for binary compatibility. Frozen c1..c19
     * channels are core-owned, so unscoped receivers are rejected fail-closed.
     */
    public static synchronized void registerClientReceiver(ResourceLocation id, ClientReceiver receiver)
    {
        validateRegistration(id, receiver);

        LOGGER.warn("[BBS-SEM] topic=net.client_receiver phase=register result=reject reason=unscoped_core_channel id={}", id);
        throw new IllegalStateException("S2C channel requires a transport-scoped BBS core receiver: " + id);
    }

    public static synchronized void registerCoreClientReceiver(ResourceLocation id, ScopedClientReceiver receiver)
    {
        validateRegistration(id, receiver);

        ScopedClientReceiver existing = CLIENT_RECEIVERS.get(id);

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

    private static void validateRegistration(ResourceLocation id, Object receiver)
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
    }

    public static void sendToServer(ResourceLocation id, FriendlyByteBuf buf)
    {
        NetworkCompat.sendToServer(id, buf);
    }

    public static void dispatchClientPayload(
        ResourceLocation id,
        FriendlyByteBuf buf,
        Connection connection,
        Player player
    )
    {
        if (connection == null || !(player instanceof LocalPlayer localPlayer))
        {
            LOGGER.warn("[BBS-SEM] topic=net.client_dispatch phase=client_payload result=drop reason=invalid_transport_scope id={}", id);
            return;
        }

        ScopedClientReceiver receiver = CLIENT_RECEIVERS.get(id);

        if (receiver == null)
        {
            LOGGER.warn("Received unbound S2C payload: {}", id);
            return;
        }

        receiver.receive(buf, connection, localPlayer);
    }

    /**
     * Legacy reflection entry intentionally fails closed because it has no
     * authenticated transport/player scope to bind delayed work to.
     */
    @Deprecated(forRemoval = false)
    public static void dispatchClientPayload(ResourceLocation id, FriendlyByteBuf buf)
    {
        LOGGER.warn("[BBS-SEM] topic=net.client_dispatch phase=client_payload result=drop reason=legacy_scope_missing id={}", id);
    }
}
