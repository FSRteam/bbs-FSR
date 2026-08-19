package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import mchorse.bbs_mod.utils.clips.Clips;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import java.util.List;
import java.util.Map;

public final class FilmPlaybackPolicyTest
{
    public static void main(String[] args)
    {
        bootstrapStandaloneMinecraftRuntime();
        runAll();

        System.out.println("FilmPlaybackPolicyTest passed");
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    public static void runAll()
    {
        testReplayAndActionClipCaps();
        testFirstPersonDisplayMutationUsesReplayIdentity();
        testClipTimelineBounds();
        testTransformBounds();
        testSeekWorkIsWeightedAndSaturating();
        testCameraAndDangerousActionBudgets();
        testCameraGuardRollsBackInvalidAndThrowingClips();
        testNestedCameraFailureRollsBackContextState();
        testNestedClipScopeRestoredAfterFailure();
    }

    private static void testFirstPersonDisplayMutationUsesReplayIdentity()
    {
        Film film = new Film();
        Replay first = film.replays.addReplay();
        Replay second = film.replays.addReplay();

        first.fp.set(true);
        second.fp.set(true);

        Replay previous = FilmPlaybackPolicy.findEnabledFirstPersonReplay(film);

        first.enabled.set(false);

        Replay next = FilmPlaybackPolicy.findEnabledFirstPersonReplay(film);

        check(previous == first && next == second,
            "the first-person replay switch fixture did not preserve exact replay identities");
        check(FilmPlaybackPolicy.affectsFirstPersonDisplay(film, previous, next, first.enabled),
            "switching to a distinct first-person replay identity did not refresh the player display");

        first.enabled.set(true);

        check(FilmPlaybackPolicy.affectsFirstPersonDisplay(film, first, first, first.form),
            "editing the active first-person form did not refresh the player display");
        check(FilmPlaybackPolicy.affectsFirstPersonDisplay(film, first, first, first),
            "overwriting the active first-person replay did not refresh the player display");
        check(FilmPlaybackPolicy.affectsFirstPersonDisplay(film, first, first, film.hp),
            "editing an allowed first-person player setting did not refresh the player display");
        check(FilmPlaybackPolicy.affectsFirstPersonDisplay(film, first, first, first.keyframes.hotbar.get(0)),
            "editing the first-person hotbar did not refresh the real player's projection");
        check(!FilmPlaybackPolicy.affectsFirstPersonDisplay(film, first, first, second.form),
            "editing an inactive replay form refreshed the real player's display");
        check(!FilmPlaybackPolicy.affectsFirstPersonDisplay(film, first, null, first.enabled),
            "removing first-person state requested a refresh instead of normal restoration");
    }

    private static void testReplayAndActionClipCaps()
    {
        check(FilmPlaybackPolicy.isReplayCountAllowed(FilmPlaybackPolicy.MAX_REPLAY_ENTRIES), "the exact replay cap was rejected");
        check(!FilmPlaybackPolicy.isReplayCountAllowed(FilmPlaybackPolicy.MAX_REPLAY_ENTRIES + 1), "a film above the replay cap was accepted");
        check(FilmPlaybackPolicy.isRuntimeReplayCountAllowed(FilmPlaybackPolicy.MAX_RUNTIME_REPLAYS), "the exact runtime replay cap was rejected");
        check(!FilmPlaybackPolicy.isRuntimeReplayCountAllowed(FilmPlaybackPolicy.MAX_RUNTIME_REPLAYS + 1), "a film above the runtime replay cap was accepted");
        check(FilmPlaybackPolicy.isRuntimeActorCountAllowed(FilmPlaybackPolicy.MAX_RUNTIME_ACTORS), "the exact runtime actor cap was rejected");
        check(!FilmPlaybackPolicy.isRuntimeActorCountAllowed(FilmPlaybackPolicy.MAX_RUNTIME_ACTORS + 1), "a film above the runtime actor cap was accepted");
        check(FilmPlaybackPolicy.isTotalActionClipCountAllowed(FilmPlaybackPolicy.MAX_TOTAL_ACTION_CLIPS), "the exact action-clip cap was rejected");
        check(!FilmPlaybackPolicy.isTotalActionClipCountAllowed((long) FilmPlaybackPolicy.MAX_TOTAL_ACTION_CLIPS + 1L), "a film above the action-clip cap was accepted");

        int[] overlappingTicks = new int[FilmPlaybackPolicy.MAX_SIMULTANEOUS_ACTION_CLIPS + 1];
        int[] overlappingDurations = new int[overlappingTicks.length];

        for (int i = 0; i < overlappingDurations.length; i++)
        {
            overlappingDurations[i] = 20;
        }

        check(!FilmPlaybackPolicy.isActionScheduleAllowed(overlappingTicks, overlappingDurations),
            "a worst-case action schedule above the simultaneous execution cap was accepted");

        int[] boundedTicks = new int[FilmPlaybackPolicy.MAX_SIMULTANEOUS_ACTION_CLIPS];
        int[] boundedDurations = new int[boundedTicks.length];

        for (int i = 0; i < boundedDurations.length; i++)
        {
            boundedDurations[i] = 20;
        }

        check(FilmPlaybackPolicy.isActionScheduleAllowed(boundedTicks, boundedDurations),
            "the exact simultaneous action execution cap was rejected");
    }

    private static void testClipTimelineBounds()
    {
        check(FilmPlaybackPolicy.isClipRangeAllowed(0, 1), "an ordinary clip range was rejected");
        check(FilmPlaybackPolicy.isClipRangeAllowed(0, FilmPlaybackPolicy.MAX_FILM_DURATION_TICKS), "the exact film duration cap was rejected");
        check(FilmPlaybackPolicy.isClipRangeAllowed(FilmPlaybackPolicy.MAX_FILM_DURATION_TICKS - 1, 1), "a clip ending at the duration cap was rejected");
        check(!FilmPlaybackPolicy.isClipRangeAllowed(-1, 1), "a negative clip tick was accepted");
        check(!FilmPlaybackPolicy.isClipRangeAllowed(0, 0), "a zero clip duration was accepted");
        check(!FilmPlaybackPolicy.isClipRangeAllowed(0, -1), "a negative clip duration was accepted");
        check(!FilmPlaybackPolicy.isClipRangeAllowed(FilmPlaybackPolicy.MAX_FILM_DURATION_TICKS, 1), "a clip ending beyond the duration cap was accepted");
        check(!FilmPlaybackPolicy.isClipRangeAllowed(Integer.MAX_VALUE, Integer.MAX_VALUE), "an overflowing clip range was accepted");
    }

    private static void testTransformBounds()
    {
        check(FilmPlaybackPolicy.isPositionAllowed(0D, 64D, 0D), "an ordinary replay position was rejected");
        check(FilmPlaybackPolicy.isPositionAllowed(
            FilmPlaybackPolicy.MIN_HORIZONTAL_POSITION,
            FilmPlaybackPolicy.MIN_VERTICAL_POSITION,
            FilmPlaybackPolicy.MIN_HORIZONTAL_POSITION
        ), "the inclusive lower world bounds were rejected");
        check(!FilmPlaybackPolicy.isPositionAllowed(FilmPlaybackPolicy.MAX_HORIZONTAL_POSITION, 0D, 0D), "the exclusive horizontal world bound was accepted");
        check(!FilmPlaybackPolicy.isPositionAllowed(0D, FilmPlaybackPolicy.MAX_VERTICAL_POSITION, 0D), "the exclusive vertical world bound was accepted");
        check(!FilmPlaybackPolicy.isPositionAllowed(Double.NaN, 0D, 0D), "a NaN replay position was accepted");
        check(!FilmPlaybackPolicy.isPositionAllowed(Double.POSITIVE_INFINITY, 0D, 0D), "an infinite replay position was accepted");
        check(!FilmPlaybackPolicy.isPositionAllowed(Double.MAX_VALUE, 0D, 0D), "a Double.MAX replay position was accepted");

        check(FilmPlaybackPolicy.isRotationAllowed(1_080D), "a finite multi-turn replay rotation was rejected");
        check(!FilmPlaybackPolicy.isRotationAllowed(Double.NaN), "a NaN replay rotation was accepted");
        check(!FilmPlaybackPolicy.isRotationAllowed(Double.NEGATIVE_INFINITY), "an infinite replay rotation was accepted");
        check(!FilmPlaybackPolicy.isRotationAllowed(Double.MAX_VALUE), "a Double.MAX replay rotation was accepted");

        check(FilmPlaybackPolicy.isCameraFovAllowed(70D), "an ordinary camera FOV was rejected");
        check(!FilmPlaybackPolicy.isCameraFovAllowed(0D), "a zero camera FOV was accepted");
        check(!FilmPlaybackPolicy.isCameraFovAllowed(180D), "a singular camera FOV was accepted");
        check(!FilmPlaybackPolicy.isCameraFovAllowed(Double.NaN), "a NaN camera FOV was accepted");
        check(!FilmPlaybackPolicy.isCameraFovAllowed(Double.MAX_VALUE), "a Double.MAX camera FOV was accepted");

        Position camera = new Position(0F, 64F, 0F, 0F, 0F, 0F, 70F);

        check(FilmPlaybackPolicy.isCameraPoseAllowed(camera), "an ordinary camera pose was rejected");
        camera.point.x = Double.NaN;
        check(!FilmPlaybackPolicy.isCameraPoseAllowed(camera), "a camera pose with NaN position was accepted");

        check(FilmPlaybackPolicy.isVelocityAllowed(1D, -0.0784D, 1D), "an ordinary replay velocity was rejected");
        check(!FilmPlaybackPolicy.isVelocityAllowed(Double.NaN, 0D, 0D), "a NaN replay velocity was accepted");
        check(!FilmPlaybackPolicy.isVelocityAllowed(Double.POSITIVE_INFINITY, 0D, 0D), "an infinite replay velocity was accepted");
        check(!FilmPlaybackPolicy.isVelocityAllowed(Double.MAX_VALUE, 0D, 0D), "a Double.MAX replay velocity was accepted");
        check(FilmPlaybackPolicy.isVelocityAllowed(FilmPlaybackPolicy.MAX_REPLAY_VELOCITY, 0D, 0D), "the exact replay velocity cap was rejected");
        check(!FilmPlaybackPolicy.isVelocityAllowed(FilmPlaybackPolicy.MAX_REPLAY_VELOCITY + 1D, 0D, 0D), "an unsafe replay velocity was accepted");
        check(FilmPlaybackPolicy.isFallDistanceAllowed(FilmPlaybackPolicy.MAX_FALL_DISTANCE), "the exact fall-distance cap was rejected");
        check(!FilmPlaybackPolicy.isFallDistanceAllowed(Float.MAX_VALUE), "an unsafe fall distance was accepted");
    }

    private static void testSeekWorkIsWeightedAndSaturating()
    {
        check(FilmPlaybackPolicy.estimateSeekWork(10L, 2, 3L) == 6L, "destination-only seek work was multiplied by timeline distance");
        check(FilmPlaybackPolicy.estimateSeekWork(0L, FilmPlaybackPolicy.MAX_REPLAY_ENTRIES, FilmPlaybackPolicy.MAX_TOTAL_ACTION_CLIPS) == 0L,
            "a zero-step seek consumed work");
        check(FilmPlaybackPolicy.estimateSeekWork(-1L, 0, 0L) == Long.MAX_VALUE, "negative seek work did not fail closed");
        check(FilmPlaybackPolicy.estimateSeekWork(Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE) == Long.MAX_VALUE,
            "seek work overflow did not saturate");
    }

    private static void testCameraAndDangerousActionBudgets()
    {
        int[] cameraTicks = new int[FilmPlaybackPolicy.MAX_SIMULTANEOUS_CAMERA_CLIPS + 1];
        int[] cameraDurations = new int[cameraTicks.length];

        java.util.Arrays.fill(cameraDurations, 20);

        check(!FilmPlaybackPolicy.isCameraScheduleAllowed(cameraTicks, cameraDurations), "overlapping camera clips exceeded no runtime cap");
        check(FilmPlaybackPolicy.isBlockPositionAllowed(0, 64, 0), "ordinary block action position was rejected");
        check(!FilmPlaybackPolicy.isBlockPositionAllowed(Integer.MAX_VALUE, 64, 0), "out-of-world block action position was accepted");
        check(FilmPlaybackPolicy.isItemDropInputAllowed(1D, 2D, 3D, 0.1D, 0.2D, 0.3D, true), "ordinary relative item drop was rejected");
        check(!FilmPlaybackPolicy.isItemDropInputAllowed(FilmPlaybackPolicy.MAX_RELATIVE_ACTION_OFFSET + 1D, 0D, 0D, 0D, 0D, 0D, true),
            "unbounded relative item drop offset was accepted");
        check(!FilmPlaybackPolicy.isItemDropInputAllowed(0D, 64D, 0D, Double.MAX_VALUE, 0D, 0D, false),
            "unbounded item drop velocity was accepted");
        check(FilmPlaybackPolicy.isDamageAllowed(2F), "ordinary damage action was rejected");
        check(!FilmPlaybackPolicy.isDamageAllowed(Float.NaN), "NaN damage action was accepted");
        check(!FilmPlaybackPolicy.isDamageAllowed(FilmPlaybackPolicy.MAX_ACTION_DAMAGE + 1F), "unbounded damage action was accepted");
        check(FilmPlaybackPolicy.isChatActionAllowed("hello", 0), "ordinary chat action was rejected");
        check(!FilmPlaybackPolicy.isChatActionAllowed("x".repeat(FilmPlaybackPolicy.MAX_CHAT_MESSAGE_LENGTH + 1), 0), "oversized chat action was accepted");
        check(!FilmPlaybackPolicy.isCommandActionAllowed("say a\nsay b", 0), "multi-line command action was accepted");
        check(!FilmPlaybackPolicy.isCommandActionAllowed("say loop", 1), "per-tick repeating command action was accepted");
    }

    private static void testCameraGuardRollsBackInvalidAndThrowingClips()
    {
        CameraClipContext context = new CameraClipContext();
        Position initial = new Position(1F, 64F, 2F, 10F, 20F, 0F, 70F);
        Position position = initial.copy();

        context.setup(0, 0F);
        check(!context.apply(new InvalidCameraClip(false), position), "an invalid camera output was reported as applied");
        check(position.equals(initial), "an invalid camera output escaped the final guard");

        position.copy(initial);
        context.clips = new Clips("test", null);
        context.clips.addClip(new InvalidCameraClip(true));
        context.setup(0, 0, 0F, 1);
        context.captureSnapshots();

        try
        {
            context.applyUnderneath(0, 0F, position);
            throw new AssertionError("a throwing camera clip did not propagate its failure");
        }
        catch (ExpectedCameraFailure ignored)
        {}

        check(position.equals(initial), "a throwing camera clip leaked its partial pose");

        CameraClip valid = new PassthroughCameraClip();

        check(context.apply(valid, position), "a normal camera clip failed after a throwing clip");
        check(context.getSnapshots().containsKey(valid), "camera snapshot capture stayed disabled after an exception");
    }

    private static void testNestedClipScopeRestoredAfterFailure()
    {
        ThrowingClipContext context = new ThrowingClipContext();

        context.clips = new Clips("test", null);
        context.clips.addClip(new PassthroughClip());
        context.setup(12, 7, 0.25F, 1);

        try
        {
            context.applyUnderneath(0, 0.75F, new Object());
            throw new AssertionError("a throwing nested clip did not propagate its failure");
        }
        catch (ExpectedCameraFailure ignored)
        {}

        check(context.currentLayer == 1, "a throwing nested clip leaked its layer scope");
        check(context.ticks == 12, "a throwing nested clip leaked its timeline tick");
        check(context.relativeTick == 7, "a throwing nested clip leaked its relative tick");
        check(context.transition == 0.25F, "a throwing nested clip leaked its transition");
    }

    private static void testNestedCameraFailureRollsBackContextState()
    {
        CameraClipContext context = new CameraClipContext();
        Position initial = new Position(3F, 70F, 4F, 15F, 5F, 0F, 70F);
        Position position = initial.copy();

        context.clips = new Clips("test", null);
        context.clips.addClip(new OffsetCameraClip());
        context.setup(0, 0, 0F, 1);
        context.captureSnapshots();

        try
        {
            context.apply(new NestedThrowingCameraClip(), position);
            throw new AssertionError("a throwing outer camera clip did not propagate its failure");
        }
        catch (ExpectedCameraFailure ignored)
        {}

        check(position.equals(initial), "a throwing outer camera clip leaked its nested pose");
        check(context.count == 0, "a throwing outer camera clip leaked its nested apply count");
        check(context.distance == 0D, "a throwing outer camera clip leaked its nested distance");
        check(context.velocity == 0D, "a throwing outer camera clip leaked its nested velocity");
        check(context.getSnapshots().isEmpty(), "a throwing outer camera clip leaked nested snapshots");

        position.point.x = Double.NaN;
        check(!context.apply(new InvalidCameraClip(false), position), "an invalid follow-up camera clip was reported as applied");
        check(position.equals(initial), "a throwing outer camera clip leaked lastPosition into the next fallback");

        CameraClip valid = new PassthroughCameraClip();

        check(context.apply(valid, position), "a normal camera clip failed after nested rollback");
        check(context.getSnapshots().containsKey(valid), "snapshot capture was not restored after nested rollback");
    }

    private static final class InvalidCameraClip extends CameraClip
    {
        private final boolean fail;

        private InvalidCameraClip(boolean fail)
        {
            this.fail = fail;
        }

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            position.point.x = Double.NaN;

            if (this.fail)
            {
                throw new ExpectedCameraFailure();
            }
        }

        @Override
        protected Clip create()
        {
            return new InvalidCameraClip(this.fail);
        }
    }

