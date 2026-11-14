package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.film.markers.FilmMarker;
import mchorse.bbs_mod.film.markers.FilmMarkers;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.StableIds;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.forms.entities.EntityState;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.keyframes.factories.FloatKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.api.events.RegisterKeyframeFactoriesEvent;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;

/** Executes recorded pose counters and loop discontinuities through the playback consumer. */
public final class ReplayStateParityTest
{
    private ReplayStateParityTest()
    {}

    public static void runAll()
    {
        testRecordedStates();
        testFractionalPlayback();
        testRepeatedConstantBoundary();
        testStubFrameHistory();
        testStableMarkers();
        testFactorySetup();
    }

    private static void testRecordedStates()
    {
        ReplayKeyframes recording = new ReplayKeyframes("keyframes");
        StubEntity source = new StubEntity();

        for (EntityState state : EntityState.values())
        {
            state.set(source, true);
            check(state.get(source), "Stub dropped " + state.id);
        }

        source.setLeaningPitch(0.6F);
        source.setRoll(14);
        recording.record(6, source, null);
        ReplayKeyframes loaded = new ReplayKeyframes("keyframes");
        loaded.fromData(recording.toData());
        StubEntity target = new StubEntity();
        loaded.apply(6, target);

        for (EntityState state : EntityState.values())
        {
            check(state.get(target), "Playback lost " + state.id);
            check(ReplayKeyframes.CURATED_CHANNELS.contains(state.id), "Timeline omitted " + state.id);
        }

        check(ReplayKeyframes.CURATED_CHANNELS.contains("death"), "State catalog lost the FSR death channel");
        close(target.getLeaningPitch(1F), 0.6F, "Swim blend did not survive recording and load");
        check(target.getRoll() == 14, "Glide counter did not survive recording and load");
        check(target.getEntityPose() == Pose.FALL_FLYING, "Preview did not prioritize the recorded glide pose");
        target.setFallFlying(false);
        check(target.getEntityPose() == Pose.SWIMMING, "Preview ignored the recorded swimming pose");
        target.setSwimming(false);
        check(target.getEntityPose() == Pose.CROUCHING, "Preview ignored the recorded crouch pose");
        check(source.getId() != target.getId(), "Preview actors share their CEM variety id");
    }

    private static void testFractionalPlayback()
    {
        ReplayKeyframes frames = new ReplayKeyframes("keyframes");
        frames.x.insert(0F, 0D);
        frames.x.insert(10F, 20D);
        frames.leaning.insert(0F, 0D);
        frames.leaning.insert(10F, 1D);
        StubEntity entity = new StubEntity();
        frames.apply(2.5F, entity);

        close(entity.getX(), 5D, "Fractional playback truncated the current position");
        close(entity.getPrevX(), 3D, "Fractional playback truncated the previous position");
        close(entity.getLeaningPitch(1F), 0.25F, "Fractional playback truncated the swim blend");
    }

    private static void testRepeatedConstantBoundary()
    {
        ReplayKeyframes frames = new ReplayKeyframes("keyframes");
        frames.x.insert(0F, 0D);
        frames.x.insert(5F, 10D);
        frames.x.insert(10F, 20D);
        frames.x.get(0).getInterpolation().setInterp(Interpolations.CONST);
        frames.x.putLoop(new KeyframeLoop("motion", 0F, 10F, 30F));
        StubEntity entity = new StubEntity();
        frames.apply(15F, entity);

        close(entity.getX(), 10D, "Loop did not reach the repeated source key");
        close(entity.getPrevX(), 10D, "Repeated constant boundary smeared the jump over a render frame");
    }

