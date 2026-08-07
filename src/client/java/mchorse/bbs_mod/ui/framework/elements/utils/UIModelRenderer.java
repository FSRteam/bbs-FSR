package mchorse.bbs_mod.ui.framework.elements.utils;

import mchorse.bbs_mod.graphics.InverseView;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.client.render.surface.BBSFormPreviewCapture;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.Factor;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Intersectiond;
import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Model renderer GUI element
 *
 * This base class can be used for full screen model viewer.
 */
public abstract class UIModelRenderer extends UIElement
{
    private static Vector3d vec = new Vector3d();
    private static Matrix3d mat = new Matrix3d();

    protected IEntity entity = new StubEntity();

    protected int timer;
    protected int dragging;
    private final MouseGestureOwnership dragOwnership = new MouseGestureOwnership();
    private long dragGeneration;

    public Camera camera = new Camera();

    public Vector3f pos = new Vector3f();
    public Factor distance = new Factor(0, 0, 100, (x) -> Math.pow(x, 2) / 100D);
    public boolean grid = true;

    private Vector3d cachedPlaneIntersection = new Vector3d();
    private Vector3f cachedPos = new Vector3f();
    private Camera cachedCamera = new Camera();
    private Vector3d plane = new Vector3d();
    private float lastX;
    private float lastY;

    private long tick;
    private Matrix4f transform = new Matrix4f();
    private final Vector3f lightA = new Vector3f();
    private final Vector3f lightB = new Vector3f();
    private final Matrix3f lightMatrix = new Matrix3f();

    public UIModelRenderer()
    {
        super();

        this.reset();
    }

    public void setTransform(Matrix4f transform)
    {
        this.transform = transform;
    }

    /**
     * The orthonormal axes of the frame the preview is actually drawn in &mdash;
     * what {@link mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace#GLOBAL}
     * means for anything rendered here. {@link #transform} is multiplied onto the
     * stack before the model AND before the grid ({@link #renderModel}), so it is
     * that frame, not the camera's, that reads as "the world" to the user: the
     * floor grid turns with it. Identity in a plain preview, so GLOBAL stays the
     * flat scene axes there; the model block's immersive editing sets it to the
     * BLOCK's own transform, and then GLOBAL follows the block the way the film's
     * follows the replay. Scale is divided out &mdash; these are directions, and
     * a scaled block must not stretch the gizmo's frame.
     */
    public Matrix3f getSceneAxes()
    {
        return MatrixStackUtils.stripScale(this.transform).get3x3(new Matrix3f());
    }

    /**
     * Lift a matrix out of the previewed form's own frame into the frame the
     * preview is drawn in &mdash; i.e. apply {@link #transform}, exactly as
     * {@link #renderModel} does before the model reaches the stack.
     *
     * <p>The gizmo needs this because its two halves are recovered from
     * different places: the drawn origin and axes come back out of the RENDER
     * matrix (so they already carry the transform), while the drag's Jacobian
     * and rotation axes are SAMPLED from the editor's own bone matrices (which
     * do not). With a plain preview the transform is the identity and the two
     * agree by accident; inside a rotated model block they would disagree by
     * the block's rotation, and every drag would run off the handles. Scale is
     * deliberately kept &mdash; a Jacobian must map local units to the scene's
     * real distances.
     */
    public Matrix4f toSceneMatrix(Matrix4f matrix)
    {
        return new Matrix4f(this.transform).mul(matrix);
    }

    public void setRotation(float yaw, float pitch)
    {
        this.camera.rotation.y = MathUtils.toRad(yaw);
        this.camera.rotation.x = MathUtils.toRad(pitch);
    }

    public void setPosition(float x, float y, float z)
    {
        this.pos.set(x, y, z);
    }

    public void setDistance(int distanceX)
    {
        this.distance.setX(distanceX);
    }

    public void setEntity(IEntity entity)
    {
        this.entity = entity;
    }

    public IEntity getEntity()
    {
        return this.entity;
    }

    public void reset()
    {
        this.setDistance(15);
        this.setPosition(0, 1, 0);
        this.setRotation(0, 0);
    }

