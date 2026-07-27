package mchorse.bbs_mod.forms.forms.sound;

import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Vector3f;

/**
 * A sound emitted in a cone, with the apex at this form's position.
 *
 * <p>The apex is the emission point and {@link #range} is the cone's height,
 * which is also the distance at which the sound goes silent — see
 * {@link #getMaxDistance()}. OpenAL's own cone model could not express this,
 * since its cone extends infinitely along the axis; the directivity here is
 * computed by {@link SoundAcoustics#coneGain} instead.</p>
 *
 * <p>Field names and ranges follow the reference light addon's spotlight, so
 * anyone who has used it finds the same knobs. Like it, there is no direction
 * field: the axis comes from the form's own {@code transform} rotation, which
 * also means the existing gizmo aims it for free.</p>
 */
public class SoundConeForm extends AbstractSoundForm
{
    /** Cone height, i.e. how far the sound carries along the axis. */
    public final ValueFloat range = new ValueFloat("range", 12F, 0.1F, 128F);
    /** Full outer cone angle, in degrees. */
    public final ValueFloat outerAngle = new ValueFloat("radius", 35F, 1F, 179F);
    /** Full inner cone angle, in degrees; clamped to never exceed the outer one. */
    public final ValueFloat innerAngle = new ValueFloat("inner_radius", 25F, 1F, 179F);
    /** Gain applied outside the outer cone. */
    public final ValueFloat outerGain = new ValueFloat("outer_gain", 0.2F, 0F, 1F);

    private final Vector3f axis = new Vector3f();

    public SoundConeForm()
    {
        this.range.invisible();
        this.outerAngle.invisible();
        this.innerAngle.invisible();
        this.outerGain.invisible();

        this.add(this.range);
        this.add(this.outerAngle);
        this.add(this.innerAngle);
        this.add(this.outerGain);
    }

    @Override
    public float getMaxDistance()
    {
        return SoundConeGeometry.capDistance(this.range.get());
    }

    @Override
    public float shapeGain(float dirX, float dirY, float dirZ, float distance)
    {
        Vector3f axis = this.getAxis();
        float cosAngle = axis.x * dirX + axis.y * dirY + axis.z * dirZ;
        float outer = this.outerAngle.get();

        return SoundAcoustics.coneGain(cosAngle,
            SoundConeGeometry.clampInnerAngle(this.innerAngle.get(), outer), outer, this.outerGain.get());
    }

    /**
     * Unit vector the cone points along, taken from the form's transform.
     *
     * <p>Reused rather than allocated: this is read once per sound per frame.</p>
     */
    public Vector3f getAxis()
    {
        Transform transform = this.transform.get();

        this.axis.set(0F, 0F, 1F);

        if (transform != null)
        {
            this.axis.rotateX(transform.rotate.x);
            this.axis.rotateY(transform.rotate.y);
            this.axis.rotateZ(transform.rotate.z);
        }

        if (this.axis.lengthSquared() > 1e-8F)
        {
            this.axis.normalize();
        }

        return this.axis;
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "Sound (cone)";
    }
}
