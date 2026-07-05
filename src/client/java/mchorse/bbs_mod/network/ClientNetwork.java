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
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.repos.RepositoryOperation;
import mchorse.bbs_mod.network.compat.AddonPayloadBroker;
import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ClientNetwork
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-network-client");
    private static int ids = 0;
    private static Map<Integer, Consumer<BaseType>> callbacks = new HashMap<>();
    private static ClientPacketCrusher crusher = new ClientPacketCrusher();

    private static boolean isBBSModOnServer;

    public static void resetHandshake()
    {
        isBBSModOnServer = false;
        crusher.reset();
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
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_CLICKED_MODEL_BLOCK_PACKET, (buf) -> handleClientModelBlockPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_PLAYER_FORM_PACKET, (buf) -> handlePlayerFormPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_PLAY_FILM_PACKET, (buf) -> handlePlayFilmPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_MANAGER_DATA_PACKET, (buf) -> handleManagerDataPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_STOP_FILM_PACKET, (buf) -> handleStopFilmPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_HANDSHAKE, (buf) -> handleHandshakePacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_RECORDED_ACTIONS, (buf) -> handleRecordedActionsPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ANIMATION_STATE_TRIGGER, (buf) -> handleFormTriggerPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_CHEATS_PERMISSION, (buf) -> handleCheatsPermissionPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_SHARED_FORM, (buf) -> handleShareFormPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ENTITY_FORM, (buf) -> handleEntityFormPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ACTORS, (buf) -> handleActorsPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_GUN_PROPERTIES, (buf) -> handleGunPropertiesPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_PAUSE_FILM, (buf) -> handlePauseFilmPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_SELECTED_SLOT, (buf) -> handleSelectedSlotPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ANIMATION_STATE_MODEL_BLOCK_TRIGGER, (buf) -> handleAnimationStateModelBlockPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_REFRESH_MODEL_BLOCKS, (buf) -> handleRefreshModelBlocksPacket(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_REQUEST_FILM_RESYNC, (buf) -> handleRequestFilmResync(Minecraft.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ADDON_BROKER, AddonPayloadBroker::handleClientPayload);
    }

    /* Handlers */

    private static void handleClientModelBlockPacket(Minecraft client, FriendlyByteBuf buf)
    {
        BlockPos pos = buf.readBlockPos();

        client.execute(() ->
        {
            BlockEntity entity = client.level.getBlockEntity(pos);

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
        });
    }

    private static void handlePlayerFormPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            int id = packetByteBuf.readInt();
            Form form = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            final Form finalForm = form;

            client.execute(() ->
            {
                Entity entity = client.level.getEntity(id);
                Morph morph = Morph.getMorph(entity);

                if (morph != null)
                {
                    morph.setForm(finalForm);
                }
            });
        });
    }

    private static void handlePlayFilmPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readUtf();
            boolean withCamera = packetByteBuf.readBoolean();
            Film film = new Film();

            film.setId(filmId);
            film.fromData(DataStorageUtils.readFromBytes(bytes));

            client.execute(() -> Films.playFilm(film, withCamera));
        });
    }

    private static void handleManagerDataPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            int callbackId = packetByteBuf.readInt();
            RepositoryOperation op = RepositoryOperation.values()[packetByteBuf.readInt()];
            BaseType data = DataStorageUtils.readFromBytes(bytes);

            client.execute(() ->
            {
                Consumer<BaseType> callback = callbacks.remove(callbackId);

                if (callback != null)
                {
                    callback.accept(data);
                }
            });
        });
    }

    private static void handleStopFilmPacket(Minecraft client, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        client.execute(() -> Films.stopFilm(filmId));
    }

    private static void handleHandshakePacket(Minecraft client, FriendlyByteBuf buf)
    {
        isBBSModOnServer = true;
    }

    private static void handleRecordedActionsPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readUtf();
            int replayId = packetByteBuf.readInt();
            int tick = packetByteBuf.readInt();
            BaseType data = DataStorageUtils.readFromBytes(bytes);

            client.execute(() ->
            {
                BBSModClient.getDashboard().getPanels().getPanel(UIFilmPanel.class).receiveActions(filmId, replayId, tick, data);
            });
        });
    }

    private static void handleFormTriggerPacket(Minecraft client, FriendlyByteBuf buf)
    {
        int id = buf.readInt();
        String triggerId = buf.readUtf();
        int type = buf.readInt();

        client.execute(() ->
        {
            Entity entity = client.level.getEntity(id);
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

    private static void handleCheatsPermissionPacket(Minecraft client, FriendlyByteBuf buf)
    {
        boolean cheats = buf.readBoolean();

        client.execute(() ->
        {
            client.player.setPermissionLevel(cheats ? 4 : 0);
        });
    }

    private static void handleShareFormPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            final Form finalForm = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            if (finalForm == null)
            {
                return;
            }

            client.execute(() ->
            {
                UIBaseMenu menu = UIScreen.getCurrentMenu();
                UIDashboard dashboard = BBSModClient.getDashboard();

                if (menu == null)
                {
                    UIScreen.open(dashboard);
                }

                dashboard.setPanel(dashboard.getPanel(UIMorphingPanel.class));
                BBSModClient.getFormCategories().getRecentForms().getCategories().get(0).addForm(finalForm);
                dashboard.context.notifyInfo(UIKeys.FORMS_SHARED_NOTIFICATION.format(finalForm.getDisplayName()));
            });
        });
    }

    private static void handleEntityFormPacket(Minecraft client, FriendlyByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            final Form finalForm = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            if (finalForm == null)
            {
                return;
            }

            int entityId = packetByteBuf.readInt();

            client.execute(() ->
            {
                Entity entity = client.level.getEntity(entityId);

                if (entity instanceof IEntityFormProvider provider)
                {
                    provider.setForm(finalForm);
                }
            });
        });
    }

    private static void handleActorsPacket(Minecraft client, FriendlyByteBuf buf)
    {
        Map<String, Integer> actors = new HashMap<>();
        String filmId = buf.readUtf();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            String key = buf.readUtf();
            int entityId = buf.readInt();

            actors.put(key, entityId);
        }

        client.execute(() ->
        {
            UIDashboard dashboard = BBSModClient.getDashboard();
            UIFilmPanel panel = dashboard.getPanel(UIFilmPanel.class);

            panel.updateActors(filmId, actors);
            BBSModClient.getFilms().updateActors(filmId, actors);
        });
    }

    private static void handleGunPropertiesPacket(Minecraft client, FriendlyByteBuf buf)
    {
        GunProperties properties = new GunProperties();
        int entityId = buf.readInt();

        properties.fromNetwork(buf);

        client.execute(() ->
        {
            Entity entity = client.level.getEntity(entityId);

            if (entity instanceof GunProjectileEntity projectile)
            {
                projectile.setProperties(properties);
                projectile.refreshDimensions();
            }
        });
    }

    private static void handlePauseFilmPacket(Minecraft client, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        client.execute(() ->
        {
            Films.togglePauseFilm(filmId);
        });
    }

    private static void handleSelectedSlotPacket(Minecraft client, FriendlyByteBuf buf)
    {
        int slot = buf.readInt();

        client.execute(() ->
        {
            client.player.getInventory().selected = slot;
        });
    }

    private static void handleAnimationStateModelBlockPacket(Minecraft client, FriendlyByteBuf buf)
    {
        BlockPos pos = buf.readBlockPos();
        String state = buf.readUtf();

        client.execute(() ->
        {
            BlockEntity blockEntity = client.level.getBlockEntity(pos);

            if (blockEntity instanceof ModelBlockEntity block)
            {
                if (block.getProperties().getForm() != null)
                {
                    block.getProperties().getForm().playState(state);
                }
            }
        });
    }

    private static void handleRefreshModelBlocksPacket(Minecraft client, FriendlyByteBuf buf)
    {
        int range = buf.readInt();

        client.execute(() ->
        {
            for (ModelBlockEntity mb : BBSRendering.capturedModelBlocks)
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
        });
    }

    private static void handleRequestFilmResync(Minecraft client, FriendlyByteBuf buf)
    {
        String filmId = buf.readUtf();

        client.execute(() ->
        {
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
        int id = ids;

        callbacks.put(id, consumer);
        sendManagerData(id, op, data);

        ids += 1;
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
        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(yaw);
        buf.writeFloat(bodyYaw);
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

    public static void sendApplyFilmPlayerSettingsToPlayer(Film film)
    {
        byte[] invBytes = DataStorageUtils.writeToBytes(film.inventory.toData());

        if (invBytes.length > ServerNetwork.MAX_APPLY_FILM_PLAYER_SETTINGS_INVENTORY_BYTES)
        {
            LOGGER.warn("[BBS-SEM] topic=net.apply_player_settings phase=send result=reject reason=inventory_size size={} max={}",
                invBytes.length,
                ServerNetwork.MAX_APPLY_FILM_PLAYER_SETTINGS_INVENTORY_BYTES);
            return;
        }

        FriendlyByteBuf buf = NetworkCompat.createBuffer();

        buf.writeFloat(film.hp.get());
        buf.writeFloat(film.hunger.get());
        buf.writeInt(film.xpLevel.get());
        buf.writeFloat(film.xpProgress.get());
        buf.writeInt(invBytes.length);
        buf.writeBytes(invBytes);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_APPLY_FILM_PLAYER_SETTINGS, buf);
    }
}
