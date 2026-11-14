package mchorse.bbs_mod.ui.framework.elements.input.keyframes;

import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.client.ui.mirror.TestRemoteInput;
import mchorse.bbs_mod.cubic.CubicModelAnimator;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.DoubleType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.interps.AutoBezier;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.BezierUtils;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Set;

/** Executes time warping, loop seams, model animation and the real handle gesture path. */
public final class KeyframeMotionShiftTest
{
    public static void runAll()
    {
        testDataAndPlayback();
        testSplineSeamsAndLoops();
        testModelAnimation();
        testPoseInterpolationPreservesSources();
        testTransformInterpolationUsesKeyTimes();
        testVectorInterpolationUsesKeyTimes();
        testNumericInterpolationUsesKeyTimesAndHandles(KeyframeFactories.FLOAT);
        testNumericInterpolationUsesKeyTimesAndHandles(KeyframeFactories.DOUBLE);
        testNumericInterpolationUsesKeyTimesAndHandles(KeyframeFactories.INTEGER);
        testNumericInterpolationUsesKeyTimesAndHandles(KeyframeFactories.LONG);
        testHandleGestures();
    }

    private static void testTransformInterpolationUsesKeyTimes()
    {
        float[] times = {0F, 3F, 17F, 60F};
        float[] positions = {-4F, 2F, 10F, 11F};
        for (IInterp interpolation : new IInterp[]{Interpolations.AUTO, Interpolations.AUTO_CLAMPED})
        {
            KeyframeChannel<Transform> transforms = new KeyframeChannel<>("transform", KeyframeFactories.TRANSFORM);
            KeyframeChannel<PoseTransform> bones = new KeyframeChannel<>("bone", KeyframeFactories.POSE_TRANSFORM);
            for (int i = 0; i < times.length; i++)
            {
                Transform transform = new Transform();
                transform.translate.x = positions[i];
                PoseTransform bone = new PoseTransform();
                bone.translate.x = positions[i];
                bone.lighting = positions[i];
                bone.glintSpeed = positions[i];
                transforms.get(transforms.insert(times[i], transform)).getInterpolation().setInterp(interpolation);
                bones.get(bones.insert(times[i], bone)).getInterpolation().setInterp(interpolation);
            }
            BaseType transformData = transforms.toData().copy();
            BaseType boneData = bones.toData().copy();
            float transition = (9F - times[1]) / (times[2] - times[1]);
            float expected = (float) AutoBezier.get(positions[0], positions[1], positions[2], positions[3],
                times[0], times[1], times[2], times[3], interpolation.has(Interpolations.AUTO_CLAMPED), transition);
            near(transforms.interpolate(9F).translate.x, expected, 1.0E-5F,
                "Transform AutoBezier ignored the neighboring key times");
            PoseTransform result = bones.interpolate(9F);
            near(result.translate.x, expected, 1.0E-5F, "Bone AutoBezier ignored the neighboring key times");
            near(result.lighting, expected, 1.0E-5F, "Bone material AutoBezier used a different time curve");
            near(result.glintSpeed, expected, 1.0E-5F, "FSR glint AutoBezier used a different time curve");
            check(transformData.equals(transforms.toData()) && boneData.equals(bones.toData()),
                "Transform interpolation mutated its stored control keys");
        }
    }

    private static void testVectorInterpolationUsesKeyTimes()
    {
        float[] times = {0F, 3F, 17F, 60F};
        Vector3f[] values = {
            new Vector3f(-40F, 12F, -3F), new Vector3f(20F, 4F, 8F),
            new Vector3f(100F, 15F, 1F), new Vector3f(110F, -2F, -5F)
        };

        for (IKeyframeFactory<Vector3f> factory : List.of(KeyframeFactories.VECTOR3F, KeyframeFactories.VECTOR3F_SCALE))
        {
            KeyframeChannel<Vector3f> channel = new KeyframeChannel<>("vector", factory);
            for (int i = 0; i < times.length; i++) channel.insert(times[i], new Vector3f(values[i]));

            for (IInterp interpolation : new IInterp[]{Interpolations.AUTO, Interpolations.AUTO_CLAMPED})
            {
                channel.get(1).getInterpolation().setInterp(interpolation);
                BaseType snapshot = channel.toData().copy();
                for (float tick : new float[]{4F, 9F, 16F})
                {
                    Vector3f actual = channel.interpolate(tick);
                    float progress = (tick - times[1]) / (times[2] - times[1]);
                    for (int axis = 0; axis < 3; axis++)
                    {
                        double expected = AutoBezier.get(values[0].get(axis), values[1].get(axis), values[2].get(axis), values[3].get(axis),
                            times[0], times[1], times[2], times[3], interpolation.has(Interpolations.AUTO_CLAMPED), progress);
                        near(actual.get(axis), expected, 1.0E-5D, "Vector AutoBezier ignored the neighboring key times on axis " + axis);
                    }
                }
                check(snapshot.equals(channel.toData()), "Vector interpolation mutated its stored keys");
            }
        }
    }

