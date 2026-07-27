package mchorse.bbs_mod.utils.keyframes.factories;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.sound.SoundConeGeometry;
import mchorse.bbs_mod.forms.forms.sound.SoundFalloff;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.resources.LinkUtils;

/** Serializer and grouped interpolator for one sound-form composite track. */
public class SoundKeyframeFactory implements IKeyframeFactory<SoundKeyframeValue>
{
    private final SoundKeyframeValue.Group group;
    private final SoundKeyframeValue interpolated = new SoundKeyframeValue();

    public SoundKeyframeFactory(SoundKeyframeValue.Group group)
    {
        this.group = group;
    }

    public SoundKeyframeValue.Group getGroup()
    {
        return this.group;
    }

    @Override
    public SoundKeyframeValue fromData(BaseType data)
    {
        SoundKeyframeValue value = new SoundKeyframeValue();

        if (!data.isMap())
        {
            return value;
        }

        MapType map = data.asMap();

        switch (this.group)
        {
            case SOUND ->
            {
                value.audio = LinkUtils.create(map.get("audio"));
                value.playing = map.getBool("playing");
                value.volume = map.getFloat("volume", 1F);
                value.pitch = map.getFloat("pitch", 1F);
                value.looping = map.getBool("looping");
                value.startOffset = map.getFloat("start_offset");
            }
            case SHAPE ->
            {
                value.extent = map.getFloat("extent", 6F);
                value.innerAngle = map.getFloat("inner_angle", 25F);
                value.outerAngle = map.getFloat("outer_angle", 35F);
                value.outerGain = map.getFloat("outer_gain", 0.2F);
            }
            case VISUALIZATION ->
            {
                value.showGuide = map.getBool("show_guide", true);
                value.guideColor = Color.rgba(map.getInt("guide_color", Color.white().getARGBColor()));
            }
            case FALLOFF ->
            {
                value.falloff = map.getString("falloff", SoundFalloff.INVERSE.id);
                value.refDistance = map.getFloat("ref_distance", 1F);
                value.rolloff = map.getFloat("rolloff", 1F);
                value.airAbsorption = map.getFloat("air_absorption");
            }
            case REFLECTIONS ->
            {
                value.reflections = map.getBool("reflections");
                value.reflectionCount = map.getInt("reflection_count", 1);
                value.reflectionDecay = map.getFloat("reflection_decay", 0.5F);
                value.blockReflections = map.getBool("block_reflections", true);
                value.entityReflections = map.getBool("entity_reflections");
                value.passThroughBlocks = map.getBool("pass_through_blocks");
                value.passThroughEntities = map.getBool("pass_through_entities", true);
            }
        }

        return value;
    }

    @Override
    public BaseType toData(SoundKeyframeValue value)
    {
        MapType map = new MapType(false);

        switch (this.group)
        {
            case SOUND ->
            {
                map.put("audio", LinkUtils.toData(value.audio));
                map.putBool("playing", value.playing);
                map.putFloat("volume", value.volume);
                map.putFloat("pitch", value.pitch);
                map.putBool("looping", value.looping);
                map.putFloat("start_offset", value.startOffset);
            }
            case SHAPE ->
            {
                map.putFloat("extent", value.extent);
                map.putFloat("inner_angle", value.innerAngle);
                map.putFloat("outer_angle", value.outerAngle);
                map.putFloat("outer_gain", value.outerGain);
            }
            case VISUALIZATION ->
            {
                map.putBool("show_guide", value.showGuide);
                map.putInt("guide_color", value.guideColor == null ? Color.white().getARGBColor() : value.guideColor.getARGBColor());
            }
            case FALLOFF ->
            {
                map.putString("falloff", value.falloff);
                map.putFloat("ref_distance", value.refDistance);
                map.putFloat("rolloff", value.rolloff);
                map.putFloat("air_absorption", value.airAbsorption);
            }
            case REFLECTIONS ->
            {
                map.putBool("reflections", value.reflections);
                map.putInt("reflection_count", value.reflectionCount);
                map.putFloat("reflection_decay", value.reflectionDecay);
                map.putBool("block_reflections", value.blockReflections);
                map.putBool("entity_reflections", value.entityReflections);
                map.putBool("pass_through_blocks", value.passThroughBlocks);
                map.putBool("pass_through_entities", value.passThroughEntities);
            }
        }

        return map;
    }

    @Override
    public SoundKeyframeValue createEmpty()
    {
        return new SoundKeyframeValue();
    }

