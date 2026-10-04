package mchorse.bbs_mod.ui.dashboard;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchorResult;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchors;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardAnchorStatus;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardNavigationResult;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardNavigationStatus;
import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelIds;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.OrbitCamera;
import mchorse.bbs_mod.camera.controller.OrbitCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.dashboard.BBSDashboardPanelHostRegistry;
import mchorse.bbs_mod.client.dashboard.BBSDashboardOverlayHostRegistry;
import mchorse.bbs_mod.client.dashboard.DashboardPanelContribution;
import mchorse.bbs_mod.client.dashboard.DashboardOverlayContribution;
import mchorse.bbs_mod.events.register.RegisterDashboardPanelsEvent;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.ui.UISettingsOverlayPanel;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.panels.IFlightSupported;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.dashboard.plugins.UIPluginsPanel;
import mchorse.bbs_mod.ui.dashboard.textures.UITextureManagerPanel;
import mchorse.bbs_mod.ui.dashboard.utils.UIGraphPanel;
import mchorse.bbs_mod.ui.dashboard.utils.UIOrbitCamera;
import mchorse.bbs_mod.ui.dashboard.utils.UIOrbitCameraKeys;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIRenderingContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.utils.UIViewportStack;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.ui.model_editor.UIModelEditorPanel;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.update.FSRUpdates;
import mchorse.bbs_mod.ui.selectors.UISelectorsOverlayPanel;
import mchorse.bbs_mod.ui.utility.UIUtilityOverlayPanel;
import mchorse.bbs_mod.ui.utility.audio.UIAudioEditorPanel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIChalkboard;
import mchorse.bbs_mod.ui.utils.UIThemeBackdrop;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.pose.Transform;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public class UIDashboard extends UIBaseMenu
{
    private UIDashboardPanels panels;

    public UIIcon settings;
    public UIIcon selectors;

    /* Camera data */
    public final UIOrbitCamera orbitUI = new UIOrbitCamera();
    public final UIOrbitCameraKeys orbitKeysUI = new UIOrbitCameraKeys(this);
    public final OrbitCamera orbit = this.orbitUI.orbit;
    public final OrbitCameraController camera = new OrbitCameraController(this.orbit, 5);

    private UISettingsOverlayPanel settingsPanel;
    private CameraType lastPerspective = CameraType.FIRST_PERSON;

    private UIChalkboard chalkboard;
    private final UIElement addonOverlayLayer = new UIElement();
    private final Map<String, UIDashboardPanel> builtInPanels = new LinkedHashMap<>();
    private final Map<String, UIExtensionDashboardPanel> extensionPanels = new LinkedHashMap<>();
    private final Map<String, UIExtensionDashboardOverlay> extensionOverlays = new LinkedHashMap<>();
    private final Map<String, AnchorTarget> dashboardAnchors = new LinkedHashMap<>();
    private boolean dashboardOpen;

    public UIDashboard()
    {
        super();

        this.orbitUI.setControl(true);

        /* Setup panels */
        this.panels = new UIDashboardPanels();
        this.panels.getEvents().register(UIDashboardPanels.PanelEvent.class, (e) ->
        {
            this.restoreCurrentPanelControls();
        });
        this.panels.full(this.viewport);
        this.registerPanels();
        BBSDashboardPanelHostRegistry.installAll(this);

        BBSMod.events.post(new RegisterDashboardPanelsEvent(this));

        this.main.add(this.panels);
        this.addonOverlayLayer.full(this.getRoot()).eventPropagataion(EventPropagation.PASS);
        this.getRoot().addBefore(this.overlay, this.addonOverlayLayer);
        BBSDashboardOverlayHostRegistry.installAll(this);

        this.settingsPanel = new UISettingsOverlayPanel();

        this.settings = new UIIcon(Icons.SETTINGS, (b) -> this.openSettings());
        this.settings.tooltip(UIKeys.CONFIG_TITLE, Direction.TOP);
        this.selectors = new UIIcon(Icons.PROPERTIES, (b) ->
        {
            UIOverlay.addOverlayRight(this.context, new UISelectorsOverlayPanel(), 240);
        });
        this.selectors.tooltip(UIKeys.SELECTORS_TITLE, Direction.TOP);
        this.registerAnchor(BBSDashboardAnchors.SETTINGS, null, () -> this.settings);
        this.registerAnchor(BBSDashboardAnchors.SELECTORS, null, () -> this.selectors);
        this.chalkboard = new UIChalkboard();
        this.chalkboard.full(this.getRoot());

        this.panels.pinned.add(this.settings, this.selectors);
        this.getRoot().prepend(this.orbitUI);
        this.getRoot().add(this.orbitKeysUI);
        this.getRoot().add(this.chalkboard);

        /* Register keys */
        IKey category = UIKeys.DASHBOARD_CATEGORY;

        this.main.keys().register(Keys.CYCLE_PANELS, this::cyclePanels).category(category);
        this.main.keys().register(Keys.TOGGLE_TASKBAR_AUTO_HIDE, this::toggleTaskbarAutoHide).category(category);
        this.overlay.keys().register(Keys.TOGGLE_VISIBILITY, () ->
        {
            if (this.panels.panel.canToggleVisibility())
            {
                this.main.toggleVisible();
                this.resize(this.width, this.height);

                if (!this.main.isVisible() && this.panels.panel instanceof UIFilmPanel)
                {
                    UIFilmPanel.applyExportSizeToBBS();
                }
            }
        }).category(category);
        this.overlay.keys().register(Keys.TOGGLE_DEBUG, () ->
        {
            boolean enabled = !(BBSSettings.ikDebug.enabled.get() || BBSSettings.physicsDebug.enabled.get());

            BBSSettings.ikDebug.enabled.set(enabled);
            BBSSettings.physicsDebug.enabled.set(enabled);
        }).category(category);
        this.overlay.keys().register(Keys.OPEN_SETTINGS, () ->
        {
            if (!UIOverlay.has(this.context))
            {
                this.openSettings();
            }
        }).category(category);
        this.overlay.keys().register(Keys.OPEN_UTILITY_PANEL, () ->
        {
            if (UIOverlay.has(this.context))
            {
                return;
            }

            UIOverlay.addOverlay(this.context, new UIUtilityOverlayPanel(UIKeys.UTILITY_TITLE, null), 240, 160);
        });

        /* Kicks the throttled FSR update check; popups only land once the
         * dashboard is actually on screen. */
        FSRUpdates.onDashboardOpened(this);

        this.showAnnoyingPopups();
    }

    public void openSettings()
    {
        UIOverlay.addOverlay(this.context, this.settingsPanel, 430, 380);
    }

    private void showAnnoyingPopups()
    {
        if (BBSRendering.isOptifinePresent())
        {
            UIOverlay.addOverlay(this.context, new UIMessageOverlayPanel(
                UIKeys.DASHBOARD_OPTIFINE_EW_TITLE,
                UIKeys.DASHBOARD_OPTIFINE_EW_DESCRIPTION
            ));
        }
    }

    public void copyCurrentEntityCamera()
    {
        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        Vec3 eyePos = cameraEntity.getEyePosition();
        Camera camera = new Camera();

        camera.position.set(eyePos.x, eyePos.y, eyePos.z);
        camera.rotation.set(MathUtils.toRad(cameraEntity.getXRot()), MathUtils.toRad(cameraEntity.getYHeadRot() - 180), 0);
        camera.fov = MathUtils.toRad(Minecraft.getInstance().options.fov().get().floatValue());

        this.orbit.setup(camera);
        this.camera.setup(BBSModClient.getCameraController().camera, 0F);
    }

    public void focusModelBlock(ModelBlockEntity modelBlock)
    {
        if (modelBlock == null || modelBlock.isRemoved())
        {
            return;
        }

        BlockPos pos = modelBlock.getBlockPos();
        Transform transform = modelBlock.getProperties().getTransform();
        Vector3d center = new Vector3d(
            pos.getX() + 0.5D + transform.translate.x,
            pos.getY() + transform.translate.y,
            pos.getZ() + 0.5D + transform.translate.z
        );
        double distance = MathUtils.clamp(this.orbit.getFinalPosition().distance(center), 2D, 16D);
        Vector3f look = this.orbit.getLook();
        Camera focused = new Camera();

        focused.position.set(center).sub(look.x * distance, look.y * distance, look.z * distance);
        focused.rotation.set(this.orbit.rotation);
        focused.fov = this.orbit.fov;

        this.orbit.setup(focused);
        this.camera.setup(BBSModClient.getCameraController().camera, 0F);
    }

    private void cyclePanels()
    {
        List<UIDashboardPanel> panels = this.panels.panels;

        int direction = Window.isShiftPressed() ? -1 : 1;
        int index = panels.indexOf(this.panels.panel);
        int newIndex = MathUtils.cycler(index + direction, panels);

        this.setPanel(panels.get(newIndex));
        UIUtils.playClick();
    }

    private void toggleTaskbarAutoHide()
    {
        boolean enabled = !BBSSettings.dashboardAutoHideTaskbarEnabled();

        BBSSettings.dashboardAutoHideTaskbar.set(enabled);
        this.panels.resetTaskbarAutoHide();
        this.context.notifyInfo(enabled ? UIKeys.DASHBOARD_AUTO_HIDE_ENABLED : UIKeys.DASHBOARD_AUTO_HIDE_DISABLED);
    }

    public UIDashboardPanels getPanels()
    {
        return this.panels;
    }

    @Override
    public boolean canPause()
    {
        return this.panels.panel != null && this.panels.panel.canPause();
    }

    @Override
    public boolean canRefresh()
    {
        return this.panels.panel != null && this.panels.panel.canRefresh();
    }

    @Override
    public void onOpen(UIBaseMenu oldMenu)
    {
        super.onOpen(oldMenu);

        this.lastPerspective = Minecraft.getInstance().options.getCameraType();

        Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);

        if (oldMenu != this)
        {
            this.panels.open();
            this.setPanel(this.panels.panel);
            this.restoreCurrentPanelControls();
        }

        this.dashboardOpen = true;

        for (UIExtensionDashboardOverlay overlay : this.extensionOverlays.values())
        {
            overlay.open();
        }

        BBSModClient.getCameraController().add(this.camera);
    }

    @Override
    public void onClose(UIBaseMenu nextMenu)
    {
        super.onClose(nextMenu);

        if (nextMenu != this)
        {
            this.dashboardOpen = false;

            for (UIExtensionDashboardOverlay overlay : this.extensionOverlays.values())
            {
                overlay.close();
            }

            this.panels.close();
        }

        this.orbit.reset();
        BBSModClient.getCameraController().remove(this.camera);

        Minecraft.getInstance().options.setCameraType(this.lastPerspective);
    }

    /** Restore root-level input which is not owned by the reusable panel node. */
    private void restoreCurrentPanelControls()
    {
        this.context.unfocus();
        this.orbitUI.cancelGesture();
        boolean enableFlight = this.panels.panel instanceof IFlightSupported panel
            && panel.shouldEnableFlightOnRestore();

        this.orbitUI.setControl(enableFlight);

        if (this.panels.panel instanceof IFlightSupported panel)
        {
            this.orbit.setFovRoll(panel.supportsRollFOVControl());
        }

        this.copyCurrentEntityCamera();
    }

    @Override
    protected void closeMenu()
    {
        super.closeMenu();

        if (!this.main.isVisible())
        {
            this.main.setVisible(true);
        }
    }

    protected void registerPanels()
    {
        this.registerBuiltInPanel(BBSDashboardPanelIds.MORPHING, new UIMorphingPanel(this), UIKeys.MORPHING_TITLE, Icons.MORPH);
        this.registerBuiltInPanel(BBSDashboardPanelIds.FILM, new UIFilmPanel(this), UIKeys.FILM_TITLE, Icons.FILM);
        this.registerBuiltInPanel(BBSDashboardPanelIds.MODEL_BLOCKS, new UIModelBlockPanel(this), UIKeys.MODEL_BLOCKS_TITLE, Icons.BLOCK);
        this.registerBuiltInPanel(BBSDashboardPanelIds.PARTICLES, new UIParticleSchemePanel(this), UIKeys.PANELS_PARTICLES, Icons.PARTICLE).marginLeft(10);
        this.registerBuiltInPanel(BBSDashboardPanelIds.MODEL_EDITOR, new UIModelEditorPanel(this), UIKeys.MODEL_EDITOR_TITLE, Icons.POSE);
        this.registerBuiltInPanel(BBSDashboardPanelIds.TEXTURES, new UITextureManagerPanel(this), UIKeys.TEXTURES_TOOLTIP, Icons.MATERIAL);
        this.registerBuiltInPanel(BBSDashboardPanelIds.AUDIO, new UIAudioEditorPanel(this), UIKeys.AUDIO_TITLE, Icons.SOUND);
        this.registerBuiltInPanel(BBSDashboardPanelIds.GRAPH, new UIGraphPanel(this), UIKeys.GRAPH_TOOLTIP, Icons.GRAPH);
        this.registerBuiltInPanel(BBSDashboardPanelIds.PLUGINS, new UIPluginsPanel(this), UIKeys.PLUGINS_TITLE, Icons.PROCESSOR);

        if (!List.copyOf(this.builtInPanels.keySet()).equals(BBSDashboardPanelIds.BUILT_IN))
        {
            throw new IllegalStateException("Dashboard built-in panel ids do not match the taskbar registration order");
        }

        if (LoaderAccessHolder.get().isDevelopmentEnvironment())
        {
            this.panels.registerPanel(new UIDebugPanel(this), IKey.raw("Sandbox"), Icons.CODE);
        }

        this.setPanel(this.getPanel(UIFilmPanel.class));
    }

    private UIIcon registerBuiltInPanel(String id, UIDashboardPanel panel, IKey title, mchorse.bbs_mod.ui.utils.icons.Icon icon)
    {
        if (!BBSDashboardPanelIds.isBuiltIn(id) || this.builtInPanels.putIfAbsent(id, panel) != null)
        {
            throw new IllegalStateException("Invalid or duplicate built-in Dashboard panel id: " + id);
        }

        UIIcon button = this.panels.registerPanel(panel, title, icon);

        this.registerAnchor(BBSDashboardAnchors.panelButton(id), null, () -> button);
        this.registerAnchor(BBSDashboardAnchors.panelContent(id), id, () -> panel);

        if (panel instanceof UIMorphingPanel morphing)
        {
            this.registerMorphingAnchors(morphing);
        }
        else if (panel instanceof UIFilmPanel film)
        {
            this.registerFilmAnchors(film);
        }
        else if (panel instanceof UIModelBlockPanel modelBlocks)
        {
            this.registerModelBlocksAnchors(modelBlocks);
        }
        else if (panel instanceof UIParticleSchemePanel particles)
        {
            this.registerParticlesAnchors(particles);
        }
        else if (panel instanceof UIModelEditorPanel modelEditor)
        {
            this.registerModelEditorAnchors(modelEditor);
        }
        else if (panel instanceof UITextureManagerPanel textures)
        {
            this.registerTexturesAnchors(textures);
        }
        else if (panel instanceof UIAudioEditorPanel audio)
        {
            this.registerAudioAnchors(audio);
        }
        else if (panel instanceof UIGraphPanel graph)
        {
            this.registerGraphAnchors(graph);
        }
        else if (panel instanceof UIPluginsPanel plugins)
        {
            this.registerPluginsAnchors(plugins);
        }

        return button;
    }

    private void registerMorphingAnchors(UIMorphingPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.MORPHING_PALETTE, BBSDashboardPanelIds.MORPHING, () -> panel.palette);
        this.registerAnchor(BBSDashboardAnchors.MORPHING_PALETTE_LIST, BBSDashboardPanelIds.MORPHING, () -> panel.palette.list);
        this.registerAnchor(BBSDashboardAnchors.MORPHING_PALETTE_EDITOR, BBSDashboardPanelIds.MORPHING, () -> panel.palette.editor);
        this.registerAnchor(BBSDashboardAnchors.MORPHING_DEMORPH, BBSDashboardPanelIds.MORPHING, () -> panel.demorph);
        this.registerAnchor(BBSDashboardAnchors.MORPHING_FROM_MOB, BBSDashboardPanelIds.MORPHING, () -> panel.fromMob);
    }

    private void registerFilmAnchors(UIFilmPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.FILM_SELECTION, BBSDashboardPanelIds.FILM, () -> panel.selectionPanel);
        this.registerAnchor(BBSDashboardAnchors.FILM_RECORDER, BBSDashboardPanelIds.FILM, () -> panel.recorder);
        this.registerAnchor(BBSDashboardAnchors.FILM_PREVIEW, BBSDashboardPanelIds.FILM, () -> panel.preview);
        this.registerAnchor(BBSDashboardAnchors.FILM_DUPLICATE, BBSDashboardPanelIds.FILM, () -> panel.duplicateFilm);
        this.registerAnchor(BBSDashboardAnchors.FILM_OPEN_MENU, BBSDashboardPanelIds.FILM, () -> panel.openFilmMenu);
        this.registerAnchor(BBSDashboardAnchors.FILM_OPEN_CAMERA_EDITOR, BBSDashboardPanelIds.FILM, () -> panel.openCameraEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_OPEN_REPLAY_EDITOR, BBSDashboardPanelIds.FILM, () -> panel.openReplayEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_OPEN_ACTION_EDITOR, BBSDashboardPanelIds.FILM, () -> panel.openActionEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_CAMERA_EDITOR, BBSDashboardPanelIds.FILM, () -> panel.cameraEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_REPLAY_EDITOR, BBSDashboardPanelIds.FILM, () -> panel.replayEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_ACTION_EDITOR, BBSDashboardPanelIds.FILM, () -> panel.actionEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_CAMERA_TIMELINE, BBSDashboardPanelIds.FILM, () -> panel.cameraEditor.clips);
        this.registerAnchor(BBSDashboardAnchors.FILM_REPLAY_TIMELINE, BBSDashboardPanelIds.FILM, () -> panel.replayEditor.keyframeEditor);
        this.registerAnchor(BBSDashboardAnchors.FILM_ACTION_TIMELINE, BBSDashboardPanelIds.FILM, () -> panel.actionEditor.clips);
    }

    private void registerModelBlocksAnchors(UIModelBlockPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.MODEL_BLOCKS_LIST, BBSDashboardPanelIds.MODEL_BLOCKS, () -> panel.modelBlocks);
        this.registerAnchor(BBSDashboardAnchors.MODEL_BLOCKS_PICK_EDIT, BBSDashboardPanelIds.MODEL_BLOCKS, () -> panel.pickEdit);
        this.registerAnchor(BBSDashboardAnchors.MODEL_BLOCKS_TOGGLE_ENABLED, BBSDashboardPanelIds.MODEL_BLOCKS, () -> panel.enabled);
        this.registerAnchor(BBSDashboardAnchors.MODEL_BLOCKS_TOGGLE_SHADOW, BBSDashboardPanelIds.MODEL_BLOCKS, () -> panel.shadow);
        this.registerAnchor(BBSDashboardAnchors.MODEL_BLOCKS_TOGGLE_GLOBAL, BBSDashboardPanelIds.MODEL_BLOCKS, () -> panel.global);
        this.registerAnchor(BBSDashboardAnchors.MODEL_BLOCKS_TOGGLE_LOOK_AT, BBSDashboardPanelIds.MODEL_BLOCKS, () -> panel.lookAt);
    }

    private void registerParticlesAnchors(UIParticleSchemePanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.PARTICLES_RENDERER, BBSDashboardPanelIds.PARTICLES, () -> panel.renderer);
        this.registerAnchor(BBSDashboardAnchors.PARTICLES_SELECTION, BBSDashboardPanelIds.PARTICLES, () -> panel.selectionPanel);
        this.registerAnchor(BBSDashboardAnchors.PARTICLES_DOCK, BBSDashboardPanelIds.PARTICLES, () -> panel.dock);
        this.registerAnchor(BBSDashboardAnchors.PARTICLES_LOCK_LAYOUT, BBSDashboardPanelIds.PARTICLES, () -> panel.lockLayoutButton);
        this.registerAnchor(BBSDashboardAnchors.PARTICLES_LAYOUT_PRESETS, BBSDashboardPanelIds.PARTICLES, () -> panel.layoutPresetsButton);
        this.registerAnchor(BBSDashboardAnchors.PARTICLES_PLAY_PAUSE, BBSDashboardPanelIds.PARTICLES, () -> panel.playPauseBtn);
    }

    private void registerModelEditorAnchors(UIModelEditorPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.MODEL_EDITOR_GENERAL, BBSDashboardPanelIds.MODEL_EDITOR, () -> panel.general);
        this.registerAnchor(BBSDashboardAnchors.MODEL_EDITOR_RENDERER, BBSDashboardPanelIds.MODEL_EDITOR, () -> panel.renderer);
        this.registerAnchor(BBSDashboardAnchors.MODEL_EDITOR_SPLITTER, BBSDashboardPanelIds.MODEL_EDITOR, () -> panel.splitter);
    }

    private void registerTexturesAnchors(UITextureManagerPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.TEXTURES_PICKER, BBSDashboardPanelIds.TEXTURES, () -> panel.picker);
    }

    private void registerAudioAnchors(UIAudioEditorPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.AUDIO_PICK, BBSDashboardPanelIds.AUDIO, () -> panel.pickAudio);
        this.registerAnchor(BBSDashboardAnchors.AUDIO_PLAY_PAUSE, BBSDashboardPanelIds.AUDIO, () -> panel.plause);
        this.registerAnchor(BBSDashboardAnchors.AUDIO_SAVE_COLORS, BBSDashboardPanelIds.AUDIO, () -> panel.saveColors);
        this.registerAnchor(BBSDashboardAnchors.AUDIO_EDITOR, BBSDashboardPanelIds.AUDIO, () -> panel.audioEditor);
    }

    private void registerGraphAnchors(UIGraphPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.GRAPH_CANVAS, BBSDashboardPanelIds.GRAPH, () -> panel.canvas);
        this.registerAnchor(BBSDashboardAnchors.GRAPH_EXPRESSION, BBSDashboardPanelIds.GRAPH, () -> panel.expression);
        this.registerAnchor(BBSDashboardAnchors.GRAPH_HELP, BBSDashboardPanelIds.GRAPH, () -> panel.help);
    }

    private void registerPluginsAnchors(UIPluginsPanel panel)
    {
        this.registerAnchor(BBSDashboardAnchors.PLUGINS_LIST, BBSDashboardPanelIds.PLUGINS, () -> panel.list);
        this.registerAnchor(BBSDashboardAnchors.PLUGINS_RESCAN, BBSDashboardPanelIds.PLUGINS, () -> panel.rescan);
        this.registerAnchor(BBSDashboardAnchors.PLUGINS_OPEN_FOLDER, BBSDashboardPanelIds.PLUGINS, () -> panel.openFolder);
        this.registerAnchor(BBSDashboardAnchors.PLUGINS_INSTALL, BBSDashboardPanelIds.PLUGINS, () -> panel.install);
        this.registerAnchor(BBSDashboardAnchors.PLUGINS_AUTO_APPLY, BBSDashboardPanelIds.PLUGINS, () -> panel.autoApply);
    }

    public <T> T getPanel(Class<T> clazz)
    {
        return this.panels.getPanel(clazz);
    }

    public void setPanel(UIDashboardPanel panel)
    {
        this.panels.setPanel(panel);
    }

    public BBSDashboardNavigationResult navigateDashboardPanel(String requestedId)
    {
        String panelId = requestedId == null ? "" : requestedId.trim();

        if (panelId.isEmpty())
        {
            return new BBSDashboardNavigationResult(
                BBSDashboardNavigationStatus.REJECTED, panelId, "Dashboard panel id is blank"
            );
        }

        UIDashboardPanel target = this.builtInPanels.get(panelId);

        if (target == null)
        {
            target = this.extensionPanels.get(panelId);
        }
        if (target == null)
        {
            return new BBSDashboardNavigationResult(
                BBSDashboardNavigationStatus.PANEL_NOT_FOUND, panelId, "Dashboard panel id is not registered"
            );
        }
        if (this.panels.panel == target)
        {
            return new BBSDashboardNavigationResult(
                BBSDashboardNavigationStatus.ALREADY_ACTIVE, panelId, "Dashboard panel is already active"
            );
        }

        this.setPanel(target);

        if (this.panels.panel != target)
        {
            return new BBSDashboardNavigationResult(
                BBSDashboardNavigationStatus.FAILED, panelId, "Dashboard panel switch did not complete"
            );
        }

        return new BBSDashboardNavigationResult(
            BBSDashboardNavigationStatus.NAVIGATED, panelId, "Dashboard panel switch completed"
        );
    }

    public BBSDashboardAnchorResult resolveDashboardAnchor(String requestedId)
    {
        String anchorId = requestedId == null ? "" : requestedId.trim();

        if (anchorId.isEmpty())
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.REJECTED, anchorId,
                "Dashboard anchor id is blank");
        }

        AnchorTarget target = this.dashboardAnchors.get(anchorId);

        if (target == null)
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.CONTROL_NOT_FOUND, anchorId,
                "Dashboard anchor id is not registered");
        }
        if (target.panelId() != null && !target.panelId().equals(this.activePanelId()))
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.PANEL_NOT_MOUNTED, anchorId,
                "Dashboard anchor panel is not active");
        }

        UIElement element = target.element().get();

        if (element == null)
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.CONTROL_NOT_FOUND, anchorId,
                "Dashboard anchor control is not available");
        }
        if (!element.canBeSeen())
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.CONTROL_HIDDEN, anchorId,
                "Dashboard anchor control is hidden");
        }

        UIViewportStack stack = UIViewportStack.fromElement(element);
        Area viewport = stack.getViewport();

        if (viewport == null || !element.canBeRendered(viewport))
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.OUTSIDE_VIEWPORT, anchorId,
                "Dashboard anchor control is outside its viewport");
        }

        Area screenArea = new Area(
            stack.globalX(element.area.x),
            stack.globalY(element.area.y),
            element.area.w,
            element.area.h
        );
        Area screenViewport = new Area(
            stack.globalX(viewport.x),
            stack.globalY(viewport.y),
            viewport.w,
            viewport.h
        );

        if (!screenViewport.intersects(screenArea))
        {
            return this.unavailableAnchor(BBSDashboardAnchorStatus.OUTSIDE_VIEWPORT, anchorId,
                "Dashboard anchor control is outside its viewport");
        }

        return new BBSDashboardAnchorResult(
            BBSDashboardAnchorStatus.AVAILABLE,
            anchorId,
            screenArea.x,
            screenArea.y,
            screenArea.w,
            screenArea.h,
            true,
            this.isAnchorHittable(element),
            "Dashboard anchor resolved"
        );
    }

    public void installDashboardPanel(DashboardPanelContribution contribution) throws Exception
    {
        UIExtensionDashboardPanel replacement = new UIExtensionDashboardPanel(this, contribution);
        UIExtensionDashboardPanel current = this.extensionPanels.get(contribution.fullId());
        UIIcon button;

        if (current == null)
        {
            button = this.panels.registerPanel(replacement, contribution.spec().title(), contribution.spec().icon());
        }
        else
        {
            button = this.panels.replacePanel(current, replacement, contribution.spec().title(), contribution.spec().icon());
        }

        this.extensionPanels.put(contribution.fullId(), replacement);
        this.dashboardAnchors.remove(BBSDashboardAnchors.panelButton(contribution.fullId()));
        this.dashboardAnchors.remove(BBSDashboardAnchors.panelContent(contribution.fullId()));
        this.registerAnchor(BBSDashboardAnchors.panelButton(contribution.fullId()), null, () -> button);
        this.registerAnchor(BBSDashboardAnchors.panelContent(contribution.fullId()), contribution.fullId(), () -> replacement);
    }

    public void removeDashboardPanel(DashboardPanelContribution contribution)
    {
        UIExtensionDashboardPanel panel = this.extensionPanels.get(contribution.fullId());

        if (panel == null || panel.contribution() != contribution)
        {
            return;
        }

        this.extensionPanels.remove(contribution.fullId());
        this.dashboardAnchors.remove(BBSDashboardAnchors.panelButton(contribution.fullId()));
        this.dashboardAnchors.remove(BBSDashboardAnchors.panelContent(contribution.fullId()));
        this.panels.removePanel(panel, this.getPanel(UIFilmPanel.class));
    }

    public void installDashboardOverlay(DashboardOverlayContribution contribution) throws Exception
    {
        UIExtensionDashboardOverlay current = this.extensionOverlays.get(contribution.ownerId());

        if (current != null)
        {
            current.unmount();
        }

        UIExtensionDashboardOverlay replacement = new UIExtensionDashboardOverlay(
            this.addonOverlayLayer,
            contribution
        );

        this.extensionOverlays.put(contribution.ownerId(), replacement);

        if (this.dashboardOpen)
        {
            replacement.open();
        }
    }

    public void setDashboardOverlayVisible(DashboardOverlayContribution contribution, boolean visible)
    {
        UIExtensionDashboardOverlay overlay = this.extensionOverlays.get(contribution.ownerId());

        if (overlay != null && overlay.contribution() == contribution)
        {
            overlay.setAddonVisible(visible);
        }
    }

    public void removeDashboardOverlay(DashboardOverlayContribution contribution)
    {
        UIExtensionDashboardOverlay overlay = this.extensionOverlays.get(contribution.ownerId());

        if (overlay == null || overlay.contribution() != contribution)
        {
            return;
        }

        this.extensionOverlays.remove(contribution.ownerId());
        overlay.unmount();
    }

    private void registerAnchor(String anchorId, String panelId, Supplier<UIElement> element)
    {
        AnchorTarget previous = this.dashboardAnchors.putIfAbsent(anchorId, new AnchorTarget(panelId, element));

        if (previous != null)
        {
            throw new IllegalStateException("Duplicate Dashboard anchor id: " + anchorId);
        }
    }

    private String activePanelId()
    {
        UIDashboardPanel active = this.panels.panel;

        for (Map.Entry<String, UIDashboardPanel> entry : this.builtInPanels.entrySet())
        {
            if (entry.getValue() == active)
            {
                return entry.getKey();
            }
        }
        for (Map.Entry<String, UIExtensionDashboardPanel> entry : this.extensionPanels.entrySet())
        {
            if (entry.getValue() == active)
            {
                return entry.getKey();
            }
        }

        return null;
    }

    private BBSDashboardAnchorResult unavailableAnchor(
        BBSDashboardAnchorStatus status,
        String anchorId,
        String message
    )
    {
        return new BBSDashboardAnchorResult(status, anchorId, 0, 0, 0, 0, false, false, message);
    }

    private boolean isAnchorHittable(UIElement element)
    {
        if (UIOverlay.has(this.context))
        {
            return false;
        }

        UIElement current = element;

        while (current != null)
        {
            if (!current.isEnabled())
            {
                return false;
            }

            current = current.getParent();
        }

        return true;
    }

    @Override
    public void update()
    {
        super.update();

        if (this.panels.panel != null)
        {
            this.panels.panel.update();
        }
    }

    @Override
    protected void preRenderMenu(UIRenderingContext context)
    {
        if (!this.main.isVisible())
        {
            if (this.panels.panel != null)
            {
                this.panels.panel.renderPanelBackground(this.context);
            }

            return;
        }

        if (this.panels.panel != null && this.panels.panel.needsBackground())
        {
            this.background(context);
        }
        else
        {
            context.batcher.gradientVBox(0, 0, this.width, this.height / 8, Colors.A25, 0);
            context.batcher.gradientVBox(0, this.height - this.height / 8, this.width, this.height, 0, Colors.A25);
        }
    }

    private void background(UIRenderingContext context)
    {
        Link background = BBSSettings.backgroundImage.get();
        int color = BBSSettings.backgroundColor.get();

        if (background == null)
        {
            background = ThemeManager.current().background;
        }

        UIThemeBackdrop.renderBackground(context, background, color, this.width, this.height);
        UIThemeBackdrop.renderDecorations(context, this.width, this.height);
    }

    @Override
    public void startRenderFrame(float tickDelta)
    {
        super.startRenderFrame(tickDelta);

        if (this.panels.panel != null)
        {
            this.panels.panel.startRenderFrame(tickDelta);
        }
    }

    public void renderInWorld(IBbsWorldRenderContext context)
    {
        super.renderInWorld(context);

        if (this.panels.panel != null)
        {
            this.panels.panel.renderInWorld(context);
        }
    }

    private record AnchorTarget(String panelId, Supplier<UIElement> element)
    {}
}
