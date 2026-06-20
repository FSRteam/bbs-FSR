package mchorse.bbs_mod.cubic.render.vao;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

public class ModelVAORenderer
{
    private static final Matrix4f modelView = new Matrix4f();

    public static void render(ShaderInstance shader, IModelVAO modelVAO, PoseStack stack, float r, float g, float b, float a, int light, int overlay)
    {
        if (shader == null || modelVAO == null)
        {
            return;
        }

        int currentVAO = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentElementArrayBuffer = GL30.glGetInteger(GL30.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        setupUniforms(stack, shader);

        shader.apply();
        modelVAO.render(shader.getVertexFormat(), r, g, b, a, light, overlay);
        shader.clear();

        GL30.glBindVertexArray(currentVAO);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, currentElementArrayBuffer);
    }

    public static void setupUniforms(PoseStack stack, ShaderInstance shader)
    {
        for (int i = 0; i < 12; i++)
        {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }

        Matrix4f modelView = ModelVAORenderer.modelView.set(RenderSystem.getModelViewMatrix()).mul(stack.last().pose());
        shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());

        /* NormalMat is present by default in Iris' shaders, but when there is no Iris,
         * the BBS mod's model.json shader is being used instead that provides NormalMat
         * uniform.
         */
        Uniform normalUniform = shader.getUniform("NormalMat");

        if (normalUniform != null)
        {
            normalUniform.set(stack.last().normal());
        }

        RenderSystem.setupShaderLights(shader);
    }
}