    private static final class ExpectedCameraFailure extends RuntimeException
    {}

    private static final class ThrowingClipContext extends ClipContext<Clip, Object>
    {
        @Override
        public boolean apply(Clip clip, Object position)
        {
            this.currentLayer = 0;
            this.ticks = 1;
            this.relativeTick = 2;
            this.transition = 0.5F;

            throw new ExpectedCameraFailure();
        }
    }

    private static final class PassthroughClip extends Clip
    {
        @Override
        protected Clip create()
        {
            return new PassthroughClip();
        }
    }

    private static final class PassthroughCameraClip extends CameraClip
    {
        @Override
        protected void applyClip(ClipContext context, Position position)
        {}

        @Override
        protected Clip create()
        {
            return new PassthroughCameraClip();
        }
    }

    private static final class OffsetCameraClip extends CameraClip
    {
        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            position.point.x += 5D;
        }

        @Override
        protected Clip create()
        {
            return new OffsetCameraClip();
        }
    }

    private static final class NestedThrowingCameraClip extends CameraClip
    {
        private NestedThrowingCameraClip()
        {
            this.layer.set(1);
        }

        @Override
        protected void applyClip(ClipContext context, Position position)
        {
            ((CameraClipContext) context).applyUnderneath(0, 0F, position);

            throw new ExpectedCameraFailure();
        }

        @Override
        protected Clip create()
        {
            return new NestedThrowingCameraClip();
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
