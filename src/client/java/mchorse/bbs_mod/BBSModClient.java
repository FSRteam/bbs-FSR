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
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
import mchorse.bbs_mod.client.renderer.item.GunItemRenderer;
import mchorse.bbs_mod.client.renderer.item.ModelBlockItemRenderer;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.events.register.RegisterClientSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormCategories;
import mchorse.bbs_mod.forms.categories.UserFormCategory;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.FramebufferManager;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.items.GunProperties;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class BBSModClient
{
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
    private static String playFilmAndRecordFilmId;

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
        dashboard = null;
        films = new Films();
        playFilmAndRecordFilmId = null;

        ClientNetwork.resetHandshake();
        films.reset();
        cameraController.reset();
    }

    public static void onClientTickPre()
    {
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
    }

    public static void onClientTickPost()
    {
        Minecraft mc = Minecraft.getInstance();

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

        if (playFilmAndRecordFilmId != null && !videoRecorder.isRecording())
        {
            playFilmAndRecordFilmId = null;
        }

        ensureKeyMappingsCreated();

        while (keyDashboard.consumeClick()) UIScreen.open(getDashboard());
        while (keyItemEditor.consumeClick()) keyOpenModelBlockEditor(mc);
        while (keyPlayFilm.consumeClick()) keyPlayFilm();
        while (keyPauseFilm.consumeClick()) keyPauseFilm();
        while (keyRecordReplay.consumeClick()) keyRecordReplay();
        while (keyRecordVideo.consumeClick())
        {
            Window window = mc.getWindow();
            int width = Math.max(window.getWidth(), 2);
            int height = Math.max(window.getHeight(), 2);

            if (width % 2 == 1) width -= width % 2;
            if (height % 2 == 1) height -= height % 2;

            videoRecorder.toggleRecording(BBSRendering.getTexture().id, width, height);
            BBSRendering.setCustomSize(videoRecorder.isRecording(), width, height);
        }
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

            if (gunZoom == null && zoom && stack.getItem() == BBSMod.GUN_ITEM)
            {
                GunProperties properties = GunProperties.get(stack);

                ClientNetwork.sendZoom(true);
                gunZoom = new GunZoom(properties.fovTarget, properties.fovInterp, properties.fovDuration);
            }
        }
    }

    public static void onRenderGuiPost(GuiGraphics drawContext, float tickDelta)
    {
        BBSRendering.renderHud(drawContext, tickDelta);

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
        BBSResources.stopWatchdog();
    }

    public static void onClientStarted()
    {
        BBSRendering.setupFramebuffer();
        BBSMod.getProvider().register(new MinecraftSourcePack());

        Window window = Minecraft.getInstance().getWindow();

        originalFramebufferScale = window.getWidth() / window.getWidth();
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
     * Start video recording and film playback together (Ctrl+F4).
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

        Film film = panel.getData();
        boolean sameComboSession = film.getId().equals(playFilmAndRecordFilmId);

        if (sameComboSession && videoRecorder.isRecording())
        {
            videoRecorder.stopRecording();
            BBSRendering.setCustomSize(false, 0, 0);
            Films.playFilm(film.getId(), false);
            getFilms().clearStopVideoRecordingWhenFilmFinished();
            playFilmAndRecordFilmId = null;

            return;
        }

        if (videoRecorder.isRecording())
        {
            return;
        }

        Window window = Minecraft.getInstance().getWindow();
        int width = Math.max(window.getWidth(), 2);
        int height = Math.max(window.getHeight(), 2);

        if (width % 2 == 1) width -= width % 2;
        if (height % 2 == 1) height -= height % 2;

        videoRecorder.toggleRecording(BBSRendering.getTexture().id, width, height);
        BBSRendering.setCustomSize(videoRecorder.isRecording(), width, height);

        Films.playFilm(film.getId(), false);
        getFilms().setStopVideoRecordingWhenFilmFinished(film.getId());
        playFilmAndRecordFilmId = film.getId();
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

                if (recorder == null || recorder.hasNotStarted() || panel.getData() == null)
                {
                    return;
                }

                panel.applyRecordedKeyframes(recorder, panel.getData());
            }
            else
            {
                Replay replay = panel.replayEditor.getReplay();
                int index = panel.getData().replays.getList().indexOf(replay);

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
            panel.preview.openReplays();
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
        return getLanguageKey(BBSSettings.language.get());
    }

    public static String getLanguageKey(String key)
    {
        if (key.isEmpty())
        {
            key = Minecraft.getInstance().options.languageCode;
        }

        return key;
    }

    public static void reloadLanguage(String language)
    {
        l10n.reload(language, BBSMod.getProvider());
    }
}
