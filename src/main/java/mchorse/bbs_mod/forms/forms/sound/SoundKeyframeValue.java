package mchorse.bbs_mod.forms.forms.sound;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.LinkUtils;

/** A complete value snapshot for one grouped sound-form keyframe track. */
public class SoundKeyframeValue
{
    public enum Group
    {
        SOUND("$sound"),
        SHAPE("$sound_shape"),
        VISUALIZATION("$sound_visualization"),
        FALLOFF("$sound_falloff"),
        REFLECTIONS("$sound_reflections");

        public final String suffix;

        Group(String suffix)
        {
            this.suffix = suffix;
        }
    }

    /* Sound */
    public Link audio;
    public boolean playing;
    public float volume = 1F;
    public float pitch = 1F;
    public boolean looping;
    public float startOffset;

    /* Shape: extent is radius for a sphere and range for a cone. */
    public float extent = 6F;
    public float innerAngle = 25F;
    public float outerAngle = 35F;
    public float outerGain = 0.2F;

    /* Visualization */
    public boolean showGuide = true;
    public Color guideColor = Color.white();

    /* Falloff */
    public String falloff = SoundFalloff.INVERSE.id;
    public float refDistance = 1F;
    public float rolloff = 1F;
    public float airAbsorption;

    /* Reflections */
    public boolean reflections;
    public int reflectionCount = 1;
    public float reflectionDecay = 0.5F;
    public boolean blockReflections = true;
    public boolean entityReflections;
    public boolean passThroughBlocks;
    public boolean passThroughEntities = true;

    public SoundKeyframeValue copy()
    {
        SoundKeyframeValue value = new SoundKeyframeValue();

        value.audio = LinkUtils.copy(this.audio);
        value.playing = this.playing;
        value.volume = this.volume;
        value.pitch = this.pitch;
        value.looping = this.looping;
        value.startOffset = this.startOffset;
        value.extent = this.extent;
        value.innerAngle = this.innerAngle;
        value.outerAngle = this.outerAngle;
        value.outerGain = this.outerGain;
        value.showGuide = this.showGuide;
        value.guideColor = this.guideColor == null ? Color.white() : this.guideColor.copy();
        value.falloff = this.falloff;
        value.refDistance = this.refDistance;
        value.rolloff = this.rolloff;
        value.airAbsorption = this.airAbsorption;
        value.reflections = this.reflections;
        value.reflectionCount = this.reflectionCount;
        value.reflectionDecay = this.reflectionDecay;
        value.blockReflections = this.blockReflections;
        value.entityReflections = this.entityReflections;
        value.passThroughBlocks = this.passThroughBlocks;
        value.passThroughEntities = this.passThroughEntities;

        return value;
    }

    public static SoundKeyframeValue capture(AbstractSoundForm form, Group group)
    {
        SoundKeyframeValue value = new SoundKeyframeValue();

        if (form == null)
        {
            return value;
        }

        switch (group)
        {
            case SOUND ->
            {
                value.audio = LinkUtils.copy(form.audio.get());
                value.playing = form.playing.get();
                value.volume = form.volume.get();
                value.pitch = form.pitch.get();
                value.looping = form.looping.get();
                value.startOffset = form.startOffset.get();
            }
            case SHAPE ->
            {
                if (form instanceof SoundSphereForm sphere)
                {
                    value.extent = sphere.radius.get();
                }
                else if (form instanceof SoundConeForm cone)
                {
                    value.extent = cone.range.get();
                    value.innerAngle = cone.innerAngle.get();
                    value.outerAngle = cone.outerAngle.get();
                    value.outerGain = cone.outerGain.get();
                }
            }
            case VISUALIZATION ->
            {
                value.showGuide = form.showGuide.get();
                value.guideColor = form.guideColor.get().copy();
            }
            case FALLOFF ->
            {
                value.falloff = form.falloff.get();
                value.refDistance = form.refDistance.get();
                value.rolloff = form.rolloff.get();
                value.airAbsorption = form.airAbsorption.get();
            }
            case REFLECTIONS ->
            {
                value.reflections = form.reflections.get();
                value.reflectionCount = form.reflectionCount.get();
                value.reflectionDecay = form.reflectionDecay.get();
                value.blockReflections = form.blockReflections.get();
                value.entityReflections = form.entityReflections.get();
                value.passThroughBlocks = form.passThroughBlocks.get();
                value.passThroughEntities = form.passThroughEntities.get();
            }
        }

        return value;
    }

