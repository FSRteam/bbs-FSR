package mchorse.bbs_mod.ui.utils;

import com.mojang.blaze3d.systems.RenderSystem;
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
    public final static int STENCIL_MAX = STENCIL_SCREEN;
    private final static float VIEW_RING_SCALE = 1.2F;
    private final static float COMBINED_INNER_SCALE = 0.6F;
    private final static float SCALE_CUBE_HALF = 0.032F;
    private final static float SCREEN_CUBE_HALF = 0.016F;

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
    private VertexBuffer rotateStencilRingVbo;
    private VertexBuffer rotateSphereVbo;
    private float lastScale = -1F;
    private float lastThickness = -1F;
    private float lastSphereLocalRadius;
    private final Matrix4f lastSphereMatrix = new Matrix4f();
    private boolean hasLastSphereMatrix;
    private final StencilFormFramebuffer sphereHighlight = new StencilFormFramebuffer();
    private boolean sphereHovered;

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

    public void setSphereHovered(boolean hovered)
    {
        this.sphereHovered = hovered;
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
            || !UIBaseMenu.renderAxes || this.rotateSphereVbo == null || projection == null || area == null)
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

        if (BBSSettings.gizmos.get())
        {
            float distanceScale = this.getAxesDistanceScale(stack);

            this.lastSphereLocalRadius = 0.22F * BBSSettings.axesScale.get() * distanceScale;

            stack.pushPose();
            stack.scale(distanceScale, distanceScale, distanceScale);
            this.lastSphereMatrix.set(modelView(stack));
            this.hasLastSphereMatrix = true;
            this.drawAxes(stack, 0.25F, 0.008F);
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
        stack.popPose();
    }

    private float getAxesDistanceScale(PoseStack stack)
    {
        Vector3f cameraRelative = stack.last().pose().getTranslation(new Vector3f());
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        float fov = proj.m33() == 0 ? (float) (2.0 * Math.atan(1.0 / proj.m11())) : BBSSettings.getFov();

        return BBSSettings.getAxesDistanceScale(cameraRelative.length(), fov);
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
                this.rotateStencilRingVbo.close();
                this.rotateSphereVbo.close();
            }

            this.rotateRingVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.rotateStencilRingVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.rotateSphereVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);

            float radius = 0.22F * scale;
            float thicknessRing = 0.02F * scale * thickness;
            float outlinePad = 0.015F * scale * thickness;
            float thicknessStencil = 0.05F * scale * thickness + outlinePad;

            PoseStack identity = new PoseStack();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            Draw.arc3D(builder, identity, Axis.Y, radius, thicknessRing, 1F, 1F, 1F, 0F, 360F);
            this.rotateRingVbo.bind();
            this.rotateRingVbo.upload(builder.buildOrThrow());

            builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            Draw.arc3D(builder, identity, Axis.Y, radius, thicknessStencil, 1F, 1F, 1F, 0F, 360F);
            this.rotateStencilRingVbo.bind();
            this.rotateStencilRingVbo.upload(builder.buildOrThrow());

            builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            Draw.sphere(builder, identity, radius, 24, 24, 1F, 1F, 1F, 1F);
            this.rotateSphereVbo.bind();
            this.rotateSphereVbo.upload(builder.buildOrThrow());

            VertexBuffer.unbind();

            this.lastScale = scale;
            this.lastThickness = thickness;
        }
    }

    private void drawCachedSphere(PoseStack stack, VertexBuffer vbo, float r, float g, float b, float a)
    {
        RenderSystem.setShaderColor(r, g, b, a);
        vbo.bind();
        vbo.drawWithShader(modelView(stack), RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
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

    private void drawRotateHandles(PoseStack stack, boolean editing, int activeOp)
    {
        this.updateVbos();

        boolean rotating = editing && activeOp == Operation.ROTATE.modeOrdinal;
        Axis activeAxis = rotating ? this.currentTransform.getAxis() : null;
        boolean trackball = rotating && this.currentTransform.isTrackball();
        boolean viewActive = rotating && this.currentTransform.isViewRotate();

        if (this.hasSphere() && BBSSettings.rotate3dSphere.get() && (!rotating || trackball))
        {
            int color = this.sphereHovered ? BBSSettings.stencilHighlightColor.get() : BBSSettings.rotate3dSphereColor.get();

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            this.drawCachedSphere(stack, this.rotateSphereVbo, Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
            RenderSystem.disableBlend();
        }

        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        if (!BBSSettings.rotateHideRings.get())
        {
            if (!rotating || activeAxis == Axis.Z) this.drawCachedRing(stack, this.rotateRingVbo, Axis.Z, Colors.BLUE);
            if (!rotating || activeAxis == Axis.X) this.drawCachedRing(stack, this.rotateRingVbo, Axis.X, Colors.RED);
            if (!rotating || activeAxis == Axis.Y) this.drawCachedRing(stack, this.rotateRingVbo, Axis.Y, Colors.GREEN);
        }

        if (!rotating || viewActive)
        {
            int color = Colors.LIGHTEST_GRAY;

            this.drawCachedRingBillboard(stack, this.rotateRingVbo, Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
        }

        if (rotating && activeAxis != null)
        {
            this.drawRotatePie(stack, activeAxis);
        }

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    private void drawAxes(PoseStack stack, float axisSize, float axisOffset)
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();
        boolean editing = this.currentTransform != null && this.currentTransform.isEditing();
        int activeMode = editing ? this.currentTransform.getMode() : -1;
        boolean showMove = this.mode.shows(Operation.MOVE) && (!editing || activeMode == Operation.MOVE.modeOrdinal);
        boolean showScale = this.mode.shows(Operation.SCALE) && (!editing || activeMode == Operation.SCALE.modeOrdinal);
        boolean showRotate = this.mode.shows(Operation.ROTATE) && (!editing || activeMode == Operation.ROTATE.modeOrdinal);

        axisSize *= scale * this.combinedInnerScale();
        axisOffset *= scale * thickness;

        boolean building = false;
        BufferBuilder builder = null;

        if (showRotate)
        {
            this.drawRotateHandles(stack, editing, activeMode);
        }

        if (showMove || showScale)
        {
            builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            building = true;

            fillBox(builder, stack, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, Colors.RED);
            fillBox(builder, stack, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, Colors.GREEN);
            fillBox(builder, stack, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, Colors.BLUE);

            if (showMove)
            {
                float screenHalf = SCREEN_CUBE_HALF * scale * thickness;

                fillBox(builder, stack, -screenHalf, -screenHalf, -screenHalf, screenHalf, screenHalf, screenHalf, Colors.WHITE);
            }

            float planeStart = axisSize * 0.2F;
            float planeEnd = planeStart + axisSize * 0.4F * thickness;
            float planeHalf = axisOffset * 0.5F;

            fillBox(builder, stack, planeStart, -planeHalf, planeStart, planeEnd, planeHalf, planeEnd, Colors.PLANE_XZ);
            fillBox(builder, stack, planeStart, planeStart, -planeHalf, planeEnd, planeEnd, planeHalf, Colors.PLANE_XY);
            fillBox(builder, stack, -planeHalf, planeStart, planeStart, planeHalf, planeEnd, planeEnd, Colors.PLANE_ZY);

            if (showScale)
            {
                float cubeHalf = SCALE_CUBE_HALF * scale * thickness;

                fillBox(builder, stack, axisSize - cubeHalf, -cubeHalf, -cubeHalf, axisSize + cubeHalf, cubeHalf, cubeHalf, Colors.RED);
                fillBox(builder, stack, -cubeHalf, axisSize - cubeHalf, -cubeHalf, cubeHalf, axisSize + cubeHalf, cubeHalf, Colors.GREEN);
                fillBox(builder, stack, -cubeHalf, -cubeHalf, axisSize - cubeHalf, cubeHalf, cubeHalf, axisSize + cubeHalf, Colors.BLUE);
            }
        }

        if ((showMove || showScale) || (showRotate && !editing))
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
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.depthFunc(GL11.GL_ALWAYS);

            BufferUploader.drawWithShader(builder.buildOrThrow());

            RenderSystem.depthFunc(GL11.GL_LEQUAL);
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

            float distanceScale = this.getAxesDistanceScale(stack);

            stack.pushPose();
            stack.scale(distanceScale, distanceScale, distanceScale);
            this.drawAxes(stack, map, 0.25F, 0.025F);
            stack.popPose();
            stack.popPose();
        }
    }

    private void drawAxes(PoseStack stack, StencilMap map, float axisSize, float axisOffset)
    {
        float scale = BBSSettings.axesScale.get();
        float thickness = BBSSettings.axesThickness.get();
        boolean editing = this.currentTransform != null && this.currentTransform.isEditing();
        int activeMode = editing ? this.currentTransform.getMode() : -1;
        boolean showMove = this.mode.shows(Operation.MOVE) && (!editing || activeMode == Operation.MOVE.modeOrdinal);
        boolean showScale = this.mode.shows(Operation.SCALE) && (!editing || activeMode == Operation.SCALE.modeOrdinal);
        boolean showRotate = this.mode.shows(Operation.ROTATE) && (!editing || activeMode == Operation.ROTATE.modeOrdinal);
        axisSize *= scale * this.combinedInnerScale();
        axisOffset *= scale * thickness;

        RenderSystem.disableDepthTest();

        if (showRotate)
        {
            this.updateVbos();

            boolean rotating = editing && activeMode == Operation.ROTATE.modeOrdinal;
            Axis activeAxis = rotating ? this.currentTransform.getAxis() : null;
            boolean viewActive = rotating && this.currentTransform.isViewRotate();

            if (!BBSSettings.rotateHideRings.get())
            {
                if (!rotating || activeAxis == Axis.Z) this.drawCachedRing(stack, this.rotateStencilRingVbo, Axis.Z, STENCIL_ROTATE_Z / 255F, 0F, 0F, 1F);
                if (!rotating || activeAxis == Axis.X) this.drawCachedRing(stack, this.rotateStencilRingVbo, Axis.X, STENCIL_ROTATE_X / 255F, 0F, 0F, 1F);
                if (!rotating || activeAxis == Axis.Y) this.drawCachedRing(stack, this.rotateStencilRingVbo, Axis.Y, STENCIL_ROTATE_Y / 255F, 0F, 0F, 1F);
            }

            if (!rotating || viewActive)
            {
                this.drawCachedRingBillboard(stack, this.rotateStencilRingVbo, STENCIL_VIEW / 255F, 0F, 0F, 1F);
            }
        }

        if (showMove || showScale)
        {
            int barX = showMove ? STENCIL_X : STENCIL_SCALE_X;
            int barY = showMove ? STENCIL_Y : STENCIL_SCALE_Y;
            int barZ = showMove ? STENCIL_Z : STENCIL_SCALE_Z;
            int planeXZ = showMove ? STENCIL_XZ : STENCIL_SCALE_XZ;
            int planeXY = showMove ? STENCIL_XY : STENCIL_SCALE_XY;
            int planeZY = showMove ? STENCIL_ZY : STENCIL_SCALE_ZY;
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            Draw.fillBox(builder, stack, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, barX / 255F, 0F, 0F);
            Draw.fillBox(builder, stack, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, barY / 255F, 0F, 0F);
            Draw.fillBox(builder, stack, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, barZ / 255F, 0F, 0F);
            Draw.fillBox(builder, stack, -axisOffset, -axisOffset, -axisOffset, axisOffset, axisOffset, axisOffset, 0F, 0F, 0F);

            if (showMove)
            {
                float screenHalf = SCREEN_CUBE_HALF * scale * thickness;

                Draw.fillBox(builder, stack, -screenHalf, -screenHalf, -screenHalf, screenHalf, screenHalf, screenHalf, STENCIL_SCREEN / 255F, 0F, 0F);
            }

            float planeStart = axisSize * 0.2F;
            float planeEnd = planeStart + axisSize * 0.4F * thickness;
            float planeHalf = axisOffset * 0.5F;

            Draw.fillBox(builder, stack, planeStart, -planeHalf, planeStart, planeEnd, planeHalf, planeEnd, planeXZ / 255F, 0F, 0F);
            Draw.fillBox(builder, stack, planeStart, planeStart, -planeHalf, planeEnd, planeEnd, planeHalf, planeXY / 255F, 0F, 0F);
            Draw.fillBox(builder, stack, -planeHalf, planeStart, planeStart, planeHalf, planeEnd, planeEnd, planeZY / 255F, 0F, 0F);

            if (showScale)
            {
                float cubeHalf = SCALE_CUBE_HALF * scale * thickness;

                Draw.fillBox(builder, stack, axisSize - cubeHalf, -cubeHalf, -cubeHalf, axisSize + cubeHalf, cubeHalf, cubeHalf, STENCIL_SCALE_X / 255F, 0F, 0F);
                Draw.fillBox(builder, stack, -cubeHalf, axisSize - cubeHalf, -cubeHalf, cubeHalf, axisSize + cubeHalf, cubeHalf, STENCIL_SCALE_Y / 255F, 0F, 0F);
                Draw.fillBox(builder, stack, -cubeHalf, -cubeHalf, axisSize - cubeHalf, cubeHalf, cubeHalf, axisSize + cubeHalf, STENCIL_SCALE_Z / 255F, 0F, 0F);
            }

            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        }

        RenderSystem.enableDepthTest();
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
        MOVE(0), SCALE(1), ROTATE(2), VIEW(2), TRACKBALL(2), SCREEN(0);

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
        SCREEN(STENCIL_SCREEN, Operation.SCREEN, null, null);

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
}
