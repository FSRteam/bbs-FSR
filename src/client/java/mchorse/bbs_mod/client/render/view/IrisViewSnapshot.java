package mchorse.bbs_mod.client.render.view;

import com.mojang.blaze3d.platform.GlStateManager;
import mchorse.bbs_mod.client.render.multiview.RenderStateRestorer;
import mchorse.bbs_mod.mixin.client.iris.IrisRenderingPipelineAccessor;
import mchorse.bbs_mod.utils.iris.IrisPipelineManagerAccess;
import mchorse.bbs_mod.utils.iris.IrisStateSnapshot;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Globals which Iris keeps outside its otherwise per-pipeline history. */
final class IrisViewSnapshot
{
    private final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
    private final boolean mainBound = this.pipeline instanceof IrisRenderingPipelineAccessor access && access.bbs$isMainBound();
    private final Runnable captured = ((IrisStateSnapshot) CapturedRenderingState.INSTANCE).bbs$captureState();
    private final Runnable settings = ((IrisStateSnapshot) WorldRenderingSettings.INSTANCE).bbs$captureState();
    private final boolean renderingLevel = ImmediateState.isRenderingLevel;
    private final boolean tessellation = ImmediateState.usingTessellation;
    private final boolean extended = ImmediateState.renderWithExtendedVertexFormat;
    private final boolean bypass = ImmediateState.bypass;
    private final Boolean skipExtension = ImmediateState.skipExtension.get();
    private final boolean shadows = ShadowRenderer.ACTIVE;
    private final List<BlockEntity> shadowEntities = ShadowRenderer.visibleBlockEntities == null
        ? null : new ArrayList<>(ShadowRenderer.visibleBlockEntities);
    private final int shadowDistance = ShadowRenderer.renderDistance;
    private final Matrix4f shadowView = ShadowRenderer.MODELVIEW == null ? null : new Matrix4f(ShadowRenderer.MODELVIEW);
    private final Matrix4f shadowProjection = ShadowRenderer.PROJECTION == null ? null : new Matrix4f(ShadowRenderer.PROJECTION);
    private final Frustum shadowFrustum = ShadowRenderer.FRUSTUM;
    private final int[] storageBuffers;
    private final long[] storageOffsets;
    private final long[] storageSizes;
    private final int storageBinding;
    private final int indirectBinding;
    private final int[][] imageBindings;

    IrisViewSnapshot()
    {
        boolean storage = GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_shader_storage_buffer_object;
        int limit = storage ? GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
        int requested = Iris.getCurrentPack().map(pack -> pack.getBufferObjects().isEmpty()
            ? 16 : Collections.max(pack.getBufferObjects().keySet()) + 1).orElse(0);
        int count = Math.min(limit, requested);

        this.storageBuffers = new int[count];
        this.storageOffsets = new long[count];
        this.storageSizes = new long[count];
        this.storageBinding = storage ? GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING) : 0;
        this.indirectBinding = GL.getCapabilities().OpenGL43 ? GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING) : 0;

        for (int i = 0; i < count; i++)
        {
            this.storageBuffers[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, i);
            this.storageOffsets[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, i);
            this.storageSizes[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, i);
        }

        boolean images = GL.getCapabilities().OpenGL42 || GL.getCapabilities().GL_ARB_shader_image_load_store;
        int imageCount = images ? GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS) : 0;

        this.imageBindings = new int[imageCount][6];

        for (int i = 0; i < imageCount; i++)
        {
            this.imageBindings[i][0] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, i);
            this.imageBindings[i][1] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, i);
            this.imageBindings[i][2] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, i);
            this.imageBindings[i][3] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, i);
            this.imageBindings[i][4] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, i);
            this.imageBindings[i][5] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, i);
        }
    }

    void restore()
    {
        RenderStateRestorer restorations = new RenderStateRestorer();

        for (int i = 0; i < this.imageBindings.length; i++)
        {
            int unit = i;
            int[] binding = this.imageBindings[i];
            restorations.add(() -> GL42.glBindImageTexture(unit, binding[0], binding[1], binding[2] != 0,
                binding[3], binding[4], binding[5]));
        }

        if (this.storageBuffers.length > 0)
        {
            restorations.add(() -> GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.storageBinding));
        }

        if (GL.getCapabilities().OpenGL43)
        {
            restorations.add(() -> GL15.glBindBuffer(GL43.GL_DISPATCH_INDIRECT_BUFFER, this.indirectBinding));
        }

        for (int i = 0; i < this.storageBuffers.length; i++)
        {
            int unit = i;
            restorations.add(() ->
            {
                if (this.storageBuffers[unit] != 0 && this.storageSizes[unit] > 0L)
                {
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, unit, this.storageBuffers[unit],
                        this.storageOffsets[unit], this.storageSizes[unit]);
                }
                else
                {
                    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, unit, this.storageBuffers[unit]);
                }
            });
        }

        restorations.add(this::restoreGlobals);
        restorations.add(this.settings);
        restorations.add(this.captured);
        restorations.add(() ->
        {
            ((IrisPipelineManagerAccess) Iris.getPipelineManager()).bbs$setPipeline(this.pipeline);

            if (this.pipeline != null)
            {
                this.pipeline.setIsMainBound(this.mainBound);
            }
        });
        restorations.add(ProgramSamplers::clearActiveSamplers);
        restorations.add(ProgramUniforms::clearActiveUniforms);
        restorations.close();
    }

    private void restoreGlobals()
    {
        ImmediateState.isRenderingLevel = this.renderingLevel;
        ImmediateState.usingTessellation = this.tessellation;
        ImmediateState.renderWithExtendedVertexFormat = this.extended;
        ImmediateState.bypass = this.bypass;
        ImmediateState.skipExtension.set(this.skipExtension);
        ShadowRenderer.ACTIVE = this.shadows;
        ShadowRenderer.visibleBlockEntities = this.shadowEntities;
        ShadowRenderer.renderDistance = this.shadowDistance;
        ShadowRenderer.MODELVIEW = this.shadowView;
        ShadowRenderer.PROJECTION = this.shadowProjection;
        ShadowRenderer.FRUSTUM = this.shadowFrustum;
    }
}
