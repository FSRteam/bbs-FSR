package mchorse.bbs_mod.cubic.render.vao;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import mchorse.bbs_mod.graphics.InverseView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL30;

import java.util.List;

public class ModelVAORenderer
{
    /** Per-VAO state used by a texture batch. The texture is owned by the caller. */
    public static record BatchDraw(IModelVAO vao, Matrix4f modelView, Matrix3f normalMat,
        float r, float g, float b, float a, int light, int overlay)
    {}

    public static Matrix4f captureModelView(PoseStack stack)
    {
        return new Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose());
    }

    public static void render(ShaderInstance shader, IModelVAO modelVAO, PoseStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        render(shader, modelVAO, captureModelView(stack), stack.last().normal(), r, g, b, a, light, overlay);
    }

    public static void render(ShaderInstance shader, IModelVAO modelVAO, Matrix4f modelView, Matrix3f normalMat, float r, float g, float b, float a, int light, int overlay)
    {
        if (shader == null || modelVAO == null)
        {
            return;
        }

        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentElementArrayBuffer = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        setupUniforms(shader, modelView, normalMat);

        shader.apply();
        modelVAO.render(shader.getVertexFormat(), r, g, b, a, light, overlay);
        shader.clear();

        GL30.glBindVertexArray(currentVAO);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, currentElementArrayBuffer);
    }

    /**
     * Render several VAOs while keeping the shader program bound for the whole texture batch.
     * Model-view and normal uniforms are uploaded between draws, so group transforms remain
     * independent without paying a full shader apply/clear and GL state query per VAO.
     */
    public static void renderBatch(ShaderInstance shader, List<BatchDraw> draws)
    {
        if (shader == null || draws == null || draws.isEmpty())
        {
            return;
        }

        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentElementArrayBuffer = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        BatchDraw first = draws.get(0);
        setupUniforms(shader, first.modelView(), first.normalMat());
        shader.apply();

        try
        {
            for (int i = 0; i < draws.size(); i++)
            {
                BatchDraw draw = draws.get(i);

                if (i > 0)
                {
                    uploadDrawUniforms(shader, draw.modelView(), draw.normalMat());
                }

                draw.vao().render(shader.getVertexFormat(), draw.r(), draw.g(), draw.b(), draw.a(), draw.light(), draw.overlay());
            }
        }
        finally
        {
            shader.clear();
            GL30.glBindVertexArray(currentVAO);
            GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, currentElementArrayBuffer);
        }
    }

    public static void uploadDrawUniforms(ShaderInstance shader, Matrix4f modelView, Matrix3f normalMat)
    {
        Uniform modelViewUniform = shader.getUniform("ModelViewMat");

        if (modelViewUniform != null)
        {
            modelViewUniform.set(modelView);
            modelViewUniform.upload();
        }

        Uniform normalUniform = shader.getUniform("NormalMat");

        if (normalUniform != null)
        {
            normalUniform.set(normalMat);
            normalUniform.upload();
        }
    }

    public static void setupUniforms(PoseStack stack, ShaderInstance shader)
    {
        setupUniforms(shader, captureModelView(stack), stack.last().normal());
    }

    public static void setupUniforms(ShaderInstance shader, Matrix4f modelView, Matrix3f normalMat)
    {
        for (int i = 0; i < 12; i++)
        {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }

        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());

        /* The model colour is carried by the NEW_ENTITY vertex attribute. Keep the
         * shader's global ColorModulator neutral, matching FS 1.21.1. Reading the
         * process-wide RenderSystem shader colour here makes deferred Color Pose
         * draws inherit a later UI/glint tint and turn the whole model black. */
        Uniform colorModulator = shader.getUniform("ColorModulator");

        if (colorModulator != null)
        {
            colorModulator.set(1F, 1F, 1F, 1F);
        }

        /* NormalMat is present by default in Iris' shaders, but when there is no Iris,
         * the BBS mod's model.json shader is being used instead that provides NormalMat
         * uniform.
         */
        Uniform normalUniform = shader.getUniform("NormalMat");

        if (normalUniform != null)
        {
            normalUniform.set(normalMat);
        }

        Uniform viewRotationUniform = shader.getUniform("ViewRotationMat");

        if (viewRotationUniform != null)
        {
            viewRotationUniform.set(InverseView.get());
        }

        RenderSystem.setupShaderLights(shader);
    }
}
