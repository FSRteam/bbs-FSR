package mchorse.bbs_mod.mob;

import mchorse.bbs_mod.mixin.client.LivingEntityRendererInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;

/**
 * Vanilla's own posing of a living entity's model, without a render: the model flags the living
 * entity renderer sets before drawing, then {@code prepareMobModel} and {@code setupAnim} with the
 * numbers it would pass.
 *
 * <p>Ported from upstream 0a6aeef93 (with 566f44d89's tripled baby stride, which vanilla's own
 * renderer applies too). The mob rig bridge's offline matrices ({@link MobRigMatrices}) and the CEM
 * stage ({@code CemVanillaStage}) both pose the model this way, from one place, so the two cannot
 * drift apart.</p>
 *
 * <p>Model parts are shared with the world's real entities of that kind. The pose left here is the
 * one every render of them overwrites first thing, so it costs nobody anything — except while an
 * entity render is in flight, which is the one moment the writes would be seen: {@link #renderer}
 * answers null then. Both this bridge's context and FSR's mob form context count as in flight (the
 * qualified name is deliberate: two different classes share the simple name {@code MobRenderContext}).</p>
 */
public class VanillaPose
{
    /**
     * The renderer a living entity draws through, or null when there is nothing to pose: an entity
     * that is not living, one no living renderer takes, or an entity render in flight.
     */
    public static LivingEntityRenderer<?, ?> renderer(Entity entity)
    {
        if (MobRenderContext.current() != null || mchorse.bbs_mod.forms.renderers.MobRenderContext.isActive()
            || !(entity instanceof LivingEntity))
        {
            return null;
        }

        return Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity) instanceof LivingEntityRenderer<?, ?> renderer
            ? renderer
            : null;
    }

    /**
     * Pose the renderer's model for this frame of the entity, the way
     * {@code LivingEntityRenderer.render} does before it draws.
     */
    public static void animate(LivingEntityRenderer<?, ?> renderer, LivingEntity living, float transition)
    {
        /* The raw type is deliberate: setupAnim/prepareMobModel are generic on the entity, and on a
         * raw receiver they accept ours the way vanilla's own untyped call sites do (upstream does
         * the same against Yarn's setAngles). */
        @SuppressWarnings("rawtypes")
        EntityModel model = renderer.getModel();
        LivingEntityRendererInvoker invoker = (LivingEntityRendererInvoker) renderer;

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

        model.prepareMobModel(living, limbAngle, limbDistance, transition);
        model.setupAnim(living, limbAngle, limbDistance, animationProgress, headYaw - bodyYaw, pitch);
    }
}
