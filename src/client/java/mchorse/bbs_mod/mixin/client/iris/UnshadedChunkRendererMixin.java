package mchorse.bbs_mod.mixin.client.iris;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.client.render.view.UnshadedTerrainProgram;
import mchorse.bbs_mod.client.render.view.UnshadedViewPipeline;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.slf4j.Logger;
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
    private static final Logger BBS_LOGGER = LogUtils.getLogger();

    @Unique
    private final Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> bbs$unshadedPrograms = new HashMap<>();

    /* Set after the first program failure so per-frame retries cannot spam the log. */
    @Unique
    private boolean bbs$unshadedUnsupported;

    @Inject(method = "compileProgram", at = @At("HEAD"), cancellable = true)
    private void bbs$unshadedProgram(ChunkShaderOptions options, CallbackInfoReturnable<GlProgram<ChunkShaderInterface>> callback)
    {
        if (this.bbs$unshadedUnsupported || !(Iris.getPipelineManager().getPipelineNullable() instanceof UnshadedViewPipeline)
            || !WorldRenderingSettings.INSTANCE.shouldUseSeparateAo())
        {
            return;
        }

        try
        {
            callback.setReturnValue(this.bbs$unshadedPrograms.computeIfAbsent(options, UnshadedTerrainProgram::create));
        }
        catch (RuntimeException | LinkageError failure)
        {
            /* Unknown Sodium builds must degrade to their own chunk program, not crash. */
            this.bbs$unshadedUnsupported = true;
            BBS_LOGGER.error("[FilmView] Unshaded terrain program is unsupported by this Sodium build; using its normal chunk program", failure);
        }
    }

    @Inject(method = "delete", at = @At("TAIL"))
    private void bbs$deletePrograms(CallbackInfo callback)
    {
        this.bbs$unshadedPrograms.values().forEach(GlProgram::delete);
        this.bbs$unshadedPrograms.clear();
    }
}
