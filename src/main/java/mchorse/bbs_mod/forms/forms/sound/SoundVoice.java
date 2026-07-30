package mchorse.bbs_mod.forms.forms.sound;

/**
 * One virtual sound source: the direct sound, or a single reflection of it.
 *
 * <p>Deliberately a mutable carrier rather than a record. The playback
 * scheduler rebuilds the full set of voices every frame, so allocating here
 * would put garbage on a hot path; instances are pooled and overwritten
 * instead.</p>
 */
public class SoundVoice
{
    public float x;
    public float y;
    public float z;

    /** Playback position within the clip, in seconds. */
    public float seconds;

    /** Final gain, with distance, shape and reflection decay already applied. */
    public float gain;

    /** 0 for the direct sound, 1 or greater for reflections. */
    public int order;

    public void set(float x, float y, float z, float seconds, float gain, int order)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.seconds = seconds;
        this.gain = gain;
        this.order = order;
    }

    public boolean isAudible()
    {
        return this.gain > 0F;
    }
}
