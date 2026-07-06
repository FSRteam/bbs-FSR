package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig;
import mchorse.bbs_mod.cubic.render.CubicRenderer.PivotFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * The per-chain bone-physics solver. Given a chain's {@link ChainState}, the animated pose and the chain
 * config, it integrates the Verlet particles a fixed number of sub-steps per film tick, springs each segment
 * toward the pose, holds segment lengths and swing cones, resolves world collisions, and reconstructs the
 * render shape. Stateless beyond the {@link ChainState} it is handed — the runtime owns lifecycle and timing.
 */
final class ChainSolver
{
    static final float EPS = 1.0e-6f;

    private static final float BASE_GRAVITY = 0.08F;

    /**
     * Stiffness falloff along the chain: the tip is left this fraction as stiff as the root, so the
     * base of the chain springs back to the pose firmly while the end stays loose and trails. A flat
     * stiffness reads stiff and lifeless; the gradient is what gives the chain a living, whip-like tail.
     */
    private static final float TIP_STIFFNESS_SCALE = 0.4F;

    private static final float COLLISION_FRICTION = 0.5F;
    private static final float COLLISION_MAX_ANCHOR_STEP = 0.25F;

    /**
     * Sub-steps the solver runs per film tick. The sim advances a fixed number of sub-steps per integer
     * tick (not off a real-time accumulator), so the chain shape at a tick is a function of the tick alone
     * — identical on every playback and in export — while the in-between shapes are still actually
     * simulated rather than interpolated from coarse 20 Hz snapshots. More = smoother but more solver work.
     */
    private static final int SUBSTEPS_PER_TICK = 3;

    /** Sub-step length in ticks, derived from {@link #SUBSTEPS_PER_TICK}. */
    private static final float PHYSICS_STEP = 1F / SUBSTEPS_PER_TICK;

    /** Hard cap on sub-steps simulated in one {@link #step} call, so a long catch-up can't stall a frame. */
    private static final int PHYSICS_MAX_STEPS = 30;

    /**
     * Largest forward tick gap still simulated by stepping in place from the current pose. A bigger jump
     * (a real scrub) can't be replayed without the intermediate poses, so the chain is re-seeded at the
     * pose instead; deterministic re-simulation from a checkpoint is handled by the runtime.
     */
    private static final int MAX_TICK_CATCHUP = 4;

    private ChainSolver()
    {
    }

    /**
     * Captures the animated pose the chain should spring back toward, stored relative to the live anchor
     * frame so the sub-step solver can carry it rigidly with the sliding anchor. The real joints take
     * their own animated world positions; the virtual tip past the last bone is reconstructed from the
     * last bone's animated rotation and rest direction (the same convention the rotation appliers use),
     * unless the tip is hard-pinned to a target, in which case its pose slot is left unused.
     */
    static void computePoseTargets(IModel model, List<String> ids, List<PivotFrame> chainFrames, float[] lengths, Vector3f anchor, Quaternionf anchorRotation, boolean hardTarget, ChainState state)
    {
        Vector3f[] poseLocal = state.poseLocal;
        int pivotCount = chainFrames.size();

        if (poseLocal == null || poseLocal.length != pivotCount + 1)
        {
            return;
        }

        Quaternionf invAnchor = new Quaternionf(anchorRotation).invert();
        Vector3f tmp = new Vector3f();

        poseLocal[0].set(0F, 0F, 0F);

        for (int i = 1; i < pivotCount; i++)
        {
            tmp.set(chainFrames.get(i).position()).sub(anchor);
            poseLocal[i].set(invAnchor.transform(tmp));
        }

        int tip = poseLocal.length - 1;

        if (hardTarget)
        {
            poseLocal[tip].set(poseLocal[pivotCount - 1]);
            return;
        }

        Vector3f tipDir = lengths != null && lengths.length >= pivotCount ? PhysicsRig.tipRestDirectionLocal(model, ids) : null;

        if (tipDir == null || tipDir.lengthSquared() < EPS * EPS)
        {
            poseLocal[tip].set(poseLocal[pivotCount - 1]);
            return;
        }

        PivotFrame lastFrame = chainFrames.get(pivotCount - 1);
        new Quaternionf(lastFrame.worldRotation()).transform(tipDir.normalize()).mul(lengths[pivotCount - 1]);
        tmp.set(lastFrame.position()).add(tipDir).sub(anchor);
        poseLocal[tip].set(invAnchor.transform(tmp));
    }

