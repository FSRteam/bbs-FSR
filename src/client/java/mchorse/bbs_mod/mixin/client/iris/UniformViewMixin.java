package mchorse.bbs_mod.mixin.client.iris;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.minecraft.client.renderer.ShaderInstance;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Uniform.class)
public abstract class UniformViewMixin
{
    @Shadow
    @Final
    private Shader parent;

    @Unique
    private boolean bbs$viewOwned;

    @Unique
    private boolean bbs$closed;

    @Inject(method = "<init>(Ljava/lang/String;IILcom/mojang/blaze3d/shaders/Shader;)V", at = @At(value = "FIELD",
        target = "Lcom/mojang/blaze3d/shaders/Uniform;floatValues:Ljava/nio/FloatBuffer;",
        opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void bbs$ownUniform(CallbackInfo callback)
    {
        /* Parentless uniforms include Iris' global FAKE_UNIFORM and must remain shared. */
        this.bbs$viewOwned = this.parent instanceof ShaderInstance && ViewResourceOwner.isCapturing();

        if (this.bbs$viewOwned)
        {
            Uniform uniform = (Uniform) (Object) this;

            ViewResourceOwner.track(uniform, uniform::close);
        }
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void bbs$skipClosedUniform(CallbackInfo callback)
    {
        if (this.bbs$viewOwned && this.bbs$closed)
        {
            callback.cancel();
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void bbs$releasedUniform(CallbackInfo callback)
    {
        this.bbs$closed = true;
        ViewResourceOwner.released(this);
    }
}
