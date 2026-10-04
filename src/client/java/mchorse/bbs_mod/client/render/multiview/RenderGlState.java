package mchorse.bbs_mod.client.render.multiview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.GlStateBackup;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Snapshot of Minecraft's GL state plus bindings external renderers may change directly. */
public final class RenderGlState implements AutoCloseable
{
    private static final int TRACKED_TEXTURES = 12;

    private final GlStateBackup state = new GlStateBackup();
    private final int[] viewport = new int[4];
    private final int[] scissor = new int[4];
    private final float[] clearColor = new float[4];
    private final int[] textures = new int[TRACKED_TEXTURES];
    private final int[] shaderTextures = new int[TRACKED_TEXTURES];
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
    private final int[] drawBuffers = new int[GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS)];
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
    private final int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
    private final int unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    private final int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
    private final ShaderInstance shader = RenderSystem.getShader();
    private boolean closed;

    public RenderGlState()
    {
        RenderSystem.backupGlState(this.state);
        this.state.blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        this.state.blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        this.state.blendDestRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        this.state.blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        this.state.blendDestAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        this.state.depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.state.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        this.state.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        this.state.cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.state.scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] colorMask = new int[4];
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
        this.state.colorMaskRed = colorMask[0] != 0;
        this.state.colorMaskGreen = colorMask[1] != 0;
        this.state.colorMaskBlue = colorMask[2] != 0;
        this.state.colorMaskAlpha = colorMask[3] != 0;

        for (int index = 0; index < this.drawBuffers.length; index++)
        {
            this.drawBuffers[index] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + index);
        }

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, this.scissor);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, this.clearColor);

        try
        {
            for (int unit = 0; unit < TRACKED_TEXTURES; unit++)
            {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                this.textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                this.shaderTextures[unit] = RenderSystem.getShaderTexture(unit);
            }
        }
        finally
        {
            GL13.glActiveTexture(this.activeTexture);
        }
    }

    void prepareAllocation()
    {
        /* A caller's PBO cannot become the data source for a target allocation. */
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    void restore()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;
        RenderSystem.restoreGlState(this.state);

        /* External renderers may bypass Minecraft's cache. Restore the queried
         * state directly as well, leaving the cache and the driver in agreement. */
        setEnabled(GL11.GL_BLEND, this.state.blendEnabled);
        setEnabled(GL11.GL_DEPTH_TEST, this.state.depthEnabled);
        setEnabled(GL11.GL_CULL_FACE, this.state.cullEnabled);
        setEnabled(GL11.GL_SCISSOR_TEST, this.state.scissorEnabled);
        GL14.glBlendFuncSeparate(this.state.blendSrcRgb, this.state.blendDestRgb,
            this.state.blendSrcAlpha, this.state.blendDestAlpha);
        GL20.glBlendEquationSeparate(this.blendEquationRgb, this.blendEquationAlpha);
        GL11.glDepthMask(this.state.depthMask);
        GL11.glDepthFunc(this.state.depthFunc);
        GL11.glColorMask(this.state.colorMaskRed, this.state.colorMaskGreen, this.state.colorMaskBlue, this.state.colorMaskAlpha);
        GL11.glScissor(this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
        RenderSystem.clearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]);
        RenderSystem.clearDepth(this.clearDepth);

        for (int unit = 0; unit < TRACKED_TEXTURES; unit++)
        {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            int texture = validTexture(this.textures[unit]);
            GlStateManager._bindTexture(texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            RenderSystem.setShaderTexture(unit, validTexture(this.shaderTextures[unit]));
        }

        RenderSystem.activeTexture(this.activeTexture);
        GL13.glActiveTexture(this.activeTexture);
        BufferUploader.invalidate();
        int restoredVertexArray = this.vertexArray != 0 && GL30.glIsVertexArray(this.vertexArray) ? this.vertexArray : 0;
        GL30.glBindVertexArray(restoredVertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, validBuffer(this.arrayBuffer));

        if (restoredVertexArray != 0)
        {
            /* Element buffers belong to a VAO; the core profile has no VAO zero to mutate. */
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, validBuffer(this.elementBuffer));
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, validBuffer(this.packBuffer));
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, validBuffer(this.unpackBuffer));
        RenderSystem.setShader(() -> this.shader != null && GL20.glIsProgram(this.shader.getId()) ? this.shader : null);
        GlStateManager._glUseProgram(this.program != 0 && GL20.glIsProgram(this.program) ? this.program : 0);
        int readTarget = validFramebuffer(this.readFramebuffer);
        int drawTarget = validFramebuffer(this.drawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
        GL11.glReadBuffer(readTarget == this.readFramebuffer ? this.readBuffer : GL11.GL_BACK);

        if (drawTarget == 0)
        {
            GL11.glDrawBuffer(drawTarget == this.drawFramebuffer ? this.drawBuffers[0] : GL11.GL_BACK);
        }
        else
        {
            GL20.glDrawBuffers(this.drawBuffers);
        }

        RenderSystem.viewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
    }

    @Override
    public void close()
    {
        this.restore();
    }

    private static int validTexture(int id)
    {
        /* Teardown and resize can delete a saved binding. Binding its old name
         * would create a new texture and silently leak it. */
        return id > 0 && GL11.glIsTexture(id) ? id : 0;
    }

    private static int validBuffer(int id)
    {
        return id > 0 && GL15.glIsBuffer(id) ? id : 0;
    }

    private static int validFramebuffer(int id)
    {
        return id > 0 && GL30.glIsFramebuffer(id) ? id : 0;
    }

    private static void setEnabled(int capability, boolean enabled)
    {
        if (enabled)
        {
            GL11.glEnable(capability);
        }
        else
        {
            GL11.glDisable(capability);
        }
    }
}
