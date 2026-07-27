package mchorse.bbs_mod.forms.forms.sound;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * Shared base for the sound forms: everything except the emission shape.
 *
 * <p>Every field below is a {@code Value*}, which means it is serialized and
 * keyframable for free — the film editor picks up animatable properties by
 * walking the value tree, so declaring a field is all it takes to get a
 * timeline track.</p>
 *
 * <p>There is deliberately no {@code maxDistance} field. The distance at which
 * the sound dies out <em>is</em> the shape's extent — the sphere's radius, the
 * cone's height — so it is supplied by {@link #getMaxDistance()} instead. Two
 * independent fields would let the drawn boundary and the audible boundary
 * drift apart, and the whole point of the visualization is that they cannot.</p>
 */
public abstract class AbstractSoundForm extends Form
{
    /* Source */

    public final ValueLink audio = new ValueLink("audio", null);
    /**
     * Whether the sound should be heard right now. Keyframe this to start and
     * stop playback on the timeline; because playback is driven from the
     * current value every frame rather than from edge transitions, scrubbing
     * and reverse playback need no special handling.
     */
    public final ValueBoolean playing = new ValueBoolean("playing", false);
    public final ValueFloat volume = new ValueFloat("volume", 1F, 0F, Float.POSITIVE_INFINITY);
    public final ValueFloat pitch = new ValueFloat("pitch", 1F, 0.1F, 4F);
    public final ValueBoolean looping = new ValueBoolean("looping", false);
    /** Offset into the clip, in seconds, where playback begins. */
    public final ValueFloat startOffset = new ValueFloat("start_offset", 0F, 0F, 3600F);

    /* Distance falloff */

    public final ValueString falloff = new ValueString("falloff", SoundFalloff.INVERSE.id);
    public final ValueFloat refDistance = new ValueFloat("ref_distance", 1F, 0.01F, 64F);
    public final ValueFloat rolloff = new ValueFloat("rolloff", 1F, 0F, 10F);
    public final ValueFloat airAbsorption = new ValueFloat("air_absorption", 0F, 0F, 1F);

    /* Reflections */

    public final ValueBoolean reflections = new ValueBoolean("reflections", false);
    public final ValueInt reflectionCount = new ValueInt("reflection_count", 1, 0, 8);
    /** Fraction of energy kept per bounce. */
    public final ValueFloat reflectionDecay = new ValueFloat("reflection_decay", 0.5F, 0F, 1F);
    public final ValueBoolean blockReflections = new ValueBoolean("block_reflections", true);
    public final ValueBoolean entityReflections = new ValueBoolean("entity_reflections", false);
    public final ValueBoolean passThroughBlocks = new ValueBoolean("pass_through_blocks", false);
    public final ValueBoolean passThroughEntities = new ValueBoolean("pass_through_entities", true);
    /** Hard cap on reflection voices, so one form cannot starve every other sound. */
    public final ValueInt reflectionVoices = new ValueInt("reflection_voices", 4, 0, 16);

    /* Visualization */

    public final ValueBoolean showGuide = new ValueBoolean("show_guide", true);
    public final ValueColor guideColor = new ValueColor("guide_color", Color.white());

    public AbstractSoundForm()
    {
        /* These remain the persisted/runtime truth, but new Film editing uses
         * five grouped channels instead of exposing one track per field. */
        this.audio.invisible();
        this.playing.invisible();
        this.volume.invisible();
        this.pitch.invisible();
        this.looping.invisible();
        this.startOffset.invisible();
        this.falloff.invisible();
        this.refDistance.invisible();
        this.rolloff.invisible();
        this.airAbsorption.invisible();
        this.reflections.invisible();
        this.reflectionCount.invisible();
        this.reflectionDecay.invisible();
        this.blockReflections.invisible();
        this.entityReflections.invisible();
        this.passThroughBlocks.invisible();
        this.passThroughEntities.invisible();
        this.showGuide.invisible();
        this.guideColor.invisible();

        this.add(this.audio);
        this.add(this.playing);
        this.add(this.volume);
        this.add(this.pitch);
        this.add(this.looping);
        this.add(this.startOffset);

        this.add(this.falloff);
        this.add(this.refDistance);
        this.add(this.rolloff);
        this.add(this.airAbsorption);

        this.add(this.reflections);
        this.add(this.reflectionCount);
        this.add(this.reflectionDecay);
        this.add(this.blockReflections);
        this.add(this.entityReflections);
        this.add(this.passThroughBlocks);
        this.add(this.passThroughEntities);

        /* Purely a resource budget — keeping it off the timeline avoids
         * cluttering the dope sheet with something nobody animates */
        this.reflectionVoices.invisible();
        this.add(this.reflectionVoices);

        this.add(this.showGuide);
        this.add(this.guideColor);
    }

    /**
     * Distance at which this form goes silent, supplied by the emission shape.
     * Shared by the acoustics and by the guide renderer.
     */
    public abstract float getMaxDistance();

    /**
     * Directional gain toward the listener, in [0, 1].
     *
     * @param dirX     unit vector from this form toward the listener
     * @param distance distance to the listener, for shapes that need it
     * @return 1 for an omnidirectional shape
     */
    public abstract float shapeGain(float dirX, float dirY, float dirZ, float distance);

    public SoundFalloff getFalloff()
    {
        return SoundFalloff.fromId(this.falloff.get());
    }

    public Link getAudio()
    {
        Link link = this.audio.get();

        return link == null || link.path.isEmpty() ? null : link;
    }

    /** Reflection bounces actually requested, or 0 when reflections are off. */
    public int getReflectionOrder()
    {
        return this.reflections.get() ? this.reflectionCount.get() : 0;
    }

    /**
     * Overall gain at a point, combining distance falloff, air absorption and
     * the shape's directivity. This is the single place both playback and the
     * intensity bands ask, so the two cannot disagree.
     */
    public float gainAt(float dirX, float dirY, float dirZ, float distance)
    {
        float max = this.getMaxDistance();

        if (distance >= max)
        {
            return 0F;
        }

        float gain = this.getFalloff().gain(distance, this.refDistance.get(), max, this.rolloff.get());

        if (gain <= 0F)
        {
            return 0F;
        }

        gain *= SoundAcoustics.airAbsorptionGain(distance, this.airAbsorption.get());
        gain *= this.shapeGain(dirX, dirY, dirZ, distance);

        return gain * this.volume.get();
    }
}
