package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.forms.forms.sound.SoundAcoustics;
import mchorse.bbs_mod.forms.forms.sound.SoundConeGeometry;
import mchorse.bbs_mod.forms.forms.sound.SoundFalloff;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.sound.SoundPlaybackTimeline;
import mchorse.bbs_mod.forms.forms.sound.SoundReflections;
import mchorse.bbs_mod.forms.forms.sound.SoundVoice;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.BooleanKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.SoundKeyframeFactory;

/**
 * Executable contract tests for the sound form acoustics.
 *
 * <p>Runs on a bare JVM. Every class under test is a pure function holder with
 * no Minecraft or BBS static state, so nothing here may trigger
 * {@code BBSMod}'s static initializer.</p>
 */
public final class SoundAcousticsTest {
    private static final float EPSILON = 1e-4F;

    private static final float REF = 1F;
    private static final float MAX = 32F;
    private static final float ROLLOFF = 1F;

    public static void main(String[] args) {
        falloffBoundaries();
        falloffMonotonicity();
        falloffOrdering();
        falloffInverseRoundTrip();
        falloffDegenerateRolloff();
        coneSegments();
        coneDegenerateAngles();
        coneClampsInnerToOuter();
        airAbsorption();
        reflectionDelayAndGain();
        reflectionBudgetKeepsStrongest();
        reflectionSkipsSurfacesBehindSource();
        reflectionCategoryMatrix();
        soundKeyframeFactoriesRoundTripCopyAndInterpolate();
        soundFormFieldsAreKeyframable();
        coneGeometryBasics();
        coneGeometryMatchesAcoustics();
        playbackTimelineTriggersAndSeeks();
        System.out.println("SoundAcousticsTest: all tests passed");
    }

    private static void playbackTimelineTriggersAndSeeks() {
        KeyframeChannel<Boolean> playing = new KeyframeChannel<>("playing", new BooleanKeyframeFactory());

        playing.insert(0F, false);
        playing.insert(20F, true);
        playing.insert(40F, true);
        playing.insert(60F, false);
        playing.insert(80F, true);

        check(SoundPlaybackTimeline.findActivationTick(playing, 35F, true) == 20F,
            "timeline: true run starts at its false-to-true keyframe");
        check(SoundPlaybackTimeline.findActivationTick(playing, 50F, true) == 20F,
            "timeline: redundant true keyframes do not restart the clip");
        check(SoundPlaybackTimeline.findActivationTick(playing, 90F, true) == 80F,
            "timeline: a later trigger starts a new clip run");
        check(SoundPlaybackTimeline.clipSeconds(50F, 20F, 1.5F) == 3F,
            "timeline: clip position includes trigger-relative time and start offset");
        check(SoundPlaybackTimeline.wrapLoopingSeconds(-0.25F, 5F) == -0.25F,
            "timeline: a looping reflection remains silent until its delayed arrival");
        check(close(SoundPlaybackTimeline.wrapLoopingSeconds(5.25F, 5F), 0.25F),
            "timeline: an arrived looping voice wraps inside the clip duration");

        check(!SoundPlaybackTimeline.shouldSeek(20F, 21F, true, true, true),
            "timeline: normal forward playback advances without forced seeks");
        check(SoundPlaybackTimeline.shouldSeek(50F, 5F, true, true, true),
            "timeline: loop wrap seeks to the wrapped position");
        check(SoundPlaybackTimeline.shouldSeek(20F, 19F, true, true, true),
            "timeline: reverse playback seeks every frame");
        check(SoundPlaybackTimeline.shouldSeek(20F, 20F, true, true, false),
            "timeline: paused scrubbing reconciles the exact offset");
        check(SoundPlaybackTimeline.shouldSeek(19F, 20F, false, true, true),
            "timeline: a fresh trigger seeks to its configured start offset");

        KeyframeChannel<SoundKeyframeValue> grouped = new KeyframeChannel<>("$sound",
            new SoundKeyframeFactory(SoundKeyframeValue.Group.SOUND));
        SoundKeyframeValue off = new SoundKeyframeValue();
        SoundKeyframeValue on = new SoundKeyframeValue();

        on.playing = true;
        check(SoundPlaybackTimeline.findSoundActivationTick(grouped, playing, 50F, true) == 20F,
            "grouped timeline: an empty editor sheet preserves the legacy activation tick");

        grouped.insert(0F, off.copy());
        grouped.insert(20F, on.copy());
        grouped.insert(40F, on.copy());
        grouped.insert(60F, off.copy());
        grouped.insert(80F, on.copy());

        check(SoundPlaybackTimeline.findActivationTick(grouped, 50F, true,
            value -> value != null && value.playing) == 20F,
            "grouped timeline: redundant true snapshots keep the original activation tick");
        check(SoundPlaybackTimeline.findActivationTick(grouped, 90F, true,
            value -> value != null && value.playing) == 80F,
            "grouped timeline: a later sound snapshot starts a new run");

        KeyframeChannel<SoundKeyframeValue> preferred = new KeyframeChannel<>("$sound",
            new SoundKeyframeFactory(SoundKeyframeValue.Group.SOUND));

        preferred.insert(0F, off.copy());
        preferred.insert(30F, on.copy());
        check(SoundPlaybackTimeline.findSoundActivationTick(preferred, playing, 50F, true) == 30F,
            "grouped timeline: a non-empty grouped track overrides the legacy activation tick");
    }

