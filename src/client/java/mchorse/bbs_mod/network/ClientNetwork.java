package mchorse.bbs_mod.network;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.entity.IEntityFormProvider;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import mchorse.bbs_mod.network.compat.AddonPayloadBroker;
import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ClientNetwork
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network-client");
    private static final ManagerCallbackRegistry callbacks = new ManagerCallbackRegistry();
    private static final ClientPacketCrusher crusher = new ClientPacketCrusher();
    private static final NetworkConnectionGate connectionGate = new NetworkConnectionGate();
    private static boolean lifecycleListenerRegistered;

    private static volatile boolean isBBSModOnServer;

    public static void resetHandshake()
    {
        Minecraft client = Minecraft.getInstance();
        UUID previous = connectionGate.rotate(getCurrentTransport(client), client.player);

        crusher.clearOwner(previous);
        AddonPayloadBroker.clearClientConnection(previous);
        AddonPayloadBroker.resetClientBudget();
        callbacks.reset();
        isBBSModOnServer = false;
    }

    public static void onClientPlayerClone(Connection connection, LocalPlayer oldPlayer, LocalPlayer newPlayer)
    {
        Minecraft client = Minecraft.getInstance();

        if (connection == null || oldPlayer == null || newPlayer == null || !connection.isConnected())
        {
            LOGGER.warn("[bbs-network-client] rejected client-player clone with an incomplete transport scope");
            return;
        }

        NetworkConnectionGate.Scope replacement = connectionGate.replacePlayer(
            connection,
            oldPlayer,
            newPlayer,
            getCurrentTransport(client),
            client.player
        );

        if (replacement == null)
        {
            LOGGER.warn("[bbs-network-client] rejected client-player clone outside the current exact transport scope");
            return;
        }

        clearRetiredTransfer(replacement);
        callbacks.reset();
    }

    public static boolean isBBSModOnServer()
    {
        return isBBSModOnServer;
    }

    @Deprecated(forRemoval = false)
    public static boolean isIsBBSModOnServer()
    {
        return isBBSModOnServer();
    }

    /* Network */

    public static void setup()
    {
        registerClientReceiver(ServerNetwork.CLIENT_CLICKED_MODEL_BLOCK_PACKET, ClientNetwork::handleClientModelBlockPacket);
        registerClientReceiver(ServerNetwork.CLIENT_PLAYER_FORM_PACKET, ClientNetwork::handlePlayerFormPacket);
        registerClientReceiver(ServerNetwork.CLIENT_PLAY_FILM_PACKET, ClientNetwork::handlePlayFilmPacket);
        registerClientReceiver(ServerNetwork.CLIENT_MANAGER_DATA_PACKET, ClientNetwork::handleManagerDataPacket);
        registerClientReceiver(ServerNetwork.CLIENT_STOP_FILM_PACKET, ClientNetwork::handleStopFilmPacket);
        registerClientReceiver(ServerNetwork.CLIENT_HANDSHAKE, ClientNetwork::handleHandshakePacket);
        registerClientReceiver(ServerNetwork.CLIENT_RECORDED_ACTIONS, ClientNetwork::handleRecordedActionsPacket);
        registerClientReceiver(ServerNetwork.CLIENT_ANIMATION_STATE_TRIGGER, ClientNetwork::handleFormTriggerPacket);
        registerClientReceiver(ServerNetwork.CLIENT_CHEATS_PERMISSION, ClientNetwork::handleCheatsPermissionPacket);
        registerClientReceiver(ServerNetwork.CLIENT_SHARED_FORM, ClientNetwork::handleShareFormPacket);
        registerClientReceiver(ServerNetwork.CLIENT_ENTITY_FORM, ClientNetwork::handleEntityFormPacket);
        registerClientReceiver(ServerNetwork.CLIENT_ACTORS, ClientNetwork::handleActorsPacket);
        registerClientReceiver(ServerNetwork.CLIENT_GUN_PROPERTIES, ClientNetwork::handleGunPropertiesPacket);
        registerClientReceiver(ServerNetwork.CLIENT_PAUSE_FILM, ClientNetwork::handlePauseFilmPacket);
        registerClientReceiver(ServerNetwork.CLIENT_SELECTED_SLOT, ClientNetwork::handleSelectedSlotPacket);
        registerClientReceiver(ServerNetwork.CLIENT_ANIMATION_STATE_MODEL_BLOCK_TRIGGER, ClientNetwork::handleAnimationStateModelBlockPacket);
        registerClientReceiver(ServerNetwork.CLIENT_REFRESH_MODEL_BLOCKS, ClientNetwork::handleRefreshModelBlocksPacket);
        registerClientReceiver(ServerNetwork.CLIENT_REQUEST_FILM_RESYNC, ClientNetwork::handleRequestFilmResync);
        registerClientReceiver(ServerNetwork.CLIENT_ADDON_BROKER, ClientNetwork::handleAddonBrokerPacket);

        if (!lifecycleListenerRegistered)
        {
            NeoForge.EVENT_BUS.addListener(ClientNetwork::handleClientTick);
            lifecycleListenerRegistered = true;
        }
    }

    private static void handleClientTick(ClientTickEvent.Post event)
    {
        crusher.expireIdleTransfers();
        AddonPayloadBroker.expireClientBudgetIdle();
    }

    private static void registerClientReceiver(ResourceLocation channel, ClientPayloadHandler handler)
    {
        NetworkCompatClient.registerCoreClientReceiver(channel, (buf, connection, player) ->
        {
            Minecraft client = Minecraft.getInstance();
            ClientPayloadScope scope = captureScope(client, connection, player);

            if (scope != null)
            {
                handler.handle(client, scope, buf);
            }
        });
    }

    private static ClientPayloadScope captureScope(Minecraft client, Connection connection, LocalPlayer player)
    {
        Connection currentConnection = getCurrentTransport(client);
        LocalPlayer currentPlayer = client.player;
        ClientLevel currentLevel = client.level;

        if (connection == null || player == null || !connection.isConnected())
        {
            return null;
        }

        NetworkConnectionGate.Scope gate = connectionGate.capture(
            connection,
            player,
            currentConnection,
            currentPlayer
        );

        if (gate == null)
        {
            return null;
        }

        clearRetiredTransfer(gate);

        return new ClientPayloadScope(connection, player, currentLevel, gate);
    }

    /** Release only chunks from the player scope retired by this atomic gate transition. */
    private static void clearRetiredTransfer(NetworkConnectionGate.Scope scope)
    {
        Object retiredTransferIdentity = scope.retiredTransferIdentity();

        if (retiredTransferIdentity != null)
        {
            crusher.clearConnection(scope.generation(), retiredTransferIdentity);
        }
    }

    private static Connection getCurrentTransport(Minecraft client)
    {
        ClientPacketListener listener = client.getConnection();

        return listener == null ? null : listener.getConnection();
    }

    private static void executeIfCurrent(Minecraft client, ClientPayloadScope scope, boolean requireLevel, Runnable runnable)
    {
        client.execute(() ->
        {
            if (!isScopeCurrent(client, scope)
                || (requireLevel && (scope.level() == null || client.level != scope.level())))
            {
                return;
            }

            runnable.run();
        });
    }

    private static boolean isScopeCurrent(Minecraft client, ClientPayloadScope scope)
    {
        return scope != null
            && scope.connection().isConnected()
            && connectionGate.isCurrent(scope.gate(), getCurrentTransport(client), client.player);
    }

    private static void handleAddonBrokerPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        AddonPayloadBroker.handleClientPayload(
            buf,
            scope.generation(),
            (task) -> executeIfCurrent(client, scope, true, task)
        );
    }

    @FunctionalInterface
    private interface ClientPayloadHandler
    {
        void handle(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf);
    }

    private record ClientPayloadScope(
        Connection connection,
        LocalPlayer player,
        ClientLevel level,
        NetworkConnectionGate.Scope gate
    )
    {
        private UUID generation()
        {
            return this.gate.generation();
        }

        private Object transferIdentity()
        {
            return this.gate.transferIdentity();
        }
    }

    /* Handlers */

    private static void handleClientModelBlockPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        if (buf.readableBytes() != Long.BYTES)
        {
            return;
        }

        BlockPos pos = buf.readBlockPos();

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            BlockEntity entity = level.getBlockEntity(pos);

            if (!(entity instanceof ModelBlockEntity))
            {
                return;
            }

            UIBaseMenu menu = UIScreen.getCurrentMenu();
            UIDashboard dashboard = BBSModClient.getDashboard();

            if (menu != dashboard)
            {
                UIScreen.open(dashboard);
            }

            UIModelBlockPanel panel = dashboard.getPanels().getPanel(UIModelBlockPanel.class);

            dashboard.setPanel(panel);
            panel.fill((ModelBlockEntity) entity, true);
            dashboard.focusModelBlock((ModelBlockEntity) entity);
        });
    }

    private static void handlePlayerFormPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork.CLIENT_PLAYER_FORM_PACKET, buf, (bytes, packetByteBuf) ->
        {
            if (packetByteBuf.readableBytes() != Integer.BYTES)
            {
                return;
            }

            int id = packetByteBuf.readInt();
            BaseType decoded = NetworkDataDecoder.decode(bytes);

            if (bytes != null && decoded == null)
            {
                return;
            }

            executeIfCurrent(client, scope, true, () ->
            {
                if (client.level != level)
                {
                    return;
                }

                try
                {
                    Form form = FormUtils.fromData(decoded);
                    Entity entity = level.getEntity(id);
                    Morph morph = Morph.getMorph(entity);

                    if (morph != null)
                    {
                        morph.setForm(form);
                    }
                }
                catch (RuntimeException | LinkageError exception)
                {
                    LOGGER.warn("[bbs-network-client] rejected player Form during client-thread construction", exception);
                }
            });
        });
    }

    private static void handlePlayFilmPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork.CLIENT_PLAY_FILM_PACKET, buf, (bytes, packetByteBuf) ->
        {
            String filmId;
            boolean withCamera;

            try
            {
                filmId = packetByteBuf.readUtf();
                withCamera = packetByteBuf.readBoolean();
            }
            catch (RuntimeException e)
            {
                return;
            }

            if (packetByteBuf.isReadable())
            {
                return;
            }

            BaseType decoded = NetworkDataDecoder.decode(bytes);

            if (!(decoded instanceof MapType))
            {
                return;
            }

            executeIfCurrent(client, scope, true, () ->
            {
                if (client.level != level)
                {
                    return;
                }

                try
                {
                    Film film = new Film();

                    film.setId(filmId);
                    film.fromData(decoded);
                    Films.playFilm(film, withCamera);
                }
                catch (RuntimeException | LinkageError exception)
                {
                    LOGGER.warn("[bbs-network-client] rejected Film during client-thread construction", exception);
                }
            });
        });
    }

    private static void handleManagerDataPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork.CLIENT_MANAGER_DATA_PACKET, buf, (bytes, packetByteBuf) ->
        {
            if (packetByteBuf.readableBytes() != Integer.BYTES * 2)
            {
                return;
            }

            int callbackId = packetByteBuf.readInt();
            int operationId = packetByteBuf.readInt();

            if (operationId < 0 || operationId >= RepositoryOperation.values().length)
            {
                executeIfCurrent(client, scope, false, () -> callbacks.remove(callbackId));
                return;
            }

            BaseType data = NetworkDataDecoder.decode(bytes);

            if (data == null)
            {
                executeIfCurrent(client, scope, false, () -> callbacks.remove(callbackId));
                return;
            }

            executeIfCurrent(client, scope, false, () ->
            {
                Consumer<BaseType> callback = callbacks.remove(callbackId);

                if (callback != null)
                {
                    try
                    {
                        callback.accept(data);
                    }
                    catch (RuntimeException | LinkageError exception)
                    {
                        LOGGER.warn("[bbs-network-client] rejected manager callback data during client-thread construction", exception);
                    }
                }
            });
        });
    }

    private static void handleStopFilmPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        String filmId;

        try
        {
            filmId = buf.readUtf();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, false, () -> Films.stopFilm(filmId));
    }

    private static void handleHandshakePacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        try
        {
            buf.readUtf();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, false, () -> isBBSModOnServer = true);
    }

    private static void handleRecordedActionsPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork.CLIENT_RECORDED_ACTIONS, buf, (bytes, packetByteBuf) ->
        {
            String filmId;
            int replayId;
            int tick;
            ServerNetwork.RecordingTerminal terminal = ServerNetwork.RecordingTerminal.LEGACY_MANUAL;

            try
            {
                filmId = packetByteBuf.readUtf();
                replayId = packetByteBuf.readInt();
                tick = packetByteBuf.readInt();
            }
            catch (RuntimeException e)
            {
                return;
            }

            if (packetByteBuf.isReadable())
            {
                /* c7 keeps the legacy footer for manual stops. Typed terminal
                 * outcomes append one marker byte; reject any other shape
                 * before touching the local recorder. */
                if (packetByteBuf.readableBytes() != 1)
                {
                    return;
                }

                terminal = ServerNetwork.RecordingTerminal.fromId(packetByteBuf.readUnsignedByte());

                if (terminal == null)
                {
                    return;
                }
            }

            final ServerNetwork.RecordingTerminal recordingTerminal = terminal;

            BaseType data = NetworkDataDecoder.decode(bytes);

            if (data == null || !data.isList())
            {
                return;
            }

            executeIfCurrent(client, scope, true, () ->
            {
                Films films = BBSModClient.getFilms();

                if (films == null)
                {
                    return;
                }

                Films.ManualRecordingTerminal manualTerminal = films.consumeManualRecordingTerminal(filmId, replayId, tick);

                if (manualTerminal == Films.ManualRecordingTerminal.CANCELED_BEFORE_START)
                {
                    return;
                }

                Recorder recorder = null;
                boolean mergeAllowed = manualTerminal == Films.ManualRecordingTerminal.STOPPED_AFTER_START;

                if (manualTerminal == Films.ManualRecordingTerminal.NONE)
                {
                    Recorder candidate = films.getRecorder();

                    try
                    {
                        recorder = films.stopRecordingFromServer(filmId, replayId, tick);
                    }
                    catch (RuntimeException | Error exception)
                    {
                        recorder = candidate != null && films.getRecorder() != candidate ? candidate : null;
                        LOGGER.error("[bbs-network-client] failed to finish matching server-driven Film recording {}", filmId, exception);
                    }

                    mergeAllowed = recorder != null && recorder.isInCurrentLevel();
                }

                boolean hasRecordedActions = !data.asList().isEmpty();
                boolean startRejected = recordingTerminal == ServerNetwork.RecordingTerminal.START_REJECTED;
                boolean applyKeyframes = mergeAllowed
                    && !startRejected
                    && recorder != null
                    && recorder.hasRecordedFrame();
                boolean mergeActions = mergeAllowed
                    && !startRejected
                    && (hasRecordedActions
                        || (manualTerminal == Films.ManualRecordingTerminal.STOPPED_AFTER_START
                            && recordingTerminal != ServerNetwork.RecordingTerminal.SERVER_FORCED));

                UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

                try
                {
                    UIFilmPanel panel = dashboard == null ? null : dashboard.getPanels().getPanel(UIFilmPanel.class);

                    if (panel != null)
                    {
                        panel.receiveActions(
                            filmId,
                            replayId,
                            tick,
                            data,
                            recorder,
                            applyKeyframes,
                            mergeActions,
                            recordingTerminal
                        );
                    }
                }
                catch (RuntimeException | Error exception)
                {
                    LOGGER.error("[bbs-network-client] failed to merge recorded Film actions {}", filmId, exception);
                }
            });
        });
    }

    private static void handleFormTriggerPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;
        int id;
        String triggerId;
        int type;

        try
        {
            id = buf.readInt();
            triggerId = buf.readUtf(NetworkDirectActionGate.MAX_ANIMATION_TRIGGER_LENGTH);
            type = buf.readInt();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable()
            || !NetworkDirectActionGate.isAnimationTriggerAllowed(triggerId)
            || type < ServerNetwork.STATE_TRIGGER_MORPH
            || type > ServerNetwork.STATE_TRIGGER_OFF_HAND_ITEM)
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            Entity entity = level.getEntity(id);
            Morph morph = Morph.getMorph(entity);

            if (type == ServerNetwork.STATE_TRIGGER_MORPH && morph != null && morph.getForm() != null)
            {
                morph.getForm().playState(triggerId);
            }

            if (entity instanceof LivingEntity livingEntity && type > 0)
            {
                ItemStack stackInHand = livingEntity.getItemInHand(type == 1 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                ModelProperties properties = BBSModClient.getItemStackProperties(stackInHand);

                if (properties != null && properties.getForm() != null)
                {
                    properties.getForm().playState(triggerId);
                }
            }
        });
    }

    private static void handleCheatsPermissionPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        if (buf.readableBytes() != 1)
        {
            return;
        }

        boolean cheats = buf.readBoolean();

        executeIfCurrent(client, scope, true, () ->
        {
            client.player.setPermissionLevel(cheats ? 4 : 0);
        });
    }

    private static void handleShareFormPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork.CLIENT_SHARED_FORM, buf, (bytes, packetByteBuf) ->
        {
            if (packetByteBuf.isReadable())
            {
                return;
            }

            BaseType decoded = NetworkDataDecoder.decode(bytes);

            if (!(decoded instanceof MapType))
            {
                return;
            }

            executeIfCurrent(client, scope, true, () ->
            {
                try
                {
                    Form form = FormUtils.fromData(decoded);

                    if (form == null)
                    {
                        return;
                    }

                    UIDashboard dashboard = BBSModClient.getDashboard();

                    /* Receiving shared data must never open a menu or switch the target's
                     * active panel. Keep the form available in Recent and show the normal
                     * notification in whichever UI context the target already owns. */
                    BBSModClient.getFormCategories().getRecentForms().getCategories().get(0).addForm(form);
                    dashboard.context.notifyInfo(UIKeys.FORMS_SHARED_NOTIFICATION.format(form.getDisplayName()));
                }
                catch (RuntimeException | LinkageError exception)
                {
                    LOGGER.warn("[bbs-network-client] rejected shared Form during client-thread construction", exception);
                }
            });
        });
    }

    private static void handleEntityFormPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        crusher.receive(scope.generation(), scope.transferIdentity(), ServerNetwork.CLIENT_ENTITY_FORM, buf, (bytes, packetByteBuf) ->
        {
            if (packetByteBuf.readableBytes() != Integer.BYTES)
            {
                return;
            }

            BaseType decoded = NetworkDataDecoder.decode(bytes);

            if (!(decoded instanceof MapType))
            {
                return;
            }

            int entityId = packetByteBuf.readInt();

            executeIfCurrent(client, scope, true, () ->
            {
                if (client.level != level)
                {
                    return;
                }

                try
                {
                    Form form = FormUtils.fromData(decoded);
                    Entity entity = level.getEntity(entityId);

                    if (form != null && entity instanceof IEntityFormProvider provider)
                    {
                        provider.setForm(form);
                    }
                }
                catch (RuntimeException | LinkageError exception)
                {
                    LOGGER.warn("[bbs-network-client] rejected entity Form during client-thread construction", exception);
                }
            });
        });
    }

    private static void handleActorsPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;
        Map<String, Integer> actors = new HashMap<>();
        String filmId;
        int count;

        try
        {
            filmId = buf.readUtf();
            count = buf.readInt();

            if (count < 0 || count > ServerNetwork.MAX_ACTOR_ENTRIES || (long) count * 5L > buf.readableBytes())
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                String key = buf.readUtf();
                int entityId = buf.readInt();

                actors.put(key, entityId);
            }
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            UIDashboard dashboard = BBSModClient.getDashboard();
            UIFilmPanel panel = dashboard.getPanel(UIFilmPanel.class);

            panel.updateActors(filmId, actors);
            BBSModClient.getFilms().updateActors(filmId, actors);
        });
    }

    private static void handleGunPropertiesPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        if (buf.readableBytes() < Integer.BYTES)
        {
            return;
        }

        int entityId = buf.readInt();
        byte[] encodedProperties = new byte[buf.readableBytes()];

        buf.readBytes(encodedProperties);

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            FriendlyByteBuf propertiesBuffer = NetworkCompat.createBuffer();

            try
            {
                propertiesBuffer.writeBytes(encodedProperties);

                GunProperties properties = new GunProperties();
                properties.fromNetwork(propertiesBuffer);

                if (propertiesBuffer.isReadable())
                {
                    return;
                }

                Entity entity = level.getEntity(entityId);

                if (entity instanceof GunProjectileEntity projectile)
                {
                    projectile.setProperties(properties);
                    projectile.refreshDimensions();
                }
            }
            catch (RuntimeException | LinkageError exception)
            {
                LOGGER.warn("[bbs-network-client] rejected GunProperties during client-thread construction", exception);
            }
            finally
            {
                propertiesBuffer.release();
            }
        });
    }

    private static void handlePauseFilmPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;
        String filmId;

        try
        {
            filmId = buf.readUtf();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            Films.togglePauseFilm(filmId);
        });
    }

    private static void handleSelectedSlotPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        if (buf.readableBytes() != Integer.BYTES)
        {
            return;
        }

        int slot = buf.readInt();

        if (slot < 0 || slot >= 9)
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            client.player.getInventory().selected = slot;
        });
    }

    private static void handleAnimationStateModelBlockPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;
        BlockPos pos;
        String state;

        try
        {
            pos = buf.readBlockPos();
            state = buf.readUtf();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof ModelBlockEntity block)
            {
                if (block.getProperties().getForm() != null)
                {
                    block.getProperties().getForm().playState(state);
                }
            }
        });
    }

    private static void handleRefreshModelBlocksPacket(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;

        if (buf.readableBytes() != Integer.BYTES)
        {
            return;
        }

        int range = buf.readInt();

        if (range < 0 || range > ServerNetwork.MAX_MODEL_BLOCK_REFRESH_TICKS)
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            for (ModelBlockEntity mb : BBSRendering.capturedModelBlocks)
            {
                try
                {
                    ModelProperties properties = mb.getProperties();
                    int random = (int) (Math.random() * range);

                    properties.setForm(FormUtils.copy(properties.getForm()));

                    while (random > 0)
                    {
                        properties.update(mb.getEntity());

                        random -= 1;
                    }
                }
                catch (RuntimeException | LinkageError exception)
                {
                    LOGGER.warn("[bbs-network-client] rejected model-block Form refresh during client-thread construction", exception);
                }
            }
        });
    }

    private static void handleRequestFilmResync(Minecraft client, ClientPayloadScope scope, FriendlyByteBuf buf)
    {
        ClientLevel level = client.level;
        String filmId;

        try
        {
            filmId = buf.readUtf();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        executeIfCurrent(client, scope, true, () ->
        {
            if (client.level != level)
            {
                return;
            }

            UIFilmPanel panel = BBSModClient.getDashboard().getPanel(UIFilmPanel.class);
            Film film = panel == null ? null : panel.getData();

            /* Server lost track of a path we edited — re-send the whole film
             * (root path) so it can rebuild its copy via film.fromData(...). */
            if (film != null && film.getId().equals(filmId))
            {
                sendSyncData(filmId, film);
            }
        });
    }

    /* API */
    
    public static void sendModelBlockForm(BlockPos pos, ModelBlockEntity modelBlock)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_MODEL_BLOCK_FORM_PACKET, modelBlock.getProperties().toData(), (packetByteBuf) ->
        {
            packetByteBuf.writeBlockPos(pos);
        });
    }

    public static void sendPlayerForm(Form form)
    {
        MapType mapType = FormUtils.toData(form);

        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_PLAYER_FORM_PACKET, mapType == null ? new MapType() : mapType, (packetByteBuf) ->
        {});
    }

    public static void sendModelBlockTransforms(MapType data)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, data, (packetByteBuf) ->
        {});
    }

    public static void sendManagerDataLoad(String id, Consumer<BaseType> consumer)
    {
        MapType mapType = new MapType();

        mapType.putString("id", id);
        ClientNetwork.sendManagerData(RepositoryOperation.LOAD, mapType, consumer);
    }

    public static void sendManagerData(RepositoryOperation op, BaseType data, Consumer<BaseType> consumer)
    {
        int id = callbacks.register(consumer);

        try
        {
            sendManagerData(id, op, data);
        }
        catch (RuntimeException e)
        {
            callbacks.remove(id);
            throw e;
        }
    }

    public static void sendManagerData(int callbackId, RepositoryOperation op, BaseType data)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_MANAGER_DATA_PACKET, data, (packetByteBuf) ->
        {
            packetByteBuf.writeInt(callbackId);
            packetByteBuf.writeInt(op.ordinal());
        });
    }

    public static void sendActionRecording(String filmId, int replayId, int tick, int countdown, boolean state)
    {
        if (filmId == null
            || filmId.isBlank()
            || replayId < 0
            || tick < 0
            || countdown < 0
            || countdown > NetworkMutationPolicy.MAX_RECORDING_COUNTDOWN_TICKS
            || (!state && countdown != 0))
        {
            return;
        }

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);
        buf.writeInt(replayId);
        buf.writeInt(tick);
        buf.writeInt(countdown);
        buf.writeBoolean(state);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ACTION_RECORDING, buf);
    }

    public static void sendToggleFilm(String filmId, boolean withCamera)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);
        buf.writeBoolean(withCamera);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_TOGGLE_FILM, buf);
    }

    public static void sendActionState(String filmId, ActionState state, int tick)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);
        buf.writeByte(state.ordinal());
        buf.writeInt(tick);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ACTION_CONTROL, buf);
    }

    public static void sendSyncData(String filmId, BaseValue data)
    {
        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_FILM_DATA_SYNC, data.toData(), (packetByteBuf) ->
        {
            DataPath path = data.getPath();

            packetByteBuf.writeUtf(filmId);
            packetByteBuf.writeInt(path.strings.size());

            for (String string : path.strings)
            {
                packetByteBuf.writeUtf(string);
            }
        });
    }

    public static void sendTeleport(Player entity, double x, double y, double z)
    {
        sendTeleport(x, y, z, entity.getYHeadRot(), entity.getYRot(), entity.getXRot());
    }

    public static void sendTeleport(double x, double y, double z, float yaw, float bodyYaw, float pitch)
    {
        if (!NetworkMutationPolicy.isTeleportAllowed(x, y, z, yaw, bodyYaw, pitch))
        {
            return;
        }

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(Mth.wrapDegrees(yaw));
        buf.writeFloat(Mth.wrapDegrees(bodyYaw));
        buf.writeFloat(pitch);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_PLAYER_TP, buf);
    }

    public static void sendFormTrigger(String triggerId, int type)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(triggerId);
        buf.writeInt(type);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ANIMATION_STATE_TRIGGER, buf);
    }

    public static void sendSharedForm(Form form, UUID uuid)
    {
        MapType mapType = FormUtils.toData(form);

        crusher.send(Minecraft.getInstance().player, ServerNetwork.SERVER_SHARED_FORM, mapType == null ? new MapType() : mapType, (packetByteBuf) ->
        {
            packetByteBuf.writeUUID(uuid);
        });
    }

    public static void sendZoom(boolean zoom)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeBoolean(zoom);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ZOOM, buf);
    }

    public static void sendPauseFilm(String filmId)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_PAUSE_FILM, buf);
    }

    /** Send first-person replay equipment at the editor cursor to the server. */
    public static void sendApplyFilmPlayerSettingsToPlayer(Film film, int tick)
    {
        Replay replay = film == null ? null : film.getFirstPersonReplay();
        byte[] dressBytes = replay == null
            ? new byte[0]
            : DataStorageUtils.writeToBytes(replay.keyframes.packEquipment(tick));

        if (dressBytes.length > ServerNetwork.MAX_APPLY_FILM_PLAYER_SETTINGS_EQUIPMENT_BYTES)
        {
            LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=send result=reject reason=equipment_size size={} max={}",
                dressBytes.length,
                ServerNetwork.MAX_APPLY_FILM_PLAYER_SETTINGS_EQUIPMENT_BYTES);
            return;
        }

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeFloat(film.hp.get());
        buf.writeFloat(film.hunger.get());
        buf.writeInt(film.xpLevel.get());
        buf.writeFloat(film.xpProgress.get());
        buf.writeInt(replay == null ? 0 : replay.keyframes.getSelectedSlot(tick));
        buf.writeInt(dressBytes.length);
        buf.writeBytes(dressBytes);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_APPLY_FILM_PLAYER_SETTINGS, buf);
    }
}
