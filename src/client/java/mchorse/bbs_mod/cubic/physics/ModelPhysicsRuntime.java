package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsRuntime;
import mchorse.bbs_mod.cubic.render.CubicRenderer.PivotFrame;
import mchorse.bbs_mod.cubic.render.ModelPivotFrames;
import mchorse.bbs_mod.cubic.render.ModelRotationBlender;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.ModelForm;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Orchestrates bone physics: each runtime belongs to one form renderer and owns the mutable history
 * of its explicit simulation owners. The solver itself ({@link ChainSolver}, {@link ChainState},
 * {@link PhysicsForces}) holds the maths; only immutable compiled config is shared globally.
 */
public final class ModelPhysicsRuntime
{
    static final class InstanceState
    {
        public final Map<String, ChainState> chains = new HashMap<>();
        public final Map<String, PivotFrame> frames = new HashMap<>();
        public final ModelPivotFrames.Workspace frameCollector = new ModelPivotFrames.Workspace();
        public final Vector3f windDirection = new Vector3f();
        public ModelPhysicsCache.Compiled compiled;
    }

    /** Weak registry preserves the existing global cache-invalidation API without making histories global. */
    private static final Set<ModelPhysicsRuntime> RUNTIMES = Collections.newSetFromMap(new WeakHashMap<>());

    private final WeakHashMap<Object, Map<String, InstanceState>> states = new WeakHashMap<>();

    public ModelPhysicsRuntime()
    {
        synchronized (RUNTIMES)
        {
            RUNTIMES.add(this);
        }
    }

    public static void clearCache()
    {
        ModelPhysicsCache.clear();

        synchronized (RUNTIMES)
        {
            for (ModelPhysicsRuntime runtime : RUNTIMES)
            {
                runtime.states.clear();
            }
        }
    }

    public static void invalidate(String modelId)
    {
        synchronized (RUNTIMES)
        {
            for (ModelPhysicsRuntime runtime : RUNTIMES)
            {
                for (Map<String, InstanceState> byModel : runtime.states.values())
                {
                    if (byModel != null)
                    {
                        byModel.remove(modelId);
                    }
                }
            }
        }
    }

    public void apply(IEntity entity, Object simulationOwner, ModelInstance instance, float transition, Matrix4f baseTransform)
    {
        if (entity == null || instance == null || instance.model == null)
        {
            return;
        }

        IModel model = instance.model;

        ModelPhysicsCache.Compiled compiled = null;
        if (instance.form instanceof ModelForm modelForm && modelForm.physics.get() instanceof MapType map)
        {
            compiled = ModelPhysicsCache.getFromData(model, map);
        }

        if (compiled == null || compiled.chains() == null || compiled.chains().isEmpty())
        {
            return;
        }

        Map<String, ModelConstraintsConfig.BoneConstraint> constraints = ModelConstraintsRuntime.getBones(instance);

        Object owner = simulationOwner == null ? entity : simulationOwner;
        Map<String, InstanceState> byModel = this.states.computeIfAbsent(owner, (e) -> new HashMap<>());
        InstanceState state = byModel.computeIfAbsent(instance.id, (k) -> new InstanceState());

        /* The wind track (if keyframed) replaces the configured wind wholesale at playback, mirroring how the
         * physics track layers over the per-chain config. */
        ModelPhysicsConfig.Wind wind = compiled.wind();

        if (instance.form instanceof ModelForm modelForm && modelForm.windControlOverride != null)
        {
            WindControl override = modelForm.windControlOverride;

            wind = new ModelPhysicsConfig.Wind(override.strength, override.x, override.y, override.z, override.turbulence, override.turbulenceSpeed, override.turbulenceScale, override.local);
        }

        wind = resolveWindDirection(wind, baseTransform, state.windDirection);

        applyCompiled(entity.getWorld(), entity.getAge(), transition, model, instance, compiled, wind, constraints, state, baseTransform);
    }

    /**
     * When the wind direction is local to the model, rotates it by the model's world orientation (the
     * rotation baked into {@code baseTransform}, the same transform the chain positions live in), so the
     * wind follows the model as it turns. The solver only ever sees a plain world-space direction. A
     * world-space or inactive wind is returned unchanged.
     */
    private static ModelPhysicsConfig.Wind resolveWindDirection(ModelPhysicsConfig.Wind wind, Matrix4f baseTransform, Vector3f scratchDirection)
    {
        if (wind == null || !wind.local() || !wind.active() || baseTransform == null)
        {
            return wind;
        }

        Vector3f dir = scratchDirection.set(wind.x(), wind.y(), wind.z());

        baseTransform.transformDirection(dir);

        return new ModelPhysicsConfig.Wind(wind.strength(), dir.x, dir.y, dir.z, wind.turbulence(), wind.turbulenceSpeed(), wind.turbulenceScale(), false);
    }

    private static void applyCompiled(Level world, int age, float transition, IModel model, ModelInstance instance, ModelPhysicsCache.Compiled compiled, ModelPhysicsConfig.Wind wind, Map<String, ModelConstraintsConfig.BoneConstraint> constraints, InstanceState state, Matrix4f baseTransform)
    {
        if (state.compiled != compiled)
        {
            state.chains.keySet().retainAll(compiled.chainIds());
            state.frames.clear();
            state.compiled = compiled;
        }

        ModelPivotFrames.collect(model, compiled.wantedBones(), state.frames, baseTransform, false, state.frameCollector);

        for (ModelPhysicsCache.CompiledChain chain : compiled.chains())
        {
            applyChain(world, age, transition, model, instance, chain, wind, constraints, state.frames, state);
        }
    }

