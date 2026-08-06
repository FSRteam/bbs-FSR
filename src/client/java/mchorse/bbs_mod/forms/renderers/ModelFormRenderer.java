package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.client.renderer.entity.ActorEntityRenderer;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.animation.ActionsConfig;
import mchorse.bbs_mod.cubic.animation.Animator;
import mchorse.bbs_mod.cubic.animation.IAnimator;
import mchorse.bbs_mod.cubic.animation.ProceduralAnimator;
import mchorse.bbs_mod.cubic.constraints.ModelConstraintsRuntime;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.ik.ModelIKDebug;
import mchorse.bbs_mod.cubic.ik.ModelIKRuntime;
import mchorse.bbs_mod.cubic.model.ArmorSlot;
import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsDebug;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsRuntime;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.pose.PoseBones;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public class ModelFormRenderer extends FormRenderer<ModelForm> implements ITickable
{
    private static Matrix4f uiMatrix = new Matrix4f();
    private static final Quaternionf ROTATE_X_90 = Axis.XP.rotationDegrees(90F);
    private static final Quaternionf ROTATE_X_180 = Axis.XP.rotationDegrees(180F);
    private static final Quaternionf ROTATE_Y_180 = Axis.YP.rotation(MathUtils.PI);
    private static final ItemStack OAK_BUTTON_STACK = new ItemStack(Items.OAK_BUTTON);

    private MatrixCache bones = new MatrixCache();
    private PoseStack modelStack = new PoseStack();
    private final Pose renderPose = new Pose();
    private final Color renderColor = new Color();
    private final Matrix4f armorBendDelta = new Matrix4f();

    private ActionsConfig lastConfigs;
    private IAnimator animator;
    private ModelInstance lastModel;
    private final ModelIKRuntime ikRuntime = new ModelIKRuntime();
    private final ModelPhysicsRuntime physicsRuntime = new ModelPhysicsRuntime();
    private final Matrix4f ikInverseBase = new Matrix4f();
    private final Map<String, Vector3f> ikLocalTargets = new HashMap<>();
    private final Map<String, Vector3f> ikLocalPoles = new HashMap<>();
    private final Object uiSimulationOwner = new Object();
    private final Object mainArmSimulationOwner = new Object();
    private final Object offArmSimulationOwner = new Object();
    private final StubEntity armSimulationClock = new StubEntity();
    private PoseStack armSimulationWorld = new PoseStack();
    private boolean ikAppliedThisRender;
    private boolean physicsAppliedThisRender;
    private boolean constraintsAppliedThisRender;
    private boolean renderingArm;
    private final PreviewPoseSnapshot previewPoseSnapshot = new PreviewPoseSnapshot();

    private IEntity entity = new StubEntity();

    @Override
    protected void applyTransforms(PoseStack stack, boolean origin, float transition)
    {
        super.applyTransforms(stack, origin, transition);

        ModelInstance model = this.getModel();

        if (model != null)
        {
            stack.scale(model.getScale().x, model.getScale().y, model.getScale().z);
        }
    }

    @Override
    protected void applyTransforms(Matrix4f matrix, float transition)
    {
        super.applyTransforms(matrix, transition);

        ModelInstance model = this.getModel();

        if (model != null)
        {
            matrix.scale(model.getScale().x, model.getScale().y, model.getScale().z);
        }
    }

    public static Matrix4f getUIMatrix(UIContext context, int x1, int y1, int x2, int y2)
    {
        float scale = (y2 - y1) / 2.5F;
        int x = x1 + (x2 - x1) / 2;
        float y = y1 + (y2 - y1) * 0.85F;
        float angle = MathUtils.toRad(context.mouseX - (x1 + x2) / 2) + MathUtils.PI;

        if (BBSSettings.freezeModels.get())
        {
            angle = -MathUtils.PI + MathUtils.PI / 8;
        }

        uiMatrix.identity();
        uiMatrix.translate(x, y, 40);
        uiMatrix.scale(scale, -scale, scale);
        uiMatrix.rotateX(MathUtils.PI / 8);
        uiMatrix.rotateY(angle);

        return uiMatrix;
    }

    public static ModelInstance getModel(ModelForm form)
    {
        return BBSModClient.getModels().getModel(form.model.get());
    }

    public ModelFormRenderer(ModelForm form)
    {
        super(form);
    }

    public IAnimator getAnimator()
    {
        return this.animator;
    }

    public ModelInstance getModel()
    {
        return getModel(this.form);
    }

    public Pose getPose()
    {
        return this.getPose(new Pose());
    }

    private Pose getPose(Pose pose)
    {
        pose.copy(this.form.pose.get());
        Pose overlay = this.form.poseOverlay.get();

        this.applyPose(pose, overlay);

        for (ValuePose newPose : this.form.additionalOverlays)
        {
            this.applyPose(pose, newPose.get());
        }

        return pose;
    }

    private void applyPose(Pose targetPose, Pose pose)
    {
        for (Map.Entry<String, PoseTransform> entry : pose.transforms.entrySet())
        {
            PoseTransform poseTransform = targetPose.get(entry.getKey());
            PoseTransform value = entry.getValue();

            if (value.fix != 0)
            {
                poseTransform.translate.lerp(value.translate, value.fix);
                poseTransform.scale.lerp(value.scale, value.fix);
                poseTransform.lerpRotation(value, value.fix);
            }
            else
            {
                poseTransform.translate.add(value.translate);
                poseTransform.scale.add(value.scale).sub(1, 1, 1);
                poseTransform.addRotation(value);
            }
        }
    }

    public void resetAnimator()
    {
        this.animator = null;
        this.lastModel = null;
    }

    public void ensureAnimator(float transition)
    {
        ModelInstance model = this.getModel();
        ActionsConfig actionsConfig = this.form.actions.get();

        if (model == null || this.lastModel == model)
        {
            /* Update the config */
            if (this.animator != null && !Objects.equals(actionsConfig, this.lastConfigs))
            {
                this.animator.setup(model, actionsConfig, true);

                this.lastConfigs = new ActionsConfig();
                this.lastConfigs.copy(actionsConfig);
            }

            return;
        }

        this.animator = model.isProcedural() ? new ProceduralAnimator() : new Animator();
        this.animator.setup(model, actionsConfig, false);

        this.lastConfigs = new ActionsConfig();
        this.lastConfigs.copy(actionsConfig);
        this.lastModel = model;
    }

    @Override
    public BoneHierarchy getBoneHierarchy()
    {
        ModelInstance model = this.getModel();

        if (model == null)
        {
            return BoneHierarchy.EMPTY;
        }

        List<BoneHierarchy.Bone> bones = new ArrayList<>();

        for (String bone : model.model.getGroupKeysInHierarchyOrder())
        {
            if (PoseBones.isHidden(model.getDisabledBones(), bone))
            {
                continue;
            }

            String parent = model.model.getParentGroupKey(bone);
            int depth = 0;
            String current = parent;

            while (current != null && !current.isEmpty())
            {
                depth++;
                current = model.model.getParentGroupKey(current);
            }

            bones.add(new BoneHierarchy.Bone(bone, bone, parent, depth, ""));
        }

        return new BoneHierarchy(bones);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        this.renderInUI(context, x1, y1, x2, y2, null, null);
    }

    public void renderPreviewThumbnail(UIContext context, int x1, int y1, int x2, int y2, Object sourceOwner, IEntity sourceEntity)
    {
        this.beginRenderUI();
        this.renderInUI(context, x1, y1, x2, y2, sourceOwner, sourceEntity);
        this.finishRenderUI(context, x1, y1, x2, y2);
    }

    private void renderInUI(UIContext context, int x1, int y1, int x2, int y2, Object sourceOwner, IEntity sourceEntity)
    {
        context.batcher.flush();

        this.ensureAnimator(context.getTransition());

        ModelInstance model = this.getModel();

        if (this.animator != null && model != null)
        {
            /* A thumbnail that owns its pose must also own its animation clock: nothing calls
             * Form#update on this path, so without this the animator stays on one keyframe pair
             * and applyActions re-samples it across the partial tick's 0..1 sweep every frame.
             * Previews that reuse a caller's pose (film, model block) keep their own pacing. */
            if (sourceOwner == null)
            {
                this.advanceUIAnimation(context);
            }

            boolean reusePreviewPose = sourceOwner != null
                && this.previewPoseSnapshot.consume(sourceOwner, sourceEntity, model, context.getTransition());
            PoseStack stack = context.batcher.getContext().pose();

            stack.pushPose();
            try
            {

                Matrix4f uiMatrix = getUIMatrix(context, x1, y1, x2, y2);

                this.applyTransforms(uiMatrix, context.getTransition());

                Link link = this.form.texture.get();
                Link texture = link == null ? model.getTexture() : link;
                Color color = this.renderColor.set(1F, 1F, 1F, 1F);
                float scale = this.form.uiScale.get() * model.getUiScale();

                FormColorBlend.blend(color, this.form.color.get(), this.form.additiveColor.get());
                if (!reusePreviewPose)
                {
                    /* A stopped clock is not a still pose while the partial tick keeps sweeping
                     * the animator across its current keyframe pair - pin it so the pose holds. */
                    float poseTransition = this.isUIAnimationFrozen() ? UI_FROZEN_TRANSITION : context.getTransition();

                    model.model.resetPose();

                    this.animator.applyActions(null, model, poseTransition);
                    model.model.applyPose(this.getPose(this.renderPose));
                }

                MatrixStackUtils.multiply(stack, uiMatrix);
                stack.scale(scale, scale, scale);

                BBSModClient.getTextures().bindTexture(texture);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);

                Supplier<ShaderInstance> mainShader = (BBSRendering.isIrisShadersEnabled() && BBSRendering.isRenderingWorld()) || !model.isVAORendered()
                    ? GameRenderer::getRendertypeEntityTranslucentCullShader
                    : BBSShaders::getModel;

                this.renderModel(this.entity, this.uiSimulationOwner, mainShader, stack, model, LightTexture.pack(15, 15), OverlayTexture.NO_OVERLAY, color, true, null, context.getTransition(), null, false, false, !reusePreviewPose);

                /* Render body parts */
                stack.pushPose();
                try
                {
                    stack.last().normal().getScale(Vectors.EMPTY_3F);
                    stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

                    this.renderBodyParts(new FormRenderingContext()
                        .set(FormRenderType.ENTITY, this.entity, stack, LightTexture.pack(15, 15), OverlayTexture.NO_OVERLAY, context.getTransition())
                        .simulationOwner(this.uiSimulationOwner)
                        .inUI());
                }
                finally
                {
                    stack.popPose();
                }
            }
            finally
            {
                stack.popPose();
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
            }
        }
    }

    private void renderModel(IEntity target, Object simulationOwner, Supplier<ShaderInstance> program, PoseStack stack, ModelInstance model, int light, int overlay, Color color, boolean ui, StencilMap stencilMap, float transition, PoseStack world, boolean allowWorldTargetOverrides, boolean allowWorldCollisions)
    {
        this.renderModel(target, simulationOwner, program, stack, model, light, overlay, color, ui, stencilMap, transition, world, allowWorldTargetOverrides, allowWorldCollisions, true);
    }

    private void renderModel(IEntity target, Object simulationOwner, Supplier<ShaderInstance> program, PoseStack stack, ModelInstance model, int light, int overlay, Color color, boolean ui, StencilMap stencilMap, float transition, PoseStack world, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, boolean solvePose)
    {
        this.ikAppliedThisRender = false;
        this.physicsAppliedThisRender = false;
        this.constraintsAppliedThisRender = false;

        if (!model.isCulling())
        {
            RenderSystem.disableCull();
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;

        gameRenderer.lightTexture().turnOnLightLayer();
        try
        {
            gameRenderer.overlayTexture().setupOverlayColor();

            PoseStack newStack = this.getModelStack();

            MatrixStackUtils.multiply(newStack, stack.last().pose());
            newStack.last().normal().set(stack.last().normal());

            if (ui)
            {
                newStack.last().normal().getScale(Vectors.EMPTY_3F);
                newStack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);
            }

            /* Rendering may be camera-relative or already contain an editor view matrix. Physics and
             * world-space IK targets must only see the separately propagated semantic world transform. */
            /* A missing semantic world frame (for example first-person arm rendering) is not a
             * camera-space world. Keep simulation model-local instead of deriving gravity/collision
             * orientation from the render stack. */
            Matrix4f baseTransform = ui || world == null ? null : new Matrix4f(world.last().pose());

            /* Clamp the FK input first. IK and physics each enforce the same limits internally; applying
             * the generic Euler clamp afterward would clear their composed quaternion orientation. */
            if (solvePose)
            {
                this.applyConstraintsOnce(model);
                this.applyIKOnce(model, simulationOwner == null ? target : simulationOwner, baseTransform, allowWorldTargetOverrides);
                this.applyPhysicsOnce(target, simulationOwner, model, transition, baseTransform, allowWorldTargetOverrides, allowWorldCollisions);
            }

            /* Default texture for materials without their own: the form's texture override, else the
             * model's default. Per-material textures (folder defaults now, animation tracks later)
             * layer on top via the resolver. */
            Link defaultTexture = this.form.texture.get();

            if (defaultTexture == null)
            {
                defaultTexture = model.getTexture();
            }

            final Link resolvedDefault = defaultTexture;

            /* A model with at most one material ignores the material system entirely: a single texture
             * (form.texture, else the model's base texture) covers the whole model, regardless of any
             * per-material folder/Kd default, editor pick, or animation track. Only with multiple materials
             * is the Default ambiguous - it's hidden in the editor then and must not affect them here either,
             * so they fall back to the model base texture. */
            final boolean ignoreMaterials = model.materials.size() <= 1;
            final Link materialFallback = ignoreMaterials ? resolvedDefault : model.getTexture();

            model.render(newStack, program, color, light, overlay, stencilMap, this.form.shapeKeys.get(), (material) ->
            {
                if (ignoreMaterials)
                {
                    return resolvedDefault;
                }

                /* Resolution order: animated per-material track > editor-picked static per-material
                 * texture > the material's loaded default (folder/Kd) > the model base texture. */
                Link override = this.form.materialTextureOverrides.get(material);

                if (override != null)
                {
                    return override;
                }

                Link picked = this.form.materialTextures.getLink(material);

                if (picked != null)
                {
                    return picked;
                }

                return model.getMaterialTexture(material, materialFallback);
            });

            if (stencilMap == null && !this.renderingArm && this.form != null && this.form.ik.get() instanceof MapType ikMap)
            {
                ModelIKDebug.render(newStack, model.model, ikMap, "");
            }

            if (stencilMap == null && !this.renderingArm && this.form != null && this.form.physics.get() instanceof MapType physicsMap)
            {
                ModelPhysicsDebug.render(newStack, model.model, physicsMap, target.getAge(), "");
            }
        }
        finally
        {
            gameRenderer.lightTexture().turnOffLightLayer();
            gameRenderer.overlayTexture().teardownOverlayColor();
            RenderSystem.disableBlend();

            if (!model.isCulling())
            {
                RenderSystem.enableCull();
            }
        }

        /* Render items */
        this.captureMatrices(model);

        if (stencilMap == null)
        {
            this.renderItems(target, model, stack, EquipmentSlot.MAINHAND, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, model.getItemsMain(), color, overlay, light);
            this.renderItems(target, model, stack, EquipmentSlot.OFFHAND, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, model.getItemsOff(), color, overlay, light);

            for (Map.Entry<ArmorType, ArmorSlot> entry : model.getArmorSlots().entrySet())
            {
                this.renderArmor(target, stack, entry.getKey(), entry.getValue(), color, overlay, light);
            }
        }
    }

    private void applyIKOnce(ModelInstance model, Object simulationOwner, Matrix4f baseTransform, boolean allowWorldTargetOverrides)
    {
        if (this.ikAppliedThisRender)
        {
            return;
        }

        this.ikAppliedThisRender = true;
        this.applyIK(model, simulationOwner, baseTransform, allowWorldTargetOverrides);
    }

    private void applyIK(ModelInstance model, Object simulationOwner, Matrix4f baseTransform, boolean allowWorldTargetOverrides)
    {
        model.form = this.form;

        boolean hasOverrides = allowWorldTargetOverrides && baseTransform != null && this.form != null
            && (!this.form.ikTargetOverrides.isEmpty() || !this.form.poleTargetOverrides.isEmpty());

        if (!hasOverrides)
        {
            this.ikRuntime.apply(simulationOwner, model, null, null);
            return;
        }

        Matrix4f inv = this.ikInverseBase.set(baseTransform).invert();

        toModelSpace(this.form.ikTargetOverrides, inv, this.ikLocalTargets);
        toModelSpace(this.form.poleTargetOverrides, inv, this.ikLocalPoles);

        if (this.ikLocalTargets.isEmpty() && this.ikLocalPoles.isEmpty())
        {
            this.ikRuntime.apply(simulationOwner, model, null, null);
            return;
        }

        this.ikRuntime.apply(simulationOwner, model, this.ikLocalTargets.isEmpty() ? null : this.ikLocalTargets, this.ikLocalPoles.isEmpty() ? null : this.ikLocalPoles);
    }

    /** World-space target overrides into the model's local space (the space the solver and pivot frames use). */
    private static void toModelSpace(Map<String, Vector3f> world, Matrix4f inv, Map<String, Vector3f> local)
    {
        local.keySet().retainAll(world.keySet());

        for (Map.Entry<String, Vector3f> entry : world.entrySet())
        {
            String key = entry.getKey();
            Vector3f worldPos = entry.getValue();

            if (key == null || key.isEmpty() || worldPos == null)
            {
                local.remove(key);
                continue;
            }

            Vector3f pos = local.computeIfAbsent(key, (ignored) -> new Vector3f());

            pos.set(worldPos);
            inv.transformPosition(pos);
        }
    }

    private void applyPhysicsOnce(IEntity target, Object simulationOwner, ModelInstance model, float transition, Matrix4f baseTransform, boolean allowWorldTargetOverrides, boolean allowWorldCollisions)
    {
        if (this.physicsAppliedThisRender)
        {
            return;
        }

        this.physicsAppliedThisRender = true;
        model.form = this.form;
        this.physicsRuntime.apply(target, simulationOwner, model, transition, baseTransform, allowWorldTargetOverrides, allowWorldCollisions);
    }

    private void applyConstraintsOnce(ModelInstance model)
    {
        if (this.constraintsAppliedThisRender)
        {
            return;
        }

        this.constraintsAppliedThisRender = true;
        ModelConstraintsRuntime.apply(model);
    }

    private PoseStack getModelStack()
    {
        if (!this.modelStack.clear())
        {
            this.modelStack = new PoseStack();
        }
        else
        {
            this.modelStack.setIdentity();
        }

        return this.modelStack;
    }

    private void renderArmor(IEntity target, PoseStack stack, ArmorType type, ArmorSlot armorSlot, Color color, int overlay, int light)
    {
        Matrix4f matrix = this.bones.get(armorSlot.group).matrix();

        if (matrix != null)
        {
            CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
            Matrix4f bendDelta = this.getArmorBendDelta(type, armorSlot);
            PoseStack lowerStack = null;

            if (bendDelta != null)
            {
                lowerStack = this.getModelStack();
                MatrixStackUtils.multiply(lowerStack, stack.last().pose());
                MatrixStackUtils.multiply(lowerStack, bendDelta);
                this.applyArmorTransform(lowerStack, matrix, armorSlot);
            }

            stack.pushPose();
            try
            {
                this.applyArmorTransform(stack, matrix, armorSlot);

                CustomVertexConsumerProvider.hijackVertexFormat((l) -> RenderSystem.enableBlend());

                float bendStart = armorSlot.hasBendRange() ? armorSlot.bendStart : type.defaultBendStart;
                float bendEnd = armorSlot.hasBendRange() ? armorSlot.bendEnd : type.defaultBendEnd;
                /* Keep the model-local bend between the outer render transform and
                 * the armor attachment. Putting it before the completed render matrix
                 * rotates camera-relative translation around the joint. */
                Matrix4f lower = lowerStack == null ? null : lowerStack.last().pose();
                Vector3f armorOrigin = stack.last().pose().getTranslation(new Vector3f());

                FormTranslucentQueue.setSortOrigin(
                    new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(armorOrigin)
                );

                ActorEntityRenderer.armorRenderer.renderArmorSlot(stack, lower, bendStart, bendEnd, consumers, target, type.slot, type, light);
                consumers.endBatch();
            }
            finally
            {
                FormTranslucentQueue.setSortOrigin(null);
                CustomVertexConsumerProvider.clearRunnables();
                stack.popPose();
                RenderSystem.enableBlend();
                RenderSystem.enableDepthTest();
            }
        }
    }

    private void applyArmorTransform(PoseStack stack, Matrix4f matrix, ArmorSlot armorSlot)
    {
        MatrixStackUtils.multiply(stack, matrix);
        MatrixStackUtils.applyTransform(stack, armorSlot.transform);
        stack.mulPose(ROTATE_X_180);
    }

    /**
     * Rotation the bending joint contributes, in model-local space, or {@code null} when this
     * slot doesn't bend or the model has no such joint.
     *
     * <p>Bending joints (elbows, knees) sit as siblings of the armor attachment bones, not
     * as ancestors, so armor parented to the upper bone alone stays rigid while the limb
     * bends. Naming the joint lets the armor get skinned across it instead.</p>
     *
     * <p>The joint's matrix can't be used to place armor directly — it's anchored at the
     * joint, so it would drag the armor away from where it belongs. What's needed is only
     * the rotation the joint adds, which is what dividing out its origin yields:
     * {@code origin} is captured before the group's own pivot rotation and scale are
     * applied, so {@code matrix * origin⁻¹} is exactly that rotation about the joint's
     * pivot. It's the identity while the joint is straight, leaving the armor untouched.</p>
     */
    private Matrix4f getArmorBendDelta(ArmorType type, ArmorSlot armorSlot)
    {
        String lowerGroup = armorSlot.hasLowerGroup() ? armorSlot.lowerGroup : type.defaultLowerGroup;

        if (lowerGroup.isEmpty())
        {
            return null;
        }

        MatrixCacheEntry entry = this.bones.get(lowerGroup);
        Matrix4f matrix = entry.matrix();
        Matrix4f origin = entry.origin();

        if (matrix == null || origin == null)
        {
            /* Model has no such joint — plain rigid armor, like every non-bending model. */
            return null;
        }

        return this.armorBendDelta.set(origin).invert().mulLocal(matrix);
    }

    private void renderItems(IEntity target, ModelInstance model, PoseStack stack, EquipmentSlot slot, ItemDisplayContext mode, List<ArmorSlot> items, Color color, int overlay, int light)
    {
        ItemStack itemStack = target.getEquipmentStack(slot);

        if (itemStack != null && itemStack.isEmpty())
        {
            return;
        }

        for (ArmorSlot armorSlot : items)
        {
            Matrix4f matrix = this.bones.get(armorSlot.group).matrix();

            if (matrix != null)
            {
                CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();

                stack.pushPose();
                try
                {
                    MatrixStackUtils.multiply(stack, matrix);
                    stack.mulPose(ROTATE_X_90);
                    stack.mulPose(ROTATE_Y_180);
                    stack.translate(0F, 0.125F, 0F);
                    MatrixStackUtils.applyTransform(stack, armorSlot.transform);

                    CustomVertexConsumerProvider.hijackVertexFormat((l) -> RenderSystem.enableBlend());
                    Vector3f itemOrigin = stack.last().pose().getTranslation(new Vector3f());

                    FormTranslucentQueue.setSortOrigin(
                        new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(itemOrigin)
                    );
                    consumers.setSubstitute(BBSRendering.getColorConsumer(color));

                    /* For some reason, due to Sodium and my color consumer, in some cases items like Trident,
                     * shield, etc. not get rendered, but if in another arm there is another item, it does render...
                     * So, I render a 0 size oak button to circumvent that bug! */
                    if (model.model instanceof BOBJModel)
                    {
                        stack.pushPose();
                        try
                        {
                            stack.scale(0F, 0F, 0F);
                            Minecraft.getInstance().getItemRenderer().renderStatic(null, OAK_BUTTON_STACK, mode, mode == ItemDisplayContext.THIRD_PERSON_LEFT_HAND, stack, consumers, target.level(), light, overlay, 0);
                            consumers.endBatch();
                        }
                        finally
                        {
                            stack.popPose();
                        }
                    }

                    Minecraft.getInstance().getItemRenderer().renderStatic(null, itemStack, mode, mode == ItemDisplayContext.THIRD_PERSON_LEFT_HAND, stack, consumers, target.level(), light, overlay, 0);
                    consumers.endBatch();
                }
                finally
                {
                    FormTranslucentQueue.setSortOrigin(null);
                    consumers.setSubstitute(null);
                    CustomVertexConsumerProvider.clearRunnables();
                    stack.popPose();

                    RenderSystem.enableDepthTest();
                }
            }
        }
    }

    @Override
    public boolean renderArm(PoseStack matrices, int light, AbstractClientPlayer player, InteractionHand hand)
    {
        ModelInstance model = this.getModel();

        if (this.animator != null && model != null)
        {
            ArmorSlot slot = hand == InteractionHand.MAIN_HAND ? model.getFpMain() : model.getFpOffhand();

            if (slot == null)
            {
                return false;
            }

            Link link = this.form.texture.get();
            Link texture = link == null ? model.getTexture() : link;
            Color color = this.renderColor.set(1F, 1F, 1F, 1F);

            FormColorBlend.blend(color, this.form.color.get(), this.form.additiveColor.get());

            for (ModelGroup group : model.getModel().getAllGroups())
            {
                ModelGroup g = group;
                boolean visible = false;

                while (g != null)
                {
                    if (g.id.equals(slot.group))
                    {
                        visible = true;

                        break;
                    }

                    g = g.parent;
                }

                group.visible = visible;
            }

            boolean matricesPushed = false;
            boolean oldRenderingArm = this.renderingArm;

            try
            {
                model.model.resetPose();

                matrices.pushPose();
                matricesPushed = true;
                matrices.mulPose(ROTATE_Y_180);
                MatrixStackUtils.applyTransform(matrices, slot.transform);

                /* First-person matrices already contain the camera/hand view. Physics
                 * must instead see a stable hand-local semantic transform and clock;
                 * otherwise looking around becomes fake model acceleration. Main and
                 * off hands also need independent histories when they use the same
                 * model resource. */
                if (!this.armSimulationWorld.clear())
                {
                    this.armSimulationWorld = new PoseStack();
                }
                else
                {
                    this.armSimulationWorld.setIdentity();
                }

                this.armSimulationWorld.mulPose(ROTATE_Y_180);
                MatrixStackUtils.applyTransform(this.armSimulationWorld, slot.transform);
                this.armSimulationClock.setWorld(null);
                this.armSimulationClock.setAge(player.tickCount);

                Object simulationOwner = hand == InteractionHand.MAIN_HAND
                    ? this.mainArmSimulationOwner
                    : this.offArmSimulationOwner;

                BBSModClient.getTextures().bindTexture(texture);

                Supplier<ShaderInstance> mainShader = (BBSRendering.isIrisShadersEnabled() && BBSRendering.isRenderingWorld()) || !model.isVAORendered()
                    ? GameRenderer::getRendertypeEntityTranslucentCullShader
                    : BBSShaders::getModel;

                RenderSystem.enableDepthTest();
                RenderSystem.enableBlend();

                this.renderingArm = true;
                this.renderModel(this.armSimulationClock, simulationOwner, mainShader, matrices, model, light, OverlayTexture.NO_OVERLAY, color, false, null, 0F, this.armSimulationWorld, false, false);

                return true;
            }
            finally
            {
                this.renderingArm = oldRenderingArm;

                for (ModelGroup group : model.getModel().getAllGroups())
                {
                    group.visible = true;
                }

                if (matricesPushed)
                {
                    matrices.popPose();
                }
            }
        }

        return super.renderArm(matrices, light, player, hand);
    }

    @Override
    public void render3D(FormRenderingContext context)
    {
        this.ensureAnimator(context.getTransition());

        ModelInstance model = this.getModel();

        if (this.animator != null && model != null)
        {
            boolean reusePreviewPose = this.previewPoseSnapshot.consume(context, model);
            Link link = this.form.texture.get();
            Link texture = link == null ? model.getTexture() : link;
            Color color = this.renderColor.set(context.color, true);

            if (context.isPicking())
            {
                color.mul(this.form.color.get());
            }
            else
            {
                FormColorBlend.blend(color, this.form.color.get(), this.form.additiveColor.get());
            }

            if (!reusePreviewPose)
            {
                model.model.resetPose();

                this.animator.applyActions(context.entity, model, context.getTransition());
                model.model.applyPose(this.getPose(this.renderPose));
            }

            context.stack.mulPose(ROTATE_Y_180);

            if (context.world != null)
            {
                context.world.mulPose(ROTATE_Y_180);
            }

            Texture textureObject = BBSModClient.getTextures().getTexture(texture);
            boolean irisWorld = BBSRendering.isIrisShadersEnabled() && BBSRendering.isRenderingWorld();
            boolean cutout = irisWorld && textureObject != null && textureObject.hasTranslucency()
                && color.a >= 1F && !this.form.additiveColor.get();

            BBSModClient.getTextures().bindTexture(textureObject);

            Supplier<ShaderInstance> mainShader = cutout
                ? GameRenderer::getRendertypeEntityCutoutShader
                : (irisWorld || !model.isVAORendered())
                    ? GameRenderer::getRendertypeEntityTranslucentCullShader
                    : BBSShaders::getModel;
            Supplier<ShaderInstance> shader = this.getShader(context, mainShader, BBSShaders::getPickerModelsProgram);
            boolean queueWasActive = false;

            if (irisWorld)
            {
                queueWasActive = FormTranslucentQueue.suspend();
            }

            if (cutout)
            {
                RenderSystem.disableBlend();
            }

            try
            {
                this.renderModel(context.entity, context.simulationOwner, shader, context.stack, model, context.light, context.overlay, color, false, context.stencilMap, context.getTransition(), context.world, context.allowWorldTargetOverrides, context.allowWorldCollisions, !reusePreviewPose);

                if (context.modelRenderer && !context.isPicking())
                {
                    this.previewPoseSnapshot.capture(context, model);
                }
            }
            finally
            {
                if (cutout)
                {
                    RenderSystem.enableBlend();
                }

                if (irisWorld)
                {
                    FormTranslucentQueue.restore(queueWasActive);
                }
            }
        }
        else
        {
            this.previewPoseSnapshot.invalidate();
        }
    }

    private static class PreviewPoseSnapshot
    {
        private final List<ModelGroupPose> groups = new ArrayList<>();
        private final List<BOBJBonePose> bones = new ArrayList<>();
        private Object owner;
        private IEntity entity;
        private ModelInstance model;
        private FormRenderingContext context;
        private final Matrix4f semanticBase = new Matrix4f();
        private boolean hasSemanticBase;
        private boolean allowWorldTargetOverrides;
        private boolean allowWorldCollisions;
        private int age;
        private int transition;
        private boolean available;

        public boolean consume(FormRenderingContext context, ModelInstance model)
        {
            boolean matches = this.available
                && context.modelRenderer
                && context.isPicking()
                && this.context == context
                && this.owner == context.simulationOwner
                && this.entity == context.entity
                && this.model == model
                && this.age == getAge(context.entity)
                && this.transition == Float.floatToIntBits(context.getTransition());

            this.available = false;

            if (!matches)
            {
                return false;
            }

            for (ModelGroupPose pose : this.groups)
            {
                pose.restore();
            }

            for (BOBJBonePose pose : this.bones)
            {
                pose.restore();
            }

            return true;
        }

        public boolean consume(Object owner, IEntity entity, ModelInstance model, float transition)
        {
            boolean matches = this.matches(owner, entity, model, transition);

            this.available = false;

            if (matches)
            {
                this.restore();
            }

            return matches;
        }

        public boolean restoreForMatrices(Object owner, IEntity entity, ModelInstance model, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, float transition)
        {
            boolean hasSemanticBase = semanticBase != null;
            boolean matches = this.matches(owner, entity, model, transition)
                && this.hasSemanticBase == hasSemanticBase
                && (!hasSemanticBase || this.semanticBase.equals(semanticBase))
                && this.allowWorldTargetOverrides == allowWorldTargetOverrides
                && this.allowWorldCollisions == allowWorldCollisions;

            if (matches)
            {
                this.restore();
            }

            return matches;
        }

        public void capture(FormRenderingContext context, ModelInstance model)
        {
            this.owner = context.simulationOwner;
            this.entity = context.entity;
            this.context = context;
            this.hasSemanticBase = context.world != null;

            if (this.hasSemanticBase)
            {
                this.semanticBase.set(context.world.last().pose());
            }

            this.allowWorldTargetOverrides = context.allowWorldTargetOverrides;
            this.allowWorldCollisions = context.allowWorldCollisions;
            this.age = getAge(context.entity);
            this.transition = Float.floatToIntBits(context.getTransition());

            if (this.model != model)
            {
                this.model = model;
                this.groups.clear();
                this.bones.clear();

                for (ModelGroup group : model.model.getAllGroups())
                {
                    this.groups.add(new ModelGroupPose(group));
                }

                for (BOBJBone bone : model.model.getAllBOBJBones())
                {
                    this.bones.add(new BOBJBonePose(bone));
                }
            }

            for (ModelGroupPose pose : this.groups)
            {
                pose.capture();
            }

            for (BOBJBonePose pose : this.bones)
            {
                pose.capture();
            }

            this.available = true;
        }

        public void invalidate()
        {
            this.available = false;
        }

        private boolean matches(Object owner, IEntity entity, ModelInstance model, float transition)
        {
            return this.available
                && this.owner == owner
                && this.entity == entity
                && this.model == model
                && this.age == getAge(entity)
                && this.transition == Float.floatToIntBits(transition);
        }

        private void restore()
        {
            for (ModelGroupPose pose : this.groups)
            {
                pose.restore();
            }

            for (BOBJBonePose pose : this.bones)
            {
                pose.restore();
            }
        }

        private static int getAge(IEntity entity)
        {
            return entity == null ? Integer.MIN_VALUE : entity.getAge();
        }
    }

    private static class ModelGroupPose
    {
        private final ModelGroup group;
        private final mchorse.bbs_mod.utils.pose.Transform current = new mchorse.bbs_mod.utils.pose.Transform();
        private final Color color = new Color();
        private final Quaternionf orient = new Quaternionf();
        private final Vector3f offset = new Vector3f();
        private float lighting;
        private boolean hasOrient;
        private boolean hasOffset;

        public ModelGroupPose(ModelGroup group)
        {
            this.group = group;
        }

        public void capture()
        {
            this.current.copy(this.group.current);
            this.color.copy(this.group.color);
            this.lighting = this.group.lighting;
            this.hasOrient = this.group.orient != null;
            this.hasOffset = this.group.offset != null;

            if (this.hasOrient) this.orient.set(this.group.orient);
            if (this.hasOffset) this.offset.set(this.group.offset);
        }

        public void restore()
        {
            this.group.current.copy(this.current);
            this.group.color.copy(this.color);
            this.group.lighting = this.lighting;
            this.group.orient = this.hasOrient ? copy(this.group.orient, this.orient) : null;
            this.group.offset = this.hasOffset ? copy(this.group.offset, this.offset) : null;
        }
    }

    private static class BOBJBonePose
    {
        private final BOBJBone bone;
        private final mchorse.bbs_mod.utils.pose.Transform transform = new mchorse.bbs_mod.utils.pose.Transform();
        private final Quaternionf orient = new Quaternionf();
        private final Vector3f offset = new Vector3f();
        private boolean hasOrient;
        private boolean hasOffset;

        public BOBJBonePose(BOBJBone bone)
        {
            this.bone = bone;
        }

        public void capture()
        {
            this.transform.copy(this.bone.transform);
            this.hasOrient = this.bone.orient != null;
            this.hasOffset = this.bone.offset != null;

            if (this.hasOrient) this.orient.set(this.bone.orient);
            if (this.hasOffset) this.offset.set(this.bone.offset);
        }

        public void restore()
        {
            this.bone.transform.copy(this.transform);
            this.bone.orient = this.hasOrient ? copy(this.bone.orient, this.orient) : null;
            this.bone.offset = this.hasOffset ? copy(this.bone.offset, this.offset) : null;
        }
    }

    private static Quaternionf copy(Quaternionf target, Quaternionf source)
    {
        return target == null ? new Quaternionf(source) : target.set(source);
    }

    private static Vector3f copy(Vector3f target, Vector3f source)
    {
        return target == null ? new Vector3f(source) : target.set(source);
    }

    @Override
    protected void updateStencilMap(FormRenderingContext context)
    {
        ModelInstance model = this.getModel();

        if (model == null || model.model == null || context.stencilMap == null)
        {
            return;
        }

        model.fillStencilMap(context.stencilMap, this.form);

        if (this.form != null && this.form.ik.get() instanceof MapType ikMap)
        {
            ModelIKDebug.renderStencil(context.stack, model.model, ikMap, context.stencilMap, this.form);
        }

        if (this.form != null && this.form.physics.get() instanceof MapType physicsMap)
        {
            ModelPhysicsDebug.renderStencil(context.stack, model.model, physicsMap, context.stencilMap, this.form);
        }
    }

    private void captureMatrices(ModelInstance model)
    {
        /* A failed render can skip renderBodyParts(), so never let its captured
         * bones leak into the next placement or a model that removed a bone. */
        this.bones.clear();
        model.captureMatrices(this.bones);
    }

    @Override
    public void renderBodyParts(FormRenderingContext context)
    {
        PoseStack world = context.world;
        boolean stackPushed = false;
        boolean worldPushed = false;

        context.stack.pushPose();
        stackPushed = true;

        if (world != null)
        {
            world.pushPose();
            worldPushed = true;
        }

        try
        {
            for (BodyPart part : this.form.parts.getAllTyped())
            {
                Matrix4f matrix = this.bones.get(part.bone.get()).matrix();
                boolean childStackPushed = false;
                boolean childWorldPushed = false;

                context.stack.pushPose();
                childStackPushed = true;

                if (world != null)
                {
                    world.pushPose();
                    childWorldPushed = true;
                }

                try
                {
                    if (matrix != null)
                    {
                        MatrixStackUtils.multiply(context.stack, matrix);

                        if (world != null)
                        {
                            MatrixStackUtils.multiply(world, matrix);
                        }
                    }
                    else
                    {
                        context.stack.mulPose(ROTATE_Y_180);

                        if (world != null)
                        {
                            world.mulPose(ROTATE_Y_180);
                        }
                    }

                    this.renderBodyPart(part, context);
                }
                finally
                {
                    try
                    {
                        if (childStackPushed)
                        {
                            context.stack.popPose();
                        }
                    }
                    finally
                    {
                        if (childWorldPushed)
                        {
                            world.popPose();
                        }
                    }
                }
            }
        }
        finally
        {
            this.bones.clear();

            try
            {
                if (stackPushed)
                {
                    context.stack.popPose();
                }
            }
            finally
            {
                if (worldPushed)
                {
                    world.popPose();
                }
            }
        }
    }

    @Override
    public void collectMatrices(IEntity entity, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        this.collectMatrices(entity, null, null, false, false, stack, matrices, prefix, transition);
    }

    @Override
    protected void collectMatricesWithAppliedStates(IEntity entity, Object simulationOwner, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        this.bones.clear();
        this.ensureAnimator(transition);

        ModelInstance model = this.getModel();
        Matrix4f mm = new Matrix4f();
        Matrix4f oo = new Matrix4f();
        Matrix4f modelSemanticBase = null;

        if (semanticBase != null)
        {
            modelSemanticBase = new Matrix4f(semanticBase);
            this.applyTransforms(modelSemanticBase, transition);
            modelSemanticBase.rotate(ROTATE_Y_180);
        }

        stack.pushPose();
        try
        {
            this.applyTransforms(stack, true, transition);
            oo.set(stack.last().pose());
        }
        finally
        {
            stack.popPose();
        }

        stack.pushPose();
        try
        {
            this.applyTransforms(stack, false, transition);
            mm.set(stack.last().pose());

            matrices.put(prefix, mm, oo);

            /* Collect bones and add them to matrix list */
            if (this.animator != null && model != null)
            {
                /* Film placement-aware collection receives the exact semantic base and owner,
                 * so absolute target overrides and physics produce the same attachment bone as
                 * render3D. Legacy/UI collection keeps its isolated local IK-only behavior. */
                Object owner = simulationOwner == null ? this.uiSimulationOwner : simulationOwner;
                boolean reusePreviewPose = this.previewPoseSnapshot.restoreForMatrices(
                    owner,
                    entity,
                    model,
                    modelSemanticBase,
                    allowWorldTargetOverrides,
                    allowWorldCollisions,
                    transition
                );

                if (!reusePreviewPose)
                {
                    model.model.resetPose();

                    this.animator.applyActions(entity, model, transition);
                    model.model.applyPose(this.getPose(this.renderPose));

                    ModelConstraintsRuntime.apply(model);

                    this.applyIK(model, owner, modelSemanticBase, allowWorldTargetOverrides);

                    if (modelSemanticBase != null)
                    {
                        this.physicsRuntime.apply(entity, owner, model, transition, modelSemanticBase, allowWorldTargetOverrides, allowWorldCollisions);
                    }
                }

                stack.mulPose(ROTATE_Y_180);
                this.captureMatrices(model);
            }

            for (Map.Entry<String, MatrixCacheEntry> entry : this.bones.entrySet())
            {
                Matrix4f matrix = new Matrix4f();
                Matrix4f o = new Matrix4f();

                stack.pushPose();
                try
                {
                    MatrixStackUtils.multiply(stack, entry.getValue().matrix());
                    matrix.set(stack.last().pose());
                }
                finally
                {
                    stack.popPose();
                }

                stack.pushPose();
                try
                {
                    MatrixStackUtils.multiply(stack, entry.getValue().origin());
                    o.set(stack.last().pose());
                }
                finally
                {
                    stack.popPose();
                }

                matrices.put(
                    StringUtils.combinePaths(prefix, entry.getKey()),
                    matrix,
                    o,
                    entry.getValue().rotationOffset(),
                    entry.getValue().evaluatedRotation()
                );
            }

            int i = 0;

            /* Recursively do the same thing with body parts */
            for (BodyPart part : this.form.parts.getAllTyped())
            {
                Form form = part.getForm();

                if (form != null)
                {
                    Matrix4f matrix = this.bones.get(part.bone.get()).matrix();

                    stack.pushPose();
                    try
                    {
                        Matrix4f childSemanticBase = modelSemanticBase == null ? null : new Matrix4f(modelSemanticBase);

                        if (matrix != null)
                        {
                            MatrixStackUtils.multiply(stack, matrix);

                            if (childSemanticBase != null)
                            {
                                childSemanticBase.mul(matrix);
                            }
                        }
                        else
                        {
                            stack.mulPose(ROTATE_Y_180);

                            if (childSemanticBase != null)
                            {
                                childSemanticBase.rotate(ROTATE_Y_180);
                            }
                        }

                        MatrixStackUtils.applyTransform(stack, part.transform.get());

                        if (childSemanticBase != null)
                        {
                            childSemanticBase.mul(part.transform.get().setupMatrix(new Matrix4f()));
                        }

                        FormUtilsClient.getRenderer(form).collectMatrices(
                            part.getRenderEntity(entity),
                            simulationOwner,
                            childSemanticBase,
                            allowWorldTargetOverrides,
                            allowWorldCollisions,
                            stack,
                            matrices,
                            StringUtils.combinePaths(prefix, String.valueOf(i)),
                            transition
                        );
                    }
                    finally
                    {
                        stack.popPose();
                    }
                }

                i += 1;
            }
        }
        finally
        {
            this.bones.clear();
            stack.popPose();
        }
    }

    /**
     * Form-local displacement that drags the shadow under the model's perceived position: how far the
     * model has moved from its bind pose, counting BOTH the form's own transform (its keyframes) and
     * the anchor bone's root motion. Falls back to the base form-transform displacement when there's
     * no model or no anchor bone, so every form still shifts its shadow by its transform.
     */
    @Override
    public Vector3f getShadowDisplacement(IEntity entity, float transition)
    {
        return this.getShadowDisplacement(entity, null, null, false, false, transition);
    }

    @Override
    public Vector3f getShadowDisplacement(IEntity entity, Object simulationOwner, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, float transition)
    {
        ModelInstance model = this.getModel();

        if (model == null)
        {
            return super.getShadowDisplacement(entity, transition);
        }

        String anchor = model.getAnchor();

        if (anchor == null || anchor.isEmpty())
        {
            return super.getShadowDisplacement(entity, transition);
        }

        MatrixCache matrices = semanticBase == null
            ? this.collectMatrices(entity, transition)
            : this.collectMatrices(entity, simulationOwner, semanticBase, allowWorldTargetOverrides, allowWorldCollisions, transition);
        MatrixCacheEntry currentEntry = matrices.get(anchor);
        Vector3f current = currentEntry == null || currentEntry.origin() == null
            ? null
            : currentEntry.origin().getTranslation(new Vector3f());
        Vector3f rest = this.sampleRestBoneOrigin(anchor);

        if (current == null || rest == null)
        {
            return super.getShadowDisplacement(entity, transition);
        }

        return current.sub(rest);
    }

    /** Capture a bone's bind-pose origin in the same scaled/model-axis frame as collected matrices. */
    private Vector3f sampleRestBoneOrigin(String bone)
    {
        ModelInstance model = this.getModel();

        if (model == null)
        {
            return null;
        }

        PoseStack stack = new PoseStack();

        stack.pushPose();
        stack.scale(model.getScale().x, model.getScale().y, model.getScale().z);

        model.model.resetPose();

        stack.mulPose(ROTATE_Y_180);
        this.captureMatrices(model);

        Vector3f result = null;
        MatrixCacheEntry entry = this.bones.get(bone);

        if (entry != null)
        {
            stack.pushPose();
            MatrixStackUtils.multiply(stack, entry.origin());
            result = stack.last().pose().getTranslation(new Vector3f());
            stack.popPose();
        }

        this.bones.clear();
        stack.popPose();

        return result;
    }

    @Override
    public void tick(IEntity entity)
    {
        this.ensureAnimator(0F);

        if (this.animator != null)
        {
            this.recordExternalTick();
            this.animator.update(entity);
        }
    }

    /**
     * Run the list thumbnail's owed animation ticks through the animator, the same path the
     * editor's 3D preview uses via {@link #tick(IEntity)}.
     */
    private void advanceUIAnimation(UIContext context)
    {
        StubEntity source = this.getUITickEntity();

        this.driveUIAnimation(this.pollUIAnimationTicks(context), () ->
        {
            source.update();
            this.tick(source);
        });
    }
}
