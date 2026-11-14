package mchorse.bbs_mod.ui.film.controller;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.Objects;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.renderers.sound.SoundGuideInteraction;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.ui.ValueMotionPath;
import mchorse.bbs_mod.settings.values.ui.ValueOnionSkin;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.PreviewHud;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.film.view.ViewCameraSnapshot;
import mchorse.bbs_mod.ui.film.view.FilmCameraMarkers;
import mchorse.bbs_mod.ui.film.view.FilmViewMenus;
import mchorse.bbs_mod.ui.film.view.ViewFrameGeometry;
import mchorse.bbs_mod.ui.film.view.ViewportPickIntent;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.film.replays.UIRecordOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;

import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.RayTracing;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.client.rendering.context.BbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.Options;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

public class UIFilmController extends UIElement implements GizmoViewport
{
    private static final List<Function<UIFilmController, mchorse.bbs_mod.api.client.editor.FilmEditorTool>> TOOL_FACTORIES = new ArrayList<>();
    private final List<mchorse.bbs_mod.api.client.editor.FilmEditorTool> addonTools = new ArrayList<>();

    public static void registerTool(Function<UIFilmController, mchorse.bbs_mod.api.client.editor.FilmEditorTool> factory)
    {
        if (factory != null)
        {
            TOOL_FACTORIES.add(factory);
        }
    }
    static final Logger LOGGER = LogUtils.getLogger();

    public static final int CAMERA_MODE_CAMERA = 0;
    public static final int CAMERA_MODE_FREE = 1;
    public static final int CAMERA_MODE_ORBIT = 2;
    public static final int CAMERA_MODE_FIRST_PERSON = 3;
    public static final int CAMERA_MODE_THIRD_PERSON_BACK = 4;
    public static final int CAMERA_MODE_THIRD_PERSON_FRONT = 5;
    public static final int CAMERA_MODE_COUNT = 6;
    private static final IKey[] CAMERA_MODE_LABELS = new IKey[] {
        UIKeys.FILM_REPLAY_ORBIT_CAMERA,
        UIKeys.FILM_REPLAY_ORBIT_FREE,
        UIKeys.FILM_REPLAY_ORBIT_ORBIT,
        UIKeys.FILM_REPLAY_ORBIT_FIRST_PERSON,
        UIKeys.FILM_REPLAY_ORBIT_THIRD_PERSON_BACK,
        UIKeys.FILM_REPLAY_ORBIT_THIRD_PERSON_FRONT
    };
    static final int REPLAY_STENCIL_OFFSET = Gizmo.STENCIL_MAX + 1;

    public final UIFilmPanel panel;

    public final FilmKeyframeInsertion keyframes = new FilmKeyframeInsertion(this);
    public final FilmRecordingController recorder = new FilmRecordingController(this);
    public final FilmStencilPicker picker = new FilmStencilPicker(this);
    public final MotionPathPin motionPathPin = new MotionPathPin(this);
    public final ActorMouseControl mouse = new ActorMouseControl();
    private final FilmControllerHud hud = new FilmControllerHud(this);
    final boolean sceneOwner;
    UIFilmPreview preview;
    private final FilmCameraMarkers cameraMarkers = new FilmCameraMarkers();

    public FilmEditorController editorController;
    private Map<String, Integer> actors;

    /* Character control */
    IEntity controlled;
    int mouseMode;

    /* Recording state */
    private IEntity previousEntity;
    private Form playerForm;
    private boolean instantKeyframes;

    /* Replay and group picking */
    final GizmoInteraction gizmo = new GizmoInteraction(this);

    public final OrbitFilmCameraController orbit = new OrbitFilmCameraController(this);
    public final OrbitViewGizmo orbitGizmo = new OrbitViewGizmo(this);
    int pov;
    private boolean paused;

    IBbsWorldRenderContext worldRenderContext;
    private final Camera renderingCamera = new Camera();
    private final ViewCameraSnapshot pickingCamera = new ViewCameraSnapshot();
    private final Gizmo.VisualState visualState = new Gizmo.VisualState();
    private final Gizmo.VisualState pendingVisualState = new Gizmo.VisualState();
    private final Gizmo.VisualState previousVisualState = new Gizmo.VisualState();
    private boolean renderingView;
    long worldContextFrame = -1L;


    public UIFilmController(UIFilmPanel panel)
    {
        this(panel, true);
    }

    public UIFilmController(UIFilmPanel panel, boolean sceneOwner)
    {
        this.panel = panel;
        this.sceneOwner = sceneOwner;
        this.setPov(sceneOwner ? BBSSettings.editorCameraMode.get() : CAMERA_MODE_CAMERA);
        this.noCulling();

        if (!sceneOwner)
        {
            return;
        }

        IKey category = UIKeys.FILM_CONTROLLER_KEYS_CATEGORY;

        Supplier<Boolean> hasActor = () -> this.getCurrentEntity() != null;
        Supplier<Boolean> hasTwoOrMoreReplays = () -> this.panel.getData() != null && this.panel.getData().replays.getList().size() >= 2;

        this.keys().register(Keys.FILM_CONTROLLER_START_RECORDING, this::pickRecording).active(hasActor).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_INSERT_FRAME, () ->
        {
            this.insertFrame();
            UIUtils.playClick();
        }).active(hasActor).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_CONTROL, this::toggleControl).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_ORBIT_MODE, () -> this.getActiveViewController().toggleOrbitMode()).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TELEPORT_ORBIT, () ->
        {
            this.getActiveViewController().teleportOrbitPivotToReplay();
            this.panel.saveViewSettings();
        }).strict().active(() -> this.getActiveViewController().getPovMode() == CAMERA_MODE_ORBIT).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_ATTACH_ORBIT, () ->
        {
            this.getActiveViewController().toggleOrbitAttachment();
            this.panel.saveViewSettings();
            UIUtils.playClick();
        }).strict().active(() -> this.getActiveViewController().getPovMode() == CAMERA_MODE_ORBIT).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_ORTHO, () ->
        {
            this.getActiveViewController().orbit.toggleOrtho();
            this.panel.saveViewSettings();
            UIUtils.playClick();
        }).strict().active(() -> this.getActiveViewController().getPovMode() == CAMERA_MODE_ORBIT).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_REPLAY_MENU, this::toggleReplayMenu).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_MOVE_REPLAY_TO_CURSOR, () ->
        {
            UIFilmController controller = this.getActiveViewController();
            Area area = controller.getViewArea();
            UIContext context = this.getContext();
            Level world = Minecraft.getInstance().level;
            Camera camera = controller.getViewCamera();

            if (world == null || controller.preview == null || !controller.preview.isInsideFrame(context))
            {
                return;
            }

            Vector3f rayOffset = new Vector3f();
            Vector3f rayDirection = camera.getMouseRay(context.mouseX, context.mouseY, area.x, area.y, area.w, area.h, rayOffset);

            HitResult result = RayTracing.rayTrace(
                world,
                RayTracing.fromVector3d(new Vector3d(camera.position).add(rayOffset.x, rayOffset.y, rayOffset.z)),
                RayTracing.fromVector3f(rayDirection),
                512F
            );

            if (result.getType() == HitResult.Type.BLOCK)
            {
                this.panel.replayEditor.moveReplay(result.getLocation().x, result.getLocation().y, result.getLocation().z);
            }
        }).active(hasActor).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_RESTART_ACTIONS, this.panel::restartActions).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_ONION_SKIN, () ->
        {
            this.getActiveViewController().getOnionSkin().enabled.toggle();
            this.panel.saveViewSettings();

            UIUtils.playClick();
        }).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_MOTION_PATH, () ->
        {
            this.getActiveViewController().getMotionPath().enabled.toggle();
            this.panel.saveViewSettings();
            UIUtils.playClick();
        }).strict().active(() -> !this.panel.hasSelectedClip()).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_MOTION_PATH_PIN, () ->
        {
            this.getActiveViewController().toggleMotionPathPin();
            UIUtils.playClick();
        }).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_OPEN_REPLAYS, () ->
        {
            this.panel.showPanel(this.panel.replayEditor);
        }).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_PREV_REPLAY, () -> this.switchReplay(-1)).active(hasTwoOrMoreReplays).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_NEXT_REPLAY, () -> this.switchReplay(1)).active(hasTwoOrMoreReplays).category(category);

        for (Function<UIFilmController, mchorse.bbs_mod.api.client.editor.FilmEditorTool> factory : TOOL_FACTORIES)
        {
            mchorse.bbs_mod.api.client.editor.FilmEditorTool tool = Objects.requireNonNull(factory.apply(this));
            this.addonTools.add(tool);
            this.add(tool);
        }

        this.noCulling();
    }

    public void bindPreview(UIFilmPreview preview)
    {
        this.preview = preview;
    }

    @Override
    public UIContext getContext()
    {
        return this.panel == null ? null : this.panel.getContext();
    }

    public boolean isSceneOwner()
    {
        return this.sceneOwner;
    }

    public Camera getViewCamera()
    {
        if (this.renderingView)
        {
            return this.renderingCamera;
        }

        return this.preview == null ? this.panel.getCamera() : this.preview.getDisplayedCamera();
    }

    public Area getViewArea()
    {
        return this.preview == null ? this.panel.preview.getViewport() : this.preview.getViewport();
    }

    public boolean isViewFlying()
    {
        return this.preview != null && this.panel.isViewFlying(this.preview);
    }

    public void setViewOrthoDistance(float distance)
    {
        if (this.preview != null)
        {
            this.preview.getViewDescriptor().setOrthoDistance(distance);
        }

        if (this.sceneOwner)
        {
            BBSRendering.setOrthoDistance(distance);
        }
    }

    boolean isViewActive()
    {
        return this.preview == null || this.panel.getActivePreview() == this.preview;
    }

    private UIFilmController getActiveViewController()
    {
        return this.panel.getActivePreview().getViewController();
    }

    private void switchReplay(int direction)
    {
        List<Replay> list = this.panel.getData().replays.getList();

        int index = CollectionUtils.getIndex(list, this.getReplay());
        int newIndex = MathUtils.cycler(index + direction, list);
        Replay replay = list.get(newIndex);

        this.panel.replayEditor.setReplay(replay);
        UIUtils.playClick();
    }

    public boolean isInstantKeyframes()
    {
        return this.sceneOwner ? this.instantKeyframes : this.panel.getController().isInstantKeyframes();
    }

    public void toggleInstantKeyframes()
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().toggleInstantKeyframes();

            return;
        }

        this.instantKeyframes = !this.instantKeyframes;
    }

    public boolean isPaused()
    {
        return this.sceneOwner ? this.paused : this.panel.getController().isPaused();
    }

    public void setPaused(boolean paused)
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().setPaused(paused);

            return;
        }

        this.paused = paused;
    }

    void toggleMousePointer(boolean disable)
    {
        Window.setCursorHidden(this, disable);
    }

    public ValueOnionSkin getOnionSkin()
    {
        return this.preview == null ? BBSSettings.editorOnionSkin : this.preview.getViewDescriptor().getOnionSkin();
    }

    public ValueMotionPath getMotionPath()
    {
        return this.preview == null ? BBSSettings.editorMotionPath : this.preview.getViewDescriptor().getMotionPath();
    }


    public boolean isMotionPathPinned()
    {
        return this.motionPathPin.isPinned();
    }

    public void pinMotionPath()
    {
        this.motionPathPin.pin();
    }

    public void unpinMotionPath()
    {
        this.motionPathPin.unpin();
    }

    public void toggleMotionPathPin()
    {
        this.motionPathPin.toggle();
    }

    int getTick()
    {
        return this.panel.getCursor();
    }

    Replay getReplay()
    {
        return this.panel.replayEditor.getReplay();
    }

    int getCurrentReplayIndex()
    {
        if (this.panel.getData() == null)
        {
            return -1;
        }

        Replay replay = this.getReplay();

        return replay == null ? -1 : CollectionUtils.getIndex(this.panel.getData().replays.getList(), replay);
    }

    public StencilFormFramebuffer getStencil()
    {
        return this.picker.getStencil();
    }

    public IEntity getCurrentEntity()
    {
        if (this.panel.getData() == null)
        {
            return null;
        }

        /* The entity map is keyed by the replay's stable id, never by its position in the list. */
        Replay replay = this.getReplay();

        return replay == null ? null : this.getEntities().get(replay.getId());
    }

    public int getPovMode()
    {
        return Math.floorMod(this.pov, CAMERA_MODE_COUNT);
    }

    public void setPov(int pov)
    {
        int mode = Math.floorMod(pov, CAMERA_MODE_COUNT);

        if (mode != this.getPovMode())
        {
            this.cancelOrbitGesture();
        }

        if (this.preview != null && mode != this.getPovMode())
        {
            this.preview.onCameraModeChanged(this.getPovMode(), mode);
        }

        this.pov = mode;
        this.orbit.enabled = mode > CAMERA_MODE_FREE;

        if (this.sceneOwner)
        {
            BBSSettings.editorCameraMode.set(mode);
        }
    }

    public void restorePov(int pov)
    {
        this.pov = Math.floorMod(pov, CAMERA_MODE_COUNT);
        this.orbit.enabled = this.pov > CAMERA_MODE_FREE;
    }

    int getMouseMode()
    {
        return this.mouse.getMode();
    }

    void setMouseMode(int mode)
    {
        if (!ClientNetwork.isIsBBSModOnServer() && mode == 0)
        {
            mode = 1;
            this.getContext().notifyError(UIKeys.FILM_CONTROLLER_SERVER_WARNING);
        }

        this.mouse.setMode(mode, this.controlled);
    }

    private boolean isMouseLookMode()
    {
        return this.getMouseMode() == 0;
    }

    public void createEntities()
    {
        if (!this.sceneOwner)
        {
            return;
        }

        this.stopRecording();

        if (this.controlled != null)
        {
            this.toggleControl();
        }

        if (this.editorController != null)
        {
            this.editorController.shutdown();
        }

        this.editorController = new FilmEditorController(this.panel.getData(), this);
        this.editorController.createEntities();

        Map<String, IEntity> entities = this.panel.getRunner().getContext().entities;

        entities.clear();
        entities.putAll(this.editorController.getEntities());
    }

    public void shutdown()
    {
        if (this.sceneOwner)
        {
            this.picker.cancelTargetPick();
        }

        this.resetViewInteraction();

        if (this.sceneOwner && this.editorController != null)
        {
            this.editorController.shutdown();
        }
    }

    public Map<String, IEntity> getEntities()
    {
        if (!this.sceneOwner)
        {
            return this.panel.getController().getEntities();
        }

        return this.editorController == null ? new LinkedHashMap<>() : this.editorController.getEntities();
    }

    public Map<String, Integer> getActors()
    {
        return this.sceneOwner ? this.actors : this.panel.getController().getActors();
    }

    public void updateActors(Map<String, Integer> actors)
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().updateActors(actors);

            return;
        }

        this.actors = actors;
    }

    /* Character control state */

    public IEntity getControlled()
    {
        return this.sceneOwner ? this.controlled : this.panel.getController().getControlled();
    }

    public boolean isControlling()
    {
        return this.getControlled() != null;
    }

    public void toggleControl()
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().toggleControl();

            return;
        }

        this.getContext().unfocus();

        if (this.panel.replayEditor.isVisible())
        {
            this.panel.replayEditor.pickPlayerCategory();
        }

        boolean replacePlayer = ClientNetwork.isIsBBSModOnServer();
        Map<String, IEntity> entities = this.getEntities();

        if (this.controlled != null)
        {
            if (replacePlayer && this.previousEntity != null)
            {
                this.controlled.setForm(this.playerForm);

                String controlledId = CollectionUtils.getKey(entities, this.controlled);

                if (controlledId != null)
                {
                    entities.put(controlledId, this.previousEntity);
                }

                this.previousEntity = null;
            }

            this.controlled = null;
        }
        else if (this.panel.replayEditor.replaysList.replays.isSelected())
        {
            this.controlled = this.getCurrentEntity();

            if (replacePlayer && this.controlled != null)
            {
                MCEntity player = Morph.getMorph(Minecraft.getInstance().player).entity;

                this.playerForm = player.getForm();
                this.previousEntity = this.controlled;

                player.copy(this.controlled);
                PlayerUtils.teleport(this.controlled.getX(), this.controlled.getY(), this.controlled.getZ(), this.controlled.getHeadYaw(), this.controlled.getBodyYaw(), this.controlled.getPitch());
                String controlledId = CollectionUtils.getKey(entities, this.controlled);

                if (controlledId != null)
                {
                    entities.put(controlledId, player);
                }

                this.controlled = player;
            }
        }

        this.setMouseMode(this.mouse.getMode());
        this.toggleMousePointer(this.controlled != null);

        if (this.controlled == null && this.recorder.recording)
        {
            this.stopRecording();
        }
    }

    private boolean canControl()
    {
        UIContext context = this.getContext();

        return this.controlled != null && context != null && !UIOverlay.has(context);
    }

    /* Recording */

    public boolean isPlaying()
    {
        boolean playing = !UIOverlay.has(this.getContext()) && this.panel.isRunning();

        if (this.isPaused())
        {
            playing = true;
        }

        return playing;
    }

    public boolean isRecording()
    {
        return this.recorder.isRecording();
    }

    public int getRecordingCountdown()
    {
        return this.recorder.getRecordingCountdown();
    }

    public List<String> getRecordingGroups()
    {
        return this.recorder.getRecordingGroups();
    }

    private boolean hasTransformRecordingGroup()
    {
        return this.recorder.hasTransformRecordingGroup();
    }

    public boolean isTransformRecording()
    {
        return this.recorder.isTransformRecording();
    }

    public void startRecording(List<String> groups)
    {
        this.recorder.startRecording(groups);
    }

    /**
     * Begin playback for a take, unless the film is already running (the editor can be playing when a
     * recording starts). Never a blind {@code togglePlayback} — that would stop it instead.
     */
    private void startPlayback()
    {
        this.recorder.startPlayback();
    }

    public void stopRecording()
    {
        this.recorder.stopRecording();
    }

    /* Input handling */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            if (tool.click(context))
            {
                return true;
            }
        }

        if (this.preview != null && !this.preview.isInsideFrame(context))
        {
            return false;
        }

        if (this.canControl())
        {
            return true;
        }

        if (!this.hasDisplayedStencil(Window.isAltPressed()))
        {
            return false;
        }

        boolean gizmoShown = this.canShowGizmo();

        if (gizmoShown && this.gizmo.mouseClickedHandle(context))
        {
            return true;
        }

        if (context.mouseButton == 0 && this.picker.hoveredReplayIndex >= 0)
        {
            this.pickReplay(this.picker.hoveredReplayIndex);

            return true;
        }

        if (gizmoShown && this.gizmo.mouseClickedSphere(context))
        {
            return true;
        }

        return super.subMouseClicked(context);
    }

    /** Start a preview-owned Gizmo press through the controller's ownership state. */
    public boolean startViewportGizmo(UIContext context)
    {
        return this.hasDisplayedStencil(Window.isAltPressed()) && this.canShowGizmo() && this.gizmo.mouseClickedHandle(context);
    }

    public boolean startViewportSoundGuide(UIContext context)
    {
        if (this.isViewFlying() || this.isControlling() || !this.hasDisplayedStencil(Window.isAltPressed()))
        {
            return false;
        }

        return SoundGuideInteraction.tryStartFilm(
            this,
            this.picker.stencil,
            this.getViewCamera(),
            this.getViewArea(),
            context,
            this.panel.replayEditor.getReplay(),
            this.panel.getCursor()
        );
    }

    public void updateSoundGuideDrag(UIContext context)
    {
        SoundGuideInteraction.update(this, context);
    }

    @Override
    public StencilFormFramebuffer getGizmoStencil()
    {
        return this.picker.stencil;
    }

    @Override
    public Matrix4f getGizmoProjection()
    {
        return this.getViewCamera().projection;
    }

    @Override
    public Area getGizmoArea()
    {
        return this.getViewArea();
    }

    @Override
    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        if (!this.getEditTarget().isNone())
        {
            for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
            {
                Boolean started = tool.startGizmo(context, stencilIndex);

                if (started != null)
                {
                    return started;
                }
            }
        }

        float gizmoTransition = this.isPlaying() ? context.getTransition() : 0F;

        return UIReplaysEditorUtils.startFilmGizmo(this.panel, this.getViewCamera(), this.getViewArea(), context, stencilIndex, gizmoTransition);
    }

    @Override
    public void pickGizmoForm(UIContext context, Form form, String bone)
    {
        this.panel.replayEditor.pickFormWithOffers(context, form, bone);
    }

    void pickReplay(int index)
    {
        this.panel.replayEditor.setReplay(this.panel.getData().replays.getList().get(index));

        if (!this.panel.replayEditor.isVisible())
        {
            this.panel.showPanel(this.panel.replayEditor);
        }
    }

    public void stopGizmoInteraction()
    {
        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            tool.stopTool();
        }

        this.gizmo.cancel();
    }

    public void resetViewInteraction()
    {
        this.cancelPendingViewportPick();
        SoundGuideInteraction.cancel(this, 0);
        this.cancelOrbitGesture();
        this.orbit.resetVelocity();
        this.orbitGizmo.cancel();
        this.gizmo.cancel();
        this.picker.hoveredReplayIndex = -1;
        this.picker.stencil.clearPicking();
        this.picker.stencilFrame = -1L;
        this.picker.stencilReplay = null;
    }

    public boolean clickViewport(UIContext context)
    {
        if (this.preview == null || !this.preview.isInsideFrame(context))
        {
            return false;
        }

        this.cancelPendingViewportPick();

        if (this.picker.pickTarget(context))
        {
            return true;
        }

        Gizmo.INSTANCE.restoreVisualState(this.visualState);
        ViewRenderState state = BBSRendering.getViewRenderState(this.preview.getViewDescriptor().getId());

        this.cameraMarkers.publish(state);
        this.prepareViewportPick(context);

        if (!this.isViewFlying() && !this.isControlling() && context.mouseButton < 2)
        {
            String cameraId = this.cameraMarkers.pick(this.panel, this.preview, context);

            if (cameraId != null)
            {
                this.panel.selectCameraTrack(cameraId);

                if (context.mouseButton == 1)
                {
                    context.replaceContextMenu(menu -> FilmViewMenus.cameraActions(this.panel, this.preview, cameraId, menu));
                }

                return true;
            }
        }

        if (this.startViewportSoundGuide(context) || this.subMouseClicked(context))
        {
            return true;
        }

        return this.panel.replayEditor.clickViewport(context, this.getViewArea(), this);
    }

    /** A navigation gesture emits its short context click only after release. */
    public boolean clickViewportOnRelease(UIContext context)
    {
        boolean handled = this.clickViewport(context);

        this.releaseViewportPick(context, false, 0L);

        return handled;
    }

    public boolean deferViewportPick(UIContext context, long orbitGeneration)
    {
        return this.picker.deferViewportPick(context, orbitGeneration);
    }

    private ViewportPickIntent.Target viewportPickTarget()
    {
        return this.picker.viewportPickTarget();
    }

    private boolean updatePendingViewportPick(UIContext context)
    {
        return this.picker.updatePendingViewportPick(context);
    }

    private void resolveViewportPick()
    {
        this.picker.resolveViewportPick();
    }

    public boolean releaseViewportPick(UIContext context, boolean dragged, long orbitGeneration)
    {
        return this.picker.releaseViewportPick(context, dragged, orbitGeneration);
    }

    public void cancelViewportPick(long orbitGeneration)
    {
        this.picker.cancelViewportPick(orbitGeneration);
    }

    public void cancelPendingViewportPick()
    {
        this.picker.cancelPendingViewportPick();
    }

    private void queueViewportPick(UIContext context)
    {
        this.picker.queueViewportPick(context);
    }

    private void commitViewportPick(UIContext context, long generation)
    {
        this.picker.commitViewportPick(context, generation);
    }

    public void releaseViewResources()
    {
        MotionPath.release(this);
        if (this.sceneOwner)
        {
            this.picker.cancelTargetPick();
        }

        this.resetViewInteraction();
        Link id = Link.bbs("stencil_film_" + (this.preview == null ? "preview" : this.preview.getViewDescriptor().getId()));

        if (this.picker.stencil.getFramebuffer() != null
            && BBSModClient.getFramebuffers().framebuffers.remove(id, this.picker.stencil.getFramebuffer()))
        {
            this.picker.stencil.getFramebuffer().delete();
        }

        this.picker.stencil = new StencilFormFramebuffer();
        this.worldRenderContext = null;
        this.worldContextFrame = -1L;
        this.cameraMarkers.clear();
    }

    public void resetOrbit()
    {
        long generation = this.orbit.gestureGeneration();

        this.orbit.reset();

        if (generation != 0L && this.panel.replayEditor != null)
        {
            this.panel.replayEditor.cancelViewportPick(this, generation);
        }
    }

    private void cancelOrbitGesture()
    {
        long generation = this.orbit.gestureGeneration();

        this.orbit.stop();

        if (generation != 0L && this.panel.replayEditor != null)
        {
            this.panel.replayEditor.cancelViewportPick(this, generation);
        }
    }

    /**
     * Finish a viewport gesture whose press was dispatched by the sibling
     * Film preview. Captured release routing cannot discover this controller
     * again, so the preview forwards the terminal event here explicitly.
     */
    public boolean releaseViewportGesture(UIContext context)
    {
        return this.subMouseReleased(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            if (tool.release(context))
            {
                return true;
            }
        }

        boolean controlling = this.canControl();
        long orbitGeneration = this.orbit.gestureGeneration();
        Throwable failure = null;

        try
        {
            if (this.orbit.isGestureOwnedBy(context.mouseButton))
            {
                this.orbit.handleOrbiting(context);
            }
        }
        catch (RuntimeException | Error exception)
        {
            failure = mergeInputFailure(failure, exception);
            this.cancelViewportPick(orbitGeneration);
        }

        boolean orbitDragged = this.orbit.wasDragged();
        long dashboardOrbitGeneration = this.isViewFlying() && context.mouseButton == 2
            ? this.panel.dashboard.orbitUI.gestureGeneration()
            : 0L;
        boolean consumed = false;
        boolean orbitReleased = false;
        boolean inherited = false;

        try
        {
            consumed = SoundGuideInteraction.mouseReleased(this, context.mouseButton);
            consumed = this.gizmo.mouseReleased(context) || consumed;
            consumed = this.orbitGizmo.mouseReleased(context) || consumed;
        }
        catch (RuntimeException | Error exception)
        {
            failure = mergeInputFailure(failure, exception);
        }

        try
        {
            orbitReleased = this.orbit.stop(context.mouseButton, orbitGeneration);

            if (orbitReleased)
            {
                this.panel.replayEditor.releaseViewport(context, orbitDragged, this, orbitGeneration);
            }
            else if (this.picker.pendingPickOrbitGeneration == 0L)
            {
                consumed = this.releaseViewportPick(context, false, 0L) || consumed;
            }
        }
        catch (RuntimeException | Error exception)
        {
            failure = mergeInputFailure(failure, exception);
        }

        try
        {
            if (this.isViewFlying() && context.mouseButton == 2)
            {
                this.panel.dashboard.orbitUI.stopGesture(context.mouseButton, dashboardOrbitGeneration);
            }
        }
        catch (RuntimeException | Error exception)
        {
            failure = mergeInputFailure(failure, exception);
        }

        if (!controlling)
        {
            try
            {
                inherited = super.subMouseReleased(context);
            }
            catch (RuntimeException | Error exception)
            {
                failure = mergeInputFailure(failure, exception);
            }
        }

        rethrowInputFailure(failure);

        return controlling || consumed || orbitReleased || inherited;
    }

    /** Cancel a sibling-owned viewport gesture without committing its deferred pick. */
    public void cancelViewportGesture(UIContext context)
    {
        if (this.picker.pendingViewportPick.isOwnedBy(context.mouseButton))
        {
            this.cancelPendingViewportPick();
        }

        SoundGuideInteraction.cancel(this, context.mouseButton);
        this.orbitGizmo.cancel();

        long gizmoGeneration = this.gizmo.gestureGeneration();
        long orbitGeneration = this.orbit.gestureGeneration();
        long dashboardOrbitGeneration = this.isViewFlying() && context.mouseButton == 2
            ? this.panel.dashboard.orbitUI.gestureGeneration()
            : 0L;

        if (this.orbit.stop(context.mouseButton, orbitGeneration)
            && this.panel.replayEditor != null)
        {
            this.panel.replayEditor.cancelViewportPick(this, orbitGeneration);
        }

        if (this.isViewFlying() && context.mouseButton == 2)
        {
            this.panel.dashboard.orbitUI.stopGesture(context.mouseButton, dashboardOrbitGeneration);
        }

        this.gizmo.cancel(context.mouseButton, gizmoGeneration);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.cancelViewportGesture(context);
        super.subMouseCanceled(context);
    }

    private static Throwable mergeInputFailure(Throwable failure, Throwable exception)
    {
        if (failure == null)
        {
            return exception;
        }

        if (failure != exception)
        {
            failure.addSuppressed(exception);
        }

        return failure;
    }

    private static void rethrowInputFailure(Throwable failure)
    {
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
    protected boolean subKeyPressed(UIContext context)
    {
        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            if (tool.key(context))
            {
                return true;
            }
        }

        if (this.canControl())
        {
            if (this.isControlling() && context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                this.toggleControl();
                UIUtils.playClick();

                return true;
            }
            else if (context.getKeyAction() == KeyAction.PRESSED && context.getKeyCode() >= GLFW.GLFW_KEY_1 && context.getKeyCode() <= GLFW.GLFW_KEY_6)
            {
                /* Switch mouse input mode */
                this.setMouseMode(context.getKeyCode() - GLFW.GLFW_KEY_1);

                return true;
            }

            InputConstants.Key utilKey = InputConstants.getKey(context.getKeyCode(), context.getScanCode());

            if (this.canControlWithKeyboard(utilKey))
            {
                return true;
            }
        }

        return super.subKeyPressed(context);
    }

    private boolean canControlWithKeyboard(InputConstants.Key utilKey)
    {
        if (!ClientNetwork.isIsBBSModOnServer())
        {
            return false;
        }

        Options options = Minecraft.getInstance().options;

        return options.keyUp.getDefaultKey() == utilKey
            || options.keyDown.getDefaultKey() == utilKey
            || options.keyLeft.getDefaultKey() == utilKey
            || options.keyRight.getDefaultKey() == utilKey
            || options.keyShift.getDefaultKey() == utilKey
            || options.keySprint.getDefaultKey() == utilKey
            || options.keyJump.getDefaultKey() == utilKey;
    }

    public void pickRecording()
    {
        this.recorder.pickRecording();
    }

    public Icon getOrbitModeIcon()
    {
        return this.getOrbitModeIcon(this.getPovMode());
    }

    public Icon getOrbitModeIcon(int povMode)
    {
        int mode = Math.floorMod(povMode, CAMERA_MODE_COUNT);

        if (mode == UIFilmController.CAMERA_MODE_FREE) return Icons.REFRESH;
        else if (mode == UIFilmController.CAMERA_MODE_ORBIT) return Icons.ORBIT;
        else if (mode == UIFilmController.CAMERA_MODE_FIRST_PERSON) return Icons.VISIBLE;
        else if (mode == UIFilmController.CAMERA_MODE_THIRD_PERSON_BACK) return Icons.ARROW_UP;
        else if (mode == UIFilmController.CAMERA_MODE_THIRD_PERSON_FRONT) return Icons.ARROW_DOWN;

        return Icons.CAMERA;
    }

    private IKey getOrbitModeLabel(int povMode)
    {
        return CAMERA_MODE_LABELS[Math.floorMod(povMode, CAMERA_MODE_COUNT)];
    }

    public void populateCameraModeMenu(ContextMenuManager menu)
    {
        int povMode = this.getPovMode();

        for (int mode = 0; mode < CAMERA_MODE_COUNT; mode++)
        {
            int finalMode = mode;

            menu.action(this.getOrbitModeIcon(mode), this.getOrbitModeLabel(mode), povMode == mode, () -> this.setPov(finalMode));
        }
    }

    public void teleportOrbitPivotToReplay()
    {
        this.orbit.teleportPivotToReplay();
    }

    public boolean zoomOrbit(double mouseWheel)
    {
        return this.orbit.zoom(mouseWheel);
    }

    public void toggleOrbitAttachment()
    {
        this.orbit.toggleAttachment();
    }

    public void toggleOrbitMode()
    {
        if (this.isControlling())
        {
            this.setPov(this.pov + (Window.isShiftPressed() ? -1 : 1));

            return;
        }

        this.getContext().replaceContextMenu((menu) ->
        {
            menu.autoKeys();

            this.populateCameraModeMenu(menu);
        });
    }

    public void toggleReplayMenu()
    {
        if (this.controlled != null)
        {
            return;
        }

        UISimpleContextMenu menu = new UISimpleContextMenu();

        menu.actions.scroll.scrollItemSize = 30;

        this.getContext().replaceContextMenu((manager) ->
        {
            manager.custom(menu);
            manager.autoKeys();

            for (Replay replay : this.panel.getData().replays.getList())
            {
                int color = this.getReplay() == replay ? BBSSettings.primaryColor(0) : 0;

                manager.action(new ReplayContextAction(replay, IKey.raw(replay.getName()), () ->
                {
                    this.panel.replayEditor.setReplay(replay, false, UIReplaysEditor.OrbitReaction.SWITCH);

                    UIReplayList list = this.panel.replayEditor.replaysList.replays;

                    list.scrollToReplay(replay);

                    UIUtils.playClick();
                }, color));
            }
        });
    }

    public void handleCamera(Camera camera, float transition)
    {
        this.setViewOrthoDistance(-1F);

        if (this.orbit.enabled)
        {
            if (this.isViewFlying())
            {
                this.orbit.follow(camera, transition);

                return;
            }

            int mode = this.getPovMode();

            if (mode == CAMERA_MODE_ORBIT)
            {
                this.orbit.setup(camera, transition);

                if (!this.isViewFlying() && this.preview == null)
                {
                    camera.fov = BBSSettings.getFov();
                }
            }
            else if (mode != CAMERA_MODE_FREE)
            {
                this.handleFirstThirdPerson(camera, transition, mode);
            }
        }
    }

    private void handleFirstThirdPerson(Camera camera, float transition, int mode)
    {
        IEntity actor = this.getCurrentEntity();

        if (actor == null)
        {
            return;
        }

        if (mode == CAMERA_MODE_FIRST_PERSON)
        {
            ActorCamera.firstPerson(camera, actor, transition);
        }
        else
        {
            ActorCamera.thirdPerson(camera, actor, transition, mode == CAMERA_MODE_THIRD_PERSON_BACK);
        }

        camera.fov = this.getNavigationFov();
    }

    private float getNavigationFov()
    {
        return this.preview == null ? BBSSettings.getFov()
            : MathUtils.toRad(this.preview.getViewDescriptor().getNavigation().getFreePose().angle.fov);
    }

    public void insertFrame()
    {
        this.keyframes.insertFrame();
    }

    /** Insert the live player's position and rotation at the current tick. */
    public void insertPlayerFrame()
    {
        this.keyframes.insertPlayerFrame();
    }

    /* Update */

    public void update()
    {
        if (!this.sceneOwner)
        {
            return;
        }

        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        RunnerCameraController runner = this.panel.getRunner();

        this.handleRecording(runner);

        if (this.editorController != null)
        {
            this.editorController.update();
        }

        if (this.canControl())
        {
            this.updateControls();
        }
    }

    private void handleRecording(RunnerCameraController runner)
    {
        this.recorder.handleRecording(runner);
    }

    private void updateControls()
    {
        this.mouse.applyTo(this.controlled);

        if (this.instantKeyframes)
        {
            this.insertFrame();
        }
    }

    /* Render */

    public void renderHUD(UIContext context, Area area)
    {
        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            tool.updateTool(context);
        }

        Gizmo.INSTANCE.captureVisualState(this.previousVisualState);

        ViewRenderState state = this.preview == null ? null : BBSRendering.getViewRenderState(this.preview.getViewDescriptor().getId());
        this.cameraMarkers.publish(state);

        if (state != null && state.hasFrame() && state.getSampleFrameId() == this.worldContextFrame)
        {
            Gizmo.INSTANCE.restoreVisualState(this.pendingVisualState);
            Gizmo.INSTANCE.captureVisualState(this.visualState);
        }

        Gizmo.INSTANCE.restoreVisualState(this.visualState);

        try
        {
            this.renderViewHUD(context, area);
            if (this.preview != null)
            {
                this.cameraMarkers.renderOverlay(this.panel, this.preview, context);
            }
            Gizmo.INSTANCE.captureVisualState(this.visualState);
        }
        finally
        {
            Gizmo.INSTANCE.restoreVisualState(this.previousVisualState);
        }
    }


    private void renderViewHUD(UIContext context, Area area)
    {
        this.hud.renderViewHUD(context, area);
    }

    void renderPickingPreview(UIContext context, Area area)
    {
        this.picker.renderPickingPreview(context, area);
    }

    public void startRenderFrame(float tickDelta)
    {
        if (this.sceneOwner && this.editorController != null)
        {
            this.editorController.startRenderFrame(tickDelta);
        }
    }

    private void updateViewStencil(UIContext context, boolean altPressed)
    {
        this.picker.updateViewStencil(context, altPressed);
    }

    private boolean hasDisplayedStencil(boolean altPressed)
    {
        return this.picker.hasDisplayedStencil(altPressed);
    }

    public boolean isViewportPickReady()
    {
        return this.picker.isViewportPickReady();
    }

    private void prepareViewportPick(UIContext context)
    {
        this.picker.prepareViewportPick(context);
    }

    private void readViewStencil(UIContext context)
    {
        this.picker.readViewStencil(context);
    }

    public void renderFrame(IBbsWorldRenderContext context)
    {
        FilmEditorController shared = this.panel.getController().editorController;
        boolean shadow = BBSRendering.isIrisShadowPass();
        boolean export = this.panel.recorder.isExporting();
        UIFilmController previousController = shared == null ? null : shared.controller;

        this.renderingCamera.copy(this.preview == null ? this.panel.getCamera() : this.preview.getViewDescriptor().getCamera());
        this.renderingCamera.view.set(context.modelViewMatrix());
        this.renderingCamera.projection.set(context.projectionMatrix());
        this.renderingView = true;
        Gizmo.INSTANCE.captureVisualState(this.previousVisualState);
        Gizmo.INSTANCE.restoreVisualState(this.visualState);
        RenderSystem.enableDepthTest();

        try
        {
            if (shared != null)
            {
                shared.controller = this;
                shared.render(context);
            }

            if (!shadow && !export)
            {
                if (this.preview != null)
                {
                    this.cameraMarkers.sampleWorld(this.panel, this.preview);
                }

                this.renderOrbitCenterMarker(context);
                ValueMotionPath motionPath = this.getMotionPath();

                if (motionPath.enabled.get() && !this.isRecording())
                {
                    boolean pinned = this.isMotionPathPinned();
                    Replay replay = pinned ? this.motionPathPin.getReplay() : this.getReplay();
                    FilmTarget target = pinned ? this.motionPathPin.getTarget() : this.getEditTarget();

                    MotionPath.render(context, motionPath, this, replay, target, replay == null ? 0F : replay.getTick(this.getTick()));
                }

                this.pickingCamera.capture(context.camera());
                PoseStack pickingStack = new PoseStack();

                MatrixStackUtils.multiply(pickingStack, context.modelViewMatrix());
                this.worldRenderContext = new BbsWorldRenderContext(this.pickingCamera, pickingStack, context.consumers(),
                    context.tickDelta(), context.modelViewMatrix(), context.projectionMatrix());
                this.worldContextFrame = BBSRendering.getSceneFrameId();
                Gizmo.INSTANCE.captureVisualState(this.pendingVisualState);
            }
        }
        finally
        {
            if (shared != null)
            {
                shared.controller = previousController;
            }

            this.renderingView = false;
            Gizmo.INSTANCE.restoreVisualState(this.previousVisualState);
            RenderSystem.disableDepthTest();
        }

        if (!this.sceneOwner || shadow)
        {
            return;
        }

        this.mouse.trackCursor(this.canControl(), ClientNetwork.isIsBBSModOnServer());

        RenderSystem.disableDepthTest();

        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            tool.renderTool(context);
        }
    }

    private void renderOrbitCenterMarker(IBbsWorldRenderContext context)
    {
        if (this.getPovMode() != CAMERA_MODE_ORBIT || !BBSSettings.editorOrbitCenterMarker.get())
        {
            return;
        }

        Vector3d center = this.orbit.getOrbitCenter(this.getCurrentTransition());

        if (center == null)
        {
            return;
        }

        Vec3 camera = context.camera().getPosition();
        double x = center.x - camera.x;
        double y = center.y - camera.y;
        double z = center.z - camera.z;
        float distanceScale = BBSSettings.getScreenSizeScale((float) Math.sqrt(x * x + y * y + z * z));
        PoseStack stack = context.matrixStack();

        stack.pushPose();
        stack.translate(x, y, z);
        stack.scale(distanceScale, distanceScale, distanceScale);
        Draw.coolerAxes(stack, 0.12F, 0.007F, 0.13F, 0.017F);
        stack.popPose();

        RenderSystem.enableDepthTest();
    }

    private float getCurrentTransition()
    {
        UIContext context = this.getContext();

        return context == null ? 0F : context.getTransition();
    }

    /** Single source of truth for the transform target exposed to film tools and motion paths. */
    public FilmTarget getEditTarget()
    {
        FilmTarget original = this.getDefaultEditTarget();

        if (original.isNone())
        {
            return original;
        }

        for (mchorse.bbs_mod.api.client.editor.FilmEditorTool tool : this.addonTools)
        {
            FilmTarget target = tool.target(original);

            if (target != null)
            {
                return target;
            }
        }

        return original;
    }

    private FilmTarget getDefaultEditTarget()
    {
        if (this.isRecording() || this.panel.replayEditor == null)
        {
            return FilmTarget.NONE;
        }

        Pair<String, Boolean> bone = this.getBone();

        if (bone != null)
        {
            return FilmTarget.bone(bone.a, this.getBoneSpace());
        }

        if (this.isAnchorGizmo())
        {
            return FilmTarget.anchor(this.getAnchorSpace());
        }

        Replay replay = this.getReplay();

        if (replay != null && replay.enabled.get() && this.getCurrentEntity() != null)
        {
            return FilmTarget.root(TransformSpace.WORLD);
        }

        return FilmTarget.NONE;
    }

    public Pair<String, Boolean> getBone()
    {
        UIKeyframeEditor keyframeEditor = this.panel.replayEditor.keyframeEditor;

        return keyframeEditor != null ? keyframeEditor.getBone() : null;
    }

    public TransformSpace getBoneSpace()
    {
        UIKeyframeEditor keyframeEditor = this.panel.replayEditor.keyframeEditor;

        return keyframeEditor != null ? keyframeEditor.getBoneSpace() : TransformSpace.LOCAL;
    }

    public TransformSpace getAnchorSpace()
    {
        UIKeyframeEditor keyframeEditor = this.panel.replayEditor.keyframeEditor;

        return keyframeEditor != null ? keyframeEditor.getAnchorSpace() : TransformSpace.LOCAL;
    }

    public Matrix4f getGizmoView()
    {
        return this.getViewCamera().view;
    }

    public boolean isAnchorGizmo()
    {
        UIKeyframeEditor keyframeEditor = this.panel.replayEditor.keyframeEditor;

        return keyframeEditor != null && keyframeEditor.isFormAnchorTrack();
    }

    public boolean getAnchorLocal()
    {
        UIKeyframeEditor keyframeEditor = this.panel.replayEditor.keyframeEditor;

        return keyframeEditor != null && keyframeEditor.getAnchorLocal();
    }

    boolean canShowGizmo()
    {
        Replay replay = this.getReplay();

        /* A disabled replay is skipped by the render pass, so its gizmo placement
         * stops being captured. Hide the gizmo instead of letting it linger on the
         * last captured (stale) matrix. */
        return this.isViewActive() && UIBaseMenu.shouldRenderAxes() && !this.isRecording() && !this.picker.isPickingTarget()
            && (replay == null || replay.enabled.get())
            && !this.getEditTarget().isNone();
    }

    private boolean renderStencil(IBbsWorldRenderContext renderContext, UIContext context, boolean altPressed)
    {
        return this.picker.renderStencil(renderContext, context, altPressed);
    }

    private void ensureStencilFramebuffer()
    {
        this.picker.ensureStencilFramebuffer();
    }
}
