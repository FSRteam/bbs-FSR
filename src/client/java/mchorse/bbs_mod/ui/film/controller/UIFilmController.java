package mchorse.bbs_mod.ui.film.controller;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
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
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.film.view.ViewCameraSnapshot;
import mchorse.bbs_mod.ui.film.view.FilmCameraMarkers;
import mchorse.bbs_mod.ui.film.view.FilmViewMenus;
import mchorse.bbs_mod.ui.film.view.ViewFrameGeometry;
import mchorse.bbs_mod.ui.film.view.ViewportPickIntent;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.film.replays.UIRecordOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
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
    private static final Logger LOGGER = LogUtils.getLogger();

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
    private static final int REPLAY_STENCIL_OFFSET = Gizmo.STENCIL_MAX + 1;

    public final UIFilmPanel panel;
    private final boolean sceneOwner;
    private UIFilmPreview preview;
    private final FilmCameraMarkers cameraMarkers = new FilmCameraMarkers();

    public FilmEditorController editorController;
    private Map<String, Integer> actors;

    /* Character control */
    private IEntity controlled;
    private final Vector2i lastMouse = new Vector2i();
    private int mouseMode;
    private final Vector2f mouseStick = new Vector2f();

    /* Recording state */
    private IEntity previousEntity;
    private Form playerForm;
    private int recordingTick;
    private boolean recording;
    private int recordingCountdown;
    private List<String> recordingGroups;
    private BaseType recordingOld;
    private boolean instantKeyframes;

    /* Replay and group picking */
    private int hoveredReplayIndex = -1;
    private StencilFormFramebuffer stencil = new StencilFormFramebuffer();
    private StencilMap stencilMap = new StencilMap();
    private final GizmoInteraction gizmo = new GizmoInteraction(this);

    public final OrbitFilmCameraController orbit = new OrbitFilmCameraController(this);
    public final OrbitViewGizmo orbitGizmo = new OrbitViewGizmo(this);
    private int pov;
    private boolean paused;

    private IBbsWorldRenderContext worldRenderContext;
    private final Camera renderingCamera = new Camera();
    private final ViewCameraSnapshot pickingCamera = new ViewCameraSnapshot();
    private final Gizmo.VisualState visualState = new Gizmo.VisualState();
    private final Gizmo.VisualState pendingVisualState = new Gizmo.VisualState();
    private final Gizmo.VisualState previousVisualState = new Gizmo.VisualState();
    private boolean renderingView;
    private long worldContextFrame = -1L;
    private long stencilFrame = -1L;
    private boolean stencilAlt;
    private Replay stencilReplay;
    private final ViewportPickIntent<ViewportPick> pendingViewportPick = new ViewportPickIntent<>();
    private long pendingPickOrbitGeneration;
    private long queuedPickGeneration;

    private record ViewportPick(Replay replay, Pair<Form, String> form, boolean empty)
    {}

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

    private boolean isViewActive()
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

    private void toggleMousePointer(boolean disable)
    {
        com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();

        if (disable)
        {
            GLFW.glfwSetInputMode(window.getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
        else
        {
            GLFW.glfwSetInputMode(window.getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    public ValueOnionSkin getOnionSkin()
    {
        return this.preview == null ? BBSSettings.editorOnionSkin : this.preview.getViewDescriptor().getOnionSkin();
    }

    public ValueMotionPath getMotionPath()
    {
        return this.preview == null ? BBSSettings.editorMotionPath : this.preview.getViewDescriptor().getMotionPath();
    }

    private Replay pinnedReplay;
    private Pair<String, Boolean> pinnedBone;

    public boolean isMotionPathPinned()
    {
        if (this.pinnedReplay != null && this.panel.getData() != null && CollectionUtils.getIndex(this.panel.getData().replays.getList(), this.pinnedReplay) < 0)
        {
            this.unpinMotionPath();
        }

        return this.pinnedReplay != null;
    }

    public void pinMotionPath()
    {
        Replay replay = this.getReplay();

        this.pinnedReplay = replay;
        this.pinnedBone = replay == null ? null : this.getBone();
    }

    public void unpinMotionPath()
    {
        this.pinnedReplay = null;
        this.pinnedBone = null;
    }

    public void toggleMotionPathPin()
    {
        if (this.isMotionPathPinned()) this.unpinMotionPath();
        else this.pinMotionPath();
    }

    private int getTick()
    {
        return this.panel.getCursor();
    }

    private Replay getReplay()
    {
        return this.panel.replayEditor.getReplay();
    }

    private int getCurrentReplayIndex()
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
        return this.stencil;
    }

    public IEntity getCurrentEntity()
    {
        if (this.panel.getData() == null)
        {
            return null;
        }

        int idx = this.getCurrentReplayIndex();

        return idx < 0 ? null : this.getEntities().get(idx);
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

    private int getMouseMode()
    {
        return Math.floorMod(this.mouseMode, 6);
    }

    private void setMouseMode(int mode)
    {
        if (!ClientNetwork.isIsBBSModOnServer() && mode == 0)
        {
            mode = 1;

            this.getContext().notifyError(UIKeys.FILM_CONTROLLER_SERVER_WARNING);
        }

        this.mouseMode = mode;

        if (this.controlled != null)
        {
            /* Restore value of the mouse stick */
            int index = this.getMouseMode() - 1;

            if (index >= 0)
            {
                float[] variables = this.controlled.getExtraVariables();

                this.mouseStick.set(variables[index * 2 + 1], variables[index * 2]);
            }
        }
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

        IntObjectMap<IEntity> entities = this.panel.getRunner().getContext().entities;

        entities.clear();
        entities.putAll(this.editorController.getEntities());
    }

    public void shutdown()
    {
        this.resetViewInteraction();

        if (this.sceneOwner && this.editorController != null)
        {
            this.editorController.shutdown();
        }
    }

    public IntObjectMap<IEntity> getEntities()
    {
        if (!this.sceneOwner)
        {
            return this.panel.getController().getEntities();
        }

        return this.editorController == null ? new IntObjectHashMap<>() : this.editorController.getEntities();
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
        IntObjectMap<IEntity> entities = this.getEntities();

        if (this.controlled != null)
        {
            if (replacePlayer && this.previousEntity != null)
            {
                this.controlled.setForm(this.playerForm);

                Integer controlledIndex = CollectionUtils.getKey(entities, this.controlled);

                if (controlledIndex != null)
                {
                    entities.put(controlledIndex, this.previousEntity);
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
                Integer controlledIndex = CollectionUtils.getKey(entities, this.controlled);

                if (controlledIndex != null)
                {
                    entities.put(controlledIndex, player);
                }

                this.controlled = player;
            }
        }

        this.setMouseMode(this.mouseMode);
        this.toggleMousePointer(this.controlled != null);

        if (this.controlled == null && this.recording)
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
        return this.sceneOwner ? this.recording : this.panel.getController().isRecording();
    }

    public int getRecordingCountdown()
    {
        return this.sceneOwner ? this.recordingCountdown : this.panel.getController().getRecordingCountdown();
    }

    public List<String> getRecordingGroups()
    {
        return this.sceneOwner ? this.recordingGroups : this.panel.getController().getRecordingGroups();
    }

    private boolean hasTransformRecordingGroup()
    {
        return this.recordingGroups != null && this.recordingGroups.contains(ReplayKeyframes.GROUP_TRANSFORM);
    }

    public boolean isTransformRecording()
    {
        if (!this.sceneOwner)
        {
            return this.panel.getController().isTransformRecording();
        }

        return this.recording
            && this.recordingCountdown <= 0
            && this.hasTransformRecordingGroup();
    }

    public void startRecording(List<String> groups)
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().startRecording(groups);

            return;
        }

        if (groups != null && groups.contains("outside"))
        {
            Film film = this.panel.getData();
            Replay replay = this.panel.replayEditor.getReplay();
            int index = film == null ? -1 : CollectionUtils.getIndex(film.replays.getList(), replay);
            int cursor = this.panel.getCursor();

            if (film == null || index < 0)
            {
                return;
            }

            /* Closing the dashboard synchronously tears down the Film panel. Keep
             * every recording input before that boundary; the world recorder must
             * never reach back into a panel which has already disappeared. */
            Minecraft.getInstance().setScreen(null);
            /* On the mark: started from the editor, at a cursor the editor chose,
             * so the take begins where the replay itself stands at that tick */
            BBSModClient.getFilms().startRecording(film, index, cursor, true);

            return;
        }

        this.recordingTick = this.getTick();
        this.recording = true;
        this.recordingCountdown = Math.max(0, TimeUtils.toTick(BBSSettings.recordingCountdown.get()));
        this.recordingGroups = groups;
        boolean transformRecording = groups != null && groups.contains(ReplayKeyframes.GROUP_TRANSFORM);

        this.recordingOld = transformRecording ? this.getReplay().properties.toData() : this.getReplay().keyframes.toData();

        if (transformRecording)
        {
            if (this.controlled != null)
            {
                this.toggleControl();
            }

            this.setMouseMode(0);
        }
        else if (groups != null)
        {
            if (groups.contains(ReplayKeyframes.GROUP_LEFT_STICK))
            {
                this.setMouseMode(1);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_RIGHT_STICK))
            {
                this.setMouseMode(2);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_TRIGGERS))
            {
                this.setMouseMode(3);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_EXTRA1))
            {
                this.setMouseMode(4);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_EXTRA2))
            {
                this.setMouseMode(5);
            }
            else
            {
                this.setMouseMode(0);
            }
        }

        if (!transformRecording && this.controlled == null)
        {
            this.toggleControl();
        }

        this.toggleMousePointer(!transformRecording && this.controlled != null);

        /* No countdown means the take starts now. Without this nothing ever started it: the countdown
         * branch in handleRecording is what calls togglePlayback, and it only runs while the counter
         * is still above zero — with the setting at 0 the very first tick fell straight through to
         * "the film is not running, so the take is over" and stopped the recording before a single
         * frame was written. */
        this.startPlayback();
    }

    /**
     * Begin playback for a take, unless the film is already running (the editor can be playing when a
     * recording starts). Never a blind {@code togglePlayback} — that would stop it instead.
     */
    private void startPlayback()
    {
        if (this.recordingCountdown <= 0 && !this.panel.getRunner().isRunning())
        {
            this.panel.togglePlayback();
        }
    }

    public void stopRecording()
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().stopRecording();

            return;
        }

        if (!this.recording)
        {
            return;
        }

        boolean transformRecording = this.hasTransformRecordingGroup();

        this.recording = false;
        this.recordingGroups = null;

        if (!transformRecording && this.controlled != null)
        {
            this.toggleControl();
        }

        this.panel.setCursor(this.recordingTick);

        if (this.panel.getRunner().isRunning())
        {
            this.panel.togglePlayback();
        }

        if (this.recordingCountdown > 0)
        {
            return;
        }

        Replay replay = this.getReplay();

        if (replay != null && this.recordingOld != null)
        {
            if (transformRecording)
            {
                for (KeyframeChannel<?> channel : replay.properties.properties.values())
                {
                    if (PerLimbService.isPoseBoneChannel(channel.getId()))
                    {
                        channel.simplify();
                    }
                }

                BaseType newData = replay.properties.toData();

                replay.properties.fromData(this.recordingOld);
                replay.properties.preNotify();
                replay.properties.fromData(newData);
                replay.properties.postNotify();

                if (this.panel.replayEditor.getReplay() == replay)
                {
                    this.panel.replayEditor.setReplay(replay, false, UIReplaysEditor.OrbitReaction.SWITCH);
                }
            }
            else
            {
                for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
                {
                    channel.simplify();
                }

                BaseType newData = replay.keyframes.toData();

                replay.keyframes.fromData(this.recordingOld);
                replay.keyframes.preNotify();
                replay.keyframes.fromData(newData);
                replay.keyframes.postNotify();
            }

            this.recordingOld = null;
        }

        this.setMouseMode(ClientNetwork.isIsBBSModOnServer() ? 0 : 1);
    }

    /* Input handling */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
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

        if (context.mouseButton == 0 && this.hoveredReplayIndex >= 0)
        {
            this.pickReplay(this.hoveredReplayIndex);

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
            this.stencil,
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
        return this.stencil;
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
        float gizmoTransition = this.isPlaying() ? context.getTransition() : 0F;

        return UIReplaysEditorUtils.startFilmGizmo(this.panel, this.getViewCamera(), this.getViewArea(), context, stencilIndex, gizmoTransition);
    }

    @Override
    public void pickGizmoForm(UIContext context, Form form, String bone)
    {
        this.panel.replayEditor.pickFormWithOffers(context, form, bone);
    }

    private void pickReplay(int index)
    {
        this.panel.replayEditor.setReplay(this.panel.getData().replays.getList().get(index));

        if (!this.panel.replayEditor.isVisible())
        {
            this.panel.showPanel(this.panel.replayEditor);
        }
    }

    public void stopGizmoInteraction()
    {
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
        this.hoveredReplayIndex = -1;
        this.stencil.clearPicking();
        this.stencilFrame = -1L;
        this.stencilReplay = null;
    }

    public boolean clickViewport(UIContext context)
    {
        if (this.preview == null || !this.preview.isInsideFrame(context))
        {
            return false;
        }

        this.cancelPendingViewportPick();
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
        if (this.preview == null || !this.preview.isInsideFrame(context) || this.isViewFlying() || this.isControlling())
        {
            return false;
        }

        ViewportPickIntent.Input input = new ViewportPickIntent.Input(context.mouseX, context.mouseY, context.mouseButton,
            Window.isAltPressed(), Window.isCtrlPressed(), Window.isShiftPressed());
        long generation = this.pendingViewportPick.begin(this.viewportPickTarget(), input,
            context.getPointerGestureGeneration(), context.getContextMenuIntentGeneration());

        this.pendingPickOrbitGeneration = orbitGeneration;
        this.queuedPickGeneration = 0L;

        if (generation == 0L)
        {
            return false;
        }

        if (!input.alt() && (this.getCurrentEntity() == null || this.pov == CAMERA_MODE_FIRST_PERSON))
        {
            this.pendingViewportPick.resolve(generation, new ViewportPick(null, null, true));
        }
        else if (this.hasDisplayedStencil(input.alt()))
        {
            this.readViewStencil(context);
            this.resolveViewportPick();
        }
        else
        {
            this.preview.requestRefresh();
        }

        return true;
    }

    private ViewportPickIntent.Target viewportPickTarget()
    {
        Area frame = this.getViewArea();

        return new ViewportPickIntent.Target(this.preview, this.panel.getData(), this.getReplay(),
            this.preview.getViewDescriptor().getHistoryEpoch(), this.pov, this.panel.getCursor(),
            new ViewFrameGeometry(frame.x, frame.y, frame.w, frame.h));
    }

    private boolean updatePendingViewportPick(UIContext context)
    {
        if (this.pendingViewportPick.generation() == 0L)
        {
            return false;
        }

        this.pendingViewportPick.move(context.mouseX, context.mouseY);

        return this.pendingViewportPick.validate(this.viewportPickTarget(),
            context.getPointerGestureGeneration(), context.getContextMenuIntentGeneration(),
            this.isViewActive() && this.preview.canBeSeen() && !this.isViewFlying() && !this.isControlling()
                && !context.hasContextMenu() && !UIOverlay.has(context));
    }

    private void resolveViewportPick()
    {
        ViewportPickIntent.Input input = this.pendingViewportPick.input();

        if (input == null || this.pendingViewportPick.isResolved() || !this.hasDisplayedStencil(input.alt()))
        {
            return;
        }

        Replay replay = null;
        Pair<Form, String> form = null;

        if (this.stencil.hasPicked())
        {
            int index = this.stencil.getIndex() - REPLAY_STENCIL_OFFSET;
            List<Replay> replays = this.panel.getData().replays.getList();

            if (input.alt() && input.button() == 0 && index >= 0 && index < replays.size()
                && index != this.getCurrentReplayIndex())
            {
                replay = replays.get(index);
            }
            else
            {
                Pair<Form, String> picked = this.stencil.getPicked();

                if (picked != null && picked.a != null)
                {
                    form = new Pair<>(picked.a, picked.b);
                }
            }
        }

        this.pendingViewportPick.resolve(this.pendingViewportPick.generation(), new ViewportPick(replay, form, !this.stencil.hasPicked()));
    }

    public boolean releaseViewportPick(UIContext context, boolean dragged, long orbitGeneration)
    {
        if (this.pendingPickOrbitGeneration != orbitGeneration || !this.pendingViewportPick.isOwnedBy(context.mouseButton))
        {
            return false;
        }

        long generation = this.pendingViewportPick.generation();
        boolean released = this.pendingViewportPick.release(context.mouseButton, generation, context.mouseX, context.mouseY, dragged);

        if (this.updatePendingViewportPick(context))
        {
            this.commitViewportPick(context, generation);
        }

        return released;
    }

    public void cancelViewportPick(long orbitGeneration)
    {
        if (this.pendingPickOrbitGeneration == orbitGeneration)
        {
            this.cancelPendingViewportPick();
        }
    }

    public void cancelPendingViewportPick()
    {
        this.pendingViewportPick.cancel();
        this.pendingPickOrbitGeneration = 0L;
        this.queuedPickGeneration = 0L;
    }

    private void queueViewportPick(UIContext context)
    {
        long generation = this.pendingViewportPick.generation();

        if (this.pendingViewportPick.isReady() && this.queuedPickGeneration != generation)
        {
            this.queuedPickGeneration = generation;
            context.render.postRunnable(() -> this.commitViewportPick(context, generation));
        }
    }

    private void commitViewportPick(UIContext context, long generation)
    {
        if (!this.updatePendingViewportPick(context))
        {
            return;
        }

        ViewportPickIntent.Completion<ViewportPick> completion = this.pendingViewportPick.take(generation);

        if (completion == null || completion.result() == null)
        {
            return;
        }

        ViewportPick pick = completion.result();
        ViewportPickIntent.Input input = completion.input();

        context.withPointerState(input.x(), input.y(), input.button(), () ->
        {
            if (pick.replay() != null)
            {
                int index = CollectionUtils.getIndex(this.panel.getData().replays.getList(), pick.replay());

                if (index >= 0)
                {
                    this.pickReplay(index);
                }
            }
            else if (pick.form() != null)
            {
                this.panel.replayEditor.pickViewportFormWithOffers(context, pick.form(), input);
            }
            else if (pick.empty() && input.button() == 1)
            {
                this.panel.replayEditor.openViewportContextMenu(context, this.getViewArea(), this, input.shift());
            }
        });
    }

    public void releaseViewResources()
    {
        this.resetViewInteraction();
        Link id = Link.bbs("stencil_film_" + (this.preview == null ? "preview" : this.preview.getViewDescriptor().getId()));

        if (this.stencil.getFramebuffer() != null
            && BBSModClient.getFramebuffers().framebuffers.remove(id, this.stencil.getFramebuffer()))
        {
            this.stencil.getFramebuffer().delete();
        }

        this.stencil = new StencilFormFramebuffer();
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
            else if (this.pendingPickOrbitGeneration == 0L)
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
        if (this.pendingViewportPick.isOwnedBy(context.mouseButton))
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
        if (!this.sceneOwner)
        {
            this.panel.getController().pickRecording();

            return;
        }

        if (this.panel.replayEditor.getReplay() == null)
        {
            return;
        }

        if (this.recording)
        {
            this.stopRecording();

            return;
        }

        this.toggleMousePointer(false);

        UIRecordOverlayPanel panel = new UIRecordOverlayPanel(
            UIKeys.FILM_CONTROLLER_RECORD_TITLE,
            UIKeys.FILM_CONTROLLER_RECORD_DESCRIPTION,
            this::startRecording,
            true
        );
        UIIcon icon = new UIIcon(Icons.UPLOAD, (b) -> panel.submit(Arrays.asList("outside")));

        icon.tooltip(UIKeys.FILM_GROUPS_OUTSIDE);
        panel.bar.add(icon);
        panel.keys().register(Keys.RECORDING_GROUP_OUTSIDE, icon::clickItself);

        UIOverlay.addOverlay(this.getContext(), panel);
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
        IEntity controller = this.getCurrentEntity();

        if (controller == null)
        {
            return;
        }

        Vector3d position = new Vector3d();
        Vector3f rotation = new Vector3f();
        float distance = 5F;

        position.set(controller.getPrevX(), controller.getPrevY(), controller.getPrevZ());
        position.lerp(new Vector3d(controller.getX(), controller.getY(), controller.getZ()), transition);
        position.y += controller.getEyeHeight();

        rotation.set(controller.getPrevPitch(), controller.getPrevHeadYaw(), 0);
        rotation.lerp(new Vector3f(controller.getPitch(), controller.getHeadYaw(), 0), transition);

        rotation.x = MathUtils.toRad(rotation.x);
        rotation.y = MathUtils.toRad(rotation.y);

        if (mode == CAMERA_MODE_FIRST_PERSON)
        {
            camera.position.set(position);
            camera.rotation.set(rotation.x, rotation.y + MathUtils.PI, 0F);
            camera.fov = this.getNavigationFov();

            return;
        }

        boolean back = mode == CAMERA_MODE_THIRD_PERSON_BACK;
        Vector3f rotate = Matrices.rotation(rotation.x * (back ? 1 : -1), (back ? 0F : MathUtils.PI) - rotation.y);
        Level world = Minecraft.getInstance().level;

        HitResult result = RayTracing.rayTraceEntity(
            world,
            RayTracing.fromVector3d(position),
            RayTracing.fromVector3f(rotate),
            distance
        );

        if (result.getType() == HitResult.Type.BLOCK)
        {
            distance = (float) position.distance(result.getLocation().x, result.getLocation().y, result.getLocation().z) - 0.1F;
        }

        rotate.mul(distance);
        position.add(rotate);

        camera.position.set(position);
        camera.rotation.set(rotation.x * (back ? -1 : 1), rotation.y + (back ? 0 : MathUtils.PI), 0);
        camera.fov = this.getNavigationFov();
    }

    private float getNavigationFov()
    {
        return this.preview == null ? BBSSettings.getFov()
            : MathUtils.toRad(this.preview.getViewDescriptor().getNavigation().getFreePose().angle.fov);
    }

    public void insertFrame()
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().insertFrame();

            return;
        }

        Replay replay = this.getReplay();

        if (replay == null)
        {
            return;
        }

        UIReplaysEditor.ReplayCategory category = this.panel.replayEditor.getCategory();

        if (category == UIReplaysEditor.ReplayCategory.MODEL)
        {
            return;
        }

        if (category == UIReplaysEditor.ReplayCategory.POSE)
        {
            UIReplaysEditorUtils.insertPoseKeyframesAtTick(replay, this.getTick(), this.panel.replayEditor.getExpandedPoseTabIds());
            return;
        }

        /* PLAYER */
        if (Window.isCtrlPressed())
        {
            this.toggleMousePointer(false);

            UIRecordOverlayPanel panel = new UIRecordOverlayPanel(
                UIKeys.FILM_CONTROLLER_INSERT_FRAME_TITLE,
                UIKeys.FILM_CONTROLLER_INSERT_FRAME_DESCRIPTION,
                (groups) ->
                {
                    BaseValue.edit(replay.keyframes, (keyframes) ->
                    {
                        keyframes.record(this.getTick(), this.getCurrentEntity(), groups);
                    });
                }
            );

            panel.onClose((event) -> this.toggleMousePointer(this.controlled != null));

            UIOverlay.addOverlay(this.getContext(), panel);
        }
        else
        {
            List<String> chosenGroups = Arrays.asList(ReplayKeyframes.GROUP_POSITION, ReplayKeyframes.GROUP_ROTATION);

            if (this.mouseMode == 1) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_LEFT_STICK);
            else if (this.mouseMode == 2) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_RIGHT_STICK);
            else if (this.mouseMode == 3) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_TRIGGERS);
            else if (this.mouseMode == 4) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_EXTRA1);
            else if (this.mouseMode == 5) chosenGroups = Collections.singletonList(ReplayKeyframes.GROUP_EXTRA2);

            final List<String> groups = chosenGroups;

            BaseValue.edit(replay.keyframes, (keyframes) ->
            {
                keyframes.record(this.getTick(), this.getCurrentEntity(), groups);
            });
        }
    }

    /** Insert the live player's position and rotation at the current tick. */
    public void insertPlayerFrame()
    {
        if (!this.sceneOwner)
        {
            this.panel.getController().insertPlayerFrame();

            return;
        }

        Replay replay = this.getReplay();

        if (replay == null || Minecraft.getInstance().player == null)
        {
            return;
        }

        Morph morph = Morph.getMorph(Minecraft.getInstance().player);

        if (morph == null || morph.entity == null)
        {
            return;
        }

        IEntity player = morph.entity;
        int tick = this.getTick();

        BaseValue.edit(replay.keyframes, (keyframes) ->
        {
            keyframes.x.insert(tick, player.getX());
            keyframes.y.insert(tick, player.getY());
            keyframes.z.insert(tick, player.getZ());
            keyframes.yaw.insert(tick, (double) player.getYaw());
            keyframes.pitch.insert(tick, (double) player.getPitch());
            keyframes.headYaw.insert(tick, (double) player.getHeadYaw());
            keyframes.bodyYaw.insert(tick, (double) player.getBodyYaw());
        });

        UIUtils.playClick();
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
        if (this.recording)
        {
            if (this.recordingCountdown > 0)
            {
                this.recordingCountdown -= 1;

                if (this.recordingCountdown <= 0)
                {
                    this.startPlayback();
                }
            }

            if (this.recordingCountdown <= 0)
            {
                boolean stopped = !runner.isRunning();

                if (BBSSettings.editorLoop.get())
                {
                    Vector2i loop = this.panel.getLoopingRange();
                    int min = loop.x;
                    int max = loop.y;
                    int ticks = this.panel.getCursor();

                    if (min >= 0 && max >= 0 && min < max && (ticks >= max - 1 || ticks < min) || stopped)
                    {
                        this.stopRecording();
                    }
                }
                else if (stopped)
                {
                    this.stopRecording();
                }
            }
        }
    }

    private void updateControls()
    {
        IEntity controller = this.controlled;

        if (!this.isMouseLookMode())
        {
            int index = this.getMouseMode() - 1;
            float[] extraVariables = controller.getExtraVariables();

            extraVariables[index * 2] = this.mouseStick.y;
            extraVariables[index * 2 + 1] = this.mouseStick.x;
        }

        if (this.instantKeyframes)
        {
            this.insertFrame();
        }
    }

    /* Render */

    public void renderHUD(UIContext context, Area area)
    {
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
        FontRenderer font = context.batcher.getFont();
        UIFilmController scene = this.panel.getController();
        int mode = scene.getMouseMode();

        if (scene.controlled != null)
        {
            /* Render helpful guides for sticks and triggers controls */
            if (mode > 0)
            {
                String label = UIKeys.FILM_GROUPS_LEFT_STICK.get();

                if (mode == 2)
                {
                    label = UIKeys.FILM_GROUPS_RIGHT_STICK.get();
                }
                else if (mode == 3)
                {
                    label = UIKeys.FILM_GROUPS_TRIGGERS.get();
                }
                else if (mode == 4)
                {
                    label = UIKeys.FILM_GROUPS_EXTRA_1.get();
                }
                else if (mode == 5)
                {
                    label = UIKeys.FILM_GROUPS_EXTRA_2.get();
                }

                context.batcher.textCard(label, area.x + 5, area.ey() - 5 - font.getHeight(), Colors.WHITE, BBSSettings.primaryColor(Colors.A100));

                int ww = (int) (Math.min(area.w, area.h) * 0.75F);
                int hh = ww;
                int x = area.x + (area.w - ww) / 2;
                int y = area.y + (area.h - hh) / 2;
                int color = Colors.setA(Colors.WHITE, 0.5F);

                context.batcher.outline(x, y, x + ww, y + hh, color);

                int bx = area.x + area.w / 2 + (int) (scene.mouseStick.y * ww / 2);
                int by = area.y + area.h / 2 + (int) (scene.mouseStick.x * hh / 2);

                context.batcher.box(bx - 4, by - 4, bx + 4, by + 4, color);
            }

            /* Render recording overlay */
            if (scene.recording)
            {
                int x = area.x + 5 + 16;
                int y = area.y + 5;

                context.batcher.icon(Icons.SPHERE, Colors.RED | Colors.A100, x, y, 1F, 0F);

                if (scene.recordingCountdown <= 0)
                {
                    context.batcher.textCard(UIKeys.FILM_CONTROLLER_TICKS.format(this.getTick()).get(), x + 3, y + 4, Colors.WHITE, Colors.A50);
                }
                else
                {
                    context.batcher.textCard(String.valueOf(scene.recordingCountdown / 20F), x + 3, y + 4, Colors.WHITE, Colors.A50);
                }
            }
        }

        int x = area.ex() - 4;
        int y = area.y + 5;

        if (BBSSettings.editorLoop.get())
        {
            context.batcher.icon(Icons.REFRESH, Colors.WHITE | Colors.A100, x, y, 1F, 0F);

            y += 16 + 5;
        }

        if (this.isViewFlying())
        {
            String label = UIKeys.FILM_CONTROLLER_SPEED.format(this.panel.dashboard.orbit.speed.getValue()).get();
            int w = font.getWidth(label);

            context.batcher.textCard(label, x - w, y, Colors.WHITE, Colors.A50);

            y += font.getHeight() + 7;
        }

        Replay replay = this.panel.replayEditor.getReplay();

        if (replay != null)
        {
            String label = replay.getName();
            int w = font.getWidth(label);

            context.batcher.textCard(label, x - w, y, Colors.WHITE, Colors.A50);

            Form form = replay.form.get();

            if (form != null)
            {
                x -= w + 35;
                y -= 5;

                context.batcher.clip(x, y - 10, 40, 40, context);

                y -= 10;

                FormUtilsClient.renderUI(form, context, x, y, x + 40, y + 40);

                context.batcher.unclip(context);
            }
        }

        /* The visual gizmo draws here, before the picking preview, so the bone /
         * sphere hover highlights composite on top of it. It moved out of the
         * world pass into the UI pipeline so its translucent parts blend
         * correctly (see Gizmo#renderInterface). */
        if (this.canShowGizmo())
        {
            this.gizmo.renderGizmo(context);
        }

        this.renderPickingPreview(context, area);

        this.orbitGizmo.render(context, area);

        this.orbit.handleOrbiting(context);
    }

    private void renderPickingPreview(UIContext context, Area area)
    {
        boolean pending = this.updatePendingViewportPick(context);

        if (this.isViewFlying() || this.worldRenderContext == null || !this.isViewActive()
            || (this.preview != null && !this.preview.isInsideFrame(context) && !pending))
        {
            this.hoveredReplayIndex = -1;

            return;
        }

        boolean altPressed = pending ? this.pendingViewportPick.input().alt() : Window.isAltPressed();

        context.batcher.flush();
        RenderSystem.depthFunc(GL11.GL_LESS);

        /* Cache the global stuff */
        MatrixStackUtils.cacheMatrices();
        Matrix3f previousInverseView = new Matrix3f(InverseView.get());

        try
        {
            RenderSystem.setProjectionMatrix(this.getViewCamera().projection, VertexSorting.DISTANCE_TO_ORIGIN);
            InverseView.set(new Matrix3f(this.getViewCamera().view).invert());

            /* Render the stencil */
            PoseStack worldStack = this.worldRenderContext.matrixStack();

            worldStack.pushPose();

            try
            {
                worldStack.setIdentity();
                MatrixStackUtils.multiply(worldStack, this.getViewCamera().view);
                this.updateViewStencil(context, altPressed);
            }
            finally
            {
                worldStack.popPose();
            }
        }
        finally
        {
            InverseView.set(previousInverseView);
            /* Return back to orthographic projection */
            MatrixStackUtils.restoreMatrices();
        }

        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        this.hoveredReplayIndex = -1;

        if (pending)
        {
            this.resolveViewportPick();
            this.queueViewportPick(context);

            if (!this.preview.isInsideFrame(context) || altPressed != Window.isAltPressed())
            {
                RenderSystem.depthFunc(GL11.GL_LEQUAL);

                return;
            }
        }

        if (this.canShowGizmo())
        {
            this.gizmo.update(context);
            this.gizmo.renderSphereHighlight(context);
            this.gizmo.renderReadout(context);
        }

        if (!this.stencil.hasPicked())
        {
            RenderSystem.depthFunc(GL11.GL_LEQUAL);

            return;
        }

        int index = this.stencil.getIndex();
        Texture texture = this.stencil.getFramebuffer().getMainTexture();
        Pair<Form, String> pair = this.stencil.getPicked();
        int w = texture.width;
        int h = texture.height;

        ShaderInstance previewProgram = BBSShaders.getPickerPreviewProgram();
        Supplier<ShaderInstance> getPickerPreviewProgram = BBSShaders::getPickerPreviewProgram;
        Uniform target = previewProgram.getUniform("Target");

        if (target != null)
        {
            target.set(index);
        }

        Uniform highlight = previewProgram.getUniform("HighlightColor");

        if (highlight != null)
        {
            int color = BBSSettings.stencilHighlightColor.get();

            highlight.set(Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
        }

        RenderSystem.enableBlend();
        context.batcher.texturedBox(getPickerPreviewProgram, texture.id, Colors.WHITE, area.x, area.y, area.w, area.h, 0, h, w, 0, w, h);

        if (altPressed)
        {
            int selectedReplayIndex = this.getCurrentReplayIndex();
            int stencilIndex = index - REPLAY_STENCIL_OFFSET;

            if (stencilIndex >= 0 && stencilIndex < this.panel.getData().replays.getList().size() && stencilIndex != selectedReplayIndex)
            {
                this.hoveredReplayIndex = stencilIndex;

                String label = this.panel.getData().replays.getList().get(stencilIndex).getName();

                context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
            }
            else if (pair != null && pair.a != null)
            {
                String label = pair.a.getFormIdOrName();

                if (!pair.b.isEmpty())
                {
                    label += " - " + FormUtilsClient.getBoneLabel(pair.a, pair.b);
                }

                context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
            }
        }
        else if (pair != null && pair.a != null)
        {
            String label = pair.a.getFormIdOrName();

            if (!pair.b.isEmpty())
            {
                label += " - " + FormUtilsClient.getBoneLabel(pair.a, pair.b);
            }

            context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
        }

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
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
        ViewRenderState state = this.preview == null ? null : BBSRendering.getViewRenderState(this.preview.getViewDescriptor().getId());
        long sample = state == null ? this.worldContextFrame : state.getSampleFrameId();

        if (sample != this.stencilFrame || this.stencilAlt != altPressed || this.stencilReplay != this.getReplay())
        {
            if (sample != this.worldContextFrame || sample != BBSRendering.getSceneFrameId())
            {
                if (this.preview != null)
                {
                    this.preview.requestRefresh();
                }

                this.stencil.clearPicking();
                this.stencilFrame = -1L;

                return;
            }

            boolean rendered = this.renderStencil(this.worldRenderContext, context, altPressed);

            this.stencilFrame = rendered ? sample : -1L;
            this.stencilAlt = altPressed;
            this.stencilReplay = this.getReplay();

            return;
        }

        this.readViewStencil(context);
    }

    private boolean hasDisplayedStencil(boolean altPressed)
    {
        ViewRenderState state = this.preview == null ? null : BBSRendering.getViewRenderState(this.preview.getViewDescriptor().getId());

        return state != null && state.hasFrame() && this.stencilFrame >= 0L
            && this.stencilFrame == state.getSampleFrameId() && this.stencilAlt == altPressed
            && this.stencilReplay == this.getReplay();
    }

    public boolean isViewportPickReady()
    {
        return this.hasDisplayedStencil(Window.isAltPressed());
    }

    private void prepareViewportPick(UIContext context)
    {
        boolean altPressed = Window.isAltPressed();

        this.hoveredReplayIndex = -1;

        if (!this.hasDisplayedStencil(altPressed))
        {
            this.stencil.clearPicking();
            this.stencilFrame = -1L;
            this.preview.requestRefresh();

            return;
        }

        this.readViewStencil(context);

        if (altPressed && this.stencil.hasPicked() && this.panel.getData() != null)
        {
            int index = this.stencil.getIndex() - REPLAY_STENCIL_OFFSET;

            if (index >= 0 && index < this.panel.getData().replays.getList().size() && index != this.getCurrentReplayIndex())
            {
                this.hoveredReplayIndex = index;
            }
        }
    }

    private void readViewStencil(UIContext context)
    {
        ViewportPickIntent.Input input = this.pendingViewportPick.input();
        int mouseX = input == null ? context.mouseX : input.x();
        int mouseY = input == null ? context.mouseY : input.y();

        if (this.stencil.getFramebuffer() != null)
        {
            Area viewport = this.getViewArea();
            Texture texture = this.stencil.getFramebuffer().getMainTexture();
            int x = (int) ((mouseX - viewport.x) / (float) viewport.w * texture.width);
            int y = (int) ((1F - (mouseY - viewport.y) / (float) viewport.h) * texture.height);
            int radius = Math.round(BBSSettings.gizmoHoverTolerance.get() * texture.width / (float) viewport.w);

            try
            {
                this.stencil.getFramebuffer().bind();
                this.stencil.pick(x, y, radius, Gizmo.STENCIL_MAX);
            }
            finally
            {
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            }
        }
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
                    Replay replay = pinned ? this.pinnedReplay : this.getReplay();
                    Pair<String, Boolean> bone = pinned ? this.pinnedBone : this.getBone();

                    MotionPath.render(context, motionPath, this, replay, bone, replay == null ? 0F : replay.getTick(this.getTick()));
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

        MouseHandler mouse = Minecraft.getInstance().mouseHandler;
        int x = (int) mouse.xpos();
        int y = (int) mouse.ypos();

        if (this.canControl())
        {
            if (this.isMouseLookMode() && ClientNetwork.isIsBBSModOnServer())
            {
                float cursorDeltaX = (x - this.lastMouse.x) / 2F;
                float cursorDeltaY = (y - this.lastMouse.y) / 2F;

                Minecraft.getInstance().player.turn(cursorDeltaX, cursorDeltaY);
            }
            else
            {
                /* Control sticks and triggers variables */
                float sensitivity = 100F;

                float xx = (y - this.lastMouse.y) / sensitivity;
                float yy = (x - this.lastMouse.x) / sensitivity;

                this.mouseStick.add(xx, yy);
                this.mouseStick.x = MathUtils.clamp(this.mouseStick.x, -1F, 1F);
                this.mouseStick.y = MathUtils.clamp(this.mouseStick.y, -1F, 1F);
            }
        }

        this.lastMouse.set(x, y);

        RenderSystem.disableDepthTest();
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
        float distanceScale = BBSSettings.getAxesDistanceScale((float) Math.sqrt(x * x + y * y + z * z));
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

    private boolean canShowGizmo()
    {
        Replay replay = this.getReplay();

        /* A disabled replay is skipped by the render pass, so its gizmo placement
         * stops being captured. Hide the gizmo instead of letting it linger on the
         * last captured (stale) matrix. */
        return this.isViewActive() && UIBaseMenu.shouldRenderAxes() && !this.isRecording()
            && (replay == null || replay.enabled.get())
            && (this.getBone() != null || this.isAnchorGizmo());
    }

    private boolean renderStencil(IBbsWorldRenderContext renderContext, UIContext context, boolean altPressed)
    {
        Area viewport = this.getViewArea();
        ViewportPickIntent.Input input = this.pendingViewportPick.input();
        int mouseX = input == null ? context.mouseX : input.x();
        int mouseY = input == null ? context.mouseY : input.y();

        if (!viewport.isInside(mouseX, mouseY) || this.isControlling())
        {
            this.stencil.clearPicking();

            return false;
        }

        IEntity entity = this.getCurrentEntity();

        if ((entity == null || (this.pov == CAMERA_MODE_FIRST_PERSON && entity == this.getCurrentEntity())) && !altPressed)
        {
            this.stencil.clearPicking();

            return false;
        }

        Replay selectedReplay = this.panel.replayEditor.getReplay();

        if (!altPressed && selectedReplay == null)
        {
            this.stencil.clearPicking();

            return false;
        }

        this.ensureStencilFramebuffer();

        /* Match the visual gizmo's on-screen size compensation (see
         * Gizmo#setViewportScale) so the pick handles line up with what is drawn. */
        Gizmo.INSTANCE.setViewportScale(context.menu.height / (float) viewport.h);

        boolean isPlaying = this.isPlaying();
        Texture mainTexture = this.stencil.getFramebuffer().getMainTexture();
        boolean applied = false;

        context.batcher.flush();

        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissor = new int[4];

        if (scissorEnabled)
        {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        }

        try
        {
            this.stencilMap.setup();
            RenderSystem.disableScissor();
            this.stencil.apply();
            applied = true;

            Gizmo.INSTANCE.setViewport(viewport);

            if (altPressed)
            {
                List<Replay> replays = this.panel.getData().replays.getList();
                int selectedReplayIndex = this.getCurrentReplayIndex();
                Pair<String, Boolean> bone = this.getBone();

                for (Map.Entry<Integer, IEntity> entry : this.getEntities().entrySet())
                {
                    Replay replay = CollectionUtils.getSafe(replays, entry.getKey());

                    if (replay == null)
                    {
                        continue;
                    }

                    FilmControllerContext filmContext = FilmControllerContext.instance
                        .setup(this.getEntities(), entry.getValue(), replay, renderContext)
                        .transition(isPlaying ? renderContext.tickDelta() : 0)
                        .stencil(this.stencilMap)
                        .relative(replay.relative.get());

                    if (entry.getKey() == selectedReplayIndex)
                    {
                        this.stencilMap.objectIndex = replays.size() + REPLAY_STENCIL_OFFSET;
                        this.stencilMap.setIncrement(true);

                        filmContext
                            .bone(bone == null ? null : bone.a, bone != null && bone.b)
                            .gizmoSpace(this.getBoneSpace(), this.getGizmoView())
                            .anchorGizmo(this.isAnchorGizmo(), this.getAnchorLocal());
                    }
                    else
                    {
                        this.stencilMap.objectIndex = entry.getKey() + REPLAY_STENCIL_OFFSET;
                        this.stencilMap.setIncrement(false);
                    }

                    FilmEntityRenderer.renderEntity(filmContext);
                }
            }
            else
            {
                Pair<String, Boolean> bone = this.getBone();

                this.stencilMap.setIncrement(true);

                FilmEntityRenderer.renderEntity(FilmControllerContext.instance
                    .setup(this.getEntities(), entity, selectedReplay, renderContext)
                    .transition(isPlaying ? renderContext.tickDelta() : 0)
                    .stencil(this.stencilMap)
                    .relative(selectedReplay.relative.get())
                    .bone(bone == null ? null : bone.a, bone != null && bone.b)
                    .gizmoSpace(this.getBoneSpace(), this.getGizmoView())
                    .anchorGizmo(this.isAnchorGizmo(), this.getAnchorLocal()));
            }

            int x = (int) ((mouseX - viewport.x) / (float) viewport.w * mainTexture.width);
            int y = (int) ((1F - (mouseY - viewport.y) / (float) viewport.h) * mainTexture.height);
            int radius = Math.round(BBSSettings.gizmoHoverTolerance.get() * mainTexture.width / (float) viewport.w);

            this.stencil.pick(x, y, radius, Gizmo.STENCIL_MAX);

            return true;
        }
        finally
        {
            /* Drain any vertices the form renderers left in the shared buffer source
             * while the stencil FBO is still bound, so they can't leak into a later
             * flush on the main render target. */
            try
            {
                renderContext.consumers().endBatch();
            }
            catch (Exception e)
            {
                LOGGER.warn("renderStencil: endBatch during cleanup failed", e);
            }

            if (applied)
            {
                this.stencil.unbind(this.stencilMap);
            }

            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);

            /* Defensive restore of every global write/test state a form renderer or
             * vanilla RenderType could have left behind inside the picking pass.
             * UI rendering below assumes all of these are at their defaults; a stale
             * colorMask/scissor/shaderColor here blacks out whole UI batches and
             * survives into other screens (observed: leftovers on the title screen). */
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            if (scissorEnabled)
            {
                RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            }
            else
            {
                RenderSystem.disableScissor();
            }
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    private void ensureStencilFramebuffer()
    {
        this.stencil.setup(Link.bbs("stencil_film_" + (this.preview == null ? "preview" : this.preview.getViewDescriptor().getId())));

        Texture mainTexture = this.stencil.getFramebuffer().getMainTexture();
        ViewRenderState state = this.preview == null ? null : BBSRendering.getViewRenderState(this.preview.getViewDescriptor().getId());
        int w = state == null ? BBSRendering.getVideoWidth() : state.getWidth();
        int h = state == null ? BBSRendering.getVideoHeight() : state.getHeight();

        if (mainTexture.width != w || mainTexture.height != h)
        {
            this.stencil.resize(w, h);
        }
    }
}
