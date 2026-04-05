package mchorse.bbs_mod.client.compat;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.entity.Entity;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;

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
    private static final List<WorldRenderHandler> AFTER_ENTITIES_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<WorldRenderHandler> LAST_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<DisconnectHandler> DISCONNECT_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftClient>> START_CLIENT_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftClient>> END_WORLD_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftClient>> END_CLIENT_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<HudRenderHandler> HUD_RENDER_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftClient>> CLIENT_STOPPING_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftClient>> CLIENT_STARTED_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<EntityRendererRegistration<?>> ENTITY_RENDERER_REGISTRATIONS = new CopyOnWriteArrayList<>();
    private static final List<BlockEntityRendererRegistration<?>> BLOCK_ENTITY_RENDERER_REGISTRATIONS = new CopyOnWriteArrayList<>();

    private ClientApiCompat() {}

    @FunctionalInterface
    public interface DisconnectHandler
    {
        void onDisconnect(MinecraftClient client);
    }

    @FunctionalInterface
    public interface WorldRenderHandler
    {
        void render(BBSWorldRenderContext context);
    }

    @FunctionalInterface
    public interface HudRenderHandler
    {
        void render(DrawContext drawContext, float tickDelta);
    }

    public static KeyMapping registerKeyBinding(KeyMapping keyBinding)
    {
        return Objects.requireNonNull(keyBinding, "keyBinding");
    }

    public static void onAfterEntities(WorldRenderHandler handler)
    {
        AFTER_ENTITIES_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onLast(WorldRenderHandler handler)
    {
        LAST_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onDisconnect(DisconnectHandler handler)
    {
        DISCONNECT_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onStartClientTick(Consumer<MinecraftClient> handler)
    {
        START_CLIENT_TICK_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onEndWorldTick(Consumer<MinecraftClient> handler)
    {
        END_WORLD_TICK_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onEndClientTick(Consumer<MinecraftClient> handler)
    {
        END_CLIENT_TICK_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onHudRender(HudRenderHandler handler)
    {
        HUD_RENDER_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onClientStopping(Consumer<MinecraftClient> handler)
    {
        CLIENT_STOPPING_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static void onClientStarted(Consumer<MinecraftClient> handler)
    {
        CLIENT_STARTED_HANDLERS.add(Objects.requireNonNull(handler, "handler"));
    }

    public static <T extends Entity> void registerEntityRenderer(net.minecraft.entity.EntityType<T> type, EntityRendererFactory<T> factory)
    {
        ENTITY_RENDERER_REGISTRATIONS.add(new EntityRendererRegistration<>(type, factory));
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, BlockEntityRendererFactory<? super E> factory)
    {
        BLOCK_ENTITY_RENDERER_REGISTRATIONS.add(new BlockEntityRendererRegistration<>(type, factory));
    }

    public static void emitAfterEntities(IBbsWorldRenderContext context)
    {
        BBSWorldRenderContext compatContext = BBSWorldRenderContext.bridge(Objects.requireNonNull(context, "context"));

        for (WorldRenderHandler handler : AFTER_ENTITIES_HANDLERS)
        {
            handler.render(compatContext);
        }
    }

    public static void emitLast(IBbsWorldRenderContext context)
    {
        BBSWorldRenderContext compatContext = BBSWorldRenderContext.bridge(Objects.requireNonNull(context, "context"));

        for (WorldRenderHandler handler : LAST_HANDLERS)
        {
            handler.render(compatContext);
        }
    }

    public static void emitDisconnect(MinecraftClient client)
    {
        for (DisconnectHandler handler : DISCONNECT_HANDLERS)
        {
            handler.onDisconnect(client);
        }
    }

    public static void emitStartClientTick(MinecraftClient client)
    {
        for (Consumer<MinecraftClient> handler : START_CLIENT_TICK_HANDLERS)
        {
            handler.accept(client);
        }
    }

    public static void emitEndWorldTick(MinecraftClient client)
    {
        for (Consumer<MinecraftClient> handler : END_WORLD_TICK_HANDLERS)
        {
            handler.accept(client);
        }
    }

    public static void emitEndClientTick(MinecraftClient client)
    {
        for (Consumer<MinecraftClient> handler : END_CLIENT_TICK_HANDLERS)
        {
            handler.accept(client);
        }
    }

    public static void emitHudRender(DrawContext drawContext, float tickDelta)
    {
        for (HudRenderHandler handler : HUD_RENDER_HANDLERS)
        {
            handler.render(drawContext, tickDelta);
        }
    }

    public static void emitClientStopping(MinecraftClient client)
    {
        for (Consumer<MinecraftClient> handler : CLIENT_STOPPING_HANDLERS)
        {
            handler.accept(client);
        }
    }

    public static void emitClientStarted(MinecraftClient client)
    {
        for (Consumer<MinecraftClient> handler : CLIENT_STARTED_HANDLERS)
        {
            handler.accept(client);
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
        private final net.minecraft.entity.EntityType<T> type;
        private final EntityRendererFactory<T> factory;

        public EntityRendererRegistration(net.minecraft.entity.EntityType<T> type, EntityRendererFactory<T> factory)
        {
            this.type = Objects.requireNonNull(type, "type");
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        public net.minecraft.entity.EntityType<T> getType()
        {
            return this.type;
        }

        public EntityRendererFactory<T> getFactory()
        {
            return this.factory;
        }
    }

    public static final class BlockEntityRendererRegistration<E extends BlockEntity>
    {
        private final BlockEntityType<E> type;
        private final BlockEntityRendererFactory<? super E> factory;

        public BlockEntityRendererRegistration(BlockEntityType<E> type, BlockEntityRendererFactory<? super E> factory)
        {
            this.type = Objects.requireNonNull(type, "type");
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        public BlockEntityType<E> getType()
        {
            return this.type;
        }

        public BlockEntityRendererFactory<? super E> getFactory()
        {
            return this.factory;
        }
    }

}
