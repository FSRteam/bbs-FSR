package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.forms.forms.ExtrudedForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL11;

import java.util.function.Supplier;

public class ExtrudedFormRenderer extends FormRenderer<ExtrudedForm>
{
    public ExtrudedFormRenderer(ExtrudedForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        PoseStack stack = context.batcher.getContext().pose();

        stack.pushPose();

        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);

        this.applyTransforms(uiMatrix, context.getTransition());
        MatrixStackUtils.multiply(stack, uiMatrix);
        stack.translate(0F, 1F, 0F);
        stack.scale(1.5F, 1.5F, 4F);
        stack.scale(this.form.uiScale.get(), this.form.uiScale.get(), this.form.uiScale.get());

        /* Shading fix */
        stack.last().normal().getScale(Vectors.EMPTY_3F);
        stack.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        this.renderModel(BBSShaders::getModel,
            stack,
            OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, Colors.WHITE,
            context.getTransition(), false, true
        );
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        stack.popPose();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        boolean shading = this.form.shading.get();

        if (BBSRendering.isIrisShadersEnabled())
        {
            shading = true;
        }

        VertexFormat format = shading ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
        Supplier<ShaderInstance> shader = this.getShader(context,
            shading ? GameRenderer::getRendertypeEntityTranslucentShader : GameRenderer::getPositionColorTexLightmapShader,
            shading ? BBSShaders::getPickerBillboardProgram : BBSShaders::getPickerBillboardNoShadingProgram
        );

        this.renderModel(shader, context.stack, context.overlay, context.light, context.color,
            context.getTransition(), context.canDeferWorldTranslucency(), !context.isPicking());
    }

    private void renderModel(Supplier<ShaderInstance> shader, PoseStack matrices, int overlay, int light,
        int overlayColor, float transition, boolean defer, boolean renderGlint)
    {
        Link texture = this.form.texture.get();
        ModelVAO data = BBSModClient.getTextures().getExtruder().get(texture);

        if (data != null)
        {
            if (this.form.billboard.get())
            {
                MatrixStackUtils.billboard(matrices);
            }

            Color color = Colors.COLOR.set(overlayColor, true);
            GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
            Color formColor = this.form.color.get();

            Texture textureObject = BBSModClient.getTextures().getTexture(texture);
            BBSModClient.getTextures().bindTexture(textureObject);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            gameRenderer.lightTexture().turnOnLightLayer();
            gameRenderer.overlayTexture().setupOverlayColor();

            ShaderInstance finalShader = shader.get();
            float r = color.r * formColor.r;
            float g = color.g * formColor.g;
            float b = color.b * formColor.b;
            float a = color.a * formColor.a;
            Matrix4f modelView = ModelVAORenderer.captureModelView(matrices);
            Matrix3f normalMat = new Matrix3f(matrices.last().normal());
            boolean irisWorld = BBSRendering.isIrisShadersEnabled() && BBSRendering.isRenderingWorld();
            boolean cutout = defer && irisWorld && textureObject != null && textureObject.hasTranslucency()
                && a >= 1F && !this.form.additiveColor.get();
            boolean queueWasActive = false;

            if (cutout)
            {
                finalShader = GameRenderer.getRendertypeEntityCutoutShader();
                RenderSystem.disableBlend();
            }

            if (irisWorld)
            {
                queueWasActive = FormTranslucentQueue.suspend();
            }

            try
            {
                if (defer && FormTranslucentQueue.needsSplit(finalShader, null, textureObject, a))
                {
                    FormTranslucentQueue.setPassMode(finalShader, FormTranslucentQueue.PASS_OPAQUE);
                    ModelVAORenderer.render(finalShader, data, modelView, normalMat, r, g, b, a, light, overlay);
                    FormTranslucentQueue.setPassMode(finalShader, FormTranslucentQueue.PASS_SINGLE);
                    FormTranslucentQueue.add(new FormTranslucentQueue.ModelVAOCommand(data, textureObject,
                        modelView, normalMat, r, g, b, a, light, overlay, true));
                }
                else if (defer && FormTranslucentQueue.needsWholeDefer(finalShader, null, textureObject, a))
                {
                    ShaderInstance deferredShader = finalShader;

                    FormTranslucentQueue.add(new FormTranslucentQueue.ModelVAOCommand(data, () -> deferredShader,
                        FormTranslucentQueue.PASS_SINGLE, true, textureObject, modelView, normalMat,
                        r, g, b, a, light, overlay, true));
                }
                else
                {
                    ModelVAORenderer.render(finalShader, data, matrices, r, g, b, a, light, overlay);
                }

                if (renderGlint)
                {
                    FormGlintRenderer.render(this.form, data, modelView, normalMat, light, overlay);
                }
            }
            finally
            {
                if (cutout)
                {
                    RenderSystem.enableBlend();
                }

                if (irisWorld)
                {
                    FormTranslucentQueue.restore(queueWasActive);
                }
            }

            RenderSystem.disableBlend();

            gameRenderer.lightTexture().turnOffLightLayer();
            gameRenderer.overlayTexture().teardownOverlayColor();
        }
    }
}
