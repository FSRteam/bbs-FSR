package mchorse.bbs_mod.ui.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

public class Gizmo
{
    public static class DragContext
    {
        public final Matrix4f modelView = new Matrix4f();
        public final Matrix4f projection = new Matrix4f();
        public int viewportX;
        public int viewportY;
        public int viewportW;
        public int viewportH;
        public boolean ready;
    }

    public final static int STENCIL_X = 1;
    public final static int STENCIL_Y = 2;
    public final static int STENCIL_Z = 3;
    public final static int STENCIL_XZ = 4;
    public final static int STENCIL_XY = 5;
    public final static int STENCIL_ZY = 6;
    public final static int STENCIL_SCALE_X = 7;
    public final static int STENCIL_SCALE_Y = 8;
    public final static int STENCIL_SCALE_Z = 9;
    public final static int STENCIL_SCALE_XZ = 10;
    public final static int STENCIL_SCALE_XY = 11;
    public final static int STENCIL_SCALE_ZY = 12;
    public final static int STENCIL_ROTATE_X = 13;
    public final static int STENCIL_ROTATE_Y = 14;
    public final static int STENCIL_ROTATE_Z = 15;
    public final static int STENCIL_TRACKBALL = 16;
    public final static int STENCIL_VIEW = 17;
    public final static int STENCIL_SCREEN = 18;
    public final static int STENCIL_SCALE_ALL = 19;
    public final static int STENCIL_MAX = STENCIL_SCALE_ALL;
    private final static float VIEW_RING_SCALE = 1.2F;
    private final static float COMBINED_INNER_SCALE = 0.6F;
    private final static float RING_FACE_ON_BIAS = 0.18F;
    private final static int RING_OCCLUSION_SAMPLES = 180;
    private final static float SCALE_CUBE_HALF = 0.032F;
    private final static float SCREEN_CUBE_HALF = 0.03F;

    public final static Gizmo INSTANCE = new Gizmo();

    private Mode mode = Mode.COMBINED;
    private Mode previousMode = Mode.TRANSLATE;

    private int index;
    /* TODO: I'm too lazy to figure out right now the plane intersection algorithm for
     * proper transforms, but for now, it appears, this implementation works as well
     * not even that poorly! */
    private int mouseX;
    private int mouseY;

    private UIPropTransform currentTransform;
    private final DragContext dragContext = new DragContext();
    private final Matrix4f lastRenderMatrix = new Matrix4f();
    private boolean hasLastRenderMatrix;
    private VertexBuffer rotateRingVbo;
    private VertexBuffer rotateSphereVbo;
    private float lastScale = -1F;
    private float lastThickness = -1F;
    private float lastSphereLocalRadius;
    private final Matrix4f lastSphereMatrix = new Matrix4f();
    private boolean hasLastSphereMatrix;
    private final StencilFormFramebuffer sphereHighlight = new StencilFormFramebuffer();
    private boolean sphereHovered;

    /** Per-frame on-screen size compensation, {@code menu.height / viewportArea.h}.
     *  {@link #getAxesDistanceScale} otherwise keeps the gizmo a constant fraction
     *  of its viewport, so it shrinks in a small preview (the film) versus a
     *  full-screen editor (forms); this factor makes it a constant fraction of the
     *  window instead, i.e. the same on-screen size in every editor. Each viewport
     *  sets it via {@link #setViewportScale} before BOTH its visual and stencil
     *  pass so the drawn gizmo and its pick hitbox scale together. */
    private float viewportScale = 1F;

    private Gizmo()
    {}

    public Mode getMode()
    {
        return this.mode;
    }

    public boolean setMode(Mode mode)
    {
        if (!BBSSettings.gizmos.get())
        {
            return false;
        }

        boolean same = this.mode == mode;

        this.mode = mode;

        return !same;
    }

    public String getDragReadout()
    {
        return this.currentTransform == null ? null : this.currentTransform.getDragReadout();
    }

    public void setSphereHovered(boolean hovered)
    {
        this.sphereHovered = hovered;
    }

    /**
     * Set this frame's on-screen size compensation ({@code menu.height /
     * viewportArea.h}). Call before the visual and stencil pass of the gizmo's
     * viewport, with the same value for both, so the drawn gizmo and its pick
     * hitbox stay the same constant on-screen size across editors.
     */
    public void setViewportScale(float viewportScale)
    {
        this.viewportScale = viewportScale > 0F && Float.isFinite(viewportScale) ? viewportScale : 1F;
    }

    public boolean isSphereInteractive()
    {
        if (!BBSSettings.gizmos.get() || !BBSSettings.rotate3dSphere.get() || !this.hasSphere())
        {
            return false;
        }

        if (this.currentTransform != null && this.currentTransform.isEditing() && !this.currentTransform.isTrackball())
        {
            return false;
        }

        return true;
    }

    public boolean isTrackballDragging()
    {
        return this.currentTransform != null && this.currentTransform.isEditing() && this.currentTransform.isTrackball();
    }

    public boolean hasSphere()
    {
        return this.mode == Mode.ROTATE || this.mode == Mode.COMBINED;
    }

    public boolean toggleCombined()
    {
        if (this.mode == Mode.COMBINED)
        {
            return this.setMode(this.previousMode);
        }

        Mode previous = this.mode;

        if (this.setMode(Mode.COMBINED))
        {
            this.previousMode = previous;

            return true;
        }

        return false;
    }

    public boolean computeWorldOrigin(Camera camera, Vector3d out)
    {
        if (!this.hasLastRenderMatrix)
        {
            return false;
        }

        Matrix4f undoView = new Matrix4f(camera.view).invert().mul(this.lastRenderMatrix);
        Vector3f cameraRelative = undoView.getTranslation(new Vector3f());

        out.set(
            camera.position.x + cameraRelative.x,
            camera.position.y + cameraRelative.y,
            camera.position.z + cameraRelative.z
        );

        return true;
    }

    public boolean computeWorldAxes(Camera camera, Matrix3f out)
    {
        if (!this.hasLastRenderMatrix)
        {
            return false;
        }

        Matrix4f undoView = new Matrix4f(camera.view).invert().mul(this.lastRenderMatrix);

        out.set(undoView.get3x3(new Matrix3f()));

        Vector3f col = new Vector3f();

        for (int i = 0; i < 3; i++)
        {
            out.getColumn(i, col);

            float lenSq = col.lengthSquared();

            if (lenSq < 1.0E-12F)
            {
                return false;
            }

            col.div((float) Math.sqrt(lenSq));
            out.setColumn(i, col);
        }

        return true;
    }

    public boolean computeScreenCenter(Matrix4f projection, float areaX, float areaY, float areaW, float areaH, Vector2f out)
    {
        if (!this.hasLastRenderMatrix)
        {
            return false;
        }

        Matrix4f mvp = new Matrix4f(projection).mul(this.lastRenderMatrix);
        Vector4f clip = mvp.transform(new Vector4f(0F, 0F, 0F, 1F));

        if (clip.w <= 0F)
        {
            return false;
        }

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;

        out.x = areaX + (ndcX * 0.5F + 0.5F) * areaW;
        out.y = areaY + (1F - (ndcY * 0.5F + 0.5F)) * areaH;

        return true;
    }

    public float computeScreenRadius(Matrix4f projection, float areaX, float areaY, float areaW, float areaH)
    {
        if (!this.hasLastRenderMatrix || this.lastSphereLocalRadius <= 0F)
        {
            return 0F;
        }

        Vector2f center = new Vector2f();

        if (!this.computeScreenCenter(projection, areaX, areaY, areaW, areaH, center))
        {
            return 0F;
        }

        Matrix4f mvp = new Matrix4f(projection).mul(this.lastRenderMatrix);
        float r = this.lastSphereLocalRadius;
        float[] xs = {r, 0F, 0F};
        float[] ys = {0F, r, 0F};
        float[] zs = {0F, 0F, r};
        float maxSq = 0F;

        for (int i = 0; i < 3; i++)
        {
            Vector4f clip = mvp.transform(new Vector4f(xs[i], ys[i], zs[i], 1F));

            if (clip.w <= 0F) continue;

            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float px = areaX + (ndcX * 0.5F + 0.5F) * areaW;
            float py = areaY + (1F - (ndcY * 0.5F + 0.5F)) * areaH;
            float dx = px - center.x;
            float dy = py - center.y;
            float d = dx * dx + dy * dy;

            if (d > maxSq) maxSq = d;
        }

        return (float) Math.sqrt(maxSq);
    }

