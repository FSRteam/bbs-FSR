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
import mchorse.bbs_mod.camera.clips.modifiers.TranslateClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
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
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
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
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.UIFilmUndoHandler;
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
import mchorse.bbs_mod.utils.Timer;
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
    private boolean lifecycleActive;
    private boolean lastRunning;
    private final Position position = new Position(0, 0, 0, 0, 0);
    private final Position lastPosition = new Position(0, 0, 0, 0, 0);

    public UIFilmSelectionPanel selectionPanel;

    public UIElement main;
    public UIElement editArea;
    private final UIDockLayout dock;
    public UIFilmRecorder recorder;
    public UIFilmPreview preview;

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

    private Timer flightEditTime = new Timer(100);
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
        this.preview = new UIFilmPreview(this);
        this.panelById.put(PANEL_MAIN_ID, this.main);
        this.panelById.put(PANEL_PREVIEW_ID, this.preview);
        this.panelById.put(PANEL_EDIT_AREA_ID, this.editArea);

        /* The dock must be constructed before the editors below: the replay
         * editors query its lock state (edit panel top offset) from their own
         * constructors, and this final field cannot be left unassigned when
         * getEditPanelTopOffsetPx() runs during their construction. The layout
         * wiring further down configures and mounts it afterwards. */
        this.dock = new UIDockLayout();

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
            .locked(!BBSSettings.editorLayoutSettings.isDockUnlocked(ValueEditorLayout.FILM))
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
        this.keys().register(Keys.NEXT_CLIP, () -> this.setCursor(this.data.camera.findNextTick(this.getCursor()))).active(active).category(editor);
        this.keys().register(Keys.PREV_CLIP, () -> this.setCursor(this.data.camera.findPreviousTick(this.getCursor()))).active(active).category(editor);
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

        this.selectionPanel = new UIFilmSelectionPanel(this);
        this.selectionPanel.setVisible(false);

        this.fill(null);

        this.flightEditTime.mark();

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

    private EditorLayoutNode ensureFilmLayoutPanels(EditorLayoutNode root)
    {
        HashSet<String> ids = new HashSet<>();
        this.collectPanelIds(root, ids);

        Set<String> hidden = this.getFilmLayoutSettings().getHiddenPanels(this.currentLayoutId());
        boolean hasList = ids.contains(PANEL_REPLAYS_LIST_ID) || hidden.contains(PANEL_REPLAYS_LIST_ID);
        boolean hasProps = ids.contains(PANEL_REPLAY_PROPS_ID) || hidden.contains(PANEL_REPLAY_PROPS_ID);

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
            UIOverlay.addOverlay(this.getContext(), new UIUndoHistoryOverlay(UIKeys.FILM_HISTORY_TITLE, this.getUndoHandler().getUndoManager(), this::getData, null), 200, 0.6F);
        });

        menu.action(Icons.FILM, UIKeys.FILM_RENDER_QUEUE, this::startQueueExportFromOpenTabs);

        menu.action(Icons.ARROW_RIGHT, UIKeys.FILM_MOVE_TITLE, () ->
        {
            UIFilmMoveOverlayPanel panel = new UIFilmMoveOverlayPanel((vector) ->
            {
                int topLayer = this.data.camera.getTopLayer() + 1;
                int duration = this.data.camera.calculateDuration();
                double dx = vector.x;
                double dy = vector.y;
                double dz = vector.z;

                BaseValue.edit(this.data, (__) ->
                {
                    TranslateClip clip = new TranslateClip();

                    clip.layer.set(topLayer);
                    clip.duration.set(duration);
                    clip.translate.get().set(dx, dy, dz);
                    __.camera.addClip(clip);

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
        boolean cameraVisible = this.cameraEditor.isVisible();
        boolean replayVisible = this.replayEditor.isVisible();
        boolean actionVisible = this.actionEditor.isVisible();

        if (previewMode == PREVIEW_MODE_EXPORT)
        {
            w = Math.max(2, BBSSettings.videoSettings.width.get());
            h = Math.max(2, BBSSettings.videoSettings.height.get());
        }
        else if (previewMode == PREVIEW_MODE_CUSTOM)
        {
            w = Math.max(2, BBSSettings.editorPreviewCustomWidth.get());
            h = Math.max(2, BBSSettings.editorPreviewCustomHeight.get());
        }
        else
        {
            float scale = BBSSettings.editorPreviewResolutionScale.get();

            if (cameraVisible)
            {
                int previewW = Math.max(2, this.preview.area.w);
                int previewH = Math.max(2, this.preview.area.h);
                int exportW = Math.max(2, BBSSettings.videoSettings.width.get());
                int exportH = Math.max(2, BBSSettings.videoSettings.height.get());
                Vector2i resized = Vectors.resize(exportW / (float) exportH, previewW, previewH);

                w = Math.max(2, (int) (resized.x * scale));
                h = Math.max(2, (int) (resized.y * scale));
            }
            else
            {
                int previewW = this.preview.area.w;
                int previewH = this.preview.area.h;
                w = Math.max(2, (int) (previewW * scale));
                h = Math.max(2, (int) (previewH * scale));
            }
        }

        if (w % 2 != 0) w++;
        if (h % 2 != 0) h++;

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
        this.cameraEditor.setClips(data == null ? null : data.camera);
        this.replayEditor.setFilm(data);
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
        if (this.data != null && this.undoHandler.getUndoManager().undo(this.data)) UIUtils.playClick();
    }

    public void redo()
    {
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
        this.setFlight(!this.isFlying());
    }

    /**
     * Set flight mode
     */
    public void setFlight(boolean flight)
    {
        if (flight)
        {
            this.controller.stopGizmoInteraction();
        }
        else
        {
            /* handleKeyPressed only forwards to the orbit controller while flight is
             * on, so a toggle with a movement key still held never delivers its
             * release and the direction would resume on the next toggle. */
            this.controller.orbit.resetVelocity();
        }

        if (!this.isRunning() || !flight)
        {
            if (!flight)
            {
                this.persistFlightFov();
                if (this.undoHandler != null)
                {
                    this.undoHandler.getUndoManager().markLastUndoNoMerging();
                }
                else
                {
                    this.lastPosition.set(Position.ZERO);
                }
            }
            else
            {
                this.lastPosition.set(Position.ZERO);
            }

            this.runner.setManual(flight ? this.position : null);
            this.dashboard.orbitUI.setControl(flight);
        }
    }

    private void persistFlightFov()
    {
        if (BBSSettings.fov != null)
        {
            BBSSettings.fov.set(this.position.angle.fov);
        }
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

        max = Math.min(max, this.data.camera.calculateDuration());

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

        this.controller.orbit.update(context);

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
        Clip clip = this.cameraEditor.getClip();

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
        if (this.dashboard.orbitUI.canControl())
        {
            this.dashboard.orbit.apply(this.position);

            Position current = new Position(this.getCamera());
            boolean check = this.flightEditTime.check();

            if (this.cameraEditor.getClip() != null && this.cameraEditor.isVisible() && this.controller.getPovMode() != UIFilmController.CAMERA_MODE_FREE)
            {
                if (!this.lastPosition.equals(current) && check)
                {
                    this.cameraEditor.editClip(current);
                }
            }

            if (check)
            {
                this.lastPosition.set(current);
            }
        }
        else
        {
            this.dashboard.orbit.setup(this.getCamera());
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
    }

    @Override
    public void renderInWorld(IBbsWorldRenderContext context)
    {
        super.renderInWorld(context);

        if (!BBSRendering.isIrisShadowPass())
        {
            this.lastProjection.set(context.projectionMatrix());
            this.lastView.set(context.modelViewMatrix());
        }

        this.controller.renderFrame(context);
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
    public void setCursor(int value)
    {
        this.flightEditTime.mark();
        this.lastPosition.set(Position.ZERO);

        this.runner.ticks = Math.max(0, value);

        this.notifyServer(ActionState.SEEK);
    }

    public boolean isRunning()
    {
        return this.runner.isRunning();
    }

    public void togglePlayback()
    {
        this.setFlight(false);

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
            this.cameraEditor.setClips(this.data.camera);
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
            this.cameraEditor.setClips(this.data.camera);
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
        boolean noCamera = this.getData().camera.calculateDuration() <= 0;

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
        return this.controller.orbit.keyPressed(context, this.preview.area);
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);

        this.showPanel(data.getInt("panel"));
        this.setCursor(data.getInt("tick"));
        this.controller.createEntities();
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);

        data.putInt("panel", this.getPanelIndex());
        data.putInt("tick", this.getCursor());
    }

    @Override
    protected boolean canSave(UIContext context)
    {
        return !this.recorder.isRecording();
    }
}