    /**
     * Interpolates the settled chain shape of the two latest simulation ticks and re-roots it onto the live
     * anchor. The chain is rebuilt segment by segment from the anchor outwards: each segment's direction is
     * slerped between the two ticks (so the bone swings along an arc, not a straight chord) and its length is
     * lerped, while the anchor's leftover sub-tick rotation carries the whole chain. This keeps the motion
     * smooth between simulation ticks instead of reading as stepping.
     */
    static Vector3f[] renderInterpolate(ChainState state, float transition, Vector3f liveAnchor, Quaternionf liveAnchorRotation, Vector3f target)
    {
        Vector3f[] render = state.render;
        Vector3f[] settled = state.settled;
        Vector3f[] settledPrev = state.settledPrev;

        if (render == null || settled == null || settledPrev == null || render.length != settled.length || settledPrev.length != settled.length)
        {
            return state.pos;
        }

        float alpha = clamp01(transition);

        Vector3f dir = new Vector3f();
        Vector3f dirCurr = new Vector3f();
        Quaternionf swing = new Quaternionf();
        Quaternionf segRot = new Quaternionf();
        Quaternionf frac = new Quaternionf();

        swing.set(liveAnchorRotation).mul(segRot.set(state.anchorRotation).invert()).normalize(); // anchor sub-tick swing

        /* Root point is pinned to the live anchor, the rest is rebuilt outwards from it */
        render[0].set(liveAnchor);

        for (int i = 0; i + 1 < render.length; i++)
        {
            dir.set(settledPrev[i + 1]).sub(settledPrev[i]); // segment last tick
            dirCurr.set(settled[i + 1]).sub(settled[i]); // segment this tick

            float lenPrev = dir.length();
            float lenCurr = dirCurr.length();
            float len = lenPrev + (lenCurr - lenPrev) * alpha;

            boolean okPrev = lenPrev > EPS;
            boolean okCurr = lenCurr > EPS;

            if (okPrev && okCurr)
            {
                dir.div(lenPrev);
                dirCurr.div(lenCurr);
                segRot.rotationTo(dir, dirCurr); // full swing of this segment over the tick
                frac.identity().slerp(segRot, alpha).transform(dir); // dir = direction at sub-tick alpha
            }
            else if (okCurr)
            {
                dir.set(dirCurr).div(lenCurr);
            }
            else if (okPrev)
            {
                dir.div(lenPrev);
            }
            else
            {
                render[i + 1].set(render[i]);
                continue;
            }

            /* Carry the chain by the anchor's leftover sub-tick rotation. The lag of the tip during a
             * turn is produced by the simulation now, so the render carries every segment equally
             * instead of faking the trail with a per-segment falloff. */
            swing.transform(dir);
            render[i + 1].set(render[i]).add(dir.mul(len));
        }

        if (target != null)
        {
            render[render.length - 1].set(target);
        }

        return render;
    }

    private static void copyPositions(Vector3f[] src, Vector3f[] dst)
    {
        for (int i = 0; i < src.length; i++)
        {
            dst[i].set(src[i]);
        }
    }