    private static <T extends Number> void testNumericInterpolationUsesKeyTimesAndHandles(IKeyframeFactory<T> factory)
    {
        float[] times = {0F, 3F, 17F, 60F};
        double[] values = {-40D, 20D, 100D, 110D};
        KeyframeChannel<T> channel = new KeyframeChannel<>("numeric", factory);

        for (int i = 0; i < times.length; i++)
        {
            channel.insert(times[i], factory.fromData(new DoubleType(values[i])));
        }

        for (IInterp interpolation : new IInterp[]{Interpolations.AUTO, Interpolations.AUTO_CLAMPED})
        {
            channel.get(1).getInterpolation().setInterp(interpolation);

            for (float tick : new float[]{4F, 9F, 16F})
            {
                float progress = (tick - times[1]) / (times[2] - times[1]);
                double expected = AutoBezier.get(values[0], values[1], values[2], values[3],
                    times[0], times[1], times[2], times[3], interpolation.has(Interpolations.AUTO_CLAMPED), progress);
                double typedExpected = factory.fromData(new DoubleType(expected)).doubleValue();

                check(Math.abs(channel.interpolate(tick).doubleValue() - typedExpected) < 1.0E-5D,
                    factory.getClass().getSimpleName() + " AutoBezier ignored the neighboring key times");
            }
        }

        Keyframe<T> a = channel.get(1);
        Keyframe<T> b = channel.get(2);
        a.getInterpolation().setInterp(Interpolations.BEZIER);
        a.rx = 4F;
        a.ry = 28F;
        a.lx = 2F;
        a.ly = -80F;
        b.lx = 9F;
        b.ly = -3F;
        BaseType snapshot = channel.toData().copy();
        float progress = (9F - times[1]) / (times[2] - times[1]);
        double expected = BezierUtils.get(values[1], values[2], times[1], times[2], a.rx, a.ry, b.lx, b.ly, progress);
        double typedExpected = factory.fromData(new DoubleType(expected)).doubleValue();

        check(Math.abs(channel.interpolate(9F).doubleValue() - typedExpected) < 1.0E-5D,
            factory.getClass().getSimpleName() + " Bezier ignored the destination key's left handle");
        check(snapshot.equals(channel.toData()), "Numeric interpolation mutated its stored keys");
    }

    private static void testDataAndPlayback()
    {
        KeyframeChannel<Float> channel = numeric(0F, 10F);
        Keyframe<Float> first = channel.get(0);
        int originalHash = first.contentHash();
        first.setMotionShift(0.2F);
        near(channel.interpolate(7F), 5F, 0.0001F, "Shifted transition midpoint");
        near(channel.interpolate(0F), 0F, 0F, "Start endpoint moved");
        near(channel.interpolate(10F), 10F, 0F, "End endpoint moved");
        check(first.contentHash() != originalHash, "Shift missing from signature");
        Keyframe<Float> copy = new Keyframe<>("", KeyframeFactories.FLOAT);
        copy.fromData(first.toData());
        check(copy.equals(first), "Motion shift did not survive serialization");
        copy.setMotionShift(-0.1F);
        check(!copy.equals(first), "Motion shift missing from undo equality");
        copy.copy(first);
        check(copy.equals(first), "Copy lost motion shift");
        copy.setMotionShift(Float.NaN);
        near(copy.getMotionShift(), 0F, 0F, "Nonfinite shift was not reset");
        copy.setMotionShift(4F);
        near(copy.getMotionShift(), 0.49F, 0F, "Shift upper bound");
        copy.setMotionShift(-4F);
        near(copy.getMotionShift(), -0.49F, 0F, "Shift lower bound");
        first.getInterpolation().setInterp(Interpolations.CONST);
        near(first.remapMotion(0.7F), 0.7F, 0F, "Constant transition was warped");
        Keyframe<Boolean> stepped = new Keyframe<>("", KeyframeFactories.BOOLEAN);
        stepped.getInterpolation().setInterp(Interpolations.LINEAR);
        stepped.setMotionShift(0.2F);
        check(!stepped.supportsMotionShift(), "Stepped factory gained a motion handle");
        first.getInterpolation().setInterp(Interpolations.LINEAR);
        first.setDuration(5F);
        near(channel.interpolate(3.5F), 5F, 0.0001F, "Forced duration midpoint");
        near(channel.interpolate(8F), 10F, 0F, "Forced duration hold");
        int handleHash = first.contentHash();
        first.setRx(1, 7F);
        check(first.contentHash() != handleHash, "FSR per-axis handles missing from signature");
    }