    private static void applyChain(Level world, int age, float transition, IModel model, ModelInstance instance, ModelPhysicsCache.CompiledChain chain, ModelPhysicsConfig.Wind wind, Map<String, ModelConstraintsConfig.BoneConstraint> constraints, Map<String, PivotFrame> frames, InstanceState instanceState)
    {
        List<String> ids = chain.chainRootToEnd();
        int pivotCount = ids.size();
        int pointCount = pivotCount + 1;

        if (pivotCount < 1)
        {
            return;
        }

        /* The film physics track layers a per-chain control over the config, keyed by the chain's
         * root bone, replacing its dynamic scalars wholesale (mirrors the IK track). */
        PhysicsControl control = null;

        if (instance != null && instance.form instanceof ModelForm modelForm && !modelForm.physicsControlOverrides.isEmpty())
        {
            control = modelForm.physicsControlOverrides.get(ids.get(0));
        }

        if (control != null && !control.enabled)
        {
            return;
        }

        float weight = control != null ? control.weight : chain.weight();

        if (weight <= 0F)
        {
            return;
        }

        float gravity = control != null ? control.gravity : chain.gravity();
        float damping = control != null ? control.damping : chain.damping();
        float stiffness = control != null ? control.stiffness : chain.stiffness();

        ChainState state = instanceState.chains.computeIfAbsent(chain.id(), (k) -> new ChainState());

        if (state.pos == null || state.pos.length != pointCount)
        {
            state.pos = new Vector3f[pointCount];
            state.prev = new Vector3f[pointCount];
            state.settledLocal = new Vector3f[pointCount];
            state.settledPrevLocal = new Vector3f[pointCount];
            state.render = new Vector3f[pointCount];
            state.poseLocal = new Vector3f[pointCount];

            for (int i = 0; i < pointCount; i++)
            {
                state.pos[i] = new Vector3f();
                state.prev[i] = new Vector3f();
                state.settledLocal[i] = new Vector3f();
                state.settledPrevLocal[i] = new Vector3f();
                state.render[i] = new Vector3f();
                state.poseLocal[i] = new Vector3f();
            }

            state.lastAge = Integer.MIN_VALUE;
        }

        List<PivotFrame> chainFrames = state.chainFrames;

        chainFrames.clear();

        for (int i = 0; i < pivotCount; i++)
        {
            PivotFrame frame = frames.get(ids.get(i));

            if (frame == null)
            {
                return;
            }

            chainFrames.add(frame);
        }

        PivotFrame rootFrame = chainFrames.get(0);
        Vector3f anchor = rootFrame.position();
        Quaternionf anchorRotation = rootFrame.worldRotation();

        Vector3f target = null;
        if (instance != null && instance.form instanceof ModelForm modelForm)
        {
            String rootBone = ids.get(0);
            Vector3f worldPos = modelForm.physicsTargetOverrides.get(rootBone);

            if (worldPos != null)
            {
                float targetWeight = modelForm.physicsTargetWeights.getOrDefault(rootBone, 1F);

                if (targetWeight >= 1F)
                {
                    target = state.pinTarget.set(worldPos);
                }
                else if (targetWeight > 0F)
                {
                    /* The binding is fading in or out (it crossed a no-target keyframe). Easing the pin point
                     * from the chain's current tip toward the full target by the fade amount lets the chain
                     * travel there smoothly instead of snapping, with no soft-target mode in the solver. */
                    Vector3f tip = state.pos[state.pos.length - 1];

                    target = state.lastAge == Integer.MIN_VALUE
                        ? state.pinTarget.set(worldPos)
                        : state.pinTarget.set(tip).lerp(worldPos, targetWeight);
                }
                /* targetWeight <= 0: fully faded out — leave the chain free this frame. */
            }
        }

        if (target != null)
        {
            if (state.lastAge == Integer.MIN_VALUE)
            {
                state.pos[state.pos.length - 1].set(target);
                state.prev[state.pos.length - 1].set(target);
            }
        }
        else if (chain.targetBone() != null && !chain.targetBone().isEmpty())
        {
            PivotFrame targetFrame = frames.get(chain.targetBone());
            if (targetFrame != null)
            {
                target = targetFrame.position();
                if (state.lastAge == Integer.MIN_VALUE)
                {
                    state.pos[state.pos.length - 1].set(target);
                    state.prev[state.pos.length - 1].set(target);
                }
            }
        }

        ChainSolver.computePoseTargets(chain, chainFrames, anchor, anchorRotation, target != null, state);
        ChainSolver.step(world, age, transition, ids, chain, gravity, damping, stiffness, wind, constraints, anchor, anchorRotation, chainFrames.get(0).parentRotation(), target, chainFrames, state);

        Vector3f[] positions = ChainSolver.renderInterpolate(state, state.renderAlpha, anchor, anchorRotation, target);
        ModelRotationBlender.applyWeightedRotations(model, chainFrames.get(0).parentRotation(), ids, positions, weight);
    }
}
