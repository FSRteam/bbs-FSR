package mchorse.bbs_mod.forms.forms.sound;

/**
 * Distance attenuation models for sound forms.
 *
 * <p>Every method here is a pure function of its arguments. This class must not
 * import anything from Minecraft or the rest of BBS: the acoustics test suite
 * runs on a bare JVM and loading {@code BBSMod} there would blow up on static
 * initialization.</p>
 *
 * <p>Both the forward gain and its inverse live here on purpose. The editor
 * draws intensity bands by asking "at what distance does this drop to -6 dB?",
 * and if that answer came from a separate approximation the visualization would
 * quietly disagree with what the player hears.</p>
 */
public enum SoundFalloff
{
    /** Closest to a real point source; the OpenAL default. */
    INVERSE("inverse"),
    /** Reaches silence exactly at the maximum distance; easiest to reason about. */
    LINEAR("linear"),
    /** Drops off the fastest. */
    EXPONENTIAL("exponential");

    /**
     * Cached because {@link #values()} clones its array on every call, and
     * {@link #fromId} is reached once per sound per frame.
     */
    private static final SoundFalloff[] VALUES = values();

    public final String id;

    private SoundFalloff(String id)
    {
        this.id = id;
    }

    /**
     * Resolve a stored identifier. Unknown values fall back to {@link #INVERSE}
     * rather than throwing, so a hand-edited or future-version save still loads.
     */
    public static SoundFalloff fromId(String id)
    {
        for (SoundFalloff falloff : VALUES)
        {
            if (falloff.id.equals(id))
            {
                return falloff;
            }
        }

        return INVERSE;
    }

    /**
     * Gain multiplier at the given distance, in [0, 1].
     *
     * @param distance     distance from the source to the listener
     * @param refDistance  distance below which there is no attenuation
     * @param maxDistance  distance at and beyond which the source is silent
     * @param rolloff      attenuation strength; 0 disables attenuation entirely
     */
    public float gain(float distance, float refDistance, float maxDistance, float rolloff)
    {
        float ref = Math.max(refDistance, 0.0001F);
        float max = Math.max(maxDistance, ref);

        if (distance <= ref)
        {
            return 1F;
        }

        if (distance >= max)
        {
            return 0F;
        }

        if (rolloff <= 0F)
        {
            return 1F;
        }

        float gain;

        if (this == LINEAR)
        {
            gain = 1F - rolloff * (distance - ref) / (max - ref);
        }
        else if (this == EXPONENTIAL)
        {
            gain = (float) Math.pow(distance / ref, -rolloff);
        }
        else
        {
            /* this == INVERSE */
            gain = ref / (ref + rolloff * (distance - ref));
        }

        return gain <= 0F ? 0F : (gain >= 1F ? 1F : gain);
    }

    /**
     * Distance at which {@link #gain} equals the given value — the inverse of
     * the curve above, used to place intensity bands in the world.
     *
     * @return the distance, or a negative value when the target gain is never
     *         reached inside {@code [refDistance, maxDistance]}. Callers must
     *         check the sign and skip the band rather than drawing it wrong.
     */
    public float distanceForGain(float gain, float refDistance, float maxDistance, float rolloff)
    {
        float ref = Math.max(refDistance, 0.0001F);
        float max = Math.max(maxDistance, ref);

        if (gain >= 1F)
        {
            return ref;
        }

        if (gain <= 0F)
        {
            return max;
        }

        if (rolloff <= 0F)
        {
            /* The curve never leaves 1, so no finite distance reaches this gain */
            return -1F;
        }

        float distance;

        if (this == LINEAR)
        {
            distance = ref + (1F - gain) * (max - ref) / rolloff;
        }
        else if (this == EXPONENTIAL)
        {
            distance = ref * (float) Math.pow(gain, -1F / rolloff);
        }
        else
        {
            /* this == INVERSE */
            distance = ref + ref * (1F - gain) / (gain * rolloff);
        }

        return distance > max ? -1F : distance;
    }
}
