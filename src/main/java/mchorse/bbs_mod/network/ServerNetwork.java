package mchorse.bbs_mod.network;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.ActionPlayer;
import mchorse.bbs_mod.film.replays.Inventory;
import mchorse.bbs_mod.actions.ActionRecorder;
import mchorse.bbs_mod.actions.ActionState;
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
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.EnumUtils;
import mchorse.bbs_mod.utils.PermissionUtils;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
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
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServerNetwork
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network");

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

    private static ServerPacketCrusher crusher = new ServerPacketCrusher();

    public static void reset()
    {
        crusher.reset();
    }

    public static void setup()
    {
        NetworkCompat.registerServerReceiver(SERVER_MODEL_BLOCK_FORM_PACKET, (server, player, buf) -> handleModelBlockFormPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, (server, player, buf) -> handleModelBlockTransformsPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_PLAYER_FORM_PACKET, (server, player, buf) -> handlePlayerFormPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_MANAGER_DATA_PACKET, (server, player, buf) -> handleManagerDataPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_ACTION_RECORDING, (server, player, buf) -> handleActionRecording(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_TOGGLE_FILM, (server, player, buf) -> handleToggleFilm(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_ACTION_CONTROL, (server, player, buf) -> handleActionControl(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_FILM_DATA_SYNC, (server, player, buf) -> handleSyncData(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_PLAYER_TP, (server, player, buf) -> handleTeleportPlayer(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_ANIMATION_STATE_TRIGGER, (server, player, buf) -> handleAnimationStateTriggerPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_SHARED_FORM, (server, player, buf) -> handleSharedFormPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_ZOOM, (server, player, buf) -> handleZoomPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_PAUSE_FILM, (server, player, buf) -> handlePauseFilmPacket(server, player, buf));
        NetworkCompat.registerServerReceiver(SERVER_APPLY_FILM_PLAYER_SETTINGS, (server, player, buf) -> handleApplyFilmPlayerSettings(server, player, buf));
    }

    /* Handlers */

    private static void handleModelBlockFormPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            BlockPos pos = packetByteBuf.readBlockPos();

            try
            {
                MapType data = (MapType) DataStorageUtils.readFromBytes(bytes);

                server.execute(() ->
                {
                    Level world = player.level();
                    BlockEntity be = world.getBlockEntity(pos);

                    if (be instanceof ModelBlockEntity modelBlock)
                    {
                        modelBlock.updateForm(data, world);
                    }
                });
            }
            catch (Exception e)
            {}
        });
    }

    private static void handleModelBlockTransformsPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            try
            {
                MapType data = (MapType) DataStorageUtils.readFromBytes(bytes);

                server.execute(() ->
                {
                    ItemStack stack = player.getItemBySlot(EquipmentSlot.MAINHAND).copy();

                    if (stack.getItem() == BBSMod.MODEL_BLOCK_ITEM.get())
                    {
                        updateModelBlockStackData(stack, data);
                    }
                    else if (stack.getItem() == BBSMod.GUN_ITEM.get())
                    {
                        CustomData.update(DataComponents.CUSTOM_DATA, stack, (compoundTag) ->
                        {
                            compoundTag.put("GunData", DataStorageUtils.toNbt(data));
                        });
                    }

                    player.setItemSlot(EquipmentSlot.MAINHAND, stack);
                });
            }
            catch (Exception e)
            {}
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

        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            Form form = null;

            try
            {
                if (DataStorageUtils.readFromBytes(bytes) instanceof MapType data)
                {
                    form = BBSMod.getForms().fromData(data);
                }
            }
            catch (Exception e)
            {}

            final Form finalForm = form;

            server.execute(() ->
            {
                Morph.getMorph(player).setForm(FormUtils.copy(finalForm));

                sendMorphToTracked(player, finalForm);
            });
        });
    }

    private static void handleManagerDataPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            MapType data = (MapType) DataStorageUtils.readFromBytes(bytes);
            int callbackId = packetByteBuf.readInt();
            RepositoryOperation op = RepositoryOperation.values()[packetByteBuf.readInt()];
            FilmManager films = BBSMod.getFilms();

            if (op == RepositoryOperation.LOAD)
            {
                String id = data.getString("id");
                Film film = films.load(id);

                sendManagerData(player, callbackId, op, film.toData());
            }
            else if (op == RepositoryOperation.SAVE)
            {
                films.save(data.getString("id"), data.getMap("data"));
            }
            else if (op == RepositoryOperation.RENAME)
            {
                films.rename(data.getString("from"), data.getString("to"));
            }
            else if (op == RepositoryOperation.DELETE)
            {
                films.delete(data.getString("id"));
            }
            else if (op == RepositoryOperation.KEYS)
            {
                ListType list = DataStorageUtils.stringListToData(films.getKeys());

                sendManagerData(player, callbackId, op, list);
            }
            else if (op == RepositoryOperation.ADD_FOLDER)
            {
                sendManagerData(player, callbackId, op, new ByteType(films.addFolder(data.getString("folder"))));
            }
            else if (op == RepositoryOperation.RENAME_FOLDER)
            {
                sendManagerData(player, callbackId, op, new ByteType(films.renameFolder(data.getString("from"), data.getString("to"))));
            }
            else if (op == RepositoryOperation.DELETE_FOLDER)
            {
                sendManagerData(player, callbackId, op, new ByteType(films.deleteFolder(data.getString("folder"))));
            }
        });
    }

    private static void handleActionRecording(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        String filmId = buf.readUtf();
        int replayId = buf.readInt();
        int tick = buf.readInt();
        int countdown = buf.readInt();
        boolean recording = buf.readBoolean();

        server.execute(() ->
        {
            if (recording)
            {
                Film film = BBSMod.getFilms().load(filmId);

                if (film != null)
                {
                    BBSMod.getActions().startRecording(film, player, 0, countdown, replayId);
                }
            }
            else
            {
                ActionRecorder recorder = BBSMod.getActions().stopRecording(player);
                Clips clips = recorder.composeClips();

                /* Send recorded clips to the client */
                sendRecordedActions(player, filmId, replayId, tick, clips);
            }
        });
    }

    private static void handleToggleFilm(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        String filmId = buf.readUtf();
        boolean withCamera = buf.readBoolean();

        server.execute(() ->
        {
            ActionPlayer actionPlayer = BBSMod.getActions().getPlayer(filmId);

            if (actionPlayer != null)
            {
                BBSMod.getActions().stop(filmId);

                for (ServerPlayer otherPlayer : server.getPlayerList().getPlayers())
                {
                    sendStopFilm(otherPlayer, filmId);
                }
            }
            else
            {
                sendPlayFilm(player, player.serverLevel(), filmId, withCamera);
            }
        });
    }

    private static void handleActionControl(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        ActionManager actions = BBSMod.getActions();
        String filmId = buf.readUtf();
        ActionState state = EnumUtils.getValue(buf.readByte(), ActionState.values(), ActionState.STOP);
        int tick = buf.readInt();

        server.execute(() ->
        {
            if (state == ActionState.SEEK)
            {
                ActionPlayer actionPlayer = actions.getPlayer(filmId);

                if (actionPlayer != null)
                {
                    actionPlayer.goTo(tick);
                }
            }
            else if (state == ActionState.PLAY)
            {
                ActionPlayer actionPlayer = actions.getPlayer(filmId);

                if (actionPlayer != null)
                {
                    actionPlayer.goTo(tick);
                    actionPlayer.playing = true;
                }
            }
            else if (state == ActionState.PAUSE)
            {
                ActionPlayer actionPlayer = actions.getPlayer(filmId);

                if (actionPlayer != null)
                {
                    actionPlayer.goTo(tick);
                    actionPlayer.playing = false;
                }
            }
            else if (state == ActionState.RESTART)
            {
                ActionPlayer actionPlayer = actions.getPlayer(filmId);

                if (actionPlayer == null)
                {
                    Film film = BBSMod.getFilms().load(filmId);

                    if (film != null)
                    {
                        actionPlayer = actions.play(player, player.serverLevel(), film, tick, PlayerType.FILM_EDITOR);
                    }
                }
                else
                {
                    actions.stop(filmId);

                    actionPlayer = actions.play(player, player.serverLevel(), actionPlayer.film, tick, PlayerType.FILM_EDITOR);
                }

                if (actionPlayer != null)
                {
                    actionPlayer.syncing = true;
                    actionPlayer.playing = false;

                    if (tick != 0)
                    {
                        actionPlayer.goTo(0, tick);
                    }
                }

                sendStopFilm(player, filmId);
            }
            else if (state == ActionState.STOP)
            {
                actions.stop(filmId);
            }
        });
    }

    private static void handleSyncData(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readUtf();
            List<String> path = new ArrayList<>();

            for (int i = 0, c = packetByteBuf.readInt(); i < c; i++)
            {
                path.add(packetByteBuf.readUtf());
            }

            BaseType data = DataStorageUtils.readFromBytes(bytes);

            server.execute(() ->
            {
                BBSMod.getActions().syncData(filmId, new DataPath(path), data);
            });
        });
    }

    private static void handleTeleportPlayer(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float yaw = buf.readFloat();
        float bodyYaw = buf.readFloat();
        float pitch = buf.readFloat();

        server.execute(() ->
        {
            player.teleportTo(x, y, z);
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setYBodyRot(bodyYaw);
            player.setXRot(pitch);
        });
    }

    private static void handleAnimationStateTriggerPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        String string = buf.readUtf();
        int type = buf.readInt();
        FriendlyByteBuf newBuf = NetworkCompat.createBuffer();

        newBuf.writeInt(player.getId());
        newBuf.writeUtf(string);
        newBuf.writeInt(type);

        NetworkCompat.sendToPlayersTrackingEntity(player, CLIENT_ANIMATION_STATE_TRIGGER, newBuf);

        server.execute(() ->
        {
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
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            UUID playerUuid = packetByteBuf.readUUID();
            MapType data = (MapType) DataStorageUtils.readFromBytes(bytes);

            server.execute(() ->
            {
                ServerPlayer otherPlayer = server.getPlayerList().getPlayer(playerUuid);

                if (otherPlayer != null)
                {
                    sendSharedForm(otherPlayer, data);
                }
            });
        });
    }

    private static void handleZoomPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        boolean zoom = buf.readBoolean();
        ItemStack main = player.getMainHandItem();

        if (main.getItem() == BBSMod.GUN_ITEM.get())
        {
            GunProperties properties = GunProperties.get(main);
            String command = zoom ? properties.cmdZoomOn : properties.cmdZoomOff;

            if (!command.isEmpty())
            {
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
            }
        }
    }

    private static void handlePauseFilmPacket(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        ActionPlayer actionPlayer = BBSMod.getActions().getPlayer(filmId);

        if (actionPlayer != null)
        {
            actionPlayer.toggle();
        }

        for (ServerPlayer playerEntity : server.getPlayerList().getPlayers())
        {
            sendPauseFilm(playerEntity, filmId);
        }
    }

    private static void handleApplyFilmPlayerSettings(MinecraftServer server, ServerPlayer player, FriendlyByteBuf buf)
    {
        if (!PermissionUtils.arePanelsAllowed(server, player))
        {
            return;
        }

        float hp = buf.readFloat();
        float hunger = buf.readFloat();
        int xpLevel = buf.readInt();
        float xpProgress = buf.readFloat();
        int invSize = buf.readInt();
        byte[] invBytes = invSize > 0 ? new byte[invSize] : null;

        if (invBytes != null)
        {
            buf.readBytes(invBytes);
        }

        final byte[] invBytesFinal = invBytes;

        server.execute(() ->
        {
            ActionPlayer.applyFilmPlayerSettingsTo(player, hp, hunger, xpLevel, xpProgress);

            if (invBytesFinal != null)
            {
                BaseType invData = DataStorageUtils.readFromBytes(invBytesFinal);

                if (invData.isList())
                {
                    Inventory.applyToPlayer(player, invData.asList());
                }
            }
        });
    }

    /* API */

    public static void sendMorph(ServerPlayer player, int playerId, Form form)
    {
        crusher.send(player, CLIENT_PLAYER_FORM_PACKET, FormUtils.toData(form), (packetByteBuf) ->
        {
            packetByteBuf.writeInt(playerId);
        });
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
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeBlockPos(pos);

        NetworkCompat.sendToPlayer(player, CLIENT_CLICKED_MODEL_BLOCK_PACKET, buf);
    }

    public static void sendPlayFilm(ServerPlayer player, ServerLevel world, String filmId, boolean withCamera)
    {
        try
        {
            Film film = BBSMod.getFilms().load(filmId);

            if (film != null)
            {
                BBSMod.getActions().play(player, world, film, 0);

                BaseType data = film.toData();

                crusher.send(world.getPlayers((p) -> true).stream().map((p) -> (Player) p).toList(), CLIENT_PLAY_FILM_PACKET, data, (packetByteBuf) ->
                {
                    packetByteBuf.writeUtf(filmId);
                    packetByteBuf.writeBoolean(withCamera);
                });
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void sendPlayFilm(ServerPlayer player, String filmId, boolean withCamera)
    {
        try
        {
            Film film = BBSMod.getFilms().load(filmId);

            if (film != null)
            {
                BBSMod.getActions().play(player, player.serverLevel(), film, 0);

                crusher.send(player, CLIENT_PLAY_FILM_PACKET, film.toData(), (packetByteBuf) ->
                {
                    packetByteBuf.writeUtf(filmId);
                    packetByteBuf.writeBoolean(withCamera);
                });
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void sendStopFilm(ServerPlayer player, String filmId)
    {
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeUtf(filmId);

        NetworkCompat.sendToPlayer(player, CLIENT_STOP_FILM_PACKET, buf);
    }

    public static void sendManagerData(ServerPlayer player, int callbackId, RepositoryOperation op, BaseType data)
    {
        crusher.send(player, CLIENT_MANAGER_DATA_PACKET, data, (packetByteBuf) ->
        {
            packetByteBuf.writeInt(callbackId);
            packetByteBuf.writeInt(op.ordinal());
        });
    }

    public static void sendRecordedActions(ServerPlayer player, String filmId, int replayId, int tick, Clips clips)
    {
        crusher.send(player, CLIENT_RECORDED_ACTIONS, clips.toData(), (packetByteBuf) ->
        {
            packetByteBuf.writeUtf(filmId);
            packetByteBuf.writeInt(replayId);
            packetByteBuf.writeInt(tick);
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
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeInt(tickRandom);

        NetworkCompat.sendToPlayer(player, CLIENT_REFRESH_MODEL_BLOCKS, buf);
    }
}
