package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.cubic.ik.IKControls;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.PhysicsControls;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.replays.FormControlKeys;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.mixin.client.ClientPlayerEntityAccessor;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public abstract class BaseFilmController
{
    public final Film film;

    protected IntObjectMap<IEntity> entities = new IntObjectHashMap<>();

    public boolean paused;
    public int exception = -1;

    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f TEMP_VECTOR = new Vector3f();
    private static final Map<IEntity, Boolean> RELATIVE_REPLAY_ENTITIES = new WeakHashMap<>();
    private static final Map<IEntity, Object> RELATIVE_SIMULATION_OWNERS = new WeakHashMap<>();
    private static final Map<IEntity, Object> TARGET_RESOLUTION_OWNERS = new WeakHashMap<>();

    /* Rendering helpers */

    public static void renderEntity(FilmControllerContext context)
    {
        IntObjectMap<IEntity> entities = context.entities;
        IEntity entity = context.entity;
        Camera camera = context.camera;
        PoseStack stack = context.stack;
        float transition = context.transition;

        Form form = entity.getForm();

        if (form == null)
        {
            return;
        }

        Vector3d position = Vectors.TEMP_3D.set(
            Lerps.lerp(entity.getPrevX(), entity.getX(), transition),
            Lerps.lerp(entity.getPrevY(), entity.getY(), transition),
            Lerps.lerp(entity.getPrevZ(), entity.getZ(), transition)
        );

        double cx = camera.getPosition().x;
        double cy = camera.getPosition().y;
        double cz = camera.getPosition().z;

        boolean relative = context.replay != null && context.relative;

        markRelativeReplayEntity(entity, relative);

        if (relative)
        {
            cx = context.replay.keyframes.x.interpolate(0F) + context.replay.relativeOffset.get().x;
            cy = context.replay.keyframes.y.interpolate(0F) + context.replay.relativeOffset.get().y;
            cz = context.replay.keyframes.z.interpolate(0F) + context.replay.relativeOffset.get().z;
        }

        Matrix4f target = null;
        Matrix4f defaultMatrix = getMatrixForRenderWithRotation(entity, cx, cy, cz, transition);
        float opacity = 1F;

        if (!relative)
        {
            Pair<Matrix4f, Float> pair = getTotalMatrix(entities, form.anchor.get(), defaultMatrix, cx, cy, cz, transition, 0);

            target = pair.a;
            opacity = pair.b;
        }

        if (target != null)
        {
            Vector3f v = target.getTranslation(new Vector3f());
            Vector3f v2 = defaultMatrix.getTranslation(new Vector3f());

            position.x += v.x - v2.x;
            position.y += v.y - v2.y;
            position.z += v.z - v2.z;
        }
        else
        {
            target = defaultMatrix;
        }

        BlockPos pos = BlockPos.containing(position.x, position.y + 0.5D, position.z);
        int sky = entity.level().getBrightness(LightLayer.SKY, pos);
        int torch = entity.level().getBrightness(LightLayer.BLOCK, pos);
        int light = LightTexture.pack(torch, sky);
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(entity.getHurtTimer() > 0));

        FormRenderingContext formContext = new FormRenderingContext()
            .set(FormRenderType.ENTITY, entity, stack, light, overlay, transition)
            .cameraRelativeWorld()
            .camera(camera)
            .stencilMap(context.map)
            .color(context.color)
            .timeline(context.timelineProperties, context.timelineTick, context.timelinePlaying);

        if (relative)
        {
            PoseStack semanticWorld = new PoseStack();

            MatrixStackUtils.multiply(semanticWorld, target);
            formContext
                .semanticWorld(semanticWorld)
                .simulationOwner(relativeSimulationOwner(entity))
                .localSimulation();
        }
        else
        {
            formContext.semanticWorldFromCameraRelative(target, cx, cy, cz);
        }

        stack.pushPose();
        try
        {
            if (relative)
            {
                /* Cancel the global camera view without discarding a view/local matrix
                 * already supplied by the active render pass. Vanilla starts with an
                 * identity stack; Iris may seed it before this replay is rendered. */
                stack.last().pose().rotate(context.camera.rotation());
                stack.last().normal().rotate(context.camera.rotation());
            }

            MatrixStackUtils.multiply(stack, target);

            /* Gizmo-only pass: an actor replay renders the world ActorEntity
             * instead of the editor entity, but the gizmo placement still needs
             * this frame's snapshot, so skip the visible form (and its shadow /
             * name tag below) while keeping renderAxes / renderAnchorGizmo. */
            if (!context.gizmoOnly)
            {
                FormUtilsClient.render(form, formContext);
            }

            if (UIBaseMenu.shouldRenderAxes())
            {
                if (context.bone != null) renderAxes(context.bone, context.local, context.map, form, formContext, stack);
                if (!context.gizmoOnly && context.bone2 != null && context.map == null) renderPreviewAxes(context.bone2, context.local2, form, formContext, stack);
            }
        }
        finally
        {
            stack.popPose();
        }

        if (UIBaseMenu.shouldRenderAxes() && context.anchorGizmo)
        {
            renderAnchorGizmo(entities, entity, target, defaultMatrix, cx, cy, cz, transition, context.anchorLocal, context.map, stack);
        }

        if (!relative && context.map == null && !context.gizmoOnly && opacity > 0F && context.shadowRadius > 0F && form.visible.get())
        {
            /* Skip the shadow when the form is hidden (form.visible, animatable via keyframes): the form
             * itself renders nothing then - see FormRenderer.render - so its shadow must vanish too.
             * The animated value is live here, applied to form.visible in startRenderFrame this frame.
             *
             * Place the shadow under the replay's perceived position: shift the actual shadow position
             * by how far the model (form transform + anchor-bone root motion) has moved from rest,
             * mapped from form-local into world axes via the render target. Moving the position itself
             * (not just translating the quad) makes the shadow's ground projection and shading match. */
            double shadowX = position.x;
            double shadowY = position.y;
            double shadowZ = position.z;

            FormRenderer renderer = FormUtilsClient.getRenderer(FormUtils.getRoot(form));

            if (renderer != null && !BBSRendering.isIrisShadowPass() && context.replay != null && context.replay.shadowFollow.get())
            {
                Vector3f displacement = renderer.getShadowDisplacement(
                    entity,
                    formContext.simulationOwner,
                    formContext.world == null ? null : new Matrix4f(formContext.world.last().pose()),
                    formContext.allowWorldTargetOverrides,
                    formContext.allowWorldCollisions,
                    transition
                );

                if (displacement != null)
                {
                    target.transformDirection(displacement);

                    shadowX += displacement.x;
                    shadowY += displacement.y;
                    shadowZ += displacement.z;
                }

                /* Extra world-space nudge to seat the shadow on the model's real floor (added after the
                 * form-local displacement is mapped to world, so it stays vertical regardless of facing). */
                Point offset = context.replay.shadowOffset.get();

                shadowX += offset.x;
                shadowY += offset.y;
                shadowZ += offset.z;
            }

            stack.pushPose();
            try
            {
                stack.translate(shadowX - cx, shadowY - cy, shadowZ - cz);
                ModelBlockEntityRenderer.renderShadow(context.consumers, stack, transition, shadowX, shadowY, shadowZ, 0F, 0F, 0F, context.shadowRadius, opacity);
            }
            finally
            {
                stack.popPose();
            }
        }

        if (!relative && !context.gizmoOnly && !context.nameTag.isEmpty() && context.map == null && form.visible.get())
        {
            /* Hide the name tag along with the form (form.visible, animatable via keyframes): when the
             * form renders nothing, its name tag must vanish too - same reasoning as the shadow above. */
            stack.pushPose();
            try
            {
                stack.translate(position.x - cx, position.y - cy, position.z - cz);
                renderNameTag(entity, Component.literal(StringUtils.processColoredText(context.nameTag)), stack, context.consumers, light);
            }
            finally
            {
                stack.popPose();
            }
        }

        RenderSystem.enableDepthTest();
    }

    private static void renderAxes(String bone, boolean local, StencilMap stencilMap, Form form, FormRenderingContext context, PoseStack stack)
    {
        String mapKey = bone != null && bone.contains(PerLimbService.POSE_BONES) ? bone.replace(PerLimbService.POSE_BONES, "") : bone;
        Form root = FormUtils.getRoot(form);
        MatrixCache map = FormUtilsClient.getRenderer(root).collectMatrices(
            context.entity,
            context.simulationOwner,
            context.world == null ? null : new Matrix4f(context.world.last().pose()),
            context.allowWorldTargetOverrides,
            context.allowWorldCollisions,
            context.getTransition()
        );
        Matrix4f matrix = local ? map.get(mapKey).matrix() : map.get(mapKey).origin();

        if (matrix != null)
        {
            stack.pushPose();
            try
            {
                MatrixStackUtils.multiply(stack, matrix);

                if (stencilMap == null)
                {
                    /* The visual is drawn later, in the panel's UI pass (see
                     * Gizmo#renderInterface) — here we only snapshot its placement. */
                    Gizmo.INSTANCE.captureVisual(stack);
                }
                else
                {
                    Gizmo.INSTANCE.renderStencil(stack, stencilMap);
                }
            }
            finally
            {
                RenderSystem.enableDepthTest();
                stack.popPose();
            }
        }
    }

    /**
     * Draw the replay's axes-preview bone as plain static axes.
     *
     * <p>Deliberately not {@link #renderAxes}: that one snapshots the placement of the gizmo the
     * user actually drags ({@link Gizmo#captureVisual}), and the preview runs after it in the same
     * pass, so sharing the method would leave every drag anchored on the preview bone instead.</p>
     */
    private static void renderPreviewAxes(String bone, boolean local, Form form, FormRenderingContext context, PoseStack stack)
    {
        String mapKey = bone != null && bone.contains(PerLimbService.POSE_BONES) ? bone.replace(PerLimbService.POSE_BONES, "") : bone;
        Form root = FormUtils.getRoot(form);
        MatrixCache map = FormUtilsClient.getRenderer(root).collectMatrices(
            context.entity,
            context.simulationOwner,
            context.world == null ? null : new Matrix4f(context.world.last().pose()),
            context.allowWorldTargetOverrides,
            context.allowWorldCollisions,
            context.getTransition()
        );
        MatrixCacheEntry entry = map.get(mapKey);
        Matrix4f matrix = entry == null ? null : (local ? entry.matrix() : entry.origin());

        if (matrix == null)
        {
            return;
        }

        if (local)
        {
            matrix = MatrixStackUtils.stripScale(matrix);
        }

        stack.pushPose();
        try
        {
            MatrixStackUtils.multiply(stack, matrix);

            Vector3f cameraRelative = stack.last().pose().getTranslation(new Vector3f());
            Matrix4f projection = RenderSystem.getProjectionMatrix();
            float fov = projection.m33() == 0 ? (float) (2D * Math.atan(1D / projection.m11())) : BBSSettings.getFov();
            float distanceScale = BBSSettings.getAxesDistanceScale(cameraRelative.length(), fov);

            stack.scale(distanceScale, distanceScale, distanceScale);
            Draw.coolerAxes(stack, 0.25F, 0.008F, 0.26F, 0.018F);
        }
        finally
        {
            RenderSystem.enableDepthTest();
            stack.popPose();
        }
    }

    /** Capture or stencil the whole-form gizmo at the resolved anchor transform. */
    private static void renderAnchorGizmo(IntObjectMap<IEntity> entities, IEntity entity, Matrix4f full, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, boolean local, StencilMap stencilMap, PoseStack stack)
    {
        Form form = entity.getForm();

        if (form == null || full == null)
        {
            return;
        }

        Matrix4f matrix;

        if (local)
        {
            matrix = MatrixStackUtils.stripScale(full);
        }
        else
        {
            Matrix4f parent = getEntityMatrix(entities, cx, cy, cz, form.anchor.get(), defaultMatrix, transition, 0, true);

            matrix = MatrixStackUtils.stripScale(parent);
            matrix.setTranslation(full.getTranslation(new Vector3f()));
        }

        stack.pushPose();
        try
        {
            MatrixStackUtils.multiply(stack, matrix);

            if (stencilMap == null)
            {
                Gizmo.INSTANCE.captureVisual(stack);
            }
            else
            {
                Gizmo.INSTANCE.renderStencil(stack, stencilMap);
            }
        }
        finally
        {
            RenderSystem.enableDepthTest();
            stack.popPose();
        }
    }

    public static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i)
    {
        return getTotalMatrix(entities, value, defaultMatrix, cx, cy, cz, transition, i, false);
    }

    public static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i, boolean fullMatrix)
    {
        return getTotalMatrix(entities, value, defaultMatrix, cx, cy, cz, transition, i, fullMatrix, true);
    }

    private static Pair<Matrix4f, Float> getTotalMatrix(IntObjectMap<IEntity> entities, Anchor value, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, int i, boolean fullMatrix, boolean placementAware)
    {
        /* Stupid recursion stop, I don't think anyone would need more than that */
        if (i > 5)
        {
            return new Pair<>(defaultMatrix, 1F);
        }

        boolean same = value.previous == null || Objects.equals(value, value.previous);
        boolean only = value.x <= 0F && value.previous != null;
        Pair<Matrix4f, Float> result = new Pair<>(null, 1F);

        if (same || only)
        {
            Anchor anchor = same ? value : value.previous;
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, anchor, defaultMatrix, transition, i, fullMatrix, placementAware);

            if (!isRelativeAnchorTarget(entities, anchor))
            {
                matrix = applyAnchorTransform(matrix, anchor);
            }

            if (matrix != defaultMatrix)
            {
                result.a = matrix;
                result.b = 0F;
            }
        }
        else
        {
            Matrix4f matrix = getEntityMatrix(entities, cx, cy, cz, value, defaultMatrix, transition, i, fullMatrix, placementAware);
            Matrix4f lastMatrix = getEntityMatrix(entities, cx, cy, cz, value.previous, defaultMatrix, transition, i, fullMatrix, placementAware);

            if (!isRelativeAnchorTarget(entities, value))
            {
                matrix = applyAnchorTransform(matrix, value);
            }

            if (!isRelativeAnchorTarget(entities, value.previous))
            {
                lastMatrix = applyAnchorTransform(lastMatrix, value.previous);
            }

            result.a = value.x >= 1F ? matrix : Matrices.lerp(lastMatrix, matrix, value.x);

            if (value.isFadeOut()) result.b = value.x;
            else if (value.isFadeIn()) result.b = 1F - value.x;
            else result.b = 0F;
        }

        return result;
    }

    private static boolean isRelativeAnchorTarget(IntObjectMap<IEntity> entities, Anchor anchor)
    {
        return anchor != null && isRelativeReplayEntity(entities.get(anchor.replay));
    }

    private static boolean hasRelativeAnchorTarget(IntObjectMap<IEntity> entities, Anchor anchor)
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

        return matrix.mul(anchor.transform.createMatrix());
    }

    public static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i)
    {
        return getEntityMatrix(entities, cameraX, cameraY, cameraZ, anchor, defaultMatrix, transition, i, false);
    }

    public static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i, boolean fullMatrix)
    {
        return getEntityMatrix(entities, cameraX, cameraY, cameraZ, anchor, defaultMatrix, transition, i, fullMatrix, true);
    }

    private static Matrix4f getEntityMatrix(IntObjectMap<IEntity> entities, double cameraX, double cameraY, double cameraZ, Anchor anchor, Matrix4f defaultMatrix, float transition, int i, boolean fullMatrix, boolean placementAware)
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
                Pair<Matrix4f, Float> totalMatrix = getTotalMatrix(entities, form.anchor.get(), basic, cameraX, cameraY, cameraZ, transition, i + 1, fullMatrix, placementAware);

                if (totalMatrix.a != null)
                {
                    basic = totalMatrix.a;
                }

                MatrixCache map;

                if (placementAware)
                {
                    Matrix4f semanticBase = absoluteSemanticMatrix(basic, cameraX, cameraY, cameraZ);

                    map = FormUtilsClient.getRenderer(form).collectMatrices(entity, entity, semanticBase, true, true, transition);
                }
                else
                {
                    /* Target-channel resolution must not advance current-age physics while
                     * the frame's absolute target maps are still being assembled. It uses
                     * the deterministic animation/local-IK attachment pose; placement and
                     * visual consumers use the full path above after target setup finishes. */
                    map = FormUtilsClient.getRenderer(form).collectMatrices(
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

    private static void renderNameTag(IEntity entity, Component text, PoseStack matrices, MultiBufferSource vertexConsumers, int light)
    {
        boolean sneaking = !entity.isSneaking();
        float hitboxH = (float) entity.getPickingHitbox().h + 0.5F;

        matrices.pushPose();
        try
        {
            matrices.translate(0F, hitboxH, 0F);
            matrices.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
            matrices.scale(-0.025F, -0.025F, 0.025F);

            Matrix4f matrix4f = matrices.last().pose();
            Font textRenderer = Minecraft.getInstance().font;

            float opacity = Minecraft.getInstance().options.getBackgroundOpacity(0.25F);
            int background = (int) (opacity * 255F) << 24;
            float h = (float) (-textRenderer.width(text) / 2);

            textRenderer.drawInBatch(text, h, 0, 0x20ffffff, false, matrix4f, vertexConsumers, sneaking ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, background, light);

            if (sneaking)
            {
                textRenderer.drawInBatch(text, h, 0, -1, false, matrix4f, vertexConsumers, Font.DisplayMode.NORMAL, 0, light);
            }
        }
        finally
        {
            matrices.popPose();
        }
    }

    /* Film controller */

    public BaseFilmController(Film film)
    {
        this.film = film;
    }

    public IntObjectMap<IEntity> getEntities()
    {
        return this.entities;
    }

    public void togglePause()
    {
        this.paused = !this.paused;
    }

    public void createEntities()
    {
        this.releaseFormPlayback();
        this.entities.clear();

        if (this.film == null)
        {
            return;
        }

        int i = 0;

        for (Replay replay : this.film.replays.getList())
        {
            if (replay.enabled.get())
            {
                Level world = Minecraft.getInstance().level;
                IEntity entity = new StubEntity(world);
                int ticks = replay.getTick(this.getTick());

                entity.setForm(FormUtils.copy(replay.form.get()));
                replay.keyframes.apply(ticks, entity);
                entity.setPrevX(entity.getX());
                entity.setPrevY(entity.getY());
                entity.setPrevZ(entity.getZ());

                entity.setPrevYaw(entity.getYaw());
                entity.setPrevHeadYaw(entity.getHeadYaw());
                entity.setPrevPitch(entity.getPitch());
                entity.setPrevBodyYaw(entity.getBodyYaw());

                this.entities.put(i, entity);
            }

            i += 1;
        }
    }

    public abstract Map<String, Integer> getActors();

    public abstract int getTick();

    public boolean hasFinished()
    {
        return false;
    }

    public void update()
    {
        this.updateEntities(this.getTick());
    }

    protected void updateEntities(int ticks)
    {
        Level level = Minecraft.getInstance().level;

        if (level == null)
        {
            return;
        }

        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            List<Replay> replays = this.film.replays.getList();
            Replay replay = CollectionUtils.getSafe(replays, i);

            if (replay == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.UPDATE))
            {
                continue;
            }

            /* Every replay maps the controller tick independently. Reusing the
             * previous replay's mapped value makes looping depend on list order. */
            int replayTick = replay.getTick(ticks);

            this.updateEntityAndForm(entity, replayTick);
            this.applyReplay(replay, replayTick, entity);

            Map<String, Integer> actors = this.getActors();

            if (actors != null)
            {
                Integer entityId = actors.get(replay.getId());

                if (entityId != null)
                {
                    Entity anEntity = level.getEntity(entityId);

                    if (anEntity instanceof ActorEntity actor)
                    {
                        this.applyActorReplay(replay, replayTick, actor, entity);
                    }
                    else if (anEntity instanceof Player player)
                    {
                        double x = replay.keyframes.x.interpolate(replayTick);
                        double y = replay.keyframes.y.interpolate(replayTick);
                        double z = replay.keyframes.z.interpolate(replayTick);
                        double prevX = replay.keyframes.x.interpolate(replayTick - 1);
                        double prevY = replay.keyframes.y.interpolate(replayTick - 1);
                        double prevZ = replay.keyframes.z.interpolate(replayTick - 1);

                        player.setDeltaMovement(x - prevX, y - prevY, z - prevZ);
                    }
                }
            }
        }
    }

    public void updateEndWorld()
    {
        int ticks = this.getTick();
        Level level = Minecraft.getInstance().level;

        if (level == null)
        {
            return;
        }

        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            List<Replay> replays = this.film.replays.getList();
            Replay replay = CollectionUtils.getSafe(replays, i);

            if (replay == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.UPDATE))
            {
                continue;
            }

            int replayTick = replay.getTick(ticks);

            Map<String, Integer> actors = this.getActors();

            if (actors != null)
            {
                Integer entityId = actors.get(replay.getId());

                if (entityId != null)
                {
                    Entity anEntity = level.getEntity(entityId);

                    if (anEntity instanceof Player player)
                    {
                        double x = replay.keyframes.x.interpolate(replayTick);
                        double y = replay.keyframes.y.interpolate(replayTick);
                        double z = replay.keyframes.z.interpolate(replayTick);
                        boolean sneaking = replay.keyframes.sneaking.interpolate(replayTick) > 0;
                        boolean grounded = replay.keyframes.grounded.interpolate(replayTick) > 0;

                        Vec3 pos = player.position();

                        player.move(MoverType.SELF, new Vec3(x - pos.x, y - pos.y, z - pos.z));
                        player.setPos(x, y, z);

                        player.setShiftKeyDown(sneaking);
                        player.setOnGround(grounded);

                        /* First person teleports the player from keyframes instead of walking it, so vanilla's
                         * bob amplitude (the view-bobbing stride) is computed from a zero velocity and stays
                         * flat. Re-derive it from the actual per-tick displacement (the same source as the limb
                         * animation) with vanilla's own easing. oBob already holds last tick's value
                         * (snapshotted by the player tick), so only the current one is advanced — keeping the bob
                         * smooth between frames. */
                        float dx = (float) (player.getX() - player.xo);
                        float dz = (float) (player.getZ() - player.zo);
                        float stride = grounded ? Math.min(0.1F, (float) Math.sqrt(dx * dx + dz * dz)) : 0F;

                        player.bob = player.oBob + (stride - player.oBob) * 0.4F;

                        if (player instanceof ClientPlayerEntityAccessor accessor)
                        {
                            accessor.bbs$setIsSneakingPose(sneaking);
                        }

                        if (player instanceof LocalPlayer playerEntity)
                        {
                            playerEntity.input.shiftKeyDown = sneaking;
                        }

                        player.fallDistance = replay.keyframes.fall.interpolate(replayTick).floatValue();
                    }
                }
            }
        }
    }

    protected void updateEntityAndForm(IEntity entity, int tick)
    {
        entity.update();

        if (entity.getForm() != null)
        {
            entity.getForm().update(entity);
        }
    }

    protected void applyReplay(Replay replay, int ticks, IEntity entity)
    {
        replay.keyframes.apply(ticks, entity);
        replay.applyClientActions(ticks, entity, this.film);
    }

    private void applyActorReplay(Replay replay, int ticks, ActorEntity actor, IEntity editorEntity)
    {
        MCEntity actorEntity = actor.getEntity();
        int hurtTimer = actorEntity.getHurtTimer();

        actorEntity.update();
        replay.keyframes.apply(ticks, actorEntity, List.of(ReplayKeyframes.GROUP_POSITION));
        actorEntity.setHurtTimer(Math.max(hurtTimer, actorEntity.getHurtTimer()));

        if (this.getTransition(editorEntity, 1F) == 0F)
        {
            actorEntity.setPrevX(actorEntity.getX());
            actorEntity.setPrevY(actorEntity.getY());
            actorEntity.setPrevZ(actorEntity.getZ());
            actorEntity.setPrevYaw(actorEntity.getYaw());
            actorEntity.setPrevHeadYaw(actorEntity.getHeadYaw());
            actorEntity.setPrevBodyYaw(actorEntity.getBodyYaw());
            actorEntity.setPrevPitch(actorEntity.getPitch());
        }

        replay.applyClientActions(ticks, actorEntity, this.film);
    }

    public void startRenderFrame(float transition)
    {
        /* Phase 1: every replay receives this frame's ordinary properties and
         * scalar procedural controls before any target channel samples another
         * replay's bones. This removes replay iteration order from simulation. */
        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            Replay replay = CollectionUtils.getSafe(this.film.replays.getList(), i);
            Entity anEntity = replay == null ? null : this.getReplayActor(replay);

            if (replay != null)
            {
                markRelativeReplayEntity(entity, replay.relative.get());
            }

            if (replay == null || !replay.enabled.get())
            {
                FormUtilsClient.release(entity.getForm());
                this.clearActorTimeline(anEntity);

                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.PROPERTIES))
            {
                FormUtilsClient.release(entity.getForm());
                this.clearActorTimeline(anEntity);

                continue;
            }

            float delta = this.getTransition(entity, transition);
            int tick = replay.getTick(this.getTick());

            /* Apply property */
            Form form1 = entity.getForm();
            replay.properties.applyProperties(form1, tick + delta);
            this.applyTargetControls(replay, form1, tick + delta);

            if (anEntity instanceof ActorEntity actor)
            {
                Form form = actor.getForm();

                replay.properties.applyProperties(form, tick + delta);
                this.applyTargetControls(replay, form, tick + delta);
            }
            else if (anEntity instanceof Player player)
            {
                Morph morph = Morph.getMorph(player);

                if (morph != null)
                {
                    Form form = morph.getForm();

                    replay.properties.applyProperties(form, tick + delta);
                    this.applyTargetControls(replay, form, tick + delta);
                }

                float yawHead = replay.keyframes.headYaw.interpolate(tick + delta).floatValue();
                float yawBody = replay.keyframes.bodyYaw.interpolate(tick + delta).floatValue();
                float pitch = replay.keyframes.pitch.interpolate(tick + delta).floatValue();

                player.setYRot(yawHead);
                player.setYHeadRot(yawHead);
                player.setXRot(pitch);
                player.setYBodyRot(yawBody);
                player.yRotO = yawHead;
                player.yHeadRotO = yawHead;
                player.xRotO = pitch;
                player.yBodyRotO = yawBody;
            }

            if (replay.actor.get())
            {
                FilmActorTimeline.update(this, anEntity, replay.properties, tick + delta, this.isTimelinePlaying());
            }
            else
            {
                this.clearActorTimeline(anEntity);
            }
        }

        /* Phase 2: target maps are now assembled without advancing current-age
         * physics. Placement-aware sampling happens only after this phase. */
        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            Replay replay = CollectionUtils.getSafe(this.film.replays.getList(), i);

            if (replay == null || !replay.enabled.get() || !this.canUpdate(i, replay, entity, UpdateMode.PROPERTIES))
            {
                continue;
            }

            float delta = this.getTransition(entity, transition);
            int tick = replay.getTick(this.getTick());

            this.applyTargetOverrides(replay, entity.getForm(), tick + delta, delta);

            Entity anEntity = this.getReplayActor(replay);

            if (anEntity instanceof ActorEntity actor)
            {
                this.applyTargetOverrides(replay, actor.getForm(), tick + delta, delta);
            }
            else if (anEntity instanceof Player player)
            {
                Morph morph = Morph.getMorph(player);

                if (morph != null)
                {
                    this.applyTargetOverrides(replay, morph.getForm(), tick + delta, delta);
                }
            }
        }
    }

    private Entity getReplayActor(Replay replay)
    {
        Map<String, Integer> actors = this.getActors();
        Integer entityId = actors == null ? null : actors.get(replay.getId());
        Level level = Minecraft.getInstance().level;

        return entityId == null || level == null ? null : level.getEntity(entityId);
    }

    private static void markRelativeReplayEntity(IEntity entity, boolean relative)
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

    private static Object relativeSimulationOwner(IEntity entity)
    {
        return RELATIVE_SIMULATION_OWNERS.computeIfAbsent(entity, (ignored) -> new Object());
    }

    public void update(Replay replay, Form root, float tick, float transition)
    {
        this.applyTargetControls(replay, root, tick);
        this.applyTargetOverrides(replay, root, tick, transition);
    }

    private void applyTargetControls(Replay replay, Form root, float tick)
    {
        if (replay == null || root == null)
        {
            return;
        }

        this.clearControlOverrides(root);

        if (replay.properties == null || replay.properties.properties == null || replay.properties.properties.isEmpty())
        {
            return;
        }

        for (KeyframeChannel<?> channel : replay.properties.properties.values())
        {
            if (channel == null)
            {
                continue;
            }

            String id = channel.getId();

            if (id == null || id.isEmpty())
            {
                continue;
            }

            if (FormControlKeys.isIKControlChannel(id))
            {
                this.applyIKControls(root, FormControlKeys.parseIKControlFormPath(id), channel, tick);
                continue;
            }

            if (FormControlKeys.isPhysicsControlChannel(id))
            {
                this.applyPhysicsControls(root, FormControlKeys.parsePhysicsControlFormPath(id), channel, tick);
                continue;
            }

            if (FormControlKeys.isWindControlChannel(id))
            {
                this.applyWindControls(root, FormControlKeys.parseWindControlFormPath(id), channel, tick);
            }
        }
    }

    private void applyTargetOverrides(Replay replay, Form root, float tick, float transition)
    {
        if (replay == null || root == null)
        {
            return;
        }

        this.clearSpatialTargetOverrides(root);

        if (replay.properties == null || replay.properties.properties == null || replay.properties.properties.isEmpty())
        {
            return;
        }

        for (KeyframeChannel<?> channel : replay.properties.properties.values())
        {
            if (channel == null)
            {
                continue;
            }

            String id = channel.getId();

            if (id == null || id.isEmpty()
                || FormControlKeys.isIKControlChannel(id)
                || FormControlKeys.isPhysicsControlChannel(id)
                || FormControlKeys.isWindControlChannel(id))
            {
                continue;
            }

            PerLimbService.IKTargetPath ikPath = PerLimbService.parseIKTargetPath(id);

            if (ikPath != null)
            {
                this.applyOverride(root, ikPath.formPath(), ikPath.controller(), channel, tick, transition, TargetKind.IK);
                continue;
            }

            PerLimbService.PoleTargetPath polePath = PerLimbService.parsePoleTargetPath(id);

            if (polePath != null)
            {
                this.applyOverride(root, polePath.formPath(), polePath.controller(), channel, tick, transition, TargetKind.POLE);
                continue;
            }

            PerLimbService.PhysicsTargetPath physicsPath = PerLimbService.parsePhysicsTargetPath(id);

            if (physicsPath != null)
            {
                this.applyPhysicsTarget(root, physicsPath.formPath(), physicsPath.rootBone(), channel, tick, transition);
            }
        }
    }

    private void applyIKControls(Form root, String formPath, KeyframeChannel<?> channel, float tick)
    {
        Form form = formPath == null || formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null)
        {
            return;
        }

        Object value = segment.createInterpolated();

        if (!(value instanceof IKControls controls))
        {
            return;
        }

        for (Map.Entry<String, IKControl> entry : controls.controls.entrySet())
        {
            modelForm.ikControlOverrides.computeIfAbsent(entry.getKey(), (k) -> new IKControl()).copy(entry.getValue());
        }
    }

    private void applyPhysicsControls(Form root, String formPath, KeyframeChannel<?> channel, float tick)
    {
        Form form = formPath == null || formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null)
        {
            return;
        }

        Object value = segment.createInterpolated();

        if (!(value instanceof PhysicsControls controls))
        {
            return;
        }

        for (Map.Entry<String, PhysicsControl> entry : controls.controls.entrySet())
        {
            modelForm.physicsControlOverrides.computeIfAbsent(entry.getKey(), (k) -> new PhysicsControl()).copy(entry.getValue());
        }
    }

    private void applyWindControls(Form root, String formPath, KeyframeChannel<?> channel, float tick)
    {
        Form form = formPath == null || formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null)
        {
            return;
        }

        Object value = segment.createInterpolated();

        if (!(value instanceof WindControl control))
        {
            return;
        }

        if (modelForm.windControlOverride == null)
        {
            modelForm.windControlOverride = new WindControl();
        }

        modelForm.windControlOverride.copy(control);
    }

    private enum TargetKind
    {
        IK, POLE
    }

    private void applyOverride(Form root, String formPath, String targetId, KeyframeChannel<?> channel, float tick, float transition, TargetKind kind)
    {
        Form form = formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null || !(segment.createInterpolated() instanceof Anchor anchor))
        {
            return;
        }

        Map<String, Vector3f> overrides = switch (kind)
        {
            case IK -> modelForm.ikTargetOverrides;
            case POLE -> modelForm.poleTargetOverrides;
        };
        Map<String, Float> weights = switch (kind)
        {
            case IK -> modelForm.ikTargetWeights;
            case POLE -> modelForm.poleTargetWeights;
        };

        /* Resolve the BOUND side at its full position with a 0..1 fade weight, mirroring
         * applyPhysicsTarget: feeding the fading anchor straight to getTotalMatrix would
         * lerp the position from world origin across a "None" key, yanking the pole/target
         * to (0,0,0). The applier eases the override in/out from the config position by the
         * weight instead, so a fade glides from where the bone already is. */
        Anchor resolve;
        float weight;

        if (anchor.previous != null && anchor.isFadeIn())
        {
            resolve = anchor.copy();
            weight = anchor.x;
        }
        else if (anchor.previous != null && anchor.isFadeOut())
        {
            resolve = anchor.previous;
            weight = 1F - anchor.x;
        }
        else
        {
            resolve = anchor;
            weight = 1F;
        }

        IEntity targetEntity = this.entities.get(resolve.replay);

        if (weight <= 0F || resolve.replay == Anchor.NO_ATTACHMENT || targetEntity == null || hasRelativeAnchorTarget(this.entities, resolve))
        {
            return;
        }

        Pair<Matrix4f, Float> matrix = getTotalMatrix(this.entities, resolve, IDENTITY, 0D, 0D, 0D, transition, 0, true, false);
        Matrix4f resolved = matrix.a != null ? matrix.a : IDENTITY;
        Vector3f position = resolved.getTranslation(TEMP_VECTOR);

        overrides.computeIfAbsent(targetId, (k) -> new Vector3f()).set(position);
        weights.put(targetId, weight);
    }

    /**
     * Physics target override with fade support. Unlike the IK/pole targets this also resolves a fade
     * <em>weight</em>: when the binding crosses a no-target keyframe the shared anchor interpolation lerps the
     * resolved matrix from world origin, which yanks the chain to (0,0,0). Instead we resolve the bound side at
     * its full position and hand the physics solver a 0..1 weight so it can ease the chain in/out from its own
     * tip (see {@link ModelPhysicsRuntime}).
     */
    private void applyPhysicsTarget(Form root, String formPath, String rootBone, KeyframeChannel<?> channel, float tick, float transition)
    {
        Form form = formPath.isEmpty() ? root : FormUtils.getForm(root, formPath);

        if (!(form instanceof ModelForm modelForm))
        {
            return;
        }

        KeyframeSegment<?> segment = channel.find(tick);

        if (segment == null || !(segment.createInterpolated() instanceof Anchor anchor))
        {
            return;
        }

        /* Pick the bound side and how present it is. Fade in/out blends to/from "no target"; a straight switch
         * between two real targets keeps the anchor's own lerp at full weight. */
        Anchor resolve;
        float weight;

        if (anchor.previous != null && anchor.isFadeIn())
        {
            resolve = anchor.copy();
            weight = anchor.x;
        }
        else if (anchor.previous != null && anchor.isFadeOut())
        {
            resolve = anchor.previous;
            weight = 1F - anchor.x;
        }
        else
        {
            resolve = anchor;
            weight = 1F;
        }

        IEntity targetEntity = this.entities.get(resolve.replay);

        if (weight <= 0F || resolve.replay == Anchor.NO_ATTACHMENT || targetEntity == null || hasRelativeAnchorTarget(this.entities, resolve))
        {
            return;
        }

        Pair<Matrix4f, Float> matrix = getTotalMatrix(this.entities, resolve, IDENTITY, 0D, 0D, 0D, transition, 0, true, false);
        Matrix4f resolved = matrix.a != null ? matrix.a : IDENTITY;
        Vector3f position = resolved.getTranslation(TEMP_VECTOR);

        modelForm.physicsTargetOverrides.computeIfAbsent(rootBone, (k) -> new Vector3f()).set(position);
        modelForm.physicsTargetWeights.put(rootBone, weight);
    }

    private void clearControlOverrides(Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            modelForm.ikControlOverrides.clear();
            modelForm.physicsControlOverrides.clear();
            modelForm.windControlOverride = null;
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                this.clearControlOverrides(child);
            }
        }
    }

    private void clearSpatialTargetOverrides(Form form)
    {
        if (form instanceof ModelForm modelForm)
        {
            modelForm.ikTargetOverrides.clear();
            modelForm.poleTargetOverrides.clear();
            modelForm.ikTargetWeights.clear();
            modelForm.poleTargetWeights.clear();
            modelForm.physicsTargetOverrides.clear();
            modelForm.physicsTargetWeights.clear();
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            Form child = part.getForm();

            if (child != null)
            {
                this.clearSpatialTargetOverrides(child);
            }
        }
    }

    protected float getTransition(IEntity entity, float transition)
    {
        return this.paused ? 0F : transition;
    }

    protected boolean isTimelinePlaying()
    {
        return !this.paused;
    }

    protected boolean canUpdate(int i, Replay replay, IEntity entity, UpdateMode updateMode)
    {
        if (this.paused && (updateMode == UpdateMode.UPDATE))
        {
            return false;
        }

        return i != this.exception;
    }

    public void render(IBbsWorldRenderContext context)
    {
        RenderSystem.enableDepthTest();

        for (Map.Entry<Integer, IEntity> entry : this.entities.entrySet())
        {
            int i = entry.getKey();
            IEntity entity = entry.getValue();
            Replay replay = CollectionUtils.getSafe(this.film.replays.getList(), i);

            if (replay == null || !replay.enabled.get())
            {
                continue;
            }

            if (!this.canUpdate(i, replay, entity, UpdateMode.RENDER))
            {
                continue;
            }

            this.renderEntity(context, replay, entity);
        }
    }

    protected void renderEntity(IBbsWorldRenderContext context, Replay replay, IEntity entity)
    {
        FilmControllerContext filmContext = getFilmControllerContext(context, replay, entity);
        float transition = getTransition(entity, context.tickDelta());

        filmContext.transition = transition;
        filmContext.timeline(replay.properties, replay.getTick(this.getTick()) + transition, this.isTimelinePlaying());

        if (replay.actor.get())
        {
            /* Actor replays render the world ActorEntity instead of the editor
             * entity. The editor entity is still the gizmo's placement source,
             * so keep capturing this frame's transform/pose/anchor matrices
             * (Gizmo#captureVisual) without drawing the editor form on top of
             * the actor — otherwise the gizmo sits on a stale matrix and stops
             * following the replayed form's anchor. */
            filmContext.gizmoOnly(true);
        }

        renderEntity(filmContext);
    }

    protected FilmControllerContext getFilmControllerContext(IBbsWorldRenderContext context, Replay replay, IEntity entity)
    {
        return FilmControllerContext.instance
            .setup(
                this.entities,
                entity,
                replay,
                context.camera(),
                context.matrixStack(),
                context.consumers(),
                context.tickDelta()
            )
            .shadow(replay.shadow.get(), replay.shadowSize.get())
            .nameTag(replay.nameTag.get())
            .relative(replay.relative.get());
    }

    public void shutdown()
    {
        this.releaseFormPlayback();
    }

    private void releaseFormPlayback()
    {
        for (IEntity entity : this.entities.values())
        {
            FormUtilsClient.release(entity.getForm());
        }

        FilmActorTimeline.clearOwner(this, this::releaseActorForm);
    }

    private void clearActorTimeline(Entity entity)
    {
        if (FilmActorTimeline.clear(this, entity))
        {
            this.releaseActorForm(entity);
        }
    }

    private void releaseActorForm(Entity entity)
    {
        if (entity instanceof ActorEntity actor)
        {
            FormUtilsClient.release(actor.getForm());
        }
        else if (entity instanceof Player player)
        {
            Morph morph = Morph.getMorph(player);

            if (morph != null)
            {
                FormUtilsClient.release(morph.getForm());
            }
        }
    }

    public static enum UpdateMode
    {
        UPDATE, RENDER, PROPERTIES;
    }
}
