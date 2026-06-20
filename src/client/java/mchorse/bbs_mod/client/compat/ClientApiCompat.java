package mchorse.bbs_mod.client.compat;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Transitional compatibility shim kept to avoid immediate source breakage.
 *
 * @deprecated Wire NeoForge events directly and use renderer registration events.
 */
@Deprecated(forRemoval = false, since = "M10")
public final class ClientApiCompat
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-client-api");

    private static final List<WorldRenderHandler> AFTER_ENTITIES_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<WorldRenderHandler> LAST_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<DisconnectHandler> DISCONNECT_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> START_CLIENT_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> END_WORLD_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> END_CLIENT_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<HudRenderHandler> HUD_RENDER_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> CLIENT_STOPPING_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> CLIENT_STARTED_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<EntityRendererRegistration<?>> ENTITY_RENDERER_REGISTRATIONS = new CopyOnWriteArrayList<>();
    private static final List<BlockEntityRendererRegistration<?>> BLOCK_ENTITY_RENDERER_REGISTRATIONS = new CopyOnWriteArrayList<>();
    private static final List<KeyMapping> KEY_BINDING_REGISTRATIONS = new CopyOnWriteArrayList<>();
    private static volatile boolean keyMappingsEventFired;

    private ClientApiCompat() {}

    @FunctionalInterface
    public interface DisconnectHandler
    {
        void onDisconnect(Minecraft client);
    }

    @FunctionalInterface
    public interface WorldRenderHandler
    {
        void render(BBSWorldRenderContext context);
    }

    @FunctionalInterface
    public interface HudRenderHandler
    {
        void render(net.minecraft.client.gui.GuiGraphics drawContext, float tickDelta);
    }

    public static KeyMapping registerKeyBinding(KeyMapping keyBinding)
    {
        KeyMapping mapping = Objects.requireNonNull(keyBinding, "keyBinding");

        if (!KEY_BINDING_REGISTRATIONS.contains(mapping))
        {
            KEY_BINDING_REGISTRATIONS.add(mapping);
        }

        if (keyMappingsEventFired)
        {
            LOGGER.warn("[bbs-client-api] key binding '{}' was registered after RegisterKeyMappingsEvent and may not appear until restart", mapping.getName());
        }

        return mapping;
    }

    public static void registerQueuedKeyMappings(Consumer<KeyMapping> register)
    {
        Objects.requireNonNull(register, "register");

        for (KeyMapping mapping : KEY_BINDING_REGISTRATIONS)
        {
            try
            {
                register.accept(mapping);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] key binding registration failed for '{}'", mapping.getName(), e);
            }
        }

        keyMappingsEventFired = true;
    }

    public static void onAfterEntities(WorldRenderHandler handler)
    {
        AFTER_ENTITIES_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onLast(WorldRenderHandler handler)
    {
        LAST_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static boolean hasLastHandlers()
    {
        return !LAST_HANDLERS.isEmpty();
    }

    public static void onDisconnect(DisconnectHandler handler)
    {
        DISCONNECT_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onStartClientTick(Consumer<Minecraft> handler)
    {
        START_CLIENT_TICK_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onEndWorldTick(Consumer<Minecraft> handler)
    {
        END_WORLD_TICK_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onEndClientTick(Consumer<Minecraft> handler)
    {
        END_CLIENT_TICK_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onHudRender(HudRenderHandler handler)
    {
        HUD_RENDER_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onClientStopping(Consumer<Minecraft> handler)
    {
        CLIENT_STOPPING_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onClientStarted(Consumer<Minecraft> handler)
    {
        CLIENT_STARTED_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static <T extends Entity> void registerEntityRenderer(EntityType<T> type, EntityRendererProvider<T> factory)
    {
        ENTITY_RENDERER_REGISTRATIONS.add(new EntityRendererRegistration<>(type, factory));
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory)
    {
        BLOCK_ENTITY_RENDERER_REGISTRATIONS.add(new BlockEntityRendererRegistration<>(type, factory));
    }

    public static void emitAfterEntities(IBbsWorldRenderContext context)
    {
        if (AFTER_ENTITIES_HANDLERS.isEmpty())
        {
            return;
        }

        BBSWorldRenderContext compatContext = BBSWorldRenderContext.bridge(Objects.requireNonNull(context, "context"));

        for (WorldRenderHandler handler : AFTER_ENTITIES_HANDLERS)
        {
            try
            {
                handler.render(compatContext);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] after-entities handler failed", e);
            }
        }
    }

    public static void emitLast(IBbsWorldRenderContext context)
    {
        if (LAST_HANDLERS.isEmpty())
        {
            return;
        }

        BBSWorldRenderContext compatContext = BBSWorldRenderContext.bridge(Objects.requireNonNull(context, "context"));

        for (WorldRenderHandler handler : LAST_HANDLERS)
        {
            try
            {
                handler.render(compatContext);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] after-level handler failed", e);
            }
        }
    }

    public static void emitDisconnect(Minecraft client)
    {
        for (DisconnectHandler handler : DISCONNECT_HANDLERS)
        {
            try
            {
                handler.onDisconnect(client);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] disconnect handler failed", e);
            }
        }
    }

    public static void emitStartClientTick(Minecraft client)
    {
        for (Consumer<Minecraft> handler : START_CLIENT_TICK_HANDLERS)
        {
            try
            {
                handler.accept(client);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] start-client-tick handler failed", e);
            }
        }
    }

    public static void emitEndWorldTick(Minecraft client)
    {
        for (Consumer<Minecraft> handler : END_WORLD_TICK_HANDLERS)
        {
            try
            {
                handler.accept(client);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] end-world-tick handler failed", e);
            }
        }
    }

    public static void emitEndClientTick(Minecraft client)
    {
        for (Consumer<Minecraft> handler : END_CLIENT_TICK_HANDLERS)
        {
            try
            {
                handler.accept(client);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] end-client-tick handler failed", e);
            }
        }
    }

    public static void emitHudRender(net.minecraft.client.gui.GuiGraphics drawContext, float tickDelta)
    {
        for (HudRenderHandler handler : HUD_RENDER_HANDLERS)
        {
            try
            {
                handler.render(drawContext, tickDelta);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] hud-render handler failed", e);
            }
        }
    }

    public static void emitClientStopping(Minecraft client)
    {
        for (Consumer<Minecraft> handler : CLIENT_STOPPING_HANDLERS)
        {
            try
            {
                handler.accept(client);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] client-stopping handler failed", e);
            }
        }
    }

    public static void emitClientStarted(Minecraft client)
    {
        for (Consumer<Minecraft> handler : CLIENT_STARTED_HANDLERS)
        {
            try
            {
                handler.accept(client);
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-client-api] client-started handler failed", e);
            }
        }
    }

    public static List<EntityRendererRegistration<?>> getEntityRendererRegistrations()
    {
        return Collections.unmodifiableList(new ArrayList<>(ENTITY_RENDERER_REGISTRATIONS));
    }

    public static List<BlockEntityRendererRegistration<?>> getBlockEntityRendererRegistrations()
    {
        return Collections.unmodifiableList(new ArrayList<>(BLOCK_ENTITY_RENDERER_REGISTRATIONS));
    }

    public static final class EntityRendererRegistration<T extends Entity>
    {
        private final EntityType<T> type;
        private final EntityRendererProvider<T> factory;

        public EntityRendererRegistration(EntityType<T> type, EntityRendererProvider<T> factory)
        {
            this.type = Objects.requireNonNull(type, "type");
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        public EntityType<T> getType()
        {
            return this.type;
        }

        public EntityRendererProvider<T> getFactory()
        {
            return this.factory;
        }
    }

    public static final class BlockEntityRendererRegistration<E extends BlockEntity>
    {
        private final BlockEntityType<E> type;
        private final BlockEntityRendererProvider<? super E> factory;

        public BlockEntityRendererRegistration(BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory)
        {
            this.type = Objects.requireNonNull(type, "type");
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        public BlockEntityType<E> getType()
        {
            return this.type;
        }

        public BlockEntityRendererProvider<? super E> getFactory()
        {
            return this.factory;
        }
    }

}
