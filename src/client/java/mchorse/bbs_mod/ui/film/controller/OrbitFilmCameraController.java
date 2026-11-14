package mchorse.bbs_mod.ui.film.controller;

import java.util.Map;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.MathUtils;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.FormFrameCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.camera.OrbitViewportController;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.interps.Lerps;

/**
 * The film's orbit: what it turns around is the selected replay, and — while attached — the
 * orbit hangs off that replay's own frame, so the camera rides along as the actor moves and
 * turns. Everything about flying the viewport itself lives in {@link OrbitViewportController}.
 */
public class OrbitFilmCameraController extends OrbitViewportController
{
    private final UIFilmController controller;

    /*
     * When attached, pivot and rotation are stored relative to the selected
     * replay's anchor (where the replay is and which way it faces, its own
     * transform included), and world values are composed on the fly. Detached,
     * the anchor is identity, so the same math passes world values through
     * untouched. Rebasing between anchors preserves the world state, so
     * attaching, detaching and switching replays never moves the camera.
     */
    private boolean attached = true;
    private Replay anchorReplay;

    public OrbitFilmCameraController(UIFilmController controller)
    {
        super();

        this.controller = controller;
    }

    /* What the viewport is */

    @Override
    protected UIContext getContext()
    {
        return this.controller.getContext();
    }

    @Override
    protected Area getViewport()
    {
        return this.controller.getViewArea();
    }

    @Override
    protected Camera getViewportCamera()
    {
        return this.controller.getViewCamera();
    }

    @Override
    protected float getSpeed()
    {
        return this.controller.panel.dashboard.orbit.getSpeed();
    }

    /**
     * Flight moves the camera itself, so WASD walks the pivot only when it is off - and by
     * default not even then: walking the viewport is what flight is for, and the setting keeps
     * WASD out of the orbit until it is asked for.
     */
    @Override
    protected boolean canMove()
    {
        return !this.controller.isViewFlying() && !BBSSettings.editorOrbitMovementRequiresFlight.get();
    }

    /** While flying, the wheel is the flight camera's speed dial, not the orbit's zoom. */
    @Override
    protected boolean canZoom()
    {
        return !this.controller.isViewFlying();
    }

    /** While flying, every button belongs to the flight camera: free look, roll and FOV. */
    @Override
    protected boolean canStart(UIContext context)
    {
        return !this.controller.isViewFlying() && super.canStart(context);
    }

    /* The replay it turns around */

    @Override
    protected Vector3d getSubjectPivot(float transition)
    {
        return this.getOrbitTarget(transition);
    }

    @Override
    protected boolean hasSubject()
    {
        return this.controller.panel.getData() != null && !this.controller.panel.getData().replays.getList().isEmpty();
    }

    /** Kept for the call sites that speak of replays; the orbit itself only knows subjects. */
    public void teleportPivotToReplay()
    {
        this.teleportPivotToSubject();
    }

    /* The replay it hangs off */

    public boolean isAttached()
    {
        return this.attached;
    }

    public void toggleAttachment()
    {
        this.attached = !this.attached;

        this.updateAnchor(this.getCurrentTransition());
    }

    @Override
    protected void updateAnchor(float transition)
    {
        Replay target = null;
        IEntity entity = null;

        if (this.attached)
        {
            target = this.controller.panel.replayEditor.getReplay();
            entity = target == null ? null : this.resolveEntity(target);

            if (entity == null)
            {
                target = this.anchorReplay;
                entity = target == null ? null : this.resolveEntity(target);
            }

            if (entity == null)
            {
                target = null;
            }
        }

        if (target != this.anchorReplay)
        {
            this.rebase(target, entity, transition);
        }
        else if (entity != null)
        {
            this.writeAnchor(entity, transition);
        }
    }

    private void rebase(Replay replay, IEntity entity, float transition)
    {
        this.rebaseAnchor(() ->
        {
            this.anchorReplay = replay;

            if (entity == null)
            {
                this.anchorPosition.set(0D, 0D, 0D);
                this.anchorYaw = 0F;
            }
            else
            {
                this.writeAnchor(entity, transition);
            }
        });
    }

    /**
     * The frame the camera hangs off: where the replay stands and which way it faces, its own
     * transform folded in - so a form moved or turned by its transform (keyframed or not)
     * carries the camera along instead of sliding out from under it.
     *
     * <p>Only the turn about the vertical is taken. The pivot and the rotation are stored in
     * this frame and the camera has no roll of its own, so a form tipped on its side would tip
     * the horizon with it; following the yaw is what "the camera stays with the actor" means,
     * and the horizon staying level is what every other frame of this editor promises.</p>
     */
    private void writeAnchor(IEntity entity, float transition)
    {
        double x = Lerps.lerp(entity.getPrevX(), entity.getX(), transition);
        double y = Lerps.lerp(entity.getPrevY(), entity.getY(), transition);
        double z = Lerps.lerp(entity.getPrevZ(), entity.getZ(), transition);
        Matrix4f matrix = FilmMatrices.getMatrixForRenderWithRotation(entity, x, y, z, transition);
        FormRenderer<?> renderer = FormUtilsClient.getRenderer(entity.getForm());

        if (renderer != null)
        {
            matrix.mul(renderer.createTransform().createMatrix());
        }

        Vector3f translation = matrix.getTranslation(new Vector3f());
        Matrix4f axes = MatrixStackUtils.stripScale(matrix);

        /* Compose only the small model offset in floats; the world origin stays double. */
        this.anchorPosition.set(x + translation.x, y + translation.y, z + translation.z);
        this.anchorYaw = (float) Math.atan2(-axes.m02(), axes.m00());
    }

