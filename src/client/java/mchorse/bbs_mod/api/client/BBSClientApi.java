package mchorse.bbs_mod.api.client;

import mchorse.bbs_mod.client.compat.ClientApiCompat;
import mchorse.bbs_mod.client.compat.BBSWorldRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Consumer;

/**
 * Client-only Addon/API 2.0 facade.
 */
public final class BBSClientApi
{
    private BBSClientApi() {}

    public static KeyMapping registerKeyBinding(KeyMapping keyBinding)
    {
        return ClientApiCompat.registerKeyBinding(keyBinding);
    }

    public static void onAfterEntities(WorldRenderHandler handler)
    {
        ClientApiCompat.onAfterEntities(handler::render);
    }

    public static void onAfterLevel(WorldRenderHandler handler)
    {
        ClientApiCompat.onLast(handler::render);
    }

    public static void onHudRender(HudRenderHandler handler)
    {
        ClientApiCompat.onHudRender(handler::render);
    }

    public static void onClientStarted(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onClientStarted(handler);
    }

    public static void onClientStopping(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onClientStopping(handler);
    }

    public static void onDisconnect(ClientApiCompat.DisconnectHandler handler)
    {
        ClientApiCompat.onDisconnect(handler);
    }

    public static void onStartClientTick(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onStartClientTick(handler);
    }

    public static void onEndWorldTick(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onEndWorldTick(handler);
    }

    public static void onEndClientTick(Consumer<Minecraft> handler)
    {
        ClientApiCompat.onEndClientTick(handler);
    }

    public static <T extends Entity> void registerEntityRenderer(EntityType<T> type, EntityRendererProvider<T> factory)
    {
        ClientApiCompat.registerEntityRenderer(type, factory);
    }

    public static <E extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<E> type, BlockEntityRendererProvider<? super E> factory)
    {
        ClientApiCompat.registerBlockEntityRenderer(type, factory);
    }

    @FunctionalInterface
    public interface HudRenderHandler
    {
        void render(GuiGraphics graphics, float tickDelta);
    }

    @FunctionalInterface
    public interface WorldRenderHandler
    {
        void render(BBSWorldRenderContext context);
    }
}