    static void step(Level world, int age, float transition, IModel model, List<String> ids, ModelPhysicsCache.CompiledChain chain, float gravityMul, float dampingValue, float stiffnessValue, ModelPhysicsConfig.Wind wind, Map<String, ModelConstraintsConfig.BoneConstraint> constraints, Vector3f anchorPosition, Quaternionf anchorRotation, Quaternionf parentRotation, Vector3f targetPosition, List<PivotFrame> chainFrames, ChainState state)
    {
        Vector3f newAnchor = anchorPosition;
        Quaternionf newAnchorRotation = anchorRotation;
        float[] lengths = chain.restLengths();

        if (lengths == null || lengths.length != state.pos.length - 1)
        {
            return;
        }

        if (state.lastAge == Integer.MIN_VALUE)
        {
            seedFromPose(state, chainFrames, lengths, newAnchor, newAnchorRotation);
            state.lastAge = age;
            state.renderAlpha = 0F;
            return;
        }

        int delta = age - state.lastAge;

        if (delta == 0)
        {
            /* Same film tick, a later render within it — no new simulation; the render just interpolates
             * the two latest tick states by the sub-tick transition (and re-roots them to the live anchor). */
            state.renderAlpha = clamp01(transition);
            return;
        }

        if (delta < 0 || delta > MAX_TICK_CATCHUP)
        {
            /* Scrubbed backwards or jumped a long way: without the intermediate poses the sim can't be
             * replayed here, so re-seed at the current animated pose. Deterministic re-simulation from a
             * window is the runtime's job. */
            seedFromPose(state, chainFrames, lengths, newAnchor, newAnchorRotation);
            state.lastAge = age;
            state.renderAlpha = clamp01(transition);
            return;
        }

        float gravity = BASE_GRAVITY * gravityMul;
        float damping = clamp01(dampingValue);
        int iterations = chain.iterations();
        boolean collisions = chain.collisions() && world != null && chain.radius() > 0F;
        float radius = chain.radius();
        boolean hardTarget = targetPosition != null;
        int last = state.pos.length - 1;

        /* Deterministic fixed step: exactly SUBSTEPS_PER_TICK sub-steps per film tick advanced, no
         * real-time accumulator — so the chain shape at a tick depends on the tick alone (identical on
         * every playback and in export), not on the render frame rate. A skipped tick (frame hitch)
         * advances several ticks at once, capped. The sub-tick transition interpolates the two latest
         * tick states in renderInterpolate. */
        int steps = delta * SUBSTEPS_PER_TICK;

        if (steps > PHYSICS_MAX_STEPS)
        {
            steps = PHYSICS_MAX_STEPS;
        }

        state.renderAlpha = clamp01(transition);

        float h = PHYSICS_STEP;
        float dampMul = (float) Math.pow(1F - damping, h);
        float gravityScale = h * h;

        /* Gravity (per chain), folded once into the per-sub-step acceleration: it scales by gravityScale
         * (the squared sub-step length, the Verlet dt²). */
        Vector3f gravityVec = new Vector3f();
        PhysicsForces.computeGravityDirection(chain, parentRotation, gravity, gravityVec);
        float gravityX = gravityVec.x * gravityScale;
        float gravityY = gravityVec.y * gravityScale;
        float gravityZ = gravityVec.z * gravityScale;

        /* Wind: its steady direction and base magnitude are resolved once here, but the actual force is
         * asked for per point inside the sub-step loop, because turbulence varies it across space and time
         * (so the chain ripples and gusts drift downwind). Lives in the same world frame as gravity. */
        Vector3f windDir = new Vector3f();
        float windMagnitude = PhysicsForces.prepareWind(wind, BASE_GRAVITY, windDir);
        boolean hasWind = windMagnitude > 0F;
        Vector3f windVec = hasWind ? new Vector3f() : null;
        int startAge = state.lastAge;

        /* Per-point spring-back fraction toward the animated pose for one sub-step, falling off toward the
         * floppier tip. Applied as an angular pull in solveSpring, not a positional one. */
        float[] stiffStep = computeStiffnessSteps(clamp01(stiffnessValue), state.pos.length, h);

        /* Per-bone swing limits, as the cosine of the widest deviation each bone may take from its pose
         * direction; null when no bone in the chain is constrained, so the cone clamp is skipped. */
        float[] coneCos = computeConeLimits(ids, constraints);

        Vector3f startAnchor = new Vector3f(state.anchor);
        Quaternionf startAnchorRotation = new Quaternionf(state.anchorRotation);
        Vector3f stepAnchor = new Vector3f();
        Quaternionf stepAnchorRotation = new Quaternionf();
        Vector3f vel = new Vector3f();

        Vector3f poseDir = new Vector3f();
        Vector3f curDir = new Vector3f();

        BlockPos.MutableBlockPos mutable = collisions ? new BlockPos.MutableBlockPos() : null;

        /* Snapshot the previous tick's settled shape once; the new tick's shape is snapshot after the
         * sub-steps. renderInterpolate blends these two by the sub-tick transition. */
        copyPositions(state.settled, state.settledPrev);

        for (int s = 0; s < steps; s++)
        {
            /* Slide the anchor from where the simulation left it toward the live anchor across the
             * sub-steps of this frame, so the chain sees a smooth anchor trajectory. */
            float progress = (s + 1) / (float) steps;
            float filmTime = startAge + (s + 1) * h;
            stepAnchor.set(startAnchor).lerp(newAnchor, progress);
            stepAnchorRotation.set(startAnchorRotation).slerp(newAnchorRotation, progress);

            state.anchor.set(stepAnchor);
            state.anchorRotation.set(stepAnchorRotation);
            state.pos[0].set(stepAnchor);
            state.prev[0].set(stepAnchor);

            /* Verlet integration of the free points: inertia carried from the previous step plus
             * gravity. The anchor's motion never forces the points — it reaches the chain only through
             * the pose spring and the length constraints below, so lag and whip arise honestly. */
            for (int i = 1; i < state.pos.length; i++)
            {
                Vector3f p = state.pos[i];
                Vector3f prev = state.prev[i];

                vel.set(p).sub(prev).mul(dampMul);
                prev.set(p);
                p.add(vel);
                p.x += gravityX;
                p.y += gravityY;
                p.z += gravityZ;

                if (hasWind)
                {
                    PhysicsForces.windForceAt(windDir, windMagnitude, wind, filmTime, p, windVec);
                    p.x += windVec.x * gravityScale;
                    p.y += windVec.y * gravityScale;
                    p.z += windVec.z * gravityScale;
                }

                if (collisions)
                {
                    clampTunnelStep(world, mutable, p, prev, radius);
                }
            }

            /* Angular spring: nudge each segment's direction toward its animated pose direction (carried by
             * the anchor), keeping its current length — the length relaxation below fixes lengths. Each
             * segment springs independently, so long chains can't build a self-reinforcing wave. A no-op at
             * stiffness 0, leaving a pure rope. */
            solveSpring(state.pos, stepAnchorRotation, state.poseLocal, stiffStep, poseDir, curDir);

            /* Length relaxation: backward then forward passes, iterated. The symmetric two-way sweep is
             * what keeps a long chain stable — a single forward-only ("follow the leader") pass would let
             * each joint rigidly inherit its parent's motion, which Verlet reads back as spurious velocity
             * and pumps into a slow standing wave that never settles while the anchor moves. The cone is
             * folded in so length and limit converge together. */
            for (int iter = 0; iter < iterations; iter++)
            {
                if (hardTarget)
                {
                    state.pos[last].set(targetPosition);
                }

                lengthBackward(state.pos, lengths, last);
                state.pos[0].set(state.anchor);
                lengthForward(state.pos, lengths);

                if (hardTarget)
                {
                    state.pos[last].set(targetPosition);
                }

                if (coneCos != null)
                {
                    applyConeLimits(state.pos, stepAnchorRotation, state.poseLocal, coneCos, last);
                    pinEnds(state.pos, state.anchor, targetPosition, last);
                }
            }

            /* Level collisions once per sub-step (friction applied a single time, inside resolve), then
             * re-impose the lengths and endpoints the depenetration disturbed. */
            if (collisions)
            {
                resolveCollisions(world, state.pos, state.prev, state.anchor, targetPosition, last, radius);
                lengthForward(state.pos, lengths);
                pinEnds(state.pos, state.anchor, targetPosition, last);
            }
        }

        copyPositions(state.pos, state.settled);
        state.lastAge = age;
    }

