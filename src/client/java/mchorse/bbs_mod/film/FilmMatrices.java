package mchorse.bbs_mod.film;

import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.api.client.events.FormPoseEvents;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.FormFrameCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.joml.Matrices;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Where a replay, a form or one of its bones ends up in the world, as matrices.
 *
 * <p>Split out of {@link BaseFilmController}: none of this runs a film, it is the geometry every
 * consumer of a film asks for - the renderer placing an actor, the gizmo placing its handles,
 * the drag capturing a world transform - and it is entirely static. Anchor resolution lives here
 * too (a form anchored to another replay, or to a bone of one, composes onto that replay's
 * matrix), which is why the entity map is passed through.
 *
 * <p>The relative-replay registry is deliberately kept next to the anchor resolution that reads
 * it: a relative replay has no absolute world host and cross-policy anchors fail closed, so the
 * mark and the query must live in one place.</p>
 */
public class FilmMatrices
{
    /* FSR-specific replay policy registries: whether a replay is relative (it has no absolute
     * world host), the per-entity simulation owner that keeps its Verlet/IK history stable, and
     * the owner used while target channels are resolved. */
    private static final Map<IEntity, Boolean> RELATIVE_REPLAY_ENTITIES = new WeakHashMap<>();
    private static final Map<IEntity, Object> RELATIVE_SIMULATION_OWNERS = new WeakHashMap<>();
    private static final Map<IEntity, Object> TARGET_RESOLUTION_OWNERS = new WeakHashMap<>();