    public boolean isDragging()
    {
        return this.dragging != 0;
    }

    public boolean isDraggingPosition()
    {
        return this.dragging == 2;
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (!this.isDragging() && this.area.isInside(context) && (context.mouseButton == 0 || context.mouseButton == 2))
        {
            long generation = this.dragOwnership.acquireToken(context.mouseButton);

            if (generation == 0L)
            {
                return false;
            }

            this.dragGeneration = generation;

            try
            {
                this.dragging = Window.isShiftPressed() || context.mouseButton == 2 ? 2 : 1;
                this.lastX = context.mouseX;
                this.lastY = context.mouseY;

                this.cachedPos.set(this.pos);
                this.cachedCamera.copy(this.camera);
                this.plane.set(0, 0, 1);
                this.rotateVector(this.plane);

                this.cachedPlaneIntersection = this.calculateOnPlane(context);

                return true;
            }
            catch (RuntimeException | Error exception)
            {
                this.dragOwnership.release(context.mouseButton, generation);
                this.dragGeneration = 0L;
                this.dragging = 0;

                throw exception;
            }
        }

        return false;
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        if (this.area.isInside(context) && !this.isDragging())
        {
            int x = Integer.compare(-(int) context.mouseWheel, 0);

            if (Window.isCtrlPressed())
            {
                x *= 8;
            }

            this.distance.setX(this.distance.getX() + x);

            return true;
        }

        return super.subMouseScrolled(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (this.dragOwnership.release(context.mouseButton, this.dragGeneration))
        {
            this.dragGeneration = 0L;
            this.dragging = 0;

            return true;
        }

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        if (this.dragOwnership.release(context.mouseButton, this.dragGeneration))
        {
            this.dragGeneration = 0L;
            this.dragging = 0;
        }

        super.subMouseCanceled(context);
    }

    @Override
    public void render(UIContext context)
    {
        this.updateLogic(context);

        context.batcher.clip(this.area, context);
        this.renderModel(context);
        BBSFormPreviewCapture.include(context, this.area.x, this.area.y, this.area.ex(), this.area.ey());
        context.batcher.unclip(context);

        super.render(context);
    }

    private void updateLogic(UIContext context)
    {
        long tick = context.getTick();
        long i = tick - this.tick;

        if (i > 10)
        {
            i = 10;
        }

        while (i > 0)
        {
            this.update();
            i --;
        }

        this.tick = tick;
    }

    /**
     * Update logic
     */
    protected void update()
    {
        this.timer += 1;
        this.entity.setAge(this.timer);
    }

    /**
     * Draw currently edited model
     */
    private void renderModel(UIContext context)
    {
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        this.setupPosition();
        this.setupViewport(context);

        PoseStack stack = context.render.batcher.getContext().pose();

        /* Cache the global stuff */
        MatrixStackUtils.cacheMatrices();

        RenderSystem.setProjectionMatrix(this.camera.projection, VertexSorting.DISTANCE_TO_ORIGIN);
        InverseView.set(new Matrix3f(this.camera.view).invert());

        /* Rendering begins... */
        stack.pushPose();
        MatrixStackUtils.multiply(stack, this.camera.view);
        stack.translate(-this.camera.position.x, -this.camera.position.y, -this.camera.position.z);
        MatrixStackUtils.multiply(stack, this.transform);

        this.lightA.set(0F, 0.85F, -1F).normalize();
        this.lightB.set(0F, 0.85F, 1F).normalize();
        this.lightMatrix.set(this.camera.view);
        this.lightMatrix.transform(this.lightA);
        this.lightMatrix.transform(this.lightB);

        RenderSystem.setupLevelDiffuseLighting(this.lightA, this.lightB);

        if (this.grid)
        {
            this.renderGrid(context);
        }

        this.renderUserModel(context);

        Lighting.setupForFlatItems();

        stack.popPose();

        /* Return back to orthographic projection */
        Minecraft mc = Minecraft.getInstance();

        RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        MatrixStackUtils.restoreMatrices();

        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        this.processInputs(context);
    }

    protected void processInputs(UIContext context)
    {
        int mouseX = context.mouseX;
        int mouseY = context.mouseY;

        if (this.isDragging())
        {
            if (this.isDraggingPosition())
            {
                if (this.lastX != context.mouseX || this.lastY != context.mouseY)
                {
                    Vector3d newPoint = this.calculateOnPlane(context);

                    this.pos.set(this.cachedPos);
                    this.pos.sub((float) newPoint.x, (float) newPoint.y, (float) newPoint.z);
                    this.pos.add((float) this.cachedPlaneIntersection.x, (float) this.cachedPlaneIntersection.y, (float) this.cachedPlaneIntersection.z);

                    this.lastX = mouseX;
                    this.lastY = mouseY;
                }
            }
            else
            {
                this.camera.rotation.y -= MathUtils.toRad(this.lastX - mouseX);
                this.camera.rotation.x -= MathUtils.toRad(this.lastY - mouseY);

                this.lastX = mouseX;
                this.lastY = mouseY;
            }
        }
    }

    public void setupPosition()
    {
        this.camera.position.set(this.pos);

        vec.set(0, 0, -this.distance.getValue());
        this.rotateVector(vec);

        this.camera.position.x += vec.x;
        this.camera.position.y += vec.y;
        this.camera.position.z += vec.z;
    }

    private Vector3d calculateOnPlane(UIContext context)
    {
        Vector3d vector = new Vector3d();
        Vector3d origin = new Vector3d(this.cachedCamera.position).sub(this.cachedPos);
        Vector3d destination = new Vector3d(this.cachedCamera.getMouseDirection(context.mouseX, context.mouseY, this.area.x, this.area.y, this.area.w, this.area.h)).mul(this.distance.getValue() * 2).add(origin);
        Intersectiond.intersectLineSegmentPlane(origin.x, origin.y, origin.z, destination.x, destination.y, destination.z, this.plane.x, this.plane.y, this.plane.z, 0, vector);

        return vector;
    }

    private void rotateVector(Vector3d vec)
    {
        mat.identity().rotateX(this.camera.rotation.x);
        mat.transform(vec);
        mat.identity().rotateY(MathUtils.PI - this.camera.rotation.y);
        mat.transform(vec);
    }

    protected void setupViewport(UIContext context)
    {
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        int[] viewport = UIUtils.viewportArea(this.area);

        this.camera.updatePerspectiveProjection(viewport[2], viewport[3]);
        this.camera.updateView();
    }

    /**
     * Draw your model here
     */
    protected abstract void renderUserModel(UIContext context);

    /**
     * Render block of grass under the model (which signify where
     * located the ground below the model)
     */
    protected void renderGrid(UIContext context)
    {
        Matrix4f matrix4f = context.batcher.getContext().pose().last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        for (int x = 0; x <= 10; x ++)
        {
            if (x == 0)
            {
                builder.addVertex(matrix4f, x - 5, 0, -5).setColor(0F, 0F, 1F, 1F);
                builder.addVertex(matrix4f, x - 5, 0, 5).setColor(0F, 0F, 1F, 1F);
            }
            else
            {
                builder.addVertex(matrix4f, x - 5, 0, -5).setColor(0.25F, 0.25F, 0.25F, 1F);
                builder.addVertex(matrix4f, x - 5, 0, 5).setColor(0.25F, 0.25F, 0.25F, 1F);
            }
        }

        for (int x = 0; x <= 10; x ++)
        {
            if (x == 0)
            {
                builder.addVertex(matrix4f, -5, 0, x - 5).setColor(1F, 0F, 0F, 1F);
                builder.addVertex(matrix4f, 5, 0, x - 5).setColor(1F, 0F, 0F, 1F);
            }
            else
            {
                builder.addVertex(matrix4f, -5, 0, x - 5).setColor(0.25F, 0.25F, 0.25F, 1F);
                builder.addVertex(matrix4f, 5, 0, x - 5).setColor(0.25F, 0.25F, 0.25F, 1F);
            }
        }

        { com.mojang.blaze3d.vertex.MeshData __bbsBuilt = builder.build(); if (__bbsBuilt != null) BufferUploader.drawWithShader(__bbsBuilt); }
    }
}
