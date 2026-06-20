package mchorse.bbs_mod.network.compat;

import io.netty.buffer.Unpooled;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.network.BBSAddonClientNetworkReceiver;
import mchorse.bbs_mod.api.network.BBSAddonServerNetworkReceiver;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Core-owned addon payload sub-protocol carried by frozen BBS broker channels.
 */
public final class AddonPayloadBroker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    private static final int MAX_MESSAGE_ID_LENGTH = 255;
    private static final int MAX_C2S_BODY_BYTES = 28 * 1024;
    private static final int MAX_S2C_BODY_BYTES = 1024 * 1024 - 4096;
    private static final Map<ResourceLocation, ServerBrokerReceiver> SERVER_RECEIVERS = new HashMap<>();
    private static final Map<ResourceLocation, BBSAddonClientNetworkReceiver> CLIENT_RECEIVERS = new HashMap<>();

    private AddonPayloadBroker() {}

    public static BBSRegistrationResult registerServerReceiver(
        BBSAddonDescriptor descriptor,
        ResourceLocation id,
        BBSAddonServerNetworkReceiver receiver
    )
    {
        String key = stringId(id);
        String accessIssue = validateAddonAccess(descriptor, id);

        if (accessIssue != null)
        {
            return BBSRegistrationResult.rejected(key, accessIssue);
        }

        if (receiver == null)
        {
            return BBSRegistrationResult.rejected(key, "addon network receiver is null");
        }

        synchronized (SERVER_RECEIVERS)
        {
            ServerBrokerReceiver existing = SERVER_RECEIVERS.get(id);

            if (existing != null)
            {
                return BBSRegistrationResult.duplicate(key, existing.ownerAddonId);
            }

            SERVER_RECEIVERS.put(id, new ServerBrokerReceiver(descriptor.addonId(), receiver));
        }

        LOGGER.info("[BBS-SEM] topic=net.addon_broker phase=register direction=c2s result=accept addon={} id={}",
            descriptor.addonId(),
            key);

        return BBSRegistrationResult.accepted(key);
    }

    public static BBSRegistrationResult registerClientReceiver(
        BBSAddonDescriptor descriptor,
        ResourceLocation id,
        BBSAddonClientNetworkReceiver receiver
    )
    {
        String key = stringId(id);
        String accessIssue = validateAddonAccess(descriptor, id);

        if (accessIssue != null)
        {
            return BBSRegistrationResult.rejected(key, accessIssue);
        }

        if (receiver == null)
        {
            return BBSRegistrationResult.rejected(key, "addon network receiver is null");
        }

        synchronized (CLIENT_RECEIVERS)
        {
            BBSAddonClientNetworkReceiver existing = CLIENT_RECEIVERS.get(id);

            if (existing != null)
            {
                return BBSRegistrationResult.duplicate(key, existing.getClass().getName());
            }

            CLIENT_RECEIVERS.put(id, receiver);
        }

        LOGGER.info("[BBS-SEM] topic=net.addon_broker phase=register direction=s2c result=accept addon={} id={}",
            descriptor.addonId(),
            key);

        return BBSRegistrationResult.accepted(key);
    }

    public static boolean sendToServer(BBSAddonDescriptor descriptor, ResourceLocation id, FriendlyByteBuf payload)
    {
        String accessIssue = validateAddonAccess(descriptor, id);

        if (accessIssue != null)
        {
            logSendReject("c2s", descriptor, id, accessIssue);
            return false;
        }

        FriendlyByteBuf frame = createFrame(descriptor, id, payload, MAX_C2S_BODY_BYTES, "c2s");

        if (frame == null)
        {
            return false;
        }

        NetworkCompat.sendToServer(NetworkCompat.ADDON_BROKER_C2S, frame);

        return true;
    }

    public static boolean sendToPlayer(BBSAddonDescriptor descriptor, ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload)
    {
        if (player == null)
        {
            logSendReject("s2c", descriptor, id, "target player is null");
            return false;
        }

        FriendlyByteBuf frame = createClientboundFrame(descriptor, id, payload);

        if (frame == null)
        {
            return false;
        }

        NetworkCompat.sendToPlayer(player, NetworkCompat.ADDON_BROKER_S2C, frame);

        return true;
    }

    public static boolean sendToPlayersTrackingEntity(BBSAddonDescriptor descriptor, Entity entity, ResourceLocation id, FriendlyByteBuf payload)
    {
        if (entity == null)
        {
            logSendReject("s2c", descriptor, id, "target entity is null");
            return false;
        }

        FriendlyByteBuf frame = createClientboundFrame(descriptor, id, payload);

        if (frame == null)
        {
            return false;
        }

        NetworkCompat.sendToPlayersTrackingEntity(entity, NetworkCompat.ADDON_BROKER_S2C, frame);

        return true;
    }

    public static boolean sendToPlayersTrackingEntityAndSelf(BBSAddonDescriptor descriptor, ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload)
    {
        if (player == null)
        {
            logSendReject("s2c", descriptor, id, "target player is null");
            return false;
        }

        FriendlyByteBuf frame = createClientboundFrame(descriptor, id, payload);

        if (frame == null)
        {
            return false;
        }

        NetworkCompat.sendToPlayersTrackingEntityAndSelf(player, NetworkCompat.ADDON_BROKER_S2C, frame);

        return true;
    }

    public static void handleServerPayload(MinecraftServer server, ServerPlayer player, FriendlyByteBuf frame)
    {
        BrokerFrame brokerFrame = readFrame(frame, MAX_C2S_BODY_BYTES, "c2s");

        if (brokerFrame == null)
        {
            return;
        }

        ServerBrokerReceiver receiver;

        synchronized (SERVER_RECEIVERS)
        {
            receiver = SERVER_RECEIVERS.get(brokerFrame.id);
        }

        if (receiver == null)
        {
            LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction=c2s result=drop reason=unbound_subprotocol id={}",
                brokerFrame.id);
            return;
        }

        try
        {
            receiver.receiver.receive(server, player, brokerFrame.id, wrapBytes(brokerFrame.bytes));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[BBS-SEM] topic=net.addon_broker phase=receive direction=c2s result=error addon={} id={}",
                receiver.ownerAddonId,
                brokerFrame.id,
                e);
        }
    }

    public static void handleClientPayload(FriendlyByteBuf frame)
    {
        BrokerFrame brokerFrame = readFrame(frame, MAX_S2C_BODY_BYTES, "s2c");

        if (brokerFrame == null)
        {
            return;
        }

        BBSAddonClientNetworkReceiver receiver;

        synchronized (CLIENT_RECEIVERS)
        {
            receiver = CLIENT_RECEIVERS.get(brokerFrame.id);
        }

        if (receiver == null)
        {
            LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction=s2c result=drop reason=unbound_subprotocol id={}",
                brokerFrame.id);
            return;
        }

        try
        {
            receiver.receive(brokerFrame.id, wrapBytes(brokerFrame.bytes));
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[BBS-SEM] topic=net.addon_broker phase=receive direction=s2c result=error id={}",
                brokerFrame.id,
                e);
        }
    }

    public static FriendlyByteBuf createBuffer()
    {
        return NetworkCompat.createBuffer();
    }

    public static String validateAddonAccess(BBSAddonDescriptor descriptor, ResourceLocation id)
    {
        if (descriptor == null)
        {
            return "addon descriptor is null";
        }

        if (!descriptor.capabilities().contains(BBSAddonCapability.NETWORK))
        {
            return "addon did not declare NETWORK capability";
        }

        if (id == null)
        {
            return "addon network message id is null";
        }

        String namespace = id.getNamespace();

        if (BBSMod.MOD_ID.equals(namespace))
        {
            return "addon broker namespace '" + BBSMod.MOD_ID + "' is reserved by BBS core";
        }

        if (!descriptor.namespaces().contains(namespace))
        {
            return "addon network message namespace '" + namespace + "' is not declared by addon descriptor";
        }

        String idString = id.toString();

        if (idString.length() > MAX_MESSAGE_ID_LENGTH)
        {
            return "addon network message id is longer than " + MAX_MESSAGE_ID_LENGTH + " characters";
        }

        return null;
    }

    private static FriendlyByteBuf createClientboundFrame(BBSAddonDescriptor descriptor, ResourceLocation id, FriendlyByteBuf payload)
    {
        String accessIssue = validateAddonAccess(descriptor, id);

        if (accessIssue != null)
        {
            logSendReject("s2c", descriptor, id, accessIssue);
            return null;
        }

        return createFrame(descriptor, id, payload, MAX_S2C_BODY_BYTES, "s2c");
    }

    private static FriendlyByteBuf createFrame(BBSAddonDescriptor descriptor, ResourceLocation id, FriendlyByteBuf payload, int maxBodyBytes, String direction)
    {
        if (payload == null)
        {
            logSendReject(direction, descriptor, id, "payload buffer is null");
            return null;
        }

        int readableBytes = payload.readableBytes();

        if (readableBytes > maxBodyBytes)
        {
            logSendReject(direction, descriptor, id, "payload body exceeds " + maxBodyBytes + " bytes");
            return null;
        }

        byte[] bytes = copyReadableBytes(payload);

        FriendlyByteBuf frame = NetworkCompat.createBuffer();

        frame.writeUtf(id.toString());
        frame.writeInt(bytes.length);
        frame.writeBytes(bytes);

        return frame;
    }

    private static BrokerFrame readFrame(FriendlyByteBuf frame, int maxBodyBytes, String direction)
    {
        try
        {
            String idString = frame.readUtf(MAX_MESSAGE_ID_LENGTH);
            ResourceLocation id = ResourceLocation.tryParse(idString);

            if (id == null)
            {
                LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction={} result=drop reason=invalid_id id={}",
                    direction,
                    idString);
                return null;
            }

            int bodyLength = frame.readInt();

            if (bodyLength < 0 || bodyLength > maxBodyBytes)
            {
                LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction={} result=drop reason=invalid_size id={} size={} max={}",
                    direction,
                    id,
                    bodyLength,
                    maxBodyBytes);
                return null;
            }

            if (bodyLength > frame.readableBytes())
            {
                LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction={} result=drop reason=truncated_body id={} size={} readable={}",
                    direction,
                    id,
                    bodyLength,
                    frame.readableBytes());
                return null;
            }

            byte[] bytes = new byte[bodyLength];

            frame.readBytes(bytes);

            if (frame.readableBytes() > 0)
            {
                LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction={} result=drop reason=trailing_bytes id={} trailing={}",
                    direction,
                    id,
                    frame.readableBytes());
                return null;
            }

            return new BrokerFrame(id, bytes);
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=receive direction={} result=drop reason=malformed_frame",
                direction,
                e);
            return null;
        }
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
            return NetworkCompat.createBuffer();
        }

        return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
    }

    private static void logSendReject(String direction, BBSAddonDescriptor descriptor, ResourceLocation id, String reason)
    {
        String addonId = descriptor == null ? "<unknown>" : descriptor.addonId();

        LOGGER.warn("[BBS-SEM] topic=net.addon_broker phase=send direction={} result=reject addon={} id={} reason={}",
            direction,
            addonId,
            stringId(id),
            reason);
    }

    private static String stringId(ResourceLocation id)
    {
        return id == null ? "<null>" : id.toString();
    }

    private record BrokerFrame(ResourceLocation id, byte[] bytes) {}

    private record ServerBrokerReceiver(String ownerAddonId, BBSAddonServerNetworkReceiver receiver) {}
}