    public static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i)
    {
        return getTotalMatrix(entities, value, defaultMatrix, cx, cy, cz, transition, i, false);
    }

    public static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i, boolean fullMatrix)
    {
        return getTotalMatrix(entities, value, defaultMatrix, cx, cy, cz, transition, i, fullMatrix, true, null);
    }

    public static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i, boolean fullMatrix, FormFrameCache frame)
    {
        return getTotalMatrix(entities, value, defaultMatrix, cx, cy, cz, transition, i, fullMatrix, true, frame);
    }

    static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i, boolean fullMatrix, boolean placementAware)
    {
        return getTotalMatrix(entities, value, defaultMatrix, cx, cy, cz, transition, i, fullMatrix, placementAware, null);
    }

    private static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i, boolean fullMatrix, boolean placementAware, FormFrameCache frame)
    {
        /* Substituting the anchor here rather than at each caller covers the whole chain, because
         * this method resolves the anchor level by level - including the levels reached from a
         * `previous` anchor just below. The stored anchor is never rewritten. */
        value = FormPoseEvents.ANCHOR.invoker().resolve(value);

        /* Stupid recursion stop, I don't think anyone would need more than that */
        if (i > 5)
        {
            return new Pair<>(defaultMatrix, 1F);
        }

        boolean same = value.previous == null || Objects.equals(value, value.previous);
        boolean only = value.previous != null && (value.x == 0F || value.x == 1F);
        Pair<Matrix4f, Float> result = new Pair<>(null, 1F);

        if (same || only)
        {
            Anchor anchor = same || value.x == 1F ? value : value.previous;
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, anchor, defaultMatrix, transition, i, fullMatrix, placementAware, frame);

            /* Whether the endpoint resolved to the actor's own frame decides its shadow opacity,
             * and has to be read before the anchor's offset is folded in. */
            result.b = matrix == defaultMatrix ? 1F : 0F;

            if (!isRelativeAnchorTarget(entities, anchor))
            {
                matrix = applyAnchorTransform(matrix, anchor);
            }

            if (matrix != defaultMatrix)
            {
                result.a = matrix;
            }
        }
        else
        {
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, value, defaultMatrix, transition, i, fullMatrix, placementAware, frame);
            Matrix4f lastMatrix = getEntityMatrix(entities, cx, cy, cz, value.previous, defaultMatrix, transition, i, fullMatrix, placementAware, frame);

            /* Shadow opacity follows whether each endpoint resolved to the actor's own frame.
             * Clamp opacity, but let Back/Elastic curves overshoot the spatial transition. */
            result.b = MathUtils.clamp(Lerps.lerp(lastMatrix == defaultMatrix ? 1F : 0F, matrix == defaultMatrix ? 1F : 0F, value.x), 0F, 1F);

            if (!isRelativeAnchorTarget(entities, value))
            {
                matrix = applyAnchorTransform(matrix, value);
            }

            if (!isRelativeAnchorTarget(entities, value.previous))
            {
                lastMatrix = applyAnchorTransform(lastMatrix, value.previous);
            }

            result.a = Matrices.lerp(lastMatrix, matrix, value.x);
        }

        return result;
    }

    private static boolean isRelativeAnchorTarget(IntObjectMap<IEntity> entities, Anchor anchor)
    {
        return anchor != null && isRelativeReplayEntity(entities.get(anchor.replay));
    }

    static boolean hasRelativeAnchorTarget(IntObjectMap<IEntity> entities, Anchor anchor)
    {
        Anchor current = anchor;

        for (int i = 0; current != null && i <= 5; i++)
        {
            if (isRelativeAnchorTarget(entities, current))
            {
                return true;
            }

            current = current.previous;
        }

        return false;
    }

    private static Matrix4f applyAnchorTransform(Matrix4f matrix, Anchor anchor)
    {
        if (matrix == null || anchor == null || anchor.transform.isDefault())
        {
            return matrix;
        }

        /* Both endpoints may share defaultMatrix when their targets are absent, so the offset
         * must not be folded into the caller's matrix in place. */
        return new Matrix4f(matrix).mul(anchor.transform.createMatrix());
    }

    public static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i)
    {
        return getEntityMatrix(entities, cameraX, cameraY, cameraZ, anchor, defaultMatrix, transition, i, false);
    }

    public static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i, boolean fullMatrix)
    {
        return getEntityMatrix(entities, cameraX, cameraY, cameraZ, anchor, defaultMatrix, transition, i, fullMatrix, true, null);
    }

    public static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i, boolean fullMatrix, FormFrameCache frame)
    {
        return getEntityMatrix(entities, cameraX, cameraY, cameraZ, anchor, defaultMatrix, transition, i, fullMatrix, true, frame);
    }

    private static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i, boolean fullMatrix, boolean placementAware)
    {
        return getEntityMatrix(entities, cameraX, cameraY, cameraZ, anchor, defaultMatrix, transition, i, fullMatrix, placementAware, null);
    }

    private static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i, boolean fullMatrix, boolean placementAware, FormFrameCache frame)
    {
        IEntity entity = entities.get(anchor.replay);

        /* A relative replay intentionally has no absolute world host. Letting an
         * absolute child/target consume its local matrix would silently mix owner,
         * collision and coordinate policies, so cross-policy anchors fail closed. */
        if (entity != null && !isRelativeReplayEntity(entity))
        {
            Matrix4f basic = getMatrixForRenderWithRotation(entity, cameraX, cameraY, cameraZ, transition);

            Form form = entity.getForm();

            if (form != null)
            {
                Pair<Matrix4f, Float> totalMatrix = getTotalMatrix(entities, form.anchor.get(), basic, cameraX, cameraY, cameraZ, transition, i + 1, fullMatrix, placementAware, frame);

                if (totalMatrix.a != null)
                {
                    basic = totalMatrix.a;
                }

                MatrixCache map;

                if (placementAware)
                {
                    Matrix4f semanticBase = absoluteSemanticMatrix(basic, cameraX, cameraY, cameraZ);

                    map = FormFrameCache.collect(frame, form, entity, entity, semanticBase, true, true, transition);
                }
                else
                {
                    /* Target-channel resolution must not advance current-age physics while
                     * the frame's absolute target maps are still being assembled. It uses
                     * the deterministic animation/local-IK attachment pose; placement and
                     * visual consumers use the full path above after target setup finishes. */
                    map = FormFrameCache.collect(
                        frame,
                        form,
                        entity,
                        targetResolutionOwner(entity),
                        null,
                        false,
                        false,
                        transition
                    );
                }
                Matrix4f matrix = map.get(anchor.attachment).matrix();

                if (matrix != null)
                {
                    basic.mul(matrix);

                    if (!fullMatrix && anchor.scale)
                    {
                        Matrix3f mat = new Matrix3f();
                        Vector3f v = new Vector3f();
                        basic.get3x3(mat);

                        mat.getColumn(0, v); v.normalize(); mat.setColumn(0, v);
                        mat.getColumn(1, v); v.normalize(); mat.setColumn(1, v);
                        mat.getColumn(2, v); v.normalize(); mat.setColumn(2, v);

                        basic.set3x3(mat);
                    }

                    if (!fullMatrix && anchor.translate)
                    {
                        Vector3f t = new Vector3f();
                        basic.getTranslation(t);
                        basic.set(defaultMatrix);
                        basic.setTranslation(t);
                    }
                }
            }

            return basic;
        }

        return defaultMatrix;
    }

    public static Matrix3f getReplayWorldAxes(IEntity entity, float tickDelta)
    {
        Matrix3f axes = new Matrix3f();

        if (entity == null)
        {
            return axes;
        }

        float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), tickDelta);

        return axes.rotateY(MathUtils.toRad(-bodyYaw));
    }

    public static Matrix4f getMatrixForRenderWithRotation(IEntity entity, double cameraX, double cameraY, double cameraZ, float tickDelta)
    {
        double x = Lerps.lerp(entity.getPrevX(), entity.getX(), tickDelta) - cameraX;
        double y = Lerps.lerp(entity.getPrevY(), entity.getY(), tickDelta) - cameraY;
        double z = Lerps.lerp(entity.getPrevZ(), entity.getZ(), tickDelta) - cameraZ;

        Matrix4f matrix = new Matrix4f();

        float bodyYaw = Lerps.lerp(entity.getPrevBodyYaw(), entity.getBodyYaw(), tickDelta);

        matrix.translate((float) x, (float) y, (float) z);
        matrix.rotateY(MathUtils.toRad(-bodyYaw));

        return matrix;
    }

    public static Matrix4f getGizmoBoneCompositeMatrix(
        IntObjectMap<IEntity> entities,
        IEntity entity,
        Replay replay,
        double cameraX,
        double cameraY,
        double cameraZ,
        float transition,
        String bonePath,
        boolean useBoneMatrix
    )
    {
        Matrix4f matrix = getBoneCompositeMatrix(entities, entity, replay, cameraX, cameraY, cameraZ, transition, bonePath, useBoneMatrix);

        return matrix == null ? null : MatrixStackUtils.stripScale(matrix);
    }

    /**
     * The rotation offset of the same bone sample {@link #getGizmoBoneCompositeMatrix} resolves.
     * Both go through {@link #sampleBonePlacement} on purpose: a gizmo pairs the offset with that
     * matrix, so sampling them from different placements (or a different simulation owner) lets the
     * rotation basis disagree with the mesh the user sees.
     */
    public static Vector3f getGizmoBoneRotationOffset(
        IntObjectMap<IEntity> entities,
        IEntity entity,
        Replay replay,
        double cameraX,
        double cameraY,
        double cameraZ,
        float transition,
        String bonePath
    )
    {
        BonePlacement placement = sampleBonePlacement(entities, entity, replay, cameraX, cameraY, cameraZ, transition, bonePath);
        Vector3f offset = placement == null ? null : placement.entry().rotationOffset();

        return offset == null ? new Vector3f() : new Vector3f(offset);
    }

    public static Vector3f getGizmoBoneEvaluatedRotation(
        IntObjectMap<IEntity> entities,
        IEntity entity,
        Replay replay,
        double cameraX,
        double cameraY,
        double cameraZ,
        float transition,
        String bonePath
    )
    {
        BonePlacement placement = sampleBonePlacement(entities, entity, replay, cameraX, cameraY, cameraZ, transition, bonePath);
        Vector3f rotation = placement == null ? null : placement.entry().evaluatedRotation();

        return rotation == null ? null : new Vector3f(rotation);
    }

    /**
     * The same composite as {@link #getGizmoBoneCompositeMatrix} but with the bone's scale kept.
     * The gizmo drops scale on purpose (a gizmo must not inherit it); world-space transform capture
     * needs the full matrix, so it goes through this variant instead.
     */
    public static Matrix4f getBoneCompositeMatrix(
        IntObjectMap<IEntity> entities,
        IEntity entity,
        Replay replay,
        double cameraX,
        double cameraY,
        double cameraZ,
        float transition,
        String bonePath,
        boolean useBoneMatrix
    )
    {
        BonePlacement placement = sampleBonePlacement(entities, entity, replay, cameraX, cameraY, cameraZ, transition, bonePath);

        if (placement == null)
        {
            return null;
        }

        Matrix4f bone = useBoneMatrix ? placement.entry().matrix() : placement.entry().origin();

        if (bone == null)
        {
            return null;
        }

        return new Matrix4f(placement.target()).mul(bone);
    }

    /**
     * Resolve the entity's render placement and sample one bone in it. Every film-side bone consumer
     * shares this so they agree on the placement, the simulation owner and the world-input policy.
     */
    private static BonePlacement sampleBonePlacement(
        IntObjectMap<IEntity> entities,
        IEntity entity,
        Replay replay,
        double cameraX,
        double cameraY,
        double cameraZ,
        float transition,
        String bonePath
    )
    {
        if (entity == null || entity.getForm() == null || bonePath == null)
        {
            return null;
        }

        Form form = entity.getForm();
        boolean relative = replay != null && replay.relative.get();
        double cx = cameraX;
        double cy = cameraY;
        double cz = cameraZ;

        if (relative)
        {
            cx = replay.keyframes.x.interpolate(0F) + replay.relativeOffset.get().x;
            cy = replay.keyframes.y.interpolate(0F) + replay.relativeOffset.get().y;
            cz = replay.keyframes.z.interpolate(0F) + replay.relativeOffset.get().z;
        }

        Matrix4f defaultMatrix = getMatrixForRenderWithRotation(entity, cx, cy, cz, transition);
        Matrix4f target;

        if (!relative)
        {
            Pair<Matrix4f, Float> pair = getTotalMatrix(entities, form.anchor.get(), defaultMatrix, cx, cy, cz, transition, 0);

            target = pair.a != null ? pair.a : defaultMatrix;
        }
        else
        {
            target = defaultMatrix;
        }

        String mapKey = bonePath.contains(PerLimbService.POSE_BONES)
            ? bonePath.replace(PerLimbService.POSE_BONES, "")
            : bonePath;

        Form root = FormUtils.getRoot(form);
        FormRenderer<?> renderer = FormUtilsClient.getRenderer(root);

        if (renderer == null)
        {
            return null;
        }

        Matrix4f semanticBase = relative
            ? new Matrix4f(target)
            : absoluteSemanticMatrix(target, cx, cy, cz);
        /* Keep placement sampling on the exact same history owner as
         * renderEntity(). Using the Replay value here creates a second Verlet/IK
         * history, so gizmos and other bone consumers can disagree with the mesh. */
        Object simulationOwner = relative ? relativeSimulationOwner(entity) : entity;
        MatrixCache map = renderer.collectMatrices(
            entity,
            simulationOwner,
            semanticBase,
            !relative,
            !relative,
            transition
        );
        MatrixCacheEntry entry = map.get(mapKey);

        return entry == null ? null : new BonePlacement(target, entry);
    }

    /** One bone sampled inside a resolved entity placement. */
    private record BonePlacement(Matrix4f target, MatrixCacheEntry entry)
    {}

    private static Matrix4f absoluteSemanticMatrix(Matrix4f cameraRelative, double cameraX, double cameraY, double cameraZ)
    {
        return new Matrix4f()
            .translation((float) cameraX, (float) cameraY, (float) cameraZ)
            .mul(cameraRelative);
    }

    /** The camera-relative matrix of the whole form after its anchor chain and offset are applied. */
    public static Matrix4f getGizmoAnchorCompositeMatrix(
        IntObjectMap<IEntity> entities,
        IEntity entity,
        Replay replay,
        double cameraX,
        double cameraY,
        double cameraZ,
        float transition
    )
    {
        if (entity == null || entity.getForm() == null)
        {
            return null;
        }

        Form form = entity.getForm();
        boolean relative = replay != null && replay.relative.get();
        double cx = cameraX;
        double cy = cameraY;
        double cz = cameraZ;

        if (relative)
        {
            cx = replay.keyframes.x.interpolate(0F) + replay.relativeOffset.get().x;
            cy = replay.keyframes.y.interpolate(0F) + replay.relativeOffset.get().y;
            cz = replay.keyframes.z.interpolate(0F) + replay.relativeOffset.get().z;
        }

        Matrix4f defaultMatrix = getMatrixForRenderWithRotation(entity, cx, cy, cz, transition);
        Matrix4f full = defaultMatrix;

        if (!relative)
        {
            Pair<Matrix4f, Float> pair = getTotalMatrix(entities, form.anchor.get(), defaultMatrix, cx, cy, cz, transition, 0);

            full = pair.a != null ? pair.a : defaultMatrix;
        }

        return MatrixStackUtils.stripScale(full);
    }

    static void markRelativeReplayEntity(IEntity entity, boolean relative)
    {
        if (entity != null)
        {
            boolean wasRelative = Boolean.TRUE.equals(RELATIVE_REPLAY_ENTITIES.get(entity));

            RELATIVE_REPLAY_ENTITIES.put(entity, relative);

            if (relative && !wasRelative)
            {
                RELATIVE_SIMULATION_OWNERS.put(entity, new Object());
            }
            else if (!relative)
            {
                RELATIVE_SIMULATION_OWNERS.remove(entity);
            }
        }
    }

    public static boolean isRelativeReplayEntity(IEntity entity)
    {
        return entity != null && Boolean.TRUE.equals(RELATIVE_REPLAY_ENTITIES.get(entity));
    }

    private static Object targetResolutionOwner(IEntity entity)
    {
        return TARGET_RESOLUTION_OWNERS.computeIfAbsent(entity, (ignored) -> new Object());
    }

    static Object relativeSimulationOwner(IEntity entity)
    {
        return RELATIVE_SIMULATION_OWNERS.computeIfAbsent(entity, (ignored) -> new Object());
    }
}