    private IEntity resolveEntity(Replay replay)
    {
        /* Addressed by the replay's stable id, not by its position: the entity map is keyed by id
         * since identity moved off the list index, and a positional lookup would silently miss. */
        if (replay == null || this.controller.panel.getData() == null)
        {
            return null;
        }

        return this.controller.getEntities().get(replay.getId());
    }

    @Override
    public void reset()
    {
        super.reset();

        this.anchorReplay = null;
    }

    /**
     * Where the replay's model actually is: the middle of the pose it is standing in, carried
     * into the world by the replay's own frame (so a form hung off another replay is found
     * where it is drawn).
     *
     * <p>The middle is read from the frames themselves and from nothing else - not from the
     * picking hitbox, which is a separate number the author sets by hand and which says nothing
     * about a form that never declared one, and not from the anchor bone, whose pivot sits
     * wherever the model editor left it (for a rig anchored at the waist, already half way up).
     * The pose is the one thing that always describes the form as it is right now.</p>
     */
    private Vector3d getOrbitTarget(float transition)
    {
        IEntity entity = this.controller.getCurrentEntity();

        if (entity == null)
        {
            return null;
        }

        Form form = entity.getForm();
        double x = Lerps.lerp(entity.getPrevX(), entity.getX(), transition);
        double y = Lerps.lerp(entity.getPrevY(), entity.getY(), transition);
        double z = Lerps.lerp(entity.getPrevZ(), entity.getZ(), transition);
        Replay replay = this.controller.panel.replayEditor.getReplay();
        boolean relative = replay != null && replay.relative.get();

        if (form != null && !relative && !FilmMatrices.isRelativeReplayEntity(entity))
        {
            FormRenderer<?> renderer = FormUtilsClient.getRenderer(form);

            if (renderer != null)
            {
                Anchor anchor = form.anchor.get();
                FormFrameCache frame = new FormFrameCache();
                Matrix4f defaultMatrix = FilmMatrices.getMatrixForRenderWithRotation(entity, x, y, z, transition);
                Pair<Matrix4f, Float> totalMatrix = FilmMatrices.getTotalMatrix(this.controller.getEntities(), anchor,
                    defaultMatrix, x, y, z, transition, 0, false, frame);

                if (totalMatrix.a != null)
                {
                    defaultMatrix = totalMatrix.a;
                }

                Matrix4f semanticBase = new Matrix4f().translation((float) x, (float) y, (float) z).mul(defaultMatrix);
                MatrixCache map = FormFrameCache.collect(frame, form, entity, entity, semanticBase, true, true, transition);
                Vector3f center = this.getPoseCenter(map);

                if (center != null)
                {
                    defaultMatrix.transformPosition(center);
                    x += center.x;
                    y += center.y;
                    z += center.z;
                }
            }
        }

        return new Vector3d(x, y, z);
    }

    private Vector3f getPoseCenter(MatrixCache map)
    {
        Vector3f min = null;
        Vector3f max = null;

        for (Map.Entry<String, MatrixCacheEntry> entry : map.entrySet())
        {
            Matrix4f matrix = entry.getValue().matrix();

            if (matrix == null || entry.getKey().isEmpty())
            {
                continue;
            }

            Vector3f point = matrix.getTranslation(new Vector3f());

            if (min == null)
            {
                min = new Vector3f(point);
                max = new Vector3f(point);
            }
            else
            {
                min.min(point);
                max.max(point);
            }
        }

        if (min == null)
        {
            Matrix4f root = map.get("").matrix();

            return root == null ? null : root.getTranslation(new Vector3f());
        }

        return min.add(max).mul(0.5F);
    }

    @Override
    protected void setOrthoDistance(float distance)
    {
        this.controller.setViewOrthoDistance(distance);
    }

    public MapType toData()
    {
        MapType data = new MapType();
        Vector3d worldPivot = this.toWorld(new Vector3d(this.targetPivot));

        data.putDouble("x", worldPivot.x);
        data.putDouble("y", worldPivot.y);
        data.putDouble("z", worldPivot.z);
        data.putFloat("pitch", this.targetRotation.x);
        data.putFloat("yaw", this.targetRotation.y + this.anchorYaw);
        data.putFloat("distance", this.targetDistance);
        data.putBool("positioned", this.positioned);
        data.putBool("attached", this.attached);
        data.putBool("ortho", this.ortho);
        data.putBool("auto_ortho", this.autoOrtho);

        return data;
    }

    public void fromData(MapType data)
    {
        this.reset();
        this.attached = data.getBool("attached", true);
        this.ortho = data.getBool("ortho");
        this.autoOrtho = this.ortho && data.getBool("auto_ortho");

        double x = data.getDouble("x");
        double y = data.getDouble("y");
        double z = data.getDouble("z");
        float pitch = data.getFloat("pitch");
        float yaw = data.getFloat("yaw", MathUtils.PI);
        float distance = data.getFloat("distance", 4F);

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Float.isFinite(pitch) || !Float.isFinite(yaw) || !Float.isFinite(distance))
        {
            return;
        }

        /* Save in world space, then let updateAnchor rebase onto the selected actor. */
        this.pivot.set(x, y, z);
        this.targetPivot.set(this.pivot);
        this.rotation.set(MathUtils.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT), yaw);
        this.targetRotation.set(this.rotation);
        this.distance = MathUtils.clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
        this.targetDistance = this.distance;
        this.positioned = data.getBool("positioned") && data.has("x") && data.has("y") && data.has("z");
    }
}
