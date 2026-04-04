package mchorse.bbs_mod.client.compat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.impl.client.rendering.BlockEntityRendererRegistryImpl;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.entity.Entity;

import java.util.function.Consumer;

/**
 * Transitional client compatibility facade.
 *
 * Current implementation delegates to Fabric APIs; M07-M10 will swap internals to NeoForge events.
 */
public final class ClientApiCompat
{
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
        void render(net.minecraft.client.gui.DrawContext drawContext, float tickDelta);
    }

    public static KeyBinding registerKeyBinding(KeyBinding keyBinding)
    {
        return KeyBindingHelper.registerKeyBinding(keyBinding);
    }

    public static void onAfterEntities(WorldRenderHandler handler)
    {
        WorldRenderEvents.AFTER_ENTITIES.register((context) ->
        {
            handler.render(FabricWorldRenderContextAdapter.wrap(context));
        });
    }

    public static void onLast(WorldRenderHandler handler)
    {
        WorldRenderEvents.LAST.register((context) ->
        {
            handler.render(FabricWorldRenderContextAdapter.wrap(context));
        });
    }

    public static void onDisconnect(DisconnectHandler handler)
    {
        ClientPlayConnectionEvents.DISCONNECT.register((networkHandler, client) -> handler.onDisconnect(client));
    }

    public static void onStartClientTick(Consumer<MinecraftClient> handler)
    {
        ClientTickEvents.START_CLIENT_TICK.register(handler::accept);
    }

    public static void onEndWorldTick(Consumer<MinecraftClient> handler)
    {
        ClientTickEvents.END_WORLD_TICK.register(handler::accept);
    }

    public static void onEndClientTick(Consumer<MinecraftClient> handler)
    {
        ClientTickEvents.END_CLIENT_TICK.register(handler::accept);
    }

    public static void onHudRender(HudRenderHandler handler)
    {
        HudRenderCallback.EVENT.register(handler::render);
    }

    public static void onClientStopping(Consumer<MinecraftClient> handler)
    {
        ClientLifecycleEvents.CLIENT_STOPPING.register(handler::accept);
    }

    public static void onClientStarted(Consumer<MinecraftClient> handler)
    {
        ClientLifecycleEvents.CLIENT_STARTED.register(handler::accept);
    }

    public static <T extends Entity> void registerEntityRenderer(net.minecraft.entity.EntityType<T> type, EntityRendererFactory<T> factory)
    {
        EntityRendererRegistry.register(type, factory);
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, BlockEntityRendererFactory<? super E> factory)
    {
        BlockEntityRendererRegistryImpl.register(type, factory);
    }

}
