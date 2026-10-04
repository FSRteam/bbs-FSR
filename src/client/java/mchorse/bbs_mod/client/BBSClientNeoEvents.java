package mchorse.bbs_mod.client;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.compat.ClientApiCompat;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_mod.client.renderer.ModelBlockRenderLastQueue;
import mchorse.bbs_mod.client.renderer.entity.ActorEntityRenderer;
import mchorse.bbs_mod.client.renderer.entity.GunProjectileEntityRenderer;
import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
import mchorse.bbs_mod.client.rendering.context.BbsWorldRenderContext;
import mchorse.bbs_mod.film.FilmFrustumCulling;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.structure.StructureWand;
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
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS)
        {
            /* The last stage before the entity loop, and after the solid layer where the Iris
             * film pass draws. Opening the render-last scope here is the FSR equivalent of the
             * upstream BEFORE_ENTITIES hook: one scope then spans the whole entity pass — actors,
             * morphed players, model blocks — and the films, so a form set to render last draws
             * after every other form of the frame, not only the film ones. */
            BBSRendering.beginEntityPass();
        }
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
        {
            try
            {
                PoseStack stack = event.getPoseStack();

                if (stack == null)
                {
                    return;
                }

                /* The block-entity pass runs right after this stage — open the render-last
                 * scope so render-last model blocks defer into the queue instead of drawing. */
                ModelBlockRenderLastQueue.begin();

                BBSModClient.onRenderAfterEntities(createWorldRenderContext(event, stack));
            }
            finally
            {
                /* Closed here, right after the films and before the chroma sky quad, matching
                 * upstream's ordering. In a finally because the scope must never outlive its
                 * pass: a return or a throw above would otherwise leave it open, and an open
                 * scope owns every render-last form of the next frame — forms drawn into the
                 * wrong pass, which is worse than render-last not working at all. */
                BBSRendering.endEntityPass();
            }
        }
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)
        {
            /* Replay deferred render-last model blocks after every block entity has drawn,
             * before the translucent terrain boundary — matching Blockbuster's renderLastEntities.
             * Each entry replays through the pose stack captured at enqueue time, so the
             * event's own camera-relative stack is not used here. */
            ModelBlockRenderLastQueue.flush(Minecraft.getInstance().renderBuffers().bufferSource());
        }
        else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
        {
            /* Safety net: a frame that never reached AFTER_BLOCK_ENTITIES (a mod redirecting
             * the stage, an exception mid-render) must not leak entries into the next frame. */
            ModelBlockRenderLastQueue.release();

            /* The same net for the entity pass's render-last scope: a frame whose render threw
             * before AFTER_ENTITIES leaves a scope that no later close can end, because a close
             * only ends the scope its own open() opened. */
            FormRenderLast.release();

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

        /* The structure wand spends the notch on reshaping its box: the hotbar must not get it */
        if (StructureWand.onScroll(event.getScrollDeltaY()))
        {
            event.setCanceled(true);
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

        /* Diagnostic only (off by default). Placed on the factory rather than on any one user of
         * the context, so it measures the frustum and camera every pass hands out, with or without
         * a film in the scene. */
        FilmFrustumCulling.probePass(event.getFrustum(), event.getCamera());

        return new BbsWorldRenderContext(
            event.getCamera(),
            worldStack,
            mc.renderBuffers().bufferSource(),
            resolveTickDelta(event.getPartialTick()),
            event.getModelViewMatrix(),
            event.getProjectionMatrix(),
            /* The frustum of this very pass: each viewport runs its own world render, and that
             * render rebuilds it from its own camera and projection. */
            event.getFrustum()
        );
    }

    private static float resolveTickDelta(DeltaTracker partialTick)
    {
        return partialTick.getGameTimeDeltaPartialTick(false);
    }
}
