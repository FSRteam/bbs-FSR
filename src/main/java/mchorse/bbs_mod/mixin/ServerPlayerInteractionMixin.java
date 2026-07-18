package mchorse.bbs_mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the result of the exact entity-interaction branch selected by the
 * vanilla packet dispatcher. The target is the anonymous handler inside
 * ServerGamePacketListenerImpl#handleInteract in Minecraft 1.21.1.
 */
@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl$1")
public class ServerPlayerInteractionMixin
{
    @Unique
    private boolean bbs$interactAt;

    @Unique
    private Vec3 bbs$interactionLocation = Vec3.ZERO;

    @Inject(method = "onInteraction(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"))
    private void bbs$prepareInteraction(InteractionHand hand, CallbackInfo info)
    {
        this.bbs$interactAt = false;
        this.bbs$interactionLocation = Vec3.ZERO;
    }

    @Inject(method = "onInteraction(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
    private void bbs$prepareInteractionAt(InteractionHand hand, Vec3 location, CallbackInfo info)
    {
        this.bbs$interactAt = true;
        this.bbs$interactionLocation = location;
    }

    @WrapOperation(
        method = "performInteraction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl$EntityInteraction;run(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"
        )
    )
    private InteractionResult bbs$recordInteractionResult(@Coerce Object interaction, ServerPlayer player, Entity entity, InteractionHand hand, Operation<InteractionResult> original)
    {
        ItemStack snapshot = player.getItemInHand(hand).copy();
        boolean secondaryUse = player.isSecondaryUseActive();
        InteractionResult result = original.call(interaction, player, entity, hand);

        boolean validLocation = !this.bbs$interactAt
            || InteractionActionSemantics.isValidEntityHit(entity, this.bbs$interactionLocation);

        if (InteractionActionSemantics.shouldRecordPlayerInteraction(result, player.isSpectator()) && validLocation)
        {
            BBSMod.getActions().addAction(player, () ->
            {
                EntityInteractionActionClip clip = new EntityInteractionActionClip();

                clip.target.capture(entity);
                clip.hand.set(hand == InteractionHand.MAIN_HAND);
                clip.itemStack.set(snapshot);
                clip.secondaryUse.set(secondaryUse);
                clip.interactAt.set(this.bbs$interactAt);
                clip.location.get().set(this.bbs$interactionLocation.x, this.bbs$interactionLocation.y, this.bbs$interactionLocation.z);

                return clip;
            });
        }

        return result;
    }
}
