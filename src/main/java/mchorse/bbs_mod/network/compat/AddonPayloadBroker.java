package mchorse.bbs_mod.network.compat;

import io.netty.buffer.Unpooled;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.addon.BBSAddonCapability;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.network.BBSAddonClientNetworkReceiver;
import mchorse.bbs_mod.api.network.BBSAddonServerNetworkReceiver;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.plugin.runtime.ActivePluginIndex;
import mchorse.bbs_mod.plugin.runtime.PluginGenerationLease;
import mchorse.bbs_mod.plugin.runtime.PluginLease;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    /*
     * These maps deliberately do not contain plugin callbacks.  A route is a
     * host-owned proxy; callbacks live only in the ActivePluginIndex
     * generation snapshot selected by the route at delivery time.
     */
    private static final Map<ResourceLocation, HotServerRoute> HOT_SERVER_ROUTES = new HashMap<>();
    private static final Map<ResourceLocation, HotClientRoute> HOT_CLIENT_ROUTES = new HashMap<>();

    private AddonPayloadBroker() {}

    /**
     * Starts a staged hot receiver contribution for one plugin generation.
     * The returned builder is not visible to the broker's fixed payload maps;
     * publish its immutable {@link HotReceiverSnapshot} through the supplied
     * {@link ActivePluginIndex} when the generation is ready.
     */
    public static HotReceiverSnapshot.Builder stageHotReceivers(
        PluginOwner owner,
        ActivePluginIndex<HotReceiverSnapshot> activeIndex
    )
    {
        return HotReceiverSnapshot.builder(owner, activeIndex);
    }

    /** Alias retained for callers that name the operation after the snapshot. */
    public static HotReceiverSnapshot.Builder hotReceiverSnapshot(
        PluginOwner owner,
        ActivePluginIndex<HotReceiverSnapshot> activeIndex
    )
    {
        return stageHotReceivers(owner, activeIndex);
    }

    /**
     * Removes all fixed hot route claims for exactly one plugin generation.
     * It does not touch legacy Addon API maps or another generation of the same
     * plugin id.  Repeated calls are harmless.
     */
    public static int clearHotOwner(PluginOwner owner)
    {
        if (owner == null)
        {
            return 0;
        }

        int removed = 0;

        synchronized (HOT_SERVER_ROUTES)
        {
            removed += clearHotOwners(HOT_SERVER_ROUTES, owner);
        }

        synchronized (HOT_CLIENT_ROUTES)
        {
            removed += clearHotOwners(HOT_CLIENT_ROUTES, owner);
        }

        return removed;
    }

    /** More explicit alias for lifecycle teardown code. */
    public static int clearHotReceiverOwner(PluginOwner owner)
    {
        return clearHotOwner(owner);
    }

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

        HotServerSelection hotSelection = null;

        if (receiver == null)
        {
            HotServerRoute route;

            synchronized (HOT_SERVER_ROUTES)
            {
                route = HOT_SERVER_ROUTES.get(brokerFrame.id);
            }

            if (route != null)
            {
                hotSelection = route.select(brokerFrame.id);
            }
        }

        if (receiver == null && hotSelection == null)
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

        String receiverOwner = receiver == null ? hotSelection.owner.pluginId() : receiver.ownerAddonId;

        if (!SERVER_BUDGET.tryAcquire(
            player.getUUID(),
            player,
            receiverOwner,
            brokerFrame.bytes.length
        ))
        {
            logServerDiagnostic(player, false, "rate_limited", brokerFrame.id,
                "addon=" + receiverOwner + " body_bytes=" + brokerFrame.bytes.length, null);
            return;
        }

        ServerBrokerReceiver queuedReceiver = receiver;
        HotServerSelection queuedHotSelection = hotSelection;

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
                    if (queuedReceiver != null)
                    {
                        queuedReceiver.receiver.receive(server, player, brokerFrame.id, wrapBytes(brokerFrame.bytes));
                    }
                    else
                    {
                        queuedHotSelection.deliver(server, player, brokerFrame);
                    }
                }
                catch (Exception | LinkageError e)
                {
                    logServerDiagnostic(
                        player,
                        true,
                        "receiver_failure",
                        brokerFrame.id,
                        "addon=" + receiverOwner,
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
                "addon=" + receiverOwner,
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

        HotClientSelection hotSelection = null;

        if (receiver == null)
        {
            HotClientRoute route;

            synchronized (HOT_CLIENT_ROUTES)
            {
                route = HOT_CLIENT_ROUTES.get(brokerFrame.id);
            }

            if (route != null)
            {
                hotSelection = route.select(brokerFrame.id);
            }
        }

        if (receiver == null && hotSelection == null)
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

        String receiverOwner = receiver == null ? hotSelection.owner.pluginId() : receiver.ownerAddonId;

        if (!CLIENT_BUDGET.tryAcquire(
            CLIENT_BUDGET_OWNER,
            connectionIdentity,
            receiverOwner,
            brokerFrame.bytes.length
        ))
        {
            logClientDiagnostic(false, "rate_limited", brokerFrame.id,
                "addon=" + receiverOwner + " body_bytes=" + brokerFrame.bytes.length, null);
            return;
        }

        ClientBrokerReceiver queuedReceiver = receiver;
        HotClientSelection queuedHotSelection = hotSelection;

        Runnable delivery = () ->
        {
            try
            {
                if (queuedReceiver != null)
                {
                    queuedReceiver.receiver.receive(brokerFrame.id, wrapBytes(brokerFrame.bytes));
                }
                else
                {
                    queuedHotSelection.deliver(brokerFrame);
                }
            }
            catch (Exception | LinkageError e)
            {
                logClientDiagnostic(true, "receiver_failure", brokerFrame.id,
                    queuedReceiver == null ? "plugin=" + receiverOwner : "receiver=addon", e);
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

    public static PluginLease registerHotServerReceiver(
        HotReceiverSnapshot.Builder staged,
        ResourceLocation id,
        BBSAddonServerNetworkReceiver receiver
    )
    {
        return Objects.requireNonNull(staged, "staged").registerServerReceiver(id, receiver);
    }

    public static PluginLease registerHotClientReceiver(
        HotReceiverSnapshot.Builder staged,
        ResourceLocation id,
        BBSAddonClientNetworkReceiver receiver
    )
    {
        return Objects.requireNonNull(staged, "staged").registerClientReceiver(id, receiver);
    }

    private static PluginLease claimHotServerRoute(
        PluginOwner owner,
        ActivePluginIndex<HotReceiverSnapshot> activeIndex,
        ResourceLocation id
    )
    {
        validateHotAccess(owner, activeIndex, id);

        synchronized (SERVER_RECEIVERS)
        {
            ServerBrokerReceiver legacy = SERVER_RECEIVERS.get(id);

            if (legacy != null)
            {
                throw new IllegalStateException(
                    "duplicate addon network message id " + id + " kept by " + legacy.ownerAddonId
                );
            }
        }

        HotServerRoute route;

        synchronized (HOT_SERVER_ROUTES)
        {
            route = HOT_SERVER_ROUTES.get(id);

            if (route == null)
            {
                route = new HotServerRoute(owner.pluginId(), activeIndex);
                HOT_SERVER_ROUTES.put(id, route);
            }
            else if (!route.matches(owner.pluginId(), activeIndex))
            {
                throw new IllegalStateException(
                    "duplicate hot addon network message id " + id + " kept by " + route.pluginId()
                );
            }

            if (!route.claim(owner))
            {
                throw new IllegalStateException("duplicate hot server receiver for " + owner + " and " + id);
            }
        }

        HotServerRoute claimedRoute = route;

        return PluginLease.of(owner, "server broker route " + id,
            () -> releaseHotServerRoute(id, claimedRoute, owner));
    }

    private static PluginLease claimHotClientRoute(
        PluginOwner owner,
        ActivePluginIndex<HotReceiverSnapshot> activeIndex,
        ResourceLocation id
    )
    {
        validateHotAccess(owner, activeIndex, id);

        synchronized (CLIENT_RECEIVERS)
        {
            ClientBrokerReceiver legacy = CLIENT_RECEIVERS.get(id);

            if (legacy != null)
            {
                throw new IllegalStateException(
                    "duplicate addon network message id " + id + " kept by " + legacy.ownerAddonId
                );
            }
        }

        HotClientRoute route;

        synchronized (HOT_CLIENT_ROUTES)
        {
            route = HOT_CLIENT_ROUTES.get(id);

            if (route == null)
            {
                route = new HotClientRoute(owner.pluginId(), activeIndex);
                HOT_CLIENT_ROUTES.put(id, route);
            }
            else if (!route.matches(owner.pluginId(), activeIndex))
            {
                throw new IllegalStateException(
                    "duplicate hot addon network message id " + id + " kept by " + route.pluginId()
                );
            }

            if (!route.claim(owner))
            {
                throw new IllegalStateException("duplicate hot client receiver for " + owner + " and " + id);
            }
        }

        HotClientRoute claimedRoute = route;

        return PluginLease.of(owner, "client broker route " + id,
            () -> releaseHotClientRoute(id, claimedRoute, owner));
    }

    private static void releaseHotServerRoute(ResourceLocation id, HotServerRoute route, PluginOwner owner)
    {
        synchronized (HOT_SERVER_ROUTES)
        {
            if (HOT_SERVER_ROUTES.get(id) != route)
            {
                return;
            }

            route.release(owner);

            if (route.isEmpty())
            {
                HOT_SERVER_ROUTES.remove(id, route);
            }
        }
    }

    private static void releaseHotClientRoute(ResourceLocation id, HotClientRoute route, PluginOwner owner)
    {
        synchronized (HOT_CLIENT_ROUTES)
        {
            if (HOT_CLIENT_ROUTES.get(id) != route)
            {
                return;
            }

            route.release(owner);

            if (route.isEmpty())
            {
                HOT_CLIENT_ROUTES.remove(id, route);
            }
        }
    }

    private static <R extends HotRoute> int clearHotOwners(Map<ResourceLocation, R> routes, PluginOwner owner)
    {
        int removed = 0;
        var iterator = routes.entrySet().iterator();

        while (iterator.hasNext())
        {
            R route = iterator.next().getValue();

            if (route.release(owner))
            {
                removed += 1;
            }

            if (route.isEmpty())
            {
                iterator.remove();
            }
        }

        return removed;
    }

    private static void validateHotAccess(
        PluginOwner owner,
        ActivePluginIndex<HotReceiverSnapshot> activeIndex,
        ResourceLocation id
    )
    {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(activeIndex, "activeIndex");

        if (id == null)
        {
            throw new IllegalArgumentException("hot plugin network message id is null");
        }

        if (BBSMod.MOD_ID.equals(id.getNamespace()))
        {
            throw new IllegalArgumentException(
                "hot plugin broker namespace '" + BBSMod.MOD_ID + "' is reserved by BBS core"
            );
        }

        if (!owner.pluginId().equals(id.getNamespace()))
        {
            throw new IllegalArgumentException(
                "hot plugin network message namespace '" + id.getNamespace()
                    + "' does not match plugin id '" + owner.pluginId() + "'"
            );
        }

        if (id.toString().length() > MAX_MESSAGE_ID_LENGTH)
        {
            throw new IllegalArgumentException(
                "hot plugin network message id is longer than " + MAX_MESSAGE_ID_LENGTH + " characters"
            );
        }
    }

    private static String stringId(ResourceLocation id)
    {
        return id == null ? "<null>" : id.toString();
    }

    /** Immutable generation-owned receiver table used by hot route proxies. */
    public static final class HotReceiverSnapshot
    {
        private final PluginOwner owner;
        private final Map<ResourceLocation, BBSAddonServerNetworkReceiver> serverReceivers;
        private final Map<ResourceLocation, BBSAddonClientNetworkReceiver> clientReceivers;

        private HotReceiverSnapshot(
            PluginOwner owner,
            Map<ResourceLocation, BBSAddonServerNetworkReceiver> serverReceivers,
            Map<ResourceLocation, BBSAddonClientNetworkReceiver> clientReceivers
        )
        {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.serverReceivers = Collections.unmodifiableMap(new LinkedHashMap<>(serverReceivers));
            this.clientReceivers = Collections.unmodifiableMap(new LinkedHashMap<>(clientReceivers));
        }

        public static Builder builder(
            PluginOwner owner,
            ActivePluginIndex<HotReceiverSnapshot> activeIndex
        )
        {
            return new Builder(owner, activeIndex);
        }

        public PluginOwner owner()
        {
            return this.owner;
        }

        public int serverReceiverCount()
        {
            return this.serverReceivers.size();
        }

        public int clientReceiverCount()
        {
            return this.clientReceivers.size();
        }

        public BBSAddonServerNetworkReceiver serverReceiver(ResourceLocation id)
        {
            return this.serverReceivers.get(id);
        }

        public BBSAddonClientNetworkReceiver clientReceiver(ResourceLocation id)
        {
            return this.clientReceivers.get(id);
        }

        /** Mutable only during prepare; {@link #build()} seals registration. */
        public static final class Builder implements AutoCloseable
        {
            private final PluginOwner owner;
            private final ActivePluginIndex<HotReceiverSnapshot> activeIndex;
            private final Map<ResourceLocation, BBSAddonServerNetworkReceiver> serverReceivers =
                new LinkedHashMap<>();
            private final Map<ResourceLocation, BBSAddonClientNetworkReceiver> clientReceivers =
                new LinkedHashMap<>();
            private final java.util.List<PluginLease> registrations = new java.util.ArrayList<>();
            private HotReceiverSnapshot snapshot;
            private boolean closed;

            private Builder(PluginOwner owner, ActivePluginIndex<HotReceiverSnapshot> activeIndex)
            {
                this.owner = Objects.requireNonNull(owner, "owner");
                this.activeIndex = Objects.requireNonNull(activeIndex, "activeIndex");
            }

            public PluginOwner owner()
            {
                return this.owner;
            }

            public ActivePluginIndex<HotReceiverSnapshot> activeIndex()
            {
                return this.activeIndex;
            }

            public synchronized PluginLease registerServerReceiver(
                ResourceLocation id,
                BBSAddonServerNetworkReceiver receiver
            )
            {
                this.ensureAccepting();

                if (receiver == null)
                {
                    throw new IllegalArgumentException("hot plugin server network receiver is null");
                }

                if (this.serverReceivers.containsKey(id))
                {
                    throw new IllegalStateException("duplicate hot server receiver id " + stringId(id));
                }

                PluginLease route = claimHotServerRoute(this.owner, this.activeIndex, id);
                this.serverReceivers.put(id, receiver);
                PluginLease registration = PluginLease.of(this.owner, "staged server broker receiver " + id, () ->
                {
                    route.close();

                    synchronized (Builder.this)
                    {
                        if (Builder.this.snapshot == null)
                        {
                            Builder.this.serverReceivers.remove(id, receiver);
                        }
                    }
                });
                this.registrations.add(registration);

                return registration;
            }

            public synchronized PluginLease registerClientReceiver(
                ResourceLocation id,
                BBSAddonClientNetworkReceiver receiver
            )
            {
                this.ensureAccepting();

                if (receiver == null)
                {
                    throw new IllegalArgumentException("hot plugin client network receiver is null");
                }

                if (this.clientReceivers.containsKey(id))
                {
                    throw new IllegalStateException("duplicate hot client receiver id " + stringId(id));
                }

                PluginLease route = claimHotClientRoute(this.owner, this.activeIndex, id);
                this.clientReceivers.put(id, receiver);
                PluginLease registration = PluginLease.of(this.owner, "staged client broker receiver " + id, () ->
                {
                    route.close();

                    synchronized (Builder.this)
                    {
                        if (Builder.this.snapshot == null)
                        {
                            Builder.this.clientReceivers.remove(id, receiver);
                        }
                    }
                });
                this.registrations.add(registration);

                return registration;
            }

            public synchronized HotReceiverSnapshot build()
            {
                if (this.closed)
                {
                    throw new IllegalStateException("hot receiver builder is closed for " + this.owner);
                }

                if (this.snapshot == null)
                {
                    this.snapshot = new HotReceiverSnapshot(
                        this.owner,
                        this.serverReceivers,
                        this.clientReceivers
                    );
                    this.serverReceivers.clear();
                    this.clientReceivers.clear();
                }

                return this.snapshot;
            }

            @Override
            public void close()
            {
                java.util.List<PluginLease> snapshot;

                synchronized (this)
                {
                    if (this.closed)
                    {
                        return;
                    }

                    this.closed = true;
                    snapshot = new java.util.ArrayList<>(this.registrations);
                    this.registrations.clear();
                    this.serverReceivers.clear();
                    this.clientReceivers.clear();
                }

                Throwable failure = null;

                for (int index = snapshot.size() - 1; index >= 0; index -= 1)
                {
                    try
                    {
                        snapshot.get(index).close();
                    }
                    catch (Throwable throwable)
                    {
                        if (failure == null)
                        {
                            failure = throwable;
                        }
                        else if (failure != throwable)
                        {
                            failure.addSuppressed(throwable);
                        }
                    }
                }

                rethrowCloseFailure(failure);
            }

            private void ensureAccepting()
            {
                if (this.closed)
                {
                    throw new IllegalStateException("hot receiver builder is closed for " + this.owner);
                }

                if (this.snapshot != null)
                {
                    throw new IllegalStateException("hot receiver snapshot is already sealed for " + this.owner);
                }
            }
        }
    }

    private abstract static class HotRoute
    {
        private final String pluginId;
        private final ActivePluginIndex<HotReceiverSnapshot> activeIndex;
        private final Set<PluginOwner> owners = new HashSet<>();

        private HotRoute(String pluginId, ActivePluginIndex<HotReceiverSnapshot> activeIndex)
        {
            this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
            this.activeIndex = Objects.requireNonNull(activeIndex, "activeIndex");
        }

        final boolean matches(String pluginId, ActivePluginIndex<HotReceiverSnapshot> activeIndex)
        {
            return this.pluginId.equals(pluginId) && this.activeIndex == activeIndex;
        }

        final synchronized boolean claim(PluginOwner owner)
        {
            return this.owners.add(owner);
        }

        final synchronized boolean release(PluginOwner owner)
        {
            return this.owners.remove(owner);
        }

        final synchronized boolean isEmpty()
        {
            return this.owners.isEmpty();
        }

        final synchronized PluginGenerationLease<HotReceiverSnapshot> acquireCurrent()
        {
            PluginGenerationLease<HotReceiverSnapshot> lease = this.activeIndex.acquire(this.pluginId);

            if (lease == null || !this.owners.contains(lease.owner()))
            {
                if (lease != null)
                {
                    lease.close();
                }

                return null;
            }

            return lease;
        }

        final synchronized PluginGenerationLease<HotReceiverSnapshot> acquire(PluginOwner owner)
        {
            if (!this.owners.contains(owner))
            {
                return null;
            }

            return this.activeIndex.acquire(owner);
        }

        final String pluginId()
        {
            return this.pluginId;
        }
    }

    private static final class HotServerRoute extends HotRoute
    {
        private HotServerRoute(String pluginId, ActivePluginIndex<HotReceiverSnapshot> activeIndex)
        {
            super(pluginId, activeIndex);
        }

        private HotServerSelection select(ResourceLocation id)
        {
            PluginGenerationLease<HotReceiverSnapshot> lease = this.acquireCurrent();

            if (lease == null)
            {
                return null;
            }

            try (lease)
            {
                HotReceiverSnapshot snapshot = lease.contributions();

                return snapshot != null
                    && lease.owner().equals(snapshot.owner)
                    && snapshot.serverReceiver(id) != null
                        ? new HotServerSelection(this, lease.owner())
                        : null;
            }
        }
    }

    private static final class HotClientRoute extends HotRoute
    {
        private HotClientRoute(String pluginId, ActivePluginIndex<HotReceiverSnapshot> activeIndex)
        {
            super(pluginId, activeIndex);
        }

        private HotClientSelection select(ResourceLocation id)
        {
            PluginGenerationLease<HotReceiverSnapshot> lease = this.acquireCurrent();

            if (lease == null)
            {
                return null;
            }

            try (lease)
            {
                HotReceiverSnapshot snapshot = lease.contributions();

                return snapshot != null
                    && lease.owner().equals(snapshot.owner)
                    && snapshot.clientReceiver(id) != null
                        ? new HotClientSelection(this, lease.owner())
                        : null;
            }
        }
    }

    private record HotServerSelection(HotServerRoute route, PluginOwner owner)
    {
        private void deliver(MinecraftServer server, ServerPlayer player, BrokerFrame frame)
        {
            PluginGenerationLease<HotReceiverSnapshot> lease = this.route.acquire(this.owner);

            if (lease == null)
            {
                return;
            }

            try (lease)
            {
                HotReceiverSnapshot snapshot = lease.contributions();
                BBSAddonServerNetworkReceiver receiver = snapshot == null
                    ? null
                    : snapshot.serverReceiver(frame.id);

                if (snapshot != null && this.owner.equals(snapshot.owner) && receiver != null)
                {
                    receiver.receive(server, player, frame.id, wrapBytes(frame.bytes));
                }
            }
        }
    }

    private record HotClientSelection(HotClientRoute route, PluginOwner owner)
    {
        private void deliver(BrokerFrame frame)
        {
            PluginGenerationLease<HotReceiverSnapshot> lease = this.route.acquire(this.owner);

            if (lease == null)
            {
                return;
            }

            try (lease)
            {
                HotReceiverSnapshot snapshot = lease.contributions();
                BBSAddonClientNetworkReceiver receiver = snapshot == null
                    ? null
                    : snapshot.clientReceiver(frame.id);

                if (snapshot != null && this.owner.equals(snapshot.owner) && receiver != null)
                {
                    receiver.receive(frame.id, wrapBytes(frame.bytes));
                }
            }
        }
    }

    private static void rethrowCloseFailure(Throwable failure)
    {
        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }

        if (failure instanceof Error error)
        {
            throw error;
        }

        if (failure != null)
        {
            throw new IllegalStateException("Failed to close hot broker registrations", failure);
        }
    }

    private record BrokerFrame(ResourceLocation id, byte[] bytes) {}

    private record ServerBrokerReceiver(String ownerAddonId, BBSAddonServerNetworkReceiver receiver) {}

    private record ClientBrokerReceiver(String ownerAddonId, BBSAddonClientNetworkReceiver receiver) {}
}
