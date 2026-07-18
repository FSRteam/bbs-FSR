package mchorse.bbs_mod.client;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.compat.ClientApiCompat;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_mod.client.renderer.entity.ActorEntityRenderer;
import mchorse.bbs_mod.client.renderer.entity.GunProjectileEntityRenderer;
import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
import mchorse.bbs_mod.client.rendering.context.BbsWorldRenderContext;
import mchorse.bbs_mod.graphics.window.Window;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
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
        modBus.addListener(BBSClientNeoEvents::onRegisterClientExtensions);
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
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onPlayerClone);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onLoggingOut);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onGameShuttingDown);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onInputKey);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onMouseScroll);
            NeoForge.EVENT_BUS.addListener(BBSClientNeoEvents::onScreenMouseScroll);
        });
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        ClientApiCompat.closeKeyMappingRegistrationWindow();

        try
        {
            BBSModClient.registerKeyMappings(event::register);
        }
        finally
        {
            ClientApiCompat.registerQueuedKeyMappings(event::register);
        }
    }

    private static void onRegisterClientExtensions(RegisterClientExtensionsEvent event)
    {
        event.registerItem(new IClientItemExtensions()
        {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return BBSItemRenderers.getModelBlockCustomRenderer();
            }
        }, BBSMod.MODEL_BLOCK_ITEM.get());

        event.registerItem(new IClientItemExtensions()
        {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return BBSItemRenderers.getGunCustomRenderer();
            }
        }, BBSMod.GUN_ITEM.get());
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        ClientApiCompat.closeRendererRegistrationWindows();

        try
        {
            event.registerEntityRenderer(BBSMod.ACTOR_ENTITY.get(), ActorEntityRenderer::new);
            event.registerEntityRenderer(BBSMod.GUN_PROJECTILE_ENTITY.get(), GunProjectileEntityRenderer::new);
            event.registerBlockEntityRenderer(BBSMod.MODEL_BLOCK_ENTITY.get(), ModelBlockEntityRenderer::new);
        }
        finally
        {
            ClientApiCompat.registerQueuedEntityRenderers(registration -> registerCompatEntityRenderer(event, registration));
            ClientApiCompat.registerQueuedBlockEntityRenderers(registration -> registerCompatBlockEntityRenderer(event, registration));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerCompatEntityRenderer(EntityRenderersEvent.RegisterRenderers event, ClientApiCompat.EntityRendererRegistration<?> registration)
    {
        event.registerEntityRenderer((EntityType) registration.getType(), (EntityRendererProvider) registration.getFactory());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerCompatBlockEntityRenderer(EntityRenderersEvent.RegisterRenderers event, ClientApiCompat.BlockEntityRendererRegistration<?> registration)
    {
        event.registerBlockEntityRenderer((BlockEntityType) registration.getType(), (BlockEntityRendererProvider) registration.getFactory());
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

            BBSModClient.onRenderAfterEntities(createWorldRenderContext(event, stack));
        }
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
        {
            BBSModClient.onRenderAfterLevel();

            if (!ClientApiCompat.hasLastHandlers())
            {
                return;
            }

            PoseStack stack = event.getPoseStack();

            if (stack == null)
            {
                return;
            }

            ClientApiCompat.emitLast(createWorldRenderContext(event, stack));
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
        if (!(event.getLevel() instanceof ClientLevel))
        {
            return;
        }

        BBSModClient.onLevelTickPost();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        BBSModClient.onClientDisconnect();
    }

    private static void onPlayerClone(ClientPlayerNetworkEvent.Clone event)
    {
        BBSModClient.onClientPlayerClone(
            event.getConnection(),
            event.getOldPlayer(),
            event.getNewPlayer()
        );
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

    private static BbsWorldRenderContext createWorldRenderContext(RenderLevelStageEvent event, PoseStack stack)
    {
        Minecraft mc = Minecraft.getInstance();
        PoseStack worldStack = new PoseStack();

        worldStack.setIdentity();
        worldStack.last().pose().set(stack.last().pose());
        worldStack.last().normal().set(stack.last().normal());

        return new BbsWorldRenderContext(
            event.getCamera(),
            worldStack,
            mc.renderBuffers().bufferSource(),
            resolveTickDelta(event.getPartialTick()),
            event.getModelViewMatrix(),
            event.getProjectionMatrix()
        );
    }

    private static float resolveTickDelta(DeltaTracker partialTick)
    {
        return partialTick.getGameTimeDeltaPartialTick(false);
    }
}
