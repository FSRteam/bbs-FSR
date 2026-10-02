package mchorse.bbs_mod.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.mixin.client.EntityRendererDispatcherInvoker;
import mchorse.bbs_mod.mixin.client.LevelRendererAccessor;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.pose.Transform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.SortedSet;

public class ModelBlockEntityRenderer implements BlockEntityRenderer<ModelBlockEntity>
{
    private static ActorEntity entity;

    /** The registered renderer instance, so the render-last queue can replay through it. */
    private static ModelBlockEntityRenderer instance;

    private final Transform lookTransform = new Transform();
    private final Vector3f headTranslation = new Vector3f();

    public static void renderShadow(MultiBufferSource provider, PoseStack matrices, float tickDelta, double x, double y, double z, float tx, float ty, float tz)
    {
        renderShadow(provider, matrices, tickDelta, x, y, z, tx, ty, tz, 0.5F, 1F);
    }

    public static void renderShadow(MultiBufferSource provider, PoseStack matrices, float tickDelta, double x, double y, double z, float tx, float ty, float tz, float radius, float opacity)
    {
        ClientLevel world = Minecraft.getInstance().level;

        if (world == null)
        {
            return;
        }

        if (entity == null || entity.level() != world)
        {
            entity = new ActorEntity(BBSMod.ACTOR_ENTITY.get(), world);
        }

        entity.setPos(x, y, z);
        entity.xOld = x;
        entity.yOld = y;
        entity.zOld = z;
        entity.xo = x;
        entity.yo = y;
        entity.zo = z;

        double distance = Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(x, y, z);

        opacity = (float) ((1D - distance / 256D) * opacity);

        matrices.pushPose();
        matrices.translate(tx, ty, tz);

        EntityRendererDispatcherInvoker.bbs$renderShadow(matrices, provider, entity, opacity, tickDelta, entity.level(), radius);

        matrices.popPose();
    }

    private static float getHeadYaw(float constraint, float yawDelta, float travel)
    {
        float headLimit = (float) Math.toRadians(constraint);
        float headYawBase = MathUtils.clamp(yawDelta, -headLimit, headLimit);

        float syncStart = (float) Math.toRadians(315D);
        float syncRange = (float) Math.toRadians(45D);
        float t = 0F;

        if (travel >= syncStart)
        {
            t = Math.min(1F, (travel - syncStart) / syncRange);
        }

        return headYawBase * (1F - t);
    }

    public ModelBlockEntityRenderer(BlockEntityRendererProvider.Context ctx)
    {
        instance = this;
    }

    @Override
    public boolean shouldRenderOffScreen(ModelBlockEntity blockEntity)
    {
        return blockEntity.getProperties().isGlobal();
    }