    /* Distance falloff */

    private static void falloffBoundaries() {
        for (SoundFalloff falloff : SoundFalloff.values()) {
            String label = falloff.id;

            check(falloff.gain(0F, REF, MAX, ROLLOFF) == 1F, label + ": at zero distance");
            check(falloff.gain(REF, REF, MAX, ROLLOFF) == 1F, label + ": at reference distance");
            check(falloff.gain(REF * 0.5F, REF, MAX, ROLLOFF) == 1F, label + ": inside reference distance");
            check(falloff.gain(MAX, REF, MAX, ROLLOFF) == 0F, label + ": at max distance");
            check(falloff.gain(MAX * 2F, REF, MAX, ROLLOFF) == 0F, label + ": beyond max distance");

            float mid = falloff.gain((REF + MAX) * 0.5F, REF, MAX, ROLLOFF);

            check(mid > 0F && mid < 1F, label + ": mid range stays inside (0, 1)");
        }
    }

    private static void falloffMonotonicity() {
        for (SoundFalloff falloff : SoundFalloff.values()) {
            float previous = 1F;

            for (float d = REF; d < MAX; d += 0.5F) {
                float gain = falloff.gain(d, REF, MAX, ROLLOFF);

                check(gain <= previous + EPSILON, falloff.id + ": gain must not increase with distance");
                previous = gain;
            }
        }
    }

    /** Exponential should fall off at least as fast as inverse in the mid range. */
    private static void falloffOrdering() {
        float d = 8F;
        float inverse = SoundFalloff.INVERSE.gain(d, REF, MAX, ROLLOFF);
        float exponential = SoundFalloff.EXPONENTIAL.gain(d, REF, MAX, ROLLOFF);

        check(exponential <= inverse + EPSILON, "exponential falls off at least as fast as inverse");
    }

    /**
     * The editor draws intensity bands by inverting the curve. If these two
     * ever disagree the visualization would lie about where sound dies out.
     */
    private static void falloffInverseRoundTrip() {
        float[] targets = {0.75F, 0.5F, 0.25F, 0.1F};

        for (SoundFalloff falloff : SoundFalloff.values()) {
            for (float target : targets) {
                float distance = falloff.distanceForGain(target, REF, MAX, ROLLOFF);

                if (distance < 0F) {
                    /* Unreachable inside the range is a legal answer; callers
                     * skip the band rather than drawing it in the wrong place */
                    continue;
                }

                float actual = falloff.gain(distance, REF, MAX, ROLLOFF);

                check(Math.abs(actual - target) < 1e-3F,
                    falloff.id + ": inverting gain " + target + " gave distance " + distance + " worth " + actual);
            }
        }
    }

    private static void falloffDegenerateRolloff() {
        for (SoundFalloff falloff : SoundFalloff.values()) {
            check(falloff.gain(10F, REF, MAX, 0F) == 1F, falloff.id + ": zero rolloff disables attenuation");
            check(falloff.distanceForGain(0.5F, REF, MAX, 0F) < 0F, falloff.id + ": zero rolloff never reaches a lower gain");
            /* A max distance below the reference distance must not divide by zero */
            check(falloff.gain(5F, 10F, 1F, ROLLOFF) >= 0F, falloff.id + ": max below reference stays finite");
        }
    }

    /* Cone directivity */

