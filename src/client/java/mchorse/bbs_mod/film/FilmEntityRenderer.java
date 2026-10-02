package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.renderer.ModelBlockEntityRenderer;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.utils.FormFrameCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Drawing one replay's form into the world: the form itself, the gizmo axes and preview axes of
 * the bone being edited, the whole-form anchor gizmo, the ground shadow and the name tag.
 *
 * <p>Split out of {@link BaseFilmController}: everything here is static and takes the fully
 * prepared {@link FilmControllerContext}, so any host of a film (the world renderer, the film
 * editor, the export path) can draw an actor without owning a controller instance. The relative
 * replay branch keeps the FSR-specific camera fix documented inline.</p>
 */
public class FilmEntityRenderer
{
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

        FilmMatrices.markRelativeReplayEntity(entity, relative);

        if (relative)
        {
            cx = context.replay.keyframes.x.interpolate(0F) + context.replay.relativeOffset.get().x;
            cy = context.replay.keyframes.y.interpolate(0F) + context.replay.relativeOffset.get().y;
            cz = context.replay.keyframes.z.interpolate(0F) + context.replay.relativeOffset.get().z;
        }

        Matrix4f target = null;
        Matrix4f defaultMatrix = FilmMatrices.getMatrixForRenderWithRotation(entity, cx, cy, cz, transition);
        float opacity = 1F;

        if (!relative)
        {
            Pair<Matrix4f, Float> pair = FilmMatrices.getTotalMatrix(entities, form.anchor.get(), defaultMatrix, cx, cy, cz, transition, 0);

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
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(entity.getHurtTimer() > 0 || entity.isDead()));

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
                .simulationOwner(FilmMatrices.relativeSimulationOwner(entity))
                .localSimulation();
        }
        else
        {
            formContext.semanticWorldFromCameraRelative(target, cx, cy, cz);
        }

        FormFrameCache gizmoFrame = null;

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

            gizmoFrame = UIBaseMenu.shouldRenderAxes() ? new FormFrameCache() : null;

            if (UIBaseMenu.shouldRenderAxes())
            {
                if (context.bone != null) renderAxes(context.bone, context.local, context.space, context.gizmoView, context.map, form, formContext, stack, gizmoFrame);
                if (!context.gizmoOnly && context.bone2 != null && context.map == null) renderPreviewAxes(context.bone2, context.local2, form, formContext, stack, gizmoFrame);
            }

        }
        finally
        {
            stack.popPose();
        }

        if (UIBaseMenu.shouldRenderAxes() && context.anchorGizmo)
        {
            renderAnchorGizmo(entities, entity, target, defaultMatrix, cx, cy, cz, transition, context.anchorLocal, context.space, context.gizmoView, context.map, stack, gizmoFrame);
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

    private static void renderAxes(String bone, boolean local, TransformSpace space, Matrix4f gizmoView, StencilMap stencilMap, Form form, FormRenderingContext context, PoseStack stack, FormFrameCache frame)
    {
        String mapKey = bone != null && bone.contains(PerLimbService.POSE_BONES) ? bone.replace(PerLimbService.POSE_BONES, "") : bone;
        Form root = FormUtils.getRoot(form);
        MatrixCache map = FormFrameCache.collect(
            frame,
            root,
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
                Gizmo.INSTANCE.reorientForSpace(stack, space, gizmoView, FilmMatrices.getReplayWorldAxes(context.entity, context.getTransition()));

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
    private static void renderPreviewAxes(String bone, boolean local, Form form, FormRenderingContext context, PoseStack stack, FormFrameCache frame)
    {
        String mapKey = bone != null && bone.contains(PerLimbService.POSE_BONES) ? bone.replace(PerLimbService.POSE_BONES, "") : bone;
        Form root = FormUtils.getRoot(form);
        MatrixCache map = FormFrameCache.collect(
            frame,
            root,
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
    private static void renderAnchorGizmo(IntObjectMap<IEntity> entities, IEntity entity, Matrix4f full, Matrix4f defaultMatrix, double cx, double cy, double cz, float transition, boolean local, TransformSpace space, Matrix4f gizmoView, StencilMap stencilMap, PoseStack stack, FormFrameCache frame)
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
            Matrix4f parent = FilmMatrices.getEntityMatrix(entities, cx, cy, cz, form.anchor.get(), defaultMatrix, transition, 0, true, frame);

            matrix = MatrixStackUtils.stripScale(parent);
            matrix.setTranslation(full.getTranslation(new Vector3f()));
        }

        stack.pushPose();
        try
        {
            MatrixStackUtils.multiply(stack, matrix);
            Gizmo.INSTANCE.reorientForSpace(stack, space, gizmoView, FilmMatrices.getReplayWorldAxes(entity, transition));

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
}
