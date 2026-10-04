package mchorse.bbs_mod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.camera.clips.misc.ImageClip;
import mchorse.bbs_mod.camera.clips.misc.SubtitleClip;
import mchorse.bbs_mod.camera.controller.CameraWorkCameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRuntime;
import mchorse.bbs_mod.client.render.multiview.FilmViewRenderer;
import mchorse.bbs_mod.client.render.multiview.MultiViewManager;
import mchorse.bbs_mod.client.render.multiview.ViewBudgetScheduler;
import mchorse.bbs_mod.client.render.multiview.ViewGpuTiming;
import mchorse.bbs_mod.client.render.multiview.ViewPerformanceMonitor;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.client.render.multiview.RenderPassScope;
import mchorse.bbs_mod.client.render.multiview.RenderGlState;
import mchorse.bbs_mod.client.render.multiview.RenderStateRestorer;
import mchorse.bbs_mod.client.render.multiview.SodiumViewAdapter;
import mchorse.bbs_mod.client.render.multiview.ViewPassContext;
import mchorse.bbs_mod.client.render.multiview.ViewTargetSize;
import mchorse.bbs_mod.client.render.multiview.ViewFramebuffer;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.cubic.model.ModelSetupQueue;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.view.ViewDescriptor;
import net.minecraft.client.DeltaTracker;
import mchorse.bbs_mod.client.render.view.IrisViewBackend;
import mchorse.bbs_mod.utils.iris.IrisViewState;
import mchorse.bbs_mod.client.ui.mirror.BBSUiFrameRecorder;
import mchorse.bbs_mod.events.ModelBlockEntityUpdateCallback;
import mchorse.bbs_mod.forms.renderers.utils.FramebufferDebug;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import mchorse.bbs_mod.forms.structure.StructureWand;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureFormat;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIImageRenderer;
import mchorse.bbs_mod.ui.film.UISubtitleRenderer;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.iris.IrisUtils;
import mchorse.bbs_mod.utils.iris.ShaderCurves;
import mchorse.bbs_mod.client.rendering.context.BbsWorldRenderContext;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.mixin.client.MinecraftAccessor;
import mchorse.bbs_mod.mixin.client.WindowDimensionsAccessor;
import net.minecraft.client.Camera;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.io.File;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class BBSRendering
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cached rendered model blocks
     */
    public static final Set<ModelBlockEntity> capturedModelBlocks = new HashSet<>();

    public static boolean canRender;

    public static boolean renderingWorld;
    public static int lastAction;

    private static boolean customSize;
    private static boolean iris;
    private static boolean sodium;
    private static boolean optifine;

    private static int width;
    private static int height;

    /* Re-armed by the orbit controller on every orthographic frame. */
    private static float orthoDistance = -1F;
    private static boolean sceneSmartCull;
    private static boolean sceneCullingCaptured;
    private static int renderFrameDepth;
    private static boolean releaseViewResourcesPending;

    private static boolean toggleFramebuffer;
    private static RenderTarget framebuffer;
    private static RenderTarget clientFramebuffer;
    private static Texture texture;
    private static Texture primaryStagingTexture;

    private static boolean secondaryViewEnabled;
    private static final MultiViewManager MULTI_VIEW_MANAGER = new MultiViewManager();
    private static final ViewBudgetScheduler VIEW_BUDGET = new ViewBudgetScheduler();
    private static final FilmViewRenderer FILM_VIEW_RENDERER = new FilmViewRenderer();
    private static final ViewGpuTiming FRAME_TIMING = new ViewGpuTiming();
    private static ViewGpuTiming.Spec frameTimingSpec;
    private static boolean frameTimingActive;
    private static Object timingLevel;
    private static UIBaseMenu timingMenu;
    private static long sceneFrameId;
    private static ViewPassContext pendingPrimaryFrame;
    private static IrisViewState.Scope pendingPrimaryIris;
    private static long pendingPrimaryCpuNanos;
    private static boolean primaryFrameCompleted;

    private static volatile long exportFrameGeneration;
    private static final ExportResolutionActionGate EXPORT_RESOLUTION_ACTIONS =
        new ExportResolutionActionGate((stage, failure) ->
        {
            switch (stage)
            {
                case OWNER_VALIDATION -> LOGGER.warn("Failed to validate pending export-resolution owner", failure);
                case ACTION -> LOGGER.error("Pending export-resolution action failed", failure);
                case CLEANUP -> LOGGER.warn("Failed to clean up a cancelled export-resolution action", failure);
            }
        });

    public static int getMotionBlur()
    {
        return getMotionBlur(BBSSettings.videoSettings.frameRate.get(), getMotionBlurFactor());
    }

    public static int getMotionBlur(double fps, int target)
    {
        int i = 0;

        while (fps < target)
        {
            fps *= 2;

            i++;
        }

        return i;
    }

    public static int getMotionBlurFactor()
    {
        return getMotionBlurFactor(BBSSettings.videoSettings.motionBlur.get());
    }

    public static int getMotionBlurFactor(int integer)
    {
        return integer == 0 ? 0 : (int) Math.pow(2, 6 + integer);
    }

    public static int getVideoWidth()
    {
        return width == 0 ? BBSSettings.videoSettings.width.get() : width;
    }

    public static int getVideoHeight()
    {
        return height == 0 ? BBSSettings.videoSettings.height.get() : height;
    }

    public static int getVideoFrameRate()
    {
        int frameRate = BBSSettings.videoSettings.frameRate.get();

        return frameRate * (1 << getMotionBlur(frameRate, getMotionBlurFactor()));
    }

    public static long getExportFrameGeneration()
    {
        return exportFrameGeneration;
    }

    public static boolean isExportFrameReadyAfter(long generation, int expectedWidth, int expectedHeight)
    {
        return exportFrameGeneration > generation
            && texture != null
            && texture.width == expectedWidth
            && texture.height == expectedHeight;
    }

    public static File getVideoFolder()
    {
        File movies = new File(BBSMod.getSettingsFolder().getParentFile(), "movies");
        File exportPath = new File(BBSSettings.videoSettings.path.get());

        if (exportPath.isDirectory())
        {
            movies = exportPath;
        }

        movies.mkdirs();

        return movies;
    }

    public static boolean canReplaceFramebuffer()
    {
        /* Keep the HUD at export resolution while the world export framebuffer is active.
         * Film-editor UI remains at the real window size and uses its own preview target. */
        return customSize && (renderingWorld || (toggleFramebuffer && UIScreen.getCurrentMenu() == null));
    }

    public static boolean isCustomSize()
    {
        return customSize;
    }

    public static boolean isToggleFramebuffer()
    {
        return toggleFramebuffer;
    }

    public static void setCustomSize(boolean customSize)
    {
        setCustomSize(customSize, 0, 0);
    }

    public static void setCustomSize(boolean customSize, int w, int h)
    {
        int newWidth = !customSize ? 0 : w;
        int newHeight = !customSize ? 0 : h;

        /* No-op when nothing actually changes. A redundant setCustomSize(false)
         * — e.g. a film panel disappearing while custom size is already off, which
         * happens when the dashboard is first lazily created by the teleport/record
         * keybinds — must NOT resize the vanilla framebuffers: that stalls the GPU
         * and freezes the screen for a frame even though the state didn't change. */
        if (BBSRendering.customSize == customSize && width == newWidth && height == newHeight)
        {
            return;
        }

        width = newWidth;
        height = newHeight;
        BBSRendering.customSize = customSize;

        if (!customSize)
        {
            if (toggleFramebuffer)
            {
                toggleFramebuffer(false);
            }

            resizeExtraFramebuffers();
        }
    }

    public static Texture getTexture()
    {
        if (texture == null)
        {
            texture = new Texture();
            texture.setFormat(TextureFormat.RGB_U8);
            texture.setFilter(GL11.GL_NEAREST);
        }

        return texture;
    }

    /** Stable scene frame id shared by all camera evaluators and view passes. */
    public static long getSceneFrameId()
    {
        return sceneFrameId;
    }

    public static MultiViewManager getMultiViewManager()
    {
        return MULTI_VIEW_MANAGER;
    }

    public static ViewRenderState getViewRenderState(String id)
    {
        if (id == null || id.isBlank())
        {
            id = MultiViewManager.MAIN_ID;
        }

        ViewRenderState state = MULTI_VIEW_MANAGER.get(id);

        if (state == null && MultiViewManager.MAIN_ID.equals(id))
        {
            state = MULTI_VIEW_MANAGER.register(id, getVideoWidth(), getVideoHeight(), true);
            MULTI_VIEW_MANAGER.attachTarget(id, framebuffer, getTexture());
        }

        return state;
    }

    /** Returns the last successfully published image for a logical viewport. */
    public static Texture getViewTexture(String id)
    {
        ViewRenderState state = getViewRenderState(id);

        return state == null ? null : state.getTexture();
    }

    public static boolean isViewPassActive()
    {
        return ViewPassContext.current() != null;
    }

    public static String getActiveViewId()
    {
        ViewPassContext context = ViewPassContext.current();
        return context == null ? MultiViewManager.MAIN_ID : context.view().getId();
    }

    public static boolean isSecondaryViewEnabled()
    {
        return secondaryViewEnabled;
    }

    public static void setSecondaryViewEnabled(boolean enabled)
    {
        secondaryViewEnabled = enabled;
    }

    public static boolean isApplyingSecondaryCamera()
    {
        ViewPassContext context = ViewPassContext.current();
        return context != null && !context.view().isPrimary();
    }

    public static boolean hasViewCamera()
    {
        ViewPassContext context = ViewPassContext.current();
        return context != null && context.managedCamera();
    }

    public static mchorse.bbs_mod.camera.Camera getViewCamera()
    {
        ViewPassContext context = ViewPassContext.current();
        return context == null ? null : context.camera();
    }

    public static int getActiveTargetWidth()
    {
        ViewPassContext context = ViewPassContext.current();
        return context == null ? 0 : context.width();
    }

    public static int getActiveTargetHeight()
    {
        ViewPassContext context = ViewPassContext.current();
        return context == null ? 0 : context.height();
    }

    public static double getSecondaryCameraX()
    {
        return hasViewCamera() ? getViewCamera().position.x : 0D;
    }

    public static double getSecondaryCameraY()
    {
        return hasViewCamera() ? getViewCamera().position.y : 0D;
    }

    public static double getSecondaryCameraZ()
    {
        return hasViewCamera() ? getViewCamera().position.z : 0D;
    }

    public static float getSecondaryCameraYaw()
    {
        return hasViewCamera() ? (float) Math.toDegrees(getViewCamera().rotation.y - Math.PI) : 0F;
    }

    public static float getSecondaryCameraPitch()
    {
        return hasViewCamera() ? (float) Math.toDegrees(getViewCamera().rotation.x) : 0F;
    }

    public static double getSecondaryCameraFov()
    {
        return hasViewCamera() ? Math.toDegrees(getViewCamera().fov) : Double.NaN;
    }

    public static float getSecondaryRenderAspect()
    {
        return getActiveTargetHeight() == 0 ? 0F : getActiveTargetWidth() / (float) getActiveTargetHeight();
    }

    public static Matrix4f fitSecondaryProjection(Matrix4f projection)
    {
        float aspect = getSecondaryRenderAspect();
        return projection == null || aspect <= 0F ? projection : new Matrix4f(projection).m00(projection.m11() / aspect);
    }

    public static Matrix4f getViewProjection(GameRenderer renderer, Matrix4f original)
    {
        ViewPassContext context = ViewPassContext.current();
        return context == null || !context.managedCamera() ? original : context.projection(renderer.getDepthFar());
    }

    public static void captureViewMatrices(Camera camera, Matrix4f viewMatrix, Matrix4f projection)
    {
        ViewPassContext context = ViewPassContext.current();

        if (context != null && !isIrisShadowPass())
        {
            context.capture(camera, viewMatrix, projection);
        }
    }

    private static int getPhysicalWindowWidth(Minecraft mc)
    {
        Object windowObject = mc.getWindow();

        if (windowObject instanceof WindowDimensionsAccessor accessor)
        {
            return Math.max(1, accessor.bbs$getRawWidth());
        }

        return Math.max(1, mc.getWindow().getWidth());
    }

    private static int getPhysicalWindowHeight(Minecraft mc)
    {
        Object windowObject = mc.getWindow();

        if (windowObject instanceof WindowDimensionsAccessor accessor)
        {
            return Math.max(1, accessor.bbs$getRawHeight());
        }

        return Math.max(1, mc.getWindow().getHeight());
    }

    public static Texture getSecondaryTexture()
    {
        return getViewTexture(ViewDescriptor.SECONDARY_ID);
    }

    public static RenderTarget getSecondaryFramebuffer()
    {
        ViewRenderState view = MULTI_VIEW_MANAGER.get(ViewDescriptor.SECONDARY_ID);
        return view == null ? null : view.getFramebuffer();
    }

    public static void startTick()
    {
        capturedModelBlocks.clear();
    }

    public static void setup()
    {
        iris = LoaderAccessHolder.get().isModLoaded("iris");
        sodium = LoaderAccessHolder.get().isModLoaded("sodium");
        optifine = LoaderAccessHolder.get().isModLoaded("optifabric");

        ModelBlockEntityUpdateCallback.EVENT.register((entity) ->
        {
            if (entity.getLevel().isClientSide())
            {
                capturedModelBlocks.add(entity);
            }
        });

        if (!iris)
        {
            return;
        }

        IrisUtils.setup();
    }

    /* Framebuffers */

    public static RenderTarget getFramebuffer()
    {
        return framebuffer;
    }

    public static void setupFramebuffer()
    {
        if (framebuffer != null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();

        framebuffer = new MainTarget(window.getWidth(), window.getHeight());
        MULTI_VIEW_MANAGER.attachTarget(MultiViewManager.MAIN_ID, framebuffer, getTexture());
        ViewRenderState mainState = getViewRenderState(MultiViewManager.MAIN_ID);
        mainState.setRequestedSize(window.getWidth(), window.getHeight());
        mainState.setActive(true);
        mainState.setVisible(true);
    }

    public static void resizeExtraFramebuffers()
    {
        Set<RenderTarget> buffers = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();

        buffers.add(mc.levelRenderer.entityTarget());
        buffers.add(mc.levelRenderer.getTranslucentTarget());
        buffers.add(mc.levelRenderer.getItemEntityTarget());
        buffers.add(mc.levelRenderer.getParticlesTarget());
        buffers.add(mc.levelRenderer.getWeatherTarget());
        buffers.add(mc.levelRenderer.getCloudsTarget());

        for (RenderTarget buffer : buffers)
        {
            resizeFramebuffer(buffer);
        }

    }

    public static void resizeFramebuffer(RenderTarget framebuffer)
    {
        if (framebuffer == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();

        if (framebuffer.width == w && framebuffer.height == h)
        {
            return;
        }

        framebuffer.resize(w, h, Minecraft.ON_OSX);
    }

    public static void toggleFramebuffer(boolean toggleFramebuffer)
    {
        if (toggleFramebuffer == BBSRendering.toggleFramebuffer)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (toggleFramebuffer)
        {
            setupFramebuffer();
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            RenderTarget previous = mc.getMainRenderTarget();

            try
            {
                resizeExtraFramebuffers();

                if (framebuffer.width != w || framebuffer.height != h)
                {
                    framebuffer.resize(w, h, Minecraft.ON_OSX);
                }

                clientFramebuffer = previous;
                reassignFramebuffer(framebuffer);
                framebuffer.bindWrite(true);
                BBSRendering.toggleFramebuffer = true;
            }
            catch (RuntimeException | Error failure)
            {
                reassignFramebuffer(previous);
                previous.bindWrite(true);
                throw failure;
            }
        }
        else
        {
            restoreClientFramebuffer(true);
        }
    }

    private static void restoreClientFramebuffer(boolean present)
    {
        if (!toggleFramebuffer)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        reassignFramebuffer(clientFramebuffer);
        clientFramebuffer.bindWrite(true);
        toggleFramebuffer = false;
        clientFramebuffer = null;

        /* World recording presents the copied frame to the real window. Film
         * editors compose their own previews; teardown never presents a frame. */
        if (present && customSize && UIScreen.getCurrentMenu() == null)
        {
            Window window = mc.getWindow();
            framebuffer.blitToScreen(window.getWidth(), window.getHeight());
        }
    }

    private static void reassignFramebuffer(RenderTarget framebuffer)
    {
        ((MinecraftAccessor) Minecraft.getInstance()).bbs$setMainRenderTarget(framebuffer);
    }

    /* Rendering */

    public static void onRenderFrameBegin()
    {
        renderFrameDepth++;

        Minecraft mc = Minecraft.getInstance();

        if (renderFrameDepth == 1 && mc.level != null)
        {
            for (ViewRenderState view : MULTI_VIEW_MANAGER.all())
            {
                view.getTiming().poll();
            }

            UIBaseMenu menu = UIScreen.getCurrentMenu();
            boolean changedOwner = timingLevel != mc.level || timingMenu != menu;

            if (changedOwner)
            {
                FRAME_TIMING.close();
                timingLevel = mc.level;
                timingMenu = menu;
            }

            ViewRenderState primary = MULTI_VIEW_MANAGER.get(MultiViewManager.MAIN_ID);
            frameTimingSpec = new ViewGpuTiming.Spec(customSize ? getVideoWidth() : getPhysicalWindowWidth(mc),
                customSize ? getVideoHeight() : getPhysicalWindowHeight(mc), primary == null || primary.isShadersEnabled());
            frameTimingActive = true;
            FRAME_TIMING.begin(frameTimingSpec);

            if (changedOwner)
            {
                FRAME_TIMING.markPreparation();
            }
        }
    }

    public static void onRenderFrameEnd()
    {
        renderFrameDepth--;

        if (renderFrameDepth == 0)
        {
            try
            {
                if (frameTimingActive)
                {
                    FRAME_TIMING.end();
                    ViewPerformanceMonitor.record(FRAME_TIMING.getLastNanos(), FRAME_TIMING.getLastGpuNanos(),
                        MULTI_VIEW_MANAGER.all());
                }
            }
            finally
            {
                frameTimingActive = false;
                restoreSceneCulling();

                if (releaseViewResourcesPending)
                {
                    releaseViewResources();
                }
            }
        }
    }

    public static void onWorldRenderBegin()
    {
        if (!FILM_VIEW_RENDERER.isActive() && !isApplyingSecondaryCamera())
        {
            prepareSceneFrame();
        }

        renderingWorld = true;

        if (!isApplyingSecondaryCamera() && customSize)
        {
            toggleFramebuffer(true);
        }
    }

    private static void prepareSceneFrame()
    {
        /* The budgeted tail of model loading: VAO bakes for whatever the background loader
         * finished, a few milliseconds' worth per frame instead of all of them at once. */
        ModelSetupQueue.drain();
        restoreSceneCulling();
        Minecraft mc = Minecraft.getInstance();
        sceneSmartCull = mc.smartCull;
        sceneCullingCaptured = true;

        if (sodium)
        {
            SodiumViewAdapter.captureFrameCulling();
        }

        orthoDistance = -1F;
        sceneFrameId++;
        primaryFrameCompleted = false;
        pendingPrimaryFrame = null;
        pendingPrimaryIris = null;
        float transition = getTickDelta(mc);

        /* Marks which owned video decoders nobody asked for during this frame - the
         * idle ones can be adopted by a fresh owner of the same file (no black flash). */
        BBSModClient.getVideos().startFrame();

        BBSModClient.getFilms().startRenderFrame(transition);
        UIBaseMenu menu = UIScreen.getCurrentMenu();

        if (menu != null)
        {
            menu.startRenderFrame(transition);
        }
    }

    private static void restoreSceneCulling()
    {
        if (sceneCullingCaptured)
        {
            sceneCullingCaptured = false;
            Minecraft.getInstance().smartCull = sceneSmartCull;

            if (sodium)
            {
                SodiumViewAdapter.restoreFrameCulling();
            }
        }
    }

    /** Whether the main world target currently contains a Replay playback. */
    public static boolean isWorldReplayActive()
    {
        return currentWorldReplayController() != null;
    }

    public static boolean isMorphWorldPreviewActive()
    {
        UIBaseMenu menu = UIScreen.getCurrentMenu();

        return menu instanceof UIDashboard dashboard
            && dashboard.getPanels().panel instanceof UIMorphingPanel panel
            && !panel.palette.editor.isEditing();
    }

    public static void onWorldRenderEnd()
    {
        if (isApplyingSecondaryCamera())
        {
            renderingWorld = false;
            return;
        }

        primaryFrameCompleted = true;
        Minecraft mc = Minecraft.getInstance();
        EnumSet<BBSRenderSurfaceKind> surfaces = EnumSet.noneOf(BBSRenderSurfaceKind.class);
        PlayCameraController playback = currentWorldReplayController();
        UIBaseMenu currentMenu = UIScreen.getCurrentMenu();
        UIFilmPanel filmPanel = null;

        if (customSize)
        {
            if (currentMenu instanceof UIDashboard dashboard && dashboard.getPanels().panel instanceof UIFilmPanel panel)
            {
                filmPanel = panel;
                UIImageRenderer.renderImages(currentMenu.context.batcher.getContext().pose(), currentMenu.context.batcher, ImageClip.getImages(panel.getRunner().getContext()));
                UISubtitleRenderer.renderSubtitles(currentMenu.context.batcher.getContext().pose(), currentMenu.context.batcher, SubtitleClip.getSubtitles(panel.getRunner().getContext()));
                surfaces.add(BBSRenderSurfaceKind.FILM_PREVIEW);
            }
        }

        if (playback != null)
        {
            /* A Film editor target already received its own runner subtitles above. If a
             * playback controller is also present, expose both logical aliases without
             * drawing a second subtitle layer into the shared physical target. */
            if (filmPanel == null)
            {
                GuiGraphics drawContext = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
                Batcher2D batcher = new Batcher2D(drawContext);

                UIImageRenderer.renderImages(batcher.getContext().pose(), batcher, ImageClip.getImages(playback.getContext()));
                UISubtitleRenderer.renderSubtitles(batcher.getContext().pose(), batcher, SubtitleClip.getSubtitles(playback.getContext()));
            }

            surfaces.add(BBSRenderSurfaceKind.WORLD_REPLAY);
        }

        if (isMorphWorldPreviewActive())
        {
            surfaces.add(BBSRenderSurfaceKind.MORPH_WORLD_PREVIEW);
        }

        if (playback != null && currentMenu == null && BBSRenderSurfaceRuntime.hasDemand(surfaces))
        {
            Window window = mc.getWindow();

            /* There is no native BBS UIScreen to carry painter placement in
             * this mode. Publish a bounded placement-only mirror frame before
             * the asynchronous JPEG can reach listeners. */
            BBSUiFrameRecorder.publishStandaloneWorldReplayFrame(
                window.getGuiScaledWidth(),
                window.getGuiScaledHeight(),
                window.getWidth(),
                window.getHeight()
            );
        }
        else
        {
            BBSUiFrameRecorder.closeStandaloneWorldReplaySession();
        }

        /* The active main target is the vanilla world target during normal playback and
         * FSR's private target while the Film editor is visible. Capture after Replay and
         * subtitles, but before HUD/UI composition. A single JPEG payload may therefore
         * satisfy both logical surface kinds without exposing either framebuffer. */
        BBSRenderSurfaceRuntime.capture(mc.getMainRenderTarget(), surfaces);
        renderingWorld = false;
    }

    private static PlayCameraController currentWorldReplayController()
    {
        return BBSModClient.getCameraController().getCurrent() instanceof PlayCameraController controller
            ? controller
            : null;
    }

    /**
     * Outer render entry point used by GameRendererMixin. Scene simulation is
     * prepared once, auxiliary views are sampled under their own budgets, and
     * the supplied primary pass runs last so Sodium's shared terrain state ends
     * on the camera users see in the game window.
     */
    public static void renderWorldFrame(GameRenderer renderer, DeltaTracker deltaTracker, Runnable primaryPass)
    {
        if (primaryPass == null)
        {
            return;
        }

        if (FILM_VIEW_RENDERER.isActive() || isApplyingSecondaryCamera())
        {
            primaryPass.run();
            return;
        }

        /* Once per real frame, and only the frame the window shows: the framebuffer form's
         * diagnostic (see FramebufferDebug) logs one frame per second, counted from here. */
        FramebufferDebug.newFrame();

        PreparedFrame frame = new PreparedFrame();

        try
        {
            FILM_VIEW_RENDERER.renderFrame(() ->
            {
                prepareSceneFrame();
                frame.panel = activeFilmPanel();
                frame.primary = getViewRenderState(MultiViewManager.MAIN_ID);
                Minecraft mc = Minecraft.getInstance();
                int targetWidth = customSize ? getVideoWidth() : getPhysicalWindowWidth(mc);
                int targetHeight = customSize ? getVideoHeight() : getPhysicalWindowHeight(mc);
                frame.primary.setRequestedSize(targetWidth, targetHeight);

                if (frame.panel != null && frame.panel.getData() != null)
                {
                    CameraController controller = BBSModClient.getCameraController();
                    controller.setup(controller.camera, getTickDelta(Minecraft.getInstance()));
                    ViewDescriptor descriptor = frame.panel.getPrimaryView();
                    syncView(frame.primary, descriptor);
                    frame.camera = new mchorse.bbs_mod.camera.Camera();
                    frame.exporting = frame.panel.recorder.isExporting();
                    frame.camera.copy(frame.exporting ? controller.camera : descriptor.resolveCamera(getTickDelta(Minecraft.getInstance())));
                    frame.orthoDistance = frame.exporting ? -1F : descriptor.getOrthoDistance();
                    syncViewCamera(frame.primary, descriptor, frame.exporting);
                }
                else
                {
                    frame.primary.setActive(true);
                    frame.primary.setVisible(true);
                    frame.primary.setShadersEnabled(true);
                    PlayCameraController playback = currentWorldReplayController();

                    if (playback != null && playback.getContext().clips != null
                        && playback.getContext().clips.getParent() instanceof Film film)
                    {
                        frame.primary.syncCameraSource(film, playback.getContext().ticks, Film.LEGACY_CAMERA_ID, true, false);
                    }
                    else
                    {
                        frame.primary.syncCameraSource(null, 0, null, false, false);
                    }
                }

                if (iris && IrisViewBackend.isAvailable())
                {
                    IrisViewBackend.prepareMain();
                }

                ViewGpuTiming timing = frameTimingActive ? FRAME_TIMING : frame.primary.getTiming();
                timing.poll();
                ViewGpuTiming.Spec spec = frameTimingActive ? frameTimingSpec
                    : new ViewGpuTiming.Spec(targetWidth, targetHeight, frame.primary.isShadersEnabled());
                VIEW_BUDGET.setBudgetFraction(frame.panel == null ? ViewBudgetScheduler.DEFAULT_BUDGET_FRACTION
                    : frame.panel.getAuxiliaryBudgetFraction());
                VIEW_BUDGET.beginFrame(timing.getEstimatedNanos(spec), timing.hasGpuSample(spec));
            }, () -> renderAuxiliaryViews(frame, deltaTracker), () -> renderPrimaryView(frame, primaryPass));
        }
        finally
        {
            renderingWorld = false;

            if (sodium)
            {
                SodiumViewAdapter.endFrame();
            }
        }
    }

    private static UIFilmPanel activeFilmPanel()
    {
        UIBaseMenu menu = UIScreen.getCurrentMenu();
        return menu instanceof UIDashboard dashboard && dashboard.getPanels().panel instanceof UIFilmPanel panel ? panel : null;
    }

    private static void syncView(ViewRenderState view, ViewDescriptor descriptor)
    {
        view.setVisible(descriptor.isVisible());
        view.setActive(descriptor.isActive());
        view.setShadersEnabled(descriptor.isShadersEnabled());
        view.setRequestedRefreshRate(descriptor.getRefreshRate());
        view.syncRequest(descriptor.getLastRequestedFrame(), descriptor.getHistoryEpoch());
    }

    private static void renderAuxiliaryViews(PreparedFrame frame, DeltaTracker deltaTracker)
    {
        List<ViewRenderState> candidates = new ArrayList<>();
        Map<String, ViewDescriptor> descriptors = new LinkedHashMap<>();

        if (frame.panel != null)
        {
            for (ViewDescriptor descriptor : frame.panel.getViewDescriptors())
            {
                if (descriptor.isPrimary())
                {
                    continue;
                }

                descriptors.put(descriptor.getId(), descriptor);
                ViewRenderState view = MULTI_VIEW_MANAGER.getOrCreate(descriptor.getId(), descriptor.getWidth(), descriptor.getHeight());
                syncView(view, descriptor);
                view.syncPerformancePreferences(VIEW_BUDGET.getBudgetFraction(), descriptor.getResolutionWidth());
                syncViewCamera(view, descriptor, false);

                if (secondaryViewEnabled && !frame.exporting && view.isVisible() && view.isActive())
                {
                    candidates.add(view);
                }
                else
                {
                    releaseAuxiliaryView(view);
                }
            }
        }

        for (ViewRenderState view : MULTI_VIEW_MANAGER.auxiliary())
        {
            if (!descriptors.containsKey(view.getId()))
            {
                releaseAuxiliaryView(view);
            }
        }

        long now = System.nanoTime();
        candidates.sort(VIEW_BUDGET.priority(now));

        for (ViewRenderState view : candidates)
        {
            if (!VIEW_BUDGET.shouldRender(view, System.nanoTime()))
            {
                continue;
            }

            if (sodium && !SodiumViewAdapter.isMultiViewFrame())
            {
                SodiumViewAdapter.beginFrame();
            }

            renderAuxiliaryView(descriptors.get(view.getId()), view, deltaTracker);
        }
    }

    private static void renderAuxiliaryView(ViewDescriptor descriptor, ViewRenderState view, DeltaTracker deltaTracker)
    {
        Minecraft mc = Minecraft.getInstance();
        long started = System.nanoTime();
        ViewPassContext context = null;
        IrisViewState.Scope irisScope = null;
        ViewTargetSize size = view.targetSize(started);
        mchorse.bbs_mod.camera.Camera resolved = new mchorse.bbs_mod.camera.Camera();
        boolean previousRenderingWorld = renderingWorld;
        boolean measured = false;
        try
        {
            view.getTiming().begin(view.timingSpec(size));
            measured = true;

            if (view.requiresTargetAllocation(size))
            {
                view.getTiming().markPreparation();
            }

            resolved.copy(descriptor.resolveCamera(getTickDelta(mc)));
            syncViewCamera(view, descriptor, false);

            try (RenderPassScope scope = RenderPassScope.capture(mc))
            {
                ViewFramebuffer target = view.prepareTarget(size);
                context = ViewPassContext.open(view, resolved, size.width(), size.height(), descriptor.getOrthoDistance());

                try (ViewPassContext ignored = context;
                     SodiumViewAdapter.CullingScope ignoredCulling = sodium ? SodiumViewAdapter.openCameraCulling(context.orthographic()) : null)
                {
                    Camera camera = view.getWorldCamera();
                    camera.setup(mc.level, mc.getCameraEntity() == null ? mc.player : mc.getCameraEntity(), false, false, getTickDelta(mc));
                    scope.use(target, camera, sceneSmartCull && !context.orthographic());

                    if (sodium)
                    {
                        SodiumViewAdapter.prepareForView();
                    }

                    irisScope = openIrisView(view, false);

                    try (IrisViewState.Scope ignoredIris = irisScope)
                    {
                        target.target().setClearColor(0F, 0F, 0F, 1F);
                        RenderSystem.disableScissor();
                        RenderSystem.colorMask(true, true, true, true);
                        RenderSystem.depthMask(true);
                        target.target().clear(Minecraft.ON_OSX);
                        target.target().bindWrite(true);
                        mc.gameRenderer.renderLevel(deltaTracker);

                        if (!context.captured())
                        {
                            throw new IllegalStateException("The Film view produced no world matrices");
                        }
                    }
                    finally
                    {
                        /* A failed draw must never leave commands for the next camera. */
                        FormTranslucentQueue.abort();
                        FormRenderLast.release();
                    }

                    target.finishForPresentation();
                }
            }

            long elapsed = view.getTiming().end();
            view.publish(sceneFrameId, context.camera().view, context.camera().projection, context.camera(), elapsed);

            if (irisScope != null)
            {
                irisScope.commit();
            }

            if (view.getTiming().wasLastSamplePreparation())
            {
                view.setStatus(ViewRenderState.Status.WARMING_UP);
            }
            else
            {
                VIEW_BUDGET.record(view, elapsed);
            }
        }
        catch (Throwable failure)
        {
            view.fail(failure, System.nanoTime() - started);
            VIEW_BUDGET.recordAt(view, view.getLastCpuNanos(), started);

            if (iris)
            {
                IrisViewBackend.markFailure(view.getId(), failure);
            }

            LOGGER.error("Film view {} failed; retaining its last completed image", view.getId(), failure);

            if (failure instanceof VirtualMachineError fatal)
            {
                throw fatal;
            }

            if (failure instanceof ThreadDeath fatal)
            {
                throw fatal;
            }
        }
        finally
        {
            view.getTiming().end();

            if (measured && frameTimingActive)
            {
                FRAME_TIMING.exclude(view.getTiming().getLastSample());
            }

            renderingWorld = previousRenderingWorld;
        }
    }

    private static IrisViewState.Scope openIrisView(ViewRenderState view, boolean primary)
    {
        if (!iris || !IrisViewBackend.isAvailable())
        {
            return null;
        }

        return primary ? IrisViewBackend.openPrimary(view.getId(), view.isShadersEnabled(), view.getHistoryEpoch())
            : IrisViewBackend.open(view.getId(), view.isShadersEnabled(), view.getHistoryEpoch());
    }

    private static void syncViewCamera(ViewRenderState view, ViewDescriptor descriptor, boolean exporting)
    {
        boolean cameraView = descriptor.getNavigation().isInCameraView();

        view.syncCameraSource(descriptor.getPanel().getData(), descriptor.getPanel().getCursor(),
            cameraView ? descriptor.getCameraId() : null, exporting || cameraView && descriptor.isFollowOutput(),
            !exporting && descriptor.getOrthoDistance() > 0F);
    }

    private static void renderPrimaryView(PreparedFrame frame, Runnable pass)
    {
        Minecraft mc = Minecraft.getInstance();
        int targetWidth = customSize ? getVideoWidth() : getPhysicalWindowWidth(mc);
        int targetHeight = customSize ? getVideoHeight() : getPhysicalWindowHeight(mc);
        ViewGpuTiming timing = frame.primary.getTiming();
        ViewPassContext context = ViewPassContext.open(frame.primary, frame.camera, targetWidth, targetHeight, frame.orthoDistance);
        IrisViewState.Scope irisScope = null;

        try (ViewPassContext ignored = context;
             SodiumViewAdapter.CullingScope ignoredCulling = sodium ? SodiumViewAdapter.openCameraCulling(context.orthographic()) : null)
        {
            mc.smartCull = sceneSmartCull && !context.orthographic();
            if (sodium && SodiumViewAdapter.isMultiViewFrame())
            {
                SodiumViewAdapter.prepareForView();
            }

            if (customSize)
            {
                toggleFramebuffer(true);
            }

            timing.begin(new ViewGpuTiming.Spec(targetWidth, targetHeight, frame.primary.isShadersEnabled()));

            try
            {
                irisScope = openIrisView(frame.primary, true);

                try (IrisViewState.Scope ignoredIris = irisScope)
                {
                    pass.run();
                }
            }
            finally
            {
                pendingPrimaryCpuNanos = timing.end();

                if (timing.wasLastSamplePreparation())
                {
                    FRAME_TIMING.markPreparation();
                }
            }

            if (primaryFrameCompleted && context.captured())
            {
                pendingPrimaryFrame = context;
                pendingPrimaryIris = irisScope;

                if (!toggleFramebuffer)
                {
                    publishPrimaryFrame();
                }
            }
        }
        catch (RuntimeException | Error failure)
        {
            FormTranslucentQueue.abort();
            FormRenderLast.release();
            pendingPrimaryFrame = null;
            pendingPrimaryIris = null;

            if (toggleFramebuffer)
            {
                restoreClientFramebuffer(false);
            }

            throw failure;
        }
    }

    private static void publishPrimaryFrame()
    {
        ViewPassContext context = pendingPrimaryFrame;

        if (context != null)
        {
            context.view().publish(sceneFrameId, context.camera().view, context.camera().projection, context.camera(), pendingPrimaryCpuNanos);
            context.view().setStatus(ViewRenderState.Status.LIVE);

            if (pendingPrimaryIris != null)
            {
                pendingPrimaryIris.commit();
            }
        }

        pendingPrimaryFrame = null;
        pendingPrimaryIris = null;
    }

    private static void releaseAuxiliaryView(ViewRenderState view)
    {
        if (view.getStatus() != ViewRenderState.Status.HIDDEN || view.hasRenderResources())
        {
            try (RenderGlState ignored = new RenderGlState();
                 RenderStateRestorer releases = new RenderStateRestorer())
            {
                releases.add(view::dispose);

                if (iris)
                {
                    releases.add(() -> IrisViewBackend.release(view.getId()));
                }
            }
        }

        view.setStatus(ViewRenderState.Status.HIDDEN);
    }

    /** Retire view resources at a complete render-frame boundary. */
    public static void releaseViewResources()
    {
        if (!RenderSystem.isOnRenderThread())
        {
            RenderSystem.recordRenderCall(BBSRendering::releaseViewResources);
            return;
        }

        if (renderFrameDepth > 0 || FILM_VIEW_RENDERER.isActive() || ViewPassContext.current() != null)
        {
            releaseViewResourcesPending = true;
            return;
        }

        releaseViewResourcesPending = false;
        restoreClientFramebuffer(false);
        restoreSceneCulling();
        RenderTarget retiredFramebuffer = framebuffer;
        Texture retiredTexture = texture;
        Texture retiredStagingTexture = primaryStagingTexture;
        IrisViewState.Scope retiredScope = pendingPrimaryIris;

        try (RenderGlState ignored = new RenderGlState();
             RenderStateRestorer releases = new RenderStateRestorer())
        {
            framebuffer = null;
            texture = null;
            primaryStagingTexture = null;
            pendingPrimaryFrame = null;
            pendingPrimaryIris = null;
            pendingPrimaryCpuNanos = 0L;
            primaryFrameCompleted = false;

            if (retiredTexture != null)
            {
                releases.add(retiredTexture::delete);
            }

            if (retiredStagingTexture != null && retiredStagingTexture != retiredTexture)
            {
                releases.add(retiredStagingTexture::delete);
            }

            if (retiredFramebuffer != null)
            {
                releases.add(retiredFramebuffer::destroyBuffers);
            }

            releases.add(FRAME_TIMING::close);
            frameTimingSpec = null;
            timingLevel = null;
            timingMenu = null;

            for (ViewRenderState view : MULTI_VIEW_MANAGER.all())
            {
                releases.add(view::dispose);
            }

            if (iris)
            {
                releases.add(IrisViewBackend::releaseAll);
            }

            if (retiredScope != null)
            {
                releases.add(retiredScope::close);
            }
        }
    }

    private static void copyRenderTarget(RenderTarget target, Texture destination)
    {
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try
        {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            destination.bind();

            if (destination.width != target.width || destination.height != target.height)
            {
                FRAME_TIMING.markPreparation();
                destination.setSize(target.width, target.height);
            }

            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, target.width, target.height);
        }
        finally
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
        }
    }

    private static final class PreparedFrame
    {
        private UIFilmPanel panel;
        private ViewRenderState primary;
        private mchorse.bbs_mod.camera.Camera camera;
        private float orthoDistance = -1F;
        private boolean exporting;
    }

    public static void onRenderBeforeScreen()
    {
        if (!toggleFramebuffer)
        {
            return;
        }

        try
        {
            if (customSize && framebuffer != null && primaryFrameCompleted)
            {
                int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

                try
                {
                    if (primaryStagingTexture == null)
                    {
                        primaryStagingTexture = new Texture();
                        primaryStagingTexture.setFormat(TextureFormat.RGB_U8);
                        primaryStagingTexture.setFilter(GL11.GL_NEAREST);
                    }

                    copyRenderTarget(framebuffer, primaryStagingTexture);
                    Texture previous = texture;
                    texture = primaryStagingTexture;
                    primaryStagingTexture = previous;
                    MULTI_VIEW_MANAGER.attachTarget(MultiViewManager.MAIN_ID, framebuffer, texture);
                    exportFrameGeneration += 1L;
                    publishPrimaryFrame();
                }
                finally
                {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                }
            }

            renderRecordingOverlay();
        }
        finally
        {
            /* Rebind the client's original target even when preview copying or
             * overlay rendering aborts, otherwise subsequent frames keep
             * drawing into the private export target. */
            toggleFramebuffer(false);
        }

        ExportResolutionActionGate.Action action = EXPORT_RESOLUTION_ACTIONS.queuePending();

        if (action != null)
        {
            try
            {
                Minecraft.getInstance().execute(action::runIfCurrent);
            }
            catch (RuntimeException | Error e)
            {
                synchronized (BBSRendering.class)
                {
                    EXPORT_RESOLUTION_ACTIONS.cancelQueued(action);
                }
                LOGGER.error("Failed to queue pending export-resolution action", e);
            }
        }
    }

    public static synchronized void scheduleAfterNextExportFrame(Runnable action)
    {
        scheduleAfterNextExportFrame(() -> true, action, () -> {});
    }

    public static synchronized void scheduleAfterNextExportFrame(BooleanSupplier ownerValid, Runnable action, Runnable cancelled)
    {
        EXPORT_RESOLUTION_ACTIONS.schedule(ownerValid, action, cancelled);
    }

    /** Fence both the pending slot and a wrapper already queued on Minecraft's executor. */
    public static synchronized void cancelPendingExportResolutionActions()
    {
        EXPORT_RESOLUTION_ACTIONS.cancelAll();
    }

    public static void onRenderChunkLayer(PoseStack stack)
    {
        onRenderChunkLayer(stack, stack.last().pose(), RenderSystem.getProjectionMatrix());
    }

    public static void onRenderChunkLayer(Matrix4f modelViewMatrix, Matrix4f projectionMatrix)
    {
        PoseStack stack = new PoseStack();

        stack.setIdentity();
        onRenderChunkLayer(stack, modelViewMatrix, projectionMatrix);
    }

    public static void onRenderChunkLayer(PoseStack stack, Matrix4f modelViewMatrix, Matrix4f projectionMatrix)
    {
        Minecraft mc = Minecraft.getInstance();

        if (isIrisShadersEnabled())
        {
            renderCoolStuff(new BbsWorldRenderContext(
                mc.gameRenderer.getMainCamera(),
                stack,
                mc.renderBuffers().bufferSource(),
                getTickDelta(mc),
                modelViewMatrix,
                projectionMatrix
            ));
        }
    }

    public static void renderHud(GuiGraphics drawContext, float tickDelta)
    {
        Batcher2D batcher2D = new Batcher2D(drawContext);

        BBSModClient.getFilms().renderHud(batcher2D, tickDelta);
        StructureWand.renderHud(batcher2D);
    }

    /**
     * Draw operator-only recording status after the export texture was copied,
     * so the status is visible on screen but absent from the encoded frame.
     */
    private static void renderRecordingOverlay()
    {
        if (!BBSSettings.recordingOverlays.get() || UIScreen.getCurrentMenu() != null)
        {
            return;
        }

        String label;

        if (BBSModClient.isVideoExportDelayPending())
        {
            int countdown = Math.max(0, (int) Math.ceil(BBSModClient.getVideoExportDelayRemainingMs() / 50D));

            label = String.valueOf(countdown / 20F);
        }
        else if (BBSModClient.getVideoRecorder().isRecording())
        {
            int count = BBSModClient.getVideoRecorder().getCounter();

            label = UIKeys.FILM_VIDEO_RECORDING.format(
                count,
                BBSModClient.getKeyRecordVideo().getTranslatedKeyMessage().getString()
            ).get();
        }
        else
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics drawContext = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        renderRecordingTimerOverlay(new Batcher2D(drawContext), label);
        drawContext.flush();
    }

    public static void renderRecordingTimerOverlay(Batcher2D batcher2D, String label)
    {
        renderRecordingTimerOverlay(batcher2D, label, 5, 5);
    }

    public static void renderRecordingTimerOverlay(Batcher2D batcher2D, String label, int x, int y)
    {
        int iconX = x + 16;

        batcher2D.icon(Icons.SPHERE, Colors.RED | Colors.A100, iconX, y, 1F, 0F);
        batcher2D.textCard(label, iconX + 3, y + 4, BBSSettings.textColor(), Colors.A50);
    }

    /** Whether the entity pass opened the render-last scope — false when one was already open. */
    private static boolean entityPassRenderLast;

    /**
     * The world's entity pass: between these two calls vanilla draws the actors, model blocks
     * and morphed players, and without a shader pack {@link #renderCoolStuff} draws the films
     * at its end — one render-last scope spans it all, so a form set to render last draws after
     * every other form of the frame. Under Iris the films run earlier, at the solid layer, in a
     * scope of their own; this one still covers what the entity loop drew.
     *
     * <p>Opened after the terrain layers rather than before them, because the solid layer is
     * where the Iris film pass draws: a scope already open there would swallow that pass's own
     * scope, and the films' render-last forms would end up drawn after the entities instead of
     * at the end of the film pass.</p>
     */
    public static void beginEntityPass()
    {
        entityPassRenderLast = FormRenderLast.open();
    }

    public static void endEntityPass()
    {
        FormRenderLast.close(entityPassRenderLast);

        entityPassRenderLast = false;
    }

    public static void renderCoolStuff(IBbsWorldRenderContext worldRenderContext)
    {
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldVertexSorting = RenderSystem.getVertexSorting();
        Matrix3f oldInverseView = new Matrix3f(InverseView.get());
        boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        modelViewStack.pushMatrix();

        /* A scope over everything drawn here, for when this runs on its own — under Iris, at the
         * solid layer: a form set to render last skips its turn and draws when this closes, after
         * every other form of the pass. Inside the entity pass's scope this opens nothing and the
         * forms wait for that one, which is what keeps one scope over the whole frame's forms. */
        boolean renderLast = FormRenderLast.open();

        try
        {
            /* Minecraft 1.21.1 removed RenderSystem's inverse-view holder. Keep the
             * active world camera rotation available to VAO shader uniforms. */
            InverseView.set(new Matrix3f().rotation(worldRenderContext.camera().rotation()));

            /* BBS world renderers use camera-relative PoseStacks; the view matrix stays in RenderSystem. */
            RenderSystem.setProjectionMatrix(worldRenderContext.projectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
            modelViewStack.identity();
            modelViewStack.mul(worldRenderContext.modelViewMatrix());
            RenderSystem.applyModelViewMatrix();

            if (Minecraft.getInstance().screen instanceof UIScreen screen)
            {
                screen.renderInWorld(worldRenderContext);
            }

            BBSModClient.getFilms().render(worldRenderContext);
        }
        finally
        {
            /* The postponed forms replay here — before the batch is ended and the camera matrices
             * are put back — because their renderers read the same projection and model-view the
             * forms drawn above did. */
            FormRenderLast.close(renderLast);

            try
            {
                try
                {
                    worldRenderContext.consumers().endBatch();
                }
                finally
                {
                    InverseView.set(oldInverseView);
                    modelViewStack.popMatrix();
                    RenderSystem.applyModelViewMatrix();
                    RenderSystem.setProjectionMatrix(oldProjection, oldVertexSorting);
                }
            }
            finally
            {
                if (oldDepthTest)
                {
                    RenderSystem.enableDepthTest();
                }
                else
                {
                    RenderSystem.disableDepthTest();
                }
            }
        }
    }

    public static boolean isOptifinePresent()
    {
        return optifine;
    }

    public static boolean isRenderingWorld()
    {
        return renderingWorld;
    }

    public static void setOrthoDistance(float distance)
    {
        orthoDistance = distance;

        if (distance > 0F)
        {
            Minecraft.getInstance().smartCull = false;

            if (sodium)
            {
                SodiumViewAdapter.applyCameraCulling(true);
            }
        }
    }

    public static boolean isOrthoActive()
    {
        return orthoDistance > 0F;
    }

    /** Build a size-preserving orthographic projection for the active orbit. */
    public static Matrix4f getOrthoProjection(GameRenderer renderer, Matrix4f perspective, float minHalfHeight)
    {
        if (orthoDistance <= 0F)
        {
            return perspective;
        }

        float tanHalfFov = 1F / perspective.m11();
        float aspect = perspective.m11() / perspective.m00();
        float halfHeight = Math.max(minHalfHeight, orthoDistance * tanHalfFov);
        float halfWidth = halfHeight * aspect;
        float near = -minHalfHeight;
        float far = renderer.getDepthFar();

        return new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, near, far);
    }

    public static boolean isIrisShadersEnabled()
    {
        if (!iris)
        {
            return false;
        }

        ViewPassContext context = ViewPassContext.current();
        return (context == null || context.view().isShadersEnabled()) && IrisUtils.isShaderPackEnabled();
    }

    /** True while forms are rendered inside Iris' shader-pack world pass. */
    public static boolean isIrisWorldForms()
    {
        return isRenderingWorld() && isIrisShadersEnabled();
    }

    /**
     * Whether a shader pack is shading this very draw. Unlike {@link #isIrisShadersEnabled()} it
     * also reports no inside {@link #renderOffscreen(Runnable)}, where our own framebuffer forms
     * draw off-screen and Iris' programs must not take over the vanilla render types we use there.
     */
    public static boolean isIrisWorldShadersEnabled()
    {
        return iris && renderingWorld && isIrisShadersEnabled() && IrisUtils.shouldOverrideShaders();
    }

    /** Render into a framebuffer of ours: see {@link IrisUtils#renderOffscreen(Runnable)}. */
    public static void renderOffscreen(Runnable render)
    {
        if (iris)
        {
            IrisUtils.renderOffscreen(render);
        }
        else
        {
            render.run();
        }
    }

    /** True while forms are rendered inside Iris' shader-pack world pass. */
    public static boolean isIrisWorldForms()
    {
        return isRenderingWorld() && isIrisShadersEnabled();
    }

    public static boolean isIrisShadowPass()
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.isShadowPass();
    }

    /** Begin an Iris-aware vanilla buffer upload; returns the previous layout flag. */
    public static boolean beginIrisBufferUpload(com.mojang.blaze3d.vertex.BufferBuilder builder)
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.beginBufferUpload(builder);
    }

    /** Restore the layout flag returned by {@link #beginIrisBufferUpload}. */
    public static void endIrisBufferUpload(boolean previous)
    {
        if (iris)
        {
            IrisUtils.endBufferUpload(previous);
        }
    }