    private static void coneSegments() {
        float inner = 30F;
        float outer = 90F;
        float outerGain = 0.2F;

        float onAxis = SoundAcoustics.coneGain(1F, inner, outer, outerGain);
        float insideInner = SoundAcoustics.coneGain(cosDeg(10F), inner, outer, outerGain);
        float transition = SoundAcoustics.coneGain(cosDeg(30F), inner, outer, outerGain);
        float outsideOuter = SoundAcoustics.coneGain(cosDeg(80F), inner, outer, outerGain);
        float behind = SoundAcoustics.coneGain(-1F, inner, outer, outerGain);

        check(onAxis == 1F, "cone: on axis is full gain");
        check(insideInner == 1F, "cone: inside the inner cone is full gain");
        check(transition > outerGain && transition < 1F, "cone: transition lies strictly between");
        check(Math.abs(outsideOuter - outerGain) < EPSILON, "cone: outside the outer cone is outer gain");
        check(Math.abs(behind - outerGain) < EPSILON, "cone: directly behind is outer gain");

        /* Continuity at both ends of the ramp */
        float atInnerEdge = SoundAcoustics.coneGain(cosDeg(inner * 0.5F), inner, outer, outerGain);
        float atOuterEdge = SoundAcoustics.coneGain(cosDeg(outer * 0.5F), inner, outer, outerGain);

        check(Math.abs(atInnerEdge - 1F) < EPSILON, "cone: continuous at the inner edge");
        check(Math.abs(atOuterEdge - outerGain) < EPSILON, "cone: continuous at the outer edge");
    }

    private static void coneDegenerateAngles() {
        /* Equal angles collapse the ramp to zero width and must not divide by zero */
        float atEdge = SoundAcoustics.coneGain(cosDeg(30F), 60F, 60F, 0.3F);
        float inside = SoundAcoustics.coneGain(cosDeg(10F), 60F, 60F, 0.3F);
        float outside = SoundAcoustics.coneGain(cosDeg(50F), 60F, 60F, 0.3F);

        check(isFinite(atEdge) && isFinite(inside) && isFinite(outside), "cone: equal angles stay finite");
        check(inside == 1F, "cone: equal angles still give full gain inside");
        check(Math.abs(outside - 0.3F) < EPSILON, "cone: equal angles still give outer gain outside");

        float zeroWidth = SoundAcoustics.coneGain(1F, 0F, 0F, 0.5F);

        check(isFinite(zeroWidth), "cone: zero angles stay finite");
    }

    private static void coneClampsInnerToOuter() {
        /* A save edited by hand can carry an inner cone wider than the outer one */
        float wide = SoundAcoustics.coneGain(cosDeg(40F), 120F, 60F, 0.25F);
        float clamped = SoundAcoustics.coneGain(cosDeg(40F), 60F, 60F, 0.25F);

        check(Math.abs(wide - clamped) < EPSILON, "cone: inner angle is clamped to the outer angle");
    }

    private static void airAbsorption() {
        check(SoundAcoustics.airAbsorptionGain(10F, 0F) == 1F, "air: zero coefficient is transparent");
        check(SoundAcoustics.airAbsorptionGain(0F, 0.1F) == 1F, "air: zero distance is transparent");

        float near = SoundAcoustics.airAbsorptionGain(5F, 0.05F);
        float far = SoundAcoustics.airAbsorptionGain(20F, 0.05F);

        check(far < near, "air: farther is quieter");
        check(far > 0F, "air: never reaches silence outright");
    }

    /* Reflections */

    private static void reflectionDelayAndGain() {
        check(Math.abs(SoundAcoustics.delay(SoundAcoustics.SPEED_OF_SOUND) - 1F) < EPSILON,
            "delay: one second per speed-of-sound worth of distance");
        check(SoundAcoustics.delay(0F) == 0F, "delay: zero distance is instant");

        float decay = 0.5F;
        float first = SoundAcoustics.reflectionGain(SoundFalloff.LINEAR, 10F, REF, MAX, ROLLOFF, 1, decay);
        float second = SoundAcoustics.reflectionGain(SoundFalloff.LINEAR, 10F, REF, MAX, ROLLOFF, 2, decay);
        float plain = SoundFalloff.LINEAR.gain(10F, REF, MAX, ROLLOFF);

        check(Math.abs(first - plain * decay) < EPSILON, "reflection: one bounce applies decay once");
        check(Math.abs(second - plain * decay * decay) < EPSILON, "reflection: two bounces apply decay twice");
        check(SoundAcoustics.reflectionGain(SoundFalloff.LINEAR, MAX * 2F, REF, MAX, ROLLOFF, 1, decay) == 0F,
            "reflection: beyond max distance is silent");
    }