    private static void testSplineSeamsAndLoops()
    {
        KeyframeChannel<Float> channel = numeric(0F, 10F, 20F, 80F, 90F);
        channel.get(0).setMotionShift(-0.2F);
        channel.get(1).setMotionShift(0.3F);
        channel.get(2).setMotionShift(0.1F);
        for (Keyframe<Float> key : channel.getKeyframes()) key.getInterpolation().setInterp(Interpolations.CUBIC);
        channel.putLoop(new KeyframeLoop("repeat", 0F, 20F, 80F));
        KeyframeSegment<Float> ending = channel.find(19F);
        check(ending.a == channel.get(1) && ending.b == channel.get(2), "Loop copied source interval keys");
        check(ending.nextStart.getMotionShift() == channel.get(0).getMotionShift(), "Seam uses endpoint shift instead of outgoing source shift");
        KeyframeSegment<Float> beginning = channel.find(20.1F);
        check(beginning.a == channel.get(0) && beginning.timeOffset == 20F, "Repeated interval lost source identity/time offset");
        float epsilon = 0.001F;
        ending.x = 1F - epsilon;
        beginning.x = epsilon;
        float leftSlope = (1F - ending.getInterpolationProgress()) / epsilon;
        float rightSlope = beginning.getInterpolationProgress() / epsilon;
        near(leftSlope, rightSlope, 0.02F, "Spline time derivative breaks at repeated seam");
        for (float tick : new float[] {1F, 4F, 9F, 12F, 17F, 19F})
        {
            near(channel.interpolate(tick + 20F), channel.interpolate(tick + 40F), 0.0001F, "Repeated motion warp changed between passes");
        }
        for (Keyframe<Float> key : channel.getKeyframes()) key.getInterpolation().setInterp(Interpolations.LINEAR);
        channel.putLoop(new KeyframeLoop("repeat", 0F, 20F, 55F));
        float endpoint = channel.interpolate(55F);
        KeyframeSegment<Float> after = channel.find(60F);
        near(after.a.getTick(), 55F, 0F, "Finite loop has no virtual endpoint");
        near(after.a.getValue(), endpoint, 0.0001F, "Finite endpoint ignores shifted motion");
        KeyframeSegment<Float> following = channel.find(85F);
        near(following.preA.getTick(), 55F, 0F, "Following real key ignores loop endpoint");
        KeyframeChannel<Float> pasted = numeric(0F, 10F);
        pasted.putLoop(new KeyframeLoop("pasted", 0F, 10F, 30F));
        channel.copyOver(pasted, 40);
        check(channel.getLoop("repeat").end() == 40F && channel.getLoop("pasted").start() == 40F,
            "Pasting loses loop bounds or overlaps old repetition");
        KeyframeChannel<Float> snapshot = new KeyframeChannel<>("numeric", KeyframeFactories.FLOAT);
        snapshot.fromData(channel.toData());
        check(snapshot.equals(channel), "Loop copy comparison differs");
        snapshot.removeLoop("pasted");
        check(!snapshot.equals(channel), "Loop bounds missing from channel undo equality");
        channel.reset();
        check(channel.isEmpty() && channel.getLoops().isEmpty(), "Channel reset leaves loops");
    }

    private static void testModelAnimation()
    {
        KeyframeChannel<MolangExpression> x = new KeyframeChannel<>("x", null);
        x.insert(0F, new MolangValue(null, new Constant(0D)));
        x.insert(10F, new MolangValue(null, new Constant(10D)));
        x.get(0).setMotionShift(0.2F);
        KeyframeChannel<MolangExpression> empty = new KeyframeChannel<>("empty", null);
        Vector3d result = CubicModelAnimator.interpolateList(new Vector3d(), x, empty, empty, 7F, 0D);
        near(result.x, 5D, 0.0001D, "Cubic Molang evaluator ignores motion shift");
    }

