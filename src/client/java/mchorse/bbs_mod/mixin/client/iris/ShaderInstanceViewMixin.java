package mchorse.bbs_mod.mixin.client.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import net.minecraft.client.renderer.ShaderInstance;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceViewMixin
{
    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
        at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/renderer/ShaderInstance;programId:I", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void bbs$ownShader(CallbackInfo callback)
    {
        ShaderInstance shader = (ShaderInstance) (Object) this;

        /* Both stages and uniform lists now exist, so close is safe even if linking fails. */
        ViewResourceOwner.track(shader, shader::close);
    }

    @WrapOperation(method = "getOrCreate", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object bbs$useViewStage(Map<?, ?> programs, Object name, Operation<Object> original)
    {
        /* A view must not acquire stages whose close would invalidate another pipeline's cache. */
        return ViewResourceOwner.isCapturing() ? null : original.call(programs, name);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void bbs$releasedShader(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
