package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.irisshaders.iris.targets.RenderTarget;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderTarget.class, remap = false)
public abstract class IrisRenderTargetViewMixin
{
    @Inject(method = "<init>(Lnet/irisshaders/iris/targets/RenderTarget$Builder;)V", at = @At(value = "FIELD",
        target = "Lnet/irisshaders/iris/targets/RenderTarget;altTexture:I", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void bbs$ownTargets(CallbackInfo callback)
    {
        RenderTarget target = (RenderTarget) (Object) this;

        ViewResourceOwner.track(target, target::destroy);
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void bbs$releasedTargets(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
