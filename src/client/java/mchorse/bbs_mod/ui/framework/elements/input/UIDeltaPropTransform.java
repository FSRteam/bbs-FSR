package mchorse.bbs_mod.ui.framework.elements.input;

import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Transform editor that applies every edit as a per-channel delta to a whole
 * selection of transforms, instead of overwriting them. The primary &mdash; the
 * transform whose values the fields mirror, {@link #getTargetTransform()} &mdash;
 * defines the change, and the same change is fanned out onto every selected
 * target through {@link #applyToSelection(Consumer)}. With a single target a
 * delta from its own value is exactly an absolute set, so the single-selection
 * case needs no special handling.
 *
 * <p>Pasting is the one absolute operation: it puts the pasted value on every
 * target rather than nudging each by a delta.
 */
public abstract class UIDeltaPropTransform extends UIPropTransform
{
    /** Apply the edit to every transform in the current selection. */
    protected abstract void applyToSelection(Consumer<Transform> consumer);

    /** The transform the fields mirror and deltas are measured against. */
    protected Transform getTargetTransform()
    {
        return this.getTransform();
    }

    /** Route a write to wherever the edit should land (the whole selection by default). */
    protected void applyToTarget(Consumer<Transform> consumer)
    {
        this.applyToSelection(consumer);
    }

    /** Re-read the fields from the primary target after a write. */
    protected void syncTargetTransform()
    {
        Transform transform = this.getTargetTransform();

        if (transform != null)
        {
            this.setTransform(transform);
        }
    }

    @Override
    public void setT(Axis axis, double x, double y, double z)
    {
        Transform transform = this.getTargetTransform();

        if (transform == null)
        {
            return;
        }

        float dx = (float) (x - transform.translate.x);
        float dy = (float) (y - transform.translate.y);
        float dz = (float) (z - transform.translate.z);

        this.preCallback();
        this.applyToTarget((t) ->
        {
            t.translate.x += dx;
            t.translate.y += dy;
            t.translate.z += dz;
        });
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void setS(Axis axis, double x, double y, double z)
    {
        Transform transform = this.getTargetTransform();

        if (transform == null)
        {
            return;
        }

        float dx = (float) (x - transform.scale.x);
        float dy = (float) (y - transform.scale.y);
        float dz = (float) (z - transform.scale.z);

        this.preCallback();
        this.applyToTarget((t) ->
        {
            t.scale.x += dx;
            t.scale.y += dy;
            t.scale.z += dz;
        });
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void setR(Axis axis, double x, double y, double z)
    {
        Transform transform = this.getTargetTransform();

        if (transform == null)
        {
            return;
        }

        float dx = MathUtils.toRad((float) x) - transform.rotate.x;
        float dy = MathUtils.toRad((float) y) - transform.rotate.y;
        float dz = MathUtils.toRad((float) z) - transform.rotate.z;

        this.preCallback();
        this.applyToTarget((t) ->
        {
            /* The fan-out is deliberately per-channel (it composes with the
             * mirror/invert involutions of the pose editor). A quaternion bone
             * has no live channels, so the same channel walk runs on its
             * decomposed angles and recomposes — without this the delta lands
             * on the stale euler and the bone silently never moves. */
            if (t.rotationMode == Transform.RotationMode.QUATERNION)
            {
                Vector3f euler = Matrices.toEulerZYXRadians(t.quat, new Vector3f());

                t.quat.set(Matrices.toQuaternionZYXRadians(euler.x + dx, euler.y + dy, euler.z + dz));
            }
            else
            {
                t.rotate.x += dx;
                t.rotate.y += dy;
                t.rotate.z += dz;
            }
        });
        this.postCallback();

        this.syncTargetTransform();
    }

    /**
     * The rotation-mode flip lands on every transform of the selection: mode is
     * a property of the whole track/bone, so all selected keyframes (and the
     * pose editor's mirror partners) convert together instead of leaving the
     * rest behind in euler. Conversion is per-target — each transform folds its
     * OWN rotation into the new storage, nothing is overwritten.
     */
    @Override
    protected void applyRotationMode(boolean quaternion)
    {
        this.applyToTarget((t) ->
        {
            if (quaternion)
            {
                t.setModeQuaternion();
            }
            else
            {
                t.setModeEuler();
            }
        });
    }

    @Override
    public void setRQuat(Quaternionf quat)
    {
        Transform transform = this.getTargetTransform();

        if (transform == null)
        {
            return;
        }

        /* The gizmo hands an absolute rotation for the primary; turn it into a
         * world delta and fan that onto every selected bone, so each keeps its
         * own pose. Quaternion bones store the composed quaternion; euler bones
         * decompose it back so a mixed selection keeps each bone's mode. */
        Quaternionf delta = new Quaternionf(quat).mul(transform.createRotation().invert());

        this.preCallback();
        this.applyToTarget((t) ->
        {
            Quaternionf result = new Quaternionf(delta).mul(t.createRotation());

            if (t.rotationMode == Transform.RotationMode.QUATERNION)
            {
                t.quat.set(result);
            }
            else
            {
                /* Nearest-to-live branch: the delta is incremental (built against
                 * the current pose each frame), so the live channels are the
                 * continuity anchor here. */
                Matrices.toCompatibleEulerZYXRadians(result, t.rotate, t.rotate);
            }
        });
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void pasteTranslation(Vector3d translation)
    {
        this.preCallback();
        this.applyToTarget((transform) -> transform.translate.set(translation));
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void pasteScale(Vector3d scale)
    {
        this.preCallback();
        this.applyToTarget((transform) -> transform.scale.set(scale));
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void pasteRotation(Vector3d rotation)
    {
        this.preCallback();
        this.applyToTarget((transform) ->
        {
            transform.rotate.set(Vectors.toRad(rotation));

            /* Paste is absolute: a quaternion bone renders from its quat, so the
             * pasted angles must land there too (the channel write above only
             * keeps the stale euler in sync as the nearest-branch anchor). */
            if (transform.rotationMode == Transform.RotationMode.QUATERNION)
            {
                transform.quat.set(Matrices.toQuaternionZYXRadians(transform.rotate.x, transform.rotate.y, transform.rotate.z));
            }
        });
        this.postCallback();

        this.syncTargetTransform();
    }
}
