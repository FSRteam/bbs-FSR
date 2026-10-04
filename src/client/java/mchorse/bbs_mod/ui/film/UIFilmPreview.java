package mchorse.bbs_mod.ui.film;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.AudioRenderer;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.camera.data.Angle;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.client.render.multiview.ViewTargetSize;
import mchorse.bbs_mod.client.render.view.IrisViewBackend;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.settings.ui.UISettingsOverlayPanel;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.film.controller.UIMotionPathContextMenu;
import mchorse.bbs_mod.ui.film.controller.UIOnionSkinContextMenu;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.film.view.ViewDescriptor;
import mchorse.bbs_mod.ui.film.view.ViewNavigationState;
import mchorse.bbs_mod.ui.film.view.ViewNavigationGesture;
import mchorse.bbs_mod.ui.film.view.ViewFrameGeometry;
import mchorse.bbs_mod.ui.film.view.ViewPerformanceSettings;
import mchorse.bbs_mod.ui.film.view.FilmViewMenus;
import mchorse.bbs_mod.ui.film.view.FilmViewToolbarLayout;
import mchorse.bbs_mod.ui.film.utils.UICameraUtils;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageFolderOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UINumberOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyCodes;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.FFMpegUtils;
import mchorse.bbs_mod.utils.ScreenshotRecorder;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector2i;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public class UIFilmPreview extends UIElement
{
    private List<AudioClip> clips = new ArrayList<>();
    private UIFilmPanel panel;

    private final ViewDescriptor view;
    private final UIFilmController viewController;
    private final Camera displayedCamera = new Camera();
    private final ViewNavigationGesture navigationGesture = new ViewNavigationGesture();
    private long lastImageNanos;
    private double observedRefreshRate;
    private UIElement viewTools;
    private UIIcon cameraToggle;
    private UIIcon cameraLock;
    private UIIcon shaders;

    public UIElement icons;

    public UIIcon onionSkin;
    public UIIcon motionPath;
    public UIIcon plause;
    public UIIcon teleport;
    public UIIcon flight;
    public UIIcon control;
    public UIIcon perspective;
    public UIIcon recordReplay;
    public UIIcon recordVideo;

    /** Whether the icon bar is currently shown - see {@link #updateIconsVisibility(UIContext)} */
    private boolean iconsVisible = true;

    public UIFilmPreview(UIFilmPanel filmPanel)
    {
        this(filmPanel, false);
    }

    public UIFilmPreview(UIFilmPanel filmPanel, boolean secondaryView)
    {
        this(filmPanel, secondaryView ? filmPanel.getSecondaryView() : filmPanel.getPrimaryView());
    }

    public UIFilmPreview(UIFilmPanel filmPanel, ViewDescriptor view)
    {
        this.panel = filmPanel;
        this.view = view;
        this.viewController = view.isPrimary() ? filmPanel.getController() : new UIFilmController(filmPanel, false);
        this.viewController.bindPreview(this);

        this.icons = UI.row(0, 0);
        this.icons.row().resize();
        this.icons.relative(this).x(0.5F).y(1F).anchor(0.5F, 1F);

        /* Preview buttons */
        this.onionSkin = new UIIcon(Icons.ONION_SKIN, (b) -> this.openOnionSkin());
        this.onionSkin.tooltip(UIKeys.FILM_CONTROLLER_ONION_SKIN_TITLE);
        this.motionPath = new UIIcon(Icons.CURVES, (b) -> this.openMotionPath());
        this.motionPath.tooltip(UIKeys.FILM_CONTROLLER_MOTION_PATH_TITLE);
        this.plause = new UIIcon(() -> this.panel.isRunning() ? Icons.PAUSE : Icons.PLAY, (b) -> this.panel.togglePlayback());
        this.plause.tooltip(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_PLAUSE);
        this.plause.context((menu) ->
        {
            menu.action(Icons.PLAY, UIKeys.CAMERA_EDITOR_KEYS_EDITOR_PLAY_FILM, () ->
            {
                if (!this.panel.checkShowNoCamera())
                {
                    this.panel.dashboard.closeThisMenu();

                    Films.playFilm(this.panel.getData().getId(), true);
                }
            });

            menu.action(Icons.PAUSE, UIKeys.CAMERA_EDITOR_KEYS_EDITOR_FREEZE_PAUSED, !this.getViewController().isPaused(), () ->
            {
                this.getViewController().setPaused(!this.getViewController().isPaused());
            });
        });
        this.teleport = new UIIcon(Icons.MOVE_TO, (b) ->
        {
            Camera camera = this.getDisplayedCamera();
            float yaw = MathUtils.toDeg(camera.rotation.y);

            PlayerUtils.teleport(camera.position.x, camera.position.y, camera.position.z, yaw, yaw, MathUtils.toDeg(camera.rotation.x));
        });
        this.teleport.tooltip(UIKeys.FILM_TELEPORT_TITLE);
        this.teleport.context((menu) ->
        {
            menu.action(Icons.MOVE_TO, UIKeys.FILM_TELEPORT_CONTEXT_PLAYER, this.panel.playerToCamera, () -> this.panel.setPlayerToCamera(!this.panel.playerToCamera));
            menu.action(Icons.COPY, UIKeys.CAMERA_PANELS_CONTEXT_COPY_POSITION, () ->
            {
                Position current = new Position(this.view.getCamera());

                Map<String, Double> map = new LinkedHashMap<>();

                UICameraUtils.copyPoint(map, current.point);
                UICameraUtils.copyAngle(map, current.angle);

                Window.setClipboard(UICameraUtils.mapToString(map));
            });

            Map<String, Double> map = UICameraUtils.stringToMap(Window.getClipboard());

            if (!map.isEmpty())
            {
                menu.action(Icons.PASTE, UIKeys.CAMERA_PANELS_CONTEXT_PASTE_POSITION, () ->
                {
                    Position position = new Position();
                    Point point = UICameraUtils.createPoint(map);
                    Angle angle = UICameraUtils.createAngle(map);

                    if (point != null && angle != null)
                    {
                        position.point.set(point);
                        position.angle.set(angle);
                    }

                    this.panel.writeCameraPose(this.view, position);
                });
            }
        });
        this.flight = new UIIcon(Icons.PLANE, (b) ->
        {
            this.panel.activatePreview(this);

            this.panel.toggleFlight();
        });
        this.flight.tooltip(UIKeys.CAMERA_EDITOR_KEYS_MODES_FLIGHT);
        this.control = new UIIcon(Icons.POSE, (b) -> this.getViewController().toggleControl());
        this.control.tooltip(UIKeys.FILM_CONTROLLER_KEYS_TOGGLE_CONTROL);
        this.perspective = new UIIcon(this.getViewController()::getOrbitModeIcon, (b) -> this.getViewController().toggleOrbitMode());
        this.perspective.tooltip(UIKeys.FILM_CONTROLLER_KEYS_CHANGE_CAMERA_MODE);
        this.perspective.context((menu) ->
        {
            UIFilmController controller = this.getViewController();

            /* The mode button's context menu is intentionally limited to the
             * three orbit-camera actions. Camera modes themselves remain in the
             * left-click mode picker owned by UIFilmController. */
            menu.action(Icons.MOVE_TO, UIKeys.FILM_REPLAY_ORBIT_TELEPORT_TO_RECORDING, controller::teleportOrbitPivotToReplay);
            menu.action(Icons.LINK, UIKeys.FILM_CONTROLLER_KEYS_ATTACH_ORBIT, controller.orbit.isAttached(), controller::toggleOrbitAttachment);
            menu.action(Icons.FRUSTUM, UIKeys.FILM_CONTROLLER_KEYS_TOGGLE_ORTHO, controller.orbit.isOrtho(), controller.orbit::toggleOrtho);
        });
        this.recordReplay = new UIIcon(Icons.SPHERE, (b) -> this.getViewController().pickRecording());
        this.recordReplay.tooltip(UIKeys.FILM_REPLAY_RECORD);
        this.recordReplay.context((menu) ->
        {
            menu.action(Icons.DOWNLOAD, UIKeys.FILM_CONTROLLER_KEYS_TOGGLE_INSTANT_KEYFRAMES, this.getViewController().isInstantKeyframes(), () ->
            {
                this.getViewController().toggleInstantKeyframes();
            });

            menu.action(Icons.MOVE_TO, UIKeys.FILM_REPLAY_TELEPORT_TO_PLAYER, () -> this.getViewController().insertPlayerFrame());
        });
        this.recordVideo = new UIIcon(Icons.VIDEO_CAMERA, (b) ->
        {
            if (this.panel.checkShowNoCamera())
            {
                return;
            }

            if (!FFMpegUtils.checkFFMPEG())
            {
                UIMessageOverlayPanel panel = new UIMessageOverlayPanel(UIKeys.GENERAL_WARNING, UIKeys.GENERAL_FFMPEG_ERROR_DESCRIPTION);
                UIIcon guide = new UIIcon(Icons.HELP, (bb) -> UIUtils.openWebLink(UIKeys.GENERAL_FFMPEG_ERROR_GUIDE_LINK.get()));

                guide.tooltip(UIKeys.GENERAL_FFMPEG_ERROR_GUIDE, Direction.LEFT);
                panel.icons.add(guide);

                UIOverlay.addOverlay(this.getContext(), panel);

                return;
            }

            int duration = this.panel.getData().calculateDuration();

            BBSRendering.cancelPendingExportResolutionActions();

            if (!this.panel.recorder.reserveExport())
            {
                return;
            }

            boolean scheduled = false;

            try
            {
                UIFilmPanel.applyExportSizeToBBS();
                BBSRendering.scheduleAfterNextExportFrame(
                    this::isExportOwnerValid,
                    () ->
                    {
                        if (!this.panel.recorder.tryStartRecording(duration, BBSRendering.getTexture().id, BBSRendering.getVideoWidth(), BBSRendering.getVideoHeight()))
                        {
                            this.panel.restorePreviewSize();
                        }
                    },
                    this::cancelPendingVideoExport
                );
                scheduled = true;
            }
            finally
            {
                if (!scheduled)
                {
                    this.cancelPendingVideoExport();
                }
            }
        });
        this.recordVideo.tooltip(UIKeys.CAMERA_TOOLTIPS_RECORD);
        this.recordVideo.context((menu) ->
        {
            menu.action(Icons.CAMERA, UIKeys.FILM_SCREENSHOT, () ->
            {
                ScreenshotRecorder recorder = BBSModClient.getScreenshotRecorder();
                File output = Window.isAltPressed() ? null : recorder.getScreenshotFile();

                BBSRendering.cancelPendingExportResolutionActions();
                boolean scheduled = false;

                try
                {
                    UIFilmPanel.applyExportSizeToBBS();
                    BBSRendering.scheduleAfterNextExportFrame(
                        this::isPanelOwnerValid,
                        () ->
                        {
                            Texture texture = BBSRendering.getTexture();
                            int w = BBSRendering.getVideoWidth();
                            int h = BBSRendering.getVideoHeight();
                            recorder.takeScreenshot(output, texture.id, w, h);
                            this.panel.restorePreviewSize();

                            UIBaseMenu currentMenu = UIScreen.getCurrentMenu();
                            if (currentMenu != null)
                            {
                                UIMessageFolderOverlayPanel overlayPanel = new UIMessageFolderOverlayPanel(
                                    UIKeys.FILM_SCREENSHOT_TITLE,
                                    UIKeys.FILM_SCREENSHOT_DESCRIPTION,
                                    recorder.getScreenshots()
                                );
                                UIOverlay.addOverlay(currentMenu.context, overlayPanel);
                            }
                        },
                        this::restorePreviewIfOwned
                    );
                    scheduled = true;
                }
                finally
                {
                    if (!scheduled)
                    {
                        this.restorePreviewIfOwned();
                    }
                }
            });

            menu.action(Icons.FILM, UIKeys.CAMERA_TOOLTIPS_OPEN_VIDEOS, () -> this.panel.recorder.openMovies());
            menu.action(Icons.GEAR, UIKeys.CAMERA_TOOLTIPS_OPEN_VIDEO_SETTINGS, () ->
            {
                UISettingsOverlayPanel panel = new UISettingsOverlayPanel();

                panel.showCategory("bbs", "video");
                UIOverlay.addOverlay(this.getContext(), panel, 430, 380);
            });

            menu.action(Icons.VIDEO_CAMERA, UIKeys.FILM_RENDER_QUEUE, this::exportQueueFromTabs);
            menu.action(Icons.SOUND, UIKeys.FILM_RENDER_AUDIO, this::renderAudio);
            menu.action(Icons.REFRESH, UIKeys.FILM_RESET_REPLAYS, this.panel.recorder.resetReplays, () ->
            {
                this.panel.recorder.resetReplays = !this.panel.recorder.resetReplays;
            });
        });

        this.icons.add(this.onionSkin, this.motionPath, this.plause, this.teleport, this.flight, this.control, this.perspective, this.recordReplay, this.recordVideo);
        this.add(this.icons);
        this.setupViewTools();
    }

    private void setupViewTools()
    {
        UIIcon binding = new UIIcon(Icons.FRUSTUM, button -> this.getContext().replaceContextMenu(menu -> FilmViewMenus.cameras(this.panel, this, menu)));

        binding.tooltip(UIKeys.FILM_VIEW_CHOOSE_CAMERA);
        binding.context(menu -> FilmViewMenus.cameraActions(this.panel, this, menu));
        this.cameraToggle = new UIIcon(Icons.CAMERA, button ->
        {
            if (this.view.getNavigation().isInCameraView()) this.exitCameraView();
            else this.enterCameraView();
        });
        this.cameraToggle.tooltip(UIKeys.FILM_VIEW_CAMERA);
        this.cameraLock = new UIIcon(() -> this.view.getNavigation().isLockCameraToView() ? Icons.LOCKED : Icons.UNLOCKED, button ->
        {
            this.cancelViewInteraction();
            ViewNavigationState navigation = this.view.getNavigation();

            navigation.setLockCameraToView(!navigation.isLockCameraToView());
            this.panel.saveViewSettings();
        });
        this.cameraLock.tooltip(UIKeys.FILM_VIEW_LOCK);
        this.shaders = new UIIcon(Icons.LIGHT, button ->
        {
            this.view.setShadersEnabled(!this.view.isShadersEnabled());
            this.requestRefresh();
            this.panel.saveViewSettings();
        });
        this.shaders.tooltip(UIKeys.FILM_VIEW_SHADERS_ON);
        UIIcon options = new UIIcon(Icons.MORE, button -> this.getContext().replaceContextMenu(this::viewOptions));

        options.tooltip(UIKeys.FILM_VIEW_OPTIONS);
        this.viewTools = UI.row(0, 0, binding, this.cameraToggle, this.cameraLock, this.shaders, options);
        this.viewTools.row().resize();
        this.viewTools.relative(this).x(1F, -4).y(4).anchorX(1F);
        this.add(this.viewTools);
    }

    @Override
    protected void afterResizeApplied()
    {
        super.afterResizeApplied();
        FilmViewToolbarLayout.fit(this, this.icons, this.viewTools);
    }

    private void viewOptions(ContextMenuManager menu)
    {
        menu.action(Icons.CAMERA, UIKeys.FILM_VIEW_CHOOSE_CAMERA,
            () -> this.getContext().replaceContextMenu(cameras -> FilmViewMenus.cameras(this.panel, this, cameras)));
        menu.action(Icons.EDITOR, UIKeys.FILM_VIEW_CAMERA_ACTIONS,
            () -> this.getContext().replaceContextMenu(actions -> FilmViewMenus.cameraActions(this.panel, this, actions)));
        menu.action(Icons.GEAR, UIKeys.FILM_PREVIEW_PERFORMANCE,
            () -> this.getContext().replaceContextMenu(this::performanceOptions));

        if (this.panel.canAddPreview())
        {
            menu.action(Icons.ADD, UIKeys.FILM_VIEW_ADD_PREVIEW, this.panel::addPreview);
        }

        if (!this.isPrimaryView())
        {
            menu.action(Icons.CLOSE, UIKeys.FILM_VIEW_CLOSE_PREVIEW, () -> this.panel.removePreview(this));
        }

        menu.action(Icons.REFRESH, UIKeys.FILM_VIEW_RESET_FRAME, () ->
        {
            this.view.getNavigation().resetFrame();
            this.panel.saveViewSettings();
        });
        menu.action(Icons.VISIBLE, UIKeys.FILM_VIEW_CAMERA_NAME, this.view.isShowName(), () -> this.view.setShowName(!this.view.isShowName()));
        menu.action(Icons.FRUSTUM, UIKeys.FILM_VIEW_CAMERA_FRAME, this.view.isShowFrame(), () -> this.view.setShowFrame(!this.view.isShowFrame()));
        menu.action(Icons.FRUSTUM, UIKeys.FILM_VIEW_RULE_OF_THIRDS, this.view.isShowThirds(), () -> this.view.setShowThirds(!this.view.isShowThirds()));
        menu.action(Icons.FRUSTUM, UIKeys.FILM_VIEW_CENTER_LINES, this.view.isShowCenter(), () -> this.view.setShowCenter(!this.view.isShowCenter()));
        menu.action(Icons.FRUSTUM, UIKeys.FILM_VIEW_CROSSHAIR, this.view.isShowCrosshair(), () -> this.view.setShowCrosshair(!this.view.isShowCrosshair()));
        menu.action(Icons.VIDEO_CAMERA, UIKeys.FILM_VIEW_CAMERA_OBJECTS, this.view.isShowCameraObjects(), () ->
        {
            this.view.setShowCameraObjects(!this.view.isShowCameraObjects());
            this.requestRefresh();
        });
        menu.action(Icons.REFRESH, UIKeys.FILM_VIEW_REFRESH, () ->
        {
            this.view.invalidateHistory();
            this.requestRefresh();
        });
        menu.onClose(event -> this.panel.saveViewSettings());
    }

    private void performanceOptions(ContextMenuManager menu)
    {
        String percent = String.format(Locale.ROOT, "%.0f%%", this.panel.getAuxiliaryBudgetFraction() * 100D);

        menu.action(Icons.GEAR, UIKeys.FILM_PREVIEW_BUDGET.format(percent), this::editPerformanceBudget);

        if (!this.isPrimaryView())
        {
            menu.action(Icons.REFRESH, UIKeys.FILM_PREVIEW_RESOLUTION_AUTO, this.view.getResolutionWidth() == 0,
                () -> this.setResolutionWidth(0));

            for (int width : new int[] {320, 480, 640, 960, 1280, 1920})
            {
                ViewTargetSize size = ViewPerformanceSettings.resolution(width, 0, 0,
                    BBSSettings.videoSettings.width.get(), BBSSettings.videoSettings.height.get());

                menu.action(Icons.FRUSTUM, UIKeys.FILM_PREVIEW_RESOLUTION_PRESET.format(size.width(), size.height()),
                    this.view.getResolutionWidth() == width, () -> this.setResolutionWidth(width));
            }

            menu.action(Icons.GEAR, UIKeys.FILM_PREVIEW_RESOLUTION_HINT, this::editPerformanceBudget);
        }

        menu.action(Icons.ARROW_LEFT, UIKeys.FILM_PREVIEW_BACK,
            () -> this.getContext().replaceContextMenu(this::viewOptions));
    }

    private void editPerformanceBudget()
    {
        Film film = this.panel.getData();
        UINumberOverlayPanel overlay = new UINumberOverlayPanel(UIKeys.FILM_PREVIEW_BUDGET_TITLE,
            UIKeys.FILM_PREVIEW_BUDGET_DESCRIPTION,
            value ->
            {
                if (this.panel.getData() == film)
                {
                    this.panel.setAuxiliaryBudgetFraction(value / 100D);
                }
            });

        overlay.value.limit(ViewPerformanceSettings.MINIMUM_BUDGET_FRACTION * 100D,
            ViewPerformanceSettings.MAXIMUM_BUDGET_FRACTION * 100D).integer().setValue(this.panel.getAuxiliaryBudgetFraction() * 100D);
        UIOverlay.addOverlay(this.getContext(), overlay, 360, 210);
    }

    private void setResolutionWidth(int width)
    {
        this.view.setResolutionWidth(width);
        this.updateRenderDemand();
        this.requestRefresh();
        this.panel.saveViewSettings();
    }

    private boolean isPanelOwnerValid()
    {
        Minecraft mc = Minecraft.getInstance();
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        return mc.level != null
            && dashboard != null
            && UIScreen.getCurrentMenu() == dashboard
            && dashboard.getPanels().panel == this.panel
            && this.panel.getContext() != null;
    }

    public ViewDescriptor getViewDescriptor()
    {
        return this.view;
    }

    public boolean isPrimaryView()
    {
        return this.view.isPrimary();
    }

    public UIFilmController getViewController()
    {
        return this.viewController;
    }

    public Camera getDisplayedCamera()
    {
        ViewRenderState state = BBSRendering.getViewRenderState(this.view.getId());

        if (state != null && state.hasFrame())
        {
            this.displayedCamera.copy(state.getCamera());
            this.displayedCamera.view.set(state.getLastView());
            this.displayedCamera.projection.set(state.getLastProjection());
        }
        else
        {
            this.displayedCamera.copy(this.view.getCamera());
        }

        return this.displayedCamera;
    }

    public void requestRefresh()
    {
        this.view.requestRefresh(BBSRendering.getSceneFrameId());
        ViewRenderState state = BBSRendering.getViewRenderState(this.view.getId());

        if (state != null)
        {
            state.requestRefresh();
        }
    }

    public void updateRenderDemand()
    {
        if (this.isPrimaryView())
        {
            this.view.setSize(BBSRendering.getVideoWidth(), BBSRendering.getVideoHeight());
        }
        else if (this.view.isVisible())
        {
            float scale = BBSModClient.getGUIScale();
            ViewTargetSize size = ViewPerformanceSettings.resolution(this.view.getResolutionWidth(),
                Math.max(2, Math.round(this.area.w * scale)), Math.max(2, Math.round(this.area.h * scale)),
                BBSSettings.videoSettings.width.get(), BBSSettings.videoSettings.height.get());

            this.view.setSize(size.width(), size.height());
            this.view.setRefreshRate(this.panel.getActivePreview() == this ? 30 : 15);
        }
    }

    public MapType getViewSettings()
    {
        MapType settings = this.view.toData();

        settings.put("orbit", this.getViewController().orbit.toData());

        return settings;
    }

    public void loadViewSettings(MapType settings)
    {
        this.cancelViewInteraction();
        this.getViewController().releaseViewResources();
        this.getViewController().unpinMotionPath();
        this.view.fromData(settings);
        this.getViewController().orbit.fromData(settings.getMap("orbit"));

        if (settings.getMap("navigation").isEmpty())
        {
            Camera camera = this.panel.getWorldCamera();

            this.view.getCamera().copy(camera);
            this.view.getNavigation().initialize(camera);
            this.view.getNavigation().enterCameraView(camera, new Position(camera));
        }

        ViewNavigationState navigation = this.view.getNavigation();

        this.getViewController().restorePov(navigation.isInCameraView() ? UIFilmController.CAMERA_MODE_CAMERA : navigation.getFreeMode());
        this.view.invalidateHistory();
    }

    public void bindCamera(String cameraId, boolean followOutput)
    {
        this.cancelViewInteraction();
        this.view.setCameraId(cameraId);
        this.view.setFollowOutput(followOutput);
        this.enterCameraView();
        this.requestRefresh();
        this.panel.saveViewSettings();
    }

    public void enterCameraView()
    {
        this.panel.activatePreview(this);
        ViewNavigationState navigation = this.view.getNavigation();

        if (!navigation.isInCameraView())
        {
            navigation.setFreeMode(this.getViewController().getPovMode());
            navigation.enterCameraView(this.getDisplayedCamera(), null);
        }

        this.getViewController().restorePov(UIFilmController.CAMERA_MODE_CAMERA);
        this.view.invalidateHistory();
        this.panel.saveViewSettings();
    }

    public void exitCameraView()
    {
        this.cancelViewInteraction();
        ViewNavigationState navigation = this.view.getNavigation();

        navigation.exitCameraView(this.view.getCamera());
        this.getViewController().restorePov(navigation.getFreeMode());
        this.view.invalidateHistory();
        this.panel.saveViewSettings();
    }

    public void onCameraModeChanged(int previous, int next)
    {
        this.cancelViewInteraction();
        ViewNavigationState navigation = this.view.getNavigation();

        if (next == UIFilmController.CAMERA_MODE_CAMERA)
        {
            navigation.setFreeMode(previous);
            navigation.enterCameraView(this.getDisplayedCamera(), null);
        }
        else
        {
            if (navigation.isInCameraView())
            {
                navigation.exitCameraView(this.view.getCamera());
            }

            navigation.setFreeMode(next);
        }

        this.view.invalidateHistory();
        this.panel.saveViewSettings();
    }

    public void cancelViewInteraction()
    {
        this.panel.cancelFlight(this);

        if (this.navigationGesture.isActive())
        {
            this.navigationGesture.cancel();
        }

        if (this.viewController != null)
        {
            this.viewController.resetViewInteraction();
        }
    }

    public boolean isInsideFrame(UIContext context)
    {
        Area frame = this.getViewport();

        return this.area.isInside(context) && frame.isInside(context);
    }

    private boolean isExportOwnerValid()
    {
        return this.isPanelOwnerValid() && !this.panel.recorder.isExporting();
    }

    private void cancelPendingVideoExport()
    {
        this.panel.recorder.cancelPendingExport();
        this.restorePreviewIfOwned();
    }

    private void restorePreviewIfOwned()
    {
        if (this.isPanelOwnerValid())
        {
            this.panel.restorePreviewSize();
        }
    }

    /** Render one export-resolution frame through the existing screenshot path. */
    public void snapshotToFile(File output, Runnable onDone)
    {
        this.snapshotToFile(output, onDone, null, null);
    }

    /** Render a frame and notify the caller when the delayed frame is cancelled. */
    public void snapshotToFile(File output, Runnable onDone, Runnable onCancelled)
    {
        this.snapshotToFile(output, onDone, onCancelled, null);
    }

    /**
     * Render one frame while the supplied owner remains current. This prevents
     * a delayed cover capture from crossing a film tab or dashboard switch.
     */
    public void snapshotToFile(File output, Runnable onDone, Runnable onCancelled, BooleanSupplier ownerValid)
    {
        ScreenshotRecorder recorder = BBSModClient.getScreenshotRecorder();

        BBSRendering.cancelPendingExportResolutionActions();
        boolean scheduled = false;

        try
        {
            UIFilmPanel.applyExportSizeToBBS();
            BBSRendering.scheduleAfterNextExportFrame(
                () -> this.isPanelOwnerValid() && (ownerValid == null || ownerValid.getAsBoolean()),
                () ->
                {
                    Texture texture = BBSRendering.getTexture();
                    int w = BBSRendering.getVideoWidth();
                    int h = BBSRendering.getVideoHeight();

                    recorder.takeScreenshot(output, texture.id, w, h);
                    this.panel.restorePreviewSize();

                    if (onDone != null)
                    {
                        onDone.run();
                    }
                },
                () ->
                {
                    this.restorePreviewIfOwned();

                    if (onCancelled != null)
                    {
                        onCancelled.run();
                    }
                }
            );
            scheduled = true;
        }
        finally
        {
            if (!scheduled)
            {
                this.restorePreviewIfOwned();

                if (onCancelled != null)
                {
                    onCancelled.run();
                }
            }
        }
    }

    public void openOnionSkin()
    {
        this.getContext().replaceContextMenu(new UIOnionSkinContextMenu(this.panel, this.getViewController().getOnionSkin()));
    }

    public void openMotionPath()
    {
        this.getContext().replaceContextMenu(new UIMotionPathContextMenu(this.panel, this.getViewController().getMotionPath()));
    }

    private void exportQueueFromTabs()
    {
        if (this.panel.checkShowNoCamera())
        {
            return;
        }

        if (!FFMpegUtils.checkFFMPEG())
        {
            UIMessageOverlayPanel panel = new UIMessageOverlayPanel(UIKeys.GENERAL_WARNING, UIKeys.GENERAL_FFMPEG_ERROR_DESCRIPTION);
            UIIcon guide = new UIIcon(Icons.HELP, (bb) -> UIUtils.openWebLink(UIKeys.GENERAL_FFMPEG_ERROR_GUIDE_LINK.get()));

            guide.tooltip(UIKeys.GENERAL_FFMPEG_ERROR_GUIDE, Direction.LEFT);
            panel.icons.add(guide);

            UIOverlay.addOverlay(this.getContext(), panel);

            return;
        }

        this.panel.startQueueExportFromOpenTabs();
    }

    private void renderAudio()
    {
        Clips camera = this.panel.getData().camera;
        List<AudioClip> audioClips = camera.getClips(AudioClip.class);

        String name = StringUtils.createTimestampFilename() + ".wav";
        File videos = BBSRendering.getVideoFolder();
        UIContext context = this.getContext();
        Vector2i range = BBSSettings.editorLoop.get() ? this.panel.getLoopingRange() : new Vector2i();

        if (AudioRenderer.renderAudio(new File(videos, name), audioClips, this.panel.getData().calculateDuration(), 48000, TimeUtils.toSeconds(range.x), TimeUtils.toSeconds(range.y)))
        {
            UIOverlay.addOverlay(context, new UIMessageFolderOverlayPanel(UIKeys.GENERAL_SUCCESS, UIKeys.FILM_RENDER_AUDIO_SUCCESS, videos));
        }
        else
        {
            UIOverlay.addOverlay(context, new UIMessageOverlayPanel(UIKeys.GENERAL_ERROR, UIKeys.FILM_RENDER_AUDIO_ERROR));
        }
    }

    public Area getViewport()
    {
        ViewNavigationState navigation = this.view.getNavigation();
        boolean cameraView = navigation.isInCameraView();
        ViewFrameGeometry frame = ViewFrameGeometry.fit(this.area.x, this.area.y, this.area.w, this.area.h,
            BBSSettings.videoSettings.width.get(), BBSSettings.videoSettings.height.get(),
            cameraView ? navigation.getFrameZoom() : 1F,
            cameraView ? navigation.getFramePanX() : 0F,
            cameraView ? navigation.getFramePanY() : 0F);
        Area area = new Area();

        area.setSize(frame.width(), frame.height());
        area.setPos(frame.x(), frame.y());

        return area;
    }

    @Override
    protected IUIElement childrenMouseClicked(UIContext context)
    {
        if (this.area.isInside(context))
        {
            this.getViewController().cancelPendingViewportPick();
            this.panel.activatePreview(this);
        }

        return super.childrenMouseClicked(context);
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (this.isInsideFrame(context))
        {
            this.panel.activatePreview(this);
            this.requestRefresh();
            ViewNavigationState navigation = this.view.getNavigation();
            boolean cameraView = navigation.isInCameraView();

            if (!this.panel.recorder.isExporting() && !this.viewController.isViewFlying()
                && ((cameraView && context.mouseButton == 2)
                    || (cameraView && navigation.isLockCameraToView() && context.mouseButton == 1)
                    || (!cameraView && this.viewController.getPovMode() == UIFilmController.CAMERA_MODE_FREE && context.mouseButton > 0)))
            {
                return this.beginNavigation(context, cameraView && !navigation.isLockCameraToView(), cameraView && navigation.isLockCameraToView());
            }

            Area area = this.getViewport();
            boolean[] handled = {false};

            context.menu.runWithPreservedMouseCapture(
                this,
                () ->
                {
                    handled[0] = this.getViewController().orbitGizmo.mouseClicked(context, area)
                        || this.getViewController().clickViewport(context);
                }
            );

            return handled[0];
        }

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        if (this.navigationGesture.isOwnedBy(context.mouseButton))
        {
            this.updateNavigation(context);
            ViewNavigationGesture.Completion completion = this.navigationGesture.release(context.mouseButton, this.navigationTarget());

            if (completion.cameraPose() != null)
            {
                this.panel.writeCameraPose(this.view, completion.cameraPose());
            }

            this.panel.saveViewSettings();
            this.requestRefresh();

            if (completion.contextClick())
            {
                this.getViewController().clickViewportOnRelease(context);
            }

            return true;
        }

        boolean handled = this.getViewController().releaseViewportGesture(context);

        if (handled)
        {
            this.panel.saveViewSettings();
            this.requestRefresh();
        }

        return handled || super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        /* The preview owns the captured press in the UI tree, while orbit and
         * gizmo state live on the sibling controller. Cancellation must reach
         * that controller without synthesizing a committing release. The
         * controller's generation checks make the root-wide second traversal
         * idempotent. */
        if (this.navigationGesture.isOwnedBy(context.mouseButton))
        {
            this.cancelViewInteraction();
        }

        this.getViewController().cancelViewportGesture(context);
        super.subMouseCanceled(context);
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.isInsideFrame(context))
        {
            this.panel.activatePreview(this);

            if (this.view.getNavigation().isInCameraView())
            {
                this.view.getNavigation().zoomFrame((float) context.mouseWheel);
                this.panel.saveViewSettings();

                return true;
            }

            this.requestRefresh();

            if (this.getViewController().getPovMode() == UIFilmController.CAMERA_MODE_ORBIT)
            {
                boolean zoomed = this.getViewController().zoomOrbit(context.mouseWheel);

                if (zoomed)
                {
                    this.panel.saveViewSettings();
                }

                return zoomed;
            }

            Position pose = this.view.getNavigation().getFreePose();
            Vector3f delta = this.getDisplayedCamera().getLookDirection().mul((float) context.mouseWheel);

            pose.point.x += delta.x;
            pose.point.y += delta.y;
            pose.point.z += delta.z;
            this.panel.saveViewSettings();

            return true;
        }

        return super.subMouseScrolled(context);
    }

