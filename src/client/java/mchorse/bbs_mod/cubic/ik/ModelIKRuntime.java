package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig.BoneConstraint;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsRuntime;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.WeakHashMap;

public final class ModelIKRuntime
{
    private static final class InstanceState
    {
        public ModelIKCache.Compiled compiled;
        public final List<ModelIKApplier.ChainWorkspace> workspaces = new ArrayList<>();
    }

    private static final Set<ModelIKRuntime> RUNTIMES = Collections.newSetFromMap(new WeakHashMap<>());

    private final WeakHashMap<Object, Map<String, InstanceState>> states = new WeakHashMap<>();

    public ModelIKRuntime()
    {
        synchronized (RUNTIMES)
        {
            RUNTIMES.add(this);
        }
    }

    public static void clearCache()
    {
        ModelIKCache.clear();

        synchronized (RUNTIMES)
        {
            for (ModelIKRuntime runtime : RUNTIMES)
            {
                runtime.states.clear();
            }
        }
    }

    public static void invalidate(String modelId)
    {
        ModelIKCache.clear();

        synchronized (RUNTIMES)
        {
            for (ModelIKRuntime runtime : RUNTIMES)
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

    public void apply(Object simulationOwner, ModelInstance instance, Map<String, Vector3f> controllerTargets, Map<String, Vector3f> poleTargets)
    {
        if (instance == null || instance.model == null)
        {
            return;
        }

        IModel model = instance.model;

        ModelIKCache.Compiled compiled = null;
        if (instance.form instanceof ModelForm form && form.ik.get() instanceof MapType map)
        {
            compiled = ModelIKCache.getFromData(model, map);
        }

        if (compiled == null)
        {
            return;
        }

        List<ModelIKCache.CompiledChain> chains = compiled.chains();

        if (chains == null || chains.isEmpty())
        {
            return;
        }

        Map<String, IKControl> controlOverrides = null;
        Map<String, Float> targetWeights = null;
        Map<String, Float> poleWeights = null;
        Map<String, BoneConstraint> boneLimits = ModelConstraintsRuntime.getBones(instance);

        if (instance.form instanceof ModelForm form)
        {
            controlOverrides = form.ikControlOverrides;
            targetWeights = form.ikTargetWeights;
            poleWeights = form.poleTargetWeights;
        }

        Object owner = simulationOwner == null ? instance : simulationOwner;
        Map<String, InstanceState> byModel = this.states.computeIfAbsent(owner, (key) -> new HashMap<>());
        InstanceState state = byModel.computeIfAbsent(instance.id, (key) -> new InstanceState());

        if (state.compiled != compiled)
        {
            state.compiled = compiled;
            state.workspaces.clear();

            for (int i = 0; i < chains.size(); i++)
            {
                state.workspaces.add(new ModelIKApplier.ChainWorkspace());
            }
        }

        if (requiresDls(chains, compiled.bones()))
        {
            ModelIKDlsApplier.apply(model, chains, compiled.bones(), controllerTargets, poleTargets, targetWeights, poleWeights, controlOverrides, state.workspaces, boneLimits);
        }
        else
        {
            ModelIKApplier.apply(model, chains, state.workspaces, controllerTargets, poleTargets, targetWeights, poleWeights, controlOverrides, boneLimits);
        }
    }

    private static boolean requiresDls(List<ModelIKCache.CompiledChain> chains, Map<String, ModelIKConfig.JointDoF> bones)
    {
        if (bones != null && !bones.isEmpty())
        {
            return true;
        }

        for (int i = 0; i < chains.size(); i++)
        {
            ModelIKCache.CompiledChain a = chains.get(i);

            if (a.classic())
            {
                return true;
            }

            for (int j = i + 1; j < chains.size(); j++)
            {
                for (String bone : a.chainRootToEffector())
                {
                    if (chains.get(j).chainRootToEffector().contains(bone))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Whether pointing the chain that ends at {@code tip} (spanning {@code
     * chainLength} bones up) at {@code target} is cyclic — the target is one of
     * the chain's own bones, so the solve's variables would move the very point
     * it chases. Such a chain does not compile; the panel uses this to mark the
     * pick loudly.
     */
    public static boolean isCyclicTarget(IModel model, String tip, int chainLength, String target)
    {
        if (model == null || tip == null || tip.isEmpty() || target == null || target.isEmpty())
        {
            return false;
        }

        return ModelIKCache.chainIdsFor(model, tip, chainLength).contains(target);
    }

    /**
     * Whether the chain ending at {@code tip} has the classic two-bone shape:
     * exactly two directed bones after the auto-tail drop. A classic-marked
     * chain of any other shape solves on the core — the panel uses this to mark
     * the toggle so the fallback never surprises silently.
     */
    public static boolean isClassicShape(IModel model, String tip, int chainLength, boolean tipRotation)
    {
        if (model == null || tip == null || tip.isEmpty())
        {
            return false;
        }

        List<String> ids = ModelIKCache.chainIdsFor(model, tip, chainLength);
        int size = ids.size();

        if (tipRotation && ModelIKDlsApplier.autoTailId(model, ids) != null)
        {
            size--;
        }

        return size == 3;
    }

    /**
     * The bones the chain ending at {@code tip} spans, root to tip — the panel's
     * overlap check (overlapping chains merge into one core tree, which kicks a
     * classic chain off its analytic path).
     */
    public static List<String> chainBones(IModel model, String tip, int chainLength)
    {
        if (model == null || tip == null || tip.isEmpty())
        {
            return Collections.emptyList();
        }

        return ModelIKCache.chainIdsFor(model, tip, chainLength);
    }

    /**
     * Opens the IK solve dump for the duration of one transform gesture, so the
     * log holds exactly the drag being investigated — the transform editor calls
     * this when a drag begins and ends. A no-op unless the applier's debug flag
     * is compiled on, so the call sites cost nothing in a normal build.
     */
    public static void logGesture(boolean active)
    {
        ModelIKDlsApplier.setLogging(active);
    }

    /**
     * Whether the bone's ROTATION is owned by an enabled IK chain: the render
     * follows the solved orientation there, so an FK rotation gesture has no
     * honest response to offer — the gizmo refuses those and dims its rings.
     * The FK channels stay editable through the pads (they remain the blend
     * base and the pose IK falls back to when it's off). The chain tip counts
     * only when the chain also solves tip rotation; a chain whose weight is
     * zeroed (config, or the film's per-tick override on the form) doesn't
     * constrain anything.
     */
    public static boolean isRotationConstrained(IModel model, ModelForm form, String bone)
    {
        if (model == null || form == null || bone == null || bone.isEmpty())
        {
            return false;
        }

        if (!(form.ik.get() instanceof MapType map))
        {
            return false;
        }

        ModelIKCache.Compiled compiled = ModelIKCache.getFromData(model, map);

        if (compiled == null || compiled.chains() == null)
        {
            return false;
        }

        for (ModelIKCache.CompiledChain chain : compiled.chains())
        {
            if (chain == null || chain.weight() <= 0F || chain.chainRootToEffector() == null)
            {
                continue;
            }

            /* A chain the film disabled or zeroed for this tick — its per-tick `ik`
             * track override — no longer owns any rotation, so its bones become
             * FK-editable again. That override lives in ikControlOverrides keyed by
             * the tip (the same one resolveChain reads), NOT in ikTargetWeights,
             * which is only the target's position-fade weight and never reaches 0. */
            IKControl override = form.ikControlOverrides == null ? null : form.ikControlOverrides.get(chain.tip());

            if (override != null && (!override.enabled || override.weight <= 0F))
            {
                continue;
            }

            if (!chain.chainRootToEffector().contains(bone))
            {
                continue;
            }

            if (!bone.equals(chain.tip()) || chain.tipRotation())
            {
                return true;
            }
        }

        return false;
    }

    public static List<String> getControllers(ModelInstance instance)
    {
        if (instance == null || instance.model == null)
        {
            return Collections.emptyList();
        }

        IModel model = instance.model;

        ModelIKCache.Compiled compiled = null;
        if (instance.form instanceof ModelForm form && form.ik.get() instanceof MapType map)
        {
            compiled = ModelIKCache.getFromData(model, map);
        }

        if (compiled == null || compiled.chains() == null || compiled.chains().isEmpty())
        {
            return Collections.emptyList();
        }

        Set<String> unique = new LinkedHashSet<>();

        for (ModelIKCache.CompiledChain chain : compiled.chains())
        {
            if (chain != null && chain.target() != null && !chain.target().isEmpty())
            {
                unique.add(chain.target());
            }
        }

        return unique.isEmpty() ? Collections.emptyList() : new ArrayList<>(unique);
    }

    /** The pole-target bones of all enabled chains that have one — the film keys a pole anchor sheet off each. */
    public static List<String> getPoleControllers(ModelInstance instance)
    {
        if (instance == null || instance.model == null)
        {
            return Collections.emptyList();
        }

        IModel model = instance.model;

        ModelIKCache.Compiled compiled = null;
        if (instance.form instanceof ModelForm form && form.ik.get() instanceof MapType map)
        {
            compiled = ModelIKCache.getFromData(model, map);
        }

        if (compiled == null || compiled.chains() == null || compiled.chains().isEmpty())
        {
            return Collections.emptyList();
        }

        Set<String> unique = new LinkedHashSet<>();

        for (ModelIKCache.CompiledChain chain : compiled.chains())
        {
            if (chain != null && chain.pole() && chain.poleTarget() != null && !chain.poleTarget().isEmpty())
            {
                unique.add(chain.poleTarget());
            }
        }

        return unique.isEmpty() ? Collections.emptyList() : new ArrayList<>(unique);
    }
}
