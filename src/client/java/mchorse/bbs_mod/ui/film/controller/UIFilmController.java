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
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.Recorder;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtilsClient;
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
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.film.replays.UIRecordOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
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
    private int pov;
    private boolean paused;

    private IBbsWorldRenderContext worldRenderContext;

    public UIFilmController(UIFilmPanel panel)
    {
        this.panel = panel;
        this.setPov(BBSSettings.editorCameraMode.get());

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
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_ORBIT_MODE, this::toggleOrbitMode).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TELEPORT_ORBIT, this::teleportOrbitPivotToReplay).strict().active(() -> this.getPovMode() == CAMERA_MODE_ORBIT).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_ATTACH_ORBIT, () ->
        {
            this.toggleOrbitAttachment();
            UIUtils.playClick();
        }).strict().active(() -> this.getPovMode() == CAMERA_MODE_ORBIT).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_REPLAY_MENU, this::toggleReplayMenu).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_MOVE_REPLAY_TO_CURSOR, () ->
        {
            Area area = this.panel.preview.getViewport();
            UIContext context = this.getContext();
            Level world = Minecraft.getInstance().level;
            Camera camera = this.panel.getCamera();

            HitResult result = RayTracing.rayTrace(
                world,
                RayTracing.fromVector3d(camera.position),
                RayTracing.fromVector3f(camera.getMouseDirection(context.mouseX, context.mouseY, area.x, area.y, area.w, area.h)),
                512F
            );

            if (result.getType() == HitResult.Type.BLOCK)
            {
                this.panel.replayEditor.moveReplay(result.getLocation().x, result.getLocation().y, result.getLocation().z);
            }
        }).active(hasActor).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_RESTART_ACTIONS, () ->
        {
            this.panel.notifyServer(ActionState.RESTART);
            this.createEntities();
        }).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_ONION_SKIN, () ->
        {
            this.getOnionSkin().enabled.toggle();

            UIUtils.playClick();
        }).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_MOTION_PATH, () ->
        {
            this.getMotionPath().enabled.toggle();
            UIUtils.playClick();
        }).strict().active(() -> !this.panel.hasSelectedClip()).category(category);
        this.keys().register(Keys.FILM_CONTROLLER_TOGGLE_MOTION_PATH_PIN, () ->
        {
            this.toggleMotionPathPin();
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
        return this.instantKeyframes;
    }

    public void toggleInstantKeyframes()
    {
        this.instantKeyframes = !this.instantKeyframes;
    }

    public boolean isPaused()
    {
        return this.paused;
    }

    public void setPaused(boolean paused)
    {
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
        return BBSSettings.editorOnionSkin;
    }

    public ValueMotionPath getMotionPath()
    {
        return BBSSettings.editorMotionPath;
    }

    private Replay pinnedReplay;
    private Pair<String, Boolean> pinnedBone;

    public boolean isMotionPathPinned()
    {
        if (this.pinnedReplay != null && this.panel.getData() != null && !this.panel.getData().replays.getList().contains(this.pinnedReplay))
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

        this.pov = mode;
        this.orbit.enabled = mode > CAMERA_MODE_FREE;

        BBSSettings.editorCameraMode.set(mode);
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
        this.stopRecording();

        if (this.controlled != null)
        {
            this.toggleControl();
        }

        this.editorController = new FilmEditorController(this.panel.getData(), this);
        this.editorController.createEntities();

        IntObjectMap<IEntity> entities = this.panel.getRunner().getContext().entities;

        entities.clear();
        entities.putAll(this.editorController.getEntities());
    }

    public IntObjectMap<IEntity> getEntities()
    {
        return this.editorController == null ? new IntObjectHashMap<>() : this.editorController.getEntities();
    }

    public Map<String, Integer> getActors()
    {
        return this.actors;
    }

    public void updateActors(Map<String, Integer> actors)
    {
        this.actors = actors;
    }

    /* Character control state */

    public IEntity getControlled()
    {
        return this.controlled;
    }

    public boolean isControlling()
    {
        return this.controlled != null;
    }

    public void toggleControl()
    {
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
        return this.recording;
    }

    public int getRecordingCountdown()
    {
        return this.recordingCountdown;
    }

    public List<String> getRecordingGroups()
    {
        return this.recordingGroups;
    }

    private boolean hasTransformRecordingGroup()
    {
        return this.recordingGroups != null && this.recordingGroups.contains(ReplayKeyframes.GROUP_TRANSFORM);
    }

    public boolean isTransformRecording()
    {
        return this.recording
            && this.recordingCountdown <= 0
            && this.hasTransformRecordingGroup();
    }

    public void startRecording(List<String> groups)
    {
        if (groups != null && groups.contains("outside"))
        {
            Minecraft.getInstance().setScreen(null);

            Replay replay = this.panel.replayEditor.getReplay();
            int index = CollectionUtils.getIndex(this.panel.getData().replays.getList(), replay);

            if (index >= 0)
            {
                BBSModClient.getFilms().startRecording(this.panel.getData(), index, this.panel.getCursor());
            }

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
    }

    public void stopRecording()
    {
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
        if (this.canControl())
        {
            return true;
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
        return this.canShowGizmo() && this.gizmo.mouseClickedHandle(context);
    }

    @Override
    public StencilFormFramebuffer getGizmoStencil()
    {
        return this.stencil;
    }

    @Override
    public Matrix4f getGizmoProjection()
    {
        return this.panel.lastProjection;
    }

    @Override
    public Area getGizmoArea()
    {
        return this.panel.preview.getViewport();
    }

    @Override
    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        float gizmoTransition = this.isPlaying() ? context.getTransition() : 0F;

        return UIReplaysEditorUtils.startFilmGizmo(this.panel, context, stencilIndex, gizmoTransition);
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

    public void resetOrbit()
    {
        long generation = this.orbit.gestureGeneration();

        this.orbit.reset();

        if (generation != 0L && this.panel.replayEditor != null)
        {
            this.panel.replayEditor.cancelViewportPick(generation);
        }
    }

    private void cancelOrbitGesture()
    {
        long generation = this.orbit.gestureGeneration();

        this.orbit.stop();

        if (generation != 0L && this.panel.replayEditor != null)
        {
            this.panel.replayEditor.cancelViewportPick(generation);
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
        boolean orbitDragged = this.orbit.wasDragged();
        long dashboardOrbitGeneration = this.panel.isFlying() && context.mouseButton == 2
            ? this.panel.dashboard.orbitUI.gestureGeneration()
            : 0L;
        boolean consumed = false;
        boolean orbitReleased = false;
        boolean inherited = false;
        Throwable failure = null;

        try
        {
            consumed = this.gizmo.mouseReleased(context);
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
                this.panel.replayEditor.releaseViewport(context, orbitDragged, orbitGeneration);
            }
        }
        catch (RuntimeException | Error exception)
        {
            failure = mergeInputFailure(failure, exception);
        }

        try
        {
            if (this.panel.isFlying() && context.mouseButton == 2)
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
        long gizmoGeneration = this.gizmo.gestureGeneration();
        long orbitGeneration = this.orbit.gestureGeneration();
        long dashboardOrbitGeneration = this.panel.isFlying() && context.mouseButton == 2
            ? this.panel.dashboard.orbitUI.gestureGeneration()
            : 0L;

        if (this.orbit.stop(context.mouseButton, orbitGeneration)
            && this.panel.replayEditor != null)
        {
            this.panel.replayEditor.cancelViewportPick(orbitGeneration);
        }

        if (this.panel.isFlying() && context.mouseButton == 2)
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
        if (this.controlled != null)
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
        if (this.orbit.enabled)
        {
            int mode = this.getPovMode();

            if (mode == CAMERA_MODE_ORBIT)
            {
                this.orbit.setup(camera, transition);

                if (!this.panel.isFlying())
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
            camera.fov = BBSSettings.getFov();

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
        camera.fov = BBSSettings.getFov();
    }

    public void insertFrame()
    {
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
            UIReplaysEditorUtils.insertPoseKeyframesAtTick(replay, this.getTick());
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
                    this.panel.togglePlayback();
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
        FontRenderer font = context.batcher.getFont();
        int mode = this.getMouseMode();

        if (this.controlled != null)
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

                int bx = area.x + area.w / 2 + (int) ((this.mouseStick.y) * ww / 2);
                int by = area.y + area.h / 2 + (int) ((this.mouseStick.x) * hh / 2);

                context.batcher.box(bx - 4, by - 4, bx + 4, by + 4, color);
            }

            /* Render recording overlay */
            if (this.recording)
            {
                int x = area.x + 5 + 16;
                int y = area.y + 5;

                context.batcher.icon(Icons.SPHERE, Colors.RED | Colors.A100, x, y, 1F, 0F);

                if (this.recordingCountdown <= 0)
                {
                    context.batcher.textCard(UIKeys.FILM_CONTROLLER_TICKS.format(this.getTick()).get(), x + 3, y + 4, Colors.WHITE, Colors.A50);
                }
                else
                {
                    context.batcher.textCard(String.valueOf(this.recordingCountdown / 20F), x + 3, y + 4, Colors.WHITE, Colors.A50);
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

        if (this.panel.isFlying())
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

        this.orbit.handleOrbiting(context);
    }

    private void renderPickingPreview(UIContext context, Area area)
    {
        if (this.panel.isFlying() || this.worldRenderContext == null)
        {
            return;
        }

        boolean altPressed = Window.isAltPressed();

        context.batcher.flush();
        RenderSystem.depthFunc(GL11.GL_LESS);

        /* Cache the global stuff */
        MatrixStackUtils.cacheMatrices();
        Matrix3f previousInverseView = new Matrix3f(InverseView.get());

        try
        {
            RenderSystem.setProjectionMatrix(this.panel.lastProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            InverseView.set(new Matrix3f(this.panel.lastView).invert());

            /* Render the stencil */
            PoseStack worldStack = this.worldRenderContext.matrixStack();

            worldStack.pushPose();

            try
            {
                worldStack.setIdentity();
                MatrixStackUtils.multiply(worldStack, this.panel.lastView);
                this.renderStencil(this.worldRenderContext, this.getContext(), altPressed);
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
        if (this.editorController != null)
        {
            this.editorController.startRenderFrame(tickDelta);
        }
    }

    public void renderFrame(IBbsWorldRenderContext context)
    {
        this.worldRenderContext = context;

        RenderSystem.enableDepthTest();

        if (this.editorController != null)
        {
            this.editorController.render(context);

            int povMode = this.panel.getController().getPovMode();

            if (povMode != UIFilmController.CAMERA_MODE_CAMERA && BBSSettings.recordingCameraPreview.get())
            {
                Recorder.renderCameraPreview(this.panel.getRunner().getPosition(), context.camera(), context.matrixStack());
            }
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
        return UIBaseMenu.shouldRenderAxes() && !this.isRecording() && (this.getBone() != null || this.isAnchorGizmo());
    }

    private void renderStencil(IBbsWorldRenderContext renderContext, UIContext context, boolean altPressed)
    {
        Area viewport = this.panel.preview.getViewport();

        if (!viewport.isInside(context) || this.controlled != null)
        {
            this.stencil.clearPicking();

            return;
        }

        IEntity entity = this.getCurrentEntity();

        if ((entity == null || (this.pov == CAMERA_MODE_FIRST_PERSON && entity == this.getCurrentEntity())) && !altPressed)
        {
            this.stencil.clearPicking();

            return;
        }

        Replay selectedReplay = this.panel.replayEditor.getReplay();

        if (!altPressed && selectedReplay == null)
        {
            this.stencil.clearPicking();

            return;
        }

        this.ensureStencilFramebuffer();

        /* Match the visual gizmo's on-screen size compensation (see
         * Gizmo#setViewportScale) so the pick handles line up with what is drawn. */
        Gizmo.INSTANCE.setViewportScale(context.menu.height / (float) viewport.h);

        boolean isPlaying = this.isPlaying();
        Texture mainTexture = this.stencil.getFramebuffer().getMainTexture();
        boolean applied = false;

        try
        {
            this.stencilMap.setup();
            context.batcher.flush();
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
                            .anchorGizmo(this.isAnchorGizmo(), this.getAnchorLocal());
                    }
                    else
                    {
                        this.stencilMap.objectIndex = entry.getKey() + REPLAY_STENCIL_OFFSET;
                        this.stencilMap.setIncrement(false);
                    }

                    BaseFilmController.renderEntity(filmContext);
                }
            }
            else
            {
                Pair<String, Boolean> bone = this.getBone();

                this.stencilMap.setIncrement(true);

                BaseFilmController.renderEntity(FilmControllerContext.instance
                    .setup(this.getEntities(), entity, selectedReplay, renderContext)
                    .transition(isPlaying ? renderContext.tickDelta() : 0)
                    .stencil(this.stencilMap)
                    .relative(selectedReplay.relative.get())
                    .bone(bone == null ? null : bone.a, bone != null && bone.b)
                    .anchorGizmo(this.isAnchorGizmo(), this.getAnchorLocal()));
            }

            int x = (int) ((context.mouseX - viewport.x) / (float) viewport.w * mainTexture.width);
            int y = (int) ((1F - (context.mouseY - viewport.y) / (float) viewport.h) * mainTexture.height);
            int radius = Math.round(BBSSettings.gizmoHoverTolerance.get() * mainTexture.width / (float) viewport.w);

            this.stencil.pick(x, y, radius, Gizmo.STENCIL_MAX);
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
            GlStateManager._disableScissorTest();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    private void ensureStencilFramebuffer()
    {
        this.stencil.setup(Link.bbs("stencil_film"));

        Texture mainTexture = this.stencil.getFramebuffer().getMainTexture();
        int w = BBSRendering.getVideoWidth();
        int h = BBSRendering.getVideoHeight();

        if (mainTexture.width != w || mainTexture.height != h)
        {
            this.stencil.resizeGUI(w, h);
        }
    }
}