<<<<<<< HEAD
=======
    /**
     * Snapshot of Iris' extended-vertex-layout flag, taken while a render layer's buffer is still
     * inside its own flush (where Iris pins the flag to match the buffer). The translucent queue
     * draws captured meshes later in the frame, when the flag may describe a different buffer —
     * pinning the captured value during that draw keeps the vertex array layout matched to the
     * data (a mismatch shreds the geometry into a fan of stretched triangles).
     */
>>>>>>> origin/master
    public static boolean captureIrisVertexLayout()
    {
        return iris && IrisUtils.captureBufferLayout();
    }

<<<<<<< HEAD
    public static boolean applyIrisVertexLayout(boolean extended)
    {
        return iris && IrisUtils.applyBufferLayout(extended);
=======
    /**
     * Force the extended-vertex-layout flag for the duration of a deferred draw. Returns the
     * previous value, to be handed to {@link #restoreIrisVertexLayout(boolean)}.
     */
    public static boolean applyIrisVertexLayout(boolean extended)
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.applyBufferLayout(extended);
>>>>>>> origin/master
    }

    public static void restoreIrisVertexLayout(boolean previous)
    {
        if (iris)
        {
            IrisUtils.applyBufferLayout(previous);
        }
    }

    /**
     * Tell Iris when a framebuffer form temporarily renders outside the main
     * world target, preventing Iris from masking its color and depth writes.
     */
    public static void setIrisMainBound(boolean bound)
    {
        if (iris)
        {
            IrisUtils.setMainBound(bound);
        }
    }

    public static void trackTexture(Texture texture)
    {
        if (!iris)
        {
            return;
        }

        IrisUtils.trackTexture(texture);
    }

    public static float[] calculateTangents(float[] t, float[] v, float[] n, float[] u)
    {
        if (!iris)
        {
            return t;
        }

        return IrisUtils.calculateTangents(t, v, n, u);
    }

    public static float[] calculateTangents(float[] v, float[] n, float[] u)
    {
        if (!iris)
        {
            return v;
        }

        return IrisUtils.calculateTangents(v, n, u);
    }

    public static void addUniforms(List<CachedUniform> list, Map<String, ShaderCurves.ShaderVariable> variableMap)
    {
        if (!iris)
        {
            return;
        }

        IrisUtils.addUniforms(list, variableMap);
    }

    public static List<String> getShadersSliderOptions()
    {
        if (!iris)
        {
            return Collections.emptyList();
        }

        return IrisUtils.getSliderProperties();
    }

    public static Map<String, String> getShadersLanguageMap(String language)
    {
        if (!iris)
        {
            return Collections.emptyMap();
        }

        return IrisUtils.getShadersLanguageMap(language);
    }

    /* Curves */

    public static Long getTimeOfDay()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get(ShaderCurves.SUN_ROTATION) : null;

            if (v != null)
            {
                return (long) (v * 1000L);
            }
        }

        return null;
    }

    public static Double getBrightness()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get(ShaderCurves.BRIGHTNESS) : null;

            if (v != null)
            {
                return v;
            }
        }

        return null;
    }

    public static Double getWeather()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Double> values = CurveClip.getValues(controller.getContext());
            Double v = values != null ? values.get(ShaderCurves.WEATHER) : null;

            if (v != null)
            {
                return v;
            }
        }

        return null;
    }

    public static Integer getChromaSkyColorArgb()
    {
        if (!Minecraft.getInstance().isSameThread())
        {
            return null;
        }

        if (BBSModClient.getCameraController().getCurrent() instanceof CameraWorkCameraController controller)
        {
            Map<String, Integer> values = CurveClip.getColorValues(controller.getContext());

            if (values != null)
            {
                return values.get(CurveClip.CHROMA_SKY_COLOR);
            }
        }

        return null;
    }

    public static Function<VertexConsumer, VertexConsumer> getColorConsumer(Color color)
    {
<<<<<<< HEAD
        /* Keep form tint and alpha on the normal VertexConsumer contract. Sodium's bulk writer
         * bypasses setColor(), which can leave model layers with stale RGB/alpha values. */
=======
        /* Sodium's 0.8 vertex writer bypasses the normal consumer color path and
         * its optional mixin is not stable across Connector versions. Keep the
         * vanilla consumer here; this is also the correct path for block/particle
         * texture colors, which must not be replaced by a stale global tint. */
>>>>>>> origin/master
        return (b) -> new RecolorVertexConsumer(b, color);
    }

    private static float getTickDelta(Minecraft mc)
    {
        try
        {
            return mc.getTimer().getGameTimeDeltaPartialTick(false);
        }
        catch (Exception ignored)
        {}

        return 0F;
    }
}