    public void renderSphereHighlight(UIContext context, Matrix4f projection, Area area)
    {
        if (!this.sphereHovered || !this.hasLastSphereMatrix || !this.isSphereInteractive()
            || !UIBaseMenu.shouldRenderAxes() || this.rotateSphereVbo == null || projection == null || area == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        this.sphereHighlight.setup(Link.bbs("gizmo_sphere_highlight"));

        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        Texture texture = this.sphereHighlight.getFramebuffer().getMainTexture();

        if (texture.width != w || texture.height != h)
        {
            this.sphereHighlight.resize(w, h);
        }

        context.batcher.flush();

        boolean applied = false;

        try
        {
            this.sphereHighlight.apply();
            applied = true;

            RenderSystem.disableDepthTest();
            RenderSystem.setShaderColor(STENCIL_TRACKBALL / 255F, 0F, 0F, 1F);
            this.rotateSphereVbo.bind();
            this.rotateSphereVbo.drawWithShader(this.lastSphereMatrix, projection, GameRenderer.getPositionColorShader());
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.enableDepthTest();
        }
        finally
        {
            if (applied)
            {
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                RenderSystem.enableDepthTest();
                this.sphereHighlight.unbind();
            }

            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }

        ShaderInstance previewProgram = BBSShaders.getPickerPreviewProgram();
        Uniform target = previewProgram.getUniform("Target");

        if (target != null)
        {
            target.set(STENCIL_TRACKBALL);
        }

        Uniform highlight = previewProgram.getUniform("HighlightColor");

        if (highlight != null)
        {
            int color = BBSSettings.stencilHighlightColor.get();

            highlight.set(Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
        }

        RenderSystem.enableBlend();
        context.batcher.texturedBox(BBSShaders::getPickerPreviewProgram, texture.id, Colors.WHITE, area.x, area.y, area.w, area.h, 0, texture.height, texture.width, 0, texture.width, texture.height);
    }

    public void setViewport(Area area)
    {
        if (area == null)
        {
            this.dragContext.viewportW = 0;
            this.dragContext.viewportH = 0;
            this.dragContext.ready = false;
            this.hasLastRenderMatrix = false;

            return;
        }

        this.dragContext.viewportX = area.x;
        this.dragContext.viewportY = area.y;
        this.dragContext.viewportW = area.w;
        this.dragContext.viewportH = area.h;
    }

    public boolean start(int index, int mouseX, int mouseY, UIPropTransform transform)
    {
        return this.start(index, mouseX, mouseY, transform, null);
    }

    public boolean start(int index, int mouseX, int mouseY, UIPropTransform transform, GizmoDrag drag)
    {
        if (!BBSSettings.gizmos.get())
        {
            return false;
        }

        Handle handle = Handle.byIndex(index);

        if (handle != null)
        {
            this.index = index;
            this.mouseX = mouseX;
            this.mouseY = mouseY;

            this.currentTransform = transform;

            if (transform != null)
            {
                if (handle.op == Operation.MOVE || handle.op == Operation.SCALE || handle.op == Operation.ROTATE)
                {
                    transform.enableMode(handle.op.modeOrdinal, handle.axis, handle.axis2, drag);
                }
                else if (handle.op == Operation.TRACKBALL)
                {
                    if (BBSSettings.rotate3dSphere.get()) transform.enableSphereRotate(drag);
                }
                else if (handle.op == Operation.VIEW)
                {
                    transform.enableViewRotate(drag);
                }
                else if (handle.op == Operation.SCREEN)
                {
                    transform.enableScreenTranslate(drag);
                }
                else if (handle.op == Operation.SCALE_ALL)
                {
                    transform.enableUniformScale(drag);
                }
            }

            return true;
        }

        return false;
    }

    public void trackTransform(UIPropTransform transform)
    {
        this.currentTransform = transform;
    }

    public void clearTrackedTransform(UIPropTransform transform)
    {
        if (this.currentTransform == transform)
        {
            this.currentTransform = null;

            if (this.index < STENCIL_X || this.index > STENCIL_MAX)
            {
                this.index = -1;
            }
        }
    }

    public void stop()
    {
        this.index = -1;

        if (this.currentTransform != null)
        {
            this.currentTransform.acceptChanges();
        }

        this.currentTransform = null;
    }

    public void render(PoseStack stack)
    {
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        stack.pushPose();
        MatrixStackUtils.scaleBack(stack);
        this.captureRenderMatrix(stack);
        this.drawGizmo(stack);
        stack.popPose();
    }

    /**
     * Capture the gizmo's model-view for the deferred interface-pass visual
     * ({@link #renderInterface}) without drawing anything in the caller's world
     * / 3D pass. The visual moved out of the world pass so its translucent parts
     * (the rotation sphere, the sweep pie, the view ring) composite through the
     * UI pipeline instead of the world shaders, which did not blend them.
     */
    public void captureVisual(PoseStack stack)
    {
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        stack.pushPose();
        MatrixStackUtils.scaleBack(stack);
        this.captureRenderMatrix(stack);
        stack.popPose();
    }

    /**
     * Draw the gizmo's visual over a viewport area in the UI pass, from the
     * model-view captured this frame ({@link #lastRenderMatrix}, set by
     * {@link #captureVisual} or {@link #renderStencil}).
     *
     * <p>It draws straight onto the main framebuffer through the UI pipeline with
     * the GL viewport set to {@code area} — the same setup the form editor's
     * model pass uses ({@link mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer}).
     * This fixes the transparency the world shaders mangled (the whole point of
     * the move) and places the gizmo correctly: the film world is itself
     * rendered into that same {@code area}, and {@code projection} maps NDC onto
     * the area, so the gizmo lines up with the model and stays inside the
     * preview (the frustum clips it to the viewport rect). It is NOT rendered
     * to an off-screen buffer and blitted, the way the pick stencil and sphere
     * highlight are: those are opaque masks, but the rotation pie is translucent,
     * and an intermediate buffer applies its alpha twice (once on draw, once on
     * blit), leaving it nearly invisible.
     *
     * <p>The projection is applied before drawing because
     * {@link #getAxesDistanceScale} reads it back from {@link RenderSystem} to
     * keep the gizmo a constant on-screen size.
     */
    public void renderInterface(UIContext context, Matrix4f projection, Area area)
    {
        if (BBSRendering.isIrisShadowPass() || !this.hasLastRenderMatrix
            || context == null || projection == null || area == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        this.setViewportScale(context.menu.height / (float) area.h);

        context.batcher.flush();

        MatrixStackUtils.cacheMatrices();
        try
        {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

            float rx = (float) Math.round(mc.getWindow().getScreenWidth() / (double) context.menu.width);
            float ry = (float) Math.round(mc.getWindow().getScreenHeight() / (double) context.menu.height);
            float size = BBSModClient.getOriginalFramebufferScale();
            int vx = (int) (area.x * rx);
            int vy = (int) (mc.getWindow().getScreenHeight() - (area.y + area.h) * ry);
            int vw = (int) (area.w * rx);
            int vh = (int) (area.h * ry);

            RenderSystem.viewport((int) (vx * size), (int) (vy * size), (int) (vw * size), (int) (vh * size));

            PoseStack stack = new PoseStack();
            MatrixStackUtils.multiply(stack, this.lastRenderMatrix);
            this.drawGizmo(stack);
        }
        finally
        {
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            MatrixStackUtils.restoreMatrices();
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        }
    }

    private void drawGizmo(PoseStack stack)
    {
        if (BBSSettings.gizmos.get())
        {
            float distanceScale = this.getAxesDistanceScale(stack);

            /* Cache the sphere's effective world radius (in
             * {@link #lastRenderMatrix}'s coordinate frame) so
             * {@link #computeScreenRadius} can report the real on-screen
             * pixel size for hover/pick distance checks. */
            this.lastSphereLocalRadius = 0.22F * BBSSettings.axesScale.get() * distanceScale;

            stack.pushPose();
            stack.scale(distanceScale, distanceScale, distanceScale);
            this.lastSphereMatrix.set(modelView(stack));
            this.hasLastSphereMatrix = true;
            this.drawOccludedGizmo(stack);
            stack.popPose();
        }
        else
        {
            float distanceScale = this.getAxesDistanceScale(stack);

            stack.pushPose();
            stack.scale(distanceScale, distanceScale, distanceScale);
            Draw.coolerAxes(stack, 0.25F, 0.008F, 0.26F, 0.018F);
            stack.popPose();
        }

        this.drawInfiniteLine(stack);
    }

    /**
     * Clear scene depth under the gizmo, then depth-sort its own handles. Every
     * global state touched here is restored even if a draw fails.
     */
    private void drawOccludedGizmo(PoseStack stack)
    {
        float opacity = BBSSettings.gizmoOpacity.get();

        try
        {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            GL11.glDepthRange(1D, 1D);
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.colorMask(false, false, false, false);
            this.drawAxes(stack, 0.25F, 0.008F);

            GL11.glDepthRange(0D, 1D);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1F, 1F, 1F, opacity);
            this.drawAxes(stack, 0.25F, 0.008F);

            RenderSystem.depthMask(false);
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.setShaderColor(1F, 1F, 1F, opacity);
            this.drawRotatePieIfActive(stack);
        }
        finally
        {
            GL11.glDepthRange(0D, 1D);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        }
    }

    private void drawRotatePieIfActive(PoseStack stack)
    {
        UIPropTransform transform = this.currentTransform;

        if (transform == null || !transform.isEditing() || transform.getMode() != Operation.ROTATE.modeOrdinal || transform.isSphereRotate())
        {
            return;
        }

        if (transform.isViewRotate())
        {
            this.drawViewPie(stack);

            return;
        }

        Axis axis = transform.getAxis();

        if (axis != null)
        {
            this.drawRotatePie(stack, axis);
        }
    }

    private float getAxesDistanceScale(PoseStack stack)
    {
        Vector3f cameraRelative = stack.last().pose().getTranslation(new Vector3f());
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        float fov = proj.m33() == 0 ? (float) (2.0 * Math.atan(1.0 / proj.m11())) : BBSSettings.getFov();

        return BBSSettings.getAxesDistanceScale(cameraRelative.length(), fov) * this.viewportScale;
    }

    private void drawInfiniteLine(PoseStack stack)
    {
        int debugIndex = this.index;

        if ((debugIndex < STENCIL_X || debugIndex > STENCIL_ZY) && this.currentTransform != null)
        {
            debugIndex = this.currentTransform.getDebugLineStencilIndex();
        }

        if (debugIndex < STENCIL_X || debugIndex > STENCIL_ZY)
        {
            return;
        }

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float size = 10000F;
        float t = 0.005F;

        if (debugIndex == STENCIL_X || debugIndex == STENCIL_XZ || debugIndex == STENCIL_XY)
        {
            Draw.fillBox(builder, stack, -size, -t, -t, size, t, t, 1F, 0F, 0F);
        }

        if (debugIndex == STENCIL_Y || debugIndex == STENCIL_XY || debugIndex == STENCIL_ZY)
        {
            Draw.fillBox(builder, stack, -t, -size, -t, t, size, t, 0F, 1F, 0F);
        }

        if (debugIndex == STENCIL_Z || debugIndex == STENCIL_XZ || debugIndex == STENCIL_ZY)
        {
            Draw.fillBox(builder, stack, -t, -t, -size, t, t, size, 0F, 0F, 1F);
        }

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    private static void fillBox(BufferBuilder builder, PoseStack stack, float x1, float y1, float z1, float x2, float y2, float z2, int color)
    {
        float a = Colors.getA(color);

        Draw.fillBox(builder, stack, x1, y1, z1, x2, y2, z2, Colors.getR(color), Colors.getG(color), Colors.getB(color), a <= 0F ? 1F : a);
    }

    private void updateVbos()
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();

        if (this.rotateRingVbo == null || scale != this.lastScale || thickness != this.lastThickness)
        {
            if (this.rotateRingVbo != null)
            {
                this.rotateRingVbo.close();
                this.rotateSphereVbo.close();
            }

            this.rotateRingVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.rotateSphereVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);

            float radius = 0.22F * scale;
            float thicknessRing = 0.02F * scale * thickness;

            PoseStack identity = new PoseStack();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            Draw.arc3D(builder, identity, Axis.Y, radius, thicknessRing, 1F, 1F, 1F, 0F, 360F);
            this.rotateRingVbo.bind();
            this.rotateRingVbo.upload(builder.buildOrThrow());

            builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            Draw.sphere(builder, identity, radius, 24, 24, 1F, 1F, 1F, 1F);
            this.rotateSphereVbo.bind();
            this.rotateSphereVbo.upload(builder.buildOrThrow());

            VertexBuffer.unbind();

            this.lastScale = scale;
            this.lastThickness = thickness;
        }
    }

    private static Matrix4f modelView(PoseStack stack)
    {
        return new Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose());
    }

