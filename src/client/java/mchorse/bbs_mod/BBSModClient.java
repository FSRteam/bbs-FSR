package mchorse.bbs_mod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.InputConstants;
import mchorse.bbs_mod.audio.SoundManager;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.camera.clips.misc.CurveClientClip;
import mchorse.bbs_mod.camera.clips.misc.TrackerClientClip;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.camera.controller.ICameraController;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.compat.ClientApiCompat;
import mchorse.bbs_mod.client.film.collaboration.BBSFilmCollaborationBridge;
import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
import mchorse.bbs_mod.client.renderer.item.GunItemRenderer;
import mchorse.bbs_mod.client.renderer.item.ModelBlockItemRenderer;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.client.ui.mirror.BBSUiMirrorRuntime;
import mchorse.bbs_mod.client.ui.mirror.BBSUiOpenDispatcher;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.events.register.RegisterClientSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.WorldVideoExportSession;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.categories.UserFormCategory;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.FramebufferManager;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.items.GunPropertiesPolicy;
import mchorse.bbs_mod.items.GunZoom;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.particles.ParticleManager;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.URLError;
import mchorse.bbs_mod.resources.packs.URLRepository;
import mchorse.bbs_mod.resources.packs.URLSourcePack;
import mchorse.bbs_mod.resources.packs.URLTextureErrorCallback;
import mchorse.bbs_mod.selectors.EntitySelectors;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockEditorMenu;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.ui.utils.keys.KeybindSettings;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.ScreenshotRecorder;
import mchorse.bbs_mod.utils.VideoRecorder;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.resources.MinecraftSourcePack;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.Connection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class BBSModClient
{
    private static final Logger LOGGER = LoggerFactory.getLogger(BBSModClient.class);

    private static TextureManager textures;
    private static FramebufferManager framebuffers;
    private static SoundManager sounds;
    private static L10n l10n;

    private static ModelManager models;
    private static FormCategories formCategories;
    private static ScreenshotRecorder screenshotRecorder;
    private static VideoRecorder videoRecorder;
    private static EntitySelectors selectors;

    private static ParticleManager particles;

    private static KeyMapping keyDashboard;
    private static KeyMapping keyItemEditor;
    private static KeyMapping keyPlayFilm;
    private static KeyMapping keyPauseFilm;
    private static KeyMapping keyRecordReplay;
    private static KeyMapping keyRecordVideo;
    private static KeyMapping keyPlayFilmAndRecord;
    private static KeyMapping keyOpenReplays;
    private static KeyMapping keyOpenMorphing;
    private static KeyMapping keyDemorph;
    private static KeyMapping keyTeleport;
    private static KeyMapping keyZoom;

    private static UIDashboard dashboard;

    private static CameraController cameraController = new CameraController();
    private static ModelBlockItemRenderer modelBlockItemRenderer = BBSItemRenderers.getModelBlockRenderer();
    private static GunItemRenderer gunItemRenderer = BBSItemRenderers.getGunRenderer();
    private static Films films;
    private static GunZoom gunZoom;
    private static final WorldVideoExportSession worldExportSession = new WorldVideoExportSession();

    private static float originalFramebufferScale;

    public static TextureManager getTextures()
    {
        return textures;
    }

    public static FramebufferManager getFramebuffers()
    {
        return framebuffers;
    }

    public static SoundManager getSounds()
    {
        return sounds;
    }

    public static L10n getL10n()
    {
        return l10n;
    }

    public static ModelManager getModels()
    {
        return models;
    }

    public static FormCategories getFormCategories()
    {
        return formCategories;
    }

    public static ScreenshotRecorder getScreenshotRecorder()
    {
        return screenshotRecorder;
    }

    public static VideoRecorder getVideoRecorder()
    {
        return videoRecorder;
    }

    public static EntitySelectors getSelectors()
    {
        return selectors;
    }

    public static ParticleManager getParticles()
    {
        return particles;
    }

    public static CameraController getCameraController()
    {
        return cameraController;
    }

    public static Films getFilms()
    {
        return films;
    }

    public static GunZoom getGunZoom()
    {
        return gunZoom;
    }

    public static KeyMapping getKeyZoom()
    {
        return keyZoom;
    }

    public static KeyMapping getKeyRecordVideo()
    {
        return keyRecordVideo;
    }

    public static boolean isVideoExportDelayPending()
    {
        return worldExportSession.isWarmingUp();
    }

    public static long getVideoExportDelayRemainingMs()
    {
        return worldExportSession.getWarmupRemainingMs();
    }

    /** Returns the dashboard without creating it. Used to avoid creating UI when handling keys (e.g. F6) before user has opened BBS. */
    public static UIDashboard getDashboardIfCreated()
    {
        return dashboard;
    }

    public static UIDashboard getDashboard()
    {
        if (dashboard == null)
        {
            dashboard = new UIDashboard();
        }

        return dashboard;
    }

    public static int getGUIScale()
    {
        int scale = BBSSettings.userIntefaceScale.get();

        if (scale == 0)
        {
            return Minecraft.getInstance().options.guiScale().get();
        }

        return scale;
    }

    public static float getOriginalFramebufferScale()
    {
        return Math.max(originalFramebufferScale, 1);
    }

    public static ModelProperties getItemStackProperties(ItemStack stack)
    {
        ModelBlockItemRenderer.Item item = modelBlockItemRenderer.get(stack);

        if (item != null)
        {
            return item.entity.getProperties();
        }

        GunItemRenderer.Item gunItem = gunItemRenderer.get(stack);

        if (gunItem != null)
        {
            return gunItem.properties;
        }

        return null;
    }

    public static void onEndKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info)
    {
        onEndKey(window, key, scancode, action, modifiers);
    }

    public static void onEndKey(long window, int key, int scancode, int action, int modifiers)
    {
        if (action != GLFW.GLFW_PRESS)
        {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null || Minecraft.getInstance().screen != null)
        {
            return;
        }

        Morph morph = Morph.getMorph(player);

        /* Animation state trigger */
        if (morph != null && morph.getForm() != null && morph.getForm().findState(key, (form, state) ->
        {
            ClientNetwork.sendFormTrigger(state.id.get(), ServerNetwork.STATE_TRIGGER_MORPH);
            form.playState(state);
        }))
            return;

        /* Animation state trigger for items*/
        ModelProperties main = getItemStackProperties(player.getItemInHand(InteractionHand.MAIN_HAND));
        ModelProperties offhand = getItemStackProperties(player.getItemInHand(InteractionHand.OFF_HAND));

        if (main != null && main.getForm() != null && main.getForm().findState(key, (form, state) ->
        {
            ClientNetwork.sendFormTrigger(state.id.get(), ServerNetwork.STATE_TRIGGER_MAIN_HAND_ITEM);
            form.playState(state);
        }))
            return;

        if (offhand != null && offhand.getForm() != null && offhand.getForm().findState(key, (form, state) ->
        {
            ClientNetwork.sendFormTrigger(state.id.get(), ServerNetwork.STATE_TRIGGER_OFF_HAND_ITEM);
            form.playState(state);
        }))
            return;

        /* Change form based on the hotkey */
        for (Form form : BBSModClient.getFormCategories().getRecentForms().getCategories().get(0).getForms())
        {
            if (form.hotkey.get() == key)
            {
                ClientNetwork.sendPlayerForm(form);

                return;
            }
        }

        for (UserFormCategory category : BBSModClient.getFormCategories().getUserForms().categories)
        {
            for (Form form : category.getForms())
            {
                if (form.hotkey.get() == key)
                {
                    ClientNetwork.sendPlayerForm(form);

                    return;
                }
            }
        }
    }

    public void onInitializeClient()
    {
        AssetProvider provider = BBSMod.getProvider();

        textures = new TextureManager(provider);
        framebuffers = new FramebufferManager();
        sounds = new SoundManager(provider);
        l10n = new L10n();
        l10n.register((lang) -> Collections.singletonList(Link.assets("strings/" + lang + ".json")));
        l10n.reload();

        BBSMod.events.post(new RegisterL10nEvent(l10n));

        File parentFile = BBSMod.getSettingsFolder().getParentFile();

        particles = new ParticleManager(() -> new File(BBSMod.getAssetsFolder(), "particles"));

        models = new ModelManager(provider);
        formCategories = new FormCategories();
        screenshotRecorder = new ScreenshotRecorder(new File(parentFile, "screenshots"));
        videoRecorder = new VideoRecorder();
        selectors = new EntitySelectors();
        selectors.read();
        films = new Films();

        BBSResources.init();

        URLRepository repository = new URLRepository(new File(parentFile, "url_cache"));

        provider.register(new URLSourcePack("http", repository));
        provider.register(new URLSourcePack("https", repository));

        KeybindSettings.registerClasses();

        BBSMod.setupConfig(Icons.KEY_CAP, "keybinds", new File(BBSMod.getSettingsFolder(), "keybinds.json"), KeybindSettings::register);

        BBSMod.events.post(new RegisterClientSettingsEvent());

        BBSSettings.language.postCallback((v, f) -> reloadLanguage(getLanguageKey()));
        BBSSettings.editorSeconds.postCallback((v, f) ->
        {
            if (dashboard != null && dashboard.getPanels().panel instanceof UIFilmPanel panel)
            {
                panel.fillData();
            }
        });

        BBSSettings.theme.modes(
            UIKeys.ENGINE_THEME_LIGHT,
            UIKeys.ENGINE_THEME_DARK
        );

        BBSSettings.tooltipStyle.modes(
            UIKeys.ENGINE_TOOLTIP_STYLE_LIGHT,
            UIKeys.ENGINE_TOOLTIP_STYLE_DARK
        );

        BBSSettings.keystrokeMode.modes(
            UIKeys.ENGINE_KEYSTROKES_POSITION_AUTO,
            UIKeys.ENGINE_KEYSTROKES_POSITION_BOTTOM_LEFT,
            UIKeys.ENGINE_KEYSTROKES_POSITION_BOTTOM_RIGHT,
            UIKeys.ENGINE_KEYSTROKES_POSITION_TOP_RIGHT,
            UIKeys.ENGINE_KEYSTROKES_POSITION_TOP_LEFT
        );

        BBSSettings.rotate3dSphereMode.modes(
            UIKeys.ENGINE_ROTATE_3D_SPHERE_MODE_TRACKBALL,
            UIKeys.ENGINE_ROTATE_3D_SPHERE_MODE_ARCBALL
        );

        BBSSettings.translateHotkeyOrder.labels(
            UIKeys.TRANSFORMS_TARGET_SCREEN,
            UIKeys.GENERAL_X,
            UIKeys.GENERAL_Y,
            UIKeys.GENERAL_Z
        );
        BBSSettings.scaleHotkeyOrder.labels(UIKeys.GENERAL_X, UIKeys.GENERAL_Y, UIKeys.GENERAL_Z);
        BBSSettings.rotateHotkeyOrder.labels(
            UIKeys.TRANSFORMS_TARGET_VIEW,
            UIKeys.TRANSFORMS_TARGET_SPHERE,
            UIKeys.GENERAL_X,
            UIKeys.GENERAL_Y,
            UIKeys.GENERAL_Z
        );

        UIKeys.C_KEYBIND_CATGORIES.load(KeyCombo.getCategoryKeys());
        UIKeys.C_KEYBIND_CATGORIES_TOOLTIP.load(KeyCombo.getCategoryKeys());

        /* Replace audio clip with client version that plays audio */
        BBSMod.getFactoryCameraClips()
            .register(Link.bbs("audio"), AudioClientClip.class, new ClipFactoryData(Icons.SOUND, 0xffc825))
            .register(Link.bbs("tracker"), TrackerClientClip.class, new ClipFactoryData(Icons.USER, 0x4cedfc))
            .register(Link.bbs("curve"), CurveClientClip.class, new ClipFactoryData(Icons.ARC, 0xff1493));

        /* Keybinds */
        ensureKeyMappingsCreated();

        URLTextureErrorCallback.EVENT.register((url, error) ->
        {
            UIBaseMenu menu = UIScreen.getCurrentMenu();

            if (menu != null)
            {
                url = url.substring(0, MathUtils.clamp(url.length(), 0, 40));

                if (error == URLError.FFMPEG)
                {
                    menu.context.notifyError(UIKeys.TEXTURE_URL_ERROR_FFMPEG.format(url));
                }
                else if (error == URLError.HTTP_ERROR)
                {
                    menu.context.notifyError(UIKeys.TEXTURE_URL_ERROR_HTTP.format(url));
                }
            }
        });

        BBSRendering.setup();

        /* Network */
        ClientNetwork.setup();

        /* Create folders */
        BBSMod.getAudioFolder().mkdirs();
        BBSMod.getAssetsPath("textures").mkdirs();

        for (String path : List.of("alex", "alex_simple", "steve", "steve_simple"))
        {
            BBSMod.getAssetsPath("models/emoticons/" + path + "/").mkdirs();
        }

        for (String path : List.of("alex", "alex_bends", "eyes", "eyes_1px", "steve", "steve_bends"))
        {
            BBSMod.getAssetsPath("models/player/" + path + "/").mkdirs();
        }
    }

    public static void registerKeyMappings(Consumer<KeyMapping> register)
    {
        Objects.requireNonNull(register, "register");
        ensureKeyMappingsCreated();

        register.accept(keyDashboard);
        register.accept(keyItemEditor);
        register.accept(keyPlayFilm);
        register.accept(keyPauseFilm);
        register.accept(keyRecordReplay);
        register.accept(keyRecordVideo);
        register.accept(keyPlayFilmAndRecord);
        register.accept(keyOpenReplays);
        register.accept(keyOpenMorphing);
        register.accept(keyDemorph);
        register.accept(keyTeleport);
        register.accept(keyZoom);
    }

    public static void onRenderAfterEntities(IBbsWorldRenderContext context)
    {
        if (!BBSRendering.isIrisShadersEnabled())
        {
            BBSRendering.renderCoolStuff(context);
        }

        if (BBSSettings.chromaSkyEnabled.get())
        {
            float d = BBSSettings.chromaSkyBillboard.get();

            if (d > 0)
            {
                PoseStack stack = context.matrixStack();
                Integer fromCurve = BBSRendering.getChromaSkyColorArgb();
                Color color = Colors.COLOR.set(fromCurve != null ? fromCurve : BBSSettings.chromaSkyColor.get());

                stack.pushPose();

                PoseStack.Pose peek = stack.last();

                peek.pose().identity();
                peek.normal().identity();
                stack.translate(0F, 0F, -d);

                RenderSystem.enableDepthTest();
                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

                float fov = Minecraft.getInstance().options.fov().get().floatValue();
                float dd = d * (float) Math.pow(fov / 40F, 2F);

                Draw.fillQuad(builder, stack,
                    -dd, -dd, 0,
                    dd, -dd, 0,
                    dd, dd, 0,
                    -dd, dd, 0,
                    color.r, color.g, color.b, 1F
                );

                RenderSystem.setShader(GameRenderer::getPositionColorShader);

                BufferUploader.drawWithShader(builder.buildOrThrow());
                RenderSystem.disableDepthTest();

                stack.popPose();
            }
        }

        ClientApiCompat.emitAfterEntities(context);
    }

    public static void onRenderAfterLevel()
    {
        if (videoRecorder.isRecording() && BBSRendering.canRender)
        {
            videoRecorder.recordFrame();
        }
    }

    public static void onClientDisconnect()
    {
        UIFilmPanel filmPanel = getFilmPanelForLifecycle("disconnect");

        try
        {
            runClientLifecycleStep("stop disconnect Film recording", () -> stopFilmRecordingForLifecycle(filmPanel, "disconnect"));
            saveFilmPanelForLifecycle(filmPanel, "disconnect");
            runClientLifecycleStep("notify addon disconnect", () -> ClientApiCompat.emitDisconnect(Minecraft.getInstance()));
            runClientLifecycleStep("cancel client exports", () -> cancelClientExports(filmPanel));
            runClientLifecycleStep("reset UI mirror", () -> BBSUiMirrorRuntime.reset());
            runClientLifecycleStep("reset Film collaboration", () -> BBSFilmCollaborationBridge.resetSession());
        }
        finally
        {
            /* Identity clearing is unconditional and independent of callbacks. */
            dashboard = null;
        }

        runClientLifecycleStep("reset Film controller state", () -> films.reset());
        runClientLifecycleStep("replace Film controller", () -> films = new Films());
        runClientLifecycleStep("reset network handshake", () -> ClientNetwork.resetHandshake());
        resetCameraControllersForLifecycle("disconnect");
    }

    public static void onClientPlayerClone(Connection connection, LocalPlayer oldPlayer, LocalPlayer newPlayer)
    {
        runClientLifecycleStep("replace exact client network player scope",
            () -> ClientNetwork.onClientPlayerClone(connection, oldPlayer, newPlayer));
        runClientLifecycleStep("reset Film controller state for client-player clone", () ->
        {
            if (films != null)
            {
                films.reset();
            }
        });
        runClientLifecycleStep("replace Film controller for client-player clone", () -> films = new Films());
        resetCameraControllersForLifecycle("client-player clone");
    }

    private static void resetCameraControllersForLifecycle(String lifecycle)
    {
        List<ICameraController> removed = Collections.emptyList();

        try
        {
            removed = cameraController.removeAll(PlayCameraController.class);
        }
        catch (RuntimeException | Error exception)
        {
            LOGGER.error("[bbs-client] failed to remove Film playback cameras during {}; continuing lifecycle teardown",
                lifecycle,
                exception);
        }

        for (ICameraController controller : removed)
        {
            if (controller instanceof PlayCameraController play)
            {
                runClientLifecycleStep(lifecycle + " shutdown Film playback camera",
                    () -> play.getContext().shutdown());
            }
        }

        runClientLifecycleStep(lifecycle + " clear camera controllers", () -> cameraController.reset());
    }

    public static void onClientTickPre()
    {
        ClientApiCompat.emitStartClientTick(Minecraft.getInstance());
        BBSRendering.startTick();
    }

    public static void onLevelTickPost()
    {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isPaused())
        {
            films.updateEndWorld();
        }

        BBSResources.tick();
        ClientApiCompat.emitEndWorldTick(mc);
    }

    public static void onClientTickPost()
    {
        Minecraft mc = Minecraft.getInstance();

        BBSUiOpenDispatcher.tick(mc);

        if (mc.screen instanceof UIScreen screen)
        {
            screen.update();
        }

        cameraController.update();

        if (!mc.isPaused())
        {
            films.update();
            modelBlockItemRenderer.update();
            gunItemRenderer.update();
            textures.update();
        }

        worldExportSession.update();

        ensureKeyMappingsCreated();

        while (keyDashboard.consumeClick()) UIScreen.open(getDashboard());
        while (keyItemEditor.consumeClick()) keyOpenModelBlockEditor(mc);
        while (keyPlayFilm.consumeClick()) keyPlayFilm();
        while (keyPauseFilm.consumeClick()) keyPauseFilm();
        while (keyRecordReplay.consumeClick()) keyRecordReplay();
        while (keyRecordVideo.consumeClick()) keyRecordVideo(mc);
        while (keyPlayFilmAndRecord.consumeClick()) keyPlayFilmAndRecord();
        while (keyOpenReplays.consumeClick()) keyOpenReplays();
        while (keyOpenMorphing.consumeClick())
        {
            UIDashboard dashboard = getDashboard();

            UIScreen.open(dashboard);
            dashboard.setPanel(dashboard.getPanel(UIMorphingPanel.class));
        }
        while (keyDemorph.consumeClick()) ClientNetwork.sendPlayerForm(null);
        while (keyTeleport.consumeClick()) keyTeleport();

        if (mc.player != null)
        {
            boolean zoom = keyZoom.isDown();
            ItemStack stack = mc.player.getMainHandItem();

            if (gunZoom == null && zoom && stack.getItem() == BBSMod.GUN_ITEM.get())
            {
                GunProperties properties = GunProperties.get(stack);

                if (GunPropertiesPolicy.isAllowed(properties))
                {
                    ClientNetwork.sendZoom(true);
                    gunZoom = new GunZoom(properties.fovTarget, properties.fovInterp, properties.fovDuration);
                }
            }
        }

        ClientApiCompat.emitEndClientTick(mc);
    }

    public static void onRenderGuiPost(GuiGraphics drawContext, float tickDelta)
    {
        BBSRendering.renderHud(drawContext, tickDelta);
        ClientApiCompat.emitHudRender(drawContext, tickDelta);

        if (gunZoom != null)
        {
            gunZoom.update(keyZoom.isDown(), Minecraft.getInstance().getTimer().getGameTimeDeltaTicks());

            if (gunZoom.canBeRemoved())
            {
                ClientNetwork.sendZoom(false);
                gunZoom = null;
            }
        }
    }

    public static void onClientStopping()
    {
        UIFilmPanel filmPanel = getFilmPanelForLifecycle("client stopping");

        try
        {
            saveFilmPanelForLifecycle(filmPanel, "client stopping");
            runClientLifecycleStep("notify addon client stopping", () -> ClientApiCompat.emitClientStopping(Minecraft.getInstance()));
            runClientLifecycleStep("cancel client exports", () -> cancelClientExports(filmPanel));
            runClientLifecycleStep("shutdown UI mirror", () -> BBSUiMirrorRuntime.shutdown());
            runClientLifecycleStep("reset Film collaboration", () -> BBSFilmCollaborationBridge.resetSession());
        }
        finally
        {
            dashboard = null;
            runClientLifecycleStep("stop resource watchdog", () -> BBSResources.stopWatchdog());
        }
    }

    private static void cancelClientExports(UIFilmPanel filmPanel)
    {
        /* Fence callbacks that have not reached the panel session yet,
         * including wrappers already queued by the render thread. */
        runClientLifecycleStep("fence pending export startup", () -> BBSRendering.cancelPendingExportResolutionActions());

        if (filmPanel != null)
        {
            /* Cancel through the session while its UI owner is reachable so
             * warm-up and active exports both delete session-owned audio. */
            runClientLifecycleStep("cancel panel export", () -> filmPanel.recorder.cancel());
        }

        runClientLifecycleStep("cancel world export", () -> worldExportSession.cancel());

        /* A panel or addon can leave the shared recorder without a live UI
         * session. Cancellation must never be reported as natural completion. */
        runClientLifecycleStep("cancel orphaned video recorder", () ->
        {
            if (videoRecorder != null && videoRecorder.isRecording())
            {
                videoRecorder.cancelRecording();
            }
        });

        runClientLifecycleStep("restore export render size", () -> BBSRendering.setCustomSize(false, 0, 0));
    }

    private static void stopFilmRecordingForLifecycle(UIFilmPanel filmPanel, String lifecycle)
    {
        Films owner = films;
        Recorder recorder = owner == null ? null : owner.getRecorder();

        if (recorder == null)
        {
            return;
        }

        runClientLifecycleStep(lifecycle + " stop Film recording", () -> owner.stopRecordingForClientLifecycle(recorder));

        if (owner.getRecorder() == recorder)
        {
            return;
        }

        Film film = filmPanel == null ? null : filmPanel.getData();

        if (film != null
            && Objects.equals(film.getId(), recorder.getRecordingFilmId())
            && CollectionUtils.inRange(film.replays.getList(), recorder.getRecordingReplayId())
            && recorder.hasRecordedFrame()
            && recorder.isInCurrentLevel())
        {
            runClientLifecycleStep(lifecycle + " apply Film recording", () -> filmPanel.applyRecordedKeyframes(recorder, film));
        }
    }

    private static void saveFilmPanelForLifecycle(UIFilmPanel filmPanel, String lifecycle)
    {
        if (filmPanel == null)
        {
            return;
        }

        /* Keep the remote repository and collaboration owner alive for both
         * operations, but do not let either failure skip mandatory teardown. */
        runClientLifecycleStep(lifecycle + " film collaboration flush", () -> filmPanel.flushFilmCollaborationEdits());
        runClientLifecycleStep(lifecycle + " film save", () -> filmPanel.save());
    }

    private static UIFilmPanel getFilmPanelForLifecycle(String lifecycle)
    {
        if (dashboard == null)
        {
            return null;
        }

        try
        {
            return dashboard.getPanel(UIFilmPanel.class);
        }
        catch (RuntimeException | Error exception)
        {
            LOGGER.error("[bbs-client] failed to resolve Film panel during {}; continuing lifecycle teardown", lifecycle, exception);

            return null;
        }
    }

    private static void runClientLifecycleStep(String step, Runnable runnable)
    {
        try
        {
            runnable.run();
        }
        catch (RuntimeException | Error exception)
        {
            LOGGER.error("[bbs-client] failed to {}; continuing lifecycle teardown", step, exception);
        }
    }

    public static void onClientStarted()
    {
        BBSRendering.setupFramebuffer();
        BBSMod.getProvider().register(new MinecraftSourcePack());

        BBSUiOpenDispatcher.start(Minecraft.getInstance());

        Window window = Minecraft.getInstance().getWindow();

        originalFramebufferScale = window.getWidth() / (float) Math.max(window.getScreenWidth(), 1);
        ClientApiCompat.emitClientStarted(Minecraft.getInstance());
    }

    private static void ensureKeyMappingsCreated()
    {
        if (keyDashboard != null)
        {
            return;
        }

        keyDashboard = createKey("dashboard", GLFW.GLFW_KEY_0);
        keyItemEditor = createKey("item_editor", GLFW.GLFW_KEY_HOME);
        keyPlayFilm = createKey("play_film", GLFW.GLFW_KEY_RIGHT_CONTROL);
        keyPauseFilm = createKey("pause_film", GLFW.GLFW_KEY_BACKSLASH);
        keyRecordReplay = createKey("record_replay", GLFW.GLFW_KEY_RIGHT_ALT);
        keyRecordVideo = createKey("record_video", GLFW.GLFW_KEY_F4);
        keyPlayFilmAndRecord = createKey("play_film_and_record", GLFW.GLFW_KEY_F6);
        keyOpenReplays = createKey("open_replays", GLFW.GLFW_KEY_RIGHT_SHIFT);
        keyOpenMorphing = createKey("open_morphing", GLFW.GLFW_KEY_B);
        keyDemorph = createKey("demorph", GLFW.GLFW_KEY_PERIOD);
        keyTeleport = createKey("teleport", GLFW.GLFW_KEY_Y);
        keyZoom = createKeyMouse("zoom", 2);
    }

    private static void keyRecordVideo(Minecraft mc)
    {
        if (worldExportSession.isExporting())
        {
            worldExportSession.cancel();

            return;
        }

        worldExportSession.start(null, null);
    }

    private static KeyMapping createKey(String id, int key)
    {
        return new KeyMapping(
            "key." + BBSMod.MOD_ID + "." + id,
            InputConstants.Type.KEYSYM,
            key,
            "category." + BBSMod.MOD_ID + ".main"
        );
    }

    private static KeyMapping createKeyMouse(String id, int button)
    {
        return new KeyMapping(
            "key." + BBSMod.MOD_ID + "." + id,
            InputConstants.Type.MOUSE,
            button,
            "category." + BBSMod.MOD_ID + ".main"
        );
    }

    private static void keyOpenModelBlockEditor(Minecraft mc)
    {
        ItemStack stack = mc.player.getItemBySlot(EquipmentSlot.MAINHAND);
        ModelBlockItemRenderer.Item item = modelBlockItemRenderer.get(stack);
        GunItemRenderer.Item gunItem = gunItemRenderer.get(stack);

        if (item != null)
        {
            UIScreen.open(new UIModelBlockEditorMenu(item.entity.getProperties()));
        }
        else if (gunItem != null)
        {
            UIScreen.open(new UIModelBlockEditorMenu(gunItem.properties));
        }
    }

    private static void keyPlayFilm()
    {
        if (getDashboardIfCreated() == null)
        {
            return;
        }

        UIFilmPanel panel = getDashboard().getPanel(UIFilmPanel.class);
        if (panel.getData() != null)
        {
            Films.playFilm(panel.getData().getId(), false);
        }
    }

    /**
     * Start video recording and film playback together (F6).
     * Recording stops automatically when the film finishes.
     */
    private static void keyPlayFilmAndRecord()
    {
        if (getDashboardIfCreated() == null)
        {
            return;
        }

        UIFilmPanel panel = getDashboard().getPanel(UIFilmPanel.class);
        if (panel.getData() == null)
        {
            return;
        }

        String filmId = panel.getData().getId();

        if (worldExportSession.isExporting())
        {
            /* Toggle off only this film's F6 session; ignore unrelated exports. */
            if (filmId.equals(worldExportSession.getFilmId()))
            {
                worldExportSession.cancel();
            }

            return;
        }

        worldExportSession.start(filmId, panel.getData());
    }

    private static void keyPauseFilm()
    {
        if (getDashboardIfCreated() == null)
        {
            return;
        }

        UIFilmPanel panel = getDashboard().getPanel(UIFilmPanel.class);
        if (panel.getData() != null)
        {
            Films.pauseFilm(panel.getData().getId());
        }
    }

    private static void keyRecordReplay()
    {
        UIDashboard dashboard = getDashboard();
        UIFilmPanel panel = dashboard.getPanel(UIFilmPanel.class);

        if (panel != null && panel.getData() != null)
        {
            Recorder recorder = getFilms().getRecorder();

            if (recorder != null)
            {
                recorder = BBSModClient.getFilms().stopRecording();
                Film film = panel.getData();

                if (recorder == null
                    || film == null
                    || !Objects.equals(film.getId(), recorder.getRecordingFilmId())
                    || !CollectionUtils.inRange(film.replays.getList(), recorder.getRecordingReplayId())
                    || !recorder.hasRecordedFrame()
                    || !recorder.isInCurrentLevel())
                {
                    return;
                }

                panel.applyRecordedKeyframes(recorder, film);
            }
            else
            {
                Replay replay = panel.replayEditor.getReplay();
                int index = CollectionUtils.getIndex(panel.getData().replays.getList(), replay);

                if (index >= 0)
                {
                    getFilms().startRecording(panel.getData(), index, 0);
                }
            }
        }
    }

    private static void keyOpenReplays()
    {
        UIDashboard dashboard = getDashboard();

        UIScreen.open(dashboard);

        if (dashboard.getPanels().panel instanceof UIFilmPanel panel && panel.getData() != null)
        {
            panel.showPanel(panel.replayEditor);
        }
        else
        {
            dashboard.setPanel(dashboard.getPanel(UIFilmPanel.class));
        }
    }

    private static void keyTeleport()
    {
        UIDashboard dashboard = getDashboard();
        UIFilmPanel panel = dashboard.getPanel(UIFilmPanel.class);

        if (panel != null)
        {
            panel.replayEditor.teleport();
        }
    }

    public static String getLanguageKey()
    {
        return getLanguageKey(BBSSettings.language == null ? "" : BBSSettings.language.get());
    }

    public static String getLanguageKey(String key)
    {
        if (key == null || key.isEmpty())
        {
            Minecraft minecraft = Minecraft.getInstance();

            key = minecraft == null || minecraft.options == null ? "en_us" : minecraft.options.languageCode;
        }

        return key == null || key.isEmpty() ? "en_us" : key;
    }

    public static void reloadLanguage(String language)
    {
        AssetProvider provider = BBSMod.getProvider();

        if (l10n == null || provider == null)
        {
            return;
        }

        l10n.reload(language, provider);
    }
}