    @Override
    public SoundKeyframeValue copy(SoundKeyframeValue value)
    {
        return value == null ? new SoundKeyframeValue() : value.copy();
    }

    @Override
    public boolean compare(Object a, Object b)
    {
        return a instanceof SoundKeyframeValue av && b instanceof SoundKeyframeValue bv
            && this.toData(av).equals(this.toData(bv));
    }

    @Override
    public SoundKeyframeValue interpolate(SoundKeyframeValue preA, SoundKeyframeValue a,
        SoundKeyframeValue b, SoundKeyframeValue postB, IInterp interpolation, float x)
    {
        preA = preA == null ? new SoundKeyframeValue() : preA;
        a = a == null ? new SoundKeyframeValue() : a;
        b = b == null ? a : b;
        postB = postB == null ? b : postB;

        switch (this.group)
        {
            case SOUND ->
            {
                this.interpolated.audio = LinkUtils.copy(a.audio);
                this.interpolated.playing = a.playing;
                this.interpolated.volume = lerp(interpolation, preA.volume, a.volume, b.volume, postB.volume, x);
                this.interpolated.pitch = lerp(interpolation, preA.pitch, a.pitch, b.pitch, postB.pitch, x);
                this.interpolated.looping = a.looping;
                this.interpolated.startOffset = lerp(interpolation, preA.startOffset, a.startOffset, b.startOffset, postB.startOffset, x);
            }
            case SHAPE ->
            {
                this.interpolated.extent = lerp(interpolation, preA.extent, a.extent, b.extent, postB.extent, x);
                this.interpolated.innerAngle = lerp(interpolation, preA.innerAngle, a.innerAngle, b.innerAngle, postB.innerAngle, x);
                this.interpolated.outerAngle = lerp(interpolation, preA.outerAngle, a.outerAngle, b.outerAngle, postB.outerAngle, x);
                this.interpolated.outerGain = lerp(interpolation, preA.outerGain, a.outerGain, b.outerGain, postB.outerGain, x);
                this.interpolated.innerAngle = SoundConeGeometry.clampInnerAngle(this.interpolated.innerAngle, this.interpolated.outerAngle);
            }
            case VISUALIZATION ->
            {
                this.interpolated.showGuide = a.showGuide;
                Color preColor = color(preA.guideColor);
                Color aColor = color(a.guideColor);
                Color bColor = color(b.guideColor);
                Color postColor = color(postB.guideColor);

                this.interpolated.guideColor.set(
                    MathUtils.clamp(lerp(interpolation, preColor.r, aColor.r, bColor.r, postColor.r, x), 0F, 1F),
                    MathUtils.clamp(lerp(interpolation, preColor.g, aColor.g, bColor.g, postColor.g, x), 0F, 1F),
                    MathUtils.clamp(lerp(interpolation, preColor.b, aColor.b, bColor.b, postColor.b, x), 0F, 1F),
                    MathUtils.clamp(lerp(interpolation, preColor.a, aColor.a, bColor.a, postColor.a, x), 0F, 1F));
            }
            case FALLOFF ->
            {
                this.interpolated.falloff = a.falloff;
                this.interpolated.refDistance = lerp(interpolation, preA.refDistance, a.refDistance, b.refDistance, postB.refDistance, x);
                this.interpolated.rolloff = lerp(interpolation, preA.rolloff, a.rolloff, b.rolloff, postB.rolloff, x);
                this.interpolated.airAbsorption = lerp(interpolation, preA.airAbsorption, a.airAbsorption, b.airAbsorption, postB.airAbsorption, x);
            }
            case REFLECTIONS ->
            {
                this.interpolated.reflections = a.reflections;
                this.interpolated.reflectionCount = Math.round(lerp(interpolation,
                    preA.reflectionCount, a.reflectionCount, b.reflectionCount, postB.reflectionCount, x));
                this.interpolated.reflectionDecay = lerp(interpolation, preA.reflectionDecay, a.reflectionDecay, b.reflectionDecay, postB.reflectionDecay, x);
                this.interpolated.blockReflections = a.blockReflections;
                this.interpolated.entityReflections = a.entityReflections;
                this.interpolated.passThroughBlocks = a.passThroughBlocks;
                this.interpolated.passThroughEntities = a.passThroughEntities;
            }
        }

        return this.interpolated;
    }

    private static float lerp(IInterp interpolation, float preA, float a, float b, float postB, float x)
    {
        return (float) interpolation.interpolate(IInterp.context.set(preA, a, b, postB, x));
    }

    private static Color color(Color color)
    {
        return color == null ? Color.white() : color;
    }
}
