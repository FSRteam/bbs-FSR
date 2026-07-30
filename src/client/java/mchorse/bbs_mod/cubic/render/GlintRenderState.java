package mchorse.bbs_mod.cubic.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import mchorse.bbs_mod.client.BBSRendering;
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
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/**
 * Routes every configurable glint through vanilla render states.
 *
 * <p>Shader packs replace Minecraft's known {@link RenderType} shaders. A standalone BBS
 * core shader bypasses that replacement and is commonly omitted from the pack's gbuffer,
 * so even a valid OpenGL draw can disappear. The configurable effects retain vanilla's
 * energy-swirl entity pipeline normally. While Iris is rendering a shader-pack world, all
 * modes use the armor-glint program that packs explicitly provide. That path keeps the
 * exact vanilla texture matrix because shader packs compile their armor-glint program
 * against vanilla's texture-coordinate contract.</p>
 */
public class GlintRenderState
{
    private static final float TIME_WRAP_SECONDS = 100000F;
    private static final int SHADER_TEXTURE_COUNT = 12;

    public static void renderVao(int mode, float speed, Color color, Transform transform,
        IModelVAO vao, Matrix4f modelView, Matrix3f normalMat, int light, int overlay)
    {
        if (mode == Form.GLINT_OFF || vao == null)
        {
            return;
        }

        boolean armorPipeline = usesArmorPipeline(mode);
        RenderType renderType = getRenderType(mode, speed, armorPipeline);
        Matrix4f glintTransform = transform.createMatrix();
        Matrix4f transformedModelView = new Matrix4f(modelView).mul(glintTransform);
        Matrix3f transformedNormal = transformNormal(normalMat, glintTransform);
        RenderStateSnapshot previousState = new RenderStateSnapshot();

        renderType.setupRenderState();

        try
        {
            setupOverlayDepth(!transform.isDefault());
            ShaderInstance shader = RenderSystem.getShader();

            if (armorPipeline)
            {
                /* The vanilla glint format has no vertex color. ColorModulator is the
                 * canonical tint/opacity input; shader packs may additionally consume
                 * the NEW_ENTITY color carried by BBS' compatibility geometry. */
                RenderSystem.setShaderColor(color.r, color.g, color.b, color.a);
            }

            ModelVAORenderer.render(shader, vao, transformedModelView, transformedNormal,
                color.r, color.g, color.b, color.a, light, overlay);
        }
        finally
        {
            renderType.clearRenderState();
            previousState.restore();
        }
    }

    public static void drawMesh(int mode, float speed, Color color, MeshData mesh)
    {
        drawMesh(mode, speed, color, false, mesh);
    }

    public static void drawMesh(int mode, float speed, Color color, boolean transformed, MeshData mesh)
    {
        if (mesh == null || mode == Form.GLINT_OFF)
        {
            return;
        }

        boolean armorPipeline = usesArmorPipeline(mode);
        RenderType renderType = getRenderType(mode, speed, armorPipeline);
        RenderStateSnapshot previousState = new RenderStateSnapshot();

        renderType.setupRenderState();

        try
        {
            setupOverlayDepth(transformed);

            if (armorPipeline)
            {
                RenderSystem.setShaderColor(color.r, color.g, color.b, color.a);
            }

            BufferUploader.drawWithShader(mesh);
        }
        finally
        {
            renderType.clearRenderState();
            previousState.restore();
        }
    }

    public static void drawBuffer(int mode, float speed, Color color, boolean transformed,
        VertexBuffer buffer, Matrix4f modelView)
    {
        if (buffer == null || mode == Form.GLINT_OFF)
        {
            return;
        }

        boolean armorPipeline = usesArmorPipeline(mode);
        RenderType renderType = getRenderType(mode, speed, armorPipeline);
        RenderStateSnapshot previousState = new RenderStateSnapshot();

        renderType.setupRenderState();

        try
        {
            setupOverlayDepth(transformed);

            if (armorPipeline)
            {
                RenderSystem.setShaderColor(color.r, color.g, color.b, color.a);
            }

            buffer.bind();

            try
            {
                buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
            }
            finally
            {
                VertexBuffer.unbind();
            }
        }
        finally
        {
            renderType.clearRenderState();
            previousState.restore();
        }
    }

