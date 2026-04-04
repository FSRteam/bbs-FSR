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
import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ClientNetwork
{
    private static int ids = 0;
    private static Map<Integer, Consumer<BaseType>> callbacks = new HashMap<>();
    private static ClientPacketCrusher crusher = new ClientPacketCrusher();

    private static boolean isBBSModOnServer;

    public static void resetHandshake()
    {
        isBBSModOnServer = false;
        crusher.reset();
    }

    public static boolean isIsBBSModOnServer()
    {
        return isBBSModOnServer;
    }

    /* Network */

    public static void setup()
    {
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_CLICKED_MODEL_BLOCK_PACKET, (buf) -> handleClientModelBlockPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_PLAYER_FORM_PACKET, (buf) -> handlePlayerFormPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_PLAY_FILM_PACKET, (buf) -> handlePlayFilmPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_MANAGER_DATA_PACKET, (buf) -> handleManagerDataPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_STOP_FILM_PACKET, (buf) -> handleStopFilmPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_HANDSHAKE, (buf) -> handleHandshakePacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_RECORDED_ACTIONS, (buf) -> handleRecordedActionsPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ANIMATION_STATE_TRIGGER, (buf) -> handleFormTriggerPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_CHEATS_PERMISSION, (buf) -> handleCheatsPermissionPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_SHARED_FORM, (buf) -> handleShareFormPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ENTITY_FORM, (buf) -> handleEntityFormPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ACTORS, (buf) -> handleActorsPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_GUN_PROPERTIES, (buf) -> handleGunPropertiesPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_PAUSE_FILM, (buf) -> handlePauseFilmPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_SELECTED_SLOT, (buf) -> handleSelectedSlotPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_ANIMATION_STATE_MODEL_BLOCK_TRIGGER, (buf) -> handleAnimationStateModelBlockPacket(MinecraftClient.getInstance(), buf));
        NetworkCompatClient.registerClientReceiver(ServerNetwork.CLIENT_REFRESH_MODEL_BLOCKS, (buf) -> handleRefreshModelBlocksPacket(MinecraftClient.getInstance(), buf));
    }

    /* Handlers */

    private static void handleClientModelBlockPacket(MinecraftClient client, PacketByteBuf buf)
    {
        BlockPos pos = buf.readBlockPos();

        client.execute(() ->
        {
            BlockEntity entity = client.world.getBlockEntity(pos);

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

    private static void handlePlayerFormPacket(MinecraftClient client, PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            int id = packetByteBuf.readInt();
            Form form = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            final Form finalForm = form;

            client.execute(() ->
            {
                Entity entity = client.world.getEntityById(id);
                Morph morph = Morph.getMorph(entity);

                if (morph != null)
                {
                    morph.setForm(finalForm);
                }
            });
        });
    }

    private static void handlePlayFilmPacket(MinecraftClient client, PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readString();
            boolean withCamera = packetByteBuf.readBoolean();
            Film film = new Film();

            film.setId(filmId);
            film.fromData(DataStorageUtils.readFromBytes(bytes));

            client.execute(() -> Films.playFilm(film, withCamera));
        });
    }

    private static void handleManagerDataPacket(MinecraftClient client, PacketByteBuf buf)
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

    private static void handleStopFilmPacket(MinecraftClient client, PacketByteBuf buf)
    {
        String filmId = buf.readString();

        client.execute(() -> Films.stopFilm(filmId));
    }

    private static void handleHandshakePacket(MinecraftClient client, PacketByteBuf buf)
    {
        isBBSModOnServer = true;
    }

    private static void handleRecordedActionsPacket(MinecraftClient client, PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            String filmId = packetByteBuf.readString();
            int replayId = packetByteBuf.readInt();
            int tick = packetByteBuf.readInt();
            BaseType data = DataStorageUtils.readFromBytes(bytes);

            client.execute(() ->
            {
                BBSModClient.getDashboard().getPanels().getPanel(UIFilmPanel.class).receiveActions(filmId, replayId, tick, data);
            });
        });
    }

    private static void handleFormTriggerPacket(MinecraftClient client, PacketByteBuf buf)
    {
        int id = buf.readInt();
        String triggerId = buf.readString();
        int type = buf.readInt();

        client.execute(() ->
        {
            Entity entity = client.world.getEntityById(id);
            Morph morph = Morph.getMorph(entity);

            if (morph != null && morph.getForm() != null)
            {
                morph.getForm().playState(triggerId);
            }

            if (entity instanceof LivingEntity livingEntity && type > 0)
            {
                ItemStack stackInHand = livingEntity.getStackInHand(type == 1 ? Hand.MAIN_HAND : Hand.OFF_HAND);
                ModelProperties properties = BBSModClient.getItemStackProperties(stackInHand);

                if (properties != null && properties.getForm() != null)
                {
                    properties.getForm().playState(triggerId);
                }
            }
        });
    }

    private static void handleCheatsPermissionPacket(MinecraftClient client, PacketByteBuf buf)
    {
        boolean cheats = buf.readBoolean();

        client.execute(() ->
        {
            client.player.setClientPermissionLevel(cheats ? 4 : 0);
        });
    }

    private static void handleShareFormPacket(MinecraftClient client, PacketByteBuf buf)
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

    private static void handleEntityFormPacket(MinecraftClient client, PacketByteBuf buf)
    {
        crusher.receive(buf, (bytes, packetByteBuf) ->
        {
            final Form finalForm = FormUtils.fromData(DataStorageUtils.readFromBytes(bytes));

            if (finalForm == null)
            {
                return;
            }

            int entityId = buf.readInt();

            client.execute(() ->
            {
                Entity entity = client.world.getEntityById(entityId);

                if (entity instanceof IEntityFormProvider provider)
                {
                    provider.setForm(finalForm);
                }
            });
        });
    }

    private static void handleActorsPacket(MinecraftClient client, PacketByteBuf buf)
    {
        Map<String, Integer> actors = new HashMap<>();
        String filmId = buf.readString();

        for (int i = 0, c = buf.readInt(); i < c; i++)
        {
            String key = buf.readString();
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

    private static void handleGunPropertiesPacket(MinecraftClient client, PacketByteBuf buf)
    {
        GunProperties properties = new GunProperties();
        int entityId = buf.readInt();

        properties.fromNetwork(buf);

        client.execute(() ->
        {
            Entity entity = client.world.getEntityById(entityId);

            if (entity instanceof GunProjectileEntity projectile)
            {
                projectile.setProperties(properties);
                projectile.calculateDimensions();
            }
        });
    }

    private static void handlePauseFilmPacket(MinecraftClient client, PacketByteBuf buf)
    {
        String filmId = buf.readString();

        client.execute(() ->
        {
            Films.togglePauseFilm(filmId);
        });
    }

    private static void handleSelectedSlotPacket(MinecraftClient client, PacketByteBuf buf)
    {
        int slot = buf.readInt();

        client.execute(() ->
        {
            client.player.getInventory().selectedSlot = slot;
        });
    }

    private static void handleAnimationStateModelBlockPacket(MinecraftClient client, PacketByteBuf buf)
    {
        BlockPos pos = buf.readBlockPos();
        String state = buf.readString();

        client.execute(() ->
        {
            BlockEntity blockEntity = client.world.getBlockEntity(pos);

            if (blockEntity instanceof ModelBlockEntity block)
            {
                if (block.getProperties().getForm() != null)
                {
                    block.getProperties().getForm().playState(state);
                }
            }
        });
    }

    private static void handleRefreshModelBlocksPacket(MinecraftClient client, PacketByteBuf buf)
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

    /* API */
    
    public static void sendModelBlockForm(BlockPos pos, ModelBlockEntity modelBlock)
    {
        crusher.send(MinecraftClient.getInstance().player, ServerNetwork.SERVER_MODEL_BLOCK_FORM_PACKET, modelBlock.getProperties().toData(), (packetByteBuf) ->
        {
            packetByteBuf.writeBlockPos(pos);
        });
    }

    public static void sendPlayerForm(Form form)
    {
        MapType mapType = FormUtils.toData(form);

        crusher.send(MinecraftClient.getInstance().player, ServerNetwork.SERVER_PLAYER_FORM_PACKET, mapType == null ? new MapType() : mapType, (packetByteBuf) ->
        {});
    }

    public static void sendModelBlockTransforms(MapType data)
    {
        crusher.send(MinecraftClient.getInstance().player, ServerNetwork.SERVER_MODEL_BLOCK_TRANSFORMS_PACKET, data, (packetByteBuf) ->
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
        crusher.send(MinecraftClient.getInstance().player, ServerNetwork.SERVER_MANAGER_DATA_PACKET, data, (packetByteBuf) ->
        {
            packetByteBuf.writeInt(callbackId);
            packetByteBuf.writeInt(op.ordinal());
        });
    }

    public static void sendActionRecording(String filmId, int replayId, int tick, int countdown, boolean state)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeString(filmId);
        buf.writeInt(replayId);
        buf.writeInt(tick);
        buf.writeInt(countdown);
        buf.writeBoolean(state);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ACTION_RECORDING, buf);
    }

    public static void sendToggleFilm(String filmId, boolean withCamera)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeString(filmId);
        buf.writeBoolean(withCamera);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_TOGGLE_FILM, buf);
    }

    public static void sendActionState(String filmId, ActionState state, int tick)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeString(filmId);
        buf.writeByte(state.ordinal());
        buf.writeInt(tick);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ACTION_CONTROL, buf);
    }

    public static void sendSyncData(String filmId, BaseValue data)
    {
        crusher.send(MinecraftClient.getInstance().player, ServerNetwork.SERVER_FILM_DATA_SYNC, data.toData(), (packetByteBuf) ->
        {
            DataPath path = data.getPath();

            packetByteBuf.writeString(filmId);
            packetByteBuf.writeInt(path.strings.size());

            for (String string : path.strings)
            {
                packetByteBuf.writeString(string);
            }
        });
    }

    public static void sendTeleport(PlayerEntity entity, double x, double y, double z)
    {
        sendTeleport(x, y, z, entity.getHeadYaw(), entity.getHeadYaw(), entity.getPitch());
    }

    public static void sendTeleport(double x, double y, double z, float yaw, float bodyYaw, float pitch)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

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
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeString(triggerId);
        buf.writeInt(type);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ANIMATION_STATE_TRIGGER, buf);
    }

    public static void sendSharedForm(Form form, UUID uuid)
    {
        MapType mapType = FormUtils.toData(form);

        crusher.send(MinecraftClient.getInstance().player, ServerNetwork.SERVER_SHARED_FORM, mapType == null ? new MapType() : mapType, (packetByteBuf) ->
        {
            packetByteBuf.writeUuid(uuid);
        });
    }

    public static void sendZoom(boolean zoom)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeBoolean(zoom);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_ZOOM, buf);
    }

    public static void sendPauseFilm(String filmId)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeString(filmId);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_PAUSE_FILM, buf);
    }

    public static void sendApplyFilmPlayerSettingsToPlayer(Film film)
    {
        PacketByteBuf buf = NetworkCompat.createBuffer();

        buf.writeFloat(film.hp.get());
        buf.writeFloat(film.hunger.get());
        buf.writeInt(film.xpLevel.get());
        buf.writeFloat(film.xpProgress.get());

        byte[] invBytes = DataStorageUtils.writeToBytes(film.inventory.toData());

        buf.writeInt(invBytes.length);
        buf.writeBytes(invBytes);

        NetworkCompatClient.sendToServer(ServerNetwork.SERVER_APPLY_FILM_PLAYER_SETTINGS, buf);
    }
}