    private void drawCachedRing(PoseStack stack, VertexBuffer vbo, Axis axis, int color)
    {
        float a = Colors.getA(color);

        if (a <= 0F)
        {
            a = 1F;
        }

        this.drawCachedRing(stack, vbo, axis, Colors.getR(color), Colors.getG(color), Colors.getB(color), a);
    }

    private void drawCachedRing(PoseStack stack, VertexBuffer vbo, Axis axis, float r, float g, float b, float a)
    {
        stack.pushPose();

        if (axis == Axis.X) stack.mulPose(com.mojang.math.Axis.ZP.rotation(MathUtils.PI / 2F));
        if (axis == Axis.Z) stack.mulPose(com.mojang.math.Axis.XP.rotation(MathUtils.PI / 2F));

        RenderSystem.setShaderColor(r, g, b, a);
        vbo.bind();
        vbo.drawWithShader(modelView(stack), RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        stack.popPose();
    }

    /** Compute the camera-facing arc of an axis ring in its local drawing plane. */
    private boolean visibleRingArc(PoseStack stack, Axis axis, Vector2f out)
    {
        Matrix4f matrix = stack.last().pose();
        Vector3f camera = matrix.getTranslation(new Vector3f()).negate();
        Matrix3f basis = matrix.get3x3(new Matrix3f());

        if (Math.abs(basis.determinant()) > 1.0E-8F)
        {
            basis.invert().transform(camera);
        }

        Quaternionf rotation = new Quaternionf();

        if (axis == Axis.X) rotation.rotationZ(MathUtils.PI / 2F);
        else if (axis == Axis.Z) rotation.rotationX(MathUtils.PI / 2F);

        rotation.conjugate().transform(camera);

        float length = camera.length();
        float bias = length > 1.0E-6F ? RING_FACE_ON_BIAS * camera.y * camera.y / length : 0F;
        boolean[] visible = new boolean[RING_OCCLUSION_SAMPLES];
        int count = 0;

        for (int i = 0; i < visible.length; i++)
        {
            float angle = (float) (i * 2D * Math.PI / visible.length);

            visible[i] = camera.x * (float) Math.cos(angle) + camera.z * (float) Math.sin(angle) + bias > 0F;

            if (visible[i]) count++;
        }

        if (count == 0)
        {
            return false;
        }

        if (count == visible.length)
        {
            out.set(0F, 360F);

            return true;
        }

        int hidden = 0;

        while (visible[hidden]) hidden++;

        int start = hidden;

        while (!visible[start % visible.length]) start++;

        int run = 0;

        while (visible[(start + run) % visible.length]) run++;

        float step = 360F / visible.length;

        out.set(start * step, run * step);

        return true;
    }

    private void drawOccludedRing(PoseStack stack, Axis axis, float radius, float thickness, float r, float g, float b)
    {
        Vector2f arc = new Vector2f();

        if (!this.visibleRingArc(stack, axis, arc))
        {
            return;
        }

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        Draw.arc3D(builder, stack, axis, radius, thickness, r, g, b, arc.x, arc.y);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private void drawCachedRingBillboard(PoseStack stack, VertexBuffer vbo, float r, float g, float b, float a)
    {
        stack.pushPose();

        Matrix4f matrix = stack.last().pose();
        Vector3f toCamera = matrix.getTranslation(new Vector3f()).negate();
        Matrix3f basis = matrix.get3x3(new Matrix3f());

        if (Math.abs(basis.determinant()) > 1.0E-8F)
        {
            basis.invert().transform(toCamera);
        }

        if (toCamera.lengthSquared() > 1.0E-8F)
        {
            toCamera.normalize();
            stack.mulPose(new Quaternionf().rotationTo(0F, 1F, 0F, toCamera.x, toCamera.y, toCamera.z));
        }

        stack.scale(VIEW_RING_SCALE, VIEW_RING_SCALE, VIEW_RING_SCALE);

        RenderSystem.setShaderColor(r, g, b, a);
        vbo.bind();
        vbo.drawWithShader(modelView(stack), RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        stack.popPose();
    }

    private void drawRotatePie(PoseStack stack, Axis axis)
    {
        if (this.currentTransform == null || this.currentTransform.getDrag() == null)
        {
            return;
        }

        float scale = BBSSettings.axesScale.get();
        float radius = 0.22F * scale;
        Vector3f initialVec = this.currentTransform.getInitialDragRingVec();
        Vector3f axisX = this.currentTransform.getDrag().gizmoWorldAxes.getColumn(0, new Vector3f());
        Vector3f axisY = this.currentTransform.getDrag().gizmoWorldAxes.getColumn(1, new Vector3f());
        Vector3f axisZ = this.currentTransform.getDrag().gizmoWorldAxes.getColumn(2, new Vector3f());
        Vector3f dragAxisDir = this.currentTransform.getDrag().rotateAxes.getColumn(axis.ordinal(), new Vector3f());
        float gx = initialVec.dot(axisX);
        float gy = initialVec.dot(axisY);
        float gz = initialVec.dot(axisZ);
        float px = 0F;
        float pz = 0F;
        float sweepDir = 1F;

        if (axis == Axis.Y)
        {
            px = gx;
            pz = gz;
            sweepDir = Math.signum(dragAxisDir.dot(new Vector3f(axisY).mul(-1)));
        }
        else if (axis == Axis.X)
        {
            px = gy;
            pz = gz;
            sweepDir = Math.signum(dragAxisDir.dot(axisX));
        }
        else if (axis == Axis.Z)
        {
            px = gx;
            pz = -gy;
            sweepDir = Math.signum(dragAxisDir.dot(new Vector3f(axisZ).mul(-1)));
        }

        if (sweepDir == 0F) sweepDir = 1F;

        float startDeg = MathUtils.toDeg((float) Math.atan2(pz, px));
        float sweepDeg = this.currentTransform.getAccumulatedRotateDeg() * sweepDir;

        if (this.currentTransform.isLocal())
        {
            startDeg -= sweepDeg;
        }

        stack.pushPose();

        if (axis == Axis.X) stack.mulPose(com.mojang.math.Axis.ZP.rotation(MathUtils.PI / 2F));
        if (axis == Axis.Z) stack.mulPose(com.mojang.math.Axis.XP.rotation(MathUtils.PI / 2F));

        int color = axis == Axis.X ? Colors.RED : (axis == Axis.Y ? Colors.GREEN : Colors.BLUE);
        float r = Colors.getR(color);
        float g = Colors.getG(color);
        float b = Colors.getB(color);
        float a = 0.25F;
        Matrix4f mat = stack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.disableCull();

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int segments = Math.max(12, (int) (Math.abs(sweepDeg) / 360F * 64F));
        float step = sweepDeg / segments;

        for (int i = 0; i < segments; i++)
        {
            float a1 = MathUtils.toRad(startDeg + step * i);
            float a2 = MathUtils.toRad(startDeg + step * (i + 1));
            float x1 = (float) Math.cos(a1) * radius;
            float z1 = (float) Math.sin(a1) * radius;
            float x2 = (float) Math.cos(a2) * radius;
            float z2 = (float) Math.sin(a2) * radius;

            builder.addVertex(mat, 0, 0, 0).setColor(r, g, b, a);

            if (sweepDeg > 0)
            {
                builder.addVertex(mat, x1, 0, z1).setColor(r, g, b, a);
                builder.addVertex(mat, x2, 0, z2).setColor(r, g, b, a);
            }
            else
            {
                builder.addVertex(mat, x2, 0, z2).setColor(r, g, b, a);
                builder.addVertex(mat, x1, 0, z1).setColor(r, g, b, a);
            }
        }

        BufferUploader.drawWithShader(builder.buildOrThrow());

        float lineThickness = 0.005F * scale;
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float endDeg = startDeg + sweepDeg;
        float sx = (float) Math.cos(MathUtils.toRad(startDeg)) * radius;
        float sz = (float) Math.sin(MathUtils.toRad(startDeg)) * radius;
        float ex = (float) Math.cos(MathUtils.toRad(endDeg)) * radius;
        float ez = (float) Math.sin(MathUtils.toRad(endDeg)) * radius;
        Vector3f p1 = new Vector3f(-sz, 0, sx).normalize().mul(lineThickness);

        builder.addVertex(mat, p1.x, 0, p1.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, -p1.x, 0, -p1.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, sx - p1.x, 0, sz - p1.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, p1.x, 0, p1.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, sx - p1.x, 0, sz - p1.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, sx + p1.x, 0, sz + p1.z).setColor(r, g, b, 1F);

        Vector3f p2 = new Vector3f(-ez, 0, ex).normalize().mul(lineThickness);

        builder.addVertex(mat, p2.x, 0, p2.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, -p2.x, 0, -p2.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, ex - p2.x, 0, ez - p2.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, p2.x, 0, p2.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, ex - p2.x, 0, ez - p2.z).setColor(r, g, b, 1F);
        builder.addVertex(mat, ex + p2.x, 0, ez + p2.z).setColor(r, g, b, 1F);

        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();

        stack.popPose();
    }

    /** Draw the view-ring sweep in the gizmo frame using screen right/down vectors. */
    private void drawViewPie(PoseStack stack)
    {
        float sweepRad = this.currentTransform.getViewScreenSweepRad();

        if (Math.abs(sweepRad) < 1.0E-4F)
        {
            return;
        }

        Matrix4f matrix = stack.last().pose();
        Matrix3f basis = matrix.get3x3(new Matrix3f());

        if (Math.abs(basis.determinant()) < 1.0E-8F)
        {
            return;
        }

        Matrix3f inverse = basis.invert();
        Vector3f right = inverse.transform(new Vector3f(1F, 0F, 0F)).normalize();
        Vector3f down = inverse.transform(new Vector3f(0F, -1F, 0F)).normalize();
        float startRad = this.currentTransform.getViewGrabScreenAngle();
        float scale = BBSSettings.axesScale.get();
        float radius = 0.22F * scale * VIEW_RING_SCALE;
        int color = Colors.LIGHTEST_GRAY;
        float r = Colors.getR(color);
        float g = Colors.getG(color);
        float b = Colors.getB(color);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();

        int segments = Math.max(2, (int) (Math.abs(sweepRad) / (float) (2D * Math.PI) * 64F));
        float step = sweepRad / segments;
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < segments; i++)
        {
            this.pieRim(p1, right, down, startRad + step * i, radius);
            this.pieRim(p2, right, down, startRad + step * (i + 1), radius);

            builder.addVertex(matrix, 0, 0, 0).setColor(r, g, b, 0.25F);
            builder.addVertex(matrix, p1.x, p1.y, p1.z).setColor(r, g, b, 0.25F);
            builder.addVertex(matrix, p2.x, p2.y, p2.z).setColor(r, g, b, 0.25F);
        }

        BufferUploader.drawWithShader(builder.buildOrThrow());

        float thickness = 0.005F * scale;
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        this.pieEdge(builder, matrix, right, down, startRad, radius, thickness, r, g, b);
        this.pieEdge(builder, matrix, right, down, startRad + sweepRad, radius, thickness, r, g, b);
        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void pieRim(Vector3f out, Vector3f right, Vector3f down, float angle, float radius)
    {
        float c = (float) Math.cos(angle) * radius;
        float s = (float) Math.sin(angle) * radius;

        out.set(right.x * c + down.x * s, right.y * c + down.y * s, right.z * c + down.z * s);
    }

    private void pieEdge(BufferBuilder builder, Matrix4f matrix, Vector3f right, Vector3f down, float angle, float radius, float thickness, float r, float g, float b)
    {
        Vector3f rim = new Vector3f();
        Vector3f perpendicular = new Vector3f();

        this.pieRim(rim, right, down, angle, radius);
        this.pieRim(perpendicular, right, down, angle + MathUtils.PI / 2F, thickness);

        builder.addVertex(matrix, perpendicular.x, perpendicular.y, perpendicular.z).setColor(r, g, b, 1F);
        builder.addVertex(matrix, -perpendicular.x, -perpendicular.y, -perpendicular.z).setColor(r, g, b, 1F);
        builder.addVertex(matrix, rim.x - perpendicular.x, rim.y - perpendicular.y, rim.z - perpendicular.z).setColor(r, g, b, 1F);
        builder.addVertex(matrix, perpendicular.x, perpendicular.y, perpendicular.z).setColor(r, g, b, 1F);
        builder.addVertex(matrix, rim.x - perpendicular.x, rim.y - perpendicular.y, rim.z - perpendicular.z).setColor(r, g, b, 1F);
        builder.addVertex(matrix, rim.x + perpendicular.x, rim.y + perpendicular.y, rim.z + perpendicular.z).setColor(r, g, b, 1F);
    }

    private void drawRotateHandles(PoseStack stack, Handle active)
    {
        this.updateVbos();

        if (!BBSSettings.rotateHideRings.get())
        {
            float scale = BBSSettings.axesScale.get();
            float radius = 0.22F * scale;
            float ringThickness = 0.02F * scale * BBSSettings.axesThickness.get();

            if (active == null || active == Handle.ROTATE_Z) this.drawOccludedRing(stack, Axis.Z, radius, ringThickness, Colors.getR(Colors.BLUE), Colors.getG(Colors.BLUE), Colors.getB(Colors.BLUE));
            if (active == null || active == Handle.ROTATE_X) this.drawOccludedRing(stack, Axis.X, radius, ringThickness, Colors.getR(Colors.RED), Colors.getG(Colors.RED), Colors.getB(Colors.RED));
            if (active == null || active == Handle.ROTATE_Y) this.drawOccludedRing(stack, Axis.Y, radius, ringThickness, Colors.getR(Colors.GREEN), Colors.getG(Colors.GREEN), Colors.getB(Colors.GREEN));
        }

        if (active == null || active == Handle.VIEW)
        {
            int color = Colors.LIGHTEST_GRAY;

            this.drawCachedRingBillboard(stack, this.rotateRingVbo, Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color) * BBSSettings.gizmoOpacity.get());
        }
    }

    private void drawAxes(PoseStack stack, float axisSize, float axisOffset)
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();
        Handle active = this.activeDragHandle();
        boolean showMove = this.mode.shows(Operation.MOVE) && (active == null || active.op == Operation.MOVE || active.op == Operation.SCREEN);
        boolean showScale = this.mode.shows(Operation.SCALE) && (active == null || active.op == Operation.SCALE || active.op == Operation.SCALE_ALL);
        boolean showRotate = this.mode.shows(Operation.ROTATE) && (active == null || active.op == Operation.ROTATE || active.op == Operation.VIEW || active.op == Operation.TRACKBALL);

        axisSize *= scale * this.combinedInnerScale();
        axisOffset *= scale * thickness;

        boolean building = false;
        BufferBuilder builder = null;

        if (showRotate)
        {
            this.drawRotateHandles(stack, active);
        }

        if (showMove || showScale)
        {
            builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            building = true;

            Handle barX = showMove ? Handle.MOVE_X : Handle.SCALE_X;
            Handle barY = showMove ? Handle.MOVE_Y : Handle.SCALE_Y;
            Handle barZ = showMove ? Handle.MOVE_Z : Handle.SCALE_Z;
            Handle planeXZ = showMove ? Handle.MOVE_XZ : Handle.SCALE_XZ;
            Handle planeXY = showMove ? Handle.MOVE_XY : Handle.SCALE_XY;
            Handle planeZY = showMove ? Handle.MOVE_ZY : Handle.SCALE_ZY;

            if (active == null || active == barX) fillBox(builder, stack, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, Colors.RED);
            if (active == null || active == barY) fillBox(builder, stack, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, Colors.GREEN);
            if (active == null || active == barZ) fillBox(builder, stack, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, Colors.BLUE);

            if (showMove && (active == null || active == Handle.SCREEN))
            {
                float screenHalf = SCREEN_CUBE_HALF * scale * thickness;

                fillBox(builder, stack, -screenHalf, -screenHalf, -screenHalf, screenHalf, screenHalf, screenHalf, Colors.WHITE);
            }

            if (showScale && !showMove && (active == null || active == Handle.SCALE_ALL))
            {
                float scaleAllHalf = SCREEN_CUBE_HALF * scale * thickness;

                fillBox(builder, stack, -scaleAllHalf, -scaleAllHalf, -scaleAllHalf, scaleAllHalf, scaleAllHalf, scaleAllHalf, Colors.WHITE);
            }

            float planeStart = axisSize * 0.2F;
            float planeEnd = planeStart + axisSize * 0.2F;
            float planeHalf = axisOffset * 0.5F;

            if (active == null || active == planeXZ) fillBox(builder, stack, planeStart, -planeHalf, planeStart, planeEnd, planeHalf, planeEnd, Colors.PLANE_XZ);
            if (active == null || active == planeXY) fillBox(builder, stack, planeStart, planeStart, -planeHalf, planeEnd, planeEnd, planeHalf, Colors.PLANE_XY);
            if (active == null || active == planeZY) fillBox(builder, stack, -planeHalf, planeStart, planeStart, planeHalf, planeEnd, planeEnd, Colors.PLANE_ZY);

            if (showScale)
            {
                float cubeHalf = SCALE_CUBE_HALF * scale * thickness;

                if (active == null || active == Handle.SCALE_X) fillBox(builder, stack, axisSize - cubeHalf, -cubeHalf, -cubeHalf, axisSize + cubeHalf, cubeHalf, cubeHalf, Colors.RED);
                if (active == null || active == Handle.SCALE_Y) fillBox(builder, stack, -cubeHalf, axisSize - cubeHalf, -cubeHalf, cubeHalf, axisSize + cubeHalf, cubeHalf, Colors.GREEN);
                if (active == null || active == Handle.SCALE_Z) fillBox(builder, stack, -cubeHalf, -cubeHalf, axisSize - cubeHalf, cubeHalf, cubeHalf, axisSize + cubeHalf, Colors.BLUE);
            }
        }

        if (active == null && (showMove || showScale || showRotate))
        {
            if (!building)
            {
                builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                building = true;
            }

            fillBox(builder, stack, -axisOffset, -axisOffset, -axisOffset, axisOffset, axisOffset, axisOffset, Colors.WHITE);
        }

        if (building)
        {
            RenderSystem.setShaderColor(1F, 1F, 1F, BBSSettings.gizmoOpacity.get());
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }
    }

    public void renderStencil(PoseStack stack, StencilMap map)
    {
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        if (BBSSettings.gizmos.get())
        {
            stack.pushPose();
            MatrixStackUtils.scaleBack(stack);
            this.captureRenderMatrix(stack);
            this.drawStencilAxes(stack, map);
            stack.popPose();
        }
    }

    /**
     * Draw the gizmo handles as stencil IDs into the currently bound picking
     * framebuffer, from a stack already positioned at the gizmo origin. Shared by
     * the world-pass {@link #renderStencil} and the UI-pass
     * {@link #renderStencilInterface}.
     */
    private void drawStencilAxes(PoseStack stack, StencilMap map)
    {
        float distanceScale = this.getAxesDistanceScale(stack);

        stack.pushPose();
        stack.scale(distanceScale, distanceScale, distanceScale);
        this.drawAxes(stack, map, 0.25F, 0.008F);
        stack.popPose();
    }

    /**
     * Draw the gizmo's pick stencil over a viewport area in the UI pass, from
     * the model-view captured this frame ({@link #lastRenderMatrix}, set by
     * {@link #captureVisual}). This is the stencil counterpart of
     * {@link #renderInterface}: it uses the identical viewport / projection /
     * matrix setup, so the handle IDs land on exactly the pixels the visual
     * draws and picking lines up with what the user sees, instead of being
     * rendered in the world pass on a separate frame of reference.
     *
     * <p>The caller binds the picking framebuffer before this call (and reads it
     * back / unbinds afterwards); it must also flush the UI batcher first, since
     * this does not (the bound framebuffer is the pick buffer, not the screen).
     */
    public void renderStencilInterface(UIContext context, Matrix4f projection, Area area, StencilMap map)
    {
        if (BBSRendering.isIrisShadowPass() || !this.hasLastRenderMatrix
            || context == null || projection == null || area == null || !BBSSettings.gizmos.get())
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        this.setViewportScale(context.menu.height / (float) area.h);

        MatrixStackUtils.cacheMatrices();
        try
        {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

            float rx = (float) Math.round(mc.getWindow().getScreenWidth() / (double) context.menu.width);
            float ry = (float) Math.round(mc.getWindow().getScreenHeight() / (double) context.menu.height);
            float size = BBSModClient.getOriginalFramebufferScale();
            int vx = (int) (area.x * rx);
            int vy = (int) (mc.getWindow().getScreenHeight() - (area.y + area.h) * ry);
            int vw = (int) (area.w * rx);
            int vh = (int) (area.h * ry);

            RenderSystem.viewport((int) (vx * size), (int) (vy * size), (int) (vw * size), (int) (vh * size));

            PoseStack stack = new PoseStack();
            MatrixStackUtils.multiply(stack, this.lastRenderMatrix);
            this.drawStencilAxes(stack, map);
        }
        finally
        {
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            MatrixStackUtils.restoreMatrices();
        }
    }

    private void drawAxes(PoseStack stack, StencilMap map, float axisSize, float axisOffset)
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();
        Handle active = this.activeDragHandle();
        boolean showMove = this.mode.shows(Operation.MOVE) && (active == null || active.op == Operation.MOVE || active.op == Operation.SCREEN);
        boolean showScale = this.mode.shows(Operation.SCALE) && (active == null || active.op == Operation.SCALE || active.op == Operation.SCALE_ALL);
        boolean showRotate = this.mode.shows(Operation.ROTATE) && (active == null || active.op == Operation.ROTATE || active.op == Operation.VIEW || active.op == Operation.TRACKBALL);
        axisSize *= scale * this.combinedInnerScale();
        axisOffset *= scale * thickness;

        RenderSystem.disableDepthTest();
        try
        {
            if (showRotate)
            {
                this.updateVbos();

                if (!BBSSettings.rotateHideRings.get())
                {
                    float radius = 0.22F * scale;
                    float ringThickness = 0.02F * scale * thickness;

                    if (active == null || active == Handle.ROTATE_Z) this.drawOccludedRing(stack, Axis.Z, radius, ringThickness, STENCIL_ROTATE_Z / 255F, 0F, 0F);
                    if (active == null || active == Handle.ROTATE_X) this.drawOccludedRing(stack, Axis.X, radius, ringThickness, STENCIL_ROTATE_X / 255F, 0F, 0F);
                    if (active == null || active == Handle.ROTATE_Y) this.drawOccludedRing(stack, Axis.Y, radius, ringThickness, STENCIL_ROTATE_Y / 255F, 0F, 0F);
                }

                if (active == null || active == Handle.VIEW)
                {
                    this.drawCachedRingBillboard(stack, this.rotateRingVbo, STENCIL_VIEW / 255F, 0F, 0F, 1F);
                }
            }

            if (showMove || showScale)
            {
                Handle barX = showMove ? Handle.MOVE_X : Handle.SCALE_X;
                Handle barY = showMove ? Handle.MOVE_Y : Handle.SCALE_Y;
                Handle barZ = showMove ? Handle.MOVE_Z : Handle.SCALE_Z;
                Handle planeXZ = showMove ? Handle.MOVE_XZ : Handle.SCALE_XZ;
                Handle planeXY = showMove ? Handle.MOVE_XY : Handle.SCALE_XY;
                Handle planeZY = showMove ? Handle.MOVE_ZY : Handle.SCALE_ZY;
                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

                if (active == null || active == barX) Draw.fillBox(builder, stack, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, barX.index / 255F, 0F, 0F);
                if (active == null || active == barY) Draw.fillBox(builder, stack, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, barY.index / 255F, 0F, 0F);
                if (active == null || active == barZ) Draw.fillBox(builder, stack, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, barZ.index / 255F, 0F, 0F);
                Draw.fillBox(builder, stack, -axisOffset, -axisOffset, -axisOffset, axisOffset, axisOffset, axisOffset, 0F, 0F, 0F);

                if (showMove && (active == null || active == Handle.SCREEN))
                {
                    float screenHalf = SCREEN_CUBE_HALF * scale * thickness;

                    Draw.fillBox(builder, stack, -screenHalf, -screenHalf, -screenHalf, screenHalf, screenHalf, screenHalf, STENCIL_SCREEN / 255F, 0F, 0F);
                }

                if (showScale && !showMove && (active == null || active == Handle.SCALE_ALL))
                {
                    float scaleAllHalf = SCREEN_CUBE_HALF * scale * thickness;

                    Draw.fillBox(builder, stack, -scaleAllHalf, -scaleAllHalf, -scaleAllHalf, scaleAllHalf, scaleAllHalf, scaleAllHalf, STENCIL_SCALE_ALL / 255F, 0F, 0F);
                }

                float planeStart = axisSize * 0.2F;
                float planeEnd = planeStart + axisSize * 0.2F;
                float planeHalf = axisOffset * 0.5F;

                if (active == null || active == planeXZ) Draw.fillBox(builder, stack, planeStart, -planeHalf, planeStart, planeEnd, planeHalf, planeEnd, planeXZ.index / 255F, 0F, 0F);
                if (active == null || active == planeXY) Draw.fillBox(builder, stack, planeStart, planeStart, -planeHalf, planeEnd, planeEnd, planeHalf, planeXY.index / 255F, 0F, 0F);
                if (active == null || active == planeZY) Draw.fillBox(builder, stack, -planeHalf, planeStart, planeStart, planeHalf, planeEnd, planeEnd, planeZY.index / 255F, 0F, 0F);

                if (showScale)
                {
                    float cubeHalf = SCALE_CUBE_HALF * scale * thickness;

                    if (active == null || active == Handle.SCALE_X) Draw.fillBox(builder, stack, axisSize - cubeHalf, -cubeHalf, -cubeHalf, axisSize + cubeHalf, cubeHalf, cubeHalf, STENCIL_SCALE_X / 255F, 0F, 0F);
                    if (active == null || active == Handle.SCALE_Y) Draw.fillBox(builder, stack, -cubeHalf, axisSize - cubeHalf, -cubeHalf, cubeHalf, axisSize + cubeHalf, cubeHalf, STENCIL_SCALE_Y / 255F, 0F, 0F);
                    if (active == null || active == Handle.SCALE_Z) Draw.fillBox(builder, stack, -cubeHalf, -cubeHalf, axisSize - cubeHalf, cubeHalf, cubeHalf, axisSize + cubeHalf, STENCIL_SCALE_Z / 255F, 0F, 0F);
                }

                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                BufferUploader.drawWithShader(builder.buildOrThrow());
            }
        }
        finally
        {
            RenderSystem.enableDepthTest();
        }
    }

    public static enum Mode
    {
        TRANSLATE, SCALE, ROTATE, COMBINED;

        public boolean shows(Operation operation)
        {
            return switch (this)
            {
                case TRANSLATE -> operation == Operation.MOVE || operation == Operation.SCREEN;
                case SCALE -> operation == Operation.SCALE;
                case ROTATE -> operation == Operation.ROTATE || operation == Operation.VIEW || operation == Operation.TRACKBALL;
                case COMBINED -> operation == Operation.MOVE || operation == Operation.SCALE || operation == Operation.ROTATE || operation == Operation.VIEW || operation == Operation.SCREEN || operation == Operation.TRACKBALL;
            };
        }
    }

    public static enum Operation
    {
        MOVE(0), SCALE(1), SCALE_ALL(1), ROTATE(2), VIEW(2), TRACKBALL(2), SCREEN(0);

        public final int modeOrdinal;

        Operation(int modeOrdinal)
        {
            this.modeOrdinal = modeOrdinal;
        }
    }

    public static enum Handle
    {
        MOVE_X(STENCIL_X, Operation.MOVE, Axis.X, null),
        MOVE_Y(STENCIL_Y, Operation.MOVE, Axis.Y, null),
        MOVE_Z(STENCIL_Z, Operation.MOVE, Axis.Z, null),
        MOVE_XZ(STENCIL_XZ, Operation.MOVE, Axis.X, Axis.Z),
        MOVE_XY(STENCIL_XY, Operation.MOVE, Axis.X, Axis.Y),
        MOVE_ZY(STENCIL_ZY, Operation.MOVE, Axis.Z, Axis.Y),
        SCALE_X(STENCIL_SCALE_X, Operation.SCALE, Axis.X, null),
        SCALE_Y(STENCIL_SCALE_Y, Operation.SCALE, Axis.Y, null),
        SCALE_Z(STENCIL_SCALE_Z, Operation.SCALE, Axis.Z, null),
        SCALE_XZ(STENCIL_SCALE_XZ, Operation.SCALE, Axis.X, Axis.Z),
        SCALE_XY(STENCIL_SCALE_XY, Operation.SCALE, Axis.X, Axis.Y),
        SCALE_ZY(STENCIL_SCALE_ZY, Operation.SCALE, Axis.Z, Axis.Y),
        ROTATE_X(STENCIL_ROTATE_X, Operation.ROTATE, Axis.X, null),
        ROTATE_Y(STENCIL_ROTATE_Y, Operation.ROTATE, Axis.Y, null),
        ROTATE_Z(STENCIL_ROTATE_Z, Operation.ROTATE, Axis.Z, null),
        TRACKBALL(STENCIL_TRACKBALL, Operation.TRACKBALL, null, null),
        VIEW(STENCIL_VIEW, Operation.VIEW, null, null),
        SCREEN(STENCIL_SCREEN, Operation.SCREEN, null, null),
        SCALE_ALL(STENCIL_SCALE_ALL, Operation.SCALE_ALL, null, null);

