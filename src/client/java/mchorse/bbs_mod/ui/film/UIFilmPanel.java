package mchorse.bbs_mod.ui.film;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraPoseEditing;
import mchorse.bbs_mod.camera.CameraPoseEvaluator;
import mchorse.bbs_mod.camera.clips.CameraPosePolicy;
import mchorse.bbs_mod.camera.clips.modifiers.TranslateClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.film.collaboration.BBSFilmCollaborationBridge;
import mchorse.bbs_mod.client.renderer.MorphRenderer;
import mchorse.bbs_mod.api.client.film.BBSFilmRefreshHint;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FrozenFilmController;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.settings.values.ui.ValueEditorLayout;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.IFlightSupported;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UICRUDOverlayPanel;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UIDataTabs;
import mchorse.bbs_mod.ui.dashboard.utils.IUIOrbitKeysHandler;
import mchorse.bbs_mod.ui.film.audio.UIAudioRecorder;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.film.home.FilmThumbnails;
import mchorse.bbs_mod.ui.film.home.UIFilmHomePanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.UIFilmUndoHandler;
import mchorse.bbs_mod.ui.film.view.ViewDescriptor;
import mchorse.bbs_mod.ui.film.view.ViewNavigationState;
import mchorse.bbs_mod.ui.film.view.ViewPerformanceSettings;
import mchorse.bbs_mod.ui.film.utils.undo.UIUndoHistoryOverlay;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.layout.ILayoutSource;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UINumberOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.presets.UICopyPasteController;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.presets.PresetManager;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2i;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class UIFilmPanel extends UIDataDashboardPanel<Film> implements IFlightSupported, IUIOrbitKeysHandler, ICursor
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PREVIEW_MODE_EXPORT = 0;
    private static final int PREVIEW_MODE_CUSTOM = 1;
    private static final int PREVIEW_MODE_AUTO = 2;

    private RunnerCameraController runner;
    private final CameraPoseEvaluator cameraPoseEvaluator = new CameraPoseEvaluator();
    private final ViewPerformanceSettings viewPerformanceSettings = new ViewPerformanceSettings();
    private long sceneFrameId;
    private boolean lifecycleActive;
    private boolean lastRunning;
    private final Position position = new Position(0, 0, 0, 0, 0);
    private final Position flightStartPose = new Position();
    private UIFilmPreview flightPreview;
    private Film flightFilm;
    private String flightCameraId;
    private Clip flightClip;
    private long flightSequence;
    private int flightTick;
    private boolean flightEditsCamera;
    private MapType flightStartOrbit;
    private long lastLogicFrame = Long.MIN_VALUE;

    public UIFilmHomePanel selectionPanel;

    public UIElement main;
    public UIElement editArea;
    private final UIDockLayout dock;
    public UIFilmRecorder recorder;
    public UIFilmPreview preview;
    private final ViewDescriptor primaryView;
    /* Stable slots keep saved dock layouts and external preview references valid. */
    public UIFilmPreview preview2;
    public UIFilmPreview preview3;
    public UIFilmPreview preview4;
    private final ViewDescriptor secondaryView;
    private final ViewDescriptor tertiaryView;
    private final ViewDescriptor quaternaryView;
    private UIFilmPreview activePreview;
    private String editedCameraId = Film.LEGACY_CAMERA_ID;
    private String viewSettingsFilmId;
    private int singleViewPreviewWidth;
    private int singleViewPreviewHeight;

    private boolean restartPending;
    private int lastRestartCursor = -1;

    public UIIcon duplicateFilm;

    /* Main editors */
    public UIClipsPanel cameraEditor;
    public UIReplaysEditor replayEditor;
    public UIClipsPanel actionEditor;

    /* Icon bar buttons */
    public UIIcon openFilmMenu;
    public UIIcon openCameraEditor;
    public UIIcon openReplayEditor;
    public UIIcon openActionEditor;

    private UICopyPasteController layoutPresetsController;

    private Camera camera = new Camera();
    private boolean entered;
    public boolean playerToCamera;

    /* Entity control */
    private UIFilmController controller = new UIFilmController(this);
    private UIFilmUndoHandler undoHandler;

    public final Matrix4f lastView = new Matrix4f();
    public final Matrix4f lastProjection = new Matrix4f();

    private long lastTime;
    private double timeSpentActiveAccumulator;
    private final FilmEditorUserActivity filmUserActivity = new FilmEditorUserActivity();

    private List<UIElement> panels = new ArrayList<>();
    private UIElement secretPlay;

    private boolean newFilm;
    private double timelineXMin = Double.NaN;
    private double timelineXMax = Double.NaN;
    private final FilmEditorMigrationLogic.TimelineScrollMemory timelineScrollByFilm = new FilmEditorMigrationLogic.TimelineScrollMemory();
    private FilmQueueExporter queueExporter;

    /* Docking: layout panels and drag-to-swap/split */
    private final Map<String, UIElement> panelById = new LinkedHashMap<>();
    private static final String PANEL_MAIN_ID = "main";
    private static final String PANEL_PREVIEW_ID = "preview";
    private static final String PANEL_PREVIEW_2_ID = "preview2";
    private static final String PANEL_PREVIEW_3_ID = "preview3";
    private static final String PANEL_PREVIEW_4_ID = "preview4";
    private static final String PANEL_EDIT_AREA_ID = "editArea";
    private static final String PANEL_REPLAYS_LIST_ID = "replaysList";
    private static final String PANEL_REPLAY_PROPS_ID = "replayProps";
    /** Top offset (px) for parameters panels when layout is unlocked (space for drag icon). Used for lock button size too. */
    public static final int EDIT_PANEL_TOP_OFFSET_PX = 20;
    private static final int FILM_TOP_BAR_BUTTON_SIZE = UIDataTabs.TABS_HEIGHT_PX;
    private static final int FILM_TOP_BAR_SEPARATOR_WIDTH = 8;
    private static final int FILM_TOP_BAR_ACTIONS_WIDTH = FILM_TOP_BAR_BUTTON_SIZE * 4 + FILM_TOP_BAR_SEPARATOR_WIDTH;
    private UIElement selectedMainEditorPanel;
    private UIElement topBarActions;
    private UIElement topBarSeparator;


    /**
     * Initialize the camera editor with a camera profile.
     */
    public UIFilmPanel(UIDashboard dashboard)
    {
        super(dashboard);
        this.enableTabs();
        this.playerToCamera = BBSSettings.editorPlayerFollowsCamera.get();

        this.runner = new RunnerCameraController(this, (playing) ->
        {
            this.notifyServer(playing ? ActionState.PLAY : ActionState.PAUSE);
        });
        this.runner.getContext().captureSnapshots();

        this.recorder = new UIFilmRecorder(this);

        this.main = new UIElement();
        this.editArea = new UIElement();
        this.primaryView = new ViewDescriptor(this, ViewDescriptor.PRIMARY_ID, true);
        this.secondaryView = new ViewDescriptor(this, ViewDescriptor.SECONDARY_ID, false);
        this.secondaryView.setShadersEnabled(false);
        this.tertiaryView = new ViewDescriptor(this, PANEL_PREVIEW_3_ID, false);
        this.tertiaryView.setShadersEnabled(false);
        this.quaternaryView = new ViewDescriptor(this, PANEL_PREVIEW_4_ID, false);
        this.quaternaryView.setShadersEnabled(false);
        this.preview = new UIFilmPreview(this, this.primaryView);
        this.preview2 = new UIFilmPreview(this, this.secondaryView);
        this.preview3 = new UIFilmPreview(this, this.tertiaryView);
        this.preview4 = new UIFilmPreview(this, this.quaternaryView);
        this.activePreview = this.preview;
        this.panelById.put(PANEL_MAIN_ID, this.main);
        this.panelById.put(PANEL_PREVIEW_ID, this.preview);
        this.panelById.put(PANEL_PREVIEW_2_ID, this.preview2);
        this.panelById.put(PANEL_PREVIEW_3_ID, this.preview3);
        this.panelById.put(PANEL_PREVIEW_4_ID, this.preview4);
        this.panelById.put(PANEL_EDIT_AREA_ID, this.editArea);

        /* The dock must be constructed before the editors below: the replay
         * editors query its lock state (edit panel top offset) from their own
         * constructors, and this final field cannot be left unassigned when
         * getEditPanelTopOffsetPx() runs during their construction. The layout
         * wiring further down configures and mounts it afterwards. */
        this.dock = new UIDockLayout()
            .locked(!BBSSettings.editorLayoutSettings.isDockUnlocked(ValueEditorLayout.FILM));

        /* Editors */
        this.cameraEditor = new UIClipsPanel(this, BBSMod.getFactoryCameraClips()).target(this.editArea);
        this.cameraEditor.full(this.main);

        this.cameraEditor.clips.context((menu) ->
        {
            UIAudioRecorder.addOption(this, menu);
        });

        this.replayEditor = new UIReplaysEditor(this);
        this.replayEditor.full(this.main).setVisible(false);
        this.actionEditor = new UIClipsPanel(this, BBSMod.getFactoryActionClips()).target(this.editArea);
        this.actionEditor.full(this.main).setVisible(false);

        this.panelById.put(PANEL_REPLAYS_LIST_ID, this.replayEditor.replaysList);
        this.panelById.put(PANEL_REPLAY_PROPS_ID, this.replayEditor.replayProperties);
        this.selectedMainEditorPanel = this.cameraEditor;

        /* Film panel keeps common CRUD actions inside film settings menu instead of the sidebar. */
        this.iconBar.remove(this.openOverlay);
        this.iconBar.remove(this.saveIcon);

        /* Top bar buttons */
        this.openFilmMenu = new UIIcon(Icons.MORE, (b) ->
        {
            this.getContext().replaceContextMenu(this::fillFilmContextMenu);
        });
        this.openCameraEditor = new UIIcon(Icons.FRUSTUM, (b) -> this.showPanel(this.cameraEditor));
        this.openReplayEditor = new UIIcon(Icons.SCENE, (b) -> this.showPanel(this.replayEditor));
        this.openActionEditor = new UIIcon(Icons.ACTION, (b) -> this.showPanel(this.actionEditor));

        this.layoutPresetsController = new UICopyPasteController(PresetManager.LAYOUTS, "_CopyFilmLayout")
            .supplier(this::getFilmLayoutPresetData)
            .consumer(this::applyFilmLayoutFromPreset);

        this.openFilmMenu.wh(FILM_TOP_BAR_BUTTON_SIZE, FILM_TOP_BAR_BUTTON_SIZE).tooltip(UIKeys.FILM_OPTIONS, Direction.BOTTOM);
        this.openCameraEditor.wh(FILM_TOP_BAR_BUTTON_SIZE, FILM_TOP_BAR_BUTTON_SIZE).tooltip(UIKeys.FILM_OPEN_CAMERA_EDITOR, Direction.BOTTOM);
        this.openReplayEditor.wh(FILM_TOP_BAR_BUTTON_SIZE, FILM_TOP_BAR_BUTTON_SIZE).tooltip(UIKeys.FILM_OPEN_REPLAY_EDITOR, Direction.BOTTOM);
        this.openActionEditor.wh(FILM_TOP_BAR_BUTTON_SIZE, FILM_TOP_BAR_BUTTON_SIZE).tooltip(UIKeys.FILM_OPEN_ACTION_EDITOR, Direction.BOTTOM);

        this.topBarActions = new UIElement();
        this.topBarActions.relative(this.tabBar).x(1F, -FILM_TOP_BAR_ACTIONS_WIDTH).w(FILM_TOP_BAR_ACTIONS_WIDTH).h(UIDataTabs.TABS_HEIGHT_PX).row(0).resize();
        this.topBarSeparator = new UIElement();
        this.topBarSeparator.wh(FILM_TOP_BAR_SEPARATOR_WIDTH, UIDataTabs.TABS_HEIGHT_PX);
        this.topBarActions.add(new UIRenderable(this::renderTopBarActions), this.openCameraEditor, this.openReplayEditor, this.openActionEditor, this.topBarSeparator, this.openFilmMenu);
        this.tabBar.add(this.topBarActions);

        /* Setup elements */
        this.dock.relative(this.editor).w(1F).h(1F);
        this.dock.source(this.createFilmLayoutSource())
            .frameless(PANEL_PREVIEW_ID)
            .gate(this::hasFilmInCurrentTab)
            .ensure(this::ensureFilmLayoutPanels)
            .icons(this::getDockPanelIcon)
            .onChanged(this::onDockVisibilityChanged)
            .onLayoutSettled(() -> this.applyPreviewSizeToBBS("layoutSettled"))
            .animateLayoutChanges(BBSSettings::filmEditorLayoutTransitionEnabled);

        for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
        {
            this.dock.addPanel(entry.getKey(), entry.getValue(), this.getDockPanelIcon(entry.getKey()), this.getDockPanelLabel(entry.getKey()));
        }

        this.dock.mount();
        this.editor.add(this.dock);
        this.main.add(this.cameraEditor, this.replayEditor, this.actionEditor);
        this.add(this.controller);
        this.overlay.namesList.setFileIcon(Icons.FILM);

        /* Register keybinds */
        IKey modes = UIKeys.CAMERA_EDITOR_KEYS_MODES_TITLE;
        IKey editor = UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE;
        IKey looping = UIKeys.CAMERA_EDITOR_KEYS_LOOPING_TITLE;
        Supplier<Boolean> active = () -> this.data != null && !this.isFlying();

        this.keys().register(Keys.PLAUSE, () -> this.preview.plause.clickItself()).active(active).category(editor);
        this.keys().register(Keys.NEXT_CLIP, () -> this.setCursor(this.data.getCameraClips(this.editedCameraId).findNextTick(this.getCursor()))).active(active).category(editor);
        this.keys().register(Keys.PREV_CLIP, () -> this.setCursor(this.data.getCameraClips(this.editedCameraId).findPreviousTick(this.getCursor()))).active(active).category(editor);
        this.keys().register(Keys.NEXT, () -> this.setCursor(this.getCursor() + 1)).active(active).category(editor);
        this.keys().register(Keys.PREV, () -> this.setCursor(this.getCursor() - 1)).active(active).category(editor);
        this.keys().register(Keys.UNDO, this::undo).category(editor);
        this.keys().register(Keys.REDO, this::redo).category(editor);
        this.keys().register(Keys.FLIGHT, this::toggleFlight).active(() -> this.data != null).category(modes);
        this.keys().register(Keys.LOOPING, () ->
        {
            BBSSettings.editorLoop.set(!BBSSettings.editorLoop.get());
            this.getContext().notifyInfo(UIKeys.CAMERA_EDITOR_KEYS_LOOPING_TOGGLE_NOTIFICATION);
        }).active(active).category(looping);
        this.keys().register(Keys.LOOPING_SET_MIN, () -> this.cameraEditor.clips.setLoopMin()).active(active).category(looping);
        this.keys().register(Keys.LOOPING_SET_MAX, () -> this.cameraEditor.clips.setLoopMax()).active(active).category(looping);
        this.keys().register(Keys.JUMP_FORWARD, () -> this.setCursor(this.getCursor() + BBSSettings.editorJump.get())).active(active).category(editor);
        this.keys().register(Keys.JUMP_BACKWARD, () -> this.setCursor(this.getCursor() - BBSSettings.editorJump.get())).active(active).category(editor);
        this.keys().register(Keys.FILM_CONTROLLER_CYCLE_EDITORS, () ->
        {
            this.showPanel(MathUtils.cycler(this.getPanelIndex() + 1, this.panels));
            UIUtils.playClick();
        }).category(editor);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_ACTIONS, () ->
        {
            this.showPanel(this.actionEditor);
            UIUtils.playClick();
        }).category(editor);
        this.keys().register(Keys.FILM_CONTROLLER_NEXT_DOCK_TAB, () ->
        {
            if (this.dock.cycleDockStackTab(1))
            {
                UIUtils.playClick();
            }
        }).active(active).category(editor);
        this.keys().register(Keys.FILM_CONTROLLER_PREV_DOCK_TAB, () ->
        {
            if (this.dock.cycleDockStackTab(-1))
            {
                UIUtils.playClick();
            }
        }).active(active).category(editor);
        this.keys().register(Keys.DOCK_MAXIMIZE, () ->
        {
            if (this.dock.toggleMaximizeUnderCursor())
            {
                UIUtils.playClick();
            }
        }).active(active).category(editor);
        this.keys().register(Keys.DOCK_UNDO_LAYOUT, () ->
        {
            if (this.dock.undoLayout())
            {
                UIUtils.playClick();
            }
        }).active(active).category(editor);
        this.selectionPanel = new UIFilmHomePanel(this);
        this.selectionPanel.setVisible(false);

        this.fill(null);


        this.panels.add(this.cameraEditor);
        this.panels.add(this.replayEditor);
        this.panels.add(this.actionEditor);

        this.secretPlay = new UIElement();
        this.secretPlay.keys().register(Keys.PLAUSE, () -> this.preview.plause.clickItself()).active(() -> !this.isFlying() && !this.canBeSeen() && this.data != null).category(editor);

        this.setUndoId("film_panel");
        this.cameraEditor.setUndoId("camera_editor");
        this.replayEditor.setUndoId("replay_editor");
        this.actionEditor.setUndoId("action_editor");

        UIElement element = new UIElement()
        {
            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                if (FilmEditorMigrationLogic.shouldMoveCursorWithWheel(
                    Window.isCtrlPressed(),
                    UIFilmPanel.this.isFlying(),
                    UIFilmPanel.this.isCursorOverTimeline(context)
                ))
                {
                    int magnitude = Window.isShiftPressed() ? BBSSettings.editorJump.get() : 1;
                    int newCursor = UIFilmPanel.this.getCursor() + (int) Math.copySign(magnitude, context.mouseWheel);

                    UIFilmPanel.this.setCursor(newCursor);

                    return true;
                }

                return super.subMouseScrolled(context);
            }
        };

        this.add(element);
        this.add(new UIFilmPanelUndoKeys(this).full(this));

        IValueListener refreshPreviewOnVideoResolution = (v, f) ->
        {
            if (this.isVisible()) this.applyPreviewSizeToBBS("settingsCallback");
        };
        BBSSettings.videoSettings.width.postCallback(refreshPreviewOnVideoResolution);
        BBSSettings.videoSettings.height.postCallback(refreshPreviewOnVideoResolution);
        BBSSettings.editorPreviewSizeMode.postCallback(refreshPreviewOnVideoResolution);
        BBSSettings.editorPreviewCustomWidth.postCallback(refreshPreviewOnVideoResolution);
        BBSSettings.editorPreviewCustomHeight.postCallback(refreshPreviewOnVideoResolution);
        BBSSettings.editorPreviewResolutionScale.postCallback(refreshPreviewOnVideoResolution);

        this.selectionPanel.relative(this).y(UIDataTabs.TABS_HEIGHT_PX).wTo(this.iconBar.area).h(1F, -UIDataTabs.TABS_HEIGHT_PX);
        this.add(this.selectionPanel);
    }

    private boolean isCursorOverTimeline(UIContext context)
    {
        return this.isCursorOverClipsTimeline(this.cameraEditor, context)
            || this.isCursorOverClipsTimeline(this.actionEditor, context)
            || this.isCursorOverReplayTimeline(context);
    }

    private boolean isCursorOverClipsTimeline(UIClipsPanel panel, UIContext context)
    {
        return panel != null
            && panel.isVisible()
            && panel.clips != null
            && panel.clips.isVisible()
            && panel.clips.area.isInside(context);
    }

    private boolean isCursorOverReplayTimeline(UIContext context)
    {
        return this.replayEditor != null
            && this.replayEditor.isVisible()
            && this.replayEditor.keyframeEditor != null
            && this.replayEditor.keyframeEditor.view != null
            && this.replayEditor.keyframeEditor.view.isVisible()
            && this.replayEditor.keyframeEditor.view.area.isInside(context);
    }

    public boolean isLayoutLocked()
    {
        return this.dock.isLocked();
    }

    /** Top offset (px) for parameters panels; 0 when layout locked. */
    public int getEditPanelTopOffsetPx()
    {
        return this.dock.isLocked() ? 0 : EDIT_PANEL_TOP_OFFSET_PX;
    }

    @Override
    protected int getSidebarWidthPx()
    {
        return 0;
    }

    @Override
    protected int getTabsRightInsetPx()
    {
        return FILM_TOP_BAR_ACTIONS_WIDTH;
    }

    @Override
    public IKey getNewTabLabel()
    {
        return UIKeys.FILM_TABS_NEW_TAB;
    }

    @Override
    public Icon getTabIcon(DataTab tab)
    {
        return tab != null && tab.dataId == null ? Icons.SEARCH : Icons.FILM;
    }

    public void renameFilmId(String from, String to)
    {
        if (from == null || to == null || from.equals(to))
        {
            return;
        }

        if (this.data != null && from.equals(this.data.getId()))
        {
            this.data.setId(to);
        }

        this.onDataRenamed(from, to);
    }

    @Override
    public void onDataRenamed(String from, String to)
    {
        this.flushFilmCollaborationEdits();
        super.onDataRenamed(from, to);

        if (this.lifecycleActive)
        {
            BBSFilmCollaborationBridge.attach(this, this.data);
        }
    }

    public void renameFilmFolder(String fromPath, String toPath)
    {
        if (fromPath == null || toPath == null || toPath.trim().isEmpty())
        {
            return;
        }

        if (this.data != null)
        {
            String id = this.data.getId();

            this.data.setId(UIDataDashboardPanel.remapIdAfterFolderRename(id, fromPath, toPath));
        }

        this.onDataFolderRenamed(fromPath, toPath);
    }

    @Override
    public void onDataFolderRenamed(String fromPath, String toPath)
    {
        this.flushFilmCollaborationEdits();
        super.onDataFolderRenamed(fromPath, toPath);

        if (this.lifecycleActive)
        {
            BBSFilmCollaborationBridge.attach(this, this.data);
        }
    }

    public void deleteFilmIds(Set<String> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return;
        }

        for (String id : ids)
        {
            this.onDataRemoved(id);
        }

        this.updateTabVisibility();
    }

    public void deleteFilmFolders(Set<String> folderPaths)
    {
        if (folderPaths == null || folderPaths.isEmpty())
        {
            return;
        }

        for (String folder : folderPaths)
        {
            if (folder != null && !folder.isEmpty())
            {
                this.onDataFolderRemoved(folder);
            }
        }

        this.updateTabVisibility();
    }

    public void updateTabVisibility()
    {
        this.dock.refreshVisibility();
    }

    private void onDockVisibilityChanged()
    {
        boolean hasFilm = this.hasFilmInCurrentTab();

        this.updateMainEditorVisibility(hasFilm);

        if (this.selectionPanel != null)
        {
            this.selectionPanel.setVisible(!hasFilm);
        }

        for (UIFilmPreview preview : this.getPreviews())
        {
            ViewDescriptor view = preview.getViewDescriptor();
            boolean visible = hasFilm && this.dock.isPanelActive(view.getId());

            if (!visible && view.isVisible())
            {
                preview.cancelViewInteraction();
                preview.getViewController().releaseViewResources();
            }

            view.setVisible(visible);
            view.setActive(visible || view.isPrimary());
        }

        if (this.activePreview != null && !this.activePreview.getViewDescriptor().isVisible())
        {
            this.activePreview = this.preview;
        }
        BBSRendering.setSecondaryViewEnabled(this.secondaryView.isVisible()
            || this.tertiaryView.isVisible()
            || this.quaternaryView.isVisible());
    }

    private boolean hasFilmInCurrentTab()
    {
        DataTab tab = this.getCurrentDataTab();

        return tab != null && tab.dataId != null;
    }

    private boolean isMainPanelActive()
    {
        return this.dock.isPanelActive(PANEL_MAIN_ID);
    }

    private boolean isEditAreaPanelActive()
    {
        return this.dock.isPanelActive(PANEL_EDIT_AREA_ID);
    }

    private void updateMainEditorVisibility(boolean hasFilm)
    {
        UIElement selected = this.selectedMainEditorPanel == null ? this.cameraEditor : this.selectedMainEditorPanel;
        boolean mainActive = this.isMainPanelActive();
        boolean editAreaActive = this.isEditAreaPanelActive();
        boolean visible = hasFilm && (mainActive || editAreaActive);
        boolean cameraVisible = visible && selected == this.cameraEditor;
        boolean replayVisible = visible && selected == this.replayEditor;
        boolean actionVisible = visible && selected == this.actionEditor;

        this.cameraEditor.setVisible(cameraVisible);
        this.replayEditor.setVisible(replayVisible);
        this.actionEditor.setVisible(actionVisible);

        this.cameraEditor.setTimelineVisible(mainActive && cameraVisible);
        this.cameraEditor.setPropertiesVisible(editAreaActive && cameraVisible);

        this.replayEditor.setTimelineVisible(mainActive && replayVisible);
        this.replayEditor.setPropertiesVisible(editAreaActive && replayVisible);

        this.actionEditor.setTimelineVisible(mainActive && actionVisible);
        this.actionEditor.setPropertiesVisible(editAreaActive && actionVisible);
    }

    private void toggleLayoutLock()
    {
        this.dock.toggleLock();
        this.getFilmLayoutSettings().setDockUnlocked(ValueEditorLayout.FILM, !this.dock.isLocked());
        this.refreshEditPanelOffsets();
    }

    private Icon getDockPanelIcon(String panelId)
    {
        switch (panelId)
        {
            case PANEL_PREVIEW_ID: return Icons.VIDEO_CAMERA;
            case PANEL_PREVIEW_2_ID: return Icons.VIDEO_CAMERA;
            case PANEL_PREVIEW_3_ID: return Icons.VIDEO_CAMERA;
            case PANEL_PREVIEW_4_ID: return Icons.VIDEO_CAMERA;
            case PANEL_EDIT_AREA_ID: return Icons.EDITOR;
            case PANEL_REPLAYS_LIST_ID: return Icons.LIST;
            case PANEL_REPLAY_PROPS_ID: return Icons.PROPERTIES;
            case PANEL_MAIN_ID: return Icons.FILM;
            default: return Icons.FILE;
        }
    }

    private IKey getDockPanelLabel(String panelId)
    {
        switch (panelId)
        {
            case PANEL_PREVIEW_ID: return UIKeys.FILM_PANELS_PREVIEW;
            case PANEL_PREVIEW_2_ID: return IKey.raw(UIKeys.FILM_PANELS_PREVIEW.get() + " 2");
            case PANEL_PREVIEW_3_ID: return IKey.raw(UIKeys.FILM_PANELS_PREVIEW.get() + " 3");
            case PANEL_PREVIEW_4_ID: return IKey.raw(UIKeys.FILM_PANELS_PREVIEW.get() + " 4");
            case PANEL_EDIT_AREA_ID: return UIKeys.FILM_PANELS_EDIT_AREA;
            case PANEL_REPLAYS_LIST_ID: return UIKeys.FILM_PANELS_REPLAYS_LIST;
            case PANEL_REPLAY_PROPS_ID: return UIKeys.FILM_PANELS_REPLAY_PROPS;
            case PANEL_MAIN_ID: return UIKeys.FILM_PANELS_MAIN;
            default: return IKey.EMPTY;
        }
    }

    private void refreshEditPanelOffsets()
    {
        this.cameraEditor.refreshEditPanelOffset();
        this.actionEditor.refreshEditPanelOffset();
        this.replayEditor.refreshEditPanelOffset();
    }

    private ValueEditorLayout.FilmEditor getCurrentFilmLayoutEditor()
    {
        if (this.selectedMainEditorPanel == this.replayEditor)
        {
            return ValueEditorLayout.FilmEditor.REPLAY;
        }

        if (this.selectedMainEditorPanel == this.actionEditor)
        {
            return ValueEditorLayout.FilmEditor.ACTION;
        }

        return ValueEditorLayout.FilmEditor.CAMERA;
    }

    private ValueEditorLayout getFilmLayoutSettings()
    {
        return BBSSettings.editorLayoutSettings;
    }

    private ILayoutSource createFilmLayoutSource()
    {
        return new ILayoutSource()
        {
            @Override
            public BaseValue value()
            {
                return UIFilmPanel.this.getFilmLayoutSettings();
            }

            @Override
            public EditorLayoutNode getRoot()
            {
                return UIFilmPanel.this.getCurrentFilmLayoutRoot();
            }

            @Override
            public void setRoot(EditorLayoutNode root)
            {
                UIFilmPanel.this.setCurrentFilmLayoutRoot(root);
            }

            @Override
            public List<EditorLayoutNode.SplitterNode> getSplitters()
            {
                return UIFilmPanel.this.getCurrentFilmSplitters();
            }

            @Override
            public List<EditorLayoutNode.SplitterNode> getSplittersForWrite()
            {
                return UIFilmPanel.this.getCurrentFilmSplittersForWrite();
            }

            @Override
            public EditorLayoutNode getDefault()
            {
                return EditorLayoutNode.defaultFilmLayout();
            }

            @Override
            public Set<String> getHiddenPanels()
            {
                return UIFilmPanel.this.getFilmLayoutSettings().getHiddenPanels(UIFilmPanel.this.currentLayoutId());
            }

            @Override
            public void setHiddenPanels(Set<String> hidden)
            {
                UIFilmPanel.this.getFilmLayoutSettings().setHiddenPanels(UIFilmPanel.this.currentLayoutId(), hidden);
            }
        };
    }

    private EditorLayoutNode getCurrentFilmLayoutRoot()
    {
        return this.getFilmLayoutSettings().getFilmLayoutRoot(this.getCurrentFilmLayoutEditor());
    }

    private void setCurrentFilmLayoutRoot(EditorLayoutNode root)
    {
        this.getFilmLayoutSettings().setFilmLayoutRoot(this.getCurrentFilmLayoutEditor(), root);
    }

    private List<EditorLayoutNode.SplitterNode> getCurrentFilmSplitters()
    {
        return this.getFilmLayoutSettings().getFilmSplitters(this.getCurrentFilmLayoutEditor());
    }

    private List<EditorLayoutNode.SplitterNode> getCurrentFilmSplittersForWrite()
    {
        return this.getFilmLayoutSettings().getFilmSplittersForWrite(this.getCurrentFilmLayoutEditor());
    }

    private String currentLayoutId()
    {
        ValueEditorLayout.FilmEditor editor = this.getCurrentFilmLayoutEditor();

        return this.getFilmLayoutSettings().isFilmLayoutBound(editor)
            ? ValueEditorLayout.filmLayoutId(editor)
            : ValueEditorLayout.FILM;
    }

    private MapType getFilmLayoutPresetData()
    {
        MapType data = new MapType();
        data.put("film_layout", this.dock.getLayoutRoot().toData());
        return data;
    }

    private void applyFilmLayoutFromPreset(MapType data, int mouseX, int mouseY)
    {
        BaseType layoutData = data.get("film_layout");
        if (layoutData == null)
        {
            return;
        }
        EditorLayoutNode root = EditorLayoutNode.fromData(layoutData);
        if (root != null)
        {
            this.dock.applyLayoutRoot(root);
        }
    }

    private void resetFilmLayout()
    {
        this.dock.resetLayout();
    }

    /** Restore the second preview while preserving the user's other dock panels. */
    public void ensurePreview2Visible()
    {
        this.ensurePreviewVisible(2);
    }

    /** Add an auxiliary preview to the dock on demand. Slots are limited to four views. */
    public void ensurePreviewVisible(int slot)
    {
        String panelId;

        if (slot == 2)
        {
            panelId = PANEL_PREVIEW_2_ID;
        }
        else if (slot == 3)
        {
            panelId = PANEL_PREVIEW_3_ID;
        }
        else if (slot == 4)
        {
            panelId = PANEL_PREVIEW_4_ID;
        }
        else
        {
            return;
        }

        this.applyPreviewSizeToBBS("beforeAddPreview");
        Set<String> hidden = this.getFilmLayoutSettings().getHiddenPanels(this.currentLayoutId());
        boolean changed = hidden.remove(panelId);
        EditorLayoutNode root = this.getCurrentFilmLayoutRoot();
        HashSet<String> ids = new HashSet<>();
        this.collectPanelIds(root, ids);

        if (!ids.contains(panelId))
        {
            root = EditorLayoutNode.copyWithInsertSplitAt(root, PANEL_PREVIEW_ID, panelId, EditorLayoutNode.EDGE_RIGHT);
            changed = true;
        }
        else
        {
            EditorLayoutNode selected = EditorLayoutNode.copyWithStackActivePanel(root, panelId, panelId);

            changed = selected != root || changed;
            root = selected;
        }

        if (changed)
        {
            this.getFilmLayoutSettings().setHiddenPanels(this.currentLayoutId(), hidden);
            this.setCurrentFilmLayoutRoot(root);
            this.dock.refresh();
            this.onDockVisibilityChanged();
        }

        this.activatePreview(this.getPreview(panelId));
        this.getPreview(panelId).requestRefresh();
    }

    public boolean canAddPreview()
    {
        return !this.isPreviewPresent(PANEL_PREVIEW_2_ID) || !this.isPreviewPresent(PANEL_PREVIEW_3_ID) || !this.isPreviewPresent(PANEL_PREVIEW_4_ID);
    }

    private boolean isPreviewPresent(String id)
    {
        HashSet<String> ids = new HashSet<>();

        this.collectPanelIds(this.getCurrentFilmLayoutRoot(), ids);

        return ids.contains(id) && !this.getFilmLayoutSettings().getHiddenPanels(this.currentLayoutId()).contains(id);
    }

    public void addPreview()
    {
        for (int slot = 2; slot <= 4; slot++)
        {
            UIFilmPreview preview = this.getPreviews().get(slot - 1);

            if (!this.isPreviewPresent(preview.getViewDescriptor().getId()))
            {
                this.ensurePreviewVisible(slot);

                return;
            }
        }
    }

    public void removePreview(UIFilmPreview preview)
    {
        if (preview == null || preview.isPrimaryView())
        {
            return;
        }

        preview.cancelViewInteraction();
        preview.getViewController().releaseViewResources();
        String id = preview.getViewDescriptor().getId();
        Set<String> hidden = this.getFilmLayoutSettings().getHiddenPanels(this.currentLayoutId());

        hidden.add(id);
        this.getFilmLayoutSettings().setHiddenPanels(this.currentLayoutId(), hidden);
        this.setCurrentFilmLayoutRoot(EditorLayoutNode.copyWithRemovedPanel(this.getCurrentFilmLayoutRoot(), id));
        this.dock.refresh();
        this.onDockVisibilityChanged();
        this.saveViewSettings();
    }

    private EditorLayoutNode ensureFilmLayoutPanels(EditorLayoutNode root)
    {
        HashSet<String> ids = new HashSet<>();
        this.collectPanelIds(root, ids);

        Set<String> hidden = this.getFilmLayoutSettings().getHiddenPanels(this.currentLayoutId());
        boolean hasList = ids.contains(PANEL_REPLAYS_LIST_ID) || hidden.contains(PANEL_REPLAYS_LIST_ID);
        boolean hasProps = ids.contains(PANEL_REPLAY_PROPS_ID) || hidden.contains(PANEL_REPLAY_PROPS_ID);
        boolean changed = false;

        for (String id : List.of(PANEL_PREVIEW_2_ID, PANEL_PREVIEW_3_ID, PANEL_PREVIEW_4_ID))
        {
            if (!ids.contains(id))
            {
                changed = hidden.add(id) || changed;
            }
        }

        if (changed)
        {
            this.getFilmLayoutSettings().setHiddenPanels(this.currentLayoutId(), hidden);
        }

        if (hasList && hasProps)
        {
            return root;
        }

        EditorLayoutNode out = root == null ? EditorLayoutNode.defaultFilmLayout() : root;

        if (!hasList)
        {
            out = EditorLayoutNode.copyWithInsertSplitAt(out, PANEL_EDIT_AREA_ID, PANEL_REPLAYS_LIST_ID, EditorLayoutNode.EDGE_BOTTOM);
        }

        if (!hasProps)
        {
            out = EditorLayoutNode.copyWithInsertSplitAt(out, PANEL_REPLAYS_LIST_ID, PANEL_REPLAY_PROPS_ID, EditorLayoutNode.EDGE_RIGHT);
        }

        return out;
    }

    private void collectPanelIds(EditorLayoutNode node, HashSet<String> out)
    {
        if (node instanceof EditorLayoutNode.PanelNode)
        {
            out.add(((EditorLayoutNode.PanelNode) node).getPanelId());
        }
        else if (node instanceof EditorLayoutNode.StackNode)
        {
            out.addAll(((EditorLayoutNode.StackNode) node).getPanelIds());
        }
        else if (node instanceof EditorLayoutNode.SplitterNode)
        {
            EditorLayoutNode.SplitterNode s = (EditorLayoutNode.SplitterNode) node;
            this.collectPanelIds(s.getFirst(), out);
            this.collectPanelIds(s.getSecond(), out);
        }
    }











    private void fillFilmContextMenu(ContextMenuManager menu)
    {
        menu.action(Icons.FILM, UIKeys.FILM_TITLE, this::openFilmListOverlay);

        if (this.data == null)
        {
            return;
        }

        menu.action(Icons.SAVED, UIKeys.GENERAL_SAVE, this::save);
        menu.action(Icons.LAYOUT, UIKeys.FILM_LAYOUT_PRESETS, this::openLayoutPresetsMenu);
        menu.action(Icons.REFRESH, UIKeys.FILM_LAYOUT_RESET, this::resetFilmLayout);
        this.dock.fillHiddenPanelsMenu(menu);
        boolean layoutLocked = this.dock.isLocked();
        menu.action(layoutLocked ? Icons.UNLOCKED : Icons.LOCKED, layoutLocked ? UIKeys.FILM_LAYOUT_UNLOCK : UIKeys.FILM_LAYOUT_LOCK, layoutLocked, this::toggleLayoutLock);

        menu.action(Icons.LIST, UIKeys.FILM_OPEN_HISTORY, () ->
        {
            UIOverlay.addOverlay(this.getContext(), new UIUndoHistoryOverlay(this), 200, 0.6F);
        });

        menu.action(Icons.FILM, UIKeys.FILM_RENDER_QUEUE, this::startQueueExportFromOpenTabs);

        menu.action(Icons.ARROW_RIGHT, UIKeys.FILM_MOVE_TITLE, () ->
        {
            Film film = this.data;
            UIFilmMoveOverlayPanel panel = new UIFilmMoveOverlayPanel((vector) ->
            {
                if (film == null || film != this.data)
                {
                    return;
                }

                int duration = Math.max(1, film.calculateDuration());
                double dx = vector.x;
                double dy = vector.y;
                double dz = vector.z;

                BaseValue.edit(film, (__) ->
                {
                    this.addCameraTranslation(__.camera, duration, dx, dy, dz);

                    for (CameraTrack track : __.cameraTracks.getList())
                    {
                        boolean hasPose = track.clips.get().stream().anyMatch(clip -> clip.enabled.get() && CameraPosePolicy.allows(clip));

                        if (hasPose)
                        {
                            this.addCameraTranslation(track.clips, duration, dx, dy, dz);
                        }
                        else
                        {
                            Position position = track.copyPosition();

                            position.point.x += dx;
                            position.point.y += dy;
                            position.point.z += dz;
                            track.position.set(position);
                        }
                    }

                    for (Replay replay : __.replays.getList())
                    {
                        for (Keyframe<Double> keyframe : replay.keyframes.x.getKeyframes()) keyframe.setValue(keyframe.getValue() + dx);
                        for (Keyframe<Double> keyframe : replay.keyframes.y.getKeyframes()) keyframe.setValue(keyframe.getValue() + dy);
                        for (Keyframe<Double> keyframe : replay.keyframes.z.getKeyframes()) keyframe.setValue(keyframe.getValue() + dz);

                        replay.actions.shift(dx, dy, dz);
                    }
                });
            });

            panel.difference(this::getMoveToPlayerOffset);

            UIOverlay.addOverlay(this.getContext(), panel, 240, 140);
        });

        menu.action(Icons.TIME, UIKeys.FILM_INSERT_SPACE_TITLE, () ->
        {
            UINumberOverlayPanel panel = new UINumberOverlayPanel(UIKeys.FILM_INSERT_SPACE_TITLE, UIKeys.FILM_INSERT_SPACE_DESCRIPTION, (d) ->
            {
                if (d.intValue() <= 0)
                {
                    return;
                }

                for (Replay replay : this.data.replays.getList())
                {
                    for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
                    {
                        channel.insertSpace(this.getCursor(), d.intValue());
                    }

                    for (KeyframeChannel channel : replay.properties.properties.values())
                    {
                        channel.insertSpace(this.getCursor(), d.intValue());
                    }
                }
            });

            panel.value.limit(1).integer().setValue(1D);

            UIOverlay.addOverlay(this.getContext(), panel);
        });

        menu.action(Icons.GEAR, UIKeys.FILM_PLAYER_SETTINGS, () ->
        {
            UIOverlay.addOverlay(this.getContext(), new UIFilmPlayerSettingsOverlayPanel(this.getData(), this.getCursor()), 280, 0.4F);
        });

        menu.action(Icons.HELP, L10n.lang("bbs.ui.film.details.button"), () ->
        {
            UIOverlay.addOverlay(this.getContext(), new UIFilmDetailsOverlayPanel(this.getData()), 300, 260);
        });
    }

    private void addCameraTranslation(Clips clips, int duration, double dx, double dy, double dz)
    {
        TranslateClip clip = new TranslateClip();
        int topLayer = clips.getTopLayer();

        clip.layer.set(topLayer == Integer.MAX_VALUE ? topLayer : topLayer + 1);
        clip.duration.set(duration);
        clip.translate.get().set(dx, dy, dz);
        clips.addClip(clip);
    }

    /**
     * Relative move that snaps the scene's first position keyframe onto the
     * player's current position ({@code round} optionally snaps the player's
     * position to whole coordinates) — offered through the move overlay's menu.
     */
    private Vector3d getMoveToPlayerOffset(boolean round)
    {
        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null)
        {
            return new Vector3d();
        }

        Vector3d first = this.getFirstReplayPosition();
        double px = round ? Math.round(player.getX()) : player.getX();
        double py = round ? Math.round(player.getY()) : player.getY();
        double pz = round ? Math.round(player.getZ()) : player.getZ();

        return new Vector3d(px - first.x, py - first.y, pz - first.z);
    }

    private Vector3d getFirstReplayPosition()
    {
        Replay selected = this.replayEditor.getReplay();

        if (selected != null && (!selected.keyframes.x.isEmpty() || !selected.keyframes.y.isEmpty() || !selected.keyframes.z.isEmpty()))
        {
            return new Vector3d(
                selected.keyframes.x.isEmpty() ? 0 : selected.keyframes.x.get(0).getValue(),
                selected.keyframes.y.isEmpty() ? 0 : selected.keyframes.y.get(0).getValue(),
                selected.keyframes.z.isEmpty() ? 0 : selected.keyframes.z.get(0).getValue()
            );
        }

        for (Replay replay : this.data.replays.getList())
        {
            if (!replay.keyframes.x.isEmpty() || !replay.keyframes.y.isEmpty() || !replay.keyframes.z.isEmpty())
            {
                return new Vector3d(
                    replay.keyframes.x.isEmpty() ? 0 : replay.keyframes.x.get(0).getValue(),
                    replay.keyframes.y.isEmpty() ? 0 : replay.keyframes.y.get(0).getValue(),
                    replay.keyframes.z.isEmpty() ? 0 : replay.keyframes.z.get(0).getValue()
                );
            }
        }

        return new Vector3d();
    }

    private void openFilmListOverlay()
    {
        UIOverlay.addOverlay(this.getContext(), this.overlay, 200, 0.9F);
    }

    private void openLayoutPresetsMenu()
    {
        UIContext context = this.getContext();

        this.layoutPresetsController.openPresets(context, context.mouseX, context.mouseY);
    }

    @Override
    protected boolean shouldAutoOpenListOnFirstResize()
    {
        return false;
    }

    @Override
    public void resize()
    {
        super.resize();
        this.updateTabVisibility();
        this.editor.resize();

        boolean anySplitterDragging = this.dock.isSplitterDragging();
        if (!this.recorder.isExporting() && !anySplitterDragging
            && this.hasFilmInCurrentTab() && this.data != null
            && this.preview.area.w >= 2 && this.preview.area.h >= 2)
        {
            this.applyPreviewSizeToBBS("resize");
        }
    }

    public FilmQueueExporter getQueueExporter()
    {
        return this.queueExporter;
    }

    public void startQueueExportFromOpenTabs()
    {
        UIContext context = this.getContext();

        if (this.recorder.isExporting() || this.queueExporter != null)
        {
            return;
        }

        FilmQueueExporter exporter = FilmQueueExporter.fromOpenTabs(this);

        if (exporter == null)
        {
            if (context != null)
            {
                context.notifyError(UIKeys.FILM_RENDER_QUEUE_EMPTY);
            }

            return;
        }

        this.queueExporter = exporter;

        if (context != null)
        {
            context.notifyInfo(UIKeys.FILM_RENDER_QUEUE_STARTED.format(exporter.totalCount()));
        }

        exporter.start();
    }

    public void clearQueueExporter(FilmQueueExporter exporter)
    {
        if (this.queueExporter == exporter)
        {
            this.queueExporter = null;
        }
    }

    /**
     * Sets BBS fake window size to export resolution (from video settings).
     * Use when starting record, or when entering F1 fullscreen in film panel.
     */
    public static void applyExportSizeToBBS()
    {
        int w = Math.max(2, BBSSettings.videoSettings.width.get());
        int h = Math.max(2, BBSSettings.videoSettings.height.get());
        if (w % 2 != 0) w++;
        if (h % 2 != 0) h++;
        BBSRendering.setCustomSize(true, w, h);
    }

    /**
     * Restores BBS fake window size to the preview block size. Call after recording
     * ends so the preview is no longer at export resolution.
     */
    public void restorePreviewSize()
    {
        this.applyPreviewSizeToBBS("restorePreviewSize");
    }

    /**
     * Applies the preview or export size to BBSRendering. Film editors render the
     * same world preview, so automatic mode keeps the export aspect ratio and only
     * scales it to the available preview area. Called when the user finishes resizing
     * the preview, when the panel is laid out, and when switching editors.
     */
    private void applyPreviewSizeToBBS()
    {
        this.applyPreviewSizeToBBS("direct");
    }

    private void applyPreviewSizeToBBS(String source)
    {
        if (!this.hasFilmInCurrentTab() || this.data == null)
        {
            return;
        }

        if (this.recorder.isExporting())
        {
            return;
        }

        int w;
        int h;

        int previewMode = BBSSettings.editorPreviewSizeMode.get();

        if (previewMode == PREVIEW_MODE_EXPORT)
        {
            w = Math.max(2, BBSSettings.videoSettings.width.get());
            h = Math.max(2, BBSSettings.videoSettings.height.get());
        }
        else if (previewMode == PREVIEW_MODE_CUSTOM)
        {
            Vector2i resized = Vectors.resize(
                Math.max(2, BBSSettings.videoSettings.width.get()) / (float) Math.max(2, BBSSettings.videoSettings.height.get()),
                Math.max(2, BBSSettings.editorPreviewCustomWidth.get()),
                Math.max(2, BBSSettings.editorPreviewCustomHeight.get()));

            w = Math.max(2, resized.x);
            h = Math.max(2, resized.y);
        }
        else
        {
            float scale = BBSSettings.editorPreviewResolutionScale.get();
            boolean multiple = this.isPreviewPresent(PANEL_PREVIEW_2_ID) || this.isPreviewPresent(PANEL_PREVIEW_3_ID)
                || this.isPreviewPresent(PANEL_PREVIEW_4_ID);

            if (!multiple || this.singleViewPreviewWidth <= 0 || this.singleViewPreviewHeight <= 0)
            {
                this.singleViewPreviewWidth = Math.max(2, this.preview.area.w);
                this.singleViewPreviewHeight = Math.max(2, this.preview.area.h);
            }

            int exportW = Math.max(2, BBSSettings.videoSettings.width.get());
            int exportH = Math.max(2, BBSSettings.videoSettings.height.get());
            Vector2i resized = Vectors.resize(exportW / (float) exportH, this.singleViewPreviewWidth, this.singleViewPreviewHeight);

            w = Math.max(2, (int) (resized.x * scale));
            h = Math.max(2, (int) (resized.y * scale));
        }

        if (w % 2 != 0) w++;
        if (h % 2 != 0) h++;

        this.primaryView.setSize(w, h);

        boolean apply = !BBSRendering.isCustomSize() || w != BBSRendering.getVideoWidth() || h != BBSRendering.getVideoHeight();

        if (apply)
        {
            BBSRendering.setCustomSize(true, w, h);
        }
    }

    public void pickClip(Clip clip, UIClipsPanel panel)
    {
        if (panel == this.cameraEditor)
        {
            this.setFlight(false);
        }
    }

    /** Finish against the old clip before its property editor and selection are replaced. */
    public void prepareClipSelection(UIClipsPanel panel)
    {
        if (panel == this.cameraEditor)
        {
            String editedCameraId = this.editedCameraId;

            this.finishFlight(true, "clip-selection");

            if (!editedCameraId.equals(this.editedCameraId))
            {
                this.selectCameraTrack(editedCameraId);
            }
        }
    }

    public int getPanelIndex()
    {
        for (int i = 0; i < this.panels.size(); i++)
        {
            if (this.panels.get(i).isVisible())
            {
                return i;
            }
        }

        return -1;
    }

    public void showPanel(int index)
    {
        this.showPanel(this.panels.get(index));
    }

    public void showPanel(UIElement element)
    {
        this.cameraEditor.embedView(null);

        if (element == this.selectedMainEditorPanel && element.isVisible())
        {
            if (this.isFlying())
            {
                this.toggleFlight();
            }

            return;
        }

        EditorLayoutNode previousRoot = this.getCurrentFilmLayoutRoot();
        int index = this.getPanelIndex();

        if (index >= 0)
        {
            this.captureTimelineViewport(this.panels.get(index));
        }

        this.selectedMainEditorPanel = element;

        if (previousRoot != this.getCurrentFilmLayoutRoot())
        {
            this.dock.applyLayoutRoot(this.getCurrentFilmLayoutRoot());
        }
        else
        {
            this.updateMainEditorVisibility(this.hasFilmInCurrentTab());
        }

        this.applyTimelineViewport(element);

        this.applyPreviewSizeToBBS("showPanel");

        if (this.isFlying())
        {
            this.toggleFlight();
        }

    }

    private void captureTimelineViewport(UIElement panel)
    {
        if (panel == this.cameraEditor)
        {
            this.timelineXMin = this.cameraEditor.clips.scale.getMinValue();
            this.timelineXMax = this.cameraEditor.clips.scale.getMaxValue();
        }
        else if (panel == this.actionEditor)
        {
            this.timelineXMin = this.actionEditor.clips.scale.getMinValue();
            this.timelineXMax = this.actionEditor.clips.scale.getMaxValue();
        }
        else if (panel == this.replayEditor && this.replayEditor.keyframeEditor != null)
        {
            this.timelineXMin = this.replayEditor.keyframeEditor.view.getXAxis().getMinValue();
            this.timelineXMax = this.replayEditor.keyframeEditor.view.getXAxis().getMaxValue();
        }
    }

    private void applyTimelineViewport(UIElement panel)
    {
        if (Double.isNaN(this.timelineXMin) || Double.isNaN(this.timelineXMax) || this.timelineXMin >= this.timelineXMax)
        {
            return;
        }

        if (panel == this.cameraEditor)
        {
            this.cameraEditor.clips.scale.view(this.timelineXMin, this.timelineXMax);
        }
        else if (panel == this.actionEditor)
        {
            this.actionEditor.clips.scale.view(this.timelineXMin, this.timelineXMax);
        }
        else if (panel == this.replayEditor && this.replayEditor.keyframeEditor != null)
        {
            this.replayEditor.keyframeEditor.view.getXAxis().view(this.timelineXMin, this.timelineXMax);
        }
    }

    private void captureTimelineScroll()
    {
        if (this.data == null || this.data.getId() == null)
        {
            return;
        }

        double replayScroll = 0D;

        if (this.replayEditor.keyframeEditor != null)
        {
            replayScroll = this.replayEditor.keyframeEditor.view.getDopeSheet().getYAxis().getScroll();
        }

        this.timelineScrollByFilm.capture(
            this.data.getId(),
            this.cameraEditor.clips.vertical.getScroll(),
            this.actionEditor.clips.vertical.getScroll(),
            replayScroll
        );
    }

    private void restoreTimelineScroll()
    {
        if (this.data == null || this.data.getId() == null)
        {
            return;
        }

        FilmEditorMigrationLogic.TimelineScroll scroll = this.timelineScrollByFilm.get(this.data.getId());

        if (scroll == null)
        {
            return;
        }

        this.cameraEditor.clips.restoreVerticalScroll(scroll.camera);
        this.actionEditor.clips.restoreVerticalScroll(scroll.action);

        if (this.replayEditor.keyframeEditor != null)
        {
            this.replayEditor.keyframeEditor.view.getDopeSheet().getYAxis().setScroll(scroll.replay);
        }
    }

    public UIFilmController getController()
    {
        return this.controller;
    }

    public List<UIFilmPreview> getPreviews()
    {
        return List.of(this.preview, this.preview2, this.preview3, this.preview4);
    }

    public UIFilmPreview getActivePreview()
    {
        return this.activePreview == null ? this.preview : this.activePreview;
    }

    public void activatePreview(UIFilmPreview preview)
    {
        if (preview != null && this.activePreview != preview)
        {
            this.finishFlight(false);

            if (this.activePreview != null)
            {
                this.activePreview.cancelViewInteraction();
            }

            this.activePreview = preview;
        }
    }

    public UIFilmPreview getPreview(String viewId)
    {
        for (UIFilmPreview preview : this.getPreviews())
        {
            if (preview.getViewDescriptor().getId().equals(viewId))
            {
                return preview;
            }
        }

        return this.preview;
    }

    public CameraPoseEvaluator getCameraPoseEvaluator()
    {
        return this.cameraPoseEvaluator;
    }

    public String getEditedCameraId()
    {
        return this.editedCameraId;
    }

    public void selectCameraTrack(String cameraId)
    {
        String id = this.data != null && this.data.hasCamera(cameraId) ? cameraId : Film.LEGACY_CAMERA_ID;

        if (!id.equals(this.editedCameraId) || this.cameraEditor.clips.getClips() != (this.data == null ? null : this.data.getCameraClips(id)))
        {
            this.cameraEditor.embedView(null);
            this.cameraEditor.pickClip(null);
            this.editedCameraId = id;
            this.cameraEditor.setClips(this.data == null ? null : this.data.getCameraClips(id));
            this.runner.setEditorCameraId(id);
        }
    }

    public void editCameraTrack(String cameraId)
    {
        this.selectCameraTrack(cameraId);
        this.showPanel(this.cameraEditor);
        this.saveViewSettings();
    }

    public void performCameraEdit(Runnable edit)
    {
        if (this.data == null || this.recorder.isExporting())
        {
            return;
        }

        if (this.undoHandler != null)
        {
            this.undoHandler.submitUndo();
            this.undoHandler.getUndoManager().markLastUndoNoMerging();
        }

        edit.run();
        this.cameraPoseEvaluator.invalidate();

        for (ViewDescriptor view : this.getViewDescriptors())
        {
            view.invalidateHistory();
        }

        if (this.undoHandler != null)
        {
            this.undoHandler.submitUndo();
            this.undoHandler.getUndoManager().markLastUndoNoMerging();
        }

        this.fillData();
        this.saveViewSettings();
    }

    public void deleteCamera(String cameraId)
    {
        this.performCameraEdit(() ->
        {
            if (this.data.removeCamera(cameraId))
            {
                for (UIFilmPreview preview : this.getPreviews())
                {
                    ViewDescriptor view = preview.getViewDescriptor();

                    if (cameraId.equals(view.getCameraId()))
                    {
                        preview.cancelViewInteraction();
                        view.setCameraId(Film.LEGACY_CAMERA_ID);
                    }
                }

                this.selectCameraTrack(this.editedCameraId);
            }
        });
    }

    public void writeCameraPose(ViewDescriptor view, Position pose)
    {
        this.writeCameraPose(view.resolveCameraId(), pose);
    }

    public void writeCameraPose(String cameraId, Position pose)
    {
        if (this.data == null || !this.data.hasCamera(cameraId))
        {
            return;
        }

        this.finishFlight(false, "explicit-write");
        this.writeCameraPose(cameraId, pose, cameraId.equals(this.editedCameraId) ? this.cameraEditor.getClip() : null);
    }

    private void writeCameraPose(String cameraId, Position pose, Clip selected)
    {
        if (this.data == null || !this.data.hasCamera(cameraId))
        {
            return;
        }

        this.performCameraEdit(() ->
        {
            CameraTrack track = this.data.getCameraTrack(cameraId);
            Clips clips = this.data.getCameraClips(cameraId);

            if (track != null && clips.get().isEmpty())
            {
                track.position.set(pose);
                this.logCameraWrite(cameraId, null, pose);

                return;
            }

            this.selectCameraTrack(cameraId);
            Clip editable = CameraPoseEditing.findEditableClip(clips, selected, this.getCursor());

            if (editable != null)
            {
                this.cameraEditor.pickClip(editable);
                this.cameraEditor.editClip(pose);
                this.logCameraWrite(cameraId, editable, pose);
            }
            else
            {
                KeyframeClip clip = new KeyframeClip();
                Camera camera = new Camera();

                pose.apply(camera);
                clip.fromCamera(camera);
                clip.tick.set(this.getCursor());
                clip.duration.set(1);
                clip.layer.set(clips.getTopLayer() == Integer.MAX_VALUE ? Integer.MAX_VALUE : clips.getTopLayer() + 1);
                clips.addClip(clip);
                this.cameraEditor.pickClip(clip);
                this.logCameraWrite(cameraId, clip, pose);
            }
        });
    }

    private void logCameraWrite(String cameraId, Clip clip, Position requested)
    {
        try
        {
            CameraPoseEvaluator evaluator = new CameraPoseEvaluator();

            evaluator.beginFrame(0L, this.data, this.getCursor(), 0F, false, this.controller.getEntities());
            Position evaluated = evaluator.evaluateEditedEnd(cameraId, clip, this.data.getCameraBasePosition(cameraId));

            LOGGER.info("[FilmFlight] write film={} camera={} tick={} target={} requested={} evaluated={}",
                this.data.getId(), cameraId, this.getCursor(), cameraClipLog(clip), cameraPoseLog(requested), cameraPoseLog(evaluated));
        }
        catch (RuntimeException exception)
        {
            /* Diagnostics must not interrupt the edit's undo/history completion. */
            LOGGER.warn("[FilmFlight] could not sample written camera={} tick={} target={}",
                cameraId, this.getCursor(), cameraClipLog(clip), exception);
        }
    }

    private static String cameraClipLog(Clip clip)
    {
        return clip == null ? "base" : clip.getClass().getSimpleName() + "@" + clip.tick.get()
            + "+" + clip.duration.get() + "/layer=" + clip.layer.get();
    }

    private static String cameraPoseLog(Position pose)
    {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f) yaw=%.2f pitch=%.2f roll=%.2f fov=%.2f",
            pose.point.x, pose.point.y, pose.point.z, pose.angle.yaw, pose.angle.pitch, pose.angle.roll, pose.angle.fov);
    }

    public double getAuxiliaryBudgetFraction()
    {
        return this.viewPerformanceSettings.getAuxiliaryBudgetFraction();
    }

    public void setAuxiliaryBudgetFraction(double fraction)
    {
        this.viewPerformanceSettings.setAuxiliaryBudgetFraction(fraction);
        this.saveViewSettings();

        for (UIFilmPreview preview : this.getPreviews())
        {
            preview.requestRefresh();
        }
    }

    public void saveViewSettings()
    {
        if (this.viewSettingsFilmId == null)
        {
            return;
        }

        MapType settings = this.getFilmLayoutSettings().getFilmViewSettings();
        MapType film = new MapType();

        for (UIFilmPreview preview : this.getPreviews())
        {
            film.put(preview.getViewDescriptor().getId(), preview.getViewSettings());
        }

        film.putString("edited_camera", this.editedCameraId);
        film.put("performance", this.viewPerformanceSettings.toData());
        settings.put(this.viewSettingsFilmId, film);
        this.getFilmLayoutSettings().setFilmViewSettings(settings);
    }

    private void loadViewSettings(Film film)
    {
        this.viewSettingsFilmId = film == null ? null : film.getId();
        MapType settings = this.viewSettingsFilmId == null ? new MapType()
            : this.getFilmLayoutSettings().getFilmViewSettings().getMap(this.viewSettingsFilmId);

        this.viewPerformanceSettings.fromData(settings.getMap("performance"));

        for (UIFilmPreview preview : this.getPreviews())
        {
            preview.loadViewSettings(settings.getMap(preview.getViewDescriptor().getId()));
        }

        this.editedCameraId = settings.getString("edited_camera", Film.LEGACY_CAMERA_ID);

        if (film != null && !film.hasCamera(this.editedCameraId))
        {
            this.editedCameraId = Film.LEGACY_CAMERA_ID;
        }

        this.runner.setEditorCameraId(this.editedCameraId);
        this.activePreview = this.preview;
    }

    public UIFilmUndoHandler getUndoHandler()
    {
        return this.undoHandler;
    }

    public RunnerCameraController getRunner()
    {
        return this.runner;
    }

    @Override
    protected UICRUDOverlayPanel createOverlayPanel()
    {
        UIFilmOverlayPanel crudPanel = new UIFilmOverlayPanel(this.getTitle(), this, this::pickData);

        this.duplicateFilm = new UIIcon(Icons.SCENE, (b) ->
        {
            UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
                UIKeys.GENERAL_DUPE,
                UIKeys.PANELS_MODALS_DUPE,
                (str) -> this.dupeData(crudPanel.namesList.getPath(str).toString())
            );

            panel.text.setText(crudPanel.namesList.getCurrentFirst().getLast());
            panel.text.filename();

            UIOverlay.addOverlay(this.getContext(), panel);
        });

        crudPanel.icons.add(this.duplicateFilm);

        return crudPanel;
    }

    private void dupeData(String name)
    {
        if (this.getData() != null && !this.overlay.namesList.hasInHierarchy(name))
        {
            this.save();
            this.overlay.namesList.addFile(name);

            Film data = this.createDuplicateFilm(name, this.data);

            this.fill(data);
            this.save();
        }
    }

    public void dupeCurrentFilmTo(String name)
    {
        this.dupeData(name);
    }

    public void dupeFilmTo(String sourceId, String name)
    {
        if (name == null || name.trim().isEmpty())
        {
            return;
        }

        Film current = this.getData();

        if (current != null && (sourceId == null || sourceId.equals(current.getId())))
        {
            this.dupeData(name);

            return;
        }

        if (sourceId == null || sourceId.trim().isEmpty() || this.overlay.namesList.hasInHierarchy(name))
        {
            return;
        }

        this.save();

        this.getRepository().load(sourceId, (loaded) ->
        {
            Film source = (Film) loaded;

            if (source == null)
            {
                return;
            }

            Film duplicated = this.createDuplicateFilm(name, source);

            this.fill(duplicated);
            this.save();
            this.requestNames();
        });
    }

    private Film createDuplicateFilm(String name, Film source)
    {
        Film data = new Film();
        Position position = new Position();
        IdleClip idle = new IdleClip();
        int tick = this.getCursor();

        position.set(this.getCamera());
        idle.duration.set(BBSSettings.getDefaultDuration());
        idle.position.set(position);
        data.camera.addClip(idle);
        data.setId(name);
        data.stampCreationTimeNow();

        for (Replay replay : source.replays.getList())
        {
            Replay copy = new Replay(replay.getId());

            copy.form.set(FormUtils.copy(replay.form.get()));

            for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
            {
                if (!channel.isEmpty())
                {
                    KeyframeChannel newChannel = (KeyframeChannel) copy.keyframes.get(channel.getId());

                    newChannel.insert(0, channel.interpolate(tick));
                }
            }

            for (Map.Entry<String, KeyframeChannel> entry : replay.properties.properties.entrySet())
            {
                KeyframeChannel channel = entry.getValue();

                if (channel.isEmpty())
                {
                    continue;
                }

                KeyframeChannel newChannel = new KeyframeChannel(channel.getId(), channel.getFactory());
                KeyframeSegment segment = channel.find(tick);

                if (segment != null)
                {
                    newChannel.insert(0, segment.createInterpolated());
                }

                if (!newChannel.isEmpty())
                {
                    copy.properties.properties.put(newChannel.getId(), newChannel);
                    copy.properties.add(newChannel);
                }
            }

            data.replays.add(copy);
        }

        return data;
    }

    @Override
    public void open()
    {
        super.open();

        Recorder recorder = BBSModClient.getFilms().stopRecording();
        Film film = this.data;

        if (recorder == null
            || film == null
            || !Objects.equals(film.getId(), recorder.getRecordingFilmId())
            || !CollectionUtils.inRange(film.replays.getList(), recorder.getRecordingReplayId())
            || !recorder.hasRecordedFrame()
            || !recorder.isInCurrentLevel())
        {
            this.notifyServer(ActionState.RESTART);

            return;
        }

        this.applyRecordedKeyframes(recorder, film);
    }

    /** Preserve the legacy direct-addon JVM descriptor and action-only behavior. */
    public void receiveActions(String filmId, int replayId, int tick, BaseType clips)
    {
        this.receiveActions(
            filmId,
            replayId,
            tick,
            clips,
            null,
            false,
            true,
            ServerNetwork.RecordingTerminal.LEGACY_MANUAL
        );
    }

    public void receiveActions(
        String filmId,
        int replayId,
        int tick,
        BaseType clips,
        Recorder recorder,
        boolean applyKeyframes,
        boolean mergeActions,
        ServerNetwork.RecordingTerminal recordingTerminal
    )
    {
        Film film = this.data;

        if (film != null && film.getId().equals(filmId) && CollectionUtils.inRange(film.replays.getList(), replayId))
        {
            boolean changed = false;

            boolean exactRecorder = recorder != null && recorder.matchesRecording(filmId, replayId, tick);

            if (applyKeyframes && exactRecorder && recorder.hasRecordedFrame())
            {
                this.applyRecordedKeyframes(recorder, film);
                changed = true;
            }

            /* A forced server terminal can legitimately carry an empty clip
             * list (for example when its countdown never started).  Empty
             * forced data is an acknowledgement/teardown, not an instruction
             * to remove the replay's existing actions from this tick onward.
             * Legacy/manual terminals retain the historical empty replacement
             * behavior used by the editor's explicit stop action. */
            boolean mergeTerminalActions = mergeActions
                && clips != null
                && clips.isList()
                && recordingTerminal != ServerNetwork.RecordingTerminal.START_REJECTED
                && (recordingTerminal == ServerNetwork.RecordingTerminal.LEGACY_MANUAL
                    || !clips.asList().isEmpty());

            if (mergeTerminalActions)
            {
                BaseValue.edit(film.replays.getList().get(replayId), IValueListener.FLAG_UNMERGEABLE, (replay) ->
                {
                    Clips newClips = new Clips("", BBSMod.getFactoryActionClips());

                    newClips.fromData(clips);
                    replay.actions.copyOver(newClips, tick);
                });

                changed = true;
            }

            if (changed)
            {
                this.save();
            }
        }
    }

    public void applyRecordedKeyframes(Recorder recorder, Film film)
    {
        int replayId = recorder.getRecordingReplayId();
        Replay rp = CollectionUtils.getSafe(film.replays.getList(), replayId);

        recorder.keyframes.compressItemChannels();

        if (rp != null)
        {
            BaseValue.edit(film, (f) ->
            {
                rp.keyframes.copyOver(recorder.keyframes, 0);

                Form form = rp.form.get();

                if (form != null)
                {
                    for (Map.Entry<String, KeyframeChannel> entry : recorder.properties.properties.entrySet())
                    {
                        KeyframeChannel channel = rp.properties.getOrCreate(form, entry.getKey());

                        if (channel != null && entry.getValue() != null)
                        {
                            channel.copyOver(entry.getValue(), 0);
                        }
                    }
                }

                f.hp.set(recorder.hp);
                f.hunger.set(recorder.hunger);
                f.xpLevel.set(recorder.xpLevel);
                f.xpProgress.set(recorder.xpProgress);
            });
        }

        this.applyRecordedMobs(recorder, film);
    }

    private void applyRecordedMobs(Recorder recorder, Film film)
    {
        if (recorder.mobs.isEmpty())
        {
            return;
        }

        BaseValue.edit(film, (f) ->
        {
            for (Recorder.RecordedMob mob : recorder.mobs)
            {
                Replay replay = f.replays.addReplay();

                mob.keyframes.compressItemChannels();

                replay.category.set("");
                replay.form.set(mob.form);
                replay.keyframes.copyOver(mob.keyframes, 0);
            }
        });

        this.replayEditor.replaysList.replays.refreshReplayList();
        this.controller.createEntities();
    }

    @Override
    public void appear()
    {
        super.appear();

        this.activateLifecycle();
    }

    private void activateLifecycle()
    {
        /* appear() also fires while the dashboard is being lazily constructed (the
         * teleport/record keybinds create it on first use), at which point there's no
         * context and the editor isn't actually shown. Defer activation until update()
         * runs for the genuinely visible panel, and keep it idempotent so the runner
         * can never be registered twice. */
        if (this.lifecycleActive || this.getContext() == null)
        {
            return;
        }

        if (this.data != null)
        {
            BBSModClient.getFilms().unfreeze(this.data.getId());
        }

        this.lifecycleActive = true;
        BBSFilmCollaborationBridge.attach(this, this.getData());
        BBSRendering.setCustomSize(true);
        MorphRenderer.hidePlayer = true;

        CameraController cameraController = this.getCameraController();

        this.fillData();
        this.applyPreviewSizeToBBS("appear");
        this.setFlight(false);
        cameraController.add(this.runner);

        this.getContext().menu.getRoot().add(this.secretPlay);
    }

    @Override
    public void close()
    {
        this.cancelViewInteractions();
        this.saveViewSettings();
        this.releaseViewResources();
        this.recorder.cancel();
        UIAudioRecorder.cancelActive(this);
        this.controller.shutdown();
        this.flushFilmCollaborationEdits();
        BBSFilmCollaborationBridge.detach(this);

        if (this.queueExporter != null)
        {
            this.queueExporter.cancel();
        }

        super.close();
        BBSRendering.setCustomSize(false);
        MorphRenderer.hidePlayer = false;

        CameraController cameraController = this.getCameraController();

        this.cameraEditor.embedView(null);
        this.setFlight(false);
        cameraController.remove(this.runner);
        this.lifecycleActive = false;

        this.disableContext();
        this.replayEditor.close();

        this.notifyServer(ActionState.STOP);
        this.freezeFrame();
    }

    /** Keep the visible editor frame in the world after the active Film panel closes. */
    private void freezeFrame()
    {
        if (this.data == null
            || Minecraft.getInstance().level == null
            || this.dashboard.getPanels().panel != this)
        {
            return;
        }

        if (BBSSettings.editorKeepFrameOnExit.get())
        {
            BBSModClient.getFilms().freeze(this.data, this.getCursor(), this.controller.isPaused());
        }
        else
        {
            BBSModClient.getFilms().unfreeze(this.data.getId());
        }
    }

    @Override
    public void disappear()
    {
        this.cancelViewInteractions();
        this.saveViewSettings();
        this.releaseViewResources();
        this.recorder.cancel();
        UIAudioRecorder.cancelActive(this);
        this.controller.shutdown();
        this.flushFilmCollaborationEdits();
        BBSFilmCollaborationBridge.detach(this);
        super.disappear();

        BBSRendering.setCustomSize(false);
        MorphRenderer.hidePlayer = false;

        this.setFlight(false);
        this.getCameraController().remove(this.runner);
        this.lifecycleActive = false;

        this.disableContext();
        this.secretPlay.removeFromParent();
    }

    private void disableContext()
    {
        UIAudioRecorder.cancelActive(this);
    }

    private void cancelViewInteractions()
    {
        this.finishFlight(false);

        for (UIFilmPreview preview : this.getPreviews())
        {
            preview.cancelViewInteraction();
        }
    }

    private void releaseViewResources()
    {
        for (UIFilmPreview preview : this.getPreviews())
        {
            preview.getViewController().releaseViewResources();
        }
    }

    @Override
    public boolean needsBackground()
    {
        return true;
    }

    @Override
    public boolean canPause()
    {
        return false;
    }

    @Override
    public boolean canRefresh()
    {
        return false;
    }

    @Override
    public ContentType getType()
    {
        return ContentType.FILMS;
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.FILM_TITLE;
    }

    @Override
    public void fillDefaultData(Film data)
    {
        super.fillDefaultData(data);

        IdleClip clip = new IdleClip();
        Camera camera = new Camera();
        Minecraft mc = Minecraft.getInstance();

        camera.set(mc.player, MathUtils.toRad(mc.options.fov().get().floatValue()));

        clip.layer.set(8);
        clip.duration.set(BBSSettings.getDefaultDuration());
        clip.fromCamera(camera);
        data.camera.addClip(clip);

        data.stampCreationTimeNow();

        this.newFilm = true;
    }

    @Override
    public void fill(Film data)
    {
        boolean wasNewFilm = this.newFilm && data != null;

        /* UIFormUndoHandler batches edits until render. Flush while the old Film is
         * still this panel's active data so a close/tab switch cannot lose its last
         * local collaboration mutation. submitUndo() is idempotent when empty. */
        this.flushFilmCollaborationEdits();
        this.captureTimelineScroll();
        this.notifyServer(ActionState.STOP);
        super.fill(data);
        this.restoreTimelineScroll();

        if (wasNewFilm)
        {
            this.forceSave();
        }

        if (data != null)
        {
            /* Capture the first visible frame after an asynchronous film load. */
            FilmThumbnails.requestCapture(data.getId());
        }

        this.notifyServer(ActionState.RESTART);
    }

    /** Flush the undo batch before a remote CAS check or session teardown. */
    public void flushFilmCollaborationEdits()
    {
        if (this.undoHandler != null)
        {
            this.undoHandler.submitUndo();
        }

        BBSFilmCollaborationBridge.flushPending(this);
    }

    @Override
    public void forceSave()
    {
        Throwable failure = null;

        try
        {
            this.flushFilmCollaborationEdits();
        }
        catch (RuntimeException | Error exception)
        {
            failure = exception;
        }

        try
        {
            if (this.data != null)
            {
                /* Keep the persisted last-modified time at the save boundary. */
                this.data.stampUpdatedTimeNow();
                FilmThumbnails.requestCapture(this.data.getId());
            }

            /* The base panel owns the repository selected when this Film data
             * session started. Always attempt that persistence even when the
             * collaboration transport failed during teardown. */
            super.forceSave();
        }
        catch (RuntimeException | Error exception)
        {
            if (failure == null)
            {
                failure = exception;
            }
            else if (failure != exception)
            {
                failure.addSuppressed(exception);
            }
        }

        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        else if (failure instanceof Error error)
        {
            throw error;
        }
    }

    @Override
    protected void fillData(Film data)
    {
        this.cancelViewInteractions();
        this.saveViewSettings();

        if (this.data != null)
        {
            this.disableContext();
        }

        if (data != null)
        {
            this.undoHandler = new UIFilmUndoHandler(this);

            data.preCallback(this.undoHandler::handlePreValues);
        }
        else
        {
            this.undoHandler = null;
        }

        this.openFilmMenu.setEnabled(true);
        this.openCameraEditor.setEnabled(data != null);
        this.openReplayEditor.setEnabled(data != null);
        this.openActionEditor.setEnabled(data != null);
        this.duplicateFilm.setEnabled(data != null);

        this.actionEditor.setClips(null);
        this.runner.setWork(data == null ? null : data.camera);
        this.replayEditor.setFilm(data);
        this.loadViewSettings(data);
        this.cameraEditor.setClips(data == null ? null : data.getCameraClips(this.editedCameraId));
        this.cameraEditor.pickClip(null);

        this.fillData();
        this.controller.createEntities();

        if (this.newFilm && this.data != null && !this.data.camera.get().isEmpty())
        {
            Clip main = this.data.camera.get(0);

            this.cameraEditor.clips.setSelected(main);
            this.cameraEditor.pickClip(main);
        }

        this.entered = data != null;
        this.newFilm = false;

        if (data != null)
        {
            this.filmUserActivity.onFilmOpened();
        }
        else
        {
            this.filmUserActivity.reset();
        }

        if (this.lifecycleActive)
        {
            BBSFilmCollaborationBridge.attach(this, data);
        }
        else if (data == null)
        {
            BBSFilmCollaborationBridge.detach(this);
        }

        this.updateTabVisibility();
    }

    @Override
    public void fillNames(Collection<String> names)
    {
        super.fillNames(names);

        if (this.selectionPanel != null)
        {
            this.selectionPanel.fillNames(names);
        }
    }

    public void undo()
    {
        this.finishFlight(false, "undo");
        if (this.data != null && this.undoHandler.getUndoManager().undo(this.data)) UIUtils.playClick();
    }

    public void redo()
    {
        this.finishFlight(false, "redo");
        if (this.data != null && this.undoHandler.getUndoManager().redo(this.data)) UIUtils.playClick();
    }

    public boolean isFlying()
    {
        return this.dashboard.orbitUI.canControl();
    }

    @Override
    public boolean shouldEnableFlightOnRestore()
    {
        return false;
    }

    public void toggleFlight()
    {
        if (this.isFlying())
        {
            this.finishFlight(true);
        }
        else
        {
            this.setFlight(true);
        }
    }

    public boolean isViewFlying(UIFilmPreview preview)
    {
        return this.isFlying() && this.flightPreview == preview;
    }

    public void cancelFlight(UIFilmPreview preview)
    {
        if (this.flightPreview == preview)
        {
            this.finishFlight(false);
        }
    }

    /** Programmatic shutdown cancels an edit; the flight toggle commits it. */
    public void setFlight(boolean flight)
    {
        if (!flight)
        {
            this.finishFlight(false);

            return;
        }

        if (this.isRunning() || this.recorder.isExporting() || this.data == null || this.flightPreview != null)
        {
            return;
        }

        UIFilmPreview preview = this.getActivePreview();
        ViewNavigationState navigation = preview.getViewDescriptor().getNavigation();

        preview.cancelViewInteraction();
        Camera camera = new Camera();

        camera.copy(preview.getViewDescriptor().resolveCamera(0F));

        this.flightPreview = preview;
        this.flightFilm = this.data;
        this.flightCameraId = preview.getViewDescriptor().resolveCameraId();
        this.flightTick = this.getCursor();
        this.flightClip = CameraPoseEditing.findEditableClip(this.data.getCameraClips(this.flightCameraId),
            this.flightCameraId.equals(this.editedCameraId) ? this.cameraEditor.getClip() : null, this.flightTick);
        this.flightSequence++;
        /* Flight explicitly edits the viewed camera; the lock only governs ordinary view dragging. */
        this.flightEditsCamera = navigation.isInCameraView();
        this.flightStartOrbit = preview.getViewController().orbit.toData();
        this.flightStartPose.set(camera);
        this.position.set(camera);
        this.dashboard.orbit.setup(camera);

        if (this.flightEditsCamera)
        {
            navigation.beginCameraEdit(camera);
        }

        this.runner.setManual(preview.isPrimaryView() ? this.position : null);
        this.dashboard.orbitUI.setControl(true);

        LOGGER.info("[FilmFlight] start #{} film={} view={} camera={} tick={} editingCamera={} locked={} target={} pose={}",
            this.flightSequence, this.data.getId(), preview.getViewDescriptor().getId(), this.flightCameraId,
            this.flightTick, this.flightEditsCamera, navigation.isLockCameraToView(), cameraClipLog(this.flightClip), cameraPoseLog(this.flightStartPose));
    }

    private void finishFlight(boolean commit)
    {
        this.finishFlight(commit, commit ? "toggle" : "cancel");
    }

    private void finishFlight(boolean commit, String reason)
    {
        UIFilmPreview preview = this.flightPreview;
        Film film = this.flightFilm;
        String cameraId = this.flightCameraId;
        Clip clip = this.flightClip;
        boolean editsCamera = this.flightEditsCamera;
        MapType orbit = this.flightStartOrbit;

        if (preview != null && this.isFlying())
        {
            /* A click or seek can arrive before the next render copies the latest input pose. */
            this.dashboard.orbit.apply(this.position);
        }

        this.flightPreview = null;
        this.flightFilm = null;
        this.flightCameraId = null;
        this.flightClip = null;
        this.flightEditsCamera = false;
        this.flightStartOrbit = null;
        this.runner.setManual(null);
        this.dashboard.orbitUI.setControl(false);

        if (preview == null)
        {
            return;
        }

        ViewDescriptor view = preview.getViewDescriptor();
        ViewNavigationState navigation = view.getNavigation();

        preview.getViewController().orbit.resetVelocity();
        navigation.endCameraEdit();

        boolean targetValid = film == this.data && film.hasCamera(cameraId) && this.getCursor() == this.flightTick
            && cameraId.equals(view.resolveCameraId()) && (clip == null || film.getCameraClips(cameraId).getIndex(clip) >= 0);
        boolean changed = !this.flightStartPose.equals(this.position);
        boolean write = editsCamera && commit && targetValid && changed;

        LOGGER.info("[FilmFlight] finish #{} reason={} film={} view={} camera={} startTick={} currentTick={} editingCamera={} changed={} valid={} write={} pose={}",
            this.flightSequence, reason, film.getId(), view.getId(), cameraId, this.flightTick, this.getCursor(),
            editsCamera, changed, targetValid, write, cameraPoseLog(this.position));

        if (write)
        {
            this.writeCameraPose(cameraId, this.position.copy(), clip);
        }
        else if (!editsCamera)
        {
            if (!commit)
            {
                navigation.getFreePose().set(this.flightStartPose);

                if (orbit != null)
                {
                    preview.getViewController().orbit.fromData(orbit);
                }
            }
            else
            {
                navigation.getFreePose().set(this.position);

                if (preview.isPrimaryView() && BBSSettings.fov != null)
                {
                    BBSSettings.fov.set(this.position.angle.fov);
                }
            }
        }

        preview.requestRefresh();
        this.saveViewSettings();
    }

    public Vector2i getLoopingRange()
    {
        Clip clip = this.cameraEditor.getClip();

        int min = -1;
        int max = -1;

        if (clip != null)
        {
            min = clip.tick.get();
            max = min + clip.duration.get();
        }

        UIClips clips = this.cameraEditor.clips;

        if (clips.loopMin != clips.loopMax && clips.loopMin >= 0 && clips.loopMin < clips.loopMax)
        {
            min = clips.loopMin;
            max = clips.loopMax;
        }

        max = Math.min(max, this.data.calculateDuration());

        return new Vector2i(min, max);
    }

    @Override
    public void update()
    {
        this.activateLifecycle();

        if (this.getContext() != null && this.secretPlay.getParent() == null)
        {
            this.getContext().menu.getRoot().add(this.secretPlay);
        }

        this.playerToCamera = BBSSettings.editorPlayerFollowsCamera.get();
        this.controller.update();
        this.updateRestartOnSeek();

        if (this.playerToCamera && this.data != null && !this.controller.isControlling())
        {
            this.teleportToCamera();
        }

        super.update();
    }

    /* Rendering code */

    @Override
    public void renderPanelBackground(UIContext context)
    {
        super.renderPanelBackground(context);

        Texture texture = BBSRendering.getTexture();

        if (texture != null && BBSRendering.isCustomSize())
        {
            context.batcher.box(0, 0, context.menu.width, context.menu.height, Colors.A100);

            int w = context.menu.width;
            int h = context.menu.height;
            Vector2i resize = Vectors.resize(texture.width / (float) texture.height, w, h);
            Area area = new Area();

            area.setSize(resize.x, resize.y);
            area.setPos((w - area.w) / 2, (h - area.h) / 2);

            context.batcher.texturedBox(texture.id, Colors.WHITE, area.x, area.y, area.w, area.h, 0, texture.height, texture.width, 0, texture.width, texture.height);
        }

        this.updateLogic(context);
    }

    @Override
    protected void renderBackground(UIContext context)
    {
        super.renderBackground(context);
    }

    private void renderTopBarActions(UIContext context)
    {
        if (this.topBarActions == null || !this.topBarActions.isVisible())
        {
            return;
        }

        this.renderTopBarButton(context, this.openCameraEditor, this.cameraEditor.isVisible());
        this.renderTopBarButton(context, this.openReplayEditor, this.replayEditor.isVisible());
        this.renderTopBarButton(context, this.openActionEditor, this.actionEditor.isVisible());
        this.renderTopBarSeparator(context);
        this.renderTopBarButton(context, this.openFilmMenu, false);
    }

    private void renderTopBarButton(UIContext context, UIIcon button, boolean active)
    {
        if (button == null || !button.isVisible())
        {
            return;
        }

        button.active(active);

        Area area = button.area;
        boolean hover = area.isInside(context.mouseX, context.mouseY);

        if (BBSSettings.cornerWidget() > 0)
        {
            return;
        }

        if (active)
        {
            UIDashboardPanels.renderHighlight(context.batcher, area, Direction.BOTTOM);
        }
        else if (hover)
        {
            context.batcher.box(area.x, area.y, area.ex(), area.ey(), BBSSettings.color(BBSSettings.raisedSurface(), Colors.A25));
        }
    }

    private void renderTopBarSeparator(UIContext context)
    {
        if (this.topBarSeparator == null || !this.topBarSeparator.isVisible())
        {
            return;
        }

        Area area = this.topBarSeparator.area;
        int x = area.mx();

        context.batcher.box(x, area.y + 3, x + 1, area.ey() - 3, BBSSettings.dividerColor());
    }

    /**
     * Draw everything on the screen
     */
    @Override
    public void render(UIContext context)
    {
        if (this.lastTime == 0)
        {
            this.lastTime = System.currentTimeMillis();
        }

        long now = System.currentTimeMillis();
        long diff = now - this.lastTime;

        this.lastTime = now;

        if (this.getData() != null)
        {
            Minecraft mc = Minecraft.getInstance();

            if (this.filmUserActivity.shouldAccumulateActiveTime(mc, context, now))
            {
                this.timeSpentActiveAccumulator += diff;
            }

            /* Batch updates to once per second to avoid undo history pollution
             * and reduce set() overhead; display already refreshes every 1s */
            if (this.timeSpentActiveAccumulator >= 1000)
            {
                long ticks = (long) (this.timeSpentActiveAccumulator / 50);

                this.getData().timeSpentActive.set(this.getData().timeSpentActive.get() + ticks);
                BBSFilmCollaborationBridge.captureCommittedValues(this, List.of(this.getData().timeSpentActive));
                this.timeSpentActiveAccumulator -= ticks * 50;
            }
        }

        if (this.controller.isControlling())
        {
            context.mouseX = context.mouseY = -1;
        }

        for (UIFilmPreview preview : this.getPreviews())
        {
            if (preview.getViewDescriptor().isVisible())
            {
                preview.getViewController().orbit.update(context);
            }
        }

        if (this.undoHandler != null)
        {
            this.undoHandler.submitUndo();
        }

        BBSFilmCollaborationBridge.samplePresence(this, context);

        if (this.queueExporter != null)
        {
            this.queueExporter.tick(context);
        }

        this.updateLogic(context);

        this.area.render(context.batcher, BBSSettings.baseSurface());

        if (this.editor.isVisible())
        {
            this.preview.area.render(context.batcher, Colors.A75);
        }

        if (this.getData() == null)
        {
            this.openOverlay.area.copy(this.openFilmMenu.area);
        }

        BBSSettings.lightInputs = true;

        try
        {
            super.render(context);
        }
        finally
        {
            BBSSettings.lightInputs = false;
        }

        /* Drawn through Batcher2D so native UI and web command replay see the
         * same bounded participant overlay. */
        BBSFilmCollaborationBridge.renderRemotePresence(this, context);

        if (this.entered)
        {
            LocalPlayer player = Minecraft.getInstance().player;
            Vec3 pos = player.position();
            Vector3d cameraPos = this.camera.position;
            double distance = cameraPos.distance(pos.x, pos.y, pos.z);
            int value = Minecraft.getInstance().options.renderDistance().get();

            if (distance > value * 12)
            {
                this.getContext().notifyError(UIKeys.FILM_TELEPORT_DESCRIPTION);
            }

            this.entered = false;
        }
    }

    /**
     * Update logic for such components as repeat fixture, minema recording,
     * sync mode, flight mode, etc.
     */
    private void updateLogic(UIContext context)
    {
        if (this.lastLogicFrame == this.sceneFrameId)
        {
            return;
        }

        this.lastLogicFrame = this.sceneFrameId;

        /* Loop fixture */
        if (BBSSettings.editorLoop.get() && this.isRunning())
        {
            Vector2i loop = this.getLoopingRange();
            int min = loop.x;
            int max = loop.y;
            int ticks = this.getCursor();

            if (!this.recorder.isRecording() && !this.controller.isRecording() && min >= 0 && max >= 0 && min < max && (ticks >= max - 1 || ticks < min))
            {
                this.setCursor(min);
            }
        }

        /* Animate flight mode */
        if (this.flightPreview != null && this.dashboard.orbitUI.canControl())
        {
            this.dashboard.orbit.apply(this.position);
            ViewNavigationState navigation = this.flightPreview.getViewDescriptor().getNavigation();

            if (this.flightEditsCamera)
            {
                navigation.getEditingPose().set(this.position);
            }
            else
            {
                navigation.getFreePose().set(this.position);
            }

            this.flightPreview.requestRefresh();
        }
        else
        {
            this.dashboard.orbit.setup(this.getActivePreview().getDisplayedCamera());
        }

        /* Rewind playback back to 0 */
        if (this.lastRunning && !this.isRunning())
        {
            this.lastRunning = this.runner.isRunning();

            if (BBSSettings.editorRewind.get())
            {
                this.setCursor(0);
                this.notifyServer(ActionState.RESTART);
            }
        }
    }

    @Override
    public void startRenderFrame(float tickDelta)
    {
        super.startRenderFrame(tickDelta);

        this.controller.startRenderFrame(tickDelta);
        this.sceneFrameId++;
        this.cameraPoseEvaluator.beginFrame(
            this.sceneFrameId,
            this.data,
            this.getCursor(),
            tickDelta,
            this.runner.isRunning(),
            this.controller.getEntities()
        );

        for (UIFilmPreview preview : this.getPreviews())
        {
            preview.updateRenderDemand();
        }
    }

    @Override
    public void renderInWorld(IBbsWorldRenderContext context)
    {
        super.renderInWorld(context);

        if (!BBSRendering.isIrisShadowPass() && !BBSRendering.isApplyingSecondaryCamera())
        {
            this.lastProjection.set(context.projectionMatrix());
            this.lastView.set(context.modelViewMatrix());
        }

        this.getPreview(BBSRendering.getActiveViewId()).getViewController().renderFrame(context);
    }

    /* IUICameraWorkDelegate implementation */

    public void notifyServer(ActionState state)
    {
        if (this.data == null || !ClientNetwork.isIsBBSModOnServer())
        {
            return;
        }

        String id = this.data.getId();
        int tick = this.getCursor();

        ClientNetwork.sendActionState(id, state, tick);
    }

    public Camera getCamera()
    {
        return this.camera;
    }

    /** Stable view descriptors consumed by the world renderer. */
    public Collection<ViewDescriptor> getViewDescriptors()
    {
        return List.of(this.primaryView, this.secondaryView, this.tertiaryView, this.quaternaryView);
    }

    public ViewDescriptor getPrimaryView()
    {
        return this.primaryView;
    }

    public ViewDescriptor getSecondaryView()
    {
        return this.secondaryView;
    }

    /** Resolve one preview without advancing playback or modifying the output camera. */
    public void resolveViewCamera(ViewDescriptor view, float transition)
    {
        if (view == null)
        {
            return;
        }

        Camera target = view.getCamera();
        Film film = this.getData();
        ViewNavigationState navigation = view.getNavigation();
        UIFilmController controller = this.getPreview(view.getId()).getViewController();

        view.setOrthoDistance(-1F);
        navigation.initialize(this.getCamera());

        if (film == null)
        {
            target.copy(this.getCamera());

            return;
        }

        if (navigation.isInCameraView())
        {
            Position pose = navigation.isEditingCamera() ? navigation.getEditingPose()
                : this.cameraPoseEvaluator.evaluate(view.resolveCameraId(), film.getCameraBasePosition(view.resolveCameraId()));

            pose.apply(target);
        }
        else if (this.isViewFlying(this.getPreview(view.getId())))
        {
            this.position.apply(target);
            controller.handleCamera(target, transition);
            navigation.getFreePose().set(target);
        }
        else
        {
            navigation.getFreePose().apply(target);
            controller.handleCamera(target, transition);
            navigation.getFreePose().set(target);
        }

        target.updatePerspectiveProjection(Math.max(2, view.getWidth()), Math.max(2, view.getHeight()));
        target.updateView();
    }

    public Camera getWorldCamera()
    {
        return BBSModClient.getCameraController().camera;
    }

    public CameraController getCameraController()
    {
        return BBSModClient.getCameraController();
    }

    @Override
    public int getCursor()
    {
        return this.runner.ticks;
    }

    @Override
    public float getCursor(float transition)
    {
        return this.runner.getCursor(transition);
    }

    @Override
    public void setCursor(int value)
    {
        this.setCursor((float) value);
    }

    @Override
    public void setCursor(float value)
    {
        int previousCursor = this.getCursor();

        this.finishFlight(true, "seek");
        this.cancelViewInteractions();
        this.runner.setCursor(Math.max(0F, value));

        if (this.runner.ticks != previousCursor)
        {
            for (ViewDescriptor view : this.getViewDescriptors())
            {
                view.invalidateHistory();
            }
        }

        this.notifyServer(ActionState.SEEK);

        if (BBSSettings.editorRestartOnSeek.get())
        {
            this.restartPending = true;
        }
    }

    /**
     * Restart the actions and recreate the actors, the same way {@link Keys#FILM_CONTROLLER_RESTART_ACTIONS}
     * does it manually.
     */
    public void restartActions()
    {
        this.restartPending = false;

        this.notifyServer(ActionState.RESTART);
        this.controller.createEntities();
    }

    /**
     * Automatic restart of the actions upon scrubbing the cursor (see the "restart on seek" setting).
     *
     * <p>Both restarting the actions on the server and recreating the actors are way too
     * expensive to run them on every frame of a scrubbing drag, so the restart waits until
     * the cursor stops moving for a tick and only then fires once.</p>
     */
    private void updateRestartOnSeek()
    {
        int cursor = this.getCursor();
        boolean settled = cursor == this.lastRestartCursor;

        this.lastRestartCursor = cursor;

        if (!this.restartPending || !settled)
        {
            return;
        }

        if (!BBSSettings.editorRestartOnSeek.get() || !this.canRestartOnSeek())
        {
            this.restartPending = false;

            return;
        }

        this.restartActions();
    }

    /**
     * Recreating the actors stops the recording and drops the character control, and both
     * the playback and the video export move the cursor on their own, so an automatic
     * restart must stay out of all of those.
     */
    private boolean canRestartOnSeek()
    {
        return this.data != null
            && !this.isRunning()
            && !this.controller.isRecording()
            && !this.controller.isControlling()
            && !this.recorder.isRecording()
            && !this.recorder.isExporting();
    }

    public boolean isRunning()
    {
        return this.runner.isRunning();
    }

    public void togglePlayback()
    {
        this.finishFlight(true, "playback");

        this.runner.toggle(this.getCursor());
        this.lastRunning = this.runner.isRunning();

        if (this.runner.isRunning())
        {
            this.cameraEditor.clips.scale.shiftIntoMiddle(this.getCursor());

            if (this.replayEditor.keyframeEditor != null)
            {
                this.replayEditor.keyframeEditor.view.getXAxis().shiftIntoMiddle(this.getCursor());
            }
        }
    }

    public boolean canUseKeybinds()
    {
        return !this.isFlying();
    }

    /**
     * Whether a visible clips timeline currently owns clip-oriented keybinds.
     */
    public boolean hasSelectedClip()
    {
        return (this.cameraEditor != null && this.cameraEditor.isVisible() && this.cameraEditor.getClip() != null)
            || (this.actionEditor != null && this.actionEditor.isVisible() && this.actionEditor.getClip() != null);
    }

    public void fillData()
    {
        if (this.data != null)
        {
            this.selectCameraTrack(this.editedCameraId);
        }

        this.cameraEditor.fillData();
        this.actionEditor.fillData();

        if (this.replayEditor.keyframeEditor != null && this.replayEditor.keyframeEditor.editor != null)
        {
            this.replayEditor.keyframeEditor.editor.update();
        }
    }

    /** Internal targeted refresh used after validated semantic collaboration updates. */
    public void refreshFilmCollaboration(
        BBSFilmRefreshHint hint,
        boolean snapshot,
        boolean invalidateUndo,
        List<List<String>> changedPaths
    )
    {
        if (this.data == null || hint == null)
        {
            return;
        }

        if (snapshot)
        {
            if (this.undoHandler != null)
            {
                this.undoHandler.reset();
            }

            this.actionEditor.setClips(null);
            this.runner.setWork(this.data.camera);
            this.cameraEditor.setClips(this.data.getCameraClips(this.editedCameraId));
            this.replayEditor.setFilm(this.data);
            this.cameraEditor.pickClip(null);
            this.fillData();
            this.controller.createEntities();

            return;
        }

        if (invalidateUndo && this.undoHandler != null)
        {
            /* Numeric list paths are revision-scoped. A subtree replacement can
             * make an old undo resolve to a different Replay/clip, so discard it. */
            this.undoHandler.reset();
        }

        boolean cameraStructure = false;
        boolean replayStructure = false;

        if (hint == BBSFilmRefreshHint.STRUCTURE && changedPaths != null)
        {
            for (List<String> path : changedPaths)
            {
                if (!path.isEmpty() && path.get(0).equals("camera"))
                {
                    cameraStructure = true;
                }
                else if (!path.isEmpty() && path.get(0).equals("replays"))
                {
                    replayStructure = true;
                }
            }
        }

        if (cameraStructure)
        {
            this.runner.setWork(this.data.camera);
            this.cameraEditor.setClips(this.data.getCameraClips(this.editedCameraId));
            this.cameraEditor.pickClip(null);
        }

        if (replayStructure)
        {
            this.actionEditor.setClips(null);
            this.replayEditor.setFilm(this.data);
        }

        switch (hint)
        {
            case NONE ->
            {}
            case VALUE, TIMELINE -> this.fillData();
            case REPLAY ->
            {
                this.replayEditor.replaysList.replays.refreshReplayList();
                this.fillData();
                this.controller.createEntities();
            }
            case STRUCTURE ->
            {
                this.replayEditor.replaysList.replays.refreshReplayList();
                this.fillData();
                this.controller.createEntities();
            }
        }
    }

    public void teleportToCamera()
    {
        Camera camera = this.getCamera();
        Vector3d cameraPos = camera.position;
        double x = cameraPos.x;
        double y = cameraPos.y;
        double z = cameraPos.z;

        PlayerUtils.teleport(x, y, z, MathUtils.toDeg(camera.rotation.y) - 180F, MathUtils.toDeg(camera.rotation.x));
    }

    public void setPlayerToCamera(boolean value)
    {
        this.playerToCamera = value;
        BBSSettings.editorPlayerFollowsCamera.set(value);
    }

    public boolean checkShowNoCamera()
    {
        boolean noCamera = this.getData() == null || this.getData().calculateDuration() <= 0;

        if (noCamera)
        {
            UIOverlay.addOverlay(this.getContext(), new UIMessageOverlayPanel(
                UIKeys.FILM_NO_CAMERA_TITLE,
                UIKeys.FILM_NO_CAMERA_DESCRIPTION
            ));
        }

        return noCamera;
    }

    public void updateActors(String filmId, Map<String, Integer> actors)
    {
        if (this.data != null && this.data.getId().equals(filmId))
        {
            this.controller.updateActors(actors);
        }
    }

    @Override
    public boolean handleKeyPressed(UIContext context)
    {
        UIFilmPreview preview = this.getActivePreview();

        return preview.getViewController().orbit.keyPressed(context, preview.getViewport());
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);

        this.showPanel(data.getInt("panel"));
        this.setCursor(data.getFloat("tick"));
        this.restoreCameraViewState(data);
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);

        data.putInt("panel", this.getPanelIndex());
        data.putFloat("tick", this.getCursor(0F));
        this.captureCameraViewState(data);
    }

    public void captureCameraViewState(MapType data)
    {
        MapType views = new MapType();

        for (UIFilmPreview preview : this.getPreviews())
        {
            ViewDescriptor view = preview.getViewDescriptor();
            MapType binding = new MapType();

            binding.putString("camera", view.getCameraId());
            binding.putBool("follow_output", view.isFollowOutput());
            views.put(view.getId(), binding);
        }

        data.put("camera_views", views);
        data.putString("edited_camera", this.editedCameraId);
    }

    public void restoreCameraViewState(MapType data)
    {
        if (!data.has("camera_views"))
        {
            return;
        }

        MapType views = data.getMap("camera_views");

        for (UIFilmPreview preview : this.getPreviews())
        {
            ViewDescriptor view = preview.getViewDescriptor();
            MapType binding = views.getMap(view.getId());

            view.setCameraId(binding.getString("camera", Film.LEGACY_CAMERA_ID));
            view.setFollowOutput(binding.getBool("follow_output"));
        }

        this.selectCameraTrack(data.getString("edited_camera", Film.LEGACY_CAMERA_ID));
        this.saveViewSettings();
    }

    @Override
    protected boolean canSave(UIContext context)
    {
        return !this.recorder.isRecording();
    }
}
