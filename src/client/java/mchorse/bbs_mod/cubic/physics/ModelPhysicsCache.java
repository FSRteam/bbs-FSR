package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.api.client.events.FormPoseEvents;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.joml.Matrices;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

final class ModelPhysicsCache
{
    public static final class CompiledChain
    {
        private final String id;
        private final String attach;
        private final String targetBone;
        private final List<String> chainRootToEnd;
        private final float[] restLengths;
        private final Vector3f[] restDirections;
        private final Vector3f tipRestDirection;
        private final float gravity;
        private final float damping;
        private final float stiffness;
        private final int iterations;
        private final boolean relativeGravity;
        private final boolean hasGravityRotation;
        private final Quaternionf gravityRotation;
        private final boolean collisions;
        private final float radius;
        private final float weight;

        public CompiledChain(String id, String attach, String targetBone, List<String> chainRootToEnd, float[] restLengths, Vector3f[] restDirections, Vector3f tipRestDirection, ModelPhysicsConfig.Bone bone)
        {
            this(id, attach, targetBone, chainRootToEnd, restLengths, restDirections, tipRestDirection,
                bone.gravity(), bone.damping(), bone.stiffness(), bone.iterations(), bone.relativeGravity(),
                bone.relativeGravityRotateX(), bone.relativeGravityRotateY(), bone.relativeGravityRotateZ(),
                bone.collisions(), bone.radius(), bone.weight());
        }

        private CompiledChain(String id, String attach, String targetBone, ChainTopology topology, FormBone bone)
        {
            this(id, attach, targetBone, topology.ids(), topology.lengths(), topology.directions(), topology.tipDirection(),
                bone.physics.get().gravity, bone.physics.get().damping, bone.physics.get().stiffness,
                bone.physicsIterations.get(), bone.physicsRelativeGravity.get(), bone.physicsGravityRotateX.get(),
                bone.physicsGravityRotateY.get(), bone.physicsGravityRotateZ.get(), bone.physicsCollisions.get(),
                bone.physicsRadius.get(), bone.physics.get().weight);
        }

        private CompiledChain(String id, String attach, String targetBone, List<String> chainRootToEnd,
            float[] restLengths, Vector3f[] restDirections, Vector3f tipRestDirection, float gravity, float damping,
            float stiffness, int iterations, boolean relativeGravity, float rotateX, float rotateY, float rotateZ,
            boolean collisions, float radius, float weight)
        {
            this.id = id;
            this.attach = attach;
            this.targetBone = targetBone;
            this.chainRootToEnd = chainRootToEnd;
            this.restLengths = restLengths;
            this.restDirections = restDirections;
            this.tipRestDirection = tipRestDirection;
            this.gravity = gravity;
            this.damping = damping;
            this.stiffness = Math.max(0F, Math.min(stiffness, 1F));
            this.iterations = Math.max(1, iterations);
            this.relativeGravity = relativeGravity;
            this.hasGravityRotation = rotateX != 0F || rotateY != 0F || rotateZ != 0F;
            this.gravityRotation = this.hasGravityRotation
                ? Matrices.toQuaternionZYXDegrees(rotateX, rotateY, rotateZ)
                : new Quaternionf();
            this.collisions = collisions;
            this.radius = Math.max(0F, radius);
            this.weight = Math.max(0F, Math.min(weight, 1F));
        }

        public String id()
        {
            return this.id;
        }

        public String attach()
        {
            return this.attach;
        }

        public String targetBone()
        {
            return this.targetBone;
        }

        public List<String> chainRootToEnd()
        {
            return this.chainRootToEnd;
        }

        public float[] restLengths()
        {
            return this.restLengths;
        }

        public Vector3f[] restDirections()
        {
            return this.restDirections;
        }

        public Vector3f tipRestDirection()
        {
            return this.tipRestDirection;
        }

        public float gravity()
        {
            return this.gravity;
        }

        public float damping()
        {
            return this.damping;
        }

        public float stiffness()
        {
            return this.stiffness;
        }

        public int iterations()
        {
            return this.iterations;
        }

        public boolean relativeGravity()
        {
            return this.relativeGravity;
        }

