package mchorse.bbs_mod.network;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.ActionPlayer;
import mchorse.bbs_mod.actions.AuthorizedCommandExecutor;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.actions.ActionRecorder;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.actions.FilmActionAuthorityPolicy;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.actions.PlayerType;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ByteType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.entity.IEntityFormProvider;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.items.GunProjectileBudget;
import mchorse.bbs_mod.items.GunPropertiesPolicy;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.network.compat.AddonPayloadBroker;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ServerNetwork
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");
    public static final int APPLY_FILM_PLAYER_SETTINGS_FIXED_BYTES = Float.BYTES * 3 + Integer.BYTES * 3;
    public static final int MAX_APPLY_FILM_PLAYER_SETTINGS_EQUIPMENT_BYTES = NetworkCompat.MAX_SERVERBOUND_RAW_PAYLOAD_BYTES - APPLY_FILM_PLAYER_SETTINGS_FIXED_BYTES;
    public static final int MAX_ACTOR_ENTRIES = 4_096;
    public static final int MAX_MODEL_BLOCK_REFRESH_TICKS = 20 * 60;
    public static final double MAX_SHARED_FORM_DISTANCE_SQR = 64D * 64D;
    private static final double MODEL_BLOCK_INTERACTION_PADDING = 1.0D;

    public static final int STATE_TRIGGER_MORPH = 0;
    public static final int STATE_TRIGGER_MAIN_HAND_ITEM = 1;
    public static final int STATE_TRIGGER_OFF_HAND_ITEM = 2;

    public static final ResourceLocation CLIENT_CLICKED_MODEL_BLOCK_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c1");
    public static final ResourceLocation CLIENT_PLAYER_FORM_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c2");
    public static final ResourceLocation CLIENT_PLAY_FILM_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c3");
    public static final ResourceLocation CLIENT_MANAGER_DATA_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c4");
    public static final ResourceLocation CLIENT_STOP_FILM_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c5");
    public static final ResourceLocation CLIENT_HANDSHAKE = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c6");
    public static final ResourceLocation CLIENT_RECORDED_ACTIONS = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c7");
    public static final ResourceLocation CLIENT_ANIMATION_STATE_TRIGGER = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c8");
    public static final ResourceLocation CLIENT_CHEATS_PERMISSION = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c9");
    public static final ResourceLocation CLIENT_SHARED_FORM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c10");
    public static final ResourceLocation CLIENT_ENTITY_FORM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c11");
    public static final ResourceLocation CLIENT_ACTORS = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c12");
    public static final ResourceLocation CLIENT_GUN_PROPERTIES = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c13");
    public static final ResourceLocation CLIENT_PAUSE_FILM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c14");
    public static final ResourceLocation CLIENT_SELECTED_SLOT = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c15");
    public static final ResourceLocation CLIENT_ANIMATION_STATE_MODEL_BLOCK_TRIGGER = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c16");
    public static final ResourceLocation CLIENT_REFRESH_MODEL_BLOCKS = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c17");
    public static final ResourceLocation CLIENT_ADDON_BROKER = NetworkCompat.ADDON_BROKER_S2C;
    /* Upstream fs 2.3 allocates "c18" for this channel, but "c18" is already taken by the addon broker in this fork */
    public static final ResourceLocation CLIENT_REQUEST_FILM_RESYNC = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "c19");

    public static final ResourceLocation SERVER_MODEL_BLOCK_FORM_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s1");
    public static final ResourceLocation SERVER_MODEL_BLOCK_TRANSFORMS_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s2");
    public static final ResourceLocation SERVER_PLAYER_FORM_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s3");
    public static final ResourceLocation SERVER_MANAGER_DATA_PACKET = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s4");
    public static final ResourceLocation SERVER_ACTION_RECORDING = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s5");
    public static final ResourceLocation SERVER_TOGGLE_FILM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s6");
    public static final ResourceLocation SERVER_ACTION_CONTROL = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s7");
    public static final ResourceLocation SERVER_FILM_DATA_SYNC = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s8");
    public static final ResourceLocation SERVER_PLAYER_TP = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s9");
    public static final ResourceLocation SERVER_ANIMATION_STATE_TRIGGER = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s10");
    public static final ResourceLocation SERVER_SHARED_FORM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s11");
    public static final ResourceLocation SERVER_ZOOM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s12");
    public static final ResourceLocation SERVER_PAUSE_FILM = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s13");
    public static final ResourceLocation SERVER_APPLY_FILM_PLAYER_SETTINGS = ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, "s14");
    public static final ResourceLocation SERVER_ADDON_BROKER = NetworkCompat.ADDON_BROKER_C2S;

    /** Optional c7 terminal marker. A missing trailing byte is legacy/manual. */
    public enum RecordingTerminal
    {
        LEGACY_MANUAL(0),
        SERVER_FORCED(1),
        START_REJECTED(2);

        private final int id;

        RecordingTerminal(int id)
        {
            this.id = id;
        }

        public int id()
        {
            return this.id;
        }

        public static RecordingTerminal fromId(int id)
        {
            for (RecordingTerminal terminal : values())
            {
                if (terminal.id == id)
                {
                    return terminal;
                }
            }

            return null;
        }
    }

    private static ServerPacketCrusher crusher = new ServerPacketCrusher();
    private static final NetworkMutationSessions mutationSessions = new NetworkMutationSessions();
    private static final NetworkSeekBudget seekBudget = new NetworkSeekBudget();
    private static final NetworkSeekBudget completedPayloadBudget = new NetworkSeekBudget(
        System::nanoTime,
        new NetworkSeekBudget.Limits(
            32L * 1024L * 1024L,
            8L * 1024L * 1024L,
            64L * 1024L * 1024L,
            16L * 1024L * 1024L,
            TimeUnit.SECONDS.toNanos(1L),
            TimeUnit.MINUTES.toNanos(2L),
            16,
            4_096
        )
    );
    private static final NetworkSeekBudget repositoryWorkBudget = new NetworkSeekBudget(
        System::nanoTime,
        new NetworkSeekBudget.Limits(
            16L * 1024L * 1024L,
            1L * 1024L * 1024L,
            32L * 1024L * 1024L,
            2L * 1024L * 1024L,
            TimeUnit.SECONDS.toNanos(1L),
            TimeUnit.MINUTES.toNanos(2L),
            1,
            4_096
        )
    );
    private static final NetworkZoomSessions zoomSessions = new NetworkZoomSessions();
    private static final NetworkDirectActionGate directActionGate = new NetworkDirectActionGate();
    private static boolean lifecycleListenerRegistered;

    public static void reset()
    {
        reset(null);
    }

    public static void reset(MinecraftServer server)
    {
        List<NetworkZoomSessions.ActiveTransition> zoomCleanup = server == null
            ? List.of()
            : zoomSessions.drainActive();

        if (server != null)
        {
            for (NetworkZoomSessions.ActiveTransition active : zoomCleanup)
            {
                ServerPlayer player = server.getPlayerList().getPlayer(active.owner());

                if (player != null
                    && player == active.connectionIdentity()
                    && active.transition().hasCommand())
                {
                    executeZoomCommand(server, player, active.transition().command());
                }
            }
        }

        crusher.reset();
        mutationSessions.reset();
        seekBudget.reset();
        completedPayloadBudget.reset();
        repositoryWorkBudget.reset();
        zoomSessions.reset();
        directActionGate.reset();
        AddonPayloadBroker.resetServerBudget();
        GunProjectileBudget.RUNTIME.reset();
    }

    public static void setup()
    {
        NetworkCompat.registerCoreServerReceiver(SERVER_MODEL_BLOCK_FORM_PACKET, (server, player, buf) -> handleModelBlockFormPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, (server, player, buf) -> handleModelBlockTransformsPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_PLAYER_FORM_PACKET, (server, player, buf) -> handlePlayerFormPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_MANAGER_DATA_PACKET, (server, player, buf) -> handleManagerDataPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_ACTION_RECORDING, (server, player, buf) -> handleActionRecording(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_TOGGLE_FILM, (server, player, buf) -> handleToggleFilm(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_ACTION_CONTROL, (server, player, buf) -> handleActionControl(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_FILM_DATA_SYNC, (server, player, buf) -> handleSyncData(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_PLAYER_TP, (server, player, buf) -> handleTeleportPlayer(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_ANIMATION_STATE_TRIGGER, (server, player, buf) -> handleAnimationStateTriggerPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_SHARED_FORM, (server, player, buf) -> handleSharedFormPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_ZOOM, (server, player, buf) -> handleZoomPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_PAUSE_FILM, (server, player, buf) -> handlePauseFilmPacket(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_APPLY_FILM_PLAYER_SETTINGS, (server, player, buf) -> handleApplyFilmPlayerSettings(server, player, buf));
        NetworkCompat.registerCoreServerReceiver(SERVER_ADDON_BROKER, AddonPayloadBroker::handleServerPayload);

        if (!lifecycleListenerRegistered)
        {
            NeoForge.EVENT_BUS.addListener(ServerNetwork::handlePlayerLoggedOut);
            NeoForge.EVENT_BUS.addListener(ServerNetwork::handleServerTick);
            lifecycleListenerRegistered = true;
        }
    }

    private static void handleServerTick(ServerTickEvent.Post event)
    {
        crusher.expireIdleTransfers();
        seekBudget.expireIdle();
        completedPayloadBudget.expireIdle();
        repositoryWorkBudget.expireIdle();
        directActionGate.expireIdle();
        AddonPayloadBroker.expireServerBudgetIdle();
    }

    private static void handlePlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }

        MinecraftServer server = player.getServer();

        if (server == null)
        {
            return;
        }

        Runnable cleanup = () -> cleanupLoggedOutPlayer(server, player);

        if (server.isSameThread())
        {
            cleanup.run();
        }
        else
        {
            server.execute(cleanup);
        }
    }

    private static void cleanupLoggedOutPlayer(MinecraftServer server, ServerPlayer player)
    {
        UUID playerId = player.getUUID();

        crusher.clearConnection(playerId, player);
        seekBudget.clearConnection(playerId, player);
        completedPayloadBudget.clearConnection(playerId, player);
        repositoryWorkBudget.clearConnection(playerId, player);
        AddonPayloadBroker.clearServerConnection(playerId, player);
        GunProjectileBudget.RUNTIME.clearOwner(playerId);
        directActionGate.clearConnection(playerId, player);

        NetworkZoomSessions.Transition zoomCleanup = zoomSessions.clearOwner(playerId, player);
        ActionManager actions = BBSMod.getActions();

        /* A disconnected client cannot retry or receive c7. Retire its exact
         * recording locally before dropping the session/film claims. */
        actions.abortRecordingOnDisconnect(player);

        List<String> films = mutationSessions.clearOwner(playerId, player);

        if (zoomCleanup.hasCommand())
        {
            executeZoomCommand(server, player, zoomCleanup.command());
        }

        for (String filmId : films)
        {
            if (!actions.stop(filmId, player))
            {
                continue;
            }

            for (ServerPlayer otherPlayer : server.getPlayerList().getPlayers())
            {
                sendStopFilm(otherPlayer, filmId);
            }
        }

        /* Command-triggered per-target playbacks do not own mutation
         * sessions, but they must still release their player/runtime
         * references when that exact connection leaves. */
        actions.stopAll(player);
    }

    /**
     * Clear state keyed by a ServerPlayer identity that NeoForge retired for a
     * respawn/End return or an ordinary dimension transition. ActionManager
     * restores first-person state before this method releases mutation claims.
     */
    public static void retirePlayerIdentity(ServerPlayer retired, @Nullable ServerPlayer replacement)
    {
        if (retired == null)
        {
            return;
        }

        MinecraftServer server = retired.getServer();

        if (server == null
            || (replacement != null
                && (replacement.getServer() != server
                    || !replacement.getUUID().equals(retired.getUUID())
                    || replacement.connection != retired.connection)))
        {
            return;
        }

        Runnable cleanup = () ->
        {
            UUID playerId = retired.getUUID();

            crusher.clearConnection(playerId, retired);
            seekBudget.clearConnection(playerId, retired);
            completedPayloadBudget.clearConnection(playerId, retired);
            repositoryWorkBudget.clearConnection(playerId, retired);
            AddonPayloadBroker.clearServerConnection(playerId, retired);
            GunProjectileBudget.RUNTIME.clearOwner(playerId);
            directActionGate.clearConnection(playerId, retired);

            NetworkZoomSessions.Transition zoomCleanup = zoomSessions.clearOwner(playerId, retired);
            List<String> films = mutationSessions.clearOwner(playerId, retired);
            ServerPlayer commandPlayer = replacement == null ? retired : replacement;

            if (zoomCleanup.hasCommand())
            {
                executeZoomCommand(server, commandPlayer, zoomCleanup.command());
            }

            for (String filmId : films)
            {
                boolean replacementNotified = false;

                for (ServerPlayer observer : server.getPlayerList().getPlayers())
                {
                    trySendStopFilm(observer, filmId);
                    replacementNotified |= observer == replacement;
                }

                if (replacement != null && !replacementNotified)
                {
                    trySendStopFilm(replacement, filmId);
                }
            }
        };

        if (server.isSameThread())
        {
            cleanup.run();
        }
        else
        {
            server.execute(cleanup);
        }
    }

    /* Handlers */

    private static void handleModelBlockFormPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(player.getUUID(), player, SERVER_MODEL_BLOCK_FORM_PACKET, buf, (bytes, packetByteBuf) ->
        {
            if (!consumeCompletedPayload(player.getUUID(), player, SERVER_MODEL_BLOCK_FORM_PACKET, bytes, false))
            {
                return;
            }

            if (packetByteBuf.readableBytes() != Long.BYTES)
            {
                LOGGER.warn("[BBS-SEM] topic=net.model_block phase=receive result=drop reason=invalid_tail player={} readable={}",
                    player.getGameProfile().getName(),
                    packetByteBuf.readableBytes());
                return;
            }

            BlockPos pos = packetByteBuf.readBlockPos();

            try
            {
                BaseType decoded = NetworkDataDecoder.decode(bytes);

                if (!(decoded instanceof MapType data))
                {
                    return;
                }

                server.execute(() ->
                {
                    if (!isCurrentConnection(server, player) || !canEditModelBlock(server, player, pos))
                    {
                        LOGGER.warn("[BBS-SEM] topic=net.model_block phase=apply result=reject reason=authorization_or_session player={} dimension={} pos={}",
                            player.getGameProfile().getName(),
                            dimensionId(player.serverLevel()),
                            pos);
                        return;
                    }

                    Level world = player.level();
                    BlockEntity be = world.getBlockEntity(pos);

                    if (be instanceof ModelBlockEntity modelBlock)
                    {
                        try
                        {
                            modelBlock.updateForm(data, world);
                        }
                        catch (RuntimeException | LinkageError e)
                        {
                            LOGGER.warn("[BBS-SEM] topic=net.model_block phase=apply result=drop reason=mutation_failed player={} dimension={} pos={}",
                                player.getGameProfile().getName(),
                                dimensionId(player.serverLevel()),
                                pos,
                                e);
                            return;
                        }

                        mutationSessions.refreshModelBlockSession(player.getUUID(), player, dimensionId(player.serverLevel()), pos.asLong());
                    }
                });
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=net.model_block phase=decode result=drop reason=invalid_form player={}",
                    player.getGameProfile().getName(),
                    e);
            }
        });
    }

    private static void handleModelBlockTransformsPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(player.getUUID(), player, SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, buf, (bytes, packetByteBuf) ->
        {
            if (!consumeCompletedPayload(player.getUUID(), player, SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, bytes, false))
            {
                return;
            }

            if (packetByteBuf.isReadable())
            {
                return;
            }

            try
            {
                BaseType decoded = NetworkDataDecoder.decode(bytes);

                if (!(decoded instanceof MapType data))
                {
                    return;
                }

                server.execute(() ->
                {
                    if (!isCurrentConnection(server, player) || !PermissionUtils.arePanelsAllowed(server, player))
                    {
                        return;
                    }

                    ItemStack stack = player.getItemBySlot(EquipmentSlot.MAINHAND).copy();

                    if (stack.getItem() == BBSMod.MODEL_BLOCK_ITEM.get())
                    {
                        updateModelBlockStackData(stack, data);
                    }
                    else if (stack.getItem() == BBSMod.GUN_ITEM.get())
                    {
                        try
                        {
                            GunProperties properties = GunPropertiesPolicy.parseAllowed(data);
                            boolean commandBearing = GunPropertiesPolicy.hasAnyCommand(properties);

                            if (!GunPropertiesPolicy.isMutationAllowed(
                                properties,
                                PermissionUtils.arePanelsAllowed(server, player),
                                PermissionUtils.hasAdminPermission(player)
                            ))
                            {
                                LOGGER.warn("[BBS-SEM] topic=net.gun_data phase=apply result=reject reason={} player={}",
                                    commandBearing ? "command_permission" : "invalid_runtime_values",
                                    player.getGameProfile().getName());
                                return;
                            }

                            CustomData.update(DataComponents.CUSTOM_DATA, stack, (compoundTag) ->
                            {
                                compoundTag.put("GunData", DataStorageUtils.toNbt(data));
                            });
                        }
                        catch (RuntimeException | LinkageError factoryError)
                        {
                            LOGGER.warn("[BBS-SEM] topic=net.gun_data phase=apply result=drop reason=typed_factory_failed player={}",
                                player.getGameProfile().getName(),
                                factoryError);
                            return;
                        }
                    }

                    player.setItemSlot(EquipmentSlot.MAINHAND, stack);
                });
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=net.model_block_item phase=decode result=drop reason=invalid_transforms player={}",
                    player.getGameProfile().getName(),
                    e);
            }
        });
    }

    private static void updateModelBlockStackData(ItemStack stack, MapType data)
    {
        CompoundTag nbt = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();

        nbt.put("Properties", DataStorageUtils.toNbt(data));
        BlockItem.setBlockEntityData(stack, BBSMod.MODEL_BLOCK_ENTITY.get(), nbt);
    }

    private static void handlePlayerFormPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(player.getUUID(), player, SERVER_PLAYER_FORM_PACKET, buf, (bytes, packetByteBuf) ->
        {
            if (!consumeCompletedPayload(player.getUUID(), player, SERVER_PLAYER_FORM_PACKET, bytes, false))
            {
                return;
            }

            if (packetByteBuf.isReadable())
            {
                return;
            }

            BaseType decoded = NetworkDataDecoder.decode(bytes);

            if (!(decoded instanceof MapType data))
            {
                return;
            }

            server.execute(() ->
            {
                if (!isCurrentConnection(server, player) || !PermissionUtils.arePanelsAllowed(server, player))
                {
                    return;
                }

                try
                {
                    /* FormArchitect owns the serialized type key ("id"). Keep an
                     * empty map as the explicit demorph request, but reject a
                     * non-empty payload whose form id is unknown. */
                    if (!data.isEmpty() && !BBSMod.getForms().has(data))
                    {
                        LOGGER.warn("[BBS-SEM] topic=net.player_form phase=apply result=drop reason=unknown_form player={}",
                            player.getGameProfile().getName());
                        return;
                    }

                    Form form = data.isEmpty() ? null : BBSMod.getForms().fromData(data);
                    Form copy = FormUtils.copy(form);

                    Morph.getMorph(player).setForm(copy);
                    sendMorphToTracked(player, form);
                }
                catch (RuntimeException | LinkageError e)
                {
                    LOGGER.warn("[BBS-SEM] topic=net.player_form phase=apply result=drop reason=form_factory_failed player={}",
                        player.getGameProfile().getName(), e);
                }
            });
        });
    }

    private static void handleManagerDataPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(player.getUUID(), player, SERVER_MANAGER_DATA_PACKET, buf, (bytes, packetByteBuf) ->
        {
            if (!consumeCompletedPayload(player.getUUID(), player, SERVER_MANAGER_DATA_PACKET, bytes, true))
            {
                return;
            }

            if (packetByteBuf.readableBytes() != Integer.BYTES * 2)
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=receive result=drop reason=invalid_tail player={} readable={}",
                    player.getGameProfile().getName(),
                    packetByteBuf.readableBytes());
                return;
            }

            int callbackId = packetByteBuf.readInt();
            int operationId = packetByteBuf.readInt();
            RepositoryOperation[] operations = RepositoryOperation.values();

            if (operationId < 0 || operationId >= operations.length)
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=receive result=drop reason=invalid_operation player={} operation={}",
                    player.getGameProfile().getName(),
                    operationId);
                return;
            }

            RepositoryOperation op = operations[operationId];
            BaseType decoded;

            try
            {
                decoded = NetworkDataDecoder.decode(bytes);
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=decode result=drop reason=invalid_data player={} operation={}",
                    player.getGameProfile().getName(),
                    op,
                    e);
                return;
            }

            if (!(decoded instanceof MapType data))
            {
                return;
            }

            server.execute(() ->
            {
                if (!isCurrentConnection(server, player) || !PermissionUtils.arePanelsAllowed(server, player))
                {
                    return;
                }

                FilmManager films = BBSMod.getFilms();

                try
                {
                    if (op == RepositoryOperation.LOAD)
                    {
                        String id = canonicalFilmId(films, data.getString("id"));
                        boolean requesterAuthorized = FilmActionAuthorityPolicy.isRequesterAuthorized(player, server);
                        Film film = FilmActionAuthorityPolicy.loadFilmForRequester(films, id, requesterAuthorized);

                        sendManagerData(player, callbackId, op, film == null ? new ByteType(false) : film.toData());
                    }
                    else if (op == RepositoryOperation.SAVE)
                    {
                        String id = canonicalFilmId(films, data.getString("id"));
                        BaseType filmData = data.get("data");

                        if (id != null && filmData instanceof MapType map)
                        {
                            boolean administrator = PermissionUtils.hasAdminPermission(player);

                            /* Inspect the raw action envelope before invoking
                             * addon factories. Only exact built-in Swipe clips
                             * are safe for an ordinary editor to deserialize. */
                            if (FilmActionAuthorityPolicy.requiresAdministrator(map) && !administrator)
                            {
                                LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=save result=reject reason=raw_effectful_action_permission player={} film={}",
                                    player.getGameProfile().getName(),
                                    id);
                                return;
                            }

                            Film candidate;

                            try
                            {
                                candidate = new Film();
                                candidate.setId(id);
                                candidate.fromData(map);
                            }
                            catch (RuntimeException | LinkageError factoryError)
                            {
                                LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=save result=drop reason=film_factory_failed player={} film={}",
                                    player.getGameProfile().getName(),
                                    id,
                                    factoryError);
                                return;
                            }

                            if (FilmActionAuthorityPolicy.requiresAdministrator(candidate, map)
                                && !administrator)
                            {
                                LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=save result=reject reason=effectful_action_permission player={} film={}",
                                    player.getGameProfile().getName(),
                                    id);
                                return;
                            }

                            films.save(id, map);
                        }
                    }
                    else if (op == RepositoryOperation.RENAME)
                    {
                        String from = canonicalFilmId(films, data.getString("from"));
                        String to = canonicalFilmId(films, data.getString("to"));

                        if (from != null && to != null)
                        {
                            films.rename(from, to);
                        }
                    }
                    else if (op == RepositoryOperation.DELETE)
                    {
                        String id = canonicalFilmId(films, data.getString("id"));

                        if (id != null)
                        {
                            films.delete(id);
                        }
                    }
                    else if (op == RepositoryOperation.KEYS)
                    {
                        ListType list = DataStorageUtils.stringListToData(films.getKeys());

                        sendManagerData(player, callbackId, op, list);
                    }
                    else if (op == RepositoryOperation.ADD_FOLDER)
                    {
                        String folder = data.getString("folder");
                        boolean added = isValidFilmFolder(films, folder) && films.addFolder(folder);

                        sendManagerData(player, callbackId, op, new ByteType(added));
                    }
                    else if (op == RepositoryOperation.RENAME_FOLDER)
                    {
                        String from = data.getString("from");
                        String to = data.getString("to");
                        boolean renamed = isValidFilmFolder(films, from)
                            && isValidFilmFolder(films, to)
                            && films.renameFolder(from, to);

                        sendManagerData(player, callbackId, op, new ByteType(renamed));
                    }
                    else if (op == RepositoryOperation.DELETE_FOLDER)
                    {
                        String folder = data.getString("folder");
                        boolean deleted = isValidFilmFolder(films, folder) && films.deleteFolder(folder);

                        sendManagerData(player, callbackId, op, new ByteType(deleted));
                    }
                }
                catch (Exception | LinkageError e)
                {
                    LOGGER.warn("[BBS-SEM] topic=net.film_repository phase=apply result=drop reason=operation_failed player={} operation={}",
                        player.getGameProfile().getName(),
                        op,
                        e);
                }
            });
        });
    }

    private static void handleActionRecording(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        String filmId;
        int replayId;
        int tick;
        int countdown;
        boolean recording;

        try
        {
            filmId = buf.readUtf();
            replayId = buf.readInt();
            tick = buf.readInt();
            countdown = buf.readInt();
            recording = buf.readBoolean();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player))
            {
                return;
            }

            if (recording)
            {
                if (!PermissionUtils.arePanelsAllowed(server, player))
                {
                    sendRecordingStartRejected(player, filmId, replayId, tick);
                    return;
                }

                UUID owner = player.getUUID();
                FilmManager films = BBSMod.getFilms();
                String canonicalId = canonicalFilmId(films, filmId);
                ActionManager actions = BBSMod.getActions();
                NetworkMutationSessions.RecordingSession activeSession = mutationSessions.getRecording(owner, player);
                ActionPlayer activeRuntime = canonicalId == null ? null : actions.getPlayer(canonicalId, player);
                String dimension = dimensionId(player.serverLevel());
                boolean exactDuplicate = canonicalId != null
                    && activeSession != null
                    && canonicalId.equals(activeSession.filmId())
                    && dimension.equals(activeSession.dimension())
                    && replayId == activeSession.replayId()
                    && tick == activeSession.tick()
                    && actions.hasRecording(player)
                    && activeRuntime != null
                    && activeRuntime.type == PlayerType.RECORDING
                    && activeRuntime.getServerPlayer() == player
                    && activeRuntime.getLevel() == player.serverLevel()
                    && activeRuntime.film != null
                    && canonicalId.equals(activeRuntime.film.getId())
                    && activeRuntime.exception == replayId;

                if (exactDuplicate)
                {
                    return;
                }

                if (activeSession != null
                    || actions.hasRecording(player)
                    || (activeRuntime != null && activeRuntime.type == PlayerType.RECORDING))
                {
                    sendRecordingStartRejected(player, filmId, replayId, tick);
                    return;
                }

                if (canonicalId == null
                    || !directActionGate.tryAcquire(
                        owner,
                        player,
                        NetworkDirectActionGate.Channel.RECORDING_START
                    ))
                {
                    sendRecordingStartRejected(player, filmId, replayId, tick);
                    return;
                }

                ActionPlayer actionPlayer = actions.getPlayer(canonicalId);

                if (actionPlayer != null && !canMutateFilm(player, canonicalId, actionPlayer))
                {
                    logFilmMutationRejected(player, canonicalId, "record_start");
                    sendRecordingStartRejected(player, filmId, replayId, tick);
                    return;
                }

                ActionPlayer previousRuntime = actionPlayer;
                boolean hadFilmClaim = mutationSessions.ownsFilm(canonicalId, owner, player, dimension);
                boolean releaseNewFilmClaim = false;

                /* Reserve a bounded film slot before loading typed action data.
                 * An exact pre-existing owner claim is idempotent and must not
                 * be released by a later recording-start rejection. */
                if (actionPlayer == null)
                {
                    if (!mutationSessions.claimFilm(canonicalId, owner, player, dimension))
                    {
                        logFilmMutationRejected(player, canonicalId, "record_start");
                        sendRecordingStartRejected(player, filmId, replayId, tick);
                        return;
                    }

                    releaseNewFilmClaim = !hadFilmClaim;
                }

                try
                {
                    boolean requesterAuthorized = FilmActionAuthorityPolicy.isRequesterAuthorized(player, server);
                    Film film = FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized);
                    boolean recordingAllowed = film != null
                        && NetworkMutationPolicy.isRecordingStartAllowed(
                            replayId,
                            tick,
                            countdown,
                            film.replays.getList().size(),
                            film.camera.calculateDuration()
                        );

                    if (!recordingAllowed)
                    {
                        sendRecordingStartRejected(player, filmId, replayId, tick);
                        return;
                    }

                    boolean recordingClaimed = mutationSessions.claimRecording(
                        canonicalId,
                        owner,
                        player,
                        dimension,
                        replayId,
                        tick
                    );

                    if (!recordingClaimed)
                    {
                        sendRecordingStartRejected(player, filmId, replayId, tick);
                        return;
                    }

                    /* Stage the new recording runtime before tearing down the
                     * editor runtime.  If construction fails, the old exact
                     * identity remains live and its c12 map is restored. */
                    if (!actions.tryStartRecording(film, player, tick, countdown, replayId))
                    {
                        if (actions.hasRecording(player))
                        {
                            /* ActionPlayer teardown failed during startup. Keep
                             * both claims with the reachable exact recovery;
                             * ActionManager.tick will retry its forced terminal. */
                            releaseNewFilmClaim = false;
                            LOGGER.warn("[BBS-SEM] topic=net.recording phase=start result=retry reason=rollback_failed player={} film={}",
                                player.getGameProfile().getName(), canonicalId);
                            return;
                        }

                        mutationSessions.releaseRecording(owner, player);
                        sendRecordingStartRejected(player, filmId, replayId, tick);
                        return;
                    }

                    ActionPlayer recordingRuntime = actions.getRecordingPlayer(player);
                    ActionRecorder recordingState = actions.getRecorder(player);

                    if (recordingRuntime == null || recordingState == null)
                    {
                        boolean rolledBack = recordingState != null
                            ? actions.stopRecordingExact(player, recordingState) != null
                            : recordingRuntime == null || actions.stopExact(recordingRuntime, "record_start_rollback");

                        if (!rolledBack)
                        {
                            releaseNewFilmClaim = false;
                            LOGGER.warn("[BBS-SEM] topic=net.recording phase=start_state result=retry reason=rollback_failed player={} film={}",
                                player.getGameProfile().getName(), canonicalId);
                            return;
                        }

                        mutationSessions.releaseRecording(owner, player);
                        sendRecordingStartRejected(player, filmId, replayId, tick);
                        return;
                    }

                    if (previousRuntime != null)
                    {
                        if (!recordingRuntime.tryResendActors()
                            || !actions.stopExact(previousRuntime, "record_start_replace"))
                        {
                            previousRuntime.resendActors();

                            if (actions.stopRecordingExact(player, recordingState) == null)
                            {
                                releaseNewFilmClaim = false;
                                LOGGER.warn("[BBS-SEM] topic=net.recording phase=start_replace result=retry reason=rollback_failed player={} film={}",
                                    player.getGameProfile().getName(), canonicalId);
                                return;
                            }

                            mutationSessions.releaseRecording(owner, player);
                            sendRecordingStartRejected(player, filmId, replayId, tick);
                            return;
                        }

                        /* The old runtime may have emitted a stale c12 map as
                         * it was discarded; assert the new identity once more. */
                        recordingRuntime.resendActors();
                    }

                    releaseNewFilmClaim = false;
                }
                catch (RuntimeException | LinkageError e)
                {
                    ActionPlayer recordingRuntime = actions.getRecordingPlayer(player);
                    ActionRecorder recordingState = actions.getRecorder(player);
                    boolean rolledBack = recordingRuntime == null
                        || (recordingState == null
                            ? actions.stopExact(recordingRuntime, "record_start_exception")
                            : actions.stopRecordingExact(player, recordingState) != null);

                    if (!rolledBack)
                    {
                        releaseNewFilmClaim = false;
                        LOGGER.warn("[BBS-SEM] topic=net.recording phase=start_exception result=retry reason=rollback_failed player={} film={}",
                            player.getGameProfile().getName(), canonicalId, e);
                        return;
                    }

                    mutationSessions.releaseRecording(owner, player);
                    LOGGER.warn("[BBS-SEM] topic=net.recording phase=start result=reject reason=runtime_start_failed player={} film={}",
                        player.getGameProfile().getName(), canonicalId, e);
                    sendRecordingStartRejected(player, filmId, replayId, tick);
                }
                finally
                {
                    if (releaseNewFilmClaim)
                    {
                        mutationSessions.releaseFilm(canonicalId, owner, player);
                    }
                }
            }
            else
            {
                if (countdown != 0)
                {
                    return;
                }

                String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);
                UUID owner = player.getUUID();
                NetworkMutationSessions.RecordingSession session = mutationSessions.getRecording(owner, player);

                if (canonicalId == null
                    || session == null
                    || !canonicalId.equals(session.filmId())
                    || replayId != session.replayId()
                    || tick != session.tick())
                {
                    return;
                }

                ActionRecorder recorder = BBSMod.getActions().getRecorder(player);

                if (recorder == null || !canonicalId.equals(recorder.getFilm().getId()))
                {
                    return;
                }

                finishRecordingTerminal(player, canonicalId, recorder, RecordingTerminal.LEGACY_MANUAL);
            }
        });
    }

    private static void handleToggleFilm(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        String filmId;
        boolean withCamera;

        try
        {
            filmId = buf.readUtf();
            withCamera = buf.readBoolean();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player))
            {
                return;
            }

            String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);

            if (canonicalId == null)
            {
                return;
            }

            if (mutationSessions.getRecording(player.getUUID(), player) != null)
            {
                return;
            }

            ActionPlayer actionPlayer = BBSMod.getActions().getPlayer(canonicalId);

            if (actionPlayer != null)
            {
                if (actionPlayer.getServerPlayer() != player
                    || !mutationSessions.ownsFilm(canonicalId, player.getUUID(), player))
                {
                    logFilmMutationRejected(player, canonicalId, "toggle");
                    return;
                }

                stopFilmForPlayer(player, canonicalId);
            }
            else
            {
                if (!PermissionUtils.arePanelsAllowed(server, player))
                {
                    return;
                }

                if (!directActionGate.tryAcquire(
                    player.getUUID(),
                    player,
                    NetworkDirectActionGate.Channel.FILM_START
                ))
                {
                    return;
                }

                sendPlayFilm(player, player.serverLevel(), canonicalId, withCamera);
            }
        });
    }

    private static void handleActionControl(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        String filmId;
        int stateId;
        int tick;

        try
        {
            filmId = buf.readUtf();
            stateId = buf.readUnsignedByte();
            tick = buf.readInt();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        ActionState[] states = ActionState.values();

        if (stateId >= states.length)
        {
            LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=receive result=drop reason=invalid_state player={} state={}",
                player.getGameProfile().getName(),
                stateId);
            return;
        }

        ActionState state = states[stateId];

        if (state != ActionState.STOP && !PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player)
                || (state != ActionState.STOP && !PermissionUtils.arePanelsAllowed(server, player)))
            {
                return;
            }

            FilmManager films = BBSMod.getFilms();
            String canonicalId = canonicalFilmId(films, filmId);

            if (canonicalId == null)
            {
                return;
            }

            if (mutationSessions.getRecording(player.getUUID(), player) != null)
            {
                return;
            }

            ActionManager actions = BBSMod.getActions();
            ActionPlayer actionPlayer = actions.getPlayer(canonicalId);

            if (state == ActionState.STOP)
            {
                stopFilmForPlayer(player, canonicalId);

                return;
            }

            if (actionPlayer != null && !canMutateFilm(player, canonicalId, actionPlayer))
            {
                logFilmMutationRejected(player, canonicalId, state.name().toLowerCase());
                return;
            }

            if (actionPlayer == null && state != ActionState.RESTART)
            {
                return;
            }

            UUID owner = player.getUUID();
            boolean restart = state == ActionState.RESTART;
            boolean rollbackRestartClaim = false;
            ActionPlayer restartRuntime = null;

            if (restart)
            {
                if (!directActionGate.tryAcquire(
                    owner,
                    player,
                    NetworkDirectActionGate.Channel.FILM_START
                ))
                {
                    return;
                }

                /* Reserve the bounded mutation slot before loading any typed
                 * Film data. An active exact-owner runtime already owns its
                 * slot, so only an inactive restart creates a provisional
                 * claim that later rejection must release. */
                if (actionPlayer == null)
                {
                    if (!mutationSessions.claimFilm(
                        canonicalId,
                        owner,
                        player,
                        dimensionId(player.serverLevel())
                    ))
                    {
                        logFilmMutationRejected(player, canonicalId, "restart");
                        return;
                    }

                    rollbackRestartClaim = true;
                }
            }

            try
            {
                boolean requesterAuthorized = FilmActionAuthorityPolicy.isRequesterAuthorized(player, server);
                Film film = actionPlayer == null
                    ? FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)
                    : actionPlayer.film;

                if (film == null
                    || !NetworkMutationPolicy.isFilmTickAllowed(
                        actionPlayer == null ? 0 : actionPlayer.tick,
                        tick,
                        film.camera.calculateDuration(),
                        restart
                    ))
                {
                    LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=apply result=reject reason=tick_budget player={} operation={} film={} tick={}",
                        player.getGameProfile().getName(),
                        state,
                        canonicalId,
                        tick);
                    return;
                }

                long seekSteps = restart
                    ? tick
                    : Math.abs((long) tick - actionPlayer.tick);
                long billableSteps = Math.max(restart ? 100L : 1L, seekSteps);
                /* seekTo samples the destination once and never replays intermediate
                 * actions, so distance is an authorization bound rather than work. */
                long baselineWork = NetworkSeekBudget.DEFAULT_WORK_UNITS_PER_STEP;
                long weightedWork = FilmPlaybackPolicy.estimateSeekWork(film, billableSteps);
                long seekWork = Math.max(baselineWork, weightedWork);

                if (!seekBudget.tryConsume(owner, player, canonicalId, seekWork))
                {
                    LOGGER.debug("[BBS-SEM] topic=net.film_mutation phase=apply result=reject reason=cumulative_seek_budget player={} operation={} film={} work={}",
                        player.getGameProfile().getName(),
                        state,
                        canonicalId,
                        seekWork);
                    return;
                }

                if (state == ActionState.SEEK)
                {
                    actionPlayer.seekTo(tick);
                }
                else if (state == ActionState.PLAY)
                {
                    actionPlayer.seekTo(tick);
                    actionPlayer.playing = true;
                }
                else if (state == ActionState.PAUSE)
                {
                    actionPlayer.seekTo(tick);
                    actionPlayer.playing = false;
                }
                else if (restart)
                {
                    if (actionPlayer != null)
                    {
                        ActionPlayer expected = actionPlayer;

                        actionPlayer = actions.replaceFilmEditorExact(
                            expected,
                            player,
                            player.serverLevel(),
                            film,
                            tick,
                            (replacement) ->
                            {
                                replacement.syncing = true;
                                replacement.playing = false;
                                replacement.seekTo(tick);
                                sendStopFilm(player, canonicalId);
                            }
                        );

                        if (actionPlayer == null)
                        {
                            return;
                        }
                    }
                    else
                    {
                        restartRuntime = actions.play(player, player.serverLevel(), film, tick, PlayerType.FILM_EDITOR);

                        if (restartRuntime == null)
                        {
                            return;
                        }

                        actionPlayer = restartRuntime;
                        actionPlayer.syncing = true;
                        actionPlayer.playing = false;
                        actionPlayer.seekTo(tick);
                        if (!actionPlayer.tryResendActors())
                        {
                            throw new IllegalStateException("Could not resend restarted film actors");
                        }
                        sendStopFilm(player, canonicalId);
                        rollbackRestartClaim = false;
                    }
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=restart result=reject reason=restart_failed player={} film={}",
                    player.getGameProfile().getName(), canonicalId, e);
            }
            finally
            {
                if (rollbackRestartClaim)
                {
                    boolean canRelease = restartRuntime == null
                        || actions.stopExact(restartRuntime, "restart_rollback");

                    if (canRelease)
                    {
                        mutationSessions.releaseFilm(canonicalId, owner, player);
                    }
                    else
                    {
                        LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=rollback result=partial player={} film={}",
                            player.getGameProfile().getName(), canonicalId);
                    }
                }
            }
        });
    }

    private static void handleSyncData(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(player.getUUID(), player, SERVER_FILM_DATA_SYNC, buf, (bytes, packetByteBuf) ->
        {
            if (!consumeCompletedPayload(player.getUUID(), player, SERVER_FILM_DATA_SYNC, bytes, false))
            {
                return;
            }

            try
            {
                String filmId = packetByteBuf.readUtf();

                if (packetByteBuf.readableBytes() < Integer.BYTES)
                {
                    return;
                }

                int pathSize = packetByteBuf.readInt();

                if (pathSize < 0 || pathSize > 256)
                {
                    LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=receive result=drop reason=invalid_path_size player={} size={}",
                        player.getGameProfile().getName(),
                        pathSize);
                    return;
                }

                List<String> path = new ArrayList<>(pathSize);

                for (int i = 0; i < pathSize; i++)
                {
                    path.add(packetByteBuf.readUtf());
                }

                if (packetByteBuf.isReadable())
                {
                    return;
                }

                BaseType data = NetworkDataDecoder.decode(bytes);

                if (data == null)
                {
                    return;
                }

                server.execute(() ->
                {
                    if (!isCurrentConnection(server, player))
                    {
                        return;
                    }

                    String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);
                    ActionPlayer actionPlayer = canonicalId == null ? null : BBSMod.getActions().getPlayer(canonicalId);

                    if (!PermissionUtils.arePanelsAllowed(server, player) || !canMutateFilm(player, canonicalId, actionPlayer))
                    {
                        logFilmMutationRejected(player, canonicalId, "sync");
                        return;
                    }

                    actionPlayer.syncData(new DataPath(path), data);
                });
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=decode result=drop reason=invalid_sync player={}",
                    player.getGameProfile().getName(),
                    e);
            }
        });
    }

    private static void handleTeleportPlayer(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        int expectedBytes = Double.BYTES * 3 + Float.BYTES * 3;

        if (buf.readableBytes() != expectedBytes)
        {
            return;
        }

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float yaw = buf.readFloat();
        float bodyYaw = buf.readFloat();
        float pitch = buf.readFloat();

        if (!NetworkMutationPolicy.isTeleportAllowed(x, y, z, yaw, bodyYaw, pitch))
        {
            return;
        }

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player)
                || !PermissionUtils.arePanelsAllowed(server, player)
                || !directActionGate.tryAcquire(
                    player.getUUID(),
                    player,
                    NetworkDirectActionGate.Channel.TELEPORT
                )
                || !NetworkMutationPolicy.isTeleportAllowed(x, y, z, yaw, bodyYaw, pitch)
            )
            {
                return;
            }

            ServerLevel level = player.serverLevel();
            BlockPos pos = BlockPos.containing(x, y, z);

            if (!level.getWorldBorder().isWithinBounds(pos)
                || level.isOutsideBuildHeight(pos)
                || !Level.isInSpawnableBounds(pos))
            {
                return;
            }

            float normalizedYaw = Mth.wrapDegrees(yaw);
            float normalizedBodyYaw = Mth.wrapDegrees(bodyYaw);

            player.teleportTo(x, y, z);
            player.setYRot(normalizedYaw);
            player.setYHeadRot(normalizedYaw);
            player.setYBodyRot(normalizedBodyYaw);
            player.setXRot(pitch);
        });
    }

    private static void handleAnimationStateTriggerPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        String string;
        int type;

        try
        {
            string = buf.readUtf(NetworkDirectActionGate.MAX_ANIMATION_TRIGGER_LENGTH);
            type = buf.readInt();
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable()
            || !NetworkDirectActionGate.isAnimationTriggerAllowed(string)
            || type < STATE_TRIGGER_MORPH
            || type > STATE_TRIGGER_OFF_HAND_ITEM)
        {
            return;
        }

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player)
                || !PermissionUtils.arePanelsAllowed(server, player)
                || !directActionGate.tryAcquire(
                    player.getUUID(),
                    player,
                    NetworkDirectActionGate.Channel.ANIMATION_TRIGGER
                ))
            {
                return;
            }

            FriendlyByteBuf newBuf = NetworkCompat.createBuffer();

            newBuf.writeInt(player.getId());
            newBuf.writeUtf(string);
            newBuf.writeInt(type);

            NetworkCompat.sendToPlayersTrackingEntity(player, CLIENT_ANIMATION_STATE_TRIGGER, newBuf);

            /* Keep server-side morph Form state in sync with the triggered animation state.
             * Item-form triggers (type > 0) rely on client-only ModelProperties and are
             * intentionally left to the broadcast above (CLIENT_ANIMATION_STATE_TRIGGER). */
            Morph morph = Morph.getMorph(player);

            if (type == STATE_TRIGGER_MORPH && morph != null && morph.getForm() != null)
            {
                morph.getForm().playState(string);
            }
        });
    }

    private static void handleSharedFormPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(player.getUUID(), player, SERVER_SHARED_FORM, buf, (bytes, packetByteBuf) ->
        {
            if (!consumeCompletedPayload(player.getUUID(), player, SERVER_SHARED_FORM, bytes, false))
            {
                return;
            }

            if (packetByteBuf.readableBytes() != Long.BYTES * 2)
            {
                return;
            }

            UUID playerUuid = packetByteBuf.readUUID();
            BaseType decoded;

            try
            {
                decoded = NetworkDataDecoder.decode(bytes);
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=net.share_form phase=decode result=drop reason=invalid_form player={}",
                    player.getGameProfile().getName(),
                    e);
                return;
            }

            if (!(decoded instanceof MapType data))
            {
                return;
            }

            server.execute(() ->
            {
                if (!isCurrentConnection(server, player) || !PermissionUtils.arePanelsAllowed(server, player))
                {
                    return;
                }

                ServerPlayer otherPlayer = server.getPlayerList().getPlayer(playerUuid);

                if (otherPlayer != null
                    && otherPlayer != player
                    && otherPlayer.serverLevel() == player.serverLevel()
                    && player.distanceToSqr(otherPlayer) <= MAX_SHARED_FORM_DISTANCE_SQR
                    && PermissionUtils.arePanelsAllowed(server, otherPlayer)
                    && directActionGate.tryAcquire(
                        player.getUUID(),
                        player,
                        NetworkDirectActionGate.Channel.SHARE_FORM
                    ))
                {
                    sendSharedForm(otherPlayer, data);
                }
            });
        });
    }

    private static void handleZoomPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (buf.readableBytes() != 1)
        {
            return;
        }

        boolean zoom = buf.readBoolean();

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player))
            {
                return;
            }

            UUID owner = player.getUUID();
            NetworkZoomSessions.Transition transition;

            if (zoom)
            {
                ItemStack main = player.getMainHandItem();

                if (main.getItem() != BBSMod.GUN_ITEM.get())
                {
                    return;
                }

                GunProperties properties;

                try
                {
                    properties = GunProperties.get(main);
                }
                catch (RuntimeException | LinkageError e)
                {
                    LOGGER.warn("[BBS-SEM] topic=net.zoom phase=decode result=drop reason=invalid_gun_data player={}",
                        player.getGameProfile().getName(), e);
                    return;
                }

                if (!GunPropertiesPolicy.isAllowed(properties))
                {
                    return;
                }

                transition = zoomSessions.turnOn(owner, player, properties.cmdZoomOn, properties.cmdZoomOff);
            }
            else
            {
                transition = zoomSessions.turnOff(owner, player);
            }

            if (transition.hasCommand())
            {
                executeZoomCommand(server, player, transition.command());
            }
        });
    }

    private static void handlePauseFilmPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        String filmId;

        try
        {
            /* s13 is a tiny control packet; bound the raw id before any
             * repository lookup and reject trailing bytes. */
            filmId = buf.readUtf(256);
        }
        catch (RuntimeException e)
        {
            return;
        }

        if (buf.isReadable())
        {
            return;
        }

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player)
                || !PermissionUtils.arePanelsAllowed(server, player))
            {
                return;
            }

            /* Consume the exact connection budget before canonicalization so
             * invalid ids cannot be used to amplify lookup/log work. */
            if (!directActionGate.tryAcquire(
                player.getUUID(),
                player,
                NetworkDirectActionGate.Channel.PAUSE_FILM
            ))
            {
                return;
            }

            String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);
            ActionPlayer actionPlayer = canonicalId == null ? null : BBSMod.getActions().getPlayer(canonicalId);

            if (!canMutateFilm(player, canonicalId, actionPlayer))
            {
                logFilmMutationRejected(player, canonicalId, "pause");
                return;
            }

            actionPlayer.toggle();

            for (ServerPlayer playerEntity : server.getPlayerList().getPlayers())
            {
                sendPauseFilm(playerEntity, canonicalId);
            }
        });
    }

    private static void handleApplyFilmPlayerSettings(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.hasAdminPermission(player))
        {
            return;
        }

        if (buf.readableBytes() < APPLY_FILM_PLAYER_SETTINGS_FIXED_BYTES)
        {
            LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=receive result=drop reason=short_payload player={} readable={}",
                player.getGameProfile().getName(),
                buf.readableBytes());
            return;
        }

        float hp = buf.readFloat();
        float hunger = buf.readFloat();
        int xpLevel = buf.readInt();
        float xpProgress = buf.readFloat();
        int selectedSlot = buf.readInt();
        int dressSize = buf.readInt();

        if (!NetworkMutationPolicy.arePlayerSettingsAllowed(hp, player.getMaxHealth(), hunger, xpLevel, xpProgress))
        {
            return;
        }

        if (dressSize < 0 || dressSize > MAX_APPLY_FILM_PLAYER_SETTINGS_EQUIPMENT_BYTES)
        {
            LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=receive result=drop reason=invalid_equipment_size player={} size={} max={}",
                player.getGameProfile().getName(),
                dressSize,
                MAX_APPLY_FILM_PLAYER_SETTINGS_EQUIPMENT_BYTES);
            return;
        }

        if (dressSize > buf.readableBytes())
        {
            LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=receive result=drop reason=truncated_equipment player={} size={} readable={}",
                player.getGameProfile().getName(),
                dressSize,
                buf.readableBytes());
            return;
        }

        if (dressSize != buf.readableBytes())
        {
            LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=receive result=drop reason=trailing_bytes player={} size={} trailing={}",
                player.getGameProfile().getName(),
                dressSize,
                buf.readableBytes() - dressSize);
            return;
        }

        byte[] dressBytes = dressSize > 0 ? new byte[dressSize] : null;

        if (dressBytes != null)
        {
            buf.readBytes(dressBytes);
        }

        ListType equipment = null;

        if (dressBytes != null)
        {
            BaseType decoded = NetworkDataDecoder.decode(dressBytes);

            if (!(decoded instanceof ListType list))
            {
                LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=decode result=drop reason=invalid_equipment_payload player={}",
                    player.getGameProfile().getName());
                return;
            }

            equipment = list;
        }

        final ListType finalEquipment = equipment;

        server.execute(() ->
        {
            if (!isCurrentConnection(server, player)
                || !PermissionUtils.hasAdminPermission(player)
                || !NetworkMutationPolicy.arePlayerSettingsAllowed(hp, player.getMaxHealth(), hunger, xpLevel, xpProgress))
            {
                return;
            }

            List<ItemStack> stagedEquipment = null;
            List<ItemStack> previousEquipment = null;

            if (finalEquipment != null)
            {
                try
                {
                    stagedEquipment = stageEquipmentForPlayer(player, finalEquipment);
                    previousEquipment = snapshotEquipment(player);
                }
                catch (RuntimeException e)
                {
                    LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=stage result=drop reason=invalid_equipment_data player={}",
                        player.getGameProfile().getName(),
                        e);
                    return;
                }
            }

            int previousSelectedSlot = player.getInventory().selected;
            float previousHealth = player.getHealth();
            int previousHunger = player.getFoodData().getFoodLevel();
            int previousXpLevel = player.experienceLevel;
            float previousXpProgress = player.experienceProgress;

            try
            {
                if (stagedEquipment != null)
                {
                    applyStagedEquipment(player, stagedEquipment, MathUtils.clamp(selectedSlot, 0, ReplayKeyframes.HOTBAR_SIZE - 1));
                }

                /* Staged equipment/components can change max health. Recheck
                 * after inventory mutation so an invalidated stat request
                 * enters the full rollback path instead of committing only
                 * the inventory portion. */
                if (!NetworkMutationPolicy.arePlayerSettingsAllowed(
                    hp,
                    player.getMaxHealth(),
                    hunger,
                    xpLevel,
                    xpProgress
                ))
                {
                    throw new IllegalStateException("Staged equipment invalidated film player settings");
                }

                ActionPlayer.applyFilmPlayerSettingsTo(player, hp, hunger, xpLevel, xpProgress);
                syncPlayerInventory(player);
            }
            catch (RuntimeException e)
            {
                if (previousEquipment != null)
                {
                    try
                    {
                        applyStagedEquipment(player, previousEquipment, previousSelectedSlot);
                    }
                    catch (RuntimeException rollbackError)
                    {
                        e.addSuppressed(rollbackError);
                    }
                }

                try
                {
                    player.setHealth(previousHealth);
                }
                catch (RuntimeException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }

                try
                {
                    player.getFoodData().setFoodLevel(previousHunger);
                }
                catch (RuntimeException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }

                try
                {
                    player.setExperienceLevels(previousXpLevel);
                }
                catch (RuntimeException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }

                try
                {
                    player.experienceProgress = previousXpProgress;
                }
                catch (RuntimeException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }

                try
                {
                    syncPlayerInventory(player);
                }
                catch (RuntimeException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }

                LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=apply result=rollback reason=mutation_failed player={}",
                    player.getGameProfile().getName(),
                    e);
            }
        });
    }

    /** Decode every submitted equipment item before touching the live player. */
    private static List<ItemStack> stageEquipmentForPlayer(ServerPlayer player, ListType data)
    {
        int expected = ReplayKeyframes.HOTBAR_SIZE + ReplayKeyframes.DRESS_SLOTS.length;

        if (player == null || data == null || data.size() != expected)
        {
            throw new IllegalArgumentException("Film equipment must contain exactly " + expected + " slots");
        }

        List<ItemStack> staged = new ArrayList<>(expected);

        for (int i = 0; i < expected; i++)
        {
            ItemStack stack = KeyframeFactories.ITEM_STACK.tryFromData(data.get(i), player.registryAccess())
                .orElseThrow(() -> new IllegalArgumentException("Film equipment contains an invalid item stack"));

            staged.add(stack.copy());
        }

        return List.copyOf(staged);
    }

    private static List<ItemStack> snapshotEquipment(ServerPlayer player)
    {
        List<ItemStack> snapshot = new ArrayList<>(ReplayKeyframes.HOTBAR_SIZE + ReplayKeyframes.DRESS_SLOTS.length);

        for (int i = 0; i < ReplayKeyframes.HOTBAR_SIZE; i++)
        {
            snapshot.add(player.getInventory().getItem(i).copy());
        }

        for (EquipmentSlot slot : ReplayKeyframes.DRESS_SLOTS)
        {
            snapshot.add(player.getItemBySlot(slot).copy());
        }

        return List.copyOf(snapshot);
    }

    private static void applyStagedEquipment(ServerPlayer player, List<ItemStack> staged, int selectedSlot)
    {
        int expected = ReplayKeyframes.HOTBAR_SIZE + ReplayKeyframes.DRESS_SLOTS.length;

        if (player == null || staged == null || staged.size() != expected)
        {
            throw new IllegalArgumentException("Staged film equipment is invalid");
        }

        for (int i = 0; i < ReplayKeyframes.HOTBAR_SIZE; i++)
        {
            player.getInventory().setItem(i, staged.get(i).copy());
        }

        for (int i = 0; i < ReplayKeyframes.DRESS_SLOTS.length; i++)
        {
            player.setItemSlot(ReplayKeyframes.DRESS_SLOTS[i], staged.get(ReplayKeyframes.HOTBAR_SIZE + i).copy());
        }

        player.getInventory().selected = MathUtils.clamp(selectedSlot, 0, ReplayKeyframes.HOTBAR_SIZE - 1);
        sendSelectedSlot(player, player.getInventory().selected);
    }

    private static void syncPlayerInventory(ServerPlayer player)
    {
        player.inventoryMenu.broadcastChanges();

        if (player.containerMenu != player.inventoryMenu)
        {
            player.containerMenu.broadcastChanges();
        }
    }

    private static boolean canEditModelBlock(MinecraftServer server, ServerPlayer player, BlockPos pos)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player)
            || !mutationSessions.hasModelBlockSession(player.getUUID(), player, dimensionId(player.serverLevel()), pos.asLong())
            || !player.canInteractWithBlock(pos, MODEL_BLOCK_INTERACTION_PADDING))
        {
            return false;
        }

        return player.serverLevel().getBlockEntity(pos) instanceof ModelBlockEntity;
    }

    private static boolean consumeCompletedPayload(
        UUID owner,
        Object connectionIdentity,
        ResourceLocation channel,
        byte[] bytes,
        boolean repositoryWork
    )
    {
        if (bytes == null)
        {
            return false;
        }

        long minimumWork = channel.equals(SERVER_FILM_DATA_SYNC) ? 1024L * 1024L : 64L * 1024L;
        long decodeWork = Math.max(minimumWork, bytes.length);

        if (!completedPayloadBudget.tryConsume(owner, connectionIdentity, channel.toString(), decodeWork))
        {
            return false;
        }

        return !repositoryWork
            || repositoryWorkBudget.tryConsume(
                owner,
                connectionIdentity,
                channel.toString(),
                Math.max(1024L * 1024L, bytes.length)
            );
    }

    private static void executeZoomCommand(MinecraftServer server, ServerPlayer player, String command)
    {
        if (!AuthorizedCommandExecutor.isAuthorized(player, server)
            || !AuthorizedCommandExecutor.execute(
                player,
                command,
                GunPropertiesPolicy.isCommandAllowed(command),
                player
            ))
        {
            LOGGER.debug("[BBS-SEM] topic=net.zoom phase=apply result=drop reason=command_rejected player={}",
                player.getGameProfile().getName());
        }
    }

    private static boolean isCurrentConnection(MinecraftServer server, ServerPlayer player)
    {
        return server != null
            && player != null
            && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean canMutateFilm(ServerPlayer player, String filmId, ActionPlayer actionPlayer)
    {
        return actionPlayer != null
            && mutationSessions.getRecording(player.getUUID(), player) == null
            && actionPlayer.getServerPlayer() == player
            && actionPlayer.getLevel() == player.serverLevel()
            && mutationSessions.ownsFilm(filmId, player.getUUID(), player, dimensionId(player.serverLevel()));
    }

    private static String canonicalFilmId(FilmManager films, String requestedId)
    {
        String canonicalId = NetworkFilmKey.resolve(films, requestedId);

        /* The existing manager protocol has no response field for canonical identity.
         * Reject aliases rather than transparently loading one id while the client keeps
         * another id for action/session traffic. */
        return canonicalId != null && canonicalId.equals(requestedId) ? canonicalId : null;
    }

    private static boolean isValidFilmFolder(FilmManager films, String folder)
    {
        try
        {
            return films != null && films.getFolder(folder) != null;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static String dimensionId(ServerLevel level)
    {
        return level.dimension().location().toString();
    }

    private static void logFilmMutationRejected(ServerPlayer player, String filmId, String operation)
    {
        LOGGER.warn("[BBS-SEM] topic=net.film_mutation phase=apply result=reject reason=owner_or_dimension player={} operation={} film={}",
            player.getGameProfile().getName(),
            operation,
            filmId);
    }

    /* API */

    public static void sendMorph(ServerPlayer player, int playerId, Form form)
    {
        crusher.send(player, CLIENT_PLAYER_FORM_PACKET, FormUtils.toData(form), (packetByteBuf) ->
        {
            packetByteBuf.writeInt(playerId);
        });
    }

    public static void releaseFilmSession(String filmId, ServerPlayer owner)
    {
        if (owner != null)
        {
            mutationSessions.releaseFilm(filmId, owner.getUUID(), owner);
        }
    }

    public static void releaseRecordingSession(String filmId, ServerPlayer owner)
    {
        if (owner == null || filmId == null)
        {
            return;
        }

        UUID ownerId = owner.getUUID();
        NetworkMutationSessions.RecordingSession recording = mutationSessions.getRecording(ownerId, owner);

        if (recording != null && filmId.equals(recording.filmId()))
        {
            mutationSessions.releaseRecording(ownerId, owner);
        }

        /* A recording runtime owns the matching film lease as well. Both
         * releases are idempotent so natural, forced and explicit teardown can
         * converge here without reopening a partially stopped session. */
        mutationSessions.releaseFilm(filmId, ownerId, owner);
    }

    public static void sendMorphToTracked(ServerPlayer player, Form form)
    {
        crusher.sendToPlayersTrackingEntityAndSelf(player, CLIENT_PLAYER_FORM_PACKET, FormUtils.toData(form), (packetByteBuf) ->
        {
            packetByteBuf.writeInt(player.getId());
        });
    }

    public static void sendClickedModelBlock(ServerPlayer player, BlockPos pos)
    {
        MinecraftServer server = player.getServer();

        if (server == null
            || !PermissionUtils.arePanelsAllowed(server, player)
            || !player.canInteractWithBlock(pos, MODEL_BLOCK_INTERACTION_PADDING)
            || !(player.serverLevel().getBlockEntity(pos) instanceof ModelBlockEntity))
        {
            return;
        }

        mutationSessions.openModelBlockSession(player.getUUID(), player, dimensionId(player.serverLevel()), pos.asLong());

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeBlockPos(pos);

        NetworkCompat.sendToPlayer(player, CLIENT_CLICKED_MODEL_BLOCK_PACKET, buf);
    }

    public static void sendPlayFilm(ServerPlayer player, ServerLevel world, String filmId, boolean withCamera)
    {
        if (player == null
            || world == null
            || player.serverLevel() != world
            || world.getServer() != player.getServer())
        {
            return;
        }

        FilmManager films = BBSMod.getFilms();
        String canonicalId = canonicalFilmId(films, filmId);
        ActionManager actions = BBSMod.getActions();
        UUID owner = player.getUUID();
        String dimension = dimensionId(world);
        boolean hadFilmClaim = canonicalId != null
            && mutationSessions.ownsFilm(canonicalId, owner, player, dimension);

        /* Keep the pre-claim phase cheap: identity/session lookups only. The
         * bounded claim must reject capacity exhaustion before raw Film data
         * can construct addon actions, spawn actors, or emit c3. */
        if (canonicalId == null
            || actions == null
            || actions.getPlayer(canonicalId) != null
            || mutationSessions.getRecording(owner, player) != null
            || !mutationSessions.claimFilm(canonicalId, owner, player, dimension))
        {
            return;
        }

        boolean committed = false;
        boolean recoveryPending = false;
        ActionPlayer startedRuntime = null;

        try
        {
            boolean requesterAuthorized = AuthorizedCommandExecutor.isAuthorized(player, world.getServer());
            Film film = FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized);

            if (film == null)
            {
                return;
            }

            boolean appliesFirstPersonState = FilmActionAuthorityPolicy.canApplyFirstPersonState(
                requesterAuthorized,
                PlayerType.NORMAL,
                true,
                requesterAuthorized
            );

            if ((FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized)
                || !FilmPlaybackPolicy.isPlaybackAllowed(film, player.getMaxHealth(), appliesFirstPersonState))
            {
                return;
            }

            BaseType data = film.toData();
            startedRuntime = actions.play(player, world, film, 0);

            if (startedRuntime == null)
            {
                return;
            }

            crusher.send(world.getPlayers((p) -> true).stream().map((p) -> (Player) p).toList(), CLIENT_PLAY_FILM_PACKET, data, (packetByteBuf) ->
            {
                packetByteBuf.writeUtf(canonicalId);
                packetByteBuf.writeBoolean(withCamera);
            });

            committed = true;
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=net.film_play phase=send result=drop reason=play_failed player={} film={}",
                player.getGameProfile().getName(),
                filmId,
                e);
        }
        finally
        {
            if (!committed)
            {
                /* A failed first-person apply can retain an exact teardown
                 * runtime while the legacy startup result remains null. Recover
                 * that identity before deciding whether its film claim is free. */
                if (startedRuntime == null)
                {
                    startedRuntime = actions.getPlayer(canonicalId, player);
                    recoveryPending = startedRuntime != null;
                }

                boolean canRelease = !recoveryPending
                    && (startedRuntime == null || actions.stopExact(startedRuntime, "film_play_rollback"));

                if (startedRuntime != null && canRelease)
                {
                    for (ServerPlayer observer : world.getPlayers((next) -> true))
                    {
                        trySendStopFilm(observer, canonicalId);
                    }
                }

                if (canRelease && !hadFilmClaim)
                {
                    mutationSessions.releaseFilm(canonicalId, owner, player);
                }
                else if (!canRelease)
                {
                    LOGGER.warn("[BBS-SEM] topic=net.film_play phase=rollback result=partial player={} film={}",
                        player.getGameProfile().getName(), canonicalId);
                }
            }
        }
    }

    public static void sendPlayFilm(ServerPlayer player, String filmId, boolean withCamera)
    {
        sendPlayFilm(player, (ServerPlayer) null, filmId, withCamera);
    }

    public static void sendPlayFilm(
        ServerPlayer player,
        @Nullable ServerPlayer requester,
        String filmId,
        boolean withCamera
    )
    {
        if (player == null)
        {
            return;
        }

        try
        {
            FilmManager films = BBSMod.getFilms();
            String canonicalId = canonicalFilmId(films, filmId);
            ActionManager actions = BBSMod.getActions();

            if (canonicalId == null || actions == null)
            {
                return;
            }

            /* Command-triggered per-target playback is not an editor mutation
             * lease.  Keep the cheap per-target duplicate gate before loading
             * any typed film data, while retaining the shared-film lease fence. */
            if (mutationSessions.hasFilm(canonicalId)
                || actions.hasRecording(player)
                || actions.getPlayer(canonicalId, player) != null)
            {
                return;
            }

            boolean requesterAuthorized = AuthorizedCommandExecutor.isAuthorized(requester, player.getServer());
            boolean allowFirstPersonState = requesterAuthorized;
            Film film = films.exists(canonicalId)
                ? FilmActionAuthorityPolicy.loadFilmForRequester(films, canonicalId, requesterAuthorized)
                : null;

            if (film == null
                || (FilmActionAuthorityPolicy.requiresAdministrator(film) && !requesterAuthorized)
                || !FilmPlaybackPolicy.isPlaybackAllowed(film, player.getMaxHealth(), allowFirstPersonState))
            {
                return;
            }

            BaseType data = film.toData();
            ActionPlayer actionPlayer = actions.playAuthorized(
                player,
                player.serverLevel(),
                film,
                0,
                PlayerType.TARGETED_COMMAND,
                requester,
                allowFirstPersonState
            );

            if (actionPlayer == null)
            {
                return;
            }

            try
            {
                crusher.send(player, CLIENT_PLAY_FILM_PACKET, data, (packetByteBuf) ->
                {
                    packetByteBuf.writeUtf(canonicalId);
                    packetByteBuf.writeBoolean(withCamera);
                });
            }
            catch (RuntimeException e)
            {
                if (!actions.stopExact(actionPlayer, "targeted_play_rollback"))
                {
                    LOGGER.warn("[BBS-SEM] topic=net.film_play phase=rollback result=partial player={} film={}",
                        player.getGameProfile().getName(), canonicalId);
                }

                throw e;
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=net.film_play phase=send result=drop reason=play_failed player={} film={}",
                player.getGameProfile().getName(),
                filmId,
                e);
        }
    }

    public static boolean stopFilmForPlayer(ServerPlayer player, String filmId)
    {
        if (player == null)
        {
            return false;
        }

        String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);

        if (canonicalId == null)
        {
            return false;
        }

        UUID owner = player.getUUID();

        try
        {
            ActionManager actions = BBSMod.getActions();

            if (actions == null)
            {
                return false;
            }

            ActionPlayer runtime = actions.getPlayer(canonicalId, player);
            ActionPlayer anyRuntime = actions.getPlayer(canonicalId);
            ActionPlayer recordingRuntime = actions.getRecordingPlayer(player);
            ActionRecorder recorder = actions.getRecorder(player);
            boolean ownsFilm = mutationSessions.ownsFilm(canonicalId, owner, player);
            NetworkMutationSessions.RecordingSession recording = mutationSessions.getRecording(owner, player);
            boolean sessionMatches = recording != null && canonicalId.equals(recording.filmId());
            boolean recorderMatches = recorder != null
                && recorder.getFilm() != null
                && canonicalId.equals(recorder.getFilm().getId());
            boolean recordingOnly = sessionMatches
                && recorderMatches
                && recordingRuntime == null
                && runtime == null
                && anyRuntime == null;
            boolean matchingRecording = sessionMatches
                && recorderMatches
                && (recordingOnly || runtime == recordingRuntime);
            boolean targetedRuntime = runtime != null && runtime.type.isTargetedDelivery();

            /* A recording session is valid only with its exact recorder and
             * (when present) its exact recording runtime.  This leaves one
             * deliberate recorder-only terminal path for a partially torn
             * down recording, but never treats a stale film lease as a live
             * playback. */
            if (sessionMatches && !recorderMatches)
            {
                return false;
            }

            if (sessionMatches && recordingRuntime != null && runtime != recordingRuntime)
            {
                return false;
            }

            if (matchingRecording)
            {
                /* handled below through stopRecordingExact */
            }
            else
            {
                /* No exact runtime means no c5, even when a stale lease is
                 * present.  A target-scoped runtime is exempt from the film
                 * lease only when it is the exact target runtime. */
                if (runtime == null
                    || (runtime.type == PlayerType.RECORDING)
                    || (!targetedRuntime && !ownsFilm)
                    || (targetedRuntime && ownsFilm))
                {
                    return false;
                }
            }

            if (targetedRuntime && (ownsFilm || sessionMatches))
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_stop phase=apply result=reject reason=ambiguous_targeted_ownership player={} film={}",
                    player.getGameProfile().getName(), canonicalId);

                return false;
            }

            boolean broadcastRuntime = ownsFilm && !matchingRecording;
            MinecraftServer server = broadcastRuntime ? player.getServer() : null;

            /* Validate the complete notification path before mutating the server
             * runtime. A missing server must not stop/release a shared playback
             * while leaving every observing client running it. */
            if (broadcastRuntime && server == null)
            {
                return false;
            }

            if (matchingRecording)
            {
                return finishRecordingTerminal(
                    player,
                    canonicalId,
                    recorder,
                    RecordingTerminal.LEGACY_MANUAL
                );
            }

            if (!actions.stopExact(runtime, "network_stop"))
            {
                LOGGER.warn("[BBS-SEM] topic=net.film_stop phase=apply result=reject reason=runtime_stop_failed player={} film={}",
                    player.getGameProfile().getName(), canonicalId);

                return false;
            }

            if (ownsFilm)
            {
                mutationSessions.releaseFilm(canonicalId, owner, player);
            }

            boolean delivered = true;

            if (broadcastRuntime)
            {
                for (ServerPlayer otherPlayer : server.getPlayerList().getPlayers())
                {
                    if (!trySendStopFilm(otherPlayer, canonicalId))
                    {
                        delivered = false;
                    }
                }
            }
            else
            {
                delivered = trySendStopFilm(player, canonicalId);
            }

            return delivered;
        }
        catch (RuntimeException e)
        {
            /* Action teardown can be user-data driven. Session release occurs
             * only after teardown, so teardown failures keep ownership claimed.
             * A later delivery failure leaves the canonical server runtime
             * stopped instead of reopening a half-stopped mutation lease. */
            LOGGER.warn("[BBS-SEM] topic=net.film_stop phase=apply result=reject reason=stop_failed player={} film={}",
                player.getGameProfile().getName(), canonicalId, e);

            return false;
        }
    }

    private static boolean trySendStopFilm(ServerPlayer player, String canonicalId)
    {
        if (player == null)
        {
            return false;
        }

        try
        {
            sendStopFilm(player, canonicalId);

            return true;
        }
        catch (RuntimeException e)
        {
            /* One stale connection must not prevent the remaining observers
             * from receiving the same canonical stop identity. */
            LOGGER.warn("[BBS-SEM] topic=net.film_stop phase=send result=drop reason=delivery_failed player={} film={}",
                player.getGameProfile().getName(), canonicalId, e);

            return false;
        }
    }

    public static void sendStopFilm(ServerPlayer player, String filmId)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);

        NetworkCompat.sendToPlayer(player, CLIENT_STOP_FILM_PACKET, buf);
    }

    /**
     * Ask the editing client to re-send the whole film, used when a per-property
     * sync targets a path the server doesn't have (client/server desync).
     */
    public static void requestFilmResync(ServerPlayer player, String filmId)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);

        NetworkCompat.sendToPlayer(player, CLIENT_REQUEST_FILM_RESYNC, buf);
    }

    public static void sendManagerData(ServerPlayer player, int callbackId, RepositoryOperation op, BaseType data)
    {
        crusher.send(player, CLIENT_MANAGER_DATA_PACKET, data, (packetByteBuf) ->
        {
            packetByteBuf.writeInt(callbackId);
            packetByteBuf.writeInt(op.ordinal());
        });
    }

    private static void sendRecordingStartRejected(ServerPlayer player, String filmId, int replayId, int tick)
    {
        try
        {
            sendRecordedActions(
                player,
                filmId,
                replayId,
                tick,
                new Clips("...", BBSMod.getFactoryActionClips()),
                RecordingTerminal.START_REJECTED
            );
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=net.recording phase=start_reject result=drop reason=delivery_failed player={} film={}",
                player == null ? "<unknown>" : player.getGameProfile().getName(), filmId, e);
        }
    }

    public static boolean finishRecordingTerminal(
        ServerPlayer player,
        String filmId,
        ActionRecorder expected,
        RecordingTerminal terminal
    )
    {
        if (player == null || filmId == null || expected == null || terminal == null)
        {
            return false;
        }

        String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);
        UUID owner = player.getUUID();
        NetworkMutationSessions.RecordingSession session = mutationSessions.getRecording(owner, player);

        if (canonicalId == null
            || session == null
            || !canonicalId.equals(session.filmId())
            || !mutationSessions.ownsFilm(canonicalId, owner, player))
        {
            return false;
        }

        try
        {
            ActionManager actions = BBSMod.getActions();
            ActionRecorder recorder = actions.prepareRecordingTerminalExact(
                player,
                expected,
                terminal == RecordingTerminal.SERVER_FORCED
            );

            if (recorder == null)
            {
                return false;
            }

            RecordingTerminal exactTerminal = recorder.isTerminalForced()
                ? RecordingTerminal.SERVER_FORCED
                : RecordingTerminal.LEGACY_MANUAL;

            if (!recorder.isTerminalDelivered())
            {
                if (!sendRecordedActionsForActiveRecording(
                    player,
                    canonicalId,
                    recorder.composeClips(),
                    exactTerminal
                ))
                {
                    return false;
                }

                recorder.markTerminalDelivered();
            }

            if (mutationSessions.releaseRecording(owner, player) != session)
            {
                return false;
            }

            mutationSessions.releaseFilm(canonicalId, owner, player);

            return actions.commitRecordingTerminalExact(player, recorder);
        }
        catch (RuntimeException | LinkageError e)
        {
            LOGGER.warn("[BBS-SEM] topic=net.recording phase=terminal result=retry player={} film={}",
                player.getGameProfile().getName(), canonicalId, e);

            return false;
        }
    }

    public static boolean sendRecordedActionsForActiveRecording(ServerPlayer player, String filmId, Clips clips)
    {
        return sendRecordedActionsForActiveRecording(
            player,
            filmId,
            clips,
            RecordingTerminal.LEGACY_MANUAL
        );
    }

    public static boolean sendRecordedActionsForActiveRecording(
        ServerPlayer player,
        String filmId,
        Clips clips,
        RecordingTerminal terminal
    )
    {
        if (player == null || filmId == null || clips == null || terminal == null)
        {
            return false;
        }

        String canonicalId = canonicalFilmId(BBSMod.getFilms(), filmId);
        NetworkMutationSessions.RecordingSession session = mutationSessions.getRecording(player.getUUID(), player);

        if (canonicalId == null || session == null || !canonicalId.equals(session.filmId()))
        {
            return false;
        }

        sendRecordedActions(
            player,
            session.filmId(),
            session.replayId(),
            session.tick(),
            clips,
            terminal
        );

        return true;
    }

    public static void sendRecordedActions(ServerPlayer player, String filmId, int replayId, int tick, Clips clips)
    {
        sendRecordedActions(
            player,
            filmId,
            replayId,
            tick,
            clips,
            RecordingTerminal.LEGACY_MANUAL
        );
    }

    public static void sendRecordedActions(
        ServerPlayer player,
        String filmId,
        int replayId,
        int tick,
        Clips clips,
        RecordingTerminal terminal
    )
    {
        if (terminal == null)
        {
            return;
        }

        crusher.send(player, CLIENT_RECORDED_ACTIONS, clips.toData(), (packetByteBuf) ->
        {
            packetByteBuf.writeUtf(filmId);
            packetByteBuf.writeInt(replayId);
            packetByteBuf.writeInt(tick);

            /* Preserve the legacy footer exactly; typed terminal outcomes
             * append the optional marker for newer clients. */
            if (terminal != RecordingTerminal.LEGACY_MANUAL)
            {
                packetByteBuf.writeByte(terminal.id());
            }
        });
    }

    public static void sendHandshake(MinecraftServer server, ServerPlayer player)
    {
        LOGGER.info("[BBS-SEM] topic=net.handshake player={} state=send_start", player.getGameProfile().getName());
        NetworkCompat.sendToPlayer(player, ServerNetwork.CLIENT_HANDSHAKE, createHandshakeBuf(server));
        LOGGER.info("[BBS-SEM] topic=net.handshake player={} state=send_done", player.getGameProfile().getName());
    }

    private static FriendlyByteBuf createHandshakeBuf(MinecraftServer server)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();
        String id = "";

        /* No need to do that in singleplayer */
        if (server.isSingleplayer())
        {
            id = "";
        }

        buf.writeUtf(id);

        return buf;
    }

    public static void sendCheatsPermission(ServerPlayer player, boolean cheats)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeBoolean(cheats);

        NetworkCompat.sendToPlayer(player, ServerNetwork.CLIENT_CHEATS_PERMISSION, buf);
    }

    public static void sendSharedForm(ServerPlayer player, MapType data)
    {
        crusher.send(player, CLIENT_SHARED_FORM, data, (packetByteBuf) ->
        {});
    }

    public static void sendEntityForm(ServerPlayer player, IEntityFormProvider actor)
    {
        crusher.send(player, CLIENT_ENTITY_FORM, FormUtils.toData(actor.getForm()), (packetByteBuf) ->
        {
            packetByteBuf.writeInt(actor.getEntityId());
        });
    }

    public static void sendEntityFormToTracking(Entity trackedEntity, IEntityFormProvider actor)
    {
        crusher.sendToPlayersTrackingEntity(trackedEntity, CLIENT_ENTITY_FORM, FormUtils.toData(actor.getForm()), (packetByteBuf) ->
        {
            packetByteBuf.writeInt(actor.getEntityId());
        });
    }

    public static void sendActors(ServerPlayer player, String filmId, Map<String, LivingEntity> actors)
    {
        if (actors.size() > MAX_ACTOR_ENTRIES)
        {
            LOGGER.warn("[BBS-SEM] topic=net.actors phase=send result=reject reason=entry_limit film={} entries={} max={}",
                filmId,
                actors.size(),
                MAX_ACTOR_ENTRIES);
            return;
        }

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);
        buf.writeInt(actors.size());

        for (Map.Entry<String, LivingEntity> entry : actors.entrySet())
        {
            buf.writeUtf(entry.getKey());
            buf.writeInt(entry.getValue().getId());
        }

        NetworkCompat.sendToPlayer(player, CLIENT_ACTORS, buf);
    }

    public static void sendGunProperties(ServerPlayer player, GunProjectileEntity projectile)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();
        GunProperties properties = projectile.getProperties();

        buf.writeInt(projectile.getId());
        properties.toNetwork(buf);

        NetworkCompat.sendToPlayer(player, CLIENT_GUN_PROPERTIES, buf);
    }

    public static void sendPauseFilm(ServerPlayer player, String filmId)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);

        NetworkCompat.sendToPlayer(player, CLIENT_PAUSE_FILM, buf);
    }

    public static void sendSelectedSlot(ServerPlayer player, int slot)
    {
        player.getInventory().selected = slot;

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeInt(slot);

        NetworkCompat.sendToPlayer(player, CLIENT_SELECTED_SLOT, buf);
    }

    public static void sendModelBlockState(ServerPlayer player, BlockPos pos, String trigger)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeBlockPos(pos);
        buf.writeUtf(trigger);

        NetworkCompat.sendToPlayer(player, CLIENT_ANIMATION_STATE_MODEL_BLOCK_TRIGGER, buf);
    }

    public static void sendReloadModelBlocks(ServerPlayer player, int tickRandom)
    {
        if (tickRandom < 0 || tickRandom > MAX_MODEL_BLOCK_REFRESH_TICKS)
        {
            LOGGER.warn("[BBS-SEM] topic=net.model_block_refresh phase=send result=reject reason=range value={} max={}",
                tickRandom,
                MAX_MODEL_BLOCK_REFRESH_TICKS);
            return;
        }

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeInt(tickRandom);

        NetworkCompat.sendToPlayer(player, CLIENT_REFRESH_MODEL_BLOCKS, buf);
    }
}