    public void applyRuntime(AbstractSoundForm form, Group group)
    {
        switch (group)
        {
            case SOUND ->
            {
                form.audio.setRuntimeValue(LinkUtils.copy(this.audio));
                form.playing.setRuntimeValue(this.playing);
                form.volume.setRuntimeValue(this.volume);
                form.pitch.setRuntimeValue(this.pitch);
                form.looping.setRuntimeValue(this.looping);
                form.startOffset.setRuntimeValue(this.startOffset);
            }
            case SHAPE ->
            {
                if (form instanceof SoundSphereForm sphere)
                {
                    sphere.radius.setRuntimeValue(this.extent);
                }
                else if (form instanceof SoundConeForm cone)
                {
                    float outer = Math.max(1F, Math.min(179F, this.outerAngle));

                    cone.range.setRuntimeValue(this.extent);
                    cone.outerAngle.setRuntimeValue(outer);
                    cone.innerAngle.setRuntimeValue(SoundConeGeometry.clampInnerAngle(this.innerAngle, outer));
                    cone.outerGain.setRuntimeValue(this.outerGain);
                }
            }
            case VISUALIZATION ->
            {
                form.showGuide.setRuntimeValue(this.showGuide);
                form.guideColor.setRuntimeValue(this.guideColor == null ? Color.white() : this.guideColor.copy());
            }
            case FALLOFF ->
            {
                form.falloff.setRuntimeValue(this.falloff);
                form.refDistance.setRuntimeValue(this.refDistance);
                form.rolloff.setRuntimeValue(this.rolloff);
                form.airAbsorption.setRuntimeValue(this.airAbsorption);
            }
            case REFLECTIONS ->
            {
                form.reflections.setRuntimeValue(this.reflections);
                form.reflectionCount.setRuntimeValue(this.reflectionCount);
                form.reflectionDecay.setRuntimeValue(this.reflectionDecay);
                form.blockReflections.setRuntimeValue(this.blockReflections);
                form.entityReflections.setRuntimeValue(this.entityReflections);
                form.passThroughBlocks.setRuntimeValue(this.passThroughBlocks);
                form.passThroughEntities.setRuntimeValue(this.passThroughEntities);
            }
        }
    }

    public static void clearRuntime(AbstractSoundForm form, Group group)
    {
        switch (group)
        {
            case SOUND ->
            {
                form.audio.setRuntimeValue(null);
                form.playing.setRuntimeValue(null);
                form.volume.setRuntimeValue(null);
                form.pitch.setRuntimeValue(null);
                form.looping.setRuntimeValue(null);
                form.startOffset.setRuntimeValue(null);
            }
            case SHAPE ->
            {
                if (form instanceof SoundSphereForm sphere)
                {
                    sphere.radius.setRuntimeValue(null);
                }
                else if (form instanceof SoundConeForm cone)
                {
                    cone.range.setRuntimeValue(null);
                    cone.innerAngle.setRuntimeValue(null);
                    cone.outerAngle.setRuntimeValue(null);
                    cone.outerGain.setRuntimeValue(null);
                }
            }
            case VISUALIZATION ->
            {
                form.showGuide.setRuntimeValue(null);
                form.guideColor.setRuntimeValue(null);
            }
            case FALLOFF ->
            {
                form.falloff.setRuntimeValue(null);
                form.refDistance.setRuntimeValue(null);
                form.rolloff.setRuntimeValue(null);
                form.airAbsorption.setRuntimeValue(null);
            }
            case REFLECTIONS ->
            {
                form.reflections.setRuntimeValue(null);
                form.reflectionCount.setRuntimeValue(null);
                form.reflectionDecay.setRuntimeValue(null);
                form.blockReflections.setRuntimeValue(null);
                form.entityReflections.setRuntimeValue(null);
                form.passThroughBlocks.setRuntimeValue(null);
                form.passThroughEntities.setRuntimeValue(null);
            }
        }
    }

    public static String channelId(AbstractSoundForm form, Group group)
    {
        String path = FormUtils.getPath(form);

        return path.isEmpty() ? group.suffix : path + FormUtils.PATH_SEPARATOR + group.suffix;
    }

    public static Group groupFromChannel(String id)
    {
        if (id == null)
        {
            return null;
        }

        int slash = id.lastIndexOf(FormUtils.PATH_SEPARATOR);
        String suffix = slash < 0 ? id : id.substring(slash + 1);

        for (Group group : Group.values())
        {
            if (group.suffix.equals(suffix))
            {
                return group;
            }
        }

        return null;
    }

    public static AbstractSoundForm formFromChannel(Form root, String id)
    {
        Group group = groupFromChannel(id);

        if (group == null)
        {
            return null;
        }

        int slash = id.lastIndexOf(FormUtils.PATH_SEPARATOR);
        String path = slash < 0 ? "" : id.substring(0, slash);
        Form form = path.isEmpty() ? root : FormUtils.getForm(root, path);

        return form instanceof AbstractSoundForm sound ? sound : null;
    }
}
