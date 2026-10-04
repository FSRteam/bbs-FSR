package mchorse.bbs_mod.ui.model_blocks.camera;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.controller.ICameraController;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Intersectiond;
import org.joml.Matrix3f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * The orbit of the model blocks panel: what it turns around is the selected block's model.
 * It is how that panel is flown at all — there is no flight beside it, so the wheel is always
 * the zoom and WASD always walks the pivot.
 *
 * <p>Nothing is followed: a block stands where it was put, and its transform is what the user
 * is dragging about, so hanging the camera off it would pull the view along with every drag.</p>
 *
 * <p>This is the panel-scoped port of the upstream orbit (827ca6999) onto FSR's
 * {@link ICameraController}. Upstream generalised this mechanic into a shared
 * {@code OrbitViewportController} base (with anchors and axis-snap ortho) during the film
 * editor wave; FSR defers that generalisation to the same wave — this class carries the
 * semantics, and a later batch can refactor it onto the shared base.</p>
 */
public class OrbitModelBlockCameraController implements ICameraController
{
    public static final float PITCH_LIMIT = MathUtils.PI * 0.5F - 0.01F;
    public static final float MIN_DISTANCE = 0.5F;
    public static final float MAX_DISTANCE = 256F;

    /** How far the cursor must travel before a press counts as a drag rather than a click. */
    private static final int DRAG_THRESHOLD = 3;

    private final UIModelBlockPanel panel;

    public boolean enabled;

    private boolean orbiting;
    private int orbitButton = -1;
    private final Vector2i last = new Vector2i();
    private final Vector2i pressOrigin = new Vector2i();
    private boolean dragged;

    /* The state the input drives. */
    private final Vector2f targetRotation = new Vector2f();
    private final Vector3f targetPivot = new Vector3f();
    private float targetDistance;

    /* The state that is rendered, smoothly chasing the target above. */
    private final Vector2f rotation = new Vector2f();
    private final Vector3f pivot = new Vector3f();
    private float distance;

    /** Whether the pivot has been placed onto the subject after a reset. */
    private boolean positioned;

    private final Vector3i velocityPosition = new Vector3i();

    private final PanState panState = new PanState();

    public OrbitModelBlockCameraController(UIModelBlockPanel panel)
    {
        this.panel = panel;

        this.reset();
    }

    /* What the host answers */

    protected UIContext getContext()
    {
        return this.panel.getContext();
    }

    /** The world is drawn across the whole screen here, which is what the gizmo also projects into. */
    protected Area getViewport()
    {
        return this.panel.getGizmoArea();
    }

    protected Camera getViewportCamera()
    {
        return BBSModClient.getCameraController().camera;
    }

    /** How fast WASD walks the pivot; the user's own speed setting. */
    protected float getSpeed()
    {
        return this.panel.dashboard.orbit.getSpeed();
    }

    /**
     * The middle of the block's model: where its transform puts it, raised by half the form's
     * own height. The block's cell says nothing about how tall what stands in it is, and an
     * orbit around the floor of a two-block statue swings it through the frame.
     */
    protected Vector3f getSubjectPivot(float transition)
    {
        ModelBlockEntity block = this.panel.getModelBlock();

        if (block == null)
        {
            return null;
        }

        BlockPos pos = block.getBlockPos();
        ModelProperties properties = block.getProperties();
        Form form = properties.getForm();
        float height = form == null ? 1F : form.hitboxHeight.get() * Math.abs(properties.getTransform().scale.y);

        return new Vector3f(
            pos.getX() + 0.5F + properties.getTransform().translate.x,
            pos.getY() + properties.getTransform().translate.y + height / 2F,
            pos.getZ() + 0.5F + properties.getTransform().translate.z
        );
    }

    protected boolean hasSubject()
    {
        return this.panel.getModelBlock() != null;
    }

    protected boolean canMove()
    {
        return true;
    }

    protected boolean canZoom()
    {
        return true;
    }

    /** Which buttons begin an orbit: the left one turns, the middle one pans. */
    protected boolean canStart(UIContext context)
    {
        return context.mouseButton == 0 || context.mouseButton == 2;
    }

    /* Input */

    public void start(UIContext context)
    {
        if (!this.canStart(context))
        {
            return;
        }

        this.orbitButton = context.mouseButton;
        this.orbiting = true;
        this.dragged = false;
        this.last.set(context.mouseX, context.mouseY);
        this.pressOrigin.set(context.mouseX, context.mouseY);

        if (this.isPanning())
        {
            this.cachePanState(context);
        }
    }

    public boolean wasDragged()
    {
        return this.dragged;
    }

    public void stop()
    {
        this.orbiting = false;
        this.orbitButton = -1;
    }