    private static void testStubFrameHistory()
    {
        StubEntity entity = new StubEntity();
        entity.setLeaningPitch(0.2F);
        entity.setVelocity(2F, 4F, 6F);
        entity.setYaw(30F);
        entity.setPitch(10F);
        entity.update();
        entity.setLeaningPitch(0.8F);
        entity.setVelocity(6F, 8F, 10F);
        entity.setYaw(70F);
        entity.setPitch(30F);

        close(entity.getLeaningPitch(0.5F), 0.5F, "Preview swim blend has no previous frame");
        check(entity.lerpVelocity(0.5F).distanceTo(new Vec3(4D, 6D, 8D)) < 1E-6D,
            "Preview glide velocity has no previous frame");
        check(entity.getRotationVec(0.5F).distanceTo(Vec3.directionFromRotation(20F, 50F)) < 1E-6D,
            "Preview glide direction ignored the recorded orientation");
        check(entity.isTouchingWater(), "Preview swim blend cannot enter the water pose branch");
    }

    private static void testStableMarkers()
    {
        FilmMarkers markers = new FilmMarkers("markers");
        FilmMarker first = markers.addMarker(12);
        FilmMarker second = markers.addMarker(4);
        first.title.set("Turn");
        String firstId = first.getId();
        String secondId = second.getId();
        check(StableIds.isStableId(firstId) && !firstId.equals(secondId), "Marker insertion skipped stable identities");
        check(first.id.get().equals(firstId), "FSR marker identity facade differs from its path");
        DataPath title = new DataPath("markers/" + firstId + "/title");
        Collections.swap(markers.getAllTyped(), 0, 1);
        markers.remove((BaseValue) second);
        check(markers.findRecursively(title) == first.title, "Marker removal or reorder retargeted an undo path");
        check(markers.getAt(12) == first && markers.findNextTick(4) == 12 && markers.findPreviousTick(15) == 12,
            "Marker lookup or navigation no longer follows its authored tick");
        BaseType data = markers.toData();
        check(data.isList() && data.asList().get(0).asMap().getString("id").equals(firstId),
            "Stable-list migration changed the FSR marker save shape");
        FilmMarkers loaded = new FilmMarkers("markers");
        loaded.fromData(data);
        check(loaded.getById(firstId).title.get().equals("Turn"), "Marker load lost identity or content");
        check(loaded.getById(firstId).id.get().equals(firstId), "Marker load left the compatibility identity stale");
        FilmMarkers copied = new FilmMarkers("markers");
        copied.copy((BaseValueGroup) loaded);
        check(copied.getById(firstId).title.get().equals("Turn"), "Marker group copy lost its fields");
        FilmMarker duplicate = new FilmMarker(firstId);
        copied.add(duplicate);
        check(!duplicate.getId().equals(firstId) && duplicate.id.get().equals(duplicate.getId()),
            "Duplicating a marker reused or desynchronized its identity");
        loaded.removeMarker(loaded.getById(firstId));
        check(loaded.getList().isEmpty(), "FSR removeMarker facade retained its list entry");
    }

    private static void testFactorySetup()
    {
        IKeyframeFactory<?> original = KeyframeFactories.get("float");
        FloatKeyframeFactory replacement = new FloatKeyframeFactory();
        String key = "tracktest:scalar";

        try
        {
            RegisterKeyframeFactoriesEvent event = new RegisterKeyframeFactoriesEvent();
            event.register("float", replacement);
            event.register(key, replacement);
            KeyframeFactories.setup();
            KeyframeFactories.setup();
            check(KeyframeFactories.get("float") == replacement && KeyframeFactories.get(key) == replacement,
                "Repeated setup overwrote an addon registration");
            KeyframeChannel<Float> channel = new KeyframeChannel<>("custom", replacement);
            channel.insert(2F, 8F);
            KeyframeChannel<Float> loaded = new KeyframeChannel<>("custom", KeyframeFactories.FLOAT);
            loaded.fromData(channel.toData());
            check(loaded.getFactory() == replacement && loaded.interpolate(2F) == 8F,
                "Addon factory registered after setup cannot round-trip its channel");
        }
        finally
        {
            KeyframeFactories.register("float", original);
            KeyframeFactories.unregister(key);
        }
    }

    private static void close(double actual, double expected, String message)
    {
        check(Math.abs(actual - expected) < 1E-5D, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
