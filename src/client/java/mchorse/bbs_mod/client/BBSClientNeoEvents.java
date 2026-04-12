package mchorse.bbs_mod.client;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_mod.client.renderer.entity.ActorEntityRenderer;
import mchorse.bbs_mod.client.renderer.entity.GunProjectileEntityRenderer;
import mchorse.bbs_mod.client.rendering.context.BbsWorldRenderContext;
import mchorse.bbs_mod.graphics.window.Window;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * NeoForge client event bridge (M07-M10).
 */
public final class BBSClientNeoEvents
{
    private static boolean initialized;
    private static boolean startedOnce;

    private BBSClientNeoEvents()
    {}

    public static void register(IEventBus modBus)
    {
        modBus.addListener(BBSClientNeoEvents::onClientSetup);
        modBus.addListener(BBSClientNeoEvents::onRegisterKeyMappings);
        modBus.addListener(BBSClientNeoEvents::onRegisterRenderers);
    }

    private static void onClientSetup(FMLClientSetupEvent event)
    {
        if (initialized)
        {
            return;
        }

        initialized = true;

        event.enqueueWork(() ->
        {
            new BBSModClient().onInitializeClient();

            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onRenderGuiPost);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onClientTickPre);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onClientTickPost);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onLevelTickPost);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onLoggingOut);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onGameShuttingDown);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onInputKey);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onMouseScroll);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onScreenMouseScroll);
        });
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        BBSModClient.registerKeyMappings(event::register);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(BBSMod.ACTOR_ENTITY, ActorEntityRenderer::new);
        event.registerEntityRenderer(BBSMod.GUN_PROJECTILE_ENTITY, GunProjectileEntityRenderer::new);
        event.registerBlockEntityRenderer(BBSMod.MODEL_BLOCK_ENTITY, ModelBlockEntityRenderer::new);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event)
    {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
        {
            PoseStack stack = event.getPoseStack();

            if (stack == null)
            {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            BbsWorldRenderContext context = new BbsWorldRenderContext(
                event.getCamera(),
                stack,
                mc.renderBuffers().bufferSource(),
                resolveTickDelta(event.getPartialTick())
            );

            BBSModClient.onRenderAfterEntities(context);
        }
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
        {
            BBSModClient.onRenderAfterLevel();
        }
    }

    private static void onRenderGuiPost(RenderGuiEvent.Post event)
    {
        BBSModClient.onRenderGuiPost(event.getGuiGraphics(), resolveTickDelta(event.getPartialTick()));
    }

    private static void onClientTickPre(ClientTickEvent.Pre event)
    {
        if (!startedOnce)
        {
            startedOnce = true;
            BBSModClient.onClientStarted();
        }

        BBSModClient.onClientTickPre();
    }

    private static void onClientTickPost(ClientTickEvent.Post event)
    {
        BBSModClient.onClientTickPost();
    }

    private static void onLevelTickPost(LevelTickEvent.Post event)
    {
        if (!isClientLevel(event.getLevel()))
        {
            return;
        }

        BBSModClient.onLevelTickPost();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        BBSModClient.onClientDisconnect();
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event)
    {
        if (FMLEnvironment.dist != Dist.CLIENT)
        {
            return;
        }

        BBSModClient.onClientStopping();
    }

    private static void onInputKey(InputEvent.Key event)
    {
        BBSRendering.lastAction = event.getAction();

        BBSModClient.onEndKey(
            Minecraft.getInstance().getWindow().getWindow(),
            event.getKey(),
            event.getScanCode(),
            event.getAction(),
            event.getModifiers()
        );
    }

    private static void onMouseScroll(InputEvent.MouseScrollingEvent event)
    {
        int scrollY = (int) event.getScrollDeltaY();

        if (scrollY != 0)
        {
            Window.setVerticalScroll(scrollY);
        }
    }

    private static void onScreenMouseScroll(ScreenEvent.MouseScrolled.Pre event)
    {
        int scrollY = (int) event.getScrollDeltaY();

        if (scrollY != 0)
        {
            Window.setVerticalScroll(scrollY);
        }
    }

    private static float resolveTickDelta(Object partialTick)
    {
        if (partialTick instanceof Number number)
        {
            return number.floatValue();
        }

        try
        {
            Object value = partialTick.getClass()
                .getMethod("getGameTimeDeltaPartialTick", boolean.class)
                .invoke(partialTick, false);

            if (value instanceof Number number)
            {
                return number.floatValue();
            }
        }
        catch (Exception ignored)
        {}

        try
        {
            return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        }
        catch (Exception ignored)
        {}

        return 0F;
    }

    private static boolean isClientLevel(Object level)
    {
        try
        {
            Object value = level.getClass().getMethod("isClientSide").invoke(level);

            if (value instanceof Boolean bool)
            {
                return bool;
            }
        }
        catch (Exception ignored)
        {}

        try
        {
            return level.getClass().getField("isClient").getBoolean(level);
        }
        catch (Exception ignored)
        {}

        return false;
    }
}
