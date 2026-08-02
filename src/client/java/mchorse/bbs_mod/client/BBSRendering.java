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
        Window window = Minecraft.getInstance().getWindow();

        framebuffer = new MainTarget(window.getWidth(), window.getHeight());
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

    public static void onRenderBeforeScreen()
    {
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

    public static boolean isIrisShadowPass()
    {
        if (!iris)
        {
            return false;
        }

        return IrisUtils.isShadowPass();
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
        if (sodium)
        {
            return (b) -> SodiumUtils.createVertexBuffer(b, color);
        }

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
