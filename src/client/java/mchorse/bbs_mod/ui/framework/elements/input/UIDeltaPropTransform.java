package mchorse.bbs_mod.ui.framework.elements.input;

import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Vector3d;

import java.util.function.Consumer;

/**
 * Transform editor that applies every edit as a per-channel delta to a whole
 * selection of transforms, instead of overwriting them. The primary, whose
 * values the fields mirror, defines the change, and the same change is fanned
 * out onto every selected target through {@link #applyToSelection(Consumer)}.
 *
 * <p>Pasting is the one absolute operation: it puts the pasted value on every
 * target rather than nudging each by a delta.</p>
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
            t.rotate.x += dx;
            t.rotate.y += dy;
            t.rotate.z += dz;
        });
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void setR2(Axis axis, double x, double y, double z)
    {
        Transform transform = this.getTargetTransform();

        if (transform == null)
        {
            return;
        }

        float dx = MathUtils.toRad((float) x) - transform.rotate2.x;
        float dy = MathUtils.toRad((float) y) - transform.rotate2.y;
        float dz = MathUtils.toRad((float) z) - transform.rotate2.z;

        this.preCallback();
        this.applyToTarget((t) ->
        {
            t.rotate2.x += dx;
            t.rotate2.y += dy;
            t.rotate2.z += dz;
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
        this.applyToTarget((transform) -> transform.rotate.set(Vectors.toRad(rotation)));
        this.postCallback();

        this.syncTargetTransform();
    }

    @Override
    public void pasteRotation2(Vector3d rotation)
    {
        this.preCallback();
        this.applyToTarget((transform) -> transform.rotate2.set(Vectors.toRad(rotation)));
        this.postCallback();

        this.syncTargetTransform();
    }
}
