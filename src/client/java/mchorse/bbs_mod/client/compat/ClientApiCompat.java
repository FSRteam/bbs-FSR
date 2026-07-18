package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.addon.BBSAddonDescriptor;
import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
    private static final String PUBLIC_CLIENT_API_CLASS = "mchorse.bbs_mod.api.client.BBSClientApi";
    private static final StackWalker REGISTRATION_SOURCE_WALKER = StackWalker.getInstance();

    private static final List<WorldRenderHandler> AFTER_ENTITIES_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<WorldRenderHandler> LAST_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<DisconnectHandler> DISCONNECT_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> START_CLIENT_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> END_WORLD_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> END_CLIENT_TICK_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<HudRenderHandler> HUD_RENDER_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> CLIENT_STOPPING_HANDLERS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Minecraft>> CLIENT_STARTED_HANDLERS = new CopyOnWriteArrayList<>();
    private static final StructuralRegistrationWindow<EntityRendererRegistration<?>> ENTITY_RENDERER_REGISTRATIONS =
        new StructuralRegistrationWindow<>("entity renderer", "EntityRenderersEvent.RegisterRenderers");
    private static final StructuralRegistrationWindow<BlockEntityRendererRegistration<?>> BLOCK_ENTITY_RENDERER_REGISTRATIONS =
        new StructuralRegistrationWindow<>("block entity renderer", "EntityRenderersEvent.RegisterRenderers");
    private static final StructuralRegistrationWindow<KeyMapping> KEY_BINDING_REGISTRATIONS =
        new StructuralRegistrationWindow<>("key binding", "RegisterKeyMappingsEvent");

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
        return registerKeyBinding(null, keyBinding);
    }

    public static KeyMapping registerKeyBinding(BBSAddonDescriptor descriptor, KeyMapping keyBinding)
    {
        KeyMapping mapping = Objects.requireNonNull(keyBinding, "keyBinding");

        KEY_BINDING_REGISTRATIONS.register(mapping, "key-binding:" + mapping.getName(), descriptor, registrationSource());

        return mapping;
    }

    public static void registerQueuedKeyMappings(Consumer<KeyMapping> register)
    {
        Objects.requireNonNull(register, "register");
        KEY_BINDING_REGISTRATIONS.consume(register);
    }

    /** Internal NeoForge event bridge: seal before core event callbacks run. */
    public static void closeKeyMappingRegistrationWindow()
    {
        KEY_BINDING_REGISTRATIONS.close();
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
        registerEntityRenderer(null, type, factory);
    }

    public static <T extends Entity> void registerEntityRenderer(BBSAddonDescriptor descriptor, EntityType<T> type, EntityRendererProvider<T> factory)
    {
        EntityRendererRegistration<T> registration = new EntityRendererRegistration<>(type, factory);

        ENTITY_RENDERER_REGISTRATIONS.register(registration, "entity-renderer:" + type, descriptor, registrationSource());
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory)
    {
        registerBlockEntityRenderer(null, type, factory);
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BBSAddonDescriptor descriptor, BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory)
    {
        BlockEntityRendererRegistration<E> registration = new BlockEntityRendererRegistration<>(type, factory);

        BLOCK_ENTITY_RENDERER_REGISTRATIONS.register(registration, "block-entity-renderer:" + type, descriptor, registrationSource());
    }

    public static void registerQueuedEntityRenderers(Consumer<EntityRendererRegistration<?>> register)
    {
        Objects.requireNonNull(register, "register");
        ENTITY_RENDERER_REGISTRATIONS.consume(register);
    }

    public static void registerQueuedBlockEntityRenderers(Consumer<BlockEntityRendererRegistration<?>> register)
    {
        Objects.requireNonNull(register, "register");
        BLOCK_ENTITY_RENDERER_REGISTRATIONS.consume(register);
    }

    /** Internal NeoForge event bridge: seal both renderer queues at event entry. */
    public static void closeRendererRegistrationWindows()
    {
        ENTITY_RENDERER_REGISTRATIONS.close();
        BLOCK_ENTITY_RENDERER_REGISTRATIONS.close();
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
        return ENTITY_RENDERER_REGISTRATIONS.snapshot();
    }

    public static List<BlockEntityRendererRegistration<?>> getBlockEntityRendererRegistrations()
    {
        return BLOCK_ENTITY_RENDERER_REGISTRATIONS.snapshot();
    }

    private static String registrationSource()
    {
        return REGISTRATION_SOURCE_WALKER.walk(frames -> frames
            .map(StackWalker.StackFrame::getClassName)
            .filter(ClientApiCompat::isRegistrationSource)
            .findFirst()
            .orElse("<unknown>"));
    }

    private static boolean isRegistrationSource(String className)
    {
        return !className.equals(ClientApiCompat.class.getName())
            && !className.equals(PUBLIC_CLIENT_API_CLASS)
            && !className.startsWith("java.lang.reflect.")
            && !className.startsWith("jdk.internal.reflect.");
    }

    private static void recordClientDiagnostic(
        BBSAddonDescriptor descriptor,
        BBSAddonPhase phase,
        String source,
        BBSRegistrationResult result,
        Throwable error
    )
    {
        if (descriptor == null)
        {
            return;
        }

        try
        {
            if (!BBSMod.recordAddonClientDiagnostic(descriptor, phase, source, result, error))
            {
                LOGGER.warn(
                    "[bbs-client-api] addon diagnostic for '{}' was not attached during phase={} from source={}",
                    descriptor.addonId(),
                    phase,
                    source
                );
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error(
                "[bbs-client-api] failed to record addon diagnostic for '{}' during phase={} from source={}",
                descriptor.addonId(),
                phase,
                source,
                e
            );
        }
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

    static final class StructuralRegistrationWindow<T>
    {
        private final String kind;
        private final String event;
        private final List<QueuedStructuralRegistration<T>> registrations = new ArrayList<>();
        private boolean open = true;
        private boolean consumed;

        StructuralRegistrationWindow(String kind, String event)
        {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.event = Objects.requireNonNull(event, "event");
        }

        synchronized boolean register(T value, String id, BBSAddonDescriptor descriptor, String source)
        {
            T checkedValue = Objects.requireNonNull(value, "value");
            String checkedId = id == null || id.isBlank() ? "<unknown>" : id;
            String checkedSource = source == null || source.isBlank() ? "<unknown>" : source;

            if (!this.open)
            {
                BBSRegistrationResult result = BBSRegistrationResult.rejected(
                    checkedId,
                    this.event + " has already fired"
                );

                LOGGER.warn(
                    "[bbs-client-api] rejected late {} registration '{}' during phase={} from source={} because {} has already fired",
                    this.kind,
                    checkedId,
                    BBSAddonPhase.REGISTER_CLIENT,
                    checkedSource,
                    this.event
                );
                recordClientDiagnostic(descriptor, BBSAddonPhase.REGISTER_CLIENT, checkedSource, result, null);

                return false;
            }

            for (QueuedStructuralRegistration<T> registration : this.registrations)
            {
                if (registration.value.equals(checkedValue))
                {
                    return true;
                }
            }

            this.registrations.add(new QueuedStructuralRegistration<>(checkedValue, checkedId, descriptor, checkedSource));
            recordClientDiagnostic(
                descriptor,
                BBSAddonPhase.REGISTER_CLIENT,
                checkedSource,
                BBSRegistrationResult.accepted(checkedId),
                null
            );

            return true;
        }

        void consume(Consumer<T> register)
        {
            List<QueuedStructuralRegistration<T>> snapshot;

            synchronized (this)
            {
                if (this.consumed)
                {
                    LOGGER.warn(
                        "[bbs-client-api] ignored repeated {} consumption during phase={} after {} already fired",
                        this.kind,
                        BBSAddonPhase.CLIENT_SETUP,
                        this.event
                    );

                    return;
                }

                this.open = false;
                this.consumed = true;
                snapshot = List.copyOf(this.registrations);
            }

            for (QueuedStructuralRegistration<T> registration : snapshot)
            {
                try
                {
                    register.accept(registration.value);
                    recordClientDiagnostic(
                        registration.descriptor,
                        BBSAddonPhase.CLIENT_SETUP,
                        registration.source,
                        null,
                        null
                    );
                }
                catch (Exception | LinkageError e)
                {
                    BBSRegistrationResult result = BBSRegistrationResult.rejected(
                        registration.id,
                        this.kind + " registration failed"
                    );

                    LOGGER.error(
                        "[bbs-client-api] {} registration failed for '{}' during phase={} from source={}",
                        this.kind,
                        registration.id,
                        BBSAddonPhase.CLIENT_SETUP,
                        registration.source,
                        e
                    );
                    recordClientDiagnostic(
                        registration.descriptor,
                        BBSAddonPhase.CLIENT_SETUP,
                        registration.source,
                        result,
                        e
                    );
                }
            }
        }

        synchronized void close()
        {
            this.open = false;
        }

        synchronized List<T> snapshot()
        {
            return Collections.unmodifiableList(this.registrations.stream()
                .map(registration -> registration.value)
                .toList());
        }
    }

    private static final class QueuedStructuralRegistration<T>
    {
        private final T value;
        private final String id;
        private final BBSAddonDescriptor descriptor;
        private final String source;

        private QueuedStructuralRegistration(T value, String id, BBSAddonDescriptor descriptor, String source)
        {
            this.value = value;
            this.id = id;
            this.descriptor = descriptor;
            this.source = source;
        }
    }

}
