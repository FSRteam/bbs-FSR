package mchorse.bbs_mod.mixin.client.iris;

import mchorse.bbs_mod.client.render.view.UnshadedTerrainProgram;
import mchorse.bbs_mod.client.render.view.UnshadedViewPipeline;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class UnshadedChunkRendererMixin
{
    @Unique
    private final Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> bbs$unshadedPrograms = new HashMap<>();

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void bbs$unshadedProgram(ChunkShaderOptions options, CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> callback)
    {
        if (Iris.getPipelineManager().getPipelineNullable() instanceof UnshadedViewPipeline
            && WorldRenderingSettings.INSTANCE.shouldUseSeparateAo())
        {
            callback.setReturnValue(this.bbs$unshadedPrograms.computeIfAbsent(options, UnshadedTerrainProgram::create));
        }
    }

    @Inject(method = "delete", at = @At("TAIL"))
    private void bbs$deletePrograms(CallbackInfo callback)
    {
        this.bbs$unshadedPrograms.values().forEach(GlProgram::delete);
        this.bbs$unshadedPrograms.clear();
    }
}
