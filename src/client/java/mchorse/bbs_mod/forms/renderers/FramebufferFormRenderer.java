package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.Renderbuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Quad;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.function.Supplier;

public class FramebufferFormRenderer extends FormRenderer<FramebufferForm>
{
    private static final Quad quad = new Quad();
    private static final Quad uvQuad = new Quad();
    private static final Vector3f framebufferLight0 = new Vector3f(0F, 0F, 1F);
    private static final Vector3f framebufferLight1 = new Vector3f(0F, 0F, 1F);

    /* Nested framebuffer forms need separate targets: the depth picks which cached framebuffer
     * this level renders into (the off-screen Iris state is nested inside renderOffscreen). */
    private static int depth;

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f orthoMatrix = new Matrix4f();
    private final Color quadColor = new Color();

    public FramebufferFormRenderer(FramebufferForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {}

    @Override
    public void renderBodyParts(FormRenderingContext context)
    {
        int w = MathUtils.clamp(this.form.width.get(), 2, 4096);
        int h = MathUtils.clamp(this.form.height.get(), 2, 4096);
        Framebuffer framebuffer = BBSModClient.getFramebuffers().getFramebuffer(Link.bbs("framebuffer_form_" + depth), w, h, (f) ->
        {
            Texture texture = new Texture();

            texture.setSize(w, h);
            texture.setFilter(GL11.GL_NEAREST);
            texture.setWrap(GL13.GL_CLAMP_TO_EDGE);

            Renderbuffer renderbuffer = new Renderbuffer();

            renderbuffer.resize(w, h);

            f.deleteTextures().attach(texture, GL30.GL_COLOR_ATTACHMENT0);
            f.attach(renderbuffer);
            f.unbind();
        });

        int width;
        int height;
        int viewportX;
        int viewportY;

        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer viewport = stack.mallocInt(4);

            GL30.glGetIntegerv(GL30.GL_VIEWPORT, viewport);

            viewportX = viewport.get(0);
            viewportY = viewport.get(1);
            width = viewport.get(2);
            height = viewport.get(3);
        }

        int prevDraw = GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];

        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);

        int prevCullFace = GL30.glGetInteger(GL11.GL_CULL_FACE_MODE);
        Vector3f light0 = new Vector3f(RenderSystem.shaderLightDirections[0]);
        Vector3f light1 = new Vector3f(RenderSystem.shaderLightDirections[1]);
        Matrix4f projectionMatrix = this.projectionMatrix.set(RenderSystem.getProjectionMatrix());
        VertexSorting vertexSorting = RenderSystem.getVertexSorting();

        GL30.glCullFace(GL30.GL_FRONT);
        RenderSystem.setShaderLights(framebufferLight0, framebufferLight1);
        RenderSystem.setProjectionMatrix(this.orthoMatrix.identity().setOrtho(-1F, 1F, 1F, -1F, -500F, 500F), VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();

        try
        {
            RenderSystem.applyModelViewMatrix();
            framebuffer.apply();

            /* Whoever was drawing before us may have left a scissor box — the UI clips its
             * viewport that way — and it would clip this framebuffer's own pixels too. */
            RenderSystem.disableScissor();
            framebuffer.clear();

            context.stack.pushPose();

            try
            {
                context.stack.last().pose().identity();
                context.stack.last().normal().identity();

                depth += 1;

                try
                {
                    boolean queueWasActive = FormTranslucentQueue.suspend();

                    try
                    {
                        /* Off-screen rendering: only the outermost framebuffer form flips Iris
                         * off the main target, and the shadow pass stays off for the duration. */
                        BBSRendering.renderOffscreen(() -> super.renderBodyParts(context));
                    }
                    finally
                    {
                        FormTranslucentQueue.restore(queueWasActive);
                    }
                }
                finally
                {
                    depth -= 1;
                }
            }
            finally
            {
                context.stack.popPose();
            }
        }
        finally
        {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glViewport(viewportX, viewportY, width, height);

            if (scissorEnabled)
            {
                RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            }
            else
            {
                RenderSystem.disableScissor();
            }

            RenderSystem.setShaderLights(light0, light1);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projectionMatrix, vertexSorting);
            GL30.glCullFace(prevCullFace);
        }

        boolean shading = !context.isPicking();
        VertexFormat format = shading ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
        Supplier<ShaderInstance> shader = shading ? GameRenderer::getRendertypeEntityTranslucentShader : GameRenderer::getPositionColorTexLightmapShader;

        this.renderModel(framebuffer.getMainTexture(), format, shader, context.stack, context.overlay,
            context.light, context.color, context.getTransition(), !context.isPicking());
    }

    private void renderModel(Texture texture, VertexFormat format, Supplier<ShaderInstance> shader,
        PoseStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        float w = texture.width;
        float h = texture.height;

        /* TL = top left, BR = bottom right*/
        float uvTLx = 0F;
        float uvTLy = 0F;
        float uvBRx = 1F;
        float uvBRy = 1F;

        uvQuad.p1.set(uvTLx, uvTLy, 0);
        uvQuad.p2.set(uvBRx, uvTLy, 0);
        uvQuad.p3.set(uvTLx, uvBRy, 0);
        uvQuad.p4.set(uvBRx, uvBRy, 0);

        /* Calculate quad's size (vertices, not UV). The scale sizes the quad the framebuffer is
         * shown on, not what is drawn into it — the body parts always fill the whole texture, so
         * raising it can't push them past the framebuffer's own edges. */
        float scale = this.form.scale.get() * 2F;
        float ratioX = (w > h ? h / w : 1F) * scale;
        float ratioY = (h > w ? w / h : 1F) * scale;
        float TLx = (uvTLx - 0.5F) * ratioY;
        float TLy = -(uvTLy - 0.5F) * ratioX;
        float BRx = (uvBRx - 0.5F) * ratioY;
        float BRy = -(uvBRy - 0.5F) * ratioX;

        quad.p1.set(TLx, TLy, 0);
        quad.p2.set(BRx, TLy, 0);
        quad.p3.set(TLx, BRy, 0);
        quad.p4.set(BRx, BRy, 0);

        this.renderQuad(format, texture, shader, matrices, overlay, light, overlayColor, transition, defer);
    }

    private void renderQuad(VertexFormat format, Texture texture, Supplier<ShaderInstance> shader,
        PoseStack matrices, int overlay, int light, int overlayColor, float transition, boolean defer)
    {
        BufferBuilder builder;
        Color color = this.quadColor.set(1F, 1F, 1F, 1F);
        Matrix4f matrix = matrices.last().pose();
        PoseStack.Pose normal = matrices.last();

        color.mul(overlayColor);

        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;

        gameRenderer.lightTexture().turnOnLightLayer();
        gameRenderer.overlayTexture().setupOverlayColor();

        BBSModClient.getTextures().bindTexture(texture);
        RenderSystem.setShader(shader);

        texture.bind();
        texture.setFilterMipmap(false, false);
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, format);

        /* Front */
        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, normal, 1F);
        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, normal, 1F);
        this.fill(format, builder, matrix, quad.p1.x, quad.p1.y, color, uvQuad.p1.x, uvQuad.p1.y, overlay, light, normal, 1F);

        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, normal, 1F);
        this.fill(format, builder, matrix, quad.p4.x, quad.p4.y, color, uvQuad.p4.x, uvQuad.p4.y, overlay, light, normal, 1F);
        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, normal, 1F);

        /* Back */
        this.fill(format, builder, matrix, quad.p1.x, quad.p1.y, color, uvQuad.p1.x, uvQuad.p1.y, overlay, light, normal, -1F);
        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, normal, -1F);
        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, normal, -1F);

        this.fill(format, builder, matrix, quad.p2.x, quad.p2.y, color, uvQuad.p2.x, uvQuad.p2.y, overlay, light, normal, -1F);
        this.fill(format, builder, matrix, quad.p4.x, quad.p4.y, color, uvQuad.p4.x, uvQuad.p4.y, overlay, light, normal, -1F);
        this.fill(format, builder, matrix, quad.p3.x, quad.p3.y, color, uvQuad.p3.x, uvQuad.p3.y, overlay, light, normal, -1F);

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();

        if (defer && FormTranslucentQueue.isActive())
        {
            /* The deferred command binds the framebuffer's live texture at flush, and the cache
             * hands one buffer to every form of a given nesting depth, so two sibling framebuffer
             * forms deferring into the same queue would both show the last-rendered content. A
             * known trade-off of the shared-per-depth framebuffer scheme, same as upstream's pool. */
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            VertexBuffer.unbind();

            ShaderInstance capturedShader = RenderSystem.getShader();
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            Vector3f origin = modelView.transformPosition(matrix.getTranslation(new Vector3f()));
            Vector3f planeNormal = FormTranslucentQueue.quadPlaneNormal(modelView, matrix);
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer,
                () -> capturedShader, texture, modelView, null, origin, planeNormal, true, null, null));
        }
        else
        {
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        gameRenderer.lightTexture().turnOffLightLayer();
        gameRenderer.overlayTexture().teardownOverlayColor();
    }

    private void fill(VertexFormat format, VertexConsumer consumer, Matrix4f matrix, float x, float y, Color color, float u, float v, int overlay, int light, PoseStack.Pose normal, float nz)
    {
        if (format == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
        {
            consumer.addVertex(matrix, x, y, 0F).setUv(u, v).setLight(light).setColor(color.r, color.g, color.b, color.a);
            return;
        }

        consumer.addVertex(matrix, x, y, 0F).setColor(color.r, color.g, color.b, color.a).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(normal, 0F, 0F, nz);
    }
}