    private static void reflectionBudgetKeepsStrongest() {
        int budget = 2;
        SoundVoice[] out = pool(8);
        SoundReflections.Surface[] surfaces = {
            surface(0F, -1F, 0F, 0F, 1F, 0F),
            surface(0F, -4F, 0F, 0F, 1F, 0F),
            surface(0F, -16F, 0F, 0F, 1F, 0F)
        };

        int count = SoundReflections.collect(
            0F, 0F, 0F, 0F, 1F, 0F,
            surfaces, surfaces.length,
            SoundFalloff.LINEAR, REF, MAX, ROLLOFF, 0F,
            1, 0.8F, 2F, 1F,
            out, budget);

        check(count == budget, "reflection budget: fills exactly to the cap, got " + count);
        check(out[0].gain >= out[1].gain, "reflection budget: results are sorted strongest first");

        /* The nearest surface makes the shortest path, so it must survive */
        float nearestPath = 2F * 1F + 1F;
        float expected = SoundAcoustics.reflectionGain(SoundFalloff.LINEAR, nearestPath, REF, MAX, ROLLOFF, 1, 0.8F);

        check(Math.abs(out[0].gain - expected) < EPSILON, "reflection budget: keeps the loudest reflection");

        for (int i = 0; i < count; i++) {
            check(out[i].order >= 1, "reflection: order is at least one");
            check(out[i].seconds <= 2F, "reflection: arrives no earlier than the direct sound offset");
            check(out[i].isAudible(), "reflection: kept voices are audible");
        }
    }

    private static void reflectionSkipsSurfacesBehindSource() {
        SoundVoice[] out = pool(4);
        /* Normal points away from the source, so the source sits behind it */
        SoundReflections.Surface[] surfaces = {surface(0F, 4F, 0F, 0F, 1F, 0F)};

        int count = SoundReflections.collect(
            0F, 0F, 0F, 0F, 1F, 0F,
            surfaces, surfaces.length,
            SoundFalloff.LINEAR, REF, MAX, ROLLOFF, 0F,
            2, 0.8F, 1F, 1F,
            out, 4);

        check(count == 0, "reflection: a surface the source sits behind reflects nothing, got " + count);

        int disabled = SoundReflections.collect(
            0F, 0F, 0F, 0F, 1F, 0F,
            new SoundReflections.Surface[]{surface(0F, -1F, 0F, 0F, 1F, 0F)}, 1,
            SoundFalloff.LINEAR, REF, MAX, ROLLOFF, 0F,
            0, 0.8F, 1F, 1F,
            out, 4);

        check(disabled == 0, "reflection: zero order produces no voices");
    }

    private static void reflectionCategoryMatrix() {
        int[] types = {SoundReflections.Surface.BLOCK, SoundReflections.Surface.ENTITY};

        for (boolean blockReflections : new boolean[]{false, true}) {
            for (boolean entityReflections : new boolean[]{false, true}) {
                for (boolean passThroughBlocks : new boolean[]{false, true}) {
                    for (boolean passThroughEntities : new boolean[]{false, true}) {
                        for (int type : types) {
                            boolean reflected = type == SoundReflections.Surface.BLOCK
                                ? blockReflections && !passThroughBlocks
                                : entityReflections && !passThroughEntities;
                            boolean blocked = type == SoundReflections.Surface.BLOCK
                                ? !passThroughBlocks
                                : !passThroughEntities;

                            check(SoundReflections.canReflect(type, blockReflections, entityReflections,
                                passThroughBlocks, passThroughEntities) == reflected,
                                "reflection matrix: category switch and pass-through precedence");
                            check(SoundReflections.blocksDirect(type, passThroughBlocks, passThroughEntities) == blocked,
                                "occlusion matrix: pass-through alone controls direct-path blocking");

                            SoundVoice[] out = pool(1);
                            SoundReflections.Surface candidate = surface(
                                0F, -1F, 0F, 0F, 1F, 0F, type);
                            int count = SoundReflections.collect(
                                0F, 0F, 0F, 0F, 1F, 0F,
                                new SoundReflections.Surface[]{candidate}, 1,
                                SoundFalloff.LINEAR, REF, MAX, ROLLOFF, 0F,
                                blockReflections, entityReflections,
                                passThroughBlocks, passThroughEntities,
                                1, 0.8F, 2F, 1F, out, 1);

                            check((count == 1) == reflected,
                                "reflection matrix: filtered category reaches voice collection");
                        }
                    }
                }
            }
        }
    }

