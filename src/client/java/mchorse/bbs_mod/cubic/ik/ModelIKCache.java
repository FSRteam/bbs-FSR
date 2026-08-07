package mchorse.bbs_mod.cubic.ik;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    public record CompiledChain(String tip, String target, boolean pole, String poleTarget, float poleAngle, float softness, float weight, boolean tipRotation, boolean stretch, boolean classic, List<String> chainRootToEffector, List<String> workRootToEffector, String tailId, Set<String> wantedBones, int rootDepth)
    {
        public CompiledChain(String tip, String target, boolean pole, String poleTarget, float poleAngle, float softness, float weight, boolean tipRotation, boolean stretch, List<String> chainRootToEffector, List<String> workRootToEffector, String tailId, Set<String> wantedBones, int rootDepth)
        {
            this(tip, target, pole, poleTarget, poleAngle, softness, weight, tipRotation, stretch, false, chainRootToEffector, workRootToEffector, tailId, wantedBones, rootDepth);
        }
    }

    public record Compiled(List<CompiledChain> chains, Map<String, ModelIKConfig.JointDoF> bones, List<String> controllers, List<String> poleControllers)
    {
    }

    private static final WeakHashMap<MapType, EmbeddedCompiled> EMBEDDED = new WeakHashMap<>();

    private record EmbeddedCompiled(IModel model, Compiled compiled)
    {
    }

    public static void clear()
    {
        EMBEDDED.clear();
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

    private static Compiled compile(IModel model, ModelIKConfig config)
    {
        Map<String, ModelIKConfig.JointDoF> bones = config == null || config.bones().isEmpty()
            ? Collections.emptyMap() : Map.copyOf(config.bones());

        if (config == null || config.chains() == null || config.chains().isEmpty())
        {
            return new Compiled(Collections.emptyList(), bones, Collections.emptyList(), Collections.emptyList());
        }

        List<CompiledChain> out = new ArrayList<>(config.chains().size());
        Set<String> controllers = new LinkedHashSet<>();
        Set<String> poleControllers = new LinkedHashSet<>();

        for (ModelIKConfig.Chain chain : config.chains())
        {
            if (chain == null || !chain.enabled())
            {
                continue;
            }

            if (!model.getAllGroupKeys().contains(chain.tip()) || !model.getAllGroupKeys().contains(chain.target()))
            {
                continue;
            }

            List<String> chainIds = buildChainIds(model, chain.tip(), chain.chainLength());

            if (chainIds.size() < 2 || chainIds.contains(chain.target()))
            {
                continue;
            }

            chainIds = Collections.unmodifiableList(chainIds);

            String poleTarget = chain.poleTarget();

            if (poleTarget != null && !poleTarget.isEmpty()
                && (!model.getAllGroupKeys().contains(poleTarget) || chainIds.contains(poleTarget)))
            {
                poleTarget = "";
            }

            String tailId = chain.tipRotation() ? autoTailId(model, chainIds) : null;
            List<String> workIds = tailId == null
                ? chainIds
                : Collections.unmodifiableList(new ArrayList<>(chainIds.subList(0, chainIds.size() - 1)));
            Set<String> wanted = new LinkedHashSet<>(chainIds);

            wanted.add(chain.target());

            if (poleTarget != null && !poleTarget.isEmpty())
            {
                wanted.add(poleTarget);

                if (chain.pole())
                {
                    poleControllers.add(poleTarget);
                }
            }

            controllers.add(chain.target());
            out.add(new CompiledChain(
                chain.tip(), chain.target(), chain.pole(), poleTarget, chain.poleAngle(), chain.softness(), chain.weight(),
                chain.tipRotation(), chain.stretch(), chain.classic(), chainIds, workIds, tailId,
                Collections.unmodifiableSet(wanted), rootDepth(model, workIds)
            ));
        }

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
