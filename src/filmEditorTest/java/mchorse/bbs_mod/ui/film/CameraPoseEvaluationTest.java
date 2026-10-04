package mchorse.bbs_mod.ui.film;

import io.netty.util.collection.IntObjectHashMap;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraPoseEvaluator;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.CameraPoseClip;
import mchorse.bbs_mod.camera.clips.CameraPosePolicy;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.camera.clips.misc.SubtitleClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.WorldFilmController;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.clips.Clips;

/** Runtime tests for camera sampling, recursive policies and the shared playback owner. */
public final class CameraPoseEvaluationTest
{
    private CameraPoseEvaluationTest()
    {}

    public static void runAll()
    {
        testRecursivePosePolicy();
        testPoseRollback();
        testFrameCache();
        testEditedEnd();
        testWorldPlaybackOwner();
    }

    private static void testRecursivePosePolicy()
    {
        CountingPoseClip pose = new CountingPoseClip();
        RecursivePoseClip recursive = new RecursivePoseClip();
        EffectClip effect = new EffectClip();
        ContractAudioClip audio = new ContractAudioClip();
        CurveClip curve = new CurveClip();
        SubtitleClip subtitle = new SubtitleClip();
        UntrustedIdleClip untrusted = new UntrustedIdleClip();
        Clips clips = new Clips("clips", BBSMod.getFactoryCameraClips());

        for (Clip clip : new Clip[] {pose, effect, audio, curve, subtitle, untrusted, recursive})
        {
            clip.duration.set(100);
            clips.addClip(clip);
        }

        recursive.layer.set(2);
        CameraClipContext context = new CameraClipContext().poseOnly(true);
        context.clips = clips;
        context.setup(5, 0.25F);
        context.entities.put(7, null);
        Position position = new Position();
        check(context.apply(recursive, position), "recursive pose clip did not run");
        check(position.point.x == 5.25D && position.point.y == 1D, "nested pose result was incorrect");
        check(pose.applies == 1 && effect.applies == 0 && audio.applies == 0, "recursive sampling executed effects");
        check(context.clipData.get("effectCalls", () -> new int[1])[0] == 0, "excluded clip wrote into clipData");
        check(!CameraPosePolicy.allows(curve) && !CameraPosePolicy.allows(subtitle), "curve or subtitle accepted as pose");
        check(!CameraPosePolicy.allows(untrusted) && CameraPosePolicy.allows(new OptInIdleClip()), "extension pose opt-in was ignored");
        check(!context.applyLast(audio, position) && audio.applies == 0, "endpoint bypassed the no-audio policy");
        context.shutdown();
        check(effect.shutdowns == 0 && recursive.shutdowns == 0, "pose shutdown invoked effectful clip teardown");
        check(context.entities.containsKey(7), "pose shutdown cleared the shared actor map");

        CameraClipContext full = new CameraClipContext();
        full.clips = clips;
        full.setup(5, 0F);
        check(full.apply(effect, new Position()) && effect.applies == 1, "legacy shared context lost extension effects");
        full.shutdown();
        check(effect.shutdowns == 1, "shared owner did not run extension teardown");
    }

    private static void testPoseRollback()
    {
        CameraClipContext context = new CameraClipContext().poseOnly(true);
        Position base = new Position(1, 2, 3, 4, 5, 6, 70);
        Position result = base.copy();
        InvalidPoseClip invalid = new InvalidPoseClip();
        context.resetPose(base);
        context.setup(4, 0.25F);
        check(!context.apply(invalid, result), "invalid pose was accepted");
        check(base.equals(result) && context.ticks == 4 && context.count == 0 && context.distance == 0D, "invalid pose leaked context state");
        invalid.throwFailure = true;

        try
        {
            context.apply(invalid, result);
            throw new AssertionError("pose exception was swallowed");
        }
        catch (IllegalStateException expected)
        {
            check(base.equals(result) && context.ticks == 4 && context.count == 0, "throwing pose did not roll back");
        }
    }

    private static void testFrameCache()
    {
        Film film = new Film();
        CameraTrack track = film.addCamera("Sampled", new Position());
        CountingPoseClip clip = new CountingPoseClip();
        CountingPoseClip legacy = new CountingPoseClip();
        clip.duration.set(100);
        legacy.duration.set(100);
        track.clips.addClip(clip);
        film.camera.addClip(legacy);
        film.setActiveCamera(track.cameraId.get());
        CameraPoseEvaluator evaluator = new CameraPoseEvaluator();
        evaluator.beginFrame(1L, film, 12, 0.25F, true, null);
        CameraClipContext shared = new CameraClipContext();
        shared.setup(12, 0.25F);
        Position legacyPose = new Position();
        shared.apply(legacy, legacyPose);
        evaluator.seedLegacyPose(legacyPose);
        evaluator.evaluate(Film.LEGACY_CAMERA_ID, new Position());
        evaluator.evaluate("missing-camera", new Position());
        check(legacy.applies == 1, "legacy shared pose was evaluated again for another view or invalid id");
        Position first = evaluator.evaluate(track.cameraId.get(), new Position());
        first.point.x = 999D;
        Position second = evaluator.evaluateOutput(new Position());
        check(second.point.x == 12.25D && clip.applies == 1, "cached pose was mutable or sampled twice");
        evaluator.beginFrame(1L, film, 12, 0.25F, true, null);
        evaluator.evaluate(track.cameraId.get(), new Position());
        check(clip.applies == 1, "identical frame key invalidated the cache");
        evaluator.beginFrame(1L, film, 12, 0.9F, false, null);
        check(evaluator.evaluateOutput(null).point.x == 12D && clip.applies == 2, "pause did not clear interpolation/cache");
        evaluator.beginFrame(1L, film, 12, 0.2F, false, null);
        evaluator.evaluateOutput(null);
        check(clip.applies == 2, "paused camera sampled again for irrelevant partial tick");
        evaluator.beginFrame(1L, film, 12, 0F, false, new IntObjectHashMap<>());
        evaluator.evaluateOutput(null);
        check(clip.applies == 3, "changed actor bindings retained a stale camera sample");
        evaluator.beginFrame(2L, film, 3, 0.5F, true, null);
        check(evaluator.evaluateOutput(null).point.x == 3.5D && clip.applies == 4, "backward seek reused old sample");
        evaluator.beginFrame(3L, null, 0, 0F, false, null);
        check(evaluator.evaluate(null, new Position(1, 0, 0, 0, 0)).point.x == 1D, "no-film fallback was ignored");
        check(evaluator.evaluate(null, new Position(2, 0, 0, 0, 0)).point.x == 2D, "different free views shared one cached fallback");
        evaluator.close();
    }

