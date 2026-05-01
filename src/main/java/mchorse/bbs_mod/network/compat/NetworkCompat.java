package mchorse.bbs_mod.network.compat;

import io.netty.buffer.Unpooled;
import mchorse.bbs_mod.BBSMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Transitional network compatibility facade.
 *
 * Uses NeoForge payload registration while preserving old byte-buffer-facing APIs.
 */
public final class NetworkCompat
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    private static final String NETWORK_VERSION = "1";
    private static final String NETWORK_COMPAT_CLIENT_CLASS = "mchorse.bbs_mod.network.compat.NetworkCompatClient";
    private static final Set<String> CHUNKED_PAYLOAD_IDS = Set.of(
        "s1", "s2", "s3", "s4", "s8", "s11",
        "c2", "c3", "c4", "c7", "c10", "c11"
    );

    private static final LinkedHashMap<ResourceLocation, PayloadBinding> C2S_BINDINGS = createBindings("s", 14);
    private static final LinkedHashMap<ResourceLocation, PayloadBinding> S2C_BINDINGS = createBindings("c", 17);
    private static final Map<ResourceLocation, ServerReceiver> SERVER_RECEIVERS = new HashMap<>();

    private NetworkCompat() {}

    @FunctionalInterface
    public interface ServerReceiver
    {
        void receive(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf);
    }

    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event)
    {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);

        for (PayloadBinding binding : C2S_BINDINGS.values())
        {
            registrar.playToServer(binding.type, codec(binding), NetworkCompat::handleServerPayload);
        }

        for (PayloadBinding binding : S2C_BINDINGS.values())
        {
            registrar.playToClient(binding.type, codec(binding), NetworkCompat::handleClientPayload);
        }

        logRegistrationSummary();
        logPayloadTypes("play_to_server", C2S_BINDINGS);
        logPayloadTypes("play_to_client", S2C_BINDINGS);
        verifyPayloadFreeze("s", 14, C2S_BINDINGS, "play_to_server");
        verifyPayloadFreeze("c", 17, S2C_BINDINGS, "play_to_client");
    }

    public static void registerServerReceiver(ResourceLocation id, ServerReceiver receiver)
    {
        if (!C2S_BINDINGS.containsKey(id))
        {
            throw new IllegalArgumentException("Unknown C2S channel id: " + id);
        }

        SERVER_RECEIVERS.put(id, receiver);
    }

    public static void sendToServer(ResourceLocation id, FriendlyByteBuf buf)
    {
        PacketDistributor.sendToServer(createPayload(C2S_BINDINGS, id, buf));
    }

    public static void sendToPlayer(ServerPlayer player, ResourceLocation id, FriendlyByteBuf buf)
    {
        PacketDistributor.sendToPlayer(player, createPayload(S2C_BINDINGS, id, buf));
    }

    public static void sendToPlayersTrackingEntity(Entity entity, ResourceLocation id, FriendlyByteBuf buf)
    {
        PacketDistributor.sendToPlayersTrackingEntity(entity, createPayload(S2C_BINDINGS, id, buf));
    }

    public static void sendToPlayersTrackingEntityAndSelf(ServerPlayer player, ResourceLocation id, FriendlyByteBuf buf)
    {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, createPayload(S2C_BINDINGS, id, buf));
    }

    public static FriendlyByteBuf createBuffer()
    {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static LinkedHashMap<ResourceLocation, PayloadBinding> createBindings(String prefix, int amount)
    {
        LinkedHashMap<ResourceLocation, PayloadBinding> bindings = new LinkedHashMap<>();

        for (int i = 1; i <= amount; i++)
        {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, prefix + i);

            bindings.put(id, new PayloadBinding(id));
        }

        return bindings;
    }

    private static void logRegistrationSummary()
    {
        int playToServer = C2S_BINDINGS.size();
        int playToClient = S2C_BINDINGS.size();
        int payloadTotal = playToServer + playToClient;

        LOGGER.info("[BBS-SEM] topic=net.version version={} play_to_server={} play_to_client={} payload_total={}",
            NETWORK_VERSION,
            playToServer,
            playToClient,
            payloadTotal
        );
        LOGGER.info("[BBS-SEM] topic=net.negotiation phase=policy result=pending reason=optional_not_configured");
    }

    private static void logPayloadTypes(String direction, LinkedHashMap<ResourceLocation, PayloadBinding> bindings)
    {
        for (PayloadBinding binding : bindings.values())
        {
            String payloadId = payloadKey(binding.id);

            LOGGER.info("[BBS-SEM] topic=net.type id={} direction={} chunked={}",
                payloadId,
                direction,
                CHUNKED_PAYLOAD_IDS.contains(payloadId)
            );
        }
    }

    private static void verifyPayloadFreeze(String prefix, int amount, LinkedHashMap<ResourceLocation, PayloadBinding> bindings, String direction)
    {
        Set<String> expected = expectedPayloadIds(prefix, amount);
        Set<String> actual = collectPayloadIds(bindings);

        if (!expected.equals(actual))
        {
            LOGGER.warn("[BBS-SEM] topic=net.type direction={} state=freeze_mismatch expected={} actual={}",
                direction,
                expected,
                actual
            );
        }
    }

    private static Set<String> expectedPayloadIds(String prefix, int amount)
    {
        Set<String> result = new HashSet<>();

        for (int i = 1; i <= amount; i++)
        {
            result.add(prefix + i);
        }

        return result;
    }

    private static Set<String> collectPayloadIds(LinkedHashMap<ResourceLocation, PayloadBinding> bindings)
    {
        Set<String> result = new HashSet<>();

        for (ResourceLocation id : bindings.keySet())
        {
            result.add(payloadKey(id));
        }

        return result;
    }

    private static String payloadKey(ResourceLocation id)
    {
        return id.getPath();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static StreamCodec codec(PayloadBinding binding)
    {
        return StreamCodec.composite(
            ByteBufCodecs.BYTE_ARRAY,
            RawPayload::bytes,
            bytes -> new RawPayload(binding, bytes)
        );
    }

    private static RawPayload createPayload(Map<ResourceLocation, PayloadBinding> bindings, ResourceLocation id, FriendlyByteBuf buf)
    {
        PayloadBinding binding = bindings.get(id);

        if (binding == null)
        {
            throw new IllegalArgumentException("Unknown payload channel id: " + id);
        }

        return new RawPayload(binding, copyReadableBytes(buf));
    }

    private static byte[] copyReadableBytes(FriendlyByteBuf buf)
    {
        byte[] bytes = new byte[buf.readableBytes()];

        buf.getBytes(buf.readerIndex(), bytes);

        return bytes;
    }

    private static FriendlyByteBuf wrapBytes(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            return createBuffer();
        }

        return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
    }

    private static void handleServerPayload(RawPayload payload, IPayloadContext context)
    {
        ServerReceiver receiver = SERVER_RECEIVERS.get(payload.binding().id);

        if (receiver == null)
        {
            LOGGER.warn("[BBS-SEM] topic=net.negotiation phase=server_payload result=reject reason=unbound_receiver id={}",
                payloadKey(payload.binding().id));
            return;
        }

        if (!(context.player() instanceof ServerPlayer player))
        {
            LOGGER.warn("[BBS-SEM] topic=net.negotiation phase=server_payload result=reject reason=invalid_player_context id={}",
                payloadKey(payload.binding().id));
            return;
        }

        MinecraftServer server = player.getServer();

        if (server == null)
        {
            return;
        }

        receiver.receive(server, player, wrapBytes(payload.bytes()));
    }

    private static void handleClientPayload(RawPayload payload, IPayloadContext context)
    {
        FriendlyByteBuf buf = wrapBytes(payload.bytes());

        try
        {
            Class<?> bridgeClass = Class.forName(NETWORK_COMPAT_CLIENT_CLASS);
            Method method = bridgeClass.getMethod("dispatchClientPayload", ResourceLocation.class, FriendlyByteBuf.class);

            method.invoke(null, payload.binding().id, buf);
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=net.client_dispatch phase=client_payload result=drop reason=bridge_unavailable id={}",
                payloadKey(payload.binding().id),
                e);
        }
    }

    private static final class PayloadBinding
    {
        private final ResourceLocation id;
        private final CustomPacketPayload.Type<RawPayload> type;

        private PayloadBinding(ResourceLocation id)
        {
            this.id = id;
            this.type = new CustomPacketPayload.Type<>(id);
        }
    }

    private record RawPayload(PayloadBinding binding, byte[] bytes) implements CustomPacketPayload
    {
        @Override
        public Type<? extends CustomPacketPayload> type()
        {
            return this.binding.type;
        }
    }
}
