package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.utils.iris.ShaderCurves;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

@Mixin(ProgramSet.class)
public class ProgramSetMixin
{
    @Redirect(method = "readProgramSource(" +
        "Lnet/irisshaders/iris/shaderpack/include/AbsolutePackPath;" +
        "Ljava/util/function/Function;" +
        "Ljava/lang/String;" +
        "Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;" +
        "Lnet/irisshaders/iris/shaderpack/properties/ShaderProperties;" +
        "Lnet/irisshaders/iris/gl/blending/BlendModeOverride;" +
        "Z)" +
        "Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;",
        at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private static Object onSourceProvider(Function function, Object path)
    {
        Object apply = function.apply(path);

        if (apply instanceof String string && path instanceof AbsolutePackPath packPath)
        {
            apply = ShaderCurves.processShader(packPath, string);
        }

        return apply;
    }
}