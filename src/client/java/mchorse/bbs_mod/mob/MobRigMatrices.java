package mchorse.bbs_mod.mob;

import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.mixin.client.LivingEntityRendererInvoker;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.Transform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where a mob form's bones are, in the form's own space.
 *
 * <p>Ported from upstream e341fa733. The one place the space convention lives. Vanilla walks from
 * the form's stack through the living entity renderer's setup (which yaws by {@code 180 - bodyYaw},
 * and the form pins body yaw to zero) and then {@code scale(-1, -1, 1)}, so the model's frame ends
 * up turned 180 degrees about X relative to the form: Y down, Z backwards. The {@code scale(-1, -1, 1)}
 * written here at the end of {@link #put} is exactly the matrix that turns it back — its determinant
 * is +1, a rotation rather than a mirror, so nothing attached to the bone gets flipped.</p>
 */
public class MobRigMatrices
{
    /**
     * Records one part, given the frame vanilla was on BEFORE the part posed itself (the parent's
     * frame, i.e. the stack at the head of {@code ModelPart.translateAndRotate}). The part's own
     * pivot, rotation and scale are re-applied here instead of being read back out of a captured
     * matrix, which keeps both flavours the cache wants — the frame at the pivot before the bone
     * rotates, and the full one — exact and inverse-free.
     */
    public static void put(MatrixCache cache, Matrix4f baseInverse, String bone, ModelPart part, Matrix4f parent)
    {
        Matrix4f origin = new Matrix4f(baseInverse).mul(parent);

        origin.translate(part.x / 16F, part.y / 16F, part.z / 16F);

        Matrix4f matrix = new Matrix4f(origin);

        if (part.xRot != 0F || part.yRot != 0F || part.zRot != 0F)
        {
            matrix.rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
        }

        if (part.xScale != 1F || part.yScale != 1F || part.zScale != 1F)
        {
            matrix.scale(part.xScale, part.yScale, part.zScale);
        }

        matrix.scale(-1F, -1F, 1F);
        origin.scale(-1F, -1F, 1F);

        cache.put(bone, matrix, origin);
    }

    /**
     * Where every bone sits WITHOUT rendering anything.
     *
     * <p>The gizmo, the anchor system, trackers and the motion path all ask a form for its bone
     * matrices several times a frame, at arbitrary ticks, and treat the call as a question rather
     * than a draw. So this replays vanilla's own preamble — the model flags, then
     * {@code prepareMobModel}/{@code setupAnim}, then the transform chain the living entity
     * renderer sets up — and walks the part tree for matrices, instead of running a second
     * {@code EntityRenderDispatcher.render} that would re-enter item and armor rendering and touch
     * GL state from inside what the callers think is a read.</p>
     *
     * <p>Model parts are shared with the world's real entities, so everything written here is put
     * back in a finally. It also refuses to run while an entity render is in flight, which is the
     * one moment those writes would be seen by someone else — both this bridge's own context and
     * FSR's mob form context count as in flight (the qualified name is deliberate: two different
     * classes share the simple name {@code MobRenderContext}).</p>
     */
    public static void evaluate(Entity entity, MobRig rig, Pose pose, Pose poseOverlay, float transition, MatrixCache cache)
    {
        if (rig == null || rig.isEmpty() || MobRenderContext.current() != null
            || mchorse.bbs_mod.forms.renderers.MobRenderContext.isActive()
            || !(entity instanceof LivingEntity living)
            || !(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity) instanceof LivingEntityRenderer<?, ?> renderer))
        {
            return;
        }

        EntityModel model = renderer.getModel();
        LivingEntityRendererInvoker invoker = (LivingEntityRendererInvoker) renderer;
        Map<ModelPart, Transform> saved = new IdentityHashMap<>();

        model.attackTime = invoker.bbs$getAttackAnim(living, transition);
        model.riding = living.isPassenger();
        model.young = living.isBaby();

        float bodyYaw = Mth.rotLerp(transition, living.yBodyRotO, living.yBodyRot);
        float headYaw = Mth.rotLerp(transition, living.yHeadRotO, living.yHeadRot);
        float pitch = Mth.lerp(transition, living.xRotO, living.getXRot());
        float animationProgress = invoker.bbs$getBob(living, transition);
        float limbDistance = 0F;
        float limbAngle = 0F;

        if (!living.isPassenger() && living.isAlive())
        {
            limbDistance = Math.min(living.walkAnimation.speed(transition), 1F);
            limbAngle = living.walkAnimation.position(transition) * (living.isBaby() ? 3F : 1F);
        }

        try
        {
            model.prepareMobModel(living, limbAngle, limbDistance, transition);
            model.setupAnim(living, limbAngle, limbDistance, animationProgress, headYaw - bodyYaw, pitch);

            MobPoseApplier.apply(rig, MobPoseApplier.merge(pose, poseOverlay), saved);

            PoseStack stack = new PoseStack();

            /* FSR runs NeoForge 1.21.1, whose living entity renderer scales the stack by the
             * entity's own scale and folds it into setupRotations as a sixth argument; upstream's
             * Fabric 1.21.1 preamble has neither, so this differs from upstream here on purpose —
             * the replayed preamble must match the renderer FSR actually ships with. */
            float scale = living.getScale();

            stack.scale(scale, scale, scale);
            invoker.bbs$setupRotations(living, stack, animationProgress, bodyYaw, transition, scale);
            stack.scale(-1F, -1F, 1F);
            invoker.bbs$scale(living, stack, transition);
            stack.translate(0F, -1.501F, 0F);

            Matrix4f baseInverse = new Matrix4f();

            for (String root : rig.getRootGroupKeys())
            {
                walk(cache, baseInverse, rig, stack, rig.part(root));
            }
        }
        finally
        {
            MobPoseApplier.restore(saved);
        }
    }

    /**
     * The same descent {@code ModelPart.render} makes, minus the drawing — including its two
     * early-outs, so a bone that would not be drawn does not get a matrix here either and the two
     * paths agree on which bones exist.
     */
    private static void walk(MatrixCache cache, Matrix4f baseInverse, MobRig rig, PoseStack stack, ModelPart part)
    {
        if (part == null || !part.visible)
        {
            return;
        }

        Map<String, ModelPart> children = IBBSModelPart.of(part).bbs$children();

        if (part.isEmpty() && children.isEmpty())
        {
            return;
        }

        String bone = rig.name(part);

        if (bone != null)
        {
            put(cache, baseInverse, bone, part, stack.last().pose());
        }

        stack.pushPose();
        part.translateAndRotate(stack);

        for (ModelPart child : children.values())
        {
            walk(cache, baseInverse, rig, stack, child);
        }

        stack.popPose();
    }
}
