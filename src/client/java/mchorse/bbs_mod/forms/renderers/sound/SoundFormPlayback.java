package mchorse.bbs_mod.forms.renderers.sound;

import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.SoundManager;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundAcoustics;
import mchorse.bbs_mod.forms.forms.sound.SoundPlaybackTimeline;
import mchorse.bbs_mod.forms.forms.sound.SoundReflections;
import mchorse.bbs_mod.forms.forms.sound.SoundVoice;
import mchorse.bbs_mod.resources.Link;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Drives one sound form's playback, one frame at a time.
 *
 * <p>Playback is <em>declarative</em>: every frame this rebuilds the full set
 * of voices that should be audible right now and hands it to
 * {@link SoundManager#reconcile}, which starts, updates and stops sources to
 * match. There is no play/stop state machine, which is precisely why reverse
 * playback, scrubbing, pausing and looping need no special cases — each is just
 * another frame with a different answer.</p>
 *
 * <p>Allocation: the voice pool, the identity keys and the desired map are all
 * reused across frames. The {@code VoiceRequest} records are not — they are
 * immutable by contract, and making them mutable would change an API that film
 * audio already depends on. At one record per audible voice, bounded by the
 * form's reflection budget, that is the one allocation left on this path.</p>
 */
public class SoundFormPlayback
{
    /** For callers that schedule the direct sound only. */
    public static final SoundReflections.Surface[] NO_SURFACES = new SoundReflections.Surface[0];

    private final Map<Object, SoundManager.VoiceRequest> desired = new IdentityHashMap<>();

    /** Stable identities so reconcile reuses the same source between frames. */
    private final Object directKey = new Object();
    private Object[] reflectionKeys = new Object[0];
    private SoundVoice[] voices = new SoundVoice[0];

    /**
     * Reconcile this form's voices for the current frame.
     *
     * @param owner        playback owner identity, as required by reconcile
     * @param formX        the form's position in the world — for a cone this is
     *                     the apex, which is where the sound is emitted from
     * @param seconds      playback position within the clip
     * @param surfaces     nearby reflecting surfaces; may be empty
     * @param playing      whether the timeline is advancing
     */
    public void update(SoundManager sounds, Object owner, AbstractSoundForm form,
        float formX, float formY, float formZ,
        float listenerX, float listenerY, float listenerZ,
        SoundReflections.Surface[] surfaces, int surfaceCount,
        boolean blockOccluded, boolean entityOccluded,
        float seconds, boolean playing, boolean seek)
    {
        this.desired.clear();

        Link link = form.getAudio();

        /* An empty desired map is how reconcile is told to release everything,
         * so silence needs no separate stop path */
        if (link == null || !form.playing.get())
        {
            sounds.reconcile(owner, this.desired, playing);

            return;
        }

        /* reconcile drops any voice whose position is past the end of the clip,
         * so looping has to wrap the request here or the sound simply stops */
        float duration = 0F;

        if (form.looping.get())
        {
            SoundBuffer buffer = sounds.get(link, false);
            duration = buffer == null ? 0F : buffer.getDuration();

            if (duration > 0F)
            {
                seconds = SoundPlaybackTimeline.wrapLoopingSeconds(seconds, duration);
            }
        }

        float dx = listenerX - formX;
        float dy = listenerY - formY;
        float dz = listenerZ - formZ;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        float dirX = 0F;
        float dirY = 0F;
        float dirZ = 0F;

        if (distance > 1e-5F)
        {
            dirX = dx / distance;
            dirY = dy / distance;
            dirZ = dz / distance;
        }

        float directGain = form.gainAt(dirX, dirY, dirZ, distance);

        if (directGain > 0F && !blockOccluded && !entityOccluded)
        {
            this.desired.put(this.directKey, SoundManager.VoiceRequest.spatial(
                link, seconds, directGain, formX, formY, formZ,
                form.pitch.get(), form.looping.get(), form.getMaxDistance(), seek));
        }

        int order = form.getReflectionOrder();
        int budget = Math.min(form.reflectionVoices.get(), 16);

        if (order <= 0 || budget <= 0 || surfaceCount <= 0)
        {
            sounds.reconcile(owner, this.desired, playing);

            return;
        }

        this.ensureCapacity(budget);

        int count = SoundReflections.collect(
            formX, formY, formZ,
            listenerX, listenerY, listenerZ,
            surfaces, surfaceCount,
            form.getFalloff(), form.refDistance.get(), form.getMaxDistance(), form.rolloff.get(), form.airAbsorption.get(),
            form.blockReflections.get(), form.entityReflections.get(),
            form.passThroughBlocks.get(), form.passThroughEntities.get(),
            order, form.reflectionDecay.get(), seconds, form.volume.get(),
            this.voices, budget);

        for (int i = 0; i < count; i++)
        {
            SoundVoice voice = this.voices[i];

            float reflectionX = voice.x - formX;
            float reflectionY = voice.y - formY;
            float reflectionZ = voice.z - formZ;
            float reflectionDistance = (float) Math.sqrt(
                reflectionX * reflectionX + reflectionY * reflectionY + reflectionZ * reflectionZ);

            if (reflectionDistance > 1e-5F)
            {
                voice.gain *= form.shapeGain(
                    reflectionX / reflectionDistance,
                    reflectionY / reflectionDistance,
                    reflectionZ / reflectionDistance,
                    reflectionDistance);
            }

            /* A reflection arriving before the clip started has nothing to play yet */
            float voiceSeconds = voice.seconds;

            if (!voice.isAudible() || voiceSeconds < 0F)
            {
                continue;
            }

            if (duration > 0F)
            {
                voiceSeconds = SoundPlaybackTimeline.wrapLoopingSeconds(voiceSeconds, duration);
            }

            this.desired.put(this.reflectionKeys[i],
                SoundManager.VoiceRequest.spatial(
                    link, voiceSeconds, voice.gain, voice.x, voice.y, voice.z,
                    form.pitch.get(), form.looping.get(), form.getMaxDistance(), seek));
        }

        sounds.reconcile(owner, this.desired, playing);
    }

    /** Release every voice this form owns, e.g. when the form goes away. */
    public void release(SoundManager sounds, Object owner)
    {
        this.desired.clear();
        sounds.releaseOwner(owner);
    }

    private void ensureCapacity(int budget)
    {
        if (this.voices.length >= budget)
        {
            return;
        }

        SoundVoice[] grownVoices = new SoundVoice[budget];
        Object[] grownKeys = new Object[budget];

        System.arraycopy(this.voices, 0, grownVoices, 0, this.voices.length);
        System.arraycopy(this.reflectionKeys, 0, grownKeys, 0, this.reflectionKeys.length);

        for (int i = this.voices.length; i < budget; i++)
        {
            grownVoices[i] = new SoundVoice();
            grownKeys[i] = new Object();
        }

        this.voices = grownVoices;
        this.reflectionKeys = grownKeys;
    }

    /** Delay of a reflected path relative to the direct sound, for diagnostics. */
    public static float reflectionDelay(float pathLength, float directDistance)
    {
        return SoundAcoustics.delay(pathLength - directDistance);
    }

}
