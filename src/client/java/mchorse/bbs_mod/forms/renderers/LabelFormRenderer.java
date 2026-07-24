package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

public class LabelFormRenderer extends FormRenderer<LabelForm>
{
    private final Color textColor = new Color();
    private final Color shadowColor = new Color();
    private final Color backgroundColor = new Color();

    public static void fillQuad(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a)
    {
        Matrix4f matrix4f = stack.last().pose();

        /* 1 - BR, 2 - BL, 3 - TL, 4 - TR */
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x2, y2, z2).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x1, y1, z1).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x3, y3, z3).setColor(r, g, b, a).setUv(0F, 0F);
        builder.addVertex(matrix4f, x4, y4, z4).setColor(r, g, b, a).setUv(0F, 0F);
    }

    public LabelFormRenderer(LabelForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        int color = this.form.color.get().getARGBColor();
        String text = StringUtils.processColoredText(this.form.text.get());
        List<String> wrap = context.batcher.getFont().wrap(text, x2 - x1 - 4);

        int th = context.batcher.getFont().getHeight();
        int lineHeight = th + 4;
        int h = th + (wrap.size() - 1) * lineHeight;
        int y = (y2 + y1) / 2 - h / 2;

        for (String s : wrap)
        {
            context.batcher.textShadow(s, x1 + 2, y, color);

            y += lineHeight;
        }
    }

    @Override
    public void render3D(FormRenderingContext context)
    {
        context.stack.pushPose();

        if (this.form.billboard.get())
        {
            MatrixStackUtils.billboard(context.stack);
        }

        Font renderer = Minecraft.getInstance().font;
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        float scale = 1F / 16F;
        int light = context.light;

        MatrixStackUtils.scaleStack(context.stack, scale, -scale, scale);

        boolean grouped = context.canDeferWorldTranslucency() && FormTranslucentQueue.isActive();
        if (grouped)
        {
            Vector3f origin = new Matrix4f(RenderSystem.getModelViewMatrix())
                .transformPosition(context.stack.last().pose().getTranslation(new Vector3f()));
            FormTranslucentQueue.beginGroup(origin, false);
        }

        try
        {
            RenderSystem.disableCull();

            if (context.isPicking())
            {
                CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
                {
                    this.setupTarget(context, BBSShaders.getPickerModelsProgram());
                    RenderSystem.setShader(BBSShaders::getPickerModelsProgram);
                });

                light = 0;
            }

            if (this.form.max.get() <= 10)
            {
                this.renderString(context, consumers, renderer, light);
            }
            else
            {
                this.renderLimitedString(context, consumers, renderer, light);
            }
        }
        finally
        {
            CustomVertexConsumerProvider.clearRunnables();

            if (grouped)
            {
                FormTranslucentQueue.endGroup();
            }

            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            context.stack.popPose();
        }
    }

    private void renderString(FormRenderingContext context, CustomVertexConsumerProvider consumers, Font renderer, int light)
    {
        String content = StringUtils.processColoredText(this.form.text.get());
        float transition = context.getTransition();
        int w = renderer.width(content) - 1;
        int h = renderer.lineHeight - 2;
        int x = (int) (-w * this.form.anchorX.get());
        int y = (int) (-h * this.form.anchorY.get());

        Color shadowColor = this.shadowColor.copy(this.form.shadowColor.get());
        Color color = this.textColor.set(context.color, true);

        FormColorBlend.blend(color, this.form.color.get(), this.form.additiveColor.get());
        shadowColor.mul(context.color);

        if (shadowColor.a > 0)
        {
            context.stack.pushPose();
            context.stack.translate(0F, 0F, -0.1F);
            renderer.drawInBatch(
                content,
                x + this.form.shadowX.get(),
                y + this.form.shadowY.get(),
                shadowColor.getARGBColor(), false,
                context.stack.last().pose(),
                consumers,
                Font.DisplayMode.NORMAL,
                0,
                light
            );
            context.stack.popPose();
        }

        renderer.drawInBatch(
            content,
            x,
            y,
            color.getARGBColor(), false,
            context.stack.last().pose(),
            consumers,
            Font.DisplayMode.NORMAL,
            0,
            light
        );

        RenderSystem.enableDepthTest();

        consumers.draw();

        this.renderShadow(context, x, y, w, h);
    }

    private void renderLimitedString(FormRenderingContext context, CustomVertexConsumerProvider consumers, Font renderer, int light)
    {
        float transition = context.getTransition();
        int w = 0;
        int h = renderer.lineHeight - 2;
        String content = StringUtils.processColoredText(this.form.text.get());
        List<String> lines = FontRenderer.wrap(renderer, content, this.form.max.get());

        if (lines.size() <= 1)
        {
            this.renderString(context, consumers, renderer, light);

            return;
        }

        for (int i = 0; i < lines.size(); i++)
        {
            lines.set(i, lines.get(i).trim());
        }

        for (String line : lines)
        {
            w = Math.max(renderer.width(line) - 1, w);
            h += 12;
        }

        h -= 12;

        int x = (int) (-w * this.form.anchorX.get());
        int y = (int) (-h * this.form.anchorY.get());
        int y2 = y;

        Color shadowColor = this.shadowColor.copy(this.form.shadowColor.get());

        shadowColor.mul(context.color);

        if (shadowColor.a > 0)
        {
            context.stack.pushPose();
            context.stack.translate(0F, 0F, -0.1F);

            for (String line : lines)
            {
                int x2 = x + (this.form.anchorLines.get() ? (int) ((w - renderer.width(line)) * this.form.anchorX.get()) : 0);

                renderer.drawInBatch(
                    line,
                    x2 + this.form.shadowX.get(),
                    y2 + this.form.shadowY.get(),
                    shadowColor.getARGBColor(), false,
                    context.stack.last().pose(),
                    consumers,
                    Font.DisplayMode.NORMAL,
                    0,
                    light
                );

                y2 += 12;
            }

            context.stack.popPose();

            y2 = y;
        }

        Color cColor = this.textColor.set(context.color, true);

        FormColorBlend.blend(cColor, this.form.color.get(), this.form.additiveColor.get());

        int color = cColor.getARGBColor();

        for (String line : lines)
        {
            int x2 = x + (this.form.anchorLines.get() ? (int) ((w - renderer.width(line)) * this.form.anchorX.get()) : 0);

            renderer.drawInBatch(
                line,
                x2,
                y2,
                color, false,
                context.stack.last().pose(),
                consumers,
                Font.DisplayMode.NORMAL,
                0,
                light
            );

            y2 += 12;
        }

        consumers.draw();

        RenderSystem.enableDepthTest();

        this.renderShadow(context, x, y, w, h);
    }

    private void renderShadow(FormRenderingContext context, int x, int y, int w, int h)
    {
        float offset = this.form.offset.get();
        Color color = this.backgroundColor.copy(this.form.background.get());

        color.mul(context.color);

        if (color.a <= 0)
        {
            return;
        }

        context.stack.pushPose();
        context.stack.translate(0, 0, -0.2F);

        BufferBuilder builder;

        builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);

        fillQuad(
            builder, context.stack,
            x + w + offset, y - offset, 0,
            x - offset, y - offset, 0,
            x - offset, y + h + offset, 0,
            x + w + offset, y + h + offset, 0,
            color.r, color.g, color.b, color.a
        );

        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        MeshData mesh = builder.buildOrThrow();
        if (FormTranslucentQueue.isGroupOpen())
        {
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            Vector3f origin = new Vector3f(FormTranslucentQueue.getSortOrigin());
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer,
                GameRenderer::getPositionColorShader, null, modelView, null, origin, false, null, null));
        }
        else
        {
            BufferUploader.drawWithShader(mesh);
        }
        context.stack.popPose();
    }
}