    private static void testPoseInterpolationPreservesSources()
    {
        KeyframeChannel<Pose> channel = new KeyframeChannel<>("pose", KeyframeFactories.POSE);
        for (int i = 0; i < 4; i++)
        {
            Pose pose = new Pose();
            PoseTransform bone = pose.getOrCreate(i == 0 ? "other" : "arm");
            bone.translate.x = i * 3F;
            bone.glintSpeed = 1F + i;
            channel.insert(i * 10F, pose);
            channel.get(i).getInterpolation().setInterp(Interpolations.AUTO_CLAMPED);
        }
        BaseType before = channel.toData().copy();
        Pose result = channel.interpolate(15F);
        check(channel.toData().equals(before), "Pose interpolation inserts missing bones into stored keys");
        check(result.transforms.get("arm").glintSpeed > 2F && result.transforms.get("arm").glintSpeed < 3F,
            "Pose AutoBezier lost FSR glint interpolation");
        int hash = KeyframeFactories.POSE.contentHash(result);
        result.transforms.get("arm").glintTransform.translate.x += 1F;
        check(hash != KeyframeFactories.POSE.contentHash(result), "FSR glint transform missing from pose signature");
    }

    private static void testHandleGestures()
    {
        Runnable restoreInput = TestRemoteInput.install(0x4D4F54494F4EL,
            new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), 0));
        try
        {
            Timeline view = new Timeline();
            KeyframeChannel<Float> channel = numeric(0F, 20F);
            UIKeyframeSheet sheet = new UIKeyframeSheet("numeric", IKey.constant("numeric"), 0, false, channel, null);
            view.addSheet(sheet);
            int y = view.getDopeSheet().getDopeSheetY(sheet) + view.getDopeSheet().getTrackHeight(sheet) / 2;
            UIContext context = new UIContext(null);
            int[] edits = {0};
            channel.postCallback((value, flag) -> edits[0]++);
            context.setMouse(view.toGraphX(10F), y, 0);
            check(view.press(context) && view.isInteracting(), "Midpoint handle did not own drag");
            context.setMouse(view.toGraphX(14F), y, 0);
            view.drag(context);
            near(channel.get(0).getMotionShift(), 0.2F, 0.001F, "Handle drag value");
            check(edits[0] == 0, "Handle emits history per mouse sample");
            context.mouseButton = 1;
            view.release(context);
            check(view.isInteracting(), "Right release cancels left-owned midpoint drag");
            context.mouseButton = 0;
            view.release(context);
            check(edits[0] == 1 && !view.isInteracting(), "Handle release did not commit exactly once");
            context.setMouse(view.toGraphX(14F), y, 0);
            view.press(context);
            context.setMouse(view.toGraphX(17F), y, 0);
            view.drag(context);
            context.setKeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, GLFW.GLFW_PRESS);
            view.escape(context);
            near(channel.get(0).getMotionShift(), 0.2F, 0.001F, "Escape did not restore original shift");
            check(edits[0] == 1 && !view.isInteracting(), "Canceled handle changed history or retained ownership");
            context.setMouse(view.toGraphX(14F), y, 0);
            view.press(context);
            context.setMouse(view.toGraphX(16F), y, 0);
            view.drag(context);
            view.removeAllSheets();
            near(channel.get(0).getMotionShift(), 0.2F, 0.001F, "Timeline rebuild committed a provisional shift");
            check(edits[0] == 1 && !view.isInteracting(), "Timeline rebuild left handle active");
        }
        finally
        {
            restoreInput.run();
        }
    }

    private static KeyframeChannel<Float> numeric(float... ticks)
    {
        KeyframeChannel<Float> channel = new KeyframeChannel<>("numeric", KeyframeFactories.FLOAT);
        for (float tick : ticks) channel.insert(tick, tick);
        return channel;
    }

    private static void check(boolean value, String message)
    {
        if (!value) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, double tolerance, String message)
    {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }

    private static final class Timeline extends UIKeyframes
    {
        Timeline()
        {
            super(null);
            this.area.set(0, 0, 600, 140);
            this.graphArea.set(120, 0, 480, 140);
            this.getXAxis().view(0D, 20D);
            this.getDopeSheet().setTrackHeight(32D);
        }

        boolean press(UIContext context) { return this.subMouseClicked(context); }
        void drag(UIContext context) { this.handleMouse(context); }
        boolean release(UIContext context) { return this.subMouseReleased(context); }
        boolean escape(UIContext context) { return this.subKeyPressed(context); }
    }
}