    private static boolean usesArmorPipeline(int mode)
    {
        return mode == Form.GLINT_VANILLA
            || (BBSRendering.isRenderingWorld() && BBSRendering.isIrisShadersEnabled());
    }

    private static RenderType getRenderType(int mode, float speed, boolean armorPipeline)
    {
        if (armorPipeline) return RenderType.armorEntityGlint();

        /* Energy swirl is a normal vanilla entity render type, so shader packs compile and
         * route it like charged-creeper/wither armor when they support that optional path.
         * Reusing the vanilla glint texture keeps the effect recognizably enchanted while
         * retaining custom tint and speed outside the shader-pack world pipeline. */
        float seconds = (Util.getMillis() % (long) (TIME_WRAP_SECONDS * 1000F)) / 1000F;
        float direction = mode == Form.GLINT_EDGE ? -1F : 1F;
        float u = seconds * speed * 0.01F * direction;
        float v = seconds * speed * 0.006F;

        return RenderType.energySwirl(ItemRenderer.ENCHANTED_GLINT_ENTITY, u % 1F, v % 1F);
    }

    /**
     * Finds the camera in the coordinate space consumed by {@code modelView}. CPU glint
     * vertices are often PoseStack-baked before upload, but the remaining global
     * model-view can still contain translation (UI cameras, view bobbing, hand scopes).
     * Assuming an origin of zero makes the Fresnel term depend on a fixed world half-plane.
     */
    public static Vector3f getViewOrigin(Matrix4f modelView)
    {
        Matrix4f inverse = new Matrix4f(modelView);

        if (Math.abs(inverse.determinant()) <= 0.00001F)
        {
            return new Vector3f();
        }

        return inverse.invert().getTranslation(new Vector3f());
    }

    /**
     * Glint is a decorative second pass and must not become the depth owner. Unchanged
     * geometry uses exact depth so transparent material pixels cannot turn into a solid
     * black/glint silhouette. An independently transformed layer needs LEQUAL because its
     * vertices no longer share the base surface's depth.
     */
    private static void setupOverlayDepth(boolean transformed)
    {
        RenderSystem.depthFunc(transformed ? GL11.GL_LEQUAL : GL11.GL_EQUAL);
        RenderSystem.depthMask(false);
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

    /** RenderType clear handlers assume vanilla owns the whole draw. BBS invokes them in the
     * middle of larger model/form renders, so restore the exact caller state afterwards. */
    private static final class RenderStateSnapshot
    {
        private final ShaderInstance shader = RenderSystem.getShader();
        private final float[] shaderColor = RenderSystem.getShaderColor().clone();
        private final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        private final int[] shaderTextures = new int[SHADER_TEXTURE_COUNT];
        private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        private final boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final int blendEquation = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        private RenderStateSnapshot()
        {
            for (int i = 0; i < this.shaderTextures.length; i++)
            {
                this.shaderTextures[i] = RenderSystem.getShaderTexture(i);
            }
        }

        private void restore()
        {
            RenderSystem.setShaderColor(this.shaderColor[0], this.shaderColor[1], this.shaderColor[2], this.shaderColor[3]);
            RenderSystem.setTextureMatrix(this.textureMatrix);

            for (int i = 0; i < this.shaderTextures.length; i++)
            {
                RenderSystem.setShaderTexture(i, this.shaderTextures[i]);
            }

            RenderSystem.setShader(() -> this.shader);
            RenderSystem.depthFunc(this.depthFunc);
            RenderSystem.depthMask(this.depthMask);

            if (this.depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();

            RenderSystem.blendEquation(this.blendEquation);
            RenderSystem.blendFuncSeparate(this.blendSrcRgb, this.blendDstRgb, this.blendSrcAlpha, this.blendDstAlpha);

            if (this.blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (this.cull) RenderSystem.enableCull(); else RenderSystem.disableCull();

            RenderSystem.activeTexture(this.activeTexture);
        }
    }
}
