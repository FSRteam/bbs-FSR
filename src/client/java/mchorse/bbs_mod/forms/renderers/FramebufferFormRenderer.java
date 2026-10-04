package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormRenderLast;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.renderers.utils.FramebufferDebug;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.Renderbuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Quad;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
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
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.function.Supplier;

public class FramebufferFormRenderer extends FormRenderer<FramebufferForm>
{
    private static final Quad quad = new Quad();
    private static final Quad uvQuad = new Quad();
    /* Both lights along Z, one each way (7d12a584a). The picture in here is meant to be flat, and
     * the two vanilla lights are what a flat one is made of - but pointing both at the camera
     * lights only the faces that happen to look back at it. The framebuffer renders under a
     * Y-flipped ortho with front faces culled, so a two-sided quad (a billboard draws both of its
     * sides) keeps the side whose normal points away, and that side came out at
     * MINECRAFT_AMBIENT_LIGHT alone - 40% - while a one-sided model next to it stayed lit. */
    private static final Vector3f framebufferLight0 = new Vector3f(0F, 0F, 1F);
    private static final Vector3f framebufferLight1 = new Vector3f(0F, 0F, -1F);

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

    /**
     * The palette thumbnail. A framebuffer form of its own draws nothing here: its picture is
     * the body parts rendered into an off-screen texture by {@link #renderBodyParts}, which the
     * world pass owns. An empty cell is indistinguishable from a gap in the grid, so a form with
     * no body parts shows the camera icon instead - the same cell the form's editor tab wears
     * (upstream 449f0058f).
     */
    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        if (this.form.parts.getAll().isEmpty())
        {
            context.batcher.icon(Icons.CAMERA, (x1 + x2) / 2, (y1 + y2) / 2, 0.5F, 0.5F);
        }
    }

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

        FramebufferDebug.beginRender(this.form, context, framebuffer);

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
        float[] clearColor = new float[4];

        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);

        int prevCullFace = GL30.glGetInteger(GL11.GL_CULL_FACE_MODE);
        Vector3f light0 = new Vector3f(RenderSystem.shaderLightDirections[0]);
        Vector3f light1 = new Vector3f(RenderSystem.shaderLightDirections[1]);
        Matrix4f projectionMatrix = this.projectionMatrix.set(RenderSystem.getProjectionMatrix());
        VertexSorting vertexSorting = RenderSystem.getVertexSorting();

        FramebufferDebug.state("entry", context);

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

            /* Transparent clear (beeeb3f2a): whatever was drawn before us may have left an opaque
             * clear colour, and clearing this buffer with it would give the finished picture a
             * solid background. */
            RenderSystem.clearColor(0F, 0F, 0F, 0F);
            FramebufferDebug.clearState("clear");
            framebuffer.clear();

            context.stack.pushPose();

            try
            {
                context.stack.last().pose().identity();
                context.stack.last().normal().identity();

                depth += 1;

                try
                {
                    /* The nested forms render under an ortho projection into this framebuffer —
                     * deferring their translucent pixels into the world's queue would replay them
                     * with the wrong projection, so they render single-pass as before. Render-last
                     * is off here for the same reason: a part postponed out of this buffer would
                     * come back in the world. Both are suspended for the whole scope below, body
                     * parts included, because the parts are what would defer. */
                    boolean queueWasActive = FormTranslucentQueue.suspend();
                    boolean renderLastWasActive = FormRenderLast.suspend();

                    try
                    {
                        /* Full bright on the way in (d7a71e001): the quad that draws the finished
                         * picture applies the caller's lightmap once, so letting it shade the parts
                         * inside the buffer too would land the very same shading on them twice. */
                        int light = context.light;

                        context.light = LightTexture.FULL_BRIGHT;

                        try
                        {
                            /* Blending as GL really holds it, not as GlStateManager's cache
                             * believes (8d47b6af3). A shader pack's per-draw-buffer blend modes are
                             * set by Iris with indexed GL calls the cache never sees, and put back
                             * through the cache - which skips the real call when it already thinks
                             * the default is in place. So after a pack's entity program the world
                             * runs with the alpha factors ZERO/ONE on draw buffer 0 while the cache
                             * says ONE/ZERO. This buffer is cleared to alpha 0, and every part's
                             * defaultBlendFunc() was a no-op against that cache: the parts painted
                             * their colours, alpha stayed 0, and the quad drew a fully transparent
                             * picture - only in the world pass, only under a pack. A raw reset puts
                             * GL at the default, the tracked calls put the cache there too. Nothing
                             * is put back afterwards: the pack re-applies its overrides on its next
                             * program bind, and a cache that agrees with GL is the state everything
                             * else assumes. */
                            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                            GL11.glEnable(GL11.GL_BLEND);
                            RenderSystem.defaultBlendFunc();
                            RenderSystem.enableBlend();

                            /* Off-screen rendering: only the outermost framebuffer form flips Iris
                             * off the main target, and the shadow pass stays off for the duration. */
                            BBSRendering.renderOffscreen(() -> super.renderBodyParts(context));
                        }
                        finally
                        {
                            context.light = light;
                        }
                    }
                    finally
                    {
                        FormTranslucentQueue.restore(queueWasActive);
                        FormRenderLast.restore(renderLastWasActive);
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

            FramebufferDebug.readBuffer("after parts", framebuffer);
            FramebufferDebug.state("after parts", context);
        }
        finally
        {
            RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
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
            FramebufferDebug.endRender();
        }

        boolean shading = !context.isPicking();
        VertexFormat format = shading ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
        Supplier<ShaderInstance> shader = shading ? GameRenderer::getRendertypeEntityTranslucentShader : GameRenderer::getPositionColorTexLightmapShader;

        this.renderModel(framebuffer.getMainTexture(), format, shader, context.stack, context.overlay,
            context.light, context.color, context.getTransition(), !context.isPicking());
    }

    /**
     * Diagnostic (see {@link FramebufferDebug}): every nested part reports its bindings and what it
     * left in the buffer. Off by default; the unlogged path is exactly the inherited one.
     */
    @Override
    protected void renderBodyPart(BodyPart part, FormRenderingContext context)
    {
        if (!FramebufferDebug.inside())
        {
            super.renderBodyPart(part, context);

            return;
        }

        String name = part.getForm() == null ? "null" : part.getForm().getClass().getSimpleName();

        FramebufferDebug.log("part", "begin " + name + " id=" + part.getId() + " | " + FramebufferDebug.bindings());

        super.renderBodyPart(part, context);

        FramebufferDebug.log("part", "end " + name + " | " + FramebufferDebug.bindings());
        FramebufferDebug.log("part", "end " + name + " | " + FramebufferDebug.glState());
        FramebufferDebug.readViewport("part end " + name);
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

        /* No raw bind here (0a3d78437): the draw binds its own samplers, and a bind on whatever
         * texture unit happens to be active would land behind GlStateManager's back - see
         * BillboardFormRenderer. */
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

        if (FramebufferDebug.logging)
        {
            FramebufferDebug.log("quad", "shader=" + FramebufferDebug.shader(shader.get()) + " deferred=" + defer
                + " | " + FramebufferDebug.bindings());
        }

        FramebufferDebug.state("before quad", matrices);

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
<<<<<<< HEAD

            /* The quad's opaque texels also draw right here, writing depth (67f23fe9c), because
             * the sort alone cannot order this quad against a model it sits inside: a
             * semi-transparent layer of the parent model (a skin's hat layer) sorts by its group's
             * pivot, which is always further than the quad's own plane, so it replays first. With
             * depth in the buffer that layer lands over the quad by the depth test, pixel by
             * pixel, instead of the two fighting over who overwrites whom. */
            ShaderInstance cutout = GameRenderer.getRendertypeEntityCutoutShader();

            if (cutout != null)
            {
                /* The world pass draws with depth writes on; this only re-asserts it. */
                RenderSystem.depthMask(true);

                buffer.bind();
                buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), cutout);
                VertexBuffer.unbind();
            }

=======
>>>>>>> origin/master
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer,
                () -> capturedShader, texture, modelView, null, origin, planeNormal, true, null, null));
        }
        else
        {
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        gameRenderer.lightTexture().turnOffLightLayer();
        gameRenderer.overlayTexture().teardownOverlayColor();

        FramebufferDebug.quad("quad corners", matrices, quad);
        FramebufferDebug.state("after quad", matrices);
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
