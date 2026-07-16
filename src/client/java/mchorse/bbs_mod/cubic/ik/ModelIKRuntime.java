package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsConfig.BoneConstraint;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsRuntime;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import org.joml.Vector3f;

import java.util.ArrayList;
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

        Map<String, BoneConstraint> boneLimits = ModelConstraintsRuntime.getBones(instance);
        Map<String, IKControl> controlOverrides = null;
        Map<String, Float> targetWeights = null;
        Map<String, Float> poleWeights = null;

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

        ModelIKApplier.apply(model, chains, state.workspaces, controllerTargets, poleTargets, targetWeights, poleWeights, controlOverrides, boneLimits);
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

        return compiled.controllers();
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

        return compiled.poleControllers();
    }
}
