package mchorse.bbs_mod.forms.forms.sound;

import mchorse.bbs_mod.settings.values.numeric.ValueFloat;

/**
 * A sound emitted equally in every direction from this form's position.
 *
 * <p>The radius is the emission range, which is also the distance at which the
 * sound goes silent — see {@link #getMaxDistance()}. Keeping it a single field
 * rather than a radius plus a separate cutoff is what guarantees the drawn
 * sphere and the audible sphere are the same sphere.</p>
 */
public class SoundSphereForm extends AbstractSoundForm
{
    /** Default and range mirror the reference light addon's point light, so the two feel alike. */
    public final ValueFloat radius = new ValueFloat("radius", 6F, 0.1F, 64F);

    public SoundSphereForm()
    {
        this.radius.invisible();
        this.add(this.radius);
    }

    @Override
    public float getMaxDistance()
    {
        return this.radius.get();
    }

    /** Omnidirectional: the listener's bearing does not matter. */
    @Override
    public float shapeGain(float dirX, float dirY, float dirZ, float distance)
    {
        return 1F;
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "Sound (sphere)";
    }
}
