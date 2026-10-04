package mchorse.bbs_mod.mixin.client.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderStorageBuffer.class, remap = false)
public abstract class ShaderStorageBufferViewMixin
{
    @Shadow
    protected int id;

    @Shadow
    protected abstract void destroy();

    @Inject(method = "<init>(ILnet/irisshaders/iris/gl/buffer/BuiltShaderStorageInfo;)V", at = @At(value = "FIELD",
        target = "Lnet/irisshaders/iris/gl/buffer/ShaderStorageBuffer;id:I", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void bbs$ownBuffer(CallbackInfo callback)
    {
        /* Native content allocation and buffer setup can fail before the constructor returns. */
        ViewResourceOwner.track(this, () ->
        {
            ShaderStorageBufferHolderAccessor.bbs$getActiveBuffers().remove((ShaderStorageBuffer) (Object) this);
            this.destroy();
        });
    }

    @Inject(method = "resizeIfRelative", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;deleteBuffers(I)V", shift = At.Shift.AFTER))
    private void bbs$clearDeletedBuffer(int width, int height, CallbackInfo callback)
    {
        this.id = 0;
    }

    @ModifyExpressionValue(method = "resizeIfRelative", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"))
    private int bbs$ownReplacementBuffer(int replacement)
    {
        /* Iris normally stores this only after storage allocation and clearing succeed. */
        this.id = replacement;

        return replacement;
    }

    @Inject(method = "destroy", at = @At("RETURN"))
    private void bbs$releasedBuffer(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