<<<<<<< HEAD
    private boolean beginNavigation(UIContext context, boolean frame, boolean camera)
    {
        if (camera && this.panel.isRunning())
        {
            this.panel.togglePlayback();
        }

        ViewNavigationGesture.Mode mode = frame ? ViewNavigationGesture.Mode.FRAME
            : camera ? ViewNavigationGesture.Mode.CAMERA : ViewNavigationGesture.Mode.FREE;

        return this.navigationGesture.begin(context.mouseButton, context.mouseX, context.mouseY, mode,
            this.view.getNavigation(), this.getDisplayedCamera(), this.navigationTarget());
    }

    private ViewNavigationGesture.Target navigationTarget()
    {
        return new ViewNavigationGesture.Target(this.panel.getData(), this.view.resolveCameraId(), this.panel.getCursor());
    }

    private void updateNavigation(UIContext context)
    {
        this.navigationGesture.drag(context.mouseX, context.mouseY, this.area.w, this.area.h, Window.isShiftPressed());
        this.requestRefresh();
    }

=======
>>>>>>> origin/master
    /**
     * The icon bar can be set to stay out of the way until the mouse comes over the
     * preview. A context menu opened from an icon takes the mouse out of the preview,
     * so while any menu is up the bar keeps the visibility it had when it opened.
     */
    private void updateIconsVisibility(UIContext context)
    {
        if (!BBSSettings.editorPreviewIconsAutoHide.get())
        {
            this.iconsVisible = true;
        }
        else if (!context.hasContextMenu())
        {
            this.iconsVisible = this.area.isInside(context.mouseX, context.mouseY);
        }

        this.icons.setVisible(this.iconsVisible);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.navigationGesture.isActive())
        {
            if (!Window.isMouseButtonPressed(this.navigationGesture.getButton()) || context.hasContextMenu() || UIOverlay.has(context))
            {
                this.cancelViewInteraction();
            }
            else
            {
                this.updateNavigation(context);
            }
        }

        context.batcher.clip(this.area, context);

        try
        {
            this.renderPreview(context);
        }
        finally
        {
            context.batcher.unclip(context);
        }
    }

    private void renderPreview(UIContext context)
    {
        Texture texture = BBSRendering.getViewTexture(this.view.getId());
        Area area = this.getViewport();
        this.getViewController().updateSoundGuideDrag(context);
        context.batcher.flush();
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());

        if (texture != null)
        {
            if (this.isPrimaryView())
            {
                context.batcher.surfaceBox(BBSRenderSurfaceKind.FILM_PREVIEW, texture.id, Colors.WHITE,
                    area.x, area.y, area.w, area.h, 0, texture.height, texture.width, 0, texture.width, texture.height);
            }
            else
            {
                context.batcher.texturedBox(texture.id, Colors.WHITE,
                    area.x, area.y, area.w, area.h, 0, texture.height, texture.width, 0, texture.width, texture.height);
            }
        }

        if (!this.getViewController().orbitGizmo.isActive())
        {
            this.renderCursor(context);
        }

        boolean needGuides = this.view.isShowThirds() || this.view.isShowCenter() || this.view.isShowCrosshair();
        if (needGuides)
        {
            if (this.view.isShowThirds())
            {
                int guidesColor = BBSSettings.editorGuidesColor.get();

                context.batcher.box(area.x + area.w / 3 - 1, area.y, area.x + area.w / 3, area.y + area.h, guidesColor);
                context.batcher.box(area.x + area.w - area.w / 3, area.y, area.x + area.w - area.w / 3 + 1, area.y + area.h, guidesColor);

                context.batcher.box(area.x, area.y + area.h / 3 - 1, area.x + area.w, area.y + area.h / 3, guidesColor);
                context.batcher.box(area.x, area.y + area.h - area.h / 3, area.x + area.w, area.y + area.h - area.h / 3 + 1, guidesColor);
            }

            if (this.view.isShowCenter())
            {
                int guidesColor = BBSSettings.editorGuidesColor.get();
                int x = area.mx();
                int y = area.my();

                context.batcher.box(area.x, y, area.ex(), y + 1, guidesColor);
                context.batcher.box(x, area.y, x + 1, area.ey(), guidesColor);
            }

            if (this.view.isShowCrosshair())
            {
                int x = area.mx() + 1;
                int y = area.my() + 1;

                context.batcher.box(x - 4, y - 1, x + 3, y, Colors.setA(BBSSettings.textColor(), 0.5F));
                context.batcher.box(x - 1, y - 4, x, y + 3, Colors.setA(BBSSettings.textColor(), 0.5F));
            }
        }

        if (this.view.isShowFrame() && this.view.getNavigation().isInCameraView())
        {
            context.batcher.outline(area.x, area.y, area.ex(), area.ey(), BBSSettings.mutedTextColor());
        }

        this.getViewController().renderHUD(context, area);
        this.renderViewStatus(context);

        if (this.panel.replayEditor.isVisible() && BBSSettings.audioWaveformVisibleInPreview.get())
        {
            RunnerCameraController runner = this.panel.getRunner();
            int w = (int) (area.w * BBSSettings.audioWaveformWidth.get());
            int x = area.x(0.5F, w);
            float tick = this.panel.getCursor() + (runner.isRunning() ? context.getTransition() : 0);

            this.clips.clear();

            for (Clip clip : this.panel.getData().camera.get())
            {
                if (clip instanceof AudioClip)
                {
                    this.clips.add((AudioClip) clip);
                }
            }

            int h = BBSSettings.audioWaveformHeight.get();

            if (BBSSettings.audioWaveformPreviewCombined.get())
            {
                AudioRenderer.renderPreviewCombined(context.batcher, this.clips, tick, x, area.y + 10, w, h, context.menu.width, context.menu.height);
            }
            else
            {
                AudioRenderer.renderAll(context.batcher, this.clips, tick, x, area.y + 10, w, h, context.menu.width, context.menu.height);
            }
        }

        this.updateIconsVisibility(context);

        if (this.iconsVisible)
        {
            Area a = this.icons.area;

            /* Render icon bar */
<<<<<<< HEAD
            int barShade = BBSSettings.color(BBSSettings.chromeSurface(), Colors.A50);
            context.batcher.gradientVBox(a.x, a.y, a.ex(), a.ey(), 0, barShade);

            if (this.getViewController().isViewFlying()) UIDashboardPanels.renderHighlight(context.batcher, this.flight.area, Direction.BOTTOM);
            if (this.getViewController().isControlling()) UIDashboardPanels.renderHighlight(context.batcher, this.control.area, Direction.BOTTOM);
            if (this.getViewController().isRecording()) UIDashboardPanels.renderHighlight(context.batcher, this.recordReplay.area, Direction.BOTTOM);
            if (this.panel.recorder.isRecording()) UIDashboardPanels.renderHighlight(context.batcher, this.recordVideo.area, Direction.BOTTOM);
            if (this.getViewController().getOnionSkin().enabled.get()) UIDashboardPanels.renderHighlight(context.batcher, this.onionSkin.area, Direction.BOTTOM);
            if (this.getViewController().getMotionPath().enabled.get()) UIDashboardPanels.renderHighlight(context.batcher, this.motionPath.area, Direction.BOTTOM);
            if (this.getViewController().isControlling())
=======
            int barShade = BBSSettings.isLightTheme() ? (Colors.A50 | 0xFFFFFF) : Colors.A50;
            context.batcher.gradientVBox(a.x, a.y, a.ex(), a.ey(), 0, barShade);

            if (this.panel.isFlying()) UIDashboardPanels.renderHighlight(context.batcher, this.flight.area, Direction.BOTTOM);
            if (this.panel.getController().isControlling()) UIDashboardPanels.renderHighlight(context.batcher, this.control.area, Direction.BOTTOM);
            if (this.panel.getController().isRecording()) UIDashboardPanels.renderHighlight(context.batcher, this.recordReplay.area, Direction.BOTTOM);
            if (this.panel.recorder.isRecording()) UIDashboardPanels.renderHighlight(context.batcher, this.recordVideo.area, Direction.BOTTOM);
            if (this.panel.getController().getOnionSkin().enabled.get()) UIDashboardPanels.renderHighlight(context.batcher, this.onionSkin.area, Direction.BOTTOM);
            if (this.panel.getController().getMotionPath().enabled.get()) UIDashboardPanels.renderHighlight(context.batcher, this.motionPath.area, Direction.BOTTOM);
            if (this.panel.getController().isControlling())
>>>>>>> origin/master
            {
                String s = UIKeys.FILM_CONTROLLER_CONTROL_MODE_TOOLTIP.format(KeyCodes.getName(Keys.FILM_CONTROLLER_TOGGLE_CONTROL.getMainKey())).get();
                int w = context.batcher.getFont().getWidth(s);
                int height = context.batcher.getFont().getHeight();

                context.batcher.textCard(s, a.mx(w), a.y - height - 5);
            }
        }

        this.cameraToggle.active(this.view.getNavigation().isInCameraView());
        this.cameraToggle.tooltip(this.view.getNavigation().isInCameraView() ? UIKeys.FILM_VIEW_EXIT_CAMERA : UIKeys.FILM_VIEW_CAMERA);
        this.cameraLock.active(this.view.getNavigation().isLockCameraToView());
        this.cameraLock.tooltip(this.view.getNavigation().isLockCameraToView() ? UIKeys.FILM_VIEW_UNLOCK : UIKeys.FILM_VIEW_LOCK);
        this.shaders.active(this.view.isShadersEnabled());
        super.render(context);
    }

    private void renderViewStatus(UIContext context)
    {
        ViewRenderState state = BBSRendering.getViewRenderState(this.view.getId());
        Film film = this.panel.getData();
        int color = BBSSettings.mutedTextColor();
        String status = UIKeys.FILM_PREVIEW_STATUS_PREPARING.get();

        if (this.view.isShowName())
        {
            String name = UIKeys.FILM_VIEW_FREE.get();

            if (this.view.getNavigation().isInCameraView())
            {
                name = FilmViewMenus.cameraName(film, this.view.resolveCameraId());

                if (this.view.isFollowOutput())
                {
                    name = UIKeys.FILM_VIEW_OUTPUT_NAME.format(name).get();
                }
            }

            if (film != null && !film.hasCamera(this.view.getCameraId()) && !this.view.isFollowOutput())
            {
                name = UIKeys.FILM_VIEW_MISSING_CAMERA.get();
            }

            context.batcher.textCard(name, this.area.x + 6, this.viewTools.area.ey() + 3, BBSSettings.textColor(), Colors.A50);
        }

        if (state != null)
        {
            long stamp = state.getLastRenderedNanos();

            if (stamp > this.lastImageNanos && this.lastImageNanos > 0L)
            {
                double hz = 1_000_000_000D / (stamp - this.lastImageNanos);

                this.observedRefreshRate = this.observedRefreshRate == 0D ? hz : this.observedRefreshRate * 0.75D + hz * 0.25D;
            }

            this.lastImageNanos = stamp;
            status = switch (state.getStatus())
            {
                case WARMING_UP -> UIKeys.FILM_PREVIEW_STATUS_PREPARING.get();
                case LIVE -> UIKeys.FILM_PREVIEW_STATUS_LIVE.get();
                case THROTTLED -> UIKeys.FILM_PREVIEW_STATUS_THROTTLED.get();
                case BUDGET_UNSATISFIED -> UIKeys.FILM_PREVIEW_STATUS_BUDGET.get();
                case FAILED -> UIKeys.FILM_PREVIEW_STATUS_FAILED.get();
                case HIDDEN -> UIKeys.FILM_PREVIEW_STATUS_PAUSED.get();
            };

            if (state.getStatus() == ViewRenderState.Status.FAILED || state.getStatus() == ViewRenderState.Status.BUDGET_UNSATISFIED)
            {
                color = BBSSettings.warningColor();
            }

            if (state.hasFrame())
            {
                long age = Math.max(0L, System.nanoTime() - stamp) / 1_000_000L;
                String refresh = this.observedRefreshRate > 0D ? String.format(Locale.ROOT, "%.1f Hz", this.observedRefreshRate)
                    : UIKeys.FILM_PREVIEW_STATUS_FIRST_FRAME.get();
                String detail = UIKeys.FILM_PREVIEW_STATUS_DETAIL.format(state.getWidth(), state.getHeight(), refresh, age).get();

                if (!this.isPrimaryView() && this.view.getResolutionWidth() > 0 && state.getResolutionScale() < 1F
                    && (state.getStatus() == ViewRenderState.Status.LIVE || state.getStatus() == ViewRenderState.Status.THROTTLED)
                    && (state.getWidth() < this.view.getWidth() || state.getHeight() < this.view.getHeight()))
                {
                    status = UIKeys.FILM_PREVIEW_RESOLUTION_REDUCED.format(this.view.getWidth(), this.view.getHeight()).get();
                    color = BBSSettings.warningColor();
                }

                context.batcher.textCard(detail, this.area.x + 6, this.icons.area.y - 23, BBSSettings.mutedTextColor(), Colors.A50);
            }

            Throwable failure = state.getFailure();

            if (failure == null)
            {
                failure = IrisViewBackend.failure(this.view.getId());
            }

            if (failure != null)
            {
                this.shaders.tooltip(UIKeys.FILM_VIEW_PREVIEW_ERROR.format(failure.getClass().getSimpleName(), failure.getMessage()));
            }
            else
            {
                IKey shaderStatus = IrisViewBackend.shadersActive(this.view.getId()) ? UIKeys.FILM_VIEW_SHADERS_ACTIVE
                    : this.view.isShadersEnabled() && !IrisViewBackend.isShaderPackAvailable()
                        ? UIKeys.FILM_VIEW_NO_SHADER_PACK : UIKeys.FILM_VIEW_BASIC_LIGHTING;
                IKey shaderAction = this.view.isShadersEnabled() ? UIKeys.FILM_VIEW_SHADERS_OFF : UIKeys.FILM_VIEW_SHADERS_ON;

                this.shaders.tooltip(UIKeys.FILM_VIEW_SHADER_STATUS.format(shaderStatus.get(), shaderAction.get()));
            }
        }

        context.batcher.textCard(status, this.area.x + 6, this.icons.area.y - 36, color, Colors.A50);
    }

    private void renderCursor(UIContext context)
    {
        Camera camera = this.getDisplayedCamera();
        Matrix4fStack stack = RenderSystem.getModelViewStack();

        stack.pushMatrix();

        stack.mul(context.batcher.getContext().pose().last().pose());
        stack.translate(area.x + 16, area.ey() - 12, 0F);
        stack.rotate(Axis.XN.rotation(camera.rotation.x));
        stack.rotate(Axis.YP.rotation(camera.rotation.y));
        stack.scale(-1F, -1F, -1F);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.renderCrosshair(10);

        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
