package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig.BoneConstraint;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.CubicRenderer.PivotFrame;
import mchorse.bbs_mod.cubic.render.ModelPivotFrames;
import mchorse.bbs_mod.cubic.render.ModelRotationBlender;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ModelIKApplier
{
    private static final int MAX_ITERATIONS = 12;
    private static final float TOLERANCE = 1.0e-4f;

    private ModelIKApplier()
    {
    }

    public static void apply(IModel model, List<ModelIKCache.CompiledChain> chains, Map<String, Vector3f> controllerTargets, Map<String, Float> poseFixByBone)
    {
        apply(model, chains, controllerTargets, poseFixByBone, null);
    }

    public static void apply(IModel model, List<ModelIKCache.CompiledChain> chains, Map<String, Vector3f> controllerTargets, Map<String, Float> poseFixByBone, Map<String, BoneConstraint> boneLimits)
    {
        if (model == null || chains == null || chains.isEmpty())
        {
            return;
        }

        /* Apply ancestor chains (shallower root) first, and re-collect frames per
         * chain, so a child chain (e.g. an arm) sees the pose its parent chain
         * (e.g. the body) already produced and rides along with it. */
        List<ModelIKCache.CompiledChain> ordered = new ArrayList<>(chains);
        ordered.sort(Comparator.comparingInt((ModelIKCache.CompiledChain chain) -> rootDepth(model, chain)));

        for (ModelIKCache.CompiledChain chain : ordered)
        {
            Set<String> wanted = new HashSet<>();
            wanted.add(chain.target());
            wanted.addAll(chain.chainRootToEffector());

            Map<String, PivotFrame> frames = new HashMap<>(wanted.size() * 2);
            ModelPivotFrames.collect(model, wanted, frames);

            applyChain(model, chain, frames, controllerTargets, poseFixByBone, boneLimits);
        }
    }

    /** Depth of the chain's root bone from the model root, for ancestor-first ordering. */
    private static int rootDepth(IModel model, ModelIKCache.CompiledChain chain)
    {
        List<String> ids = chain.chainRootToEffector();
        String group = ids.isEmpty() ? chain.tip() : ids.get(0);
        int depth = 0;

        while (group != null && !group.isEmpty() && depth < 256)
        {
            String parent = model.getParentGroupKey(group);

            if (parent == null || parent.equals(group))
            {
                break;
            }

            group = parent;
            depth++;
        }

        return depth;
    }

    private static void applyChain(IModel model, ModelIKCache.CompiledChain chain, Map<String, PivotFrame> frames, Map<String, Vector3f> controllerTargets, Map<String, Float> poseFixByBone, Map<String, BoneConstraint> boneLimits)
    {
        float poseFix = getChainPoseFix(chain, poseFixByBone);
        float weight = chain.weight() * (1F - poseFix);

        if (weight <= 0F)
        {
            return;
        }

        PivotFrame targetFrame = frames.get(chain.target());

        if (targetFrame == null)
        {
            return;
        }

        List<String> chainIds = chain.chainRootToEffector();
        List<Vector3f> currentPositions = new ArrayList<>(chainIds.size());
        Quaternionf rootParentRotation = null;

        for (String id : chainIds)
        {
            PivotFrame frame = frames.get(id);

            if (frame == null)
            {
                return;
            }

            currentPositions.add(new Vector3f(frame.position()));

            if (rootParentRotation == null)
            {
                rootParentRotation = new Quaternionf(frame.parentRotation());
            }
        }

        if (rootParentRotation == null)
        {
            return;
        }

        Vector3f override = controllerTargets == null ? null : controllerTargets.get(chain.target());
        Vector3f target = override != null ? new Vector3f(override) : new Vector3f(targetFrame.position());

        float poleAngleRad = (float) Math.toRadians(chain.poleAngle());
        IKSolver.Limit[] limits = buildLimits(model, chainIds, boneLimits);

        List<Vector3f> solved = IKSolver.solve(currentPositions, target, chain.pole(), poleAngleRad, chain.softness(), MAX_ITERATIONS, TOLERANCE, limits, limits == null ? null : rootParentRotation);

        Vector3f[] solvedArray = solved.toArray(new Vector3f[solved.size()]);
        ModelRotationBlender.applyWeightedRotations(model, rootParentRotation, chainIds, solvedArray, weight);
    }

    /**
     * Builds per-bone rotation limits for the chain's directed bones (root..tip-1),
     * matching the renderer's reconstruction so the clamp is exact. Returns
     * {@code null} when no bone in the chain is constrained (unconstrained fast
     * path) or when the model is not a cubic {@link Model}. Every returned entry
     * carries the bone's local rest direction (needed to advance the parent frame
     * during the clamp pass); {@code enabled} is set only where a constraint exists.
     */
    private static IKSolver.Limit[] buildLimits(IModel model, List<String> chainIds, Map<String, BoneConstraint> boneLimits)
    {
        if (boneLimits == null || boneLimits.isEmpty() || !(model instanceof Model cubic))
        {
            return null;
        }

        int directed = chainIds.size() - 1;

        if (directed < 1)
        {
            return null;
        }

        boolean any = false;

        for (int i = 0; i < directed; i++)
        {
            BoneConstraint c = boneLimits.get(chainIds.get(i));

            if (c != null && c.enabled())
            {
                any = true;
                break;
            }
        }

        if (!any)
        {
            return null;
        }

        IKSolver.Limit[] limits = new IKSolver.Limit[directed];

        for (int i = 0; i < directed; i++)
        {
            String id = chainIds.get(i);
            ModelGroup bone = cubic.getGroup(id);
            ModelGroup child = cubic.getGroup(chainIds.get(i + 1));

            if (bone == null || child == null)
            {
                return null;
            }

            Vector3f restDir = new Vector3f(child.initial.translate).sub(bone.initial.translate);

            if (restDir.lengthSquared() < 1.0e-12f)
            {
                restDir.set(0F, -1F, 0F);
            }

            restDir.normalize();

            BoneConstraint c = boneLimits.get(id);
            boolean enabled = c != null && c.enabled();

            limits[i] = enabled
                ? new IKSolver.Limit(true, restDir, c.minX(), c.minY(), c.minZ(), c.maxX(), c.maxY(), c.maxZ())
                : new IKSolver.Limit(false, restDir, 0F, 0F, 0F, 0F, 0F, 0F);
        }

        return limits;
    }

    private static float getChainPoseFix(ModelIKCache.CompiledChain chain, Map<String, Float> poseFixByBone)
    {
        if (poseFixByBone == null || poseFixByBone.isEmpty() || chain == null)
        {
            return 0F;
        }

        float maxFix = getFix(poseFixByBone, chain.target());

        for (String bone : chain.chainRootToEffector())
        {
            maxFix = Math.max(maxFix, getFix(poseFixByBone, bone));

            if (maxFix >= 1F)
            {
                return 1F;
            }
        }

        return maxFix;
    }

    private static float getFix(Map<String, Float> poseFixByBone, String bone)
    {
        Float value = poseFixByBone.get(bone);

        if (value == null)
        {
            return 0F;
        }

        if (value <= 0F)
        {
            return 0F;
        }

        return Math.min(value, 1F);
    }
}