    /**
     * Seeds the chain at the current animated pose with zero velocity: every joint on its posed position
     * plus the virtual tip one rest-length past the last bone. Used on first sight of a chain and when a
     * scrub or long jump leaves no history to replay.
     */
    private static void seedFromPose(ChainState state, List<PivotFrame> chainFrames, float[] lengths, Vector3f anchor, Quaternionf anchorRotation)
    {
        state.anchor.set(anchor);
        state.anchorRotation.set(anchorRotation);

        state.pos[0].set(anchor);
        state.prev[0].set(anchor);

        for (int i = 1; i < chainFrames.size(); i++)
        {
            Vector3f p = chainFrames.get(i).position();
            state.pos[i].set(p);
            state.prev[i].set(p);
        }

        Vector3f tipDir = new Vector3f();

        if (chainFrames.size() >= 2)
        {
            tipDir.set(state.pos[chainFrames.size() - 1]).sub(state.pos[chainFrames.size() - 2]);

            if (tipDir.lengthSquared() < EPS * EPS)
            {
                tipDir.set(0F, -1F, 0F);
            }
            else
            {
                tipDir.normalize();
            }
        }
        else
        {
            tipDir.set(0F, -1F, 0F);
        }

        state.pos[state.pos.length - 1].set(state.pos[chainFrames.size() - 1]).add(tipDir.mul(lengths[lengths.length - 1]));
        state.prev[state.prev.length - 1].set(state.pos[state.pos.length - 1]);

        copyPositions(state.pos, state.settled);
        copyPositions(state.pos, state.settledPrev);
    }

