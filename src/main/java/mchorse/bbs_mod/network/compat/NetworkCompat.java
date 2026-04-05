package mchorse.bbs_mod.network.compat;

import io.netty.buffer.Unpooled;
import mchorse.bbs_mod.BBSMod;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Transitional network compatibility facade.
 *
 * Uses NeoForge payload registration while preserving old PacketByteBuf-facing APIs.
 */
public final class NetworkCompat
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    private static final String NETWORK_VERSION = "1";
    private static final Set<String> CHUNKED_PAYLOAD_IDS = Set.of(
        "s1", "s2", "s3", "s4", "s8", "s11",
        "c2", "c3", "c4", "c7", "c10", "c11"
    );

    private static final LinkedHashMap<Identifier, PayloadBinding> C2S_BINDINGS = createBindings("s", 14);
    private static final LinkedHashMap<Identifier, PayloadBinding> S2C_BINDINGS = createBindings("c", 17);
    private static final Map<Identifier, ServerReceiver> SERVER_RECEIVERS = new HashMap<>();

    private NetworkCompat() {}

    @FunctionalInterface
    public interface ServerReceiver
    {
        void receive(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buf);
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

    public static void registerServerReceiver(Identifier id, ServerReceiver receiver)
    {
        if (!C2S_BINDINGS.containsKey(id))
        {
            throw new IllegalArgumentException("Unknown C2S channel id: " + id);
        }

        SERVER_RECEIVERS.put(id, receiver);
    }

    public static void sendToServer(Identifier id, PacketByteBuf buf)
    {
        PacketDistributor.sendToServer(createPayload(C2S_BINDINGS, id, buf));
    }

    public static void sendToPlayer(ServerPlayerEntity player, Identifier id, PacketByteBuf buf)
    {
        PacketDistributor.sendToPlayer(player, createPayload(S2C_BINDINGS, id, buf));
    }

    public static void sendToPlayersTrackingEntity(Entity entity, Identifier id, PacketByteBuf buf)
    {
        PacketDistributor.sendToPlayersTrackingEntity(entity, createPayload(S2C_BINDINGS, id, buf));
    }

    public static void sendToPlayersTrackingEntityAndSelf(ServerPlayerEntity player, Identifier id, PacketByteBuf buf)
    {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, createPayload(S2C_BINDINGS, id, buf));
    }

    public static PacketByteBuf createBuffer()
    {
        return new PacketByteBuf(Unpooled.buffer());
    }

    private static LinkedHashMap<Identifier, PayloadBinding> createBindings(String prefix, int amount)
    {
        LinkedHashMap<Identifier, PayloadBinding> bindings = new LinkedHashMap<>();

        for (int i = 1; i <= amount; i++)
        {
            Identifier id = new Identifier(BBSMod.MOD_ID, prefix + i);

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

    private static void logPayloadTypes(String direction, LinkedHashMap<Identifier, PayloadBinding> bindings)
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

    private static void verifyPayloadFreeze(String prefix, int amount, LinkedHashMap<Identifier, PayloadBinding> bindings, String direction)
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

    private static Set<String> collectPayloadIds(LinkedHashMap<Identifier, PayloadBinding> bindings)
    {
        Set<String> result = new HashSet<>();

        for (Identifier id : bindings.keySet())
        {
            result.add(payloadKey(id));
        }

        return result;
    }

    private static String payloadKey(Identifier id)
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

    private static RawPayload createPayload(Map<Identifier, PayloadBinding> bindings, Identifier id, PacketByteBuf buf)
    {
        PayloadBinding binding = bindings.get(id);

        if (binding == null)
        {
            throw new IllegalArgumentException("Unknown payload channel id: " + id);
        }

        return new RawPayload(binding, copyReadableBytes(buf));
    }

    private static byte[] copyReadableBytes(PacketByteBuf buf)
    {
        byte[] bytes = new byte[buf.readableBytes()];

        buf.getBytes(buf.readerIndex(), bytes);

        return bytes;
    }

    private static PacketByteBuf wrapBytes(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            return createBuffer();
        }

        return new PacketByteBuf(Unpooled.wrappedBuffer(bytes));
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

        if (!(context.player() instanceof ServerPlayerEntity player))
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
        NetworkCompatClient.dispatchClientPayload(payload.binding().id, wrapBytes(payload.bytes()));
    }

    private static final class PayloadBinding
    {
        private final Identifier id;
        private final CustomPacketPayload.Type<RawPayload> type;

        private PayloadBinding(Identifier id)
        {
            this.id = id;
            this.type = CustomPacketPayload.createType(id.toString());
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