    public boolean keyPressed(UIContext context, Area area)
    {
        if (!this.enabled || context.isFocused())
        {
            return false;
        }

        if (area.isInside(context) || (!this.velocityPosition.equals(0, 0, 0) && context.getKeyAction() == KeyAction.RELEASED))
        {
            if (!this.canMove())
            {
                return false;
            }

            int x = this.getFactor(context, Keys.FLIGHT_LEFT, Keys.FLIGHT_RIGHT, this.velocityPosition.x);
            int y = this.getFactor(context, Keys.FLIGHT_UP, Keys.FLIGHT_DOWN, this.velocityPosition.y);
            int z = this.getFactor(context, Keys.FLIGHT_FORWARD, Keys.FLIGHT_BACKWARD, this.velocityPosition.z);
            boolean changed = x != this.velocityPosition.x || y != this.velocityPosition.y || z != this.velocityPosition.z;

            this.velocityPosition.set(x, y, z);

            return changed;
        }

        return false;
    }

    protected int getFactor(UIContext context, KeyCombo positive, KeyCombo negative, int x)
    {
        if (context.isPressed(positive.getMainKey()))
        {
            x = 1;
        }
        else if (context.isPressed(negative.getMainKey()))
        {
            x = -1;
        }
        else if ((context.isReleased(positive.getMainKey()) && x > 0) || (context.isReleased(negative.getMainKey()) && x < 0))
        {
            x = 0;
        }

        return x;
    }

    public void handleOrbiting(UIContext context)
    {
        if (!this.orbiting)
        {
            return;
        }

        int x = context.mouseX;
        int y = context.mouseY;
        int dx = x - this.last.x;
        int dy = y - this.last.y;

        if (!this.dragged && (Math.abs(x - this.pressOrigin.x) > DRAG_THRESHOLD || Math.abs(y - this.pressOrigin.y) > DRAG_THRESHOLD))
        {
            this.dragged = true;
        }

        if (this.orbitButton == 2)
        {
            this.pan(context);
        }
        else
        {
            this.rotate(dx, dy);
        }

        this.last.set(x, y);
    }

    public boolean zoom(double mouseWheel)
    {
        if (!this.enabled || !this.canZoom() || mouseWheel == 0D)
        {
            return false;
        }

        float step = Window.isCtrlPressed() ? 0.22F : 0.1F;
        float factor = (float) Math.pow(1F - step, mouseWheel);

        this.targetDistance = MathUtils.clamp(this.targetDistance * factor, MIN_DISTANCE, MAX_DISTANCE);

        return true;
    }

    public boolean update(UIContext context)
    {
        if (!this.enabled)
        {
            return false;
        }

        this.applySmoothing();

        if (context.isFocused())
        {
            return false;
        }

        if (!this.canMove())
        {
            this.velocityPosition.set(0, 0, 0);

            return false;
        }

        if (this.velocityPosition.lengthSquared() > 0)
        {
            Vector3f delta = this.rotateVector(-this.velocityPosition.x, this.velocityPosition.y, -this.velocityPosition.z, this.targetRotation.y, this.targetRotation.x).mul(this.getSpeed());

            this.targetPivot.add(delta);

            return true;
        }

        return false;
    }

    private void applySmoothing()
    {
        float smoothness = BBSSettings.editorCameraSmoothness.get();

        if (smoothness <= 0F)
        {
            this.rotation.set(this.targetRotation);
            this.pivot.set(this.targetPivot);
            this.distance = this.targetDistance;

            return;
        }

        float dt = Minecraft.getInstance().getTimer().getRealtimeDeltaTicks();
        float factor = MathUtils.clamp(1F - (float) Math.pow(Math.min(smoothness, 0.99F), dt), 0F, 1F);

        this.rotation.lerp(this.targetRotation, factor);
        this.pivot.lerp(this.targetPivot, factor);
        this.distance = Lerps.lerp(this.distance, this.targetDistance, factor);
    }

    private Vector3f rotateVector(float x, float y, float z, float yaw, float pitch)
    {
        Matrix3f rotation = new Matrix3f();
        Vector3f rotate = new Vector3f(x, y, z);

        rotation.rotateY(yaw);

        if (!BBSSettings.editorHorizontalFlight.get())
        {
            rotation.rotateX(pitch);
        }

        rotation.transform(rotate);

        return rotate;
    }

    /* Panning */

    private boolean isPanning()
    {
        return this.orbitButton == 2;
    }

    /**
     * Panning casts a ray through the camera, and a ray needs the camera's matrices, not just
     * its position — the world camera of the dashboard is only ever handed a place to stand,
     * so the copy used for the ray gets its matrices built here.
     */
    protected void syncRayMatrices(Camera camera)
    {
        Area viewport = this.getViewport();

        camera.fov = BBSSettings.getFov();
        camera.updatePerspectiveProjection(viewport.w, viewport.h);
        camera.updateView();
    }

    private void cachePanState(UIContext context)
    {
        this.panState.pivot.set(this.pivot);
        this.panState.camera.copy(this.getViewportCamera());
        this.syncRayMatrices(this.panState.camera);
        this.panState.plane.set(this.panState.camera.getLookDirection()).normalize();
        this.panState.intersection.set(this.calculateOnPlane(context));
    }