    /** Re-fixes the chain endpoints after a constraint pass: the root onto the anchor and, when the tip is hard-pinned, onto its target. */
    private static void pinEnds(Vector3f[] pos, Vector3f anchor, Vector3f target, int last)
    {
        pos[0].set(anchor);

        if (target != null)
        {
            pos[last].set(target);
        }
    }

    /** Depenetrates the chain against the world, then re-pins the endpoints. The tip is excluded from the sweep when it is hard-pinned. */
    private static void resolveCollisions(Level world, Vector3f[] pos, Vector3f[] prev, Vector3f anchor, Vector3f target, int last, float radius)
    {
        int to = target != null ? last : pos.length;

        ModelPhysicsWorldCollisions.resolve(world, pos, prev, 1, to, radius, COLLISION_FRICTION);
        pinEnds(pos, anchor, target, last);
    }

    /**
     * Per-point spring-back fractions toward the animated pose for a single sub-step. The base
     * stiffness falls off linearly from the root to {@link #TIP_STIFFNESS_SCALE} at the tip, then each
     * factor is converted from a per-tick pull to a per-sub-step pull so the result is independent of
     * how many sub-steps run. Index 0 (the anchor) and the unused slots stay zero.
     */
    private static float[] computeStiffnessSteps(float baseStiffness, int pointCount, float h)
    {
        float[] out = new float[pointCount];

        if (baseStiffness <= 0F || pointCount <= 1)
        {
            return out;
        }

        int freeCount = pointCount - 1;

        for (int i = 1; i < pointCount; i++)
        {
            float t = freeCount <= 1 ? 0F : (i - 1) / (float) (freeCount - 1);
            float falloff = 1F - (1F - TIP_STIFFNESS_SCALE) * t;
            float perTick = baseStiffness * falloff;

            out[i] = 1F - (float) Math.pow(1F - perTick, h);
        }

        return out;
    }

