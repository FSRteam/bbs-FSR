package mchorse.bbs_mod.forms.forms.sound;

/**
 * Pure acoustic math shared by every sound form.
 *
 * <p>Like {@link SoundFalloff}, nothing here may import Minecraft or BBS
 * classes — the test suite loads these on a bare JVM.</p>
 *
 * <p>All attenuation is computed here rather than handed to OpenAL. OpenAL's
 * distance model is global state, so switching it would change every sound in
 * the game, and its cone extends infinitely along the axis, which cannot
 * express "the cone's height is the maximum distance".</p>
 */
public final class SoundAcoustics
{
    /** Metres per second; one block is treated as one metre. */
    public static final float SPEED_OF_SOUND = 343F;

    private SoundAcoustics()
    {}

    /**
     * Propagation delay over a distance, in seconds. Reflections are the same
     * clip heard late, so this is what turns extra path length into an offset.
     */
    public static float delay(float distance)
    {
        return distance <= 0F ? 0F : distance / SPEED_OF_SOUND;
    }

    /**
     * Extra attenuation standing in for high-frequency air absorption.
     *
     * <p>Real air absorption is frequency dependent; at the parametric fidelity
     * this feature targets we only scale the overall gain, so distant sources
     * get quieter but not duller.</p>
     */
    public static float airAbsorptionGain(float distance, float coefficient)
    {
        if (coefficient <= 0F || distance <= 0F)
        {
            return 1F;
        }

        return (float) Math.exp(-coefficient * distance);
    }

    /**
     * Directional gain of a cone, following OpenAL's cone semantics: full gain
     * inside the inner cone, a smooth ramp across the transition, and
     * {@code outerGain} beyond the outer cone.
     *
     * <p>Distance cutoff is deliberately <em>not</em> applied here — the caller
     * combines this with {@link SoundFalloff#gain}, which is what makes the
     * cone finite.</p>
     *
     * @param cosAngle      cosine of the angle between the cone axis and the
     *                      direction to the listener, i.e. a dot product of two
     *                      unit vectors
     * @param innerAngleDeg full inner cone angle in degrees (not the half-angle)
     * @param outerAngleDeg full outer cone angle in degrees
     * @param outerGain     gain applied outside the outer cone, in [0, 1]
     */
    public static float coneGain(float cosAngle, float innerAngleDeg, float outerAngleDeg, float outerGain)
    {
        float outer = clamp(outerAngleDeg, 0F, 360F);
        /* An inner cone wider than the outer one is meaningless; the editor
         * clamps it too, but a hand-edited save can still get here */
        float inner = clamp(innerAngleDeg, 0F, outer);
        float gain = clamp(outerGain, 0F, 1F);

        float halfInner = inner * 0.5F;
        float halfOuter = outer * 0.5F;
        float angle = (float) Math.toDegrees(Math.acos(clamp(cosAngle, -1F, 1F)));

        if (angle <= halfInner)
        {
            return 1F;
        }

        if (angle >= halfOuter)
        {
            return gain;
        }

        /* Guarded above by the two early returns: reaching here means
         * halfInner < angle < halfOuter, so the span cannot be zero */
        float t = (angle - halfInner) / (halfOuter - halfInner);

        return 1F + (gain - 1F) * t;
    }

    /**
     * Gain of a reflected path: attenuation over the full path length times the
     * surface absorption applied once per bounce.
     *
     * @param pathLength total distance travelled, not the extra distance
     * @param order      number of bounces, 1 or greater
     * @param decay      fraction of energy kept per bounce, in [0, 1]
     */
    public static float reflectionGain(SoundFalloff falloff, float pathLength, float refDistance,
        float maxDistance, float rolloff, int order, float decay)
    {
        float gain = falloff.gain(pathLength, refDistance, maxDistance, rolloff);

        if (gain <= 0F)
        {
            return 0F;
        }

        return gain * (float) Math.pow(clamp(decay, 0F, 1F), Math.max(order, 1));
    }

    public static float clamp(float value, float min, float max)
    {
        return value < min ? min : (value > max ? max : value);
    }
}
