package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.forms.renderers.MobRenderContext;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.FastColor;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Applies MobForm pose and appearance state to vanilla model parts. The lower priority makes the
 * compatibility wrapper run before Iris' cancellable fast-render callback.
 */
@Mixin(value = ModelPart.class, priority = 500)
public abstract class ModelPartMixin
{
    @Inject(
        method = "translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void bbs$applyMobPose(PoseStack matrices, CallbackInfo info)
    {
        ModelPart part = (ModelPart) (Object) this;
        PoseTransform transform = MobRenderContext.getTransform(part);

        if (!MobRenderContext.isTracked(part))
        {
            return;
        }

        float pivotX = part.x;
        float pivotY = part.y;
        float pivotZ = part.z;
        float pitch = part.xRot;
        float yaw = part.yRot;
        float roll = part.zRot;
        float scaleX = part.xScale;
        float scaleY = part.yScale;
        float scaleZ = part.zScale;
        Quaternionf quaternion = null;

        if (transform != null && transform.fix > 0F)
        {
            PartPose initial = part.getInitialPose();

            pivotX = Lerps.lerp(pivotX, initial.x, transform.fix);
            pivotY = Lerps.lerp(pivotY, initial.y, transform.fix);
            pivotZ = Lerps.lerp(pivotZ, initial.z, transform.fix);
            pitch = Lerps.lerp(pitch, initial.xRot, transform.fix);
            yaw = Lerps.lerp(yaw, initial.yRot, transform.fix);
            roll = Lerps.lerp(roll, initial.zRot, transform.fix);
            scaleX = Lerps.lerp(scaleX, 1F, transform.fix);
            scaleY = Lerps.lerp(scaleY, 1F, transform.fix);
            scaleZ = Lerps.lerp(scaleZ, 1F, transform.fix);
        }

        MobRenderContext.captureRotationOffset(part, pitch, yaw, roll);

        if (transform != null)
        {
            pivotX += transform.translate.x;
            pivotY += transform.translate.y;
            pivotZ += transform.translate.z;

            if (transform.rotationMode == Transform.RotationMode.QUATERNION)
            {
                quaternion = new Quaternionf().rotationZYX(roll, yaw, pitch).mul(transform.quat);
            }
            else
            {
                pitch += transform.rotate.x;
                yaw += transform.rotate.y;
                roll += transform.rotate.z;
            }

            scaleX += transform.scale.x - 1F;
            scaleY += transform.scale.y - 1F;
            scaleZ += transform.scale.z - 1F;
        }

        matrices.translate(pivotX / 16F, pivotY / 16F, pivotZ / 16F);
        MobRenderContext.captureOrigin(part, matrices.last().pose());

        if (quaternion != null)
        {
            matrices.mulPose(quaternion);
        }
        else if (pitch != 0F || yaw != 0F || roll != 0F)
        {
            matrices.mulPose(new Quaternionf().rotationZYX(roll, yaw, pitch));
        }

        if (scaleX != 1F || scaleY != 1F || scaleZ != 1F)
        {
            matrices.scale(scaleX, scaleY, scaleZ);
        }

        MobRenderContext.captureMatrix(part, matrices.last().pose());

        info.cancel();
    }

    @ModifyVariable(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private VertexConsumer bbs$disableFastRendering(VertexConsumer vertices)
    {
        if (!MobRenderContext.isActive() || vertices instanceof DirectVertexConsumer)
        {
            return vertices;
        }

        return new DirectVertexConsumer(vertices);
    }

    @ModifyArgs(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/geom/ModelPart;compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
        )
    )
    private void bbs$applyMobAppearance(Args args)
    {
        ModelPart part = (ModelPart) (Object) this;
        PoseTransform transform = MobRenderContext.getTransform(part);
        Color color = MobRenderContext.getColor(part);
        int pickingOffset = MobRenderContext.getPickingOffset(part);

        if (pickingOffset >= 0)
        {
            args.set(2, pickingOffset);
        }

        if (transform == null && color == null)
        {
            return;
        }

        if (pickingOffset < 0 && transform != null)
        {
            int light = args.get(2);
            int u = light & 0xffff;
            int v = light >> 16 & 0xffff;

            u = (int) Lerps.lerp(u, LightTexture.FULL_BLOCK, MathUtils.clamp(transform.lighting, 0F, 1F));

            args.set(2, u | v << 16);
        }

        float redMultiplier = color == null ? 1F : color.r;
        float greenMultiplier = color == null ? 1F : color.g;
        float blueMultiplier = color == null ? 1F : color.b;
        float alphaMultiplier = color == null ? 1F : color.a;

        if (transform != null)
        {
            redMultiplier *= transform.color.r;
            greenMultiplier *= transform.color.g;
            blueMultiplier *= transform.color.b;
            alphaMultiplier *= transform.color.a;
        }

        int packed = args.get(4);
        int alpha = MathUtils.clamp((int) (FastColor.ARGB32.alpha(packed) * alphaMultiplier), 0, 255);
        int red = MathUtils.clamp((int) (FastColor.ARGB32.red(packed) * redMultiplier), 0, 255);
        int green = MathUtils.clamp((int) (FastColor.ARGB32.green(packed) * greenMultiplier), 0, 255);
        int blue = MathUtils.clamp((int) (FastColor.ARGB32.blue(packed) * blueMultiplier), 0, 255);

        args.set(4, FastColor.ARGB32.color(alpha, red, green, blue));
    }

    private static final class DirectVertexConsumer implements VertexConsumer
    {
        private final VertexConsumer consumer;

        private DirectVertexConsumer(VertexConsumer consumer)
        {
            this.consumer = consumer;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z)
        {
            this.consumer.addVertex(x, y, z);

            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha)
        {
            this.consumer.setColor(red, green, blue, alpha);

            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v)
        {
            this.consumer.setUv(u, v);

            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v)
        {
            this.consumer.setUv1(u, v);

            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v)
        {
            this.consumer.setUv2(u, v);

            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z)
        {
            this.consumer.setNormal(x, y, z);

            return this;
        }
    }
}