        public boolean hasGravityRotation()
        {
            return this.hasGravityRotation;
        }

        public void applyGravityRotation(Vector3f direction)
        {
            if (this.hasGravityRotation)
            {
                this.gravityRotation.transform(direction);
            }
        }

        public boolean collisions()
        {
            return this.collisions;
        }

        public float radius()
        {
            return this.radius;
        }

        public float weight()
        {
            return this.weight;
        }
    }

    public record Compiled(List<CompiledChain> chains, ModelPhysicsConfig.Wind wind, Set<String> wantedBones, Set<String> chainIds)
    {
    }

    private static final WeakHashMap<MapType, EmbeddedCompiled> EMBEDDED = new WeakHashMap<>();
    private static final WeakHashMap<ModelForm, FormCompiled> FORMS = new WeakHashMap<>();

    private record FormCompiled(IModel model, long revision, Compiled source)
    {}

    private record EmbeddedCompiled(IModel model, Compiled compiled)
    {
    }

    private record ChainTopology(List<String> ids, float[] lengths, Vector3f[] directions, Vector3f tipDirection)
    {
    }

    private ModelPhysicsCache()
    {
    }

    public static void clear()
    {
        EMBEDDED.clear();
        FORMS.clear();
    }

    /** Canonical topology is compiled from the bone properties; exchange blobs are compatibility input. */
    public static Compiled compile(IModel model, ModelForm form)
    {
        if (model == null || form == null)
        {
            return null;
        }

        long revision = form.bones.getRevision();
        FormCompiled cached = FORMS.get(form);
        Compiled source;

        if (cached != null && cached.model == model && cached.revision == revision)
        {
            source = cached.source;
        }
        else
        {
            List<CompiledChain> chains = new ArrayList<>();

            for (BaseValue value : form.bones.getAll())
            {
                if (value instanceof FormBone bone && bone.hasPhysicsChain())
                {
                    String rootId = bone.getId();
                    String endId = bone.physicsEnd.get();
                    ChainTopology topology = compileTopology(model, rootId, endId);

                    if (topology != null)
                    {
                        chains.add(new CompiledChain(rootId + ":" + endId, rootId, bone.physicsTargetBone.get(), topology, bone));
                    }
                }
            }

            chains.sort((a, b) -> a.id().compareTo(b.id()));
            source = pack(chains, ModelPhysicsConfig.Wind.NONE);
            FORMS.put(form, new FormCompiled(model, revision, source));
        }

        List<CompiledChain> unclaimed = null;

        for (CompiledChain chain : source.chains())
        {
            FormBone bone = form.bones.getBone(chain.attach());
            boolean claimed = bone != null && FormPoseEvents.CLAIM_CHAIN.invoker().claims(form, model, bone);

            if (claimed && unclaimed == null)
            {
                unclaimed = new ArrayList<>(source.chains().subList(0, source.chains().indexOf(chain)));
            }
            else if (!claimed && unclaimed != null)
            {
                unclaimed.add(chain);
            }
        }

        /* Claim ownership is live addon state, independent of the immutable topology revision. */
        return unclaimed == null ? source : pack(unclaimed, source.wind());
    }

    private static Compiled pack(List<CompiledChain> chains, ModelPhysicsConfig.Wind wind)
    {
        Set<String> wanted = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();

        for (CompiledChain chain : chains)
        {
            ids.add(chain.id());
            wanted.addAll(chain.chainRootToEnd());

            if (chain.targetBone() != null && !chain.targetBone().isEmpty())
            {
                wanted.add(chain.targetBone());
            }
        }

        return new Compiled(Collections.unmodifiableList(chains), wind,
            Collections.unmodifiableSet(wanted), Collections.unmodifiableSet(ids));
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

        ModelPhysicsConfig config = ModelPhysicsIO.fromData(data);
        List<CompiledChain> compiled = compile(model, config);
        ModelPhysicsConfig.Wind wind = config != null ? config.wind() : ModelPhysicsConfig.Wind.NONE;
        Set<String> wanted = new LinkedHashSet<>();
        Set<String> chainIds = new LinkedHashSet<>();

        for (CompiledChain chain : compiled)
        {
            chainIds.add(chain.id());
            wanted.addAll(chain.chainRootToEnd());

            if (chain.targetBone() != null && !chain.targetBone().isEmpty())
            {
                wanted.add(chain.targetBone());
            }
        }

        Compiled result = new Compiled(
            Collections.unmodifiableList(compiled),
            wind,
            Collections.unmodifiableSet(wanted),
            Collections.unmodifiableSet(chainIds)
        );

        EmbeddedCompiled next = new EmbeddedCompiled(model, result);
        EMBEDDED.put(data, next);

        return result;
    }

