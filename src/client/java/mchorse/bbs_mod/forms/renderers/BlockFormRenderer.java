package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class BlockFormRenderer extends FormRenderer<BlockForm>
{
    public static final Color color = new Color();

    public BlockFormRenderer(BlockForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        context.batcher.getContext().flush();

        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        PoseStack matrices = context.batcher.getContext().pose();

        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);

        matrices.pushPose();
        MatrixStackUtils.multiply(matrices, uiMatrix);
        matrices.scale(this.form.uiScale.get(), this.form.uiScale.get(), this.form.uiScale.get());
        matrices.translate(-0.5F, 0F, -0.5F);

        matrices.last().normal().getScale(Vectors.EMPTY_3F);
        matrices.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        Color set = this.form.color.get();

        consumers.setSubstitute(BBSRendering.getColorConsumer(set));
        consumers.setUI(true);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(this.form.blockState.get(), matrices, consumers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        consumers.draw();
        consumers.setUI(false);
        consumers.setSubstitute(null);

        matrices.popPose();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        int light = context.light;

        context.stack.pushPose();

        if (context.world != null)
        {
            context.world.pushPose();
        }

        try
        {
            context.stack.translate(-0.5F, 0F, -0.5F);

            if (context.world != null)
            {
                context.world.translate(-0.5F, 0F, -0.5F);
            }

            if (context.isPicking())
            {
                CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
                {
                    this.setupTarget(context, BBSShaders.getPickerModelsProgram());
                    RenderSystem.setShader(BBSShaders::getPickerModelsProgram);
                });

                light = 0;
            }
            else
            {
                CustomVertexConsumerProvider.hijackVertexFormat((l) -> RenderSystem.enableBlend());
            }

            Color set = this.form.color.get();

            color.set(context.color);
            color.mul(set);

            if (context.canDeferWorldTranslucency())
            {
                Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());
                FormTranslucentQueue.setSortOrigin(new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(origin));
            }

            consumers.setSubstitute(BBSRendering.getColorConsumer(set));
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(this.form.blockState.get(), context.stack, consumers, light, context.overlay);
            consumers.draw();

            if (!context.isPicking())
            {
                int finalLight = light;

                context.stack.pushPose();
                try
                {
                    MatrixStackUtils.applyTransform(context.stack, this.form.glintTransform.get());
                    FormGlintRenderer.renderCaptured(this.form, (capture) ->
                        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(this.form.blockState.get(), context.stack, capture, finalLight, context.overlay));
                }
                finally
                {
                    context.stack.popPose();
                }
            }
        }
        finally
        {
            consumers.setSubstitute(null);
            FormTranslucentQueue.setSortOrigin(null);
            CustomVertexConsumerProvider.clearRunnables();
            context.stack.popPose();

            if (context.world != null)
            {
                context.world.popPose();
            }

            RenderSystem.enableDepthTest();
        }
    }
}