    private static void soundKeyframeFactoriesRoundTripCopyAndInterpolate() {
        SoundKeyframeValue original = populatedSoundKeyframe();

        for (SoundKeyframeValue.Group group : SoundKeyframeValue.Group.values()) {
            SoundKeyframeFactory factory = new SoundKeyframeFactory(group);
            BaseType encoded = factory.toData(original);
            SoundKeyframeValue decoded = factory.fromData(encoded);
            SoundKeyframeValue copied = factory.copy(original);

            check(factory.compare(original, decoded), group + ": serialized value round-trips");
            check(copied != original && factory.compare(original, copied), group + ": copy preserves the group");

            if (group == SoundKeyframeValue.Group.SOUND) {
                check(copied.audio != original.audio && copied.audio.equals(original.audio),
                    "sound keyframe: copied audio link has independent identity");
            }
            else if (group == SoundKeyframeValue.Group.VISUALIZATION) {
                check(copied.guideColor != original.guideColor,
                    "visualization keyframe: copied color is independent");
            }
        }

        SoundKeyframeValue a = populatedSoundKeyframe();
        SoundKeyframeValue b = populatedSoundKeyframe();

        b.audio = Link.assets("audio/second.ogg");
        b.playing = false;
        b.volume = 3F;
        b.pitch = 2F;
        b.looping = true;
        b.startOffset = 6F;
        b.extent = 8F;
        b.innerAngle = 100F;
        b.outerAngle = 80F;
        b.outerGain = 0.8F;
        b.showGuide = false;
        b.guideColor = new Color(0F, 0F, 1F, 0.5F);
        b.falloff = SoundFalloff.LINEAR.id;
        b.refDistance = 6F;
        b.rolloff = 5F;
        b.airAbsorption = 0.9F;
        b.reflections = false;
        b.reflectionCount = 4;
        b.reflectionDecay = 0.8F;
        b.blockReflections = false;
        b.entityReflections = true;
        b.passThroughBlocks = true;
        b.passThroughEntities = false;

        SoundKeyframeValue sound = interpolate(SoundKeyframeValue.Group.SOUND, a, b);
        check(sound.audio.equals(a.audio) && sound.playing == a.playing && sound.looping == a.looping,
            "sound interpolation: resource and booleans use the left snapshot");
        check(close(sound.volume, 2F) && close(sound.pitch, 1.5F) && close(sound.startOffset, 4F),
            "sound interpolation: numeric values are linear");

        SoundKeyframeValue shape = interpolate(SoundKeyframeValue.Group.SHAPE, a, b);
        check(close(shape.extent, 6F) && close(shape.outerGain, 0.5F),
            "shape interpolation: extent and gain are linear");
        check(shape.innerAngle <= shape.outerAngle && close(shape.innerAngle, 60F),
            "shape interpolation: the inner cone remains clamped to the outer cone");

        SoundKeyframeValue visualization = interpolate(SoundKeyframeValue.Group.VISUALIZATION, a, b);
        check(visualization.showGuide == a.showGuide
                && close(visualization.guideColor.r, 0.5F)
                && close(visualization.guideColor.b, 0.5F),
            "visualization interpolation: toggle steps and color blends");

        SoundKeyframeValue falloff = interpolate(SoundKeyframeValue.Group.FALLOFF, a, b);
        check(falloff.falloff.equals(a.falloff) && close(falloff.refDistance, 4F)
                && close(falloff.rolloff, 3F) && close(falloff.airAbsorption, 0.5F),
            "falloff interpolation: model steps and numeric controls blend");

        SoundKeyframeValue reflections = interpolate(SoundKeyframeValue.Group.REFLECTIONS, a, b);
        check(reflections.reflections == a.reflections
                && reflections.blockReflections == a.blockReflections
                && reflections.entityReflections == a.entityReflections
                && reflections.passThroughBlocks == a.passThroughBlocks
                && reflections.passThroughEntities == a.passThroughEntities,
            "reflection interpolation: switches use the left snapshot");
        check(reflections.reflectionCount == 3 && close(reflections.reflectionDecay, 0.5F),
            "reflection interpolation: count and decay blend");
    }