    /**
     * Anti-tunnelling guard: when a particle would travel further than {@link #COLLISION_MAX_ANCHOR_STEP}
     * (or its own diameter) in one sub-step and there are solid blocks in its swept volume, clamp the
     * step length so the depenetration pass can still catch it instead of passing through thin geometry.
     */
    private static void clampTunnelStep(Level world, BlockPos.MutableBlockPos mutable, Vector3f p, Vector3f prev, float radius)
    {
        float dx = p.x - prev.x;
        float dy = p.y - prev.y;
        float dz = p.z - prev.z;

        float maxStep = Math.max(COLLISION_MAX_ANCHOR_STEP, radius * 2F);
        float lenSq = dx * dx + dy * dy + dz * dz;

        if (lenSq <= maxStep * maxStep)
        {
            return;
        }

        int minBX = Mth.floor(Math.min(prev.x, p.x) - radius);
        int minBY = Mth.floor(Math.min(prev.y, p.y) - radius);
        int minBZ = Mth.floor(Math.min(prev.z, p.z) - radius);
        int maxBX = Mth.floor(Math.max(prev.x, p.x) + radius);
        int maxBY = Mth.floor(Math.max(prev.y, p.y) + radius);
        int maxBZ = Mth.floor(Math.max(prev.z, p.z) + radius);

        if (!ModelPhysicsWorldCollisions.hasFullCubeInAabb(world, mutable, minBX, minBY, minBZ, maxBX, maxBY, maxBZ))
        {
            return;
        }

        float inv = maxStep / (float) Math.sqrt(lenSq);
        p.x = prev.x + dx * inv;
        p.y = prev.y + dy * inv;
        p.z = prev.z + dz * inv;
    }

    /**
     * Per-bone cosine of the widest swing the bone may take from its animated pose direction, read from
     * its enabled constraint's bend limits. A value above 1 marks a bone with no active limit. Returns
     * null when no bone in the chain is constrained, so the solver skips the cone pass entirely.
     */
    private static float[] computeConeLimits(List<String> ids, Map<String, ModelConstraintsConfig.BoneConstraint> constraints)
    {
        if (constraints == null || constraints.isEmpty())
        {
            return null;
        }

        int boneCount = ids.size();
        float[] coneCos = new float[boneCount];
        boolean any = false;

        for (int i = 0; i < boneCount; i++)
        {
            String boneId = ids.get(i);
            ModelConstraintsConfig.BoneConstraint c = boneId == null ? null : constraints.get(boneId);

            if (c == null || !c.enabled())
            {
                coneCos[i] = 2F;
                continue;
            }

            /* The bone points down its own -Y, so bending it is rotation about X and Z; the widest of
             * those bounds is the cone half-angle. Twist (Y) does not move the direction, so it is left
             * to the animation and ignored here. */
            float halfAngle = Math.max(Math.max(Math.abs(c.minX()), Math.abs(c.maxX())), Math.max(Math.abs(c.minZ()), Math.abs(c.maxZ())));

            if (halfAngle >= 180F)
            {
                coneCos[i] = 2F;
                continue;
            }

            coneCos[i] = (float) Math.cos(Math.toRadians(halfAngle));
            any = true;
        }

        return any ? coneCos : null;
    }

    /**
     * Nudges each segment's direction toward its animated pose direction (carried by the anchor) by the
     * per-point stiffness, keeping the segment's CURRENT length — the length relaxation that follows fixes
     * lengths and shares corrections both ways. Each segment springs independently of its neighbours, so a
     * long chain can't build a self-reinforcing wave; the tail still trails through inertia and the shared
     * length chain. A no-op at stiffness 0, leaving the integrated rope untouched.
     */
    private static void solveSpring(Vector3f[] pos, Quaternionf anchorRotation, Vector3f[] poseLocal, float[] stiffStep, Vector3f poseDir, Vector3f curDir)
    {
        int last = pos.length - 1;

        for (int i = 1; i <= last; i++)
        {
            float k = stiffStep[i];

            if (k <= 0F)
            {
                continue;
            }

            curDir.set(pos[i]).sub(pos[i - 1]);

            float curLen = curDir.length();

            if (curLen < EPS)
            {
                continue;
            }

            poseDir.set(poseLocal[i]).sub(poseLocal[i - 1]);
            anchorRotation.transform(poseDir);

            float poseLen = poseDir.length();

            if (poseLen < EPS)
            {
                continue;
            }

            poseDir.div(poseLen);
            curDir.div(curLen);
            curDir.lerp(poseDir, k);

            float blendLen = curDir.length();

            if (blendLen < EPS)
            {
                curDir.set(poseDir);
            }
            else
            {
                curDir.div(blendLen);
            }

            /* Keep the segment's current length; only rotate it toward the pose. */
            pos[i].set(pos[i - 1]).add(curDir.mul(curLen));
        }
    }