    private static void testEditedEnd()
    {
        Film film = new Film();
        CameraTrack track = film.addCamera("Endpoint", new Position(100, 0, 0, 0, 0));
        CountingPoseClip clip = new CountingPoseClip();
        clip.tick.set(2);
        clip.duration.set(3);
        track.clips.addClip(clip);
        CameraPoseEvaluator evaluator = new CameraPoseEvaluator();
        evaluator.beginFrame(1L, film, 5, 0F, false, null);
        Position result = evaluator.evaluateEditedEnd(track.cameraId.get(), clip, null);
        check(result.point.x == 5D && clip.applies == 1, "exclusive-end pose was not editable");
        check(evaluator.evaluate(track.cameraId.get(), null).point.x == 5D && clip.applies == 1, "endpoint sample was not shared");
        evaluator.beginFrame(2L, film, 5, 0F, true, null);
        check(evaluator.evaluateEditedEnd(track.cameraId.get(), clip, null).point.x == 100D, "playback incorrectly included exclusive-end clip");
        evaluator.close();
    }

    private static void testWorldPlaybackOwner()
    {
        Film film = new Film();
        CountingPoseClip legacy = new CountingPoseClip();
        EffectClip effect = new EffectClip();
        CameraTrack track = film.addCamera("Output", new Position());
        CountingPoseClip selected = new CountingPoseClip();
        legacy.duration.set(10);
        effect.duration.set(10);
        selected.duration.set(100);
        film.camera.addClip(legacy);
        film.camera.addClip(effect);
        track.clips.addClip(selected);
        film.setCameraCut(10, track.cameraId.get());
        WorldFilmController world = new WorldFilmController(film);
        PlayCameraController play = new PlayCameraController(world);
        BBSModClient.getCameraController().add(play);
        Object audioOwner = world.getCameraContext().getPlaybackOwner();
        Camera camera = new Camera();
        world.tick = 5;
        world.startRenderFrame(0.25F);
        play.setup(camera, 0.25F);
        play.setup(camera, 0.25F);
        check(camera.position.x == 5.25D && selected.applies == 1, "output camera was not cached at shared film time");
        check(legacy.applies == 1 && effect.applies == 1, "paired camera repeated legacy pose/effects");
        check(play.getContext() == world.getCameraContext() && play.getContext().clips == film.camera, "cuts replaced the shared audio/curve context");
        world.tick = 15;
        play.update();
        play.update();
        play.setup(camera, 0.25F);
        check(camera.position.x == 15.25D && world.tick == 15, "paired camera advanced a separate running clock");
        check(BBSModClient.getCameraController().has(play), "paired camera stopped at the shorter legacy duration");
        world.paused = true;
        world.startRenderFrame(0.75F);
        play.update();
        play.update();
        play.setup(camera, 0.75F);
        check(camera.position.x == 15D && selected.applies == 3, "paired camera advanced separately while paused");
        check(world.getCameraContext().getPlaybackOwner() == audioOwner, "camera cut changed soundtrack ownership");
        BBSModClient.getCameraController().remove(play);
        check(effect.shutdowns == 0, "visual controller shut down the shared effects owner");
        world.shutdown();
        check(effect.shutdowns == 1, "shared world owner did not shut down effects exactly once");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static class CountingPoseClip extends CameraClip implements CameraPoseClip
    {
        private int applies;

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            this.applies++;
            position.point.x = context.ticks + context.transition;
        }

        @Override
        protected Clip create()
        {
            return new CountingPoseClip();
        }
    }

    private static final class RecursivePoseClip extends CountingPoseClip
    {
        private int shutdowns;

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            context.applyUnderneath(context.ticks, context.transition, position, clip -> true);
            position.point.y += 1D;
        }

        @Override
        public void shutdown(ClipContext context)
        {
            this.shutdowns++;
        }
    }

    private static final class InvalidPoseClip extends CountingPoseClip
    {
        private boolean throwFailure;

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            position.point.x = Double.NaN;
            context.ticks = 999;
            context.count = 999;
            context.distance = 999D;

            if (this.throwFailure) throw new IllegalStateException("test pose failure");
        }
    }

    private static final class EffectClip extends CameraClip
    {
        private int applies;
        private int shutdowns;

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            this.applies++;
            context.clipData.get("effectCalls", () -> new int[1])[0]++;
        }

        @Override
        public void shutdown(ClipContext context)
        {
            this.shutdowns++;
        }

        @Override
        protected Clip create()
        {
            return new EffectClip();
        }
    }

    private static final class ContractAudioClip extends AudioClip implements CameraPoseClip
    {
        private int applies;

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            this.applies++;
        }
    }

    private static final class UntrustedIdleClip extends IdleClip
    {}

    private static final class OptInIdleClip extends IdleClip implements CameraPoseClip
    {}
}
