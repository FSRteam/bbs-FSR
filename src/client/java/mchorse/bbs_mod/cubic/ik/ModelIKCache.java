package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ModelIKCache
{
    private ModelIKCache()
    {
    }

    /* No shortened constructor on purpose: the flags in the middle of this record
     * (stretch, squash, classic) each select a different behaviour, and an alias that
     * silently pins some of them is how a chain configured upstream loses a flag it
     * was authored with. Every caller spells all 16 components out. */
    public record CompiledChain(String tip, String target, boolean pole, String poleTarget, float poleAngle, float softness, float weight, boolean tipRotation, boolean stretch, boolean squash, boolean classic, List<String> chainRootToEffector, List<String> workRootToEffector, String tailId, Set<String> wantedBones, int rootDepth)
    {
    }

    public record Compiled(List<CompiledChain> chains, Map<String, JointDoF> bones, List<String> controllers, List<String> poleControllers)
    {
    }

    private static final WeakHashMap<MapType, EmbeddedCompiled> EMBEDDED = new WeakHashMap<>();
    private static IModel lastModel;
    private static ModelForm lastForm;
    private static long lastEpoch;
    private static long lastRevision;
    private static long lastPoseVersion;
    private static Compiled lastCompiled;

    private record EmbeddedCompiled(IModel model, Compiled compiled)
    {
    }

    public static void clear()
    {
        EMBEDDED.clear();
        lastModel = null;
        lastForm = null;
        lastCompiled = null;
    }

    public static Compiled getFromData(IModel model, MapType data)
    {
        if (model == null || data == null)
        {
            return null;
        }

        EmbeddedCompiled cached = EMBEDDED.get(data);

        if (cached != null && cached.model == model)
        {
            return cached.compiled;
        }

        Compiled compiled = compile(model, ModelIKIO.fromData(data));

        EMBEDDED.put(data, new EmbeddedCompiled(model, compiled));

        return compiled;
    }

    public static Compiled compile(IModel model, ModelForm form)
    {
        if (model == null || form == null)
        {
            return null;
        }

        long poseVersion = form.getPoseVersion();
        long revision = form.bones.getRevision();
        long epoch = RenderFrame.getEpoch();

        if (RenderFrame.isEnabled() && lastModel == model && lastForm == form && lastEpoch == epoch
            && lastRevision == revision && lastPoseVersion == poseVersion)
        {
            return lastCompiled;
        }

        Compiled compiled = compileFresh(model, form);

        lastModel = model;
        lastForm = form;
        lastEpoch = epoch;
        lastRevision = revision;
        lastPoseVersion = poseVersion;
        lastCompiled = compiled;

        return compiled;
    }

    /** Typed bone properties own production topology; legacy exchange maps use their adapter below. */
    private static Compiled compileFresh(IModel model, ModelForm form)
    {
        List<CompiledChain> chains = new ArrayList<>();
        Map<String, JointDoF> joints = new HashMap<>();
        Set<String> controllers = new LinkedHashSet<>();
        Set<String> poleControllers = new LinkedHashSet<>();

        for (BaseValue value : form.bones.getAll())
        {
            if (!(value instanceof FormBone bone))
            {
                continue;
            }

            JointDoF joint = bone.joint.get();

            if (!joint.isFree())
            {
                joints.put(bone.getId(), joint);
            }

            if (bone.hasChain())
            {
                IKControl control = bone.ik.get();
                addChain(model, chains, controllers, poleControllers, bone.getId(), bone.ikTarget.get(), bone.ikChainLength.get(),
                    control.pole, bone.ikPoleTarget.get(), control.poleAngle, control.softness, control.weight,
                    bone.ikTipRotation.get(), bone.ikStretch.get(), bone.ikSquash.get(), bone.ikClassic.get());
            }
        }

        return pack(chains, joints, controllers, poleControllers);
    }

    static JointDoF fromLegacyJoint(ModelIKConfig.JointDoF value)
    {
        JointDoF joint = new JointDoF();
        joint.lockX = value.lockX();
        joint.lockY = value.lockY();
        joint.lockZ = value.lockZ();
        joint.limitX = value.limitX();
        joint.limitY = value.limitY();
        joint.limitZ = value.limitZ();
        joint.minX = value.minX();
        joint.minY = value.minY();
        joint.minZ = value.minZ();
        joint.maxX = value.maxX();
        joint.maxY = value.maxY();
        joint.maxZ = value.maxZ();
        joint.stiffnessX = value.stiffnessX();
        joint.stiffnessY = value.stiffnessY();
        joint.stiffnessZ = value.stiffnessZ();

        return joint;
    }

    private static Compiled compile(IModel model, ModelIKConfig config)
    {
        Map<String, JointDoF> bones = new HashMap<>();

        if (config != null)
        {
            config.bones().forEach((id, joint) -> bones.put(id, fromLegacyJoint(joint)));
        }

        if (config == null || config.chains() == null || config.chains().isEmpty())
        {
            return new Compiled(Collections.emptyList(), bones, Collections.emptyList(), Collections.emptyList());
        }

        List<CompiledChain> out = new ArrayList<>(config.chains().size());
        Set<String> controllers = new LinkedHashSet<>();
        Set<String> poleControllers = new LinkedHashSet<>();

        for (ModelIKConfig.Chain chain : config.chains())
        {
            /* Enabled and weight are live controls. A disabled authored chain can become
             * active through a film keyframe without changing its immutable topology. */
            if (chain == null)
            {
                continue;
            }

            /* Ordered (stretch, squash, classic) here, but ModelIKConfig.Chain is
             * (stretch, classic, squash) -- all three are boolean, so a swap compiles.
             * testCompileForwardsTheChainBehaviourFlags pins this order. */
            addChain(model, out, controllers, poleControllers, chain.tip(), chain.target(), chain.chainLength(),
                chain.pole(), chain.poleTarget(), chain.poleAngle(), chain.softness(), chain.weight(),
                chain.tipRotation(), chain.stretch(), chain.squash(), chain.classic());
        }

        return pack(out, bones, controllers, poleControllers);
    }

    private static void addChain(IModel model, List<CompiledChain> out, Set<String> controllers, Set<String> poleControllers,
        String tip, String target, int chainLength, boolean pole, String poleTarget, float poleAngle, float softness,
        float weight, boolean tipRotation, boolean stretch, boolean squash, boolean classic)
    {
        if (!model.getAllGroupKeys().contains(tip) || !model.getAllGroupKeys().contains(target))
        {
            return;
        }

        List<String> chainIds = buildChainIds(model, tip, chainLength);

        if (chainIds.size() < 2 || chainIds.contains(target))
        {
            return;
        }

        chainIds = Collections.unmodifiableList(chainIds);

        if (poleTarget != null && !poleTarget.isEmpty()
            && (!model.getAllGroupKeys().contains(poleTarget) || chainIds.contains(poleTarget)))
        {
            poleTarget = "";
        }

        String tailId = tipRotation ? autoTailId(model, chainIds) : null;
        List<String> workIds = tailId == null
            ? chainIds
            : Collections.unmodifiableList(new ArrayList<>(chainIds.subList(0, chainIds.size() - 1)));
        Set<String> wanted = new LinkedHashSet<>(chainIds);

        wanted.add(target);

        if (poleTarget != null && !poleTarget.isEmpty())
        {
            wanted.add(poleTarget);

            poleControllers.add(poleTarget);
        }

        controllers.add(target);
        out.add(new CompiledChain(tip, target, pole, poleTarget, poleAngle, softness, weight,
            tipRotation, stretch, squash, classic, chainIds, workIds, tailId,
            Collections.unmodifiableSet(wanted), rootDepth(model, workIds)));
    }

    private static Compiled pack(List<CompiledChain> out, Map<String, JointDoF> bones,
        Set<String> controllers, Set<String> poleControllers)
    {
        out.sort(Comparator.comparingInt(CompiledChain::rootDepth));

        return new Compiled(
            Collections.unmodifiableList(out),
            bones,
            Collections.unmodifiableList(new ArrayList<>(controllers)),
            Collections.unmodifiableList(new ArrayList<>(poleControllers))
        );
    }

    public static List<String> chainIdsFor(IModel model, String tip, int chainLength)
    {
        return buildChainIds(model, tip, chainLength);
    }

    private static int rootDepth(IModel model, List<String> ids)
    {
        String group = ids.isEmpty() ? null : ids.get(0);
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

    private static String autoTailId(IModel model, List<String> chainIds)
    {
        if (chainIds.size() < 4 || !(model instanceof Model cubic))
        {
            return null;
        }

        String lastId = chainIds.get(chainIds.size() - 1);
        ModelGroup last = cubic.getGroup(lastId);

        if (last == null || !last.cubes.isEmpty() || !last.meshes.isEmpty() || !last.children.isEmpty())
        {
            return null;
        }

        return lastId;
    }

    private static List<String> buildChainIds(IModel model, String tip, int chainLength)
    {
        List<String> list = new ArrayList<>();
        String group = tip;

        while (group != null && !group.isEmpty())
        {
            list.add(group);

            if (chainLength > 0 && list.size() >= chainLength)
            {
                break;
            }

            String parent = model.getParentGroupKey(group);

            if (parent == null || parent.equals(group))
            {
                break;
            }

            group = parent;
        }

        Collections.reverse(list);

        return list;
    }
}