    /** Length-projects the chain from the tip inward, holding each segment at its rest length (paired with {@link #lengthForward} for a symmetric two-way relaxation). */
    private static void lengthBackward(Vector3f[] pos, float[] lengths, int last)
    {
        Vector3f dir = new Vector3f();

        for (int i = last - 1; i >= 0; i--)
        {
            Vector3f a = pos[i];
            Vector3f b = pos[i + 1];

            dir.set(a).sub(b);

            float lenSq = dir.lengthSquared();

            if (lenSq < EPS * EPS)
            {
                continue;
            }

            dir.mul((float) (lengths[i] / Math.sqrt(lenSq)));
            a.set(b).add(dir);
        }
    }

    /** Length-projects the chain from the anchor outward, holding each segment at its rest length. */
    private static void lengthForward(Vector3f[] pos, float[] lengths)
    {
        Vector3f dir = new Vector3f();

        for (int i = 1; i < pos.length; i++)
        {
            Vector3f a = pos[i - 1];
            Vector3f b = pos[i];

            dir.set(b).sub(a);

            float lenSq = dir.lengthSquared();

            if (lenSq < EPS * EPS)
            {
                continue;
            }

            dir.mul((float) (lengths[i - 1] / Math.sqrt(lenSq)));
            b.set(a).add(dir);
        }
    }

    /**
     * Clamps a unit direction into a cone of half-angle acos(cosMax) around a unit reference, rotating it
     * back to the cone boundary along the shortest arc — pure direction projection, no euler, no gimbal.
     * No-op when the direction is already inside the cone or the bone has no active limit (cosMax &gt; 1).
     */
    private static void projectIntoCone(Vector3f dir, Vector3f ref, float cosMax)
    {
        if (cosMax > 1F)
        {
            return;
        }

        float cos = dir.x * ref.x + dir.y * ref.y + dir.z * ref.z;

        if (cos >= cosMax)
        {
            return;
        }

        float sin = (float) Math.sqrt(Math.max(0F, 1F - cos * cos));

        if (sin < EPS)
        {
            /* Pointing straight back along the pose — no defined arc, snap to the pose direction. */
            dir.set(ref);
            return;
        }

        float sinMax = (float) Math.sqrt(Math.max(0F, 1F - cosMax * cosMax));
        float invSin = 1F / sin;
        float tx = (dir.x - ref.x * cos) * invSin;
        float ty = (dir.y - ref.y * cos) * invSin;
        float tz = (dir.z - ref.z * cos) * invSin;

        dir.set(ref.x * cosMax + tx * sinMax, ref.y * cosMax + ty * sinMax, ref.z * cosMax + tz * sinMax);
    }

    /**
     * Holds each segment within the cone of its animated pose direction (rebuilt from the anchor). Only the
     * child point moves; the length passes carry the correction down the tail. Iterated together with the
     * length relaxation so limit and length converge instead of fighting.
     */
    private static void applyConeLimits(Vector3f[] pos, Quaternionf anchorRotation, Vector3f[] poseLocal, float[] coneCos, int segments)
    {
        Vector3f ref = new Vector3f();
        Vector3f dir = new Vector3f();

        for (int i = 0; i < segments; i++)
        {
            float cosMax = coneCos[i];

            if (cosMax > 1F)
            {
                continue;
            }

            ref.set(poseLocal[i + 1]).sub(poseLocal[i]);
            anchorRotation.transform(ref);

            float refLen = ref.length();

            if (refLen < EPS)
            {
                continue;
            }

            ref.div(refLen);

            Vector3f a = pos[i];
            Vector3f b = pos[i + 1];

            dir.set(b).sub(a);

            float len = dir.length();

            if (len < EPS)
            {
                continue;
            }

            dir.div(len);
            projectIntoCone(dir, ref, cosMax);
            b.set(a.x + dir.x * len, a.y + dir.y * len, a.z + dir.z * len);
        }
    }

    private static float clamp01(float v)
    {
        if (v < 0F)
        {
            return 0F;
        }

        return v > 1F ? 1F : v;
    }
}