    private static SoundKeyframeValue populatedSoundKeyframe() {
        SoundKeyframeValue value = new SoundKeyframeValue();

        value.audio = Link.assets("audio/first.ogg");
        value.playing = true;
        value.volume = 1F;
        value.pitch = 1F;
        value.looping = false;
        value.startOffset = 2F;
        value.extent = 4F;
        value.innerAngle = 20F;
        value.outerAngle = 40F;
        value.outerGain = 0.2F;
        value.showGuide = true;
        value.guideColor = new Color(1F, 0F, 0F, 1F);
        value.falloff = SoundFalloff.INVERSE.id;
        value.refDistance = 2F;
        value.rolloff = 1F;
        value.airAbsorption = 0.1F;
        value.reflections = true;
        value.reflectionCount = 1;
        value.reflectionDecay = 0.2F;
        value.blockReflections = true;
        value.entityReflections = false;
        value.passThroughBlocks = false;
        value.passThroughEntities = true;

        return value;
    }

    private static SoundKeyframeValue interpolate(SoundKeyframeValue.Group group,
        SoundKeyframeValue a, SoundKeyframeValue b) {
        return new SoundKeyframeFactory(group).interpolate(
            a, a, b, b, Interpolations.LINEAR, 0.5F).copy();
    }

    /**
     * The design rests on "declaring a Value* field is all it takes to get a
     * keyframe track". This pins that down for every field the sound forms
     * expose, so a future refactor to a plain float would fail here rather than
     * silently dropping a track from the timeline.
     *
     * <p>Checked by reflection rather than by constructing a form:
     * {@code Form}'s constructor reads {@code BBSSettings}, and a bare JVM test
     * must not risk pulling BBS static initialization in.</p>
     */
    private static void soundFormFieldsAreKeyframable() {
        Class<?> keyframable;
        Class<?> form;

        try {
            keyframable = Class.forName("mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue", false,
                SoundAcousticsTest.class.getClassLoader());
            form = Class.forName("mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm", false,
                SoundAcousticsTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("sound form classes must be on the test classpath", e);
        }

        String[] expected = {
            "audio", "playing", "volume", "pitch", "looping", "startOffset",
            "refDistance", "rolloff", "airAbsorption",
            "reflections", "reflectionCount", "reflectionDecay", "reflectionVoices",
            "blockReflections", "entityReflections", "passThroughBlocks", "passThroughEntities",
            "showGuide", "guideColor"
        };

        for (String name : expected) {
            java.lang.reflect.Field field;

            try {
                field = form.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                throw new AssertionError("AbstractSoundForm is missing the field " + name, e);
            }

            check(keyframable.isAssignableFrom(field.getType()),
                "AbstractSoundForm." + name + " must be keyframable, was " + field.getType().getSimpleName());
        }

        /* The falloff model is a Value* too, so it serializes, but it selects a
         * curve rather than carrying an animatable quantity */
        try {
            check(form.getDeclaredField("falloff") != null, "AbstractSoundForm.falloff exists");
        } catch (NoSuchFieldException e) {
            throw new AssertionError("AbstractSoundForm is missing the field falloff", e);
        }

        /* The shape supplies the cutoff distance; a standalone field here would
         * let the drawn boundary and the audible boundary drift apart */
        boolean hasMaxDistance = true;

        try {
            form.getDeclaredField("maxDistance");
        } catch (NoSuchFieldException e) {
            hasMaxDistance = false;
        }

        check(!hasMaxDistance, "AbstractSoundForm must not declare its own maxDistance field");
    }

    /* Cone geometry */

    private static void coneGeometryBasics() {
        check(SoundConeGeometry.capDistance(10F) == 10F, "cap distance is the range");
        check(SoundConeGeometry.capDistance(0F) == SoundConeGeometry.MIN_RANGE, "cap distance clamps degenerate ranges");
        check(SoundConeGeometry.capDistance(-5F) == SoundConeGeometry.MIN_RANGE, "cap distance clamps negative ranges");

        /* A 90 degree cone has a 45 degree half-angle, so the cap radius equals the height */
        float r = SoundConeGeometry.coneRadius(10F, 90F);

        check(Math.abs(r - 10F) < 1e-3F, "90 degree cone: cap radius equals height, got " + r);

        float narrow = SoundConeGeometry.coneRadius(10F, 30F);
        float wide = SoundConeGeometry.coneRadius(10F, 120F);

        check(narrow < wide, "wider angle gives a wider cap");
        check(narrow > 0F, "cap radius stays positive");
        check(Math.abs(SoundConeGeometry.angleForRadius(10F, r) - 90F) < 1e-3F,
            "drag inverse recovers the angle drawn by coneRadius");
        check(Math.abs(SoundConeGeometry.angleForRadius(10F, narrow) - 30F) < 1e-3F,
            "narrow drag inverse uses the same cone geometry");
        check(isFinite(SoundConeGeometry.coneRadius(10F, 179F)), "cap radius stays finite near 180 degrees");
        check(SoundConeGeometry.coneRadius(10F, 0F) == 0F, "zero angle collapses the cap to a point");

        check(Math.abs(SoundConeGeometry.cosHalfAngle(0F) - 1F) < EPSILON, "zero angle points straight down the axis");
        check(Math.abs(SoundConeGeometry.cosHalfAngle(180F)) < EPSILON, "180 degree cone reaches the perpendicular");

        check(SoundConeGeometry.clampInnerAngle(120F, 60F) == 60F, "inner angle clamps to the outer angle");
        check(SoundConeGeometry.clampInnerAngle(30F, 60F) == 30F, "a valid inner angle passes through");
    }

    /**
     * The visualization draws the cone from {@link SoundConeGeometry} while the
     * audio attenuates through {@link SoundAcoustics}. If those two disagreed,
     * the drawn boundary would not be where the sound actually stops — which is
     * the one thing the whole guide is supposed to promise.
     */
    private static void coneGeometryMatchesAcoustics() {
        float inner = 30F;
        float outer = 90F;
        float outerGain = 0.2F;

        /* Exactly on the drawn outer edge the gain must already be the outer gain */
        float atOuterEdge = SoundAcoustics.coneGain(SoundConeGeometry.cosHalfAngle(outer), inner, outer, outerGain);

        check(Math.abs(atOuterEdge - outerGain) < EPSILON,
            "at the drawn outer edge the gain must be the outer gain, got " + atOuterEdge);

        /* Exactly on the drawn inner ring the gain must still be full */
        float atInnerEdge = SoundAcoustics.coneGain(SoundConeGeometry.cosHalfAngle(inner), inner, outer, outerGain);

        check(Math.abs(atInnerEdge - 1F) < EPSILON,
            "at the drawn inner ring the gain must still be full, got " + atInnerEdge);

        /* Just inside the outer edge must be strictly louder than outside it */
        float justInside = SoundAcoustics.coneGain(SoundConeGeometry.cosHalfAngle(outer - 2F), inner, outer, outerGain);

        check(justInside > outerGain, "just inside the outer edge is louder than beyond it");

        /* The clamp both sides apply must agree */
        float clamped = SoundConeGeometry.clampInnerAngle(150F, 60F);
        float viaGeometry = SoundAcoustics.coneGain(SoundConeGeometry.cosHalfAngle(40F), clamped, 60F, outerGain);
        float viaAcoustics = SoundAcoustics.coneGain(SoundConeGeometry.cosHalfAngle(40F), 150F, 60F, outerGain);

        check(Math.abs(viaGeometry - viaAcoustics) < EPSILON,
            "geometry and acoustics must clamp the inner angle identically");
    }

    /* Helpers */

    private static SoundVoice[] pool(int size) {
        SoundVoice[] voices = new SoundVoice[size];

        for (int i = 0; i < size; i++) {
            voices[i] = new SoundVoice();
        }

        return voices;
    }

    private static SoundReflections.Surface surface(float px, float py, float pz, float nx, float ny, float nz) {
        return surface(px, py, pz, nx, ny, nz, SoundReflections.Surface.BLOCK);
    }

    private static SoundReflections.Surface surface(float px, float py, float pz,
        float nx, float ny, float nz, int type) {
        SoundReflections.Surface surface = new SoundReflections.Surface();

        surface.set(px, py, pz, nx, ny, nz, type);

        return surface;
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static float cosDeg(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static void check(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
