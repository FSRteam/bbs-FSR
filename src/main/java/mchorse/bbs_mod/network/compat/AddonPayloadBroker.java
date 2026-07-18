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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Core-owned addon payload sub-protocol carried by frozen BBS broker channels.
 */
public final class AddonPayloadBroker
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    private static final int MAX_MESSAGE_ID_LENGTH = 255;
    private static final int MAX_C2S_BODY_BYTES = 28 * 1024;
    private static final int MAX_S2C_BODY_BYTES = 1024 * 1024 - 4096;
    private static final int SERVER_DIAGNOSTIC_PER_CONNECTION_BURST = 4;
    private static final int SERVER_DIAGNOSTIC_SHARED_BURST = 32;
    private static final int SERVER_DIAGNOSTIC_TRACKED_CONNECTIONS = 1024;
    private static final long SERVER_DIAGNOSTIC_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int CLIENT_DIAGNOSTIC_BURST = 8;
    private static final long CLIENT_DIAGNOSTIC_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final AddonBrokerDiagnosticLimiter SERVER_DIAGNOSTICS = new AddonBrokerDiagnosticLimiter(
        SERVER_DIAGNOSTIC_PER_CONNECTION_BURST,
        SERVER_DIAGNOSTIC_SHARED_BURST,
        SERVER_DIAGNOSTIC_TRACKED_CONNECTIONS,
        SERVER_DIAGNOSTIC_WINDOW_NANOS,
        System::nanoTime
    );
    private static final AddonBrokerDiagnosticLimiter CLIENT_DIAGNOSTICS = new AddonBrokerDiagnosticLimiter(
        CLIENT_DIAGNOSTIC_BURST,
        CLIENT_DIAGNOSTIC_BURST,
        1,
        CLIENT_DIAGNOSTIC_WINDOW_NANOS,
        System::nanoTime
    );
    private static final UUID CLIENT_BUDGET_OWNER = new UUID(0L, 0L);
    private static final AddonBrokerServerBudget SERVER_BUDGET = new AddonBrokerServerBudget();
    private static final AddonBrokerServerBudget CLIENT_BUDGET = AddonBrokerServerBudget.clientDefaults();
    private static final Map<ResourceLocation, ServerBrokerReceiver> SERVER_RECEIVERS = new HashMap<>();
    private static final Map<ResourceLocation, ClientBrokerReceiver> CLIENT_RECEIVERS = new HashMap<>();

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
            ClientBrokerReceiver existing = CLIENT_RECEIVERS.get(id);

            if (existing != null)
            {
                return BBSRegistrationResult.duplicate(key, existing.ownerAddonId);
            }

            CLIENT_RECEIVERS.put(id, new ClientBrokerReceiver(descriptor.addonId(), receiver));
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
        BrokerFrame brokerFrame = readFrame(frame, MAX_C2S_BODY_BYTES, "c2s", player);

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
            logServerDiagnostic(player, false, "unbound_subprotocol", brokerFrame.id, "receiver=missing", null);
            return;
        }

        if (player == null)
        {
            logServerDiagnostic(null, false, "missing_connection", brokerFrame.id,
                "server player is missing", null);
            return;
        }

        if (!SERVER_BUDGET.tryAcquire(
            player.getUUID(),
            player,
            receiver.ownerAddonId,
            brokerFrame.bytes.length
        ))
        {
            logServerDiagnostic(player, false, "rate_limited", brokerFrame.id,
                "addon=" + receiver.ownerAddonId + " body_bytes=" + brokerFrame.bytes.length, null);
            return;
        }

        try
        {
            server.execute(() ->
            {
                /* Payload handlers may run on a network executor. Addon code is
                 * allowed to touch world/player state, so deliver it on the
                 * server thread and reject work queued by a retired connection. */
                if (server.getPlayerList().getPlayer(player.getUUID()) != player)
                {
                    return;
                }

                try
                {
                    receiver.receiver.receive(server, player, brokerFrame.id, wrapBytes(brokerFrame.bytes));
                }
                catch (Exception | LinkageError e)
                {
                    logServerDiagnostic(
                        player,
                        true,
                        "receiver_failure",
                        brokerFrame.id,
                        "addon=" + receiver.ownerAddonId,
                        e
                    );
                }
            });
        }
        catch (Exception | LinkageError e)
        {
            logServerDiagnostic(
                player,
                true,
                "dispatcher_failure",
                brokerFrame.id,
                "addon=" + receiver.ownerAddonId,
                e
            );
        }
    }

    /** Release only the budget state owned by one concrete server connection. */
    public static int clearServerConnection(UUID owner, Object connectionIdentity)
    {
        int removed = SERVER_BUDGET.clearConnection(owner, connectionIdentity);

        if (owner != null && connectionIdentity != null)
        {
            SERVER_DIAGNOSTICS.clearConnection(owner.toString());
        }

        return removed;
    }

    public static int expireServerBudgetIdle()
    {
        return SERVER_BUDGET.expireIdle();
    }

    public static void resetServerBudget()
    {
        SERVER_BUDGET.reset();
        SERVER_DIAGNOSTICS.reset();
    }

    /** Release every addon scope owned by one concrete client generation. */
    public static int clearClientConnection(Object connectionIdentity)
    {
        return CLIENT_BUDGET.clearConnection(CLIENT_BUDGET_OWNER, connectionIdentity);
    }

    public static int expireClientBudgetIdle()
    {
        return CLIENT_BUDGET.expireIdle();
    }

    public static void resetClientBudget()
    {
        CLIENT_BUDGET.reset();
        CLIENT_DIAGNOSTICS.reset();
    }

    /**
     * Legacy internal entry point. Client payload delivery now requires an
     * explicit client-thread/generation dispatcher and therefore fails closed
     * when an old caller does not provide one.
     */
    @Deprecated(forRemoval = false)
    public static void handleClientPayload(FriendlyByteBuf frame)
    {
        handleClientPayload(frame, null, null);
    }

    /**
     * Compatibility overload. Exact-generation admission is mandatory, so an
     * old caller that supplies only a dispatcher is rejected after decoding.
     */
    @Deprecated(forRemoval = false)
    public static void handleClientPayload(FriendlyByteBuf frame, Consumer<Runnable> dispatcher)
    {
        handleClientPayload(frame, null, dispatcher);
    }

    public static void handleClientPayload(
        FriendlyByteBuf frame,
        Object connectionIdentity,
        Consumer<Runnable> dispatcher
    )
    {
        BrokerFrame brokerFrame = readFrame(frame, MAX_S2C_BODY_BYTES, "s2c", null);

        if (brokerFrame == null)
        {
            return;
        }

        ClientBrokerReceiver receiver;

        synchronized (CLIENT_RECEIVERS)
        {
            receiver = CLIENT_RECEIVERS.get(brokerFrame.id);
        }

        if (receiver == null)
        {
            logClientDiagnostic(false, "unbound_subprotocol", brokerFrame.id, "receiver=missing", null);
            return;
        }

        if (connectionIdentity == null)
        {
            logClientDiagnostic(false, "connection_identity_unavailable", brokerFrame.id,
                "client connection generation is missing", null);

            return;
        }

        if (dispatcher == null)
        {
            logClientDiagnostic(false, "dispatcher_unavailable", brokerFrame.id,
                "client-thread generation dispatcher is missing", null);

            return;
        }

        if (!CLIENT_BUDGET.tryAcquire(
            CLIENT_BUDGET_OWNER,
            connectionIdentity,
            receiver.ownerAddonId,
            brokerFrame.bytes.length
        ))
        {
            logClientDiagnostic(false, "rate_limited", brokerFrame.id,
                "addon=" + receiver.ownerAddonId + " body_bytes=" + brokerFrame.bytes.length, null);
            return;
        }

        Runnable delivery = () ->
        {
            try
            {
                receiver.receiver.receive(brokerFrame.id, wrapBytes(brokerFrame.bytes));
            }
            catch (Exception | LinkageError e)
            {
                logClientDiagnostic(true, "receiver_failure", brokerFrame.id, "receiver=addon", e);
            }
        };

        try
        {
            dispatcher.accept(delivery);
        }
        catch (Exception | LinkageError e)
        {
            logClientDiagnostic(true, "dispatcher_failure", brokerFrame.id,
                "client-thread generation dispatcher rejected delivery", e);
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

    private static BrokerFrame readFrame(FriendlyByteBuf frame, int maxBodyBytes, String direction, ServerPlayer player)
    {
        try
        {
            String idString = frame.readUtf(MAX_MESSAGE_ID_LENGTH);
            ResourceLocation id = ResourceLocation.tryParse(idString);

            if (id == null)
            {
                logReceiveWarning(direction, player, "invalid_id", null, "raw_id=" + idString, null);
                return null;
            }

            int bodyLength = frame.readInt();

            if (bodyLength < 0 || bodyLength > maxBodyBytes)
            {
                logReceiveWarning(direction, player, "invalid_size", id,
                    "size=" + bodyLength + " max=" + maxBodyBytes, null);
                return null;
            }

            if (bodyLength > frame.readableBytes())
            {
                logReceiveWarning(direction, player, "truncated_body", id,
                    "size=" + bodyLength + " readable=" + frame.readableBytes(), null);
                return null;
            }

            byte[] bytes = new byte[bodyLength];

            frame.readBytes(bytes);

            if (frame.readableBytes() > 0)
            {
                logReceiveWarning(direction, player, "trailing_bytes", id,
                    "trailing=" + frame.readableBytes(), null);
                return null;
            }

            return new BrokerFrame(id, bytes);
        }
        catch (Exception e)
        {
            logReceiveWarning(direction, player, "malformed_frame", null,
                "exception=" + e.getClass().getName(), e);
            return null;
        }
    }

    private static void logReceiveWarning(
        String direction,
        ServerPlayer player,
        String reason,
        ResourceLocation id,
        String detail,
        Throwable error
    )
    {
        if ("c2s".equals(direction))
        {
            logServerDiagnostic(player, false, reason, id, detail, error);

            return;
        }

        logClientDiagnostic(false, reason, id, detail, error);
    }

    private static void logServerDiagnostic(
        ServerPlayer player,
        boolean errorLevel,
        String reason,
        ResourceLocation id,
        String detail,
        Throwable error
    )
    {
        String connection = player == null ? "<unknown>" : player.getUUID().toString();
        AddonBrokerDiagnosticLimiter.Decision decision = SERVER_DIAGNOSTICS.acquire(connection);

        if (!decision.allowed())
        {
            return;
        }

        if (errorLevel)
        {
            LOGGER.error(
                "[BBS-SEM] topic=net.addon_broker phase=receive direction=c2s result=error reason={} connection={} id={} detail={} suppressed_connection={} suppressed_shared={}",
                reason,
                connection,
                stringId(id),
                detail,
                decision.connectionSuppressed(),
                decision.sharedSuppressed(),
                error
            );
        }
        else if (error == null)
        {
            LOGGER.warn(
                "[BBS-SEM] topic=net.addon_broker phase=receive direction=c2s result=drop reason={} connection={} id={} detail={} suppressed_connection={} suppressed_shared={}",
                reason,
                connection,
                stringId(id),
                detail,
                decision.connectionSuppressed(),
                decision.sharedSuppressed()
            );
        }
        else
        {
            LOGGER.warn(
                "[BBS-SEM] topic=net.addon_broker phase=receive direction=c2s result=drop reason={} connection={} id={} detail={} suppressed_connection={} suppressed_shared={}",
                reason,
                connection,
                stringId(id),
                detail,
                decision.connectionSuppressed(),
                decision.sharedSuppressed(),
                error
            );
        }
    }

    private static void logClientDiagnostic(
        boolean errorLevel,
        String reason,
        ResourceLocation id,
        String detail,
        Throwable error
    )
    {
        AddonBrokerDiagnosticLimiter.Decision decision = CLIENT_DIAGNOSTICS.acquire("server");

        if (!decision.allowed())
        {
            return;
        }

        if (errorLevel)
        {
            LOGGER.error(
                "[BBS-SEM] topic=net.addon_broker phase=receive direction=s2c result=error reason={} id={} detail={} suppressed={}",
                reason,
                stringId(id),
                detail,
                decision.sharedSuppressed(),
                error
            );
        }
        else if (error == null)
        {
            LOGGER.warn(
                "[BBS-SEM] topic=net.addon_broker phase=receive direction=s2c result=drop reason={} id={} detail={} suppressed={}",
                reason,
                stringId(id),
                detail,
                decision.sharedSuppressed()
            );
        }
        else
        {
            LOGGER.warn(
                "[BBS-SEM] topic=net.addon_broker phase=receive direction=s2c result=drop reason={} id={} detail={} suppressed={}",
                reason,
                stringId(id),
                detail,
                decision.sharedSuppressed(),
                error
            );
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

    private record ClientBrokerReceiver(String ownerAddonId, BBSAddonClientNetworkReceiver receiver) {}
}
