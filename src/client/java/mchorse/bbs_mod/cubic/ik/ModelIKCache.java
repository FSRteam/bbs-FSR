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
import java.util.Set;
import java.util.WeakHashMap;

final class ModelIKCache
{
    private ModelIKCache()
    {
    }

    public record CompiledChain(String tip, String target, boolean pole, String poleTarget, float poleAngle, float softness, float weight, boolean tipRotation, boolean stretch, List<String> chainRootToEffector, List<String> workRootToEffector, String tailId, Set<String> wantedBones, int rootDepth)
    {
    }

    public record Compiled(List<CompiledChain> chains, List<String> controllers, List<String> poleControllers)
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

        ModelIKConfig config = ModelIKIO.fromData(data);
        Compiled compiled = compile(model, config);

        EmbeddedCompiled next = new EmbeddedCompiled(model, compiled);
        EMBEDDED.put(data, next);

        return compiled;
    }

    private static Compiled compile(IModel model, ModelIKConfig config)
    {
        if (config == null || config.chains() == null || config.chains().isEmpty())
        {
            return new Compiled(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        List<CompiledChain> out = new ArrayList<>(config.chains().size());
        Set<String> controllers = new LinkedHashSet<>();
        Set<String> poleControllers = new LinkedHashSet<>();

        for (ModelIKConfig.Chain chain : config.chains())
        {
            if (chain == null)
            {
                continue;
            }

            if (!chain.enabled())
            {
                continue;
            }

            if (!model.getAllGroupKeys().contains(chain.tip()) || !model.getAllGroupKeys().contains(chain.target()))
            {
                continue;
            }

            List<String> chainIds = buildChainIds(model, chain.tip(), chain.chainLength());

            if (chainIds.size() < 2)
            {
                continue;
            }

            chainIds = Collections.unmodifiableList(chainIds);

            /* A pole target that does not resolve to a real bone falls back to
             * the automatic hinge (an empty pole target), so a stale reference
             * never breaks the chain. */
            String poleTarget = chain.poleTarget();

            if (poleTarget != null && !poleTarget.isEmpty() && !model.getAllGroupKeys().contains(poleTarget))
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
            out.add(new CompiledChain(chain.tip(), chain.target(), chain.pole(), poleTarget, chain.poleAngle(), chain.softness(), chain.weight(), chain.tipRotation(), chain.stretch(), chainIds, workIds, tailId, Collections.unmodifiableSet(wanted), rootDepth(model, workIds)));
        }

        out.sort(Comparator.comparingInt(CompiledChain::rootDepth));

        return new Compiled(
            Collections.unmodifiableList(out),
            Collections.unmodifiableList(new ArrayList<>(controllers)),
            Collections.unmodifiableList(new ArrayList<>(poleControllers))
        );
    }

    /** Depth of the chain's solve root, used once at compile time for ancestor-first ordering. */
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

    /** Detects the bare cubic tail marker once while compiling the chain topology. */
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

    /**
     * Walks up the hierarchy from {@code tip}, collecting up to {@code chainLength}
     * bones ({@code 0} = all the way to the root), and returns them ordered
     * root-to-tip.
     */
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
