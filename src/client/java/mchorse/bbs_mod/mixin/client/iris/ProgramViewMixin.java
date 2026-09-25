package mchorse.bbs_mod.mixin.client.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Program;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(Program.class)
public abstract class ProgramViewMixin
{
    @Unique
    private static final Map<Integer, Object> bbs$pendingShaders = new HashMap<>();

    @Unique
    private boolean bbs$viewOwned;

    @Shadow
    private int id;

    @ModifyExpressionValue(method = "compileShaderInternal", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/GlStateManager;glCreateShader(I)I"))
    private static int bbs$ownUncompiledShader(int shaderId)
    {
        if (shaderId > 0 && ViewResourceOwner.isCapturing())
        {
            Object allocation = new Object();

            bbs$pendingShaders.put(shaderId, allocation);
            ViewResourceOwner.track(allocation, () ->
            {
                bbs$pendingShaders.remove(shaderId, allocation);
                GlStateManager.glDeleteShader(shaderId);
            });
        }

        return shaderId;
    }

    @Inject(method = "<init>(Lcom/mojang/blaze3d/shaders/Program$Type;ILjava/lang/String;)V", at = @At("RETURN"))
    private void bbs$ownStage(CallbackInfo callback)
    {
        Object allocation = bbs$pendingShaders.remove(this.id);

        if (allocation != null)
        {
            ViewResourceOwner.released(allocation);
        }

        Program program = (Program) (Object) this;

        /* EffectProgram has its own shared cache and reference count. */
        this.bbs$viewOwned = program.getClass() == Program.class && ViewResourceOwner.isCapturing();

        if (this.bbs$viewOwned)
        {
            ViewResourceOwner.track(program, program::close);
        }
    }

    @WrapOperation(method = "compileShader", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object bbs$keepStageLocal(Map<?, ?> programs, Object name, Object program, Operation<Object> original)
    {
        return ViewResourceOwner.isCapturing() ? null : original.call(programs, name, program);
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object bbs$preserveSharedStages(Map<?, ?> programs, Object name, Operation<Object> original)
    {
        return this.bbs$viewOwned ? null : original.call(programs, name);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void bbs$releasedStage(CallbackInfo callback)
    {
        ViewResourceOwner.released(this);
    }
}
