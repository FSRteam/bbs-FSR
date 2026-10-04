package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.CubicModelAnimator;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.RigBone;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModelAnimator;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * E1 (task 28): does FSR's pose composition chain cover the three states upstream
 * {@code IKBake.applyDelta} branches on?
 *
 * <p>{@code applyDelta} (upstream {@code temp/upstream-IKBake.java:283-315}) is the whole of what a
 * bake asks of a skeleton: it turns the pose track's own value so that the NEXT composed frame lands
 * on the rotation the solve left behind. It has three cases — (a) a euler pose that is the FIRST
 * layer to compose the bone's orientation (branch 2, arithmetic in euler space), (b) a euler pose over
 * a layer that already composed {@code orient} (branch 3, the delta turns the pose's whole rotation),
 * (c) a quaternion pose (branch 1, the value is multiplied). This test drives FSR's REAL composition
 * path — {@code Model.resetPose}/{@code applyPose}, {@code BOBJModel.applyPose},
 * {@code CubicModelAnimator.postAnimate}, {@code ModelGroup.composeOrient},
 * {@code BOBJBone.composeOrient}, {@code evaluatedRotation} — for each state, on BOTH skeletons, and
 * measures the residual between what the renderer composes out of the baked value and the solve.
 *
 * <p>Deliberately NOT in scope here: the solve itself. {@code solved} is a fixed target rotation, the
 * stand-in for {@code rig.evaluatedRotation()} after {@code renderer.solveIK}. E1 is about the
 * composition chain around {@code applyDelta}; whether FSR can drive the solve headlessly (the
 * {@code ModelIKRuntime.getChains} shape, the anchor resolver) is R5-3's own remaining work.
 *
 * <p>Criterion: residual {@code < 1.0e-4} RADIANS of turn, the unit and value of upstream's own
 * {@code MOVED_EPSILON}. Residuals are printed for every row, pass or fail, so a failing row reports
 * its measured number rather than only an assertion.
 */
public final class IKBakePoseCompositionTest
{
    private static final String BONE = "hand";

    /** The task's criterion, in radians — upstream IKBake.MOVED_EPSILON's unit. */
    private static final float EPS = 1.0e-4F;

    /* Rest euler of the posed bone. Cubic keeps channels in DEGREES; a BOBJ bone's rest lives in its
     * bone matrix, so its channels start at zero (isRotationInDegrees() == false). */
    private static final float REST_X = 11F;
    private static final float REST_Y = -23F;
    private static final float REST_Z = 7F;

    /* The action layer's rotation, authored in degrees and converted to the skeleton's unit. */
    private static final float ACTION_X = 19F;
    private static final float ACTION_Y = -34F;
    private static final float ACTION_Z = 8F;

    /* The pose track's own euler rotation (poses are ALWAYS radians, both skeletons). */
    private static final float POSE_X = 18F;
    private static final float POSE_Y = 29F;
    private static final float POSE_Z = -13F;

    /* A quaternion pose overlay, the only way a pose value can go QUATERNION with a euler track. */
    private static final float OVERLAY_X = -27F;
    private static final float OVERLAY_Y = 41F;
    private static final float OVERLAY_Z = 16F;

    /** The turn the solve left behind — off-axis and unequal on all three axes, so no two layers commute. */
    private static final Quaternionf SOLVED = Matrices.toQuaternionZYXDegrees(25F, -37F, 52F);

    private static final float WORK = 0.1F;

    public static void main(String[] args)
    {
        List<String> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (Skeleton skeleton : Skeleton.values())
        {
            for (State state : State.values())
            {
                String row = verify(skeleton, state, failures);

                rows.add(row);
            }
        }

        if (!failures.isEmpty())
        {
            throw new AssertionError("IKBake pose composition: " + failures.size() + " of " + rows.size()
                + " rows unmet:\n  " + String.join("\n  ", failures));
        }

        System.out.println("IKBakePoseCompositionTest: OK (" + rows.size() + " rows within " + EPS + " rad)");
    }

    /**
     * One row: compose the frame the bake samples from, capture the FK the bake captures, run
     * {@code applyDelta} verbatim, compose the frame the baked track draws, and measure.
     */
    private static String verify(Skeleton skeleton, State state, List<String> failures)
    {
        Fixture fixture = fixture(skeleton);
        PoseTransform track = state.track();
        Transform overlay = state.overlay();
        Animation animation = state.action ? action(skeleton) : null;

        /* The frame a rendered pass would have solved: reset, action layers, overlays merged, pose. */
        Pose before = render(fixture, track, overlay, state, animation);
        PoseTransform total = before.get(BONE);
        boolean quaternionTotal = total.rotationMode == Transform.RotationMode.QUATERNION;
        boolean additive = Captured.of(fixture.bone).additive();
        String branch = branch(total, track, additive);

        Captured fk = Captured.of(fixture.bone);
        Quaternionf evaluated = fixture.bone.evaluatedRotation();
        Quaternionf delta = new Quaternionf(evaluated).conjugate().mul(SOLVED);

        /* The bake's own value: the track's interpolated value at the tick (IKBake.trackValue:596-602). */
        PoseTransform value = new PoseTransform();

        value.copy(track);

        applyDelta(value, total, fk, delta, SOLVED);

        render(fixture, value, overlay, state, animation);

        Quaternionf drawn = fixture.bone.evaluatedRotation();
        float error = turn(new Quaternionf(drawn).conjugate().mul(SOLVED));

        /* turn()'s readback is acos of a float w, so any residual under ~1.2e-7 rad normalizes to
         * w == 1 exactly and prints as 0. The dot gap has no such floor: it shows the residual the
         * criterion actually sits on, 3 orders below it. */
        float gap = Math.abs(1F - Math.abs(drawn.dot(SOLVED)));
        float solvedTurn = turn(delta);
        String label = skeleton + "/" + state;
        String row = String.format(Locale.ROOT, "%-22s branch=%-14s additive=%-5s total=%-10s value=%-10s err=%.3e rad (%9.5f deg) gap=%.2e%s",
            label, branch, additive, total.rotationMode, value.rotationMode, error, Math.toDegrees(error), gap,
            state.exact ? "" : "  [measurement]");

        if (!state.exact)
        {
            System.out.println(row);
            System.out.println("  measured only: the total is quaternion because of an OVERLAY while the track's own "
                + "value stays euler, so value is not the last factor of the composed rotation (Transform.addRotation, "
                + "upstream-identical at temp/upstream-Transform.java:201-212) and upstream's branch-1 euler arithmetic "
                + "is exact only if the overlay commutes. An upstream property, not an FSR gap.");
        }
        else
        {
            System.out.println(row);
        }

        if (state.additive != null && additive != state.additive.booleanValue())
        {
            failures.add(row + " -- precondition: additive=" + additive + ", expected " + state.additive);
        }

        if (quaternionTotal != state.quaternionTotal)
        {
            failures.add(row + " -- precondition: total mode is " + total.rotationMode + ", expected "
                + (state.quaternionTotal ? "QUATERNION" : "EULER") + " — this row did not reach the intended branch");
        }

        if (solvedTurn <= WORK)
        {
            failures.add(row + " -- the solve asked for no real turn (" + solvedTurn + " rad): the row proves nothing");
        }

        if (state.exact && !(error < EPS))
        {
            failures.add(row + " -- the composed pose missed the solved rotation by " + error + " rad ("
                + Math.toDegrees(error) + " deg)");
        }

        return row;
    }

    /**
     * One rendered frame's pose stack, in the order {@code ModelFormRenderer.render3D:1067-1071} uses:
     * reset the pose, lay the action layers, merge the form's overlays into the pose, then apply it.
     *
     * <p>The overlay merge is the one production line {@code ModelFormRenderer.applyPose:225} uses for a
     * non-fix overlay ({@code addRotation}); {@code getPose():193-206} merges before {@code applyPose}.
     */
    private static Pose render(Fixture fixture, Transform track, Transform overlay, State state, Animation animation)
    {
        Pose pose = new Pose();
        PoseTransform total = pose.get(BONE);

        total.copy(track);

        if (overlay != null)
        {
            total.addRotation(overlay);
        }

        fixture.model.resetPose();

        if (state.action)
        {
            fixture.layer.apply(animation, state.actionPost);
        }

        fixture.model.applyPose(pose);

        return pose;
    }

    /** Which of upstream's cases this frame is in — read BEFORE the bake mutates the value. */
    private static String branch(PoseTransform total, PoseTransform track, boolean additive)
    {
        if (total.rotationMode == Transform.RotationMode.QUATERNION)
        {
            return track.rotationMode == Transform.RotationMode.QUATERNION ? "1 value-quat" : "1 value-euler";
        }

        return additive ? "2 first-layer" : "3 composed-euler";
    }

    /**
     * Upstream {@code IKBake.applyDelta} (temp/upstream-IKBake.java:283-315), verbatim. Only the type
     * of {@code total}/{@code value} is FSR's own ({@code PoseTransform}, the same class upstream uses).
     */
    private static void applyDelta(PoseTransform value, PoseTransform total, Captured fk, Quaternionf delta, Quaternionf solved)
    {
        if (total != null && total.rotationMode == Transform.RotationMode.QUATERNION)
        {
            if (value.rotationMode == Transform.RotationMode.QUATERNION)
            {
                value.quat.mul(delta);
            }
            else
            {
                Quaternionf turned = Matrices.toLocalRotationZYXRadians(value.rotate).mul(delta);

                Matrices.toCompatibleEulerZYXRadians(turned, value.rotate, value.rotate);
            }

            return;
        }

        if (fk.additive())
        {
            Vector3f target = Matrices.toCompatibleEulerZYXRadians(solved, fk.channels(), new Vector3f());

            value.rotate.add(target.sub(fk.channels()));

            return;
        }

        Vector3f base = total == null ? new Vector3f() : total.rotate;
        Quaternionf turned = Matrices.toLocalRotationZYXRadians(base).mul(delta);
        Vector3f after = Matrices.toCompatibleEulerZYXRadians(turned, base, new Vector3f());

        value.rotate.add(after.sub(base));
    }

    /**
     * Upstream {@code IKBake.Captured} (temp/upstream-IKBake.java:254-263), verbatim: the FK state a
     * bone was in before the solve, and whether the pose was the first layer to compose its rotation.
     */
    private record Captured(Vector3f channels, Quaternionf evaluated, boolean additive)
    {
        static Captured of(RigBone bone)
        {
            Quaternionf orient = bone.getOrient();
            boolean additive = orient == null || sameRotation(orient, bone.orientFromEuler(bone.getBoneTransform().rotate));

            return new Captured(bone.getChannelRotation(new Vector3f()), bone.evaluatedRotation(), additive);
        }
    }

    /**
     * Both FSR skeletons implement {@link RigBone} directly ({@code ModelGroup} and {@code BOBJBone}),
     * so the fixture hands the bone over as-is. This test used to carry a {@code Rig} shim copied from
     * upstream while the real interface did not exist here; now that it does, the shim would be a
     * second, unlinked copy of {@code getChannelRotation} / {@code orientFromEuler} — the two are
     * {@code default} methods, so a change to either would leave this test asserting the old
     * arithmetic silently.
     */
    private static RigBone cubic(ModelGroup group)
    {
        return group;
    }

    private static RigBone bobj(BOBJBone bone)
    {
        return bone;
    }

    private static Fixture fixture(Skeleton skeleton)
    {
        if (skeleton == Skeleton.CUBIC)
        {
            Model model = new Model(new MolangParser());
            ModelGroup root = group("root", 0F, 0F, 0F);
            ModelGroup hand = group(BONE, 0F, -16F, 0F);

            root.children.add(hand);
            model.topGroups.add(root);
            model.initialize();
            model.resetPose();

            return new Fixture(model, cubic(hand), (animation, post) ->
            {
                if (post)
                {
                    CubicModelAnimator.postAnimate(model, animation, 0F);
                }
                else
                {
                    CubicModelAnimator.animate(model, animation, 0F, 1F, false);
                }
            });
        }

        BOBJArmature armature = new BOBJArmature("e1");
        BOBJBone root = bone(0, "root", "", 0F, 0F, 0F);
        BOBJBone hand = bone(1, BONE, "root", 0F, -1F, 0F);

        armature.addBone(root);
        armature.addBone(hand);
        armature.initArmature();

        BOBJModel model = new BOBJModel(armature, new ArrayList<>(), false);

        model.resetPose();

        return new Fixture(model, bobj(hand), (animation, post) ->
        {
            if (post)
            {
                BOBJModelAnimator.postAnimate(model, animation, 0F);
            }
            else
            {
                BOBJModelAnimator.animate(model, animation, 0F, 1F, false);
            }
        });
    }

    private static ModelGroup group(String id, float x, float y, float z)
    {
        ModelGroup group = new ModelGroup(id);

        group.initial.translate.set(x, y, z);
        group.initial.rotate.set(REST_X, REST_Y, REST_Z);

        return group;
    }

    private static BOBJBone bone(int index, String name, String parent, float x, float y, float z)
    {
        return new BOBJBone(index, name, parent, new Matrix4f().translation(x, y, z));
    }

    /**
     * A real action layer, driven through the production animators. The PRE pass
     * ({@code animate}) writes the channels only; the POST pass ({@code postAnimate} /
     * {@code applyGroupAnimationPost:161-189}) writes them and then composes {@code orient} — the
     * distinction upstream's {@code Captured.additive} is about, and the only production way a bone
     * enters the pose already oriented.
     */
    private static Animation action(Skeleton skeleton)
    {
        MolangParser parser = new MolangParser();
        Animation animation = new Animation("e1", parser);
        AnimationPart part = new AnimationPart(parser);
        float factor = skeleton == Skeleton.CUBIC ? 1F : (float) (Math.PI / 180D);

        animation.parts.put(BONE, part);

        key(part.rx, ACTION_X * factor);
        key(part.ry, ACTION_Y * factor);
        key(part.rz, ACTION_Z * factor);

        return animation;
    }

    private static void key(KeyframeChannel<MolangExpression> channel, float value)
    {
        int index = channel.insert(0F, new MolangValue(null, new Constant(value)));

        /* AnimationPart.fromData:parseAnimationVector sets the interp the same explicit way; the
         * channel's default is the MAP sentinel, which a hand-built part never registers. */
        channel.get(index).getInterpolation().setInterp(Interpolations.LINEAR);
    }

    /** Upstream IKBake.turn (:376-381). */
    private static float turn(Quaternionf rotation)
    {
        float w = Math.min(1F, Math.abs(new Quaternionf(rotation).normalize().w));

        return 2F * (float) Math.acos(w);
    }

    /** Upstream IKBake.sameRotation (:370-373), at upstream's own 1.0E-6F. */
    private static boolean sameRotation(Quaternionf a, Quaternionf b)
    {
        return Math.abs(a.dot(b)) >= 1F - 1.0E-6F;
    }

    private static float toRadians(float degrees)
    {
        return (float) Math.toRadians(degrees);
    }

    private enum Skeleton
    {
        CUBIC, BOBJ
    }

    /**
     * The states {@code applyDelta} branches on. {@code additive}/{@code quaternionTotal} are the
     * preconditions each row must actually be in, asserted rather than assumed — a row that quietly
     * ran the wrong branch would otherwise "pass" while proving nothing.
     *
     * <p>{@code exact=false} marks QUAT_OVERLAY: a total that is quaternion because an OVERLAY made it
     * so, while the track's own value stays euler. There {@code value} is not the last factor of the
     * composed rotation ({@code ModelFormRenderer.applyPose:225} merges {@code R(value) · R(overlay)}),
     * so upstream's branch-1 euler arithmetic — which turns {@code R(value)} by the delta — is exact
     * only if the overlay commutes. That is an upstream property, not an FSR gap, so the row is
     * measured and printed instead of asserted.
     */
    private enum State
    {
        POSE_ONLY(false, false, false, false, Boolean.TRUE, false, true),
        ACTION_PRE(true, false, false, false, Boolean.TRUE, false, true),
        ACTION_POST(true, true, false, false, Boolean.FALSE, false, true),
        QUAT_POSE(false, false, true, false, null, true, true),
        QUAT_POST(true, true, true, false, null, true, true),
        QUAT_OVERLAY(false, false, false, true, null, true, false);

        private final boolean action;
        private final boolean actionPost;
        private final boolean quaternionTrack;
        private final boolean quaternionOverlay;
        private final Boolean additive;
        private final boolean quaternionTotal;
        private final boolean exact;

        State(boolean action, boolean actionPost, boolean quaternionTrack, boolean quaternionOverlay, Boolean additive, boolean quaternionTotal, boolean exact)
        {
            this.action = action;
            this.actionPost = actionPost;
            this.quaternionTrack = quaternionTrack;
            this.quaternionOverlay = quaternionOverlay;
            this.additive = additive;
            this.quaternionTotal = quaternionTotal;
            this.exact = exact;
        }

        /** The pose track's own value for the bone (radians, as every pose is). */
        private PoseTransform track()
        {
            PoseTransform value = new PoseTransform();

            value.rotate.set(toRadians(POSE_X), toRadians(POSE_Y), toRadians(POSE_Z));

            if (this.quaternionTrack)
            {
                value.setModeQuaternion();
            }

            return value;
        }

        /** The form's quaternion overlay, or null. */
        private Transform overlay()
        {
            if (!this.quaternionOverlay)
            {
                return null;
            }

            PoseTransform overlay = new PoseTransform();

            overlay.rotate.set(toRadians(OVERLAY_X), toRadians(OVERLAY_Y), toRadians(OVERLAY_Z));
            overlay.setModeQuaternion();

            return overlay;
        }
    }

    private record Fixture(IModel model, RigBone bone, ActionLayer layer)
    {
    }

    private interface ActionLayer
    {
        void apply(Animation animation, boolean post);
    }
}
