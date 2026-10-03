package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsRuntime;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModelIKRuntime
{
    private ModelIKRuntime()
    {}

    public static void clearCache()
    {
        ModelIKCache.clear();
    }

    public static void invalidate(String modelId)
    {
        clearCache();
    }

    public static void apply(ModelInstance instance, Map<String, Vector3f> controllerTargets, Map<String, Vector3f> poleTargets)
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

        /* FS resolves target and pole positions from the current frame every time.
         * Passing no persistent workspace keeps undo, redo and history jumps a pure
         * function of the restored keyframe data. */
        ModelIKDlsApplier.apply(model, chains, compiled.bones(), controllerTargets, poleTargets,
            targetWeights, poleWeights, controlOverrides, null, boneLimits);
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

    /**
     * The chains the form's config compiles to on this model — the bones each one spans, root
     * to tip, keyed by the tip bone that names it, in the config's order. For tools that act on
     * a chain as a whole (the bake) without re-deriving the topology the solver uses.
     */
    public static Map<String, List<String>> getChains(IModel model, ModelForm form)
    {
        ModelIKCache.Compiled compiled = compileFor(model, form);

        if (compiled == null || compiled.chains() == null || compiled.chains().isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<String, List<String>> chains = new LinkedHashMap<>();

        for (ModelIKCache.CompiledChain chain : compiled.chains())
        {
            chains.put(chain.tip(), List.copyOf(chain.chainRootToEffector()));
        }

        return chains;
    }

    /**
     * The per-chain IK scalars the form's config seeds — one entry per enabled chain, keyed by
     * the tip that names it. This is the shared source of truth for "what does a fresh
     * IK-controls keyframe start from"; the editor's keyframe sheets and the bake's
     * disable-these-chains pass both read it, so they cannot drift apart.
     */
    public static IKControls ikControls(ModelForm form)
    {
        IKControls controls = new IKControls();

        if (form == null || !(form.ik.get() instanceof MapType map))
        {
            return controls;
        }

        ModelIKConfig config = ModelIKIO.fromData(map);

        if (config == null || config.chains() == null)
        {
            return controls;
        }

        for (ModelIKConfig.Chain chain : config.chains())
        {
            if (chain == null || !chain.enabled() || chain.tip() == null || chain.tip().isEmpty())
            {
                continue;
            }

            IKControl control = controls.get(chain.tip());

            control.weight = chain.weight();
            control.softness = chain.softness();
            control.poleAngle = chain.poleAngle();
            control.pole = chain.pole();
            control.enabled = chain.enabled();
        }

        return controls;
    }

    /**
     * The compiled config of a form, or null when there is nothing to compile. Both callers
     * above need the same three guards, and {@link ModelIKCache#getFromData} answers null for a
     * null model or a null map rather than throwing — so the null has to be carried, not
     * dereferenced.
     *
     * <p>Deliberately {@code getFromData} and not {@code ModelIKCache.compile}: the other two
     * readers in this class ({@link #isRotationConstrained} and {@link #getControllers}) go
     * through {@code getFromData} too, and it serves the per-frame {@code WeakHashMap} memo, so
     * every caller of this class sees the same {@code Compiled} the solver does. That keeps
     * {@code compile} private.
     */
    private static ModelIKCache.Compiled compileFor(IModel model, ModelForm form)
    {
        if (model == null || form == null || !(form.ik.get() instanceof MapType map))
        {
            return null;
        }

        return ModelIKCache.getFromData(model, map);
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
