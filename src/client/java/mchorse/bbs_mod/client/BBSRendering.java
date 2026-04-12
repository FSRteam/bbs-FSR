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
import mchorse.bbs_mod.events.ModelBlockEntityUpdateCallback;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureFormat;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UISubtitleRenderer;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.VideoRecorder;
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
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static boolean toggleFramebuffer;
    private static RenderTarget framebuffer;
    private static RenderTarget clientFramebuffer;
    private static Texture texture;

    private static Runnable pendingExportResolutionAction;

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
        return customSize && renderingWorld;
    }

    public static boolean isCustomSize()
    {
        return customSize;
    }

    public static void setCustomSize(boolean customSize)
    {
        setCustomSize(customSize, 0, 0);
    }

    public static void setCustomSize(boolean customSize, int w, int h)
    {
        LOGGER.info("[BBS film] setCustomSize customSize={} w={} h={} (stored width/height will be {})",
            customSize, w, h, customSize ? w + "/" + h : "0/0");
        BBSRendering.customSize = customSize;

        width = !customSize ? 0 : w;
        height = !customSize ? 0 : h;

        if (!customSize)
        {
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
            if (entity.level().isClient())
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
        Window window = mc.getWindow();

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
            int drawW = window.getWidth();
            int drawH = window.getHeight();
            reassignFramebuffer(clientFramebuffer);

            mc.getMainRenderTarget().bindWrite(true);

            if (width != 0)
            {
                /* When the film panel is open, the UI draws the preview texture in its block; do not
                 * blit our framebuffer to the full window or the preview would stretch to full screen. */
                UIBaseMenu currentMenu = UIScreen.getCurrentMenu();
                boolean filmPanelShowing = currentMenu instanceof UIDashboard dashboard
                    && dashboard.getPanels().panel instanceof UIFilmPanel;
                if (!filmPanelShowing)
                {
                    framebuffer.blitToScreen(drawW, drawH);
                }
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

    public static void onWorldRenderEnd()
    {
        Minecraft mc = Minecraft.getInstance();

        if (BBSModClient.getCameraController().getCurrent() instanceof PlayCameraController controller)
        {
            GuiGraphics drawContext = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            Batcher2D batcher = new Batcher2D(drawContext);

            UISubtitleRenderer.renderSubtitles(batcher.getContext().pose(), batcher, SubtitleClip.getSubtitles(controller.getContext()));
        }

        if (!customSize)
        {
            renderingWorld = false;

            return;
        }

        UIBaseMenu currentMenu = UIScreen.getCurrentMenu();

        if (currentMenu instanceof UIDashboard dashboard)
        {
            if (dashboard.getPanels().panel instanceof UIFilmPanel panel)
            {
                UISubtitleRenderer.renderSubtitles(currentMenu.context.batcher.getContext().pose(), currentMenu.context.batcher, SubtitleClip.getSubtitles(panel.getRunner().getContext()));
            }
        }

        renderingWorld = false;
    }

    public static void onRenderBeforeScreen()
    {
        Texture texture = getTexture();

        texture.bind();
        texture.setSize(framebuffer.width, framebuffer.height);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, framebuffer.width, framebuffer.height);
        texture.unbind();

        toggleFramebuffer(false);

        if (pendingExportResolutionAction != null)
        {
            Runnable action = pendingExportResolutionAction;
            pendingExportResolutionAction = null;
            Minecraft.getInstance().execute(action);
        }
    }

    public static void scheduleAfterNextExportFrame(Runnable action)
    {
        pendingExportResolutionAction = action;
    }

    public static void onRenderChunkLayer(PoseStack stack)
    {
        Minecraft mc = Minecraft.getInstance();

        if (isIrisShadersEnabled())
        {
            renderCoolStuff(new BbsWorldRenderContext(
                mc.gameRenderer.getMainCamera(),
                stack,
                mc.renderBuffers().bufferSource(),
                getTickDelta(mc)
            ));
        }
    }

    public static void renderHud(GuiGraphics drawContext, float tickDelta)
    {
        Batcher2D batcher2D = new Batcher2D(drawContext);
        VideoRecorder videoRecorder = BBSModClient.getVideoRecorder();

        BBSModClient.getFilms().renderHud(batcher2D, tickDelta);

        if (videoRecorder.isRecording() && BBSSettings.recordingOverlays.get() && UIScreen.getCurrentMenu() == null)
        {
            int count = videoRecorder.getCounter();
            String label = UIKeys.FILM_VIDEO_RECORDING.format(
                count,
                BBSModClient.getKeyRecordVideo().getTranslatedKeyMessage().getString()
            ).get();

            int x = 5;
            int y = 5;
            int w = batcher2D.getFont().getWidth(label);

            batcher2D.box(x, y, x + 18 + w + 3, y + 16, Colors.A50);
            batcher2D.icon(Icons.SPHERE, Colors.RED | Colors.A100, x, y);
            batcher2D.textShadow(label, x + 18, y + 4);
        }
    }

    public static void renderCoolStuff(IBbsWorldRenderContext worldRenderContext)
    {
        if (Minecraft.getInstance().screen instanceof UIScreen screen)
        {
            screen.renderInWorld(worldRenderContext);
        }

        BBSModClient.getFilms().render(worldRenderContext);
    }

    public static boolean isOptifinePresent()
    {
        return optifine;
    }

    public static boolean isRenderingWorld()
    {
        return renderingWorld;
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
