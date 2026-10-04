package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker
{
    @Invoker("getWhiteOverlayProgress")
    public float bbs$getWhiteOverlayProgress(LivingEntity entity, float tickDelta);

    /**
     * The rest of the living entity renderer's preamble the mob rig bridge replays offline
     * (upstream e341fa733's getHandSwingProgress/getAnimationCounter invokers, in FSR's Mojmap
     * names): {@code getAttackAnim} is the hand swing the renderer copies into
     * {@code model.attackTime}, {@code getBob} the age counter that drives idle sway,
     * {@code setupRotations} the body transform chain (FSR's NeoForge signature carries the
     * entity's scale as a sixth argument), {@code scale} the per-renderer scale hook that runs
     * between it and the model offset.
     */
    @Invoker("getAttackAnim")
    public float bbs$getAttackAnim(LivingEntity entity, float tickDelta);

    @Invoker("getBob")
    public float bbs$getBob(LivingEntity entity, float tickDelta);

    @Invoker("setupRotations")
    public void bbs$setupRotations(LivingEntity entity, PoseStack matrices, float animationProgress, float bodyYaw, float tickDelta, float scale);

    @Invoker("scale")
    public void bbs$scale(LivingEntity entity, PoseStack matrices, float tickDelta);
}