    private void pan(UIContext context)
    {
        Vector3d point = this.calculateOnPlane(context);
        Vector3f pivot = new Vector3f(this.panState.pivot);

        pivot.sub((float) point.x, (float) point.y, (float) point.z);
        pivot.add((float) this.panState.intersection.x, (float) this.panState.intersection.y, (float) this.panState.intersection.z);

        this.targetPivot.set(pivot);
    }

    private Vector3d calculateOnPlane(UIContext context)
    {
        Area viewport = this.getViewport();
        Vector3d vector = new Vector3d();
        Vector3f originOffset = new Vector3f();
        Vector3f direction = this.panState.camera.getMouseRay(context.mouseX, context.mouseY, viewport.x, viewport.y, viewport.w, viewport.h, originOffset);
        Vector3d origin = new Vector3d(this.panState.camera.position)
            .add(originOffset.x, originOffset.y, originOffset.z)
            .sub(this.panState.pivot.x, this.panState.pivot.y, this.panState.pivot.z);
        Vector3d destination = new Vector3d(direction).mul(Math.max(this.distance, MIN_DISTANCE) * 2F).add(origin);

        Intersectiond.intersectLineSegmentPlane(
            origin.x,
            origin.y,
            origin.z,
            destination.x,
            destination.y,
            destination.z,
            this.panState.plane.x,
            this.panState.plane.y,
            this.panState.plane.z,
            0,
            vector
        );

        return vector;
    }

    /* Turning */

    public void rotate(int dx, int dy)
    {
        if (dx == 0 && dy == 0)
        {
            return;
        }

        float orbitSpeed = this.panel.dashboard.orbit.getAngleSpeed() * 4F;

        this.targetRotation.x = MathUtils.clamp(this.targetRotation.x - dy * orbitSpeed, -PITCH_LIMIT, PITCH_LIMIT);
        this.targetRotation.y -= dx * orbitSpeed;
    }

    /* Where the camera ends up */

    @Override
    public void setup(Camera camera, float transition)
    {
        if (!this.enabled)
        {
            return;
        }

        /* Nobody else sets it while this orbit drives the camera, and the panning ray reads it. */
        camera.fov = BBSSettings.getFov();

        this.applySmoothing();

        if (!this.positioned)
        {
            Vector3f subject = this.getSubjectPivot(transition);

            if (subject != null)
            {
                this.pivot.set(subject);
                this.targetPivot.set(subject);
                this.positioned = true;
            }
            else if (!this.hasSubject())
            {
                this.seedPivotFromCamera(camera);
                this.positioned = true;
            }
        }

        Vector3f offset = this.getOffset();

        camera.position.set(this.pivot);
        camera.position.add(offset.x, offset.y, offset.z);
        camera.rotation.set(-this.rotation.x, -this.rotation.y, 0F);
    }

    @Override
    public int getPriority()
    {
        return 20;
    }

    /** Put the pivot back onto the subject, wherever the camera has wandered to. */
    public void teleportPivotToSubject()
    {
        Vector3f subject = this.getSubjectPivot(this.getCurrentTransition());

        if (subject != null)
        {
            this.targetPivot.set(subject);
            this.positioned = true;
        }
    }

    private Vector3f getOffset()
    {
        return this.rotateVector(0F, 0F, 1F, this.rotation.y, this.rotation.x, false).mul(this.distance);
    }

    private Vector3f rotateVector(float x, float y, float z, float yaw, float pitch, boolean horizontal)
    {
        Matrix3f rotation = new Matrix3f();
        Vector3f rotate = new Vector3f(x, y, z);

        rotation.rotateY(yaw);

        if (!horizontal)
        {
            rotation.rotateX(pitch);
        }

        rotation.transform(rotate);

        return rotate;
    }

    /**
     * Place the orbit center in front of the given camera, keeping its position and rotation.
     * Used when there is nothing to focus on — leaving the pivot at the world origin could put
     * it nowhere near the view.
     */
    private void seedPivotFromCamera(Camera camera)
    {
        this.targetRotation.set(-camera.rotation.x, -camera.rotation.y);
        this.rotation.set(this.targetRotation);

        Vector3f pivot = new Vector3f((float) camera.position.x, (float) camera.position.y, (float) camera.position.z);

        pivot.sub(this.getOffset());

        this.pivot.set(pivot);
        this.targetPivot.set(this.pivot);
    }

    protected float getCurrentTransition()
    {
        UIContext context = this.getContext();

        return context == null ? 0F : context.getTransition();
    }

    public void reset()
    {
        this.pivot.set(0F, 0F, 0F);
        this.targetPivot.set(0F, 0F, 0F);
        this.rotation.set(0F, MathUtils.PI);
        this.targetRotation.set(0F, MathUtils.PI);
        this.distance = 4F;
        this.targetDistance = 4F;
        this.positioned = false;
        this.orbiting = false;
        this.orbitButton = -1;
        this.velocityPosition.set(0, 0, 0);
    }

    private static class PanState
    {
        private final Vector3f pivot = new Vector3f();
        private final Camera camera = new Camera();
        private final Vector3d plane = new Vector3d();
        private final Vector3d intersection = new Vector3d();
    }
}