    @Override
    public void render(ModelBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        Minecraft mc = Minecraft.getInstance();
        ModelProperties properties = entity.getProperties();
        Transform transform = properties.getTransform();
        BlockPos pos = entity.getBlockPos();

        if (ModelBlockRenderLastQueue.shouldDefer(entity))
        {
            /* Render-last blocks skip the block-entity pass; the queue replays them at the
             * AFTER_BLOCK_ENTITIES stage (see ModelBlockRenderLastQueue). The dispatcher's
             * current stack — already translated to this block — is captured so the replay
             * draws through the exact transform. Shadows still draw here — they go through
             * the vanilla buffer and are order-independent. Break cracks stay on the
             * immediate path only: the deferred replay draws after the vanilla crumbling
             * stage has already run this frame. */
            ModelBlockRenderLastQueue.add(entity, tickDelta, matrices, light, overlay);

            if (properties.isShadow())
            {
                this.renderShadowFor(entity, matrices, vertexConsumers, tickDelta);
            }

            return;
        }

        /* While the matrices still sit at the cell's corner. */
        this.renderBreakingOverlay(mc, entity, matrices);

        matrices.pushPose();
        matrices.translate(0.5F, 0F, 0.5F);

        if (properties.getForm() != null && this.canRender(entity))
        {
            matrices.pushPose();

            Transform applied = transform;

            if (properties.isLookAt())
            {
                applied = this.applyLookingAnimation(mc, entity, properties, tickDelta);
            }
            else
            {
                IEntity iEntity = entity.getEntity();

                entity.resetLookYaw();
                iEntity.setHeadYaw(0F);
                iEntity.setPrevHeadYaw(0F);
                iEntity.setPitch(0F);
                iEntity.setPrevPitch(0F);
            }

            MatrixStackUtils.applyTransform(matrices, applied);

            PoseStack semanticWorld = new PoseStack();

            semanticWorld.translate(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            MatrixStackUtils.applyTransform(semanticWorld, applied);

            int lightAbove = LevelRenderer.getLightColor(entity.getLevel(), pos.offset((int) transform.translate.x, (int) transform.translate.y, (int) transform.translate.z));
            Camera camera = mc.gameRenderer.getMainCamera();

            RenderSystem.enableDepthTest();
            MorphRenderer.renderForm(properties.getForm(), new FormRenderingContext()
                .set(FormRenderType.MODEL_BLOCK, entity.getEntity(), matrices, lightAbove, overlay, tickDelta)
                .entityLocal(semanticWorld)
                .camera(camera));
            RenderSystem.disableDepthTest();

            if (this.canRenderAxes(entity) && UIBaseMenu.shouldRenderAxes())
            {
                matrices.pushPose();
                MatrixStackUtils.scaleBack(matrices);
                Draw.coolerAxes(matrices, 0.5F, 0.01F, 0.51F, 0.02F);
                matrices.popPose();
            }

            matrices.popPose();
        }

        RenderSystem.disableDepthTest();

        if (mc.getDebugOverlay().showDebugScreen())
        {
            Draw.renderBox(matrices, -0.5D, 0, -0.5D, 1, 1, 1, 0, 0.5F, 1F, 0.5F);
        }

        matrices.popPose();

        if (properties.isShadow())
        {
            this.renderShadowFor(entity, matrices, vertexConsumers, tickDelta);
        }
    }

    /**
     * Deferred replay for render-last model blocks: the same draw the immediate path performs,
     * minus the editor axes/debug box helpers (immediate-pass only) and the shadow — shadows
     * already drew during the block-entity pass through the vanilla buffer, where their draw
     * order does not matter. The camera-relative {@code matrices} comes from the
     * AFTER_BLOCK_ENTITIES stage, so the model lands in the same world position it would have
     * had during the block-entity pass.
     */
    public static void renderDeferred(ModelBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        ModelBlockEntityRenderer renderer = instance;

        if (renderer == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ModelProperties properties = entity.getProperties();
        Transform transform = properties.getTransform();
        BlockPos pos = entity.getBlockPos();

        matrices.pushPose();
        matrices.translate(0.5F, 0F, 0.5F);

        if (properties.getForm() != null && renderer.canRender(entity))
        {
            matrices.pushPose();

            Transform applied = transform;

            if (properties.isLookAt())
            {
                applied = renderer.applyLookingAnimation(mc, entity, properties, tickDelta);
            }
            else
            {
                IEntity iEntity = entity.getEntity();

                entity.resetLookYaw();
                iEntity.setHeadYaw(0F);
                iEntity.setPrevHeadYaw(0F);
                iEntity.setPitch(0F);
                iEntity.setPrevPitch(0F);
            }

            MatrixStackUtils.applyTransform(matrices, applied);

            PoseStack semanticWorld = new PoseStack();

            semanticWorld.translate(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            MatrixStackUtils.applyTransform(semanticWorld, applied);

            int lightAbove = LevelRenderer.getLightColor(entity.getLevel(), pos.offset((int) transform.translate.x, (int) transform.translate.y, (int) transform.translate.z));
            Camera camera = mc.gameRenderer.getMainCamera();

            RenderSystem.enableDepthTest();
            MorphRenderer.renderForm(properties.getForm(), new FormRenderingContext()
                .set(FormRenderType.MODEL_BLOCK, entity.getEntity(), matrices, lightAbove, overlay, tickDelta)
                .entityLocal(semanticWorld)
                .camera(camera));
            RenderSystem.disableDepthTest();

            matrices.popPose();
        }

        matrices.popPose();
    }

    private void renderShadowFor(ModelBlockEntity entity, PoseStack matrices, MultiBufferSource vertexConsumers, float tickDelta)
    {
        Transform transform = entity.getProperties().getTransform();
        BlockPos pos = entity.getBlockPos();

        float tx = 0.5F + transform.translate.x;
        float ty = transform.translate.y;
        float tz = 0.5F + transform.translate.z;
        double x = pos.getX() + tx;
        double y = pos.getY() + ty;
        double z = pos.getZ() + tz;

        renderShadow(vertexConsumers, matrices, tickDelta, x, y, z, tx, ty, tz);
    }

    private Transform applyLookingAnimation(Minecraft mc, ModelBlockEntity entity, ModelProperties properties, float tickDelta)
    {
        Transform transform = properties.getTransform();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 position = !mc.options.getCameraType().isFirstPerson() && mc.player != null
            ? mc.player.getEyePosition(tickDelta)
            : camera.getPosition();

        BlockPos pos = entity.getBlockPos();
        double x = pos.getX() + 0.5D + transform.translate.x;
        double y = pos.getY() + transform.translate.y;
        double z = pos.getZ() + 0.5D + transform.translate.z;

        double dx = position.x - x;
        double dz = position.z - z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        float initialYaw = lookYaw(transform);
        float yaw = (float) Math.atan2(dx, dz);
        float yawContinuous = entity.updateLookYawContinuous(yaw);
        float yawDelta = yawContinuous - initialYaw;
        float travel = Math.abs(yawDelta) % (MathUtils.PI * 2F);

        Transform finalTransform = this.lookTransform;

        finalTransform.copy(transform);
        Form form = properties.getForm();
        boolean lookAt = form instanceof MobForm;
        float headHeight = form.hitboxHeight.get() * form.hitboxEyeHeight.get() * finalTransform.scale.y;
        float constraint = 45F;
        boolean isPitching = true;

        if (form instanceof ModelForm modelForm)
        {
            ModelInstance model = ModelFormRenderer.getModel(modelForm);

            if (model != null && model.getView() != null)
            {
                String headKey = model.getView().headBone;

                lookAt = true;
                constraint = model.getView().constraint;
                isPitching = model.getView().pitch;

                if (FormUtilsClient.getBones(modelForm).contains(headKey))
                {
                    MatrixCache matrices = new MatrixCache();

                    model.captureMatrices(matrices);

                    Matrix4f matrix = matrices.get(headKey).matrix();

                    if (matrix != null)
                    {
                        headHeight = matrix.getTranslation(this.headTranslation).y * finalTransform.scale.y;
                    }
                }
            }
        }

        setLookYaw(finalTransform, yawContinuous);

        if (lookAt)
        {
            IEntity iEntity = entity.getEntity();
            double deltaHead = position.y - (y + headHeight);
            float pitch = MathUtils.clamp((float) Math.atan2(deltaHead, distance), -MathUtils.PI / 2F, MathUtils.PI / 2F);
            float headYaw = getHeadYaw(constraint, yawDelta, travel);
            float anchorYaw = yawDelta - headYaw;

            if (travel >= (float) Math.toRadians(359D))
            {
                headYaw = 0F;
                anchorYaw = 0F;

                entity.snapLookYawToBase(yaw, initialYaw);
            }

            setLookYaw(finalTransform, initialYaw + anchorYaw);
            headYaw = -MathUtils.toDeg(headYaw);
            pitch = -MathUtils.toDeg(isPitching ? pitch : 0F);

            iEntity.setHeadYaw(headYaw);
            iEntity.setPrevHeadYaw(headYaw);
            iEntity.setPitch(pitch);
            iEntity.setPrevPitch(pitch);
        }

        return finalTransform;
    }

    /**
     * The block transform's ZYX yaw channel, mode-aware: the euler channel directly, or — on a
     * quaternion transform, where the channels are stale — the quat decomposed on the branch
     * nearest those stale channels, so the yaw reads the same value the euler mode would hold
     * (a naive principal decomposition flips branches past ±90° and would read a wrong yaw).
     */
    private static float lookYaw(Transform transform)
    {
        if (transform.rotationMode == Transform.RotationMode.QUATERNION)
        {
            return Matrices.toCompatibleEulerZYXRadians(transform.quat, transform.rotate, new Vector3f()).y;
        }

        return transform.rotate.y;
    }

    /**
     * Writes the ZYX yaw channel mode-aware: the euler channel directly, or the quaternion
     * re-composed about the same compatible decomposition's X/Z tilt with the new yaw — the exact
     * quaternion equivalent of {@code rotate.y = yaw}, so look-at turns a quaternion-mode block
     * identically to a euler one.
     */
    private static void setLookYaw(Transform transform, float yaw)
    {
        if (transform.rotationMode == Transform.RotationMode.QUATERNION)
        {
            Vector3f euler = Matrices.toCompatibleEulerZYXRadians(transform.quat, transform.rotate, new Vector3f());

            transform.quat.rotationZYX(euler.z, yaw, euler.x);

            return;
        }

        transform.rotate.y = yaw;
    }

    @Override
    public int getViewDistance()
    {
        return 512;
    }

    /**
     * The vanilla mining cracks, painted over the block's hitbox box. The
     * block renders INVISIBLE, so vanilla's own crumbling pass (which redraws
     * the block model) has nothing to draw on — instead the cracks go onto the
     * body's shape here, through the same decal machinery vanilla uses: the
     * per-stage block-breaking layers on the crumbling buffers, UVs projected
     * from positions by {@link SheetedDecalTextureGenerator}.
     */
    private void renderBreakingOverlay(Minecraft mc, ModelBlockEntity entity, PoseStack matrices)
    {
        Long2ObjectMap<SortedSet<BlockDestructionProgress>> progressions = ((LevelRendererAccessor) mc.levelRenderer).bbs$getDestructionProgress();
        SortedSet<BlockDestructionProgress> infos = progressions == null ? null : progressions.get(entity.getBlockPos().asLong());

        if (infos == null || infos.isEmpty())
        {
            return;
        }

        int stage = infos.last().getProgress();

        if (stage < 0 || stage >= ModelBakery.DESTROY_TYPES.size())
        {
            return;
        }

        PoseStack.Pose entry = matrices.last();
        VertexConsumer consumer = new SheetedDecalTextureGenerator(
            mc.renderBuffers().crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(stage)),
            entry, 1F
        );

        AABB box = entity.getShape().bounds();
        int light = LevelRenderer.getLightColor(entity.getLevel(), entity.getBlockPos());
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;

        /* Vertices wind counter-clockwise seen from outside each face. */
        quad(consumer, entry, light, 0F, -1F, 0F, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
        quad(consumer, entry, light, 0F, 1F, 0F, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1);
        quad(consumer, entry, light, 0F, 0F, -1F, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
        quad(consumer, entry, light, 0F, 0F, 1F, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2);
        quad(consumer, entry, light, -1F, 0F, 0F, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1);
        quad(consumer, entry, light, 1F, 0F, 0F, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose entry, int light, float nx, float ny, float nz, float... xyz)
    {
        for (int i = 0; i < 4; i++)
        {
            consumer.addVertex(entry.pose(), xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
                .setColor(255, 255, 255, 255)
                .setUv(0F, 0F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(entry, nx, ny, nz);
        }
    }

    private boolean canRenderAxes(ModelBlockEntity entity)
    {
        if (UIScreen.getCurrentMenu() instanceof UIDashboard dashboard)
        {
            if (dashboard.getPanels().panel instanceof UIModelBlockPanel modelBlockPanel)
            {
                return !modelBlockPanel.isShowingGizmo(entity);
            }
        }

        return false;
    }

    private boolean canRender(ModelBlockEntity entity)
    {
        if (!entity.getProperties().isEnabled())
        {
            return false;
        }

        if (!BBSSettings.renderAllModelBlocks.get())
        {
            return false;
        }

        if (UIScreen.getCurrentMenu() instanceof UIDashboard dashboard)
        {
            if (dashboard.getPanels().panel instanceof UIModelBlockPanel modelBlockPanel)
            {
                return !modelBlockPanel.isEditing(entity) || modelBlockPanel.isRenderingToggled();
            }
        }

        return true;
    }
}