        public final int index;
        public final Operation op;
        public final Axis axis;
        public final Axis axis2;

        Handle(int index, Operation op, Axis axis, Axis axis2)
        {
            this.index = index;
            this.op = op;
            this.axis = axis;
            this.axis2 = axis2;
        }

        public static Handle byIndex(int index)
        {
            for (Handle handle : values())
            {
                if (handle.index == index)
                {
                    return handle;
                }
            }

            return null;
        }
    }

    private void captureRenderMatrix(PoseStack stack)
    {
        if (this.dragContext.viewportW <= 0 || this.dragContext.viewportH <= 0)
        {
            this.dragContext.ready = false;
            this.hasLastRenderMatrix = false;
            this.hasLastSphereMatrix = false;

            return;
        }

        this.dragContext.modelView.set(modelView(stack));
        this.dragContext.projection.set(RenderSystem.getProjectionMatrix());
        this.dragContext.ready = true;
        this.lastRenderMatrix.set(this.dragContext.modelView);
        this.hasLastRenderMatrix = true;
    }

    private float combinedInnerScale()
    {
        return this.mode == Mode.COMBINED && !BBSSettings.rotateHideRings.get() ? COMBINED_INNER_SCALE : 1F;
    }

    private Handle activeDragHandle()
    {
        UIPropTransform transform = this.currentTransform;

        if (!BBSSettings.hideInactiveHandles.get() || transform == null || !transform.isEditing())
        {
            return null;
        }

        int operation = transform.getMode();
        Axis axis = transform.getAxis();

        if (operation == Operation.ROTATE.modeOrdinal)
        {
            if (transform.isSphereRotate()) return Handle.TRACKBALL;
            if (transform.isViewRotate()) return Handle.VIEW;
            if (axis == Axis.X) return Handle.ROTATE_X;
            if (axis == Axis.Y) return Handle.ROTATE_Y;
            if (axis == Axis.Z) return Handle.ROTATE_Z;

            return null;
        }

        if (operation == Operation.MOVE.modeOrdinal && transform.isScreenTranslate())
        {
            return Handle.SCREEN;
        }

        if (operation == Operation.SCALE.modeOrdinal && transform.isScaleAll())
        {
            return Handle.SCALE_ALL;
        }

        Operation handleOperation = operation == Operation.SCALE.modeOrdinal ? Operation.SCALE : Operation.MOVE;
        Axis axis2 = transform.getAxis2();

        for (Handle handle : Handle.values())
        {
            if (handle.op != handleOperation)
            {
                continue;
            }

            boolean matches = axis2 == null
                ? handle.axis == axis && handle.axis2 == null
                : (handle.axis == axis && handle.axis2 == axis2) || (handle.axis == axis2 && handle.axis2 == axis);

            if (matches)
            {
                return handle;
            }
        }

        return null;
    }
}