    private static List<CompiledChain> compile(IModel model, ModelPhysicsConfig config)
    {
        if (config == null || config.bones() == null || config.bones().isEmpty())
        {
            return Collections.emptyList();
        }

        List<CompiledChain> out = new ArrayList<>();

        List<String> roots = new ArrayList<>(config.bones().keySet());
        Collections.sort(roots);

        for (String rootId : roots)
        {
            ModelPhysicsConfig.Bone chain = config.bones().get(rootId);

            if (chain == null)
            {
                continue;
            }

            String endId = chain.end();
            ChainTopology topology = compileTopology(model, rootId, endId);

            if (topology == null)
            {
                continue;
            }

            String id = rootId + ":" + endId;
            out.add(new CompiledChain(id, rootId, chain.targetBone(), topology.ids(), topology.lengths(),
                topology.directions(), topology.tipDirection(), chain));
        }

        return out;
    }

    private static ChainTopology compileTopology(IModel model, String rootId, String endId)
    {
        if (!model.getAllGroupKeys().contains(rootId) || !model.getAllGroupKeys().contains(endId))
        {
            return null;
        }

        List<String> ids = buildChainIds(model, endId, rootId);

        if (ids.isEmpty())
        {
            return null;
        }

        float[] lengths = computeRestLengths(model, ids);
        Vector3f[] directions = computeRestDirections(model, ids);

        if (lengths == null || directions == null)
        {
            return null;
        }

        Vector3f tipDirection = PhysicsRig.tipRestDirectionLocal(model, ids);

        if (tipDirection != null && tipDirection.lengthSquared() >= 1.0e-12F)
        {
            tipDirection.normalize();
        }

        return new ChainTopology(Collections.unmodifiableList(ids), lengths, directions, tipDirection);
    }

    private static List<String> buildChainIds(IModel model, String endId, String rootId)
    {
        List<String> list = new ArrayList<>();
        String group = endId;

        while (group != null && !group.isEmpty())
        {
            list.add(group);

            if (group.equals(rootId))
            {
                Collections.reverse(list);
                return list;
            }

            String parent = model.getParentGroupKey(group);

            if (parent == null || parent.equals(group))
            {
                break;
            }

            group = parent;
        }

        return Collections.emptyList();
    }

    private static float[] computeRestLengths(IModel model, List<String> ids)
    {
        PhysicsRig rig = PhysicsRig.of(model);

        if (rig == null)
        {
            return null;
        }

        int n = ids.size();
        float[] lengths = new float[n];

        if (n == 1)
        {
            float len = rig.restLength(ids.get(0), null);

            if (len < 0F)
            {
                return null;
            }

            lengths[0] = len;

            return lengths;
        }

        for (int i = 0; i < n - 1; i++)
        {
            float len = rig.restLength(ids.get(i), ids.get(i + 1));

            if (len < 0F)
            {
                return null;
            }

            lengths[i] = len;
        }

        lengths[n - 1] = lengths[n - 2];

        return lengths;
    }

    private static Vector3f[] computeRestDirections(IModel model, List<String> ids)
    {
        PhysicsRig rig = PhysicsRig.of(model);

        if (rig == null)
        {
            return null;
        }

        Vector3f[] directions = new Vector3f[ids.size()];

        for (int i = 0; i < ids.size(); i++)
        {
            String childId = i + 1 < ids.size() ? ids.get(i + 1) : null;
            Vector3f direction = rig.restDirectionLocal(ids.get(i), childId);

            if (direction == null || direction.lengthSquared() < 1.0e-12F)
            {
                return null;
            }

            directions[i] = direction.normalize();
        }

        return directions;
    }
}
