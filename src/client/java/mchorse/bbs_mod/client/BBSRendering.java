package mchorse.bbs_mod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.camera.clips.misc.SubtitleClip;
import mchorse.bbs_mod.camera.controller.CameraWorkCameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRuntime;
import mchorse.bbs_mod.client.ui.mirror.BBSUiFrameRecorder;
import mchorse.bbs_mod.events.ModelBlockEntityUpdateCallback;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureFormat;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
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
import mchorse.bbs_mod.utils.sodium.SodiumUtils;
import mchorse.bbs_mod.client.rendering.context.BbsWorldRenderContext;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.mixin.client.MinecraftAccessor;
import mchorse.bbs_mod.mixin.client.LevelRendererAccessor;
import mchorse.bbs_mod.mixin.client.EntityRenderDispatcherAccessor;
import mchorse.bbs_mod.mixin.client.GameRendererCameraAccessor;
import mchorse.bbs_mod.mixin.client.WindowDimensionsAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Quaternionf;
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
import java.util.Set;
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

    private static boolean toggleFramebuffer;
    private static RenderTarget framebuffer;
    private static RenderTarget clientFramebuffer;
    private static Texture texture;

    /* === STEP 1 (multiview validation): temporary secondary off-screen pass ===
     * Renders the world a second time per frame into framebuffer2 so a second
     * UIFilmPreview-like consumer can show an independent camera. Main-view path
     * is untouched while secondaryViewEnabled is false (zero regression).
     * Removed once the ViewDescriptor generalization (step 2) lands. */
    private static boolean secondaryViewEnabled;
    private static boolean renderingSecondaryView;
    private static RenderTarget framebuffer2;
    private static Texture texture2;

    /* The secondary pass owns a separate vanilla Camera instance. Its pose is
     * refreshed from the active film camera for the current frame, while all
     * model/entity math observes that camera instead of the main one. */
    private static boolean secondaryFrameRendered;
    private static Camera secondaryCamera;
    private static double secondaryCameraFov = Double.NaN;
    private static int secondaryRenderWidth;
    private static int secondaryRenderHeight;

    /* Secondary-view camera pose, applied by CameraMixin to secondaryCamera
     * while renderingSecondaryView is true. Step 2 will generalize this into
     * per-ViewDescriptor poses. */
    private static double secondaryCameraX;
    private static double secondaryCameraY;
    private static double secondaryCameraZ;
    private static float secondaryCameraYaw;
    private static float secondaryCameraPitch;

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

    public static boolean isSecondaryViewEnabled()
    {
        return secondaryViewEnabled;
    }

    public static void setSecondaryViewEnabled(boolean enabled)
    {
        if (secondaryViewEnabled == enabled)
        {
            return;
        }

        secondaryViewEnabled = enabled;
        secondaryFrameRendered = false;

        if (enabled)
        {
            secondaryCameraFov = Double.NaN;
        }
    }

    /**
     * Whether the CameraMixin should apply the secondary-view camera pose
     * instead of the global controller's pose. True only during the secondary
     * off-screen renderLevel call.
     */
    public static boolean isApplyingSecondaryCamera()
    {
        return renderingSecondaryView;
    }

    public static double getSecondaryCameraX()
    {
        return secondaryCameraX;
    }

    public static double getSecondaryCameraY()
    {
        return secondaryCameraY;
    }

    public static double getSecondaryCameraZ()
    {
        return secondaryCameraZ;
    }

    public static float getSecondaryCameraYaw()
    {
        return secondaryCameraYaw;
    }

    public static float getSecondaryCameraPitch()
    {
        return secondaryCameraPitch;
    }

    public static double getSecondaryCameraFov()
    {
        return secondaryCameraFov;
    }

    public static float getSecondaryRenderAspect()
    {
        return secondaryRenderWidth > 0 && secondaryRenderHeight > 0
            ? secondaryRenderWidth / (float) secondaryRenderHeight
            : 0F;
    }

    /** Keep the secondary projection's horizontal scale aligned with its target. */
    public static Matrix4f fitSecondaryProjection(Matrix4f projection)
    {
        float aspect = getSecondaryRenderAspect();

        if (projection == null || aspect <= 0F || !Float.isFinite(aspect) || projection.m11() == 0F)
        {
            return projection;
        }

        return new Matrix4f(projection).m00(projection.m11() / aspect);
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
        if (texture2 == null)
        {
            texture2 = new Texture();
            texture2.setFormat(TextureFormat.RGB_U8);
            texture2.setFilter(GL11.GL_NEAREST);
        }

        return texture2;
    }

    public static RenderTarget getSecondaryFramebuffer()
    {
        return framebuffer2;
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
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();

        framebuffer = new MainTarget(window.getWidth(), window.getHeight());
        /* preview2 belongs to the live game window, not the export target.
         * Use the raw framebuffer size so a custom export resolution cannot
         * make its first allocation disagree with the physical window. */
        framebuffer2 = new MainTarget(getPhysicalWindowWidth(mc), getPhysicalWindowHeight(mc));
        secondaryCamera = new Camera();
        secondaryCameraFov = Double.NaN;
        secondaryRenderWidth = 0;
        secondaryRenderHeight = 0;
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

        /* preview2 is deliberately resized from the raw physical framebuffer
         * dimensions. resizeFramebuffer() uses Window.getWidth()/getHeight(),
         * which are temporarily redirected to export dimensions by WindowMixin. */
        resizeSecondaryFramebuffer();
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

    private static void resizeSecondaryFramebuffer()
    {
        if (framebuffer2 == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int w = getPhysicalWindowWidth(mc);
        int h = getPhysicalWindowHeight(mc);

        if (framebuffer2.width != w || framebuffer2.height != h)
        {
            framebuffer2.resize(w, h, Minecraft.ON_OSX);
        }
    }

    public static void toggleFramebuffer(boolean toggleFramebuffer)
    {
        if (toggleFramebuffer == BBSRendering.toggleFramebuffer)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        BBSRendering.toggleFramebuffer = toggleFramebuffer;

        if (toggleFramebuffer)
        {
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();

            resizeExtraFramebuffers();

            if (framebuffer.width != w || framebuffer.height != h)
            {
                framebuffer.resize(w, h, Minecraft.ON_OSX);
            }

            clientFramebuffer = mc.getMainRenderTarget();

            reassignFramebuffer(framebuffer);

            framebuffer.bindWrite(true);
        }
        else
        {
            Window window = mc.getWindow();

            reassignFramebuffer(clientFramebuffer);

            mc.getMainRenderTarget().bindWrite(true);

            /* F4/F6 world export renders the live world into our private target.
             * The encoder reads its copied texture, but the player still needs
             * that same current frame presented to the vanilla window target;
             * otherwise the window keeps showing the last pre-recording frame.
             * Film/Morph editors render the private target inside their own UI,
             * so never stretch it over a live BBS screen. */
            if (customSize && UIScreen.getCurrentMenu() == null)
            {
                framebuffer.blitToScreen(window.getWidth(), window.getHeight());
            }
        }
    }

    private static void reassignFramebuffer(RenderTarget framebuffer)
    {
        ((MinecraftAccessor) Minecraft.getInstance()).bbs$setMainRenderTarget(framebuffer);
    }

    /* Rendering */

    public static void onWorldRenderBegin()
    {
        /* The secondary off-screen pass re-enters vanilla's renderLevel, which
         * re-fires this hook. Skip it so the main-view framebuffer swap and the
         * per-frame startRenderFrame bookkeeping are not disturbed. */
        if (renderingSecondaryView)
        {
            return;
        }

        /* The secondary pass is gated to once per frame; reset the gate at the
         * start of the main world render so a fresh frame can render again. */
        secondaryFrameRendered = false;

        if (orthoDistance > 0F)
        {
            Minecraft.getInstance().smartCull = true;

            if (sodium)
            {
                SodiumUtils.restorePointCameraCulling();
            }
        }

        orthoDistance = -1F;

        Minecraft mc = Minecraft.getInstance();
        BBSModClient.getFilms().startRenderFrame(getTickDelta(mc));

        UIBaseMenu menu = UIScreen.getCurrentMenu();

        if (menu != null)
        {
            menu.startRenderFrame(getTickDelta(mc));
        }

        renderingWorld = true;

        /* Sodium owns one shared set of visible terrain lists. Render preview2
         * after the current Film state is updated but before the primary world
         * pass, so the primary camera remains the final writer of that shared
         * state. */
        if (secondaryViewEnabled)
        {
            renderSecondaryView();
        }

        if (!customSize)
        {
            return;
        }

        toggleFramebuffer(true);
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
        /* Secondary off-screen pass re-fires this hook; skip so it doesn't
         * recapture the main surface or disturb the main-view bookkeeping. */
        if (renderingSecondaryView)
        {
            return;
        }

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
     * Render the world a second time this frame into framebuffer2, copying the
     * result into texture2 so an independent preview can display it.
     *
     * <p>Runs before the primary world pass so Sodium's shared terrain state is
     * finalized by the primary camera. The framebuffer copy remains deferred
     * until {@link #onRenderBeforeScreen()}.</p>
     *
     * <p>The re-entrancy guard {@code renderingSecondaryView} keeps the
     * {@code onWorldRenderBegin}/{@code onWorldRenderEnd} mixin hooks from
     * re-toggling the primary framebuffer or re-capturing the main surface while
     * the secondary {@code gameRenderer.renderLevel} call is in flight.</p>
     */
    private static void renderSecondaryView()
    {
        if (!secondaryViewEnabled || framebuffer2 == null)
        {
            return;
        }

        if (renderingSecondaryView)
        {
            return;
        }

        /* Multiple onRenderBeforeScreen call sites (GameRendererMixin and
         * InGameHudMixin) can fire in a single frame. Render the secondary view
         * only once so repeated hooks cannot overwrite its texture or mutate
         * the shared renderer state twice. */
        if (secondaryFrameRendered)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null)
        {
            return;
        }

        RenderTarget originalTarget = mc.getMainRenderTarget();
        Camera mainCamera = mc.gameRenderer.getMainCamera();
        Object gameRendererObject = mc.gameRenderer;

        if (!(gameRendererObject instanceof GameRendererCameraAccessor))
        {
            secondaryViewEnabled = false;
            LOGGER.error("Disabling secondary preview because GameRenderer camera accessor is unavailable");

            return;
        }

        GameRendererCameraAccessor gameRenderer = (GameRendererCameraAccessor) gameRendererObject;
        Camera originalGameRendererCamera = gameRenderer.bbs$getMainCamera();

        if (mainCamera == null || originalGameRendererCamera == null)
        {
            secondaryViewEnabled = false;
            LOGGER.error("Disabling secondary preview because the main camera is unavailable");

            return;
        }

        if (secondaryCamera == null)
        {
            secondaryCamera = new Camera();
        }

        /* Keep the secondary view live with the active film camera mode. The
         * pose is copied every frame into a separate Camera instance, so the
         * secondary renderer gets the current orbit/free/first/third-person
         * pose without sharing the main camera's matrices or mutable object. */
        secondaryCameraX = mainCamera.getPosition().x;
        secondaryCameraY = mainCamera.getPosition().y;
        secondaryCameraZ = mainCamera.getPosition().z;
        /* preview2 is a validation mirror until a camera-unit pose is bound to
         * it. Reuse the primary yaw exactly; adding an artificial angle here
         * makes every mouse-look update appear as a second movement layer in
         * the model/replay transforms. */
        secondaryCameraYaw = mainCamera.getYRot();
        secondaryCameraPitch = mainCamera.getXRot();
        secondaryCameraFov = BBSModClient.getCameraController().getFOV();

        /* gameRenderer.renderLevel mutates global GL state that the 2D GUI pass
         * (already set up by the time onRenderBeforeScreen runs) depends on:
         * the projection matrix, vertex sorting and the model-view stack. Save
         * them here so the secondary 3D pass cannot leak into the editor HUD. */
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedVertexSorting = RenderSystem.getVertexSorting();
        Matrix4fStack savedModelView = RenderSystem.getModelViewStack();

        savedModelView.pushMatrix();
        /* onRenderBeforeScreen runs after vanilla has installed the GUI
         * translation (-11000) and an aspect-dependent orthographic matrix.
         * A world pass must start from an identity model-view; otherwise the
         * GUI scale becomes an extra transform on every entity. */
        savedModelView.identity();
        RenderSystem.applyModelViewMatrix();

        /* LevelRenderer cull bookkeeping snapshots — declared before the try so
         * the finally can restore them even if renderLevel throws. */
        Object levelRendererObject = mc.levelRenderer;

        if (!(levelRendererObject instanceof LevelRendererAccessor))
        {
            secondaryViewEnabled = false;
            LOGGER.error("Disabling secondary preview because LevelRenderer state accessor is unavailable");

            return;
        }

        LevelRendererAccessor lr = (LevelRendererAccessor) levelRendererObject;
        double savePrevCamX = lr.bbs$getPrevCamX();
        double savePrevCamY = lr.bbs$getPrevCamY();
        double savePrevCamZ = lr.bbs$getPrevCamZ();
        double savePrevCamRotX = lr.bbs$getPrevCamRotX();
        double savePrevCamRotY = lr.bbs$getPrevCamRotY();
        int saveLastSecX = lr.bbs$getLastCameraSectionX();
        int saveLastSecY = lr.bbs$getLastCameraSectionY();
        int saveLastSecZ = lr.bbs$getLastCameraSectionZ();

        /* EntityRenderDispatcher holds the shared camera-relative state the
         * secondary pass overwrites via prepare(). Entities render at the wrong
         * position if a later read picks up the secondary camera. */
        Object entityDispatcherObject = mc.getEntityRenderDispatcher();

        if (!(entityDispatcherObject instanceof EntityRenderDispatcherAccessor))
        {
            secondaryViewEnabled = false;
            LOGGER.error("Disabling secondary preview because entity camera state accessor is unavailable");

            return;
        }

        EntityRenderDispatcherAccessor erd = (EntityRenderDispatcherAccessor) entityDispatcherObject;
        Camera saveEntityCam = erd.bbs$getCamera();
        Quaternionf saveEntityCamRot = erd.bbs$getCameraOrientation() != null
            ? new Quaternionf(erd.bbs$getCameraOrientation())
            : null;
        boolean savedSmartCull = mc.smartCull;

        try
        {
            resizeSecondaryFramebuffer();

            secondaryRenderWidth = Math.max(1, framebuffer2.viewWidth);
            secondaryRenderHeight = Math.max(1, framebuffer2.viewHeight);

            /* Swap the field so renderLevel's internal bindWrite() calls land on
             * our off-screen target, not the window. */
            reassignFramebuffer(framebuffer2);
            framebuffer2.bindWrite(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            secondaryFrameRendered = true;

            /* GameRenderer.renderLevel(DeltaTracker) normally uses its private
             * mainCamera field. Swap in the independent camera so every vanilla
             * and BBS renderer receives the secondary camera as context.camera. */
            gameRenderer.bbs$setMainCamera(secondaryCamera);
            renderingSecondaryView = true;

            /* Bind the secondary camera to the current world/entity before any
             * fog, Sodium, or entity renderer hook can inspect it. The old
             * addon path skipped setup on a fresh Camera and crashed in
             * ClientHooks.onFogRender because camera.getEntity() was null. */
            secondaryCamera.setup(
                mc.level,
                mc.player,
                false,
                false,
                getTickDelta(mc)
            );

            mc.smartCull = false;

            /* gameRenderer.renderLevel(DeltaTracker) is the Sodium-safe entry
             * point (vs. the 7-arg LevelRenderer.renderLevel). It re-fires the
             * BBS mixin hooks, but the guard above makes them no-ops. */
            mc.gameRenderer.renderLevel(mc.getTimer());
        }
        catch (Throwable failure)
        {
            /* A secondary pass is an optional preview. Never let camera setup,
             * fog, renderer, or shader incompatibility terminate the client. */
            secondaryViewEnabled = false;
            LOGGER.error("Disabling secondary preview after an off-screen render failure", failure);
        }
        finally
        {
            renderingSecondaryView = false;
            mc.smartCull = savedSmartCull;

            /* Restore the host's camera object before any following render hook
             * can observe the temporary view. The main Camera itself was never
             * mutated by the secondary pass. */
            gameRenderer.bbs$setMainCamera(originalGameRendererCamera);

            /* Restore the client's original target before any 2D overlay draws. */
            reassignFramebuffer(originalTarget);
            originalTarget.bindWrite(true);

            /* Restore the GUI projection/model-view the 2D pass set up before us,
             * otherwise the editor HUD renders with the secondary view's 3D
             * projection and the screen goes black. */
            savedModelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedVertexSorting);

            /* Restore LevelRenderer's cull bookkeeping so the secondary pass's
             * secondary-camera pose does not leak into the next main frame. */
            lr.bbs$setPrevCamX(savePrevCamX);
            lr.bbs$setPrevCamY(savePrevCamY);
            lr.bbs$setPrevCamZ(savePrevCamZ);
            lr.bbs$setPrevCamRotX(savePrevCamRotX);
            lr.bbs$setPrevCamRotY(savePrevCamRotY);
            lr.bbs$setLastCameraSectionX(saveLastSecX);
            lr.bbs$setLastCameraSectionY(saveLastSecY);
            lr.bbs$setLastCameraSectionZ(saveLastSecZ);

            /* Restore EntityRenderDispatcher's camera-relative state so entities
             * in the main view render relative to the main camera, not the
             * secondary +45 yaw one. */
            erd.bbs$setCamera(saveEntityCam);

            if (saveEntityCamRot != null)
            {
                erd.bbs$setCameraOrientation(saveEntityCamRot);
            }

            secondaryRenderWidth = 0;
            secondaryRenderHeight = 0;
        }

        /* Copy framebuffer2 into texture2 (mirrors the main-view glCopyTexSubImage2D path). */
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        try
        {
            Texture secondary = getSecondaryTexture();

            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer2.frameBufferId);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            secondary.bind();

            if (secondary.width != framebuffer2.width || secondary.height != framebuffer2.height)
            {
                secondary.setSize(framebuffer2.width, framebuffer2.height);
            }

            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, framebuffer2.width, framebuffer2.height);
        }
        finally
        {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
        }
    }

    public static void onRenderBeforeScreen()
    {
        /* The secondary off-screen pass is independent of the export pipeline so
         * the Shift+M debug toggle is visible in normal gameplay, not only while
         * exporting with a hidden GUI. The export path below remains gated by
         * toggleFramebuffer/customSize, so this adds no regression there. */
        if (secondaryViewEnabled)
        {
            renderSecondaryView();
        }

        if (!toggleFramebuffer)
        {
            return;
        }

        try
        {
            if (customSize && framebuffer != null)
            {
                int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
                int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

                try
                {
                    Texture texture = getTexture();

                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer.frameBufferId);
                    GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

                    texture.bind();

                    /* Keep the preview texture allocation stable across frames. Besides avoiding a
                     * needless glTexImage2D stall, this is important for remote surface capture:
                     * consumers can reuse their GPU/PBO resources until the preview size changes. */
                    if (texture.width != framebuffer.width || texture.height != framebuffer.height)
                    {
                        texture.setSize(framebuffer.width, framebuffer.height);
                    }

                    GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, framebuffer.width, framebuffer.height);
                    exportFrameGeneration += 1L;
                }
                finally
                {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
                    GL11.glReadBuffer(previousReadBuffer);
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

    public static void renderCoolStuff(IBbsWorldRenderContext worldRenderContext)
    {
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldVertexSorting = RenderSystem.getVertexSorting();
        Matrix3f oldInverseView = new Matrix3f(InverseView.get());
        boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        modelViewStack.pushMatrix();

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
                SodiumUtils.disablePointCameraCulling();
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

        return IrisUtils.isShaderPackEnabled();
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

    public static boolean captureIrisVertexLayout()
    {
        return iris && IrisUtils.captureBufferLayout();
    }

    public static boolean applyIrisVertexLayout(boolean extended)
    {
        return iris && IrisUtils.applyBufferLayout(extended);
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
        /* Keep form tint and alpha on the normal VertexConsumer contract. Sodium's bulk writer
         * bypasses setColor(), which can leave model layers with stale RGB/alpha values. */
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
