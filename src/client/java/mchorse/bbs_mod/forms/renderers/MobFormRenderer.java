package mchorse.bbs_mod.forms.renderers;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.mixin.LimbAnimatorAccessor;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
import net.minecraft.nbt.TagParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MobFormRenderer extends FormRenderer<MobForm> implements ITickable
{
    public static final GameProfile WIDE = new GameProfile(UUID.fromString("b99a2400-28a8-4288-92dc-924beafbf756"), "McHorseYT");
    public static final GameProfile SLIM = new GameProfile(UUID.fromString("5477bd28-e672-4f87-a209-c03cf75f3606"), "osmiq");
    private static final VertexConsumer EMPTY_VERTEX_CONSUMER = new EmptyVertexConsumer();
    private static final MultiBufferSource EMPTY_VERTEX_CONSUMERS = (layer) -> EMPTY_VERTEX_CONSUMER;
    private static final int PAUSE_SAMPLE_TICK = 0;
    private static final int PAUSE_SAMPLE_UI = 1;
    private static final int PAUSE_SAMPLE_PREVIEW = 2;
    private static final int PAUSE_SAMPLE_WORLD = 3;

    private Entity entity;
    private String lastId = "";
    private String lastNBT = "";
    private MatrixCache bones = new MatrixCache();
    private List<String> pickedBoneIds = List.of();

    public float prevHandSwing;
    private boolean lastSlim;
    private boolean animationInitialized;
    private boolean animationSourceInitialized;
    private boolean animationPaused;
    private boolean animationResuming;
    private boolean pauseRequestPending;
    private boolean requestedPaused;
    private boolean runtimePauseActive;
    private boolean runtimePauseFromTick;
    private boolean pausedLookCaptured;
    private boolean pauseCaptureOpen;
    private float pausedTransition;
    private float requestTransition;
    private float requestLookTransition;
    private float limbPositionOffset;
    private float pausedHeadYaw;
    private float pausedPitch;
    private float resumeTransition;
    private float resumeStartHeadYaw;
    private float resumeStartPitch;
    private float resumeHeadYaw;
    private float resumePitch;
    private float lastRenderTransition;
    private int animationAgeOffset;
    private int pauseCapturePriority;
    private int lastRenderPriority;
    private int lastRenderAge = Integer.MIN_VALUE;

    public MobFormRenderer(MobForm form)
    {
        super(form);

        this.animationPaused = this.form.paused.getOriginalValue();
    }

    @Override
    public BoneHierarchy getBoneHierarchy()
    {
        this.ensureEntity();

        if (this.entity != null)
        {
            Object renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(this.entity);

            return VanillaRendererBones.discover(renderer).getBoneHierarchy();
        }

        return super.getBoneHierarchy();
    }

    private Pose mergeOverlays()
    {
        Pose overlay = this.form.poseOverlay.get().copy();

        for (ValuePose additional : this.form.additionalOverlays)
        {
            Pose additionalPose = additional.get();

            for (Map.Entry<String, PoseTransform> entry : additionalPose.transforms.entrySet())
            {
                PoseTransform target = overlay.get(entry.getKey());
                PoseTransform value = entry.getValue();

                if (value.fix != 0F)
                {
                    target.translate.lerp(value.translate, value.fix);
                    target.scale.lerp(value.scale, value.fix);
                    target.rotate.lerp(value.rotate, value.fix);
                    target.rotate2.lerp(value.rotate2, value.fix);
                }
                else
                {
                    target.translate.add(value.translate);
                    target.scale.add(value.scale).sub(1F, 1F, 1F);
                    target.rotate.add(value.rotate);
                    target.rotate2.add(value.rotate2);
                }
            }
        }

        return overlay;
    }

    private void bindTexture()
    {
        Link link = this.form.texture.get();

        if (link != null)
        {
            BBSModClient.getTextures().bindTexture(link);
        }
    }

    private void ensureEntity()
    {
        String id = this.form.mobID.get();
        String nbt = this.form.mobNBT.get();
        boolean slim = this.form.slim.get();

        if (!this.lastId.equals(id) || !this.lastNBT.equals(nbt) || slim != this.lastSlim)
        {
            this.lastId = id;
            this.lastNBT = nbt;
            this.lastSlim = slim;
            this.entity = null;
            this.bones.clear();
            this.pickedBoneIds = List.of();
            this.animationInitialized = false;
            this.animationSourceInitialized = false;
            this.animationPaused = this.form.paused.getOriginalValue();
            this.animationResuming = false;
            this.pauseRequestPending = false;
            this.runtimePauseActive = false;
            this.runtimePauseFromTick = false;
            this.pausedLookCaptured = false;
            this.pauseCaptureOpen = false;
            this.pausedTransition = 0F;
            this.limbPositionOffset = 0F;
            this.animationAgeOffset = 0;
            this.pauseCapturePriority = 0;
            this.lastRenderTransition = 0F;
            this.lastRenderPriority = PAUSE_SAMPLE_TICK;
            this.lastRenderAge = Integer.MIN_VALUE;
            this.prevHandSwing = 0F;
        }

        if (this.entity != null)
        {
            return;
        }

        CompoundTag compound = new CompoundTag();

        try
        {
            compound = TagParser.parseTag(nbt);
        }
        catch (Exception e)
        {}

        if (this.form.isPlayer())
        {
            this.entity = new MobPlayer(Minecraft.getInstance().level, slim ? SLIM : WIDE);
            this.entity.getEntityData().set(PlayerUtils.ProtectedAccess.getModelParts(), (byte) 0b1111111);
        }
        else
        {
            this.entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id)).create(Minecraft.getInstance().level);
        }

        if (this.entity != null)
        {
            compound.putString("id", id);
            this.entity.load(compound);
            this.entity.noPhysics = true;
        }
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        this.ensureEntity();
        if (this.entity != null)
        {
            this.ensureAnimationInitialized(null);

            PoseStack stack = context.batcher.getContext().pose();

            stack.pushPose();

            Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);
            CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
            float scale = this.form.uiScale.get();
            float width = this.entity.getBbWidth();
            float height = this.entity.getBbHeight();

            scale = scale * Math.min(1.8F / Math.max(width, height), 1F);

            this.applyTransforms(uiMatrix, context.getTransition());
            MatrixStackUtils.multiply(stack, uiMatrix);
            stack.scale(scale, scale, scale);

            if (!this.form.mobID.get().equals("minecraft:ender_dragon"))
            {
                stack.mulPose(Axis.YP.rotation(MathUtils.PI));
            }

            stack.last().normal().getScale(Vectors.EMPTY_3F);
            stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

            BooleanHolder first = new BooleanHolder();

            CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
            {
                if (!first.bool)
                {
                    this.bindTexture();

                    first.bool = true;
                }

                RenderSystem.enableBlend();
            });

            consumers.setUI(true);
            Object renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(this.entity);

            float transition = this.getUIAnimationTransition(context.getTransition());

            this.prepareRenderLook(null, context.getTransition());
            this.recordRenderSample(transition, PAUSE_SAMPLE_UI);

            try (MobRenderContext ignored = MobRenderContext.push(renderer, this.form.pose.get(), this.mergeOverlays(), this.getColor(0xffffffff)))
            {
                Minecraft.getInstance().getEntityRenderDispatcher().render(this.entity, 0D, 0D, 0D, 0F, transition, stack, consumers, LightTexture.FULL_BLOCK);
            }

            consumers.draw();
            consumers.setUI(false);

            CustomVertexConsumerProvider.clearRunnables();

            stack.popPose();

            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        }
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        this.ensureEntity();
        this.bones.clear();
        this.pickedBoneIds = List.of();

        if (this.entity != null)
        {
            CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
            int light = context.light;
            BooleanHolder first = new BooleanHolder();
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());

            if (context.isPicking())
            {
                CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
                {
                    if (!first.bool)
                    {
                        this.bindTexture();

                        first.bool = true;
                    }

                    /* Piglin equipment and other extra entity layers are emitted
                     * after the base layer. Reinstall the picker shader for each
                     * one so vanilla state cannot leak into the stencil pass. */
                    this.setupTarget(context, BBSShaders.getPickerModelsProgram());
                    RenderSystem.setShader(BBSShaders::getPickerModelsProgram);
                });

                light = 0;
            }
            else
            {
                CustomVertexConsumerProvider.hijackLayerPreparation((layer) -> this.createWorldLayerPreparation(first));
            }

            context.stack.pushPose();
            if (context.world != null)
            {
                context.world.pushPose();
            }

            /* The sort origin, the layer hijack and both matrix stacks are process-wide state that
             * every later form in the frame reads. A vanilla entity render can throw (malformed NBT,
             * a third-party entity renderer), so release them from a finally rather than from the
             * success path, the same way ModelFormRenderer does. */
            try
            {
                Matrix4f captureBase = new Matrix4f(context.stack.last().pose());

                if (this.form.mobID.get().equals("minecraft:ender_dragon"))
                {
                    context.stack.mulPose(Axis.YP.rotation(MathUtils.PI));
                    if (context.world != null)
                    {
                        context.world.mulPose(Axis.YP.rotation(MathUtils.PI));
                    }
                }

                if (this.entity instanceof LivingEntity entity)
                {
                    int u = context.overlay & '\uffff';
                    int v = context.overlay >> 16 & '\uffff';

                    entity.hurtTime = v != 10 ? 100 : 0;
                }

                Object renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(this.entity);
                boolean incrementPicking = context.stencilMap != null && context.stencilMap.increment;
                MobRenderContext mobContext = MobRenderContext.push(
                    renderer,
                    this.form.pose.get(),
                    this.mergeOverlays(),
                    this.getColor(context.color),
                    captureBase,
                    context.isPicking(),
                    incrementPicking
                );

                try (mobContext)
                {
                    float transition = this.prepareAnimationRender(context);

                    /* Publishing the form's camera-space origin opts its translucent layers (slime
                     * bodies, ghost textures) into the deferred sorted pass. */
                    if (context.canDeferWorldTranslucency())
                    {
                        Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());

                        FormTranslucentQueue.setSortOrigin(new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(origin));
                    }

                    Minecraft.getInstance().getEntityRenderDispatcher().render(
                        this.entity,
                        0D,
                        0D,
                        0D,
                        0F,
                        transition,
                        context.stack,
                        consumers,
                        light
                    );

                    mobContext.completeMatrices();
                }

                this.bones = mobContext.getMatrices();
                this.pickedBoneIds = mobContext.getPickedBoneIds();

                consumers.draw();
            }
            finally
            {
                FormTranslucentQueue.setSortOrigin(null);
                CustomVertexConsumerProvider.clearRunnables();

                context.stack.popPose();

                if (context.world != null)
                {
                    context.world.popPose();
                }

                /* A MobForm body part can reach this path from either a 2D preview or a world
                 * render. Preserve the caller-owned model-view matrix in both cases and restore
                 * only the depth state required by the active rendering context. */
                if (context.ui)
                {
                    RenderSystem.depthFunc(GL11.GL_ALWAYS);
                }
                else
                {
                    RenderSystem.enableDepthTest();
                    RenderSystem.getModelViewMatrix().set(modelView);
                }
            }
        }
    }

    private float prepareAnimationRender(FormRenderingContext context)
    {
        this.ensureAnimationInitialized(context.entity);

        if (!context.isPicking())
        {
            this.sampleRenderPause(context.entity, context.getTransition(), this.getPauseSamplePriority(context));
        }

        float transition = this.getAnimationTransition(context.getTransition());

        this.prepareRenderLook(context.entity, context.getTransition());

        if (!context.isPicking())
        {
            int priority = this.getPauseSamplePriority(context);
            boolean recorded = this.recordRenderSample(transition, priority);

            if (recorded && (this.animationPaused || this.pauseRequestPending))
            {
                this.captureCurrentLook();
            }
        }

        return transition;
    }

    private Color getColor(int contextColor)
    {
        Color color = new Color().set(contextColor, true);

        FormColorBlend.blend(color, this.form.color.get(), this.form.additiveColor.get());

        return color;
    }

    @Override
    protected void updateStencilMap(FormRenderingContext context)
    {
        StencilMap stencilMap = context.stencilMap;

        if (stencilMap == null)
        {
            return;
        }

        stencilMap.addPicking(this.form);

        if (stencilMap.increment)
        {
            for (String bone : this.pickedBoneIds)
            {
                stencilMap.addPicking(this.form, bone);
            }
        }
    }

    @Override
    public void renderBodyParts(FormRenderingContext context)
    {
        for (BodyPart part : this.form.parts.getAllTyped())
        {
            String boneId = this.getBoneHierarchy().resolveId(part.bone.get());
            Matrix4f matrix = this.bones.get(boneId == null ? part.bone.get() : boneId).matrix();

            context.stack.pushPose();
            if (context.world != null)
            {
                context.world.pushPose();
            }

            if (matrix != null)
            {
                MatrixStackUtils.multiply(context.stack, matrix);
                if (context.world != null)
                {
                    MatrixStackUtils.multiply(context.world, matrix);
                }
            }

            this.renderBodyPart(part, context);

            context.stack.popPose();
            if (context.world != null)
            {
                context.world.popPose();
            }
        }
    }

    @Override
    protected void collectMatricesWithAppliedStates(
        IEntity entity,
        Object simulationOwner,
        Matrix4f semanticBase,
        boolean allowWorldTargetOverrides,
        boolean allowWorldCollisions,
        PoseStack stack,
        MatrixCache matrices,
        String prefix,
        float transition
    )
    {
        MatrixCache bones = this.collectBoneMatrices(entity, transition);
        Matrix4f matrix = new Matrix4f();
        Matrix4f origin = new Matrix4f();

        stack.pushPose();
        this.applyTransforms(stack, true, transition);
        origin.set(stack.last().pose());
        stack.popPose();

        stack.pushPose();
        this.applyTransforms(stack, false, transition);
        matrix.set(stack.last().pose());
        matrices.put(prefix, matrix, origin);

        Matrix4f formSemanticBase = null;

        if (semanticBase != null)
        {
            formSemanticBase = new Matrix4f(semanticBase);
            this.applyTransforms(formSemanticBase, transition);
        }

        for (Map.Entry<String, MatrixCacheEntry> entry : bones.entrySet())
        {
            Matrix4f boneMatrix = new Matrix4f(stack.last().pose()).mul(entry.getValue().matrix());
            Matrix4f boneOrigin = new Matrix4f(stack.last().pose()).mul(entry.getValue().origin());

            matrices.put(StringUtils.combinePaths(prefix, entry.getKey()), boneMatrix, boneOrigin, entry.getValue().rotationOffset());
        }

        int i = 0;

        for (BodyPart part : this.form.parts.getAllTyped())
        {
            Form form = part.getForm();

            if (form != null)
            {
                String boneId = this.getBoneHierarchy().resolveId(part.bone.get());
                Matrix4f boneMatrix = bones.get(boneId == null ? part.bone.get() : boneId).matrix();

                stack.pushPose();

                if (boneMatrix != null)
                {
                    MatrixStackUtils.multiply(stack, boneMatrix);
                }

                MatrixStackUtils.applyTransform(stack, part.transform.get());

                Matrix4f childSemanticBase = formSemanticBase == null ? null : new Matrix4f(formSemanticBase);

                if (childSemanticBase != null)
                {
                    if (boneMatrix != null)
                    {
                        childSemanticBase.mul(boneMatrix);
                    }

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

                stack.popPose();
            }

            i += 1;
        }

        stack.popPose();
    }

    private MatrixCache collectBoneMatrices(IEntity source, float transition)
    {
        this.ensureEntity();
        this.ensureAnimationInitialized(source);

        if (this.entity == null)
        {
            return new MatrixCache();
        }

        Minecraft client = Minecraft.getInstance();
        PoseStack stack = new PoseStack();
        Matrix4f captureBase = new Matrix4f(stack.last().pose());

        if (this.form.mobID.get().equals("minecraft:ender_dragon"))
        {
            stack.mulPose(Axis.YP.rotation(MathUtils.PI));
        }

        Object renderer = client.getEntityRenderDispatcher().getRenderer(this.entity);
        MobRenderContext context = MobRenderContext.push(
            renderer,
            this.form.pose.get(),
            this.mergeOverlays(),
            Color.white(),
            captureBase,
            false,
            false
        );

        try (context)
        {
            float animationTransition = this.getAnimationTransition(transition);

            this.prepareRenderLook(source, transition);

            client.getEntityRenderDispatcher().render(
                this.entity,
                0D,
                0D,
                0D,
                0F,
                animationTransition,
                stack,
                EMPTY_VERTEX_CONSUMERS,
                LightTexture.FULL_BRIGHT
            );
            context.completeMatrices();
        }

        return context.getMatrices();
    }

    private Runnable createWorldLayerPreparation(BooleanHolder first)
    {
        boolean bindTexture = !first.bool;
        Link texture = bindTexture ? this.form.texture.get() : null;

        first.bool = true;

        return () ->
        {
            if (texture != null)
            {
                BBSModClient.getTextures().bindTexture(texture);
            }

            RenderSystem.enableBlend();
        };
    }

    @Override
    public void tick(IEntity source)
    {
        this.ensureEntity();

        if (this.entity == null)
        {
            return;
        }

        boolean initialized = this.animationInitialized;

        this.ensureAnimationInitialized(source);

        boolean finishingResume = this.animationResuming;
        boolean resuming = this.updatePauseState(source, initialized, finishingResume);
        boolean paused = this.animationPaused;
        int ageBeforeTick = this.entity.tickCount;
        float limbSpeedBeforeTick = this.getLimbSpeed();

        if (!paused && !resuming)
        {
            this.entity.tick();
        }

        this.entity.xRotO = source.getPrevPitch();

        this.updateLivingAnimation(source, paused, resuming, finishingResume, limbSpeedBeforeTick);
        this.synchronizeEntity(source, paused, resuming, ageBeforeTick);

        if (resuming)
        {
            this.animationSourceInitialized = true;
        }

        this.lastRenderPriority = PAUSE_SAMPLE_TICK;
    }

    private boolean updatePauseState(IEntity source, boolean initialized, boolean finishingResume)
    {
        float tickTransition = initialized ? 1F : 0F;
        IEntity pauseSource = source;

        if (initialized && !finishingResume && this.lastRenderAge == this.entity.tickCount)
        {
            tickTransition = this.lastRenderTransition;
            pauseSource = null;
        }

        this.sampleTickPause(pauseSource, tickTransition);
        this.animationResuming = false;

        if (!this.pauseRequestPending)
        {
            this.pauseCaptureOpen = false;

            return false;
        }

        boolean resuming = this.animationPaused && !this.requestedPaused;

        if (this.requestedPaused && !this.pausedLookCaptured)
        {
            this.capturePausedAnimation(this.requestTransition, this.requestLookTransition, source);
        }

        this.animationPaused = this.requestedPaused;
        this.pauseRequestPending = false;
        this.pauseCaptureOpen = false;

        if (resuming)
        {
            this.animationResuming = true;
            this.resumeTransition = this.pausedTransition;
            this.resumeStartHeadYaw = this.pausedHeadYaw;
            this.resumeStartPitch = this.pausedPitch;
            this.resumeHeadYaw = source.getHeadYaw() - source.getBodyYaw();
            this.resumePitch = source.getPitch();
        }

        return resuming;
    }

    private float getLimbSpeed()
    {
        if (this.entity instanceof LivingEntity livingEntity && livingEntity.walkAnimation instanceof LimbAnimatorAccessor animator)
        {
            return animator.getSpeed();
        }

        return 0F;
    }

    private void updateLivingAnimation(IEntity source, boolean paused, boolean resuming, boolean finishingResume, float limbSpeedBeforeTick)
    {
        this.entity.yRotO = 0F;

        if (!(this.entity instanceof LivingEntity livingEntity))
        {
            return;
        }

        livingEntity.yBodyRotO = 0F;
        livingEntity.yHeadRotO = source.getPrevHeadYaw() - source.getPrevBodyYaw();

        if (paused)
        {
            return;
        }

        /* Limb swing is so ugly */
        if (livingEntity.walkAnimation instanceof LimbAnimatorAccessor target && source.getLimbAnimator() instanceof LimbAnimatorAccessor sourceAnimator)
        {
            if (resuming)
            {
                this.limbPositionOffset = target.getPosition() - sourceAnimator.getPosition();
            }
            else
            {
                target.setSpeedOld(finishingResume ? limbSpeedBeforeTick : sourceAnimator.getSpeedOld());
                target.setSpeed(sourceAnimator.getSpeed());
                target.setPosition(sourceAnimator.getPosition() + this.limbPositionOffset);
            }
        }

        this.updateHandSwing(source, livingEntity, resuming);
    }

    private void updateHandSwing(IEntity source, LivingEntity livingEntity, boolean resuming)
    {
        float handSwingProgress = source.getHandSwingProgress(0F);

        if (resuming)
        {
            this.prevHandSwing = handSwingProgress;

            return;
        }

        if (handSwingProgress < this.prevHandSwing)
        {
            this.prevHandSwing = 0F;
        }

        if (handSwingProgress > 0F && this.prevHandSwing == 0F)
        {
            livingEntity.swing(InteractionHand.MAIN_HAND);
        }

        this.prevHandSwing = handSwingProgress;
    }

    private void synchronizeEntity(IEntity source, boolean paused, boolean resuming, int ageBeforeTick)
    {
        this.entity.setYRot(0F);
        this.entity.setYHeadRot(source.getHeadYaw() - source.getBodyYaw());
        this.entity.setXRot(source.getPitch());
        this.entity.setYBodyRot(0F);
        this.entity.setPos(source.getX(), source.getY(), source.getZ());
        this.entity.setOnGround(source.isOnGround());
        this.entity.setShiftKeyDown(source.isSneaking());
        this.entity.setSprinting(source.isSprinting());
        this.entity.setPose(source.isSneaking() ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
        if (this.entity instanceof LivingEntity livingEntity)
        {
            livingEntity.setItemSlot(EquipmentSlot.MAINHAND, source.getEquipmentStack(EquipmentSlot.MAINHAND));
            livingEntity.setItemSlot(EquipmentSlot.OFFHAND, source.getEquipmentStack(EquipmentSlot.OFFHAND));
            livingEntity.setItemSlot(EquipmentSlot.HEAD, source.getEquipmentStack(EquipmentSlot.HEAD));
            livingEntity.setItemSlot(EquipmentSlot.CHEST, source.getEquipmentStack(EquipmentSlot.CHEST));
            livingEntity.setItemSlot(EquipmentSlot.LEGS, source.getEquipmentStack(EquipmentSlot.LEGS));
            livingEntity.setItemSlot(EquipmentSlot.FEET, source.getEquipmentStack(EquipmentSlot.FEET));
        }

        if (!paused)
        {
            if (resuming)
            {
                this.animationAgeOffset = ageBeforeTick - source.getAge();
            }
            else
            {
                this.entity.tickCount = source.getAge() + this.animationAgeOffset;
            }
        }
        else
        {
            this.captureCurrentLook();
        }

        this.entity.noPhysics = true;
    }

    private void ensureAnimationInitialized(IEntity source)
    {
        if (this.entity == null)
        {
            return;
        }

        boolean visualInitialized = this.animationInitialized;

        if (!this.animationInitialized)
        {
            this.animationInitialized = true;
            this.animationAgeOffset = 0;
        }

        if (source == null || this.animationSourceInitialized)
        {
            return;
        }

        if (visualInitialized && (this.animationPaused || this.form.paused.get()))
        {
            return;
        }

        if (this.entity instanceof LivingEntity livingEntity && livingEntity.walkAnimation instanceof LimbAnimatorAccessor target && source.getLimbAnimator() instanceof LimbAnimatorAccessor sourceAnimator)
        {
            target.setSpeedOld(sourceAnimator.getSpeedOld());
            target.setSpeed(sourceAnimator.getSpeed());
            target.setPosition(sourceAnimator.getPosition());
        }

        this.entity.tickCount = source.getAge();
        this.prevHandSwing = source.getHandSwingProgress(0F);
        this.animationAgeOffset = 0;
        this.animationSourceInitialized = true;
    }

    private void sampleTickPause(IEntity source, float transition)
    {
        Boolean runtimePaused = this.form.paused.getRuntimeValue();

        if (runtimePaused != null)
        {
            this.runtimePauseActive = true;
            this.runtimePauseFromTick = true;
            this.requestAnimationPause(runtimePaused, transition, transition, source, PAUSE_SAMPLE_TICK);

            return;
        }

        if (this.runtimePauseFromTick)
        {
            this.runtimePauseActive = false;
            this.runtimePauseFromTick = false;
        }

        if (!this.runtimePauseActive)
        {
            boolean paused = this.form.paused.getOriginalValue();

            this.requestAnimationPause(paused, transition, transition, source, PAUSE_SAMPLE_TICK);
        }
    }

    private void sampleRenderPause(IEntity source, float transition, int priority)
    {
        boolean runtime = this.form.paused.getRuntimeValue() != null;
        float animationTransition = this.animationResuming
            ? Mth.lerp(transition, this.resumeTransition, 1F)
            : transition;

        if (!runtime)
        {
            this.runtimePauseFromTick = false;
        }

        this.runtimePauseActive = runtime;
        this.requestAnimationPause(this.form.paused.get(), animationTransition, transition, source, priority);
    }

    private int getPauseSamplePriority(FormRenderingContext context)
    {
        if (context.ui || (context.type != FormRenderType.ENTITY && context.type != FormRenderType.MODEL_BLOCK))
        {
            return context.type == FormRenderType.PREVIEW ? PAUSE_SAMPLE_PREVIEW : PAUSE_SAMPLE_UI;
        }

        return PAUSE_SAMPLE_WORLD;
    }

    private boolean recordRenderSample(float transition, int priority)
    {
        if (priority < this.lastRenderPriority)
        {
            return false;
        }

        this.lastRenderTransition = transition;
        this.lastRenderPriority = priority;
        this.lastRenderAge = this.entity.tickCount;

        return true;
    }

    private void requestAnimationPause(boolean paused, float animationTransition, float lookTransition, IEntity source, int priority)
    {
        if (this.pauseRequestPending)
        {
            if (paused == this.requestedPaused)
            {
                if (paused && this.pauseCaptureOpen && priority > this.pauseCapturePriority)
                {
                    this.requestTransition = animationTransition;
                    this.requestLookTransition = lookTransition;
                    this.pauseCapturePriority = priority;
                    this.capturePausedAnimation(animationTransition, lookTransition, source);
                }

                return;
            }

            this.pauseRequestPending = false;

            if (!this.animationPaused)
            {
                this.pausedLookCaptured = false;
                this.pauseCaptureOpen = false;
                this.pauseCapturePriority = 0;
            }

            return;
        }

        if (paused == this.animationPaused)
        {
            if (paused && !this.pausedLookCaptured && this.animationInitialized)
            {
                this.pauseCaptureOpen = true;
                this.pauseCapturePriority = priority;
                this.capturePausedAnimation(animationTransition, lookTransition, source);
            }
            else if (paused && this.pauseCaptureOpen && priority > this.pauseCapturePriority)
            {
                this.pauseCapturePriority = priority;
                this.capturePausedAnimation(animationTransition, lookTransition, source);
            }

            return;
        }

        this.pauseRequestPending = true;
        this.requestedPaused = paused;
        this.requestTransition = animationTransition;
        this.requestLookTransition = lookTransition;
        this.pauseCaptureOpen = paused;
        this.pauseCapturePriority = paused ? priority : 0;

        if (paused && this.animationInitialized)
        {
            this.capturePausedAnimation(animationTransition, lookTransition, source);
        }
    }

    private float getAnimationTransition(float transition)
    {
        if (this.animationPaused || this.pauseRequestPending)
        {
            return this.pausedTransition;
        }

        return this.animationResuming ? Mth.lerp(transition, this.resumeTransition, 1F) : transition;
    }

    private float getUIAnimationTransition(float transition)
    {
        boolean paused = this.form.paused.get();
        boolean pendingPause = this.pauseRequestPending && this.requestedPaused;

        if (this.runtimePauseActive && !this.runtimePauseFromTick && this.lastRenderPriority <= PAUSE_SAMPLE_UI && this.form.paused.getRuntimeValue() == null)
        {
            this.runtimePauseActive = false;
        }

        if (paused && (!this.pausedLookCaptured || (!this.animationPaused && !pendingPause)))
        {
            float pauseTransition = this.lastRenderAge == this.entity.tickCount ? this.lastRenderTransition : transition;

            this.pauseCaptureOpen = true;
            this.pauseCapturePriority = PAUSE_SAMPLE_UI;
            this.capturePausedAnimation(pauseTransition, pauseTransition, null);
        }

        if (paused)
        {
            return this.pausedTransition;
        }

        if (!this.runtimePauseActive && !this.pauseRequestPending && paused != this.animationPaused)
        {
            return transition;
        }

        return this.getAnimationTransition(transition);
    }

    private void capturePausedAnimation(float animationTransition, float lookTransition, IEntity source)
    {
        if (!this.animationInitialized)
        {
            return;
        }

        if (source != null)
        {
            this.applyRenderLook(source, lookTransition);
        }

        this.pausedTransition = animationTransition;

        this.pausedPitch = source == null
            ? Mth.lerp(lookTransition, this.entity.xRotO, this.entity.getXRot())
            : this.entity.getXRot();
        this.pausedHeadYaw = this.entity.getYHeadRot();

        if (source == null && this.entity instanceof LivingEntity livingEntity)
        {
            this.pausedHeadYaw = Mth.rotLerp(lookTransition, livingEntity.yHeadRotO, livingEntity.getYHeadRot());
        }

        this.pausedLookCaptured = true;
        this.applyPausedLook();
    }

    private void captureCurrentLook()
    {
        this.pausedPitch = this.entity.getXRot();
        this.pausedHeadYaw = this.entity.getYHeadRot();
        this.pausedLookCaptured = true;
    }

    private void applyPausedLook()
    {
        this.entity.xRotO = this.pausedPitch;
        this.entity.setXRot(this.pausedPitch);
        this.entity.setYHeadRot(this.pausedHeadYaw);

        if (this.entity instanceof LivingEntity livingEntity)
        {
            livingEntity.yBodyRotO = 0F;
            livingEntity.setYBodyRot(0F);
            livingEntity.yHeadRotO = this.pausedHeadYaw;
        }
    }

    private void applyResumeLook(float transition)
    {
        float headYaw = Mth.rotLerp(transition, this.resumeStartHeadYaw, this.resumeHeadYaw);
        float pitch = Mth.lerp(transition, this.resumeStartPitch, this.resumePitch);
        this.entity.xRotO = pitch;
        this.entity.setXRot(pitch);
        this.entity.setYHeadRot(headYaw);

        if (this.entity instanceof LivingEntity livingEntity)
        {
            livingEntity.yBodyRotO = 0F;
            livingEntity.setYBodyRot(0F);
            livingEntity.yHeadRotO = headYaw;
        }
    }

    /**
     * Resolves look angles once per render from the source entity. Vanilla normally performs this
     * interpolation inside LivingEntityRenderer, but MobForm keeps body yaw outside that renderer;
     * synchronizing only tick endpoints would therefore interpolate the already-subtracted angle.
     * Look remains source-driven while the animation clock is paused.
     */
    private void prepareRenderLook(IEntity source, float transition)
    {
        if (source != null)
        {
            this.applyRenderLook(source, transition);

            return;
        }

        if (this.animationPaused || this.pauseRequestPending)
        {
            if (this.pausedLookCaptured)
            {
                this.applyPausedLook();
            }

            return;
        }

        if (this.animationResuming)
        {
            this.applyResumeLook(transition);

            return;
        }
    }

    private void applyRenderLook(IEntity source, float transition)
    {
        float interpolatedHeadYaw = Mth.rotLerp(transition, source.getPrevHeadYaw(), source.getHeadYaw());
        float interpolatedBodyYaw = Mth.rotLerp(transition, source.getPrevBodyYaw(), source.getBodyYaw());
        float relativeHeadYaw = interpolatedHeadYaw - interpolatedBodyYaw;
        float interpolatedPitch = Mth.lerp(transition, source.getPrevPitch(), source.getPitch());

        this.entity.xRotO = interpolatedPitch;
        this.entity.setXRot(interpolatedPitch);

        if (this.entity instanceof LivingEntity livingEntity)
        {
            livingEntity.yBodyRotO = 0F;
            livingEntity.setYBodyRot(0F);
            livingEntity.yHeadRotO = relativeHeadYaw;
            livingEntity.setYHeadRot(relativeHeadYaw);
        }
    }

    private static class BooleanHolder
    {
        public boolean bool;
    }

    /** Render-only players must never push the real local player they overlap. */
    private static class MobPlayer extends RemotePlayer
    {
        public MobPlayer(ClientLevel level, GameProfile profile)
        {
            super(level, profile);
        }

        @Override
        protected void pushEntities()
        {}
    }

    private static class EmptyVertexConsumer implements VertexConsumer
    {
        @Override
        public VertexConsumer addVertex(float x, float y, float z)
        {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z)
        {
            return this;
        }
    }
}
