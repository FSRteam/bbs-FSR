package mchorse.bbs_mod.cubic.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import mchorse.bbs_mod.cubic.render.vao.IModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.ItemRenderer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Routes every configurable glint through vanilla render states.
 *
 * <p>Shader packs replace Minecraft's known {@link RenderType} shaders. A standalone BBS
 * core shader bypasses that replacement and is commonly omitted from the pack's gbuffer,
 * so even a valid OpenGL draw can disappear. The two configurable effects use vanilla's
 * energy-swirl entity pipeline, while the vanilla mode uses the exact armor glint render
 * type. This keeps all three visible to Iris and other shader-pack implementations.</p>
 */
public class GlintRenderState
{
    private static final float TIME_WRAP_SECONDS = 100000F;

    public static void renderVao(int mode, float speed, Color color, Transform transform,
        IModelVAO vao, Matrix4f modelView, Matrix3f normalMat, int light, int overlay)
    {
        if (mode == Form.GLINT_OFF || vao == null)
        {
            return;
        }

        RenderType renderType = getRenderType(mode, speed);
        Matrix4f glintTransform = transform.createMatrix();
        Matrix4f transformedModelView = new Matrix4f(modelView).mul(glintTransform);
        Matrix3f transformedNormal = transformNormal(normalMat, glintTransform);

        renderType.setupRenderState();

        try
        {
            setupOverlayDepth();
            ShaderInstance shader = RenderSystem.getShader();

            if (mode == Form.GLINT_VANILLA)
            {
                /* The vanilla glint format has no vertex color. ColorModulator is the
                 * canonical tint/opacity input and shader packs mirror that uniform. */
                RenderSystem.setShaderColor(color.r, color.g, color.b, color.a);
            }

            ModelVAORenderer.render(shader, vao, transformedModelView, transformedNormal,
                color.r, color.g, color.b, color.a, light, overlay);
        }
        finally
        {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            renderType.clearRenderState();
            restoreDefaultDepth();
        }
    }

    public static void drawMesh(int mode, float speed, Color color, MeshData mesh)
    {
        if (mesh == null || mode == Form.GLINT_OFF)
        {
            return;
        }

        RenderType renderType = getRenderType(mode, speed);

        renderType.setupRenderState();

        try
        {
            setupOverlayDepth();

            if (mode == Form.GLINT_VANILLA)
            {
                RenderSystem.setShaderColor(color.r, color.g, color.b, color.a);
            }

            BufferUploader.drawWithShader(mesh);
        }
        finally
        {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            renderType.clearRenderState();
            restoreDefaultDepth();
        }
    }

    private static RenderType getRenderType(int mode, float speed)
    {
        if (mode == Form.GLINT_VANILLA)
        {
            return RenderType.armorEntityGlint();
        }

        /* Energy swirl is a normal vanilla entity render type, so shader packs compile and
         * route it like charged-creeper/wither armor. Reusing the vanilla glint texture
         * keeps the effect recognizably enchanted while retaining custom tint and speed. */
        float seconds = (Util.getMillis() % (long) (TIME_WRAP_SECONDS * 1000F)) / 1000F;
        float direction = mode == Form.GLINT_EDGE ? -1F : 1F;
        float u = seconds * speed * 0.01F * direction;
        float v = seconds * speed * 0.006F;

        return RenderType.energySwirl(ItemRenderer.ENCHANTED_GLINT_ENTITY, u % 1F, v % 1F);
    }

    /**
     * Glint is a decorative second pass and must not become the depth owner. Vanilla armor
     * glint normally uses an exact-depth test because it reuses identical armor geometry;
     * configurable glint can be translated, scaled or rotated independently, so an exact
     * test would discard the transformed effect. The shader, texture, blend and texturing
     * state still come from the vanilla render type for shader-pack compatibility.
     */
    private static void setupOverlayDepth()
    {
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
    }

    private static void restoreDefaultDepth()
    {
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
    }

    private static Matrix3f transformNormal(Matrix3f normalMat, Matrix4f transform)
    {
        Matrix3f local = new Matrix3f(transform);

        if (Math.abs(local.determinant()) <= 0.00001F)
        {
            return new Matrix3f(normalMat);
        }

        return new Matrix3f(normalMat).mul(local.invert().transpose());
    }
}
