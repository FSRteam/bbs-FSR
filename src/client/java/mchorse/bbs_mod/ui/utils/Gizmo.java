package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
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
import mchorse.bbs_mod.ui.framework.elements.input.drag.DragStrategy;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformGesture;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import java.util.EnumSet;

public class Gizmo
{
    /* Every pickable gizmo handle owns a distinct stencil id so the combined
     * mode can show move/scale/rotate at once and a pick unambiguously names
     * both the operation and the axis. {@link Handle} ties these together;
     * single-operation modes simply render a subset of them. {@link #STENCIL_MAX}
     * stays the highest id so form parts (which begin right after it) never
     * collide with a handle. */
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
    /** Screen-space translate handle: the big centre cube that grabs in the view plane. */
    public final static int STENCIL_SCREEN = 18;
    /** Uniform-scale handle: the centre cube in scale mode that scales all three axes at once. */
    public final static int STENCIL_SCALE_ALL = 19;

    /** Highest gizmo handle id; form-part stencil ids begin right after it. */
    public final static int STENCIL_MAX = STENCIL_SCALE_ALL;

    /** Radius of the view-plane ring relative to the per-axis rings. */
    private final static float VIEW_RING_SCALE = GizmoRings.VIEW_RING_SCALE;

    private final static float AXIS_SIZE = 0.25F;
    private final static float AXIS_OFFSET = 0.008F;

    /** Move/scale handles shrink inside the rotation rings in combined mode. */
    private final static float COMBINED_INNER_SCALE = 0.6F;

    /** Half-size of the scale handle's end cube, in gizmo-local units (× axes scale × thickness).
     *  Based on scale/thickness rather than the per-pass line offset, so the cube is the same
     *  size in the visual and stencil passes and its hitbox matches the drawn cube exactly. */
    private final static float SCALE_CUBE_HALF = 0.032F;

    /** Half-size of the centre cube shared by the screen-space (view-plane) translate
     *  handle and the uniform (three-axis) scale handle, in gizmo-local units
     *  (× axes scale × thickness). Deliberately large so the centre reads as an easy
     *  grab target. Like {@link #SCALE_CUBE_HALF} it is offset-independent so the visual
     *  and stencil passes match and the hitbox lines up with the drawn cube. */
    private final static float SCREEN_CUBE_HALF = 0.03F;

    public final static Gizmo INSTANCE = new Gizmo();

    private HandleMask handleMask = HandleMask.ALL;

    private Mode mode = Mode.COMBINED;
    /** The mode to return to when combined mode is toggled off. */
    private Mode previousMode = Mode.TRANSLATE;

    private int index;
    private int mouseX;
    private int mouseY;

    private TransformGesture currentGesture;

    private final GizmoRings rings = new GizmoRings();

    /* Snapshot of the matrix stack at the moment the gizmo is rendered.
     * Combined with a camera (whose view matrix matches the one applied to
     * the stack during rendering) this lets us recover the gizmo's true
     * world position without having to thread it through every call site. */
    private final Matrix4f lastRenderMatrix = new Matrix4f();
    private boolean hasLastRenderMatrix;

    /* While an axis ring is dragged the whole gizmo is drawn from the
     * orientation captured at the gesture's first draw, so the ring stays put
     * (only the pie sweeps) instead of writhing as the live rotation is
     * recomposed from euler angles each frame — most visible in local/world
     * space. Keyed to the gesture itself so it can never be forgotten by an
     * edit entry point and re-freezes when the axis is switched mid-edit. */
    private final Matrix4f bakedRotationMatrix = new Matrix4f();
    private DragStrategy bakedGesture;

    /** Last transform space applied to the drawing frame. Drag math keeps its own basis. */
    private TransformSpace lastSpace;

    /** World-space radius the sphere is drawn at, expressed in
     *  the local coordinate frame {@link #lastRenderMatrix} describes
     *  (i.e. already includes axesScale and the per-frame distanceScale).
     *  Captured in {@link #render} so {@link #computeScreenRadius} can
     *  project an edge point and report the sphere's real pixel size. */
    private float lastSphereLocalRadius;

    /** Model-view the sphere is actually drawn with this frame (origin frame
     *  times the per-frame distanceScale). Reused by {@link #renderSphereHighlight}
     *  to re-draw the sphere into a mask at the exact same on-screen footprint. */
    private final Matrix4f lastSphereMatrix = new Matrix4f();
    private boolean hasLastSphereMatrix;

    /** Sphere-only mask the hover highlight is composited from. The sphere is
     *  kept out of the pick stencil (one pixel can't be both "bone" and "sphere",
     *  and the deferred bone-vs-sphere pick needs both), so its highlight gets a
     *  private buffer it can own outright. */
    private final StencilFormFramebuffer sphereHighlight = new StencilFormFramebuffer();

    /** Driven by {@link GizmoInteraction}'s per-frame hover pass. When true the
     *  sphere highlight is composited over the viewport (the screen-space hover
     *  overlay, the same look bones/handles get from the pick stencil). */
    private boolean sphereHovered;

    /** Per-frame on-screen size compensation, {@code menu.height / viewportArea.h}.
     *  {@link #getAxesDistanceScale} otherwise keeps the gizmo a constant fraction
     *  of its viewport, so it shrinks in a small preview (the film) versus a
     *  full-screen editor (forms); this factor makes it a constant fraction of the
     *  window instead, i.e. the same on-screen size in every editor. Each viewport
     *  sets it via {@link #setViewportScale} before BOTH its visual and stencil
     *  pass so the drawn gizmo and its pick hitbox scale together. */
    private float viewportScale = 1F;
    private float viewportHeight;

    private Gizmo()
    {}

    /** Copies presentation state only; the active transform remains a single shared gesture. */
    public void captureVisualState(VisualState state)
    {
        state.renderMatrix.set(this.lastRenderMatrix);
        state.sphereMatrix.set(this.lastSphereMatrix);
        state.hasRenderMatrix = this.hasLastRenderMatrix;
        state.hasSphereMatrix = this.hasLastSphereMatrix;
        state.sphereLocalRadius = this.lastSphereLocalRadius;
        state.viewportScale = this.viewportScale;
        state.viewportHeight = this.viewportHeight;
        state.space = this.lastSpace;
        state.handleMask = this.handleMask;
        state.sphereHovered = this.sphereHovered;
    }

    public void restoreVisualState(VisualState state)
    {
        this.lastRenderMatrix.set(state.renderMatrix);
        this.lastSphereMatrix.set(state.sphereMatrix);
        this.hasLastRenderMatrix = state.hasRenderMatrix;
        this.hasLastSphereMatrix = state.hasSphereMatrix;
        this.lastSphereLocalRadius = state.sphereLocalRadius;
        this.viewportScale = state.viewportScale;
        this.viewportHeight = state.viewportHeight;
        this.lastSpace = state.space;
        this.handleMask = state.handleMask;
        this.sphereHovered = state.sphereHovered;
    }

    /**
     * Reconstruct the world-space origin of the gizmo from the most recent
     * render matrix and the camera that drove that render. The stack at
     * render time is {@code view * translate(-cam.pos) * gizmoChain}, so
     * undoing the view rotation and adding camera position yields the real
     * world coordinates.
     */
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

    /**
     * Recover the gizmo's world-space axes from the latest render matrix and
     * camera. Columns of {@code out} become the unit-length world directions
     * of the gizmo's X/Y/Z handles. Returns {@code false} if the gizmo hasn't
     * been rendered yet, in which case the caller should skip ray-based
     * dragging.
     */
    public boolean computeWorldAxes(Camera camera, Matrix3f out)
    {
        if (!this.hasLastRenderMatrix)
        {
            return false;
        }

        Matrix4f undoView = new Matrix4f(camera.view).invert().mul(this.lastRenderMatrix);

        out.set(GizmoDrag.basisOf(undoView));

        return true;
    }

    public Mode getMode()
    {
        return this.mode;
    }

    /** The active drag's on-screen readout (angle / offset / scale delta), or
     *  {@code null} when nothing is being dragged. See
     *  {@link UIPropTransform#getDragReadout()}. */
    public String getDragReadout()
    {
        return this.currentGesture == null ? null : this.currentGesture.getReadout();
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

    /** Supply the viewport height used by the projection derivative. */
    public void setViewport(Area area)
    {
        this.viewportHeight = area == null ? 0F : area.h;
        if (area == null)
        {
            this.hasLastRenderMatrix = false;
            this.hasLastSphereMatrix = false;
        }
    }

    /** The trackball sphere shows in the dedicated rotate mode and in combined. */
    public boolean hasSphere()
    {
        return this.mode == Mode.ROTATE || this.mode == Mode.COMBINED;
    }

    /** One visibility contract for painting, picking and accepting a mouse grab. */
    public boolean isHandleVisible(Handle handle)
    {
        if (handle == null || !this.handleMask.allows(handle))
        {
            return false;
        }

        return switch (handle.op)
        {
            case MOVE, SCREEN -> this.mode.shows(Op.MOVE) && BBSSettings.gizmoShowTranslate.get();
            case SCALE, SCALE_ALL -> this.mode.shows(Op.SCALE) && BBSSettings.gizmoShowScale.get();
            case ROTATE -> this.mode.shows(Op.ROTATE) && BBSSettings.gizmoShowRotate.get();
            case VIEW -> this.mode.shows(Op.VIEW) && BBSSettings.gizmoShowViewRotate.get();
            case TRACKBALL -> this.hasSphere() && BBSSettings.gizmoShowSphere.get();
        };
    }

    public boolean isSphereInteractive()
    {
        if (!this.handleMask.allows(Op.TRACKBALL) || !BBSSettings.gizmos.get() || !BBSSettings.rotate3dSphere.get()
            || !BBSSettings.gizmoShowSphere.get())
        {
            return false;
        }

        if (!this.hasSphere())
        {
            return false;
        }

        if (this.currentGesture != null && this.currentGesture.isEditing() && !this.currentGesture.isSphereRotate())
        {
            return false;
        }

        return true;
    }

    public boolean isSphereDragging()
    {
        return this.currentGesture != null && this.currentGesture.isEditing() && this.currentGesture.isSphereRotate();
    }

    public boolean isTrackballDragging()
    {
        return this.isSphereDragging();
    }

    /** World-space radius the rotate sphere was last drawn at ({@code 0} until rendered). */
    public float getSphereWorldRadius()
    {
        return this.hasLastRenderMatrix ? this.lastSphereLocalRadius : 0F;
    }

    /**
     * Project the gizmo's origin onto the viewport in pixel space and
     * write the result into {@code out}. Returns {@code false} when the
     * gizmo hasn't been rendered yet or the origin sits behind the
     * camera ({@code clip.w <= 0}) — caller should skip the hover check.
     *
     * <p>{@link #lastRenderMatrix} already encodes
     * {@code view * translate(-cam) * gizmoChain}, so left-multiplying
     * by the projection matrix yields clip space directly. NDC → pixel
     * mapping then accounts for the inverted Y between OpenGL NDC
     * (Y up) and screen coordinates (Y down).
     */
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

    /**
     * Effective pixel radius of the rotate-mode sphere on screen, so the
     * hover/pick disc in {@link mchorse.bbs_mod.ui.film.controller.UIFilmController}
     * matches the sphere's actual visual size at the current camera
     * distance and axes scale.
     *
     * <p>Projects three local-axis edge points
     * ({@code (r,0,0)}, {@code (0,r,0)}, {@code (0,0,r)}) onto the
     * viewport and returns the largest pixel distance from the
     * projected centre — covers all camera orientations without
     * needing a true ellipse-from-sphere derivation. Returns {@code 0}
     * when the gizmo hasn't been rendered yet, the centre is behind
     * the camera, or the sphere radius hasn't been captured.
     */
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

    /**
     * Composite the trackball sphere's hover highlight over the viewport, the
     * same screen-space overlay bones and handles get from the pick stencil.
     *
     * <p>The sphere can't share the pick stencil (its pixels would erase the
     * bone ids the deferred bone-vs-sphere pick reads), so it gets a private
     * mask: re-draw the sphere — at the exact matrix it was rendered with this
     * frame ({@link #lastSphereMatrix}) and the viewport's projection — into
     * {@link #sphereHighlight} carrying {@link #STENCIL_TRACKBALL} as its id,
     * then run the picker-preview shader so only those pixels light up.
     *
     * <p>Called from each {@link GizmoViewport}'s GUI overlay pass via
     * {@link GizmoInteraction#renderSphereHighlight}; {@code projection}/{@code area}
     * are the same pair {@link #computeScreenCenter} uses, so the mask lands on
     * the sphere's footprint regardless of mask resolution.
     */
    public void renderSphereHighlight(UIContext context, Matrix4f projection, Area area)
    {
        if (!this.sphereHovered || !this.hasLastSphereMatrix || !this.isSphereInteractive()
            || !UIBaseMenu.shouldRenderAxes() || projection == null || area == null)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        this.sphereHighlight.setup(Link.bbs("gizmo_sphere_highlight"));

        int[] previousViewport = UIUtils.currentViewport();
        int w = mc.getWindow().getScreenWidth();
        int h = mc.getWindow().getScreenHeight();
        Texture texture = this.sphereHighlight.getFramebuffer().getMainTexture();

        if (texture.width != w || texture.height != h)
        {
            this.sphereHighlight.resize(w, h);
        }

        this.sphereHighlight.apply();

        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(STENCIL_TRACKBALL / 255F, 0F, 0F, 1F);
        this.rings.drawSphere(this.lastSphereMatrix, projection);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.enableDepthTest();

        this.sphereHighlight.unbind();
        mc.getMainRenderTarget().bindWrite(false);
        UIUtils.restoreViewport(previousViewport);

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

    /**
     * Set the persistent gizmo mode. Returns {@code true} iff the mode
     * actually changed — callers (notably the tool-switch hotkey
     * helper) use this to distinguish a real switch from a no-op press
     * on the already-active tool.
     */
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

    /**
     * Toggle the combined mode: entering it remembers the mode left behind so a
     * second press returns there. This is the only way out of combined, since
     * in that mode the G/S/R hotkeys run their operation without switching the
     * displayed handles.
     */
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

    public boolean start(int index, int mouseX, int mouseY, UIPropTransform transform)
    {
        return this.start(index, mouseX, mouseY, transform, null);
    }

    public boolean start(int index, int mouseX, int mouseY, UIPropTransform transform, GizmoDrag drag)
    {
        Handle handle = Handle.byIndex(index);

        if (!BBSSettings.gizmos.get() || handle == null || transform == null
            || !this.isHandleVisible(handle) || !transform.getHandleMask().allows(handle))
        {
            return false;
        }

        TransformGesture gesture = transform.getGesture();

        switch (handle.op)
        {
            case MOVE:
            case SCALE:
            case ROTATE:
                gesture.enableMode(handle.op.transformOp, handle.axis, handle.axis2, drag);
                break;
            case SCALE_ALL:
                gesture.enableUniformScale(drag);
                break;
            case SCREEN:
                gesture.enableScreenTranslate(drag);
                break;
            case TRACKBALL:
                if (BBSSettings.rotate3dSphere.get()) gesture.enableSphereRotate(drag);
                break;
            case VIEW:
                gesture.enableViewRotate(drag);
                break;
        }

        if (!gesture.isEditing())
        {
            return false;
        }

        this.index = index;
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        return true;
    }

    public TransformGesture getTrackedGesture()
    {
        return this.currentGesture;
    }

    public void trackGesture(TransformGesture gesture)
    {
        this.currentGesture = gesture;
    }

    public void clearTrackedGesture(TransformGesture gesture)
    {
        if (this.currentGesture == gesture)
        {
            this.currentGesture = null;
            this.bakedGesture = null;

            if (this.index < STENCIL_X || this.index > STENCIL_MAX)
            {
                this.index = -1;
            }
        }
    }

    /** Compatibility entry point for integrations that hold the field widget. */
    public void trackTransform(UIPropTransform transform)
    {
        this.trackGesture(transform == null ? null : transform.getGesture());
    }

    public void clearTrackedTransform(UIPropTransform transform)
    {
        if (transform != null) this.clearTrackedGesture(transform.getGesture());
    }

    public void stop()
    {
        this.index = -1;
        TransformGesture gesture = this.currentGesture;

        try
        {
            if (gesture != null) gesture.accept();
        }
        finally
        {
            if (this.currentGesture == gesture) this.currentGesture = null;
        }
    }

    public void cancel()
    {
        this.index = -1;
        TransformGesture gesture = this.currentGesture;

        try
        {
            if (gesture != null) gesture.reject();
        }
        finally
        {
            if (this.currentGesture == gesture) this.currentGesture = null;
        }
    }

    public void render(PoseStack stack)
    {
        BBSProfiler.begin(BBSProfiler.Timer.GIZMO);

        try
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
        finally
        {
            BBSProfiler.end(BBSProfiler.Timer.GIZMO);
        }
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
        this.captureVisual(stack, HandleMask.ALL);
    }

    public void captureVisual(PoseStack stack, HandleMask mask)
    {
        /* The secondary monitor is display-only. Its world pass uses a different
         * camera and framebuffer, while this singleton's captured frame belongs
         * to the interactive main preview. Do not let the secondary matrix
         * replace the main preview's gizmo frame. */
        if (BBSRendering.isIrisShadowPass() || BBSRendering.isApplyingSecondaryCamera())
        {
            return;
        }

        this.handleMask = mask;
        stack.pushPose();
        MatrixStackUtils.scaleBack(stack);
        this.captureRenderMatrix(stack);
        stack.popPose();
    }

    /**
     * Draw the gizmo's visual over a {@link GizmoViewport} in the UI pass, from
     * the model-view captured this frame ({@link #lastRenderMatrix}, set by
     * {@link #captureVisual}).
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

        this.setViewport(area);

        context.batcher.flush();

        MatrixStackUtils.cacheMatrices();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

        /* Map the UI area to a framebuffer-pixel viewport, exactly as the form
         * editor's model pass does, so the gizmo renders into the preview and is
         * clipped to it by the view frustum. */
        int[] previousViewport = UIUtils.currentViewport();

        UIUtils.viewportArea(area);

        PoseStack stack = new PoseStack();
        MatrixStackUtils.multiply(stack, this.lastRenderMatrix);

        RenderSystem.disableDepthTest();
        this.drawGizmo(stack);
        RenderSystem.enableDepthTest();

        UIUtils.restoreViewport(previousViewport);
        MatrixStackUtils.restoreMatrices();

        /* Leave the depth state the UI expects after a 3D interlude (always-pass),
         * the same exit state as the form editor's model pass. */
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
    }

    private void drawGizmo(PoseStack stack)
    {
        this.applyBakedRotation(stack);

        float distanceScale = this.getAxesDistanceScale(stack);

        stack.pushPose();
        this.applyViewShear(stack);
        stack.scale(distanceScale, distanceScale, distanceScale);

        if (BBSSettings.gizmos.get())
        {
            /* Cache the sphere's effective world radius (in
             * {@link #lastRenderMatrix}'s coordinate frame) so
             * {@link #computeScreenRadius} can report the real on-screen
             * pixel size for hover/pick distance checks. */
            this.lastSphereLocalRadius = 0.22F * BBSSettings.axesScale.get() * distanceScale;

            this.lastSphereMatrix.set(modelView(stack));
            this.hasLastSphereMatrix = true;
            this.drawOccludedGizmo(stack);
        }
        else
        {
            Draw.coolerAxes(stack, 0.25F, 0.008F);
        }

        stack.popPose();

        /* The guide shows the real drag axis, so it stays outside the drawing-only shear. */
        this.drawInfiniteLine(stack);
    }

    /**
     * In VIEW space, replace the drawing frame's third column with the unit ray from the
     * gizmo to the eye. This affects only the visual and stencil passes; the captured matrix
     * and {@link GizmoDrag} basis remain orthonormal for dragging.
     */
    private void applyViewShear(PoseStack stack)
    {
        if (this.lastSpace != TransformSpace.VIEW)
        {
            return;
        }

        Matrix4f matrix = stack.last().pose();
        Vector3f toCamera = matrix.getTranslation(new Vector3f()).negate();

        if (toCamera.lengthSquared() < 1.0E-8F)
        {
            return;
        }

        toCamera.normalize();
        matrix.m20(toCamera.x);
        matrix.m21(toCamera.y);
        matrix.m22(toCamera.z);
    }

    /**
     * Draw the opaque gizmo handles with real depth so nearer parts hide farther
     * ones — the solid look a typical 3D gizmo has, instead of every bar, plane
     * and ring bleeding over each other flat.
     *
     * <p>The gizmo still sits on top of the scene: a first depth-only pass stamps
     * every handle pixel to the far plane (via {@code depthRange(1,1)}), so the
     * model's own depth can't occlude the gizmo; the real pass then draws the
     * handles against that clean slate with an ordinary depth test, sorting them
     * among themselves. The translucent sweep pie stays on top of everything and
     * writes no depth, so it can't punch holes in the handles.
     */
    private void drawOccludedGizmo(PoseStack stack)
    {
        Layout layout = new Layout();
        float opacity = layout.opacity;

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        GL11.glDepthRange(1D, 1D);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.colorMask(false, false, false, false);
        this.drawAxes(stack, layout);

        GL11.glDepthRange(0D, 1D);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);

        /* The gizmo opacity rides on the shader colour's alpha, which the
         * position_color program multiplies into every vertex — so blend must be
         * on for it to show (at opacity 1 this is just opaque, no change). */
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1F, 1F, 1F, opacity);
        this.drawAxes(stack, layout);

        /* The sweep pie overlays the handles and must not write depth. */
        RenderSystem.depthMask(false);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.setShaderColor(1F, 1F, 1F, opacity);
        this.drawRotatePieIfActive(stack);
        RenderSystem.depthMask(true);

        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    /**
     * Draw the rotation sweep pie when an axis ring is being dragged. Split out
     * of the handle pass so it can be composited last, on top of and without
     * disturbing the depth-sorted handles.
     */
    private void drawRotatePieIfActive(PoseStack stack)
    {
        GizmoPie.draw(stack, this.currentGesture, this.ringDragGesture());
    }

    private float getAxesDistanceScale(PoseStack stack)
    {
        return GizmoSize.getScale(modelView(stack), RenderSystem.getProjectionMatrix(), this.viewportHeight);
    }

    private void drawInfiniteLine(PoseStack stack)
    {
        int debugIndex = this.index;

        if ((debugIndex < STENCIL_X || debugIndex > STENCIL_ZY) && this.currentGesture != null)
        {
            debugIndex = this.currentGesture.getDebugLineStencilIndex();
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
            Draw.fillBox(builder, stack, -size, -t, -t, size, t, t, Colors.RED);
        }

        if (debugIndex == STENCIL_Y || debugIndex == STENCIL_XY || debugIndex == STENCIL_ZY)
        {
            Draw.fillBox(builder, stack, -t, -size, -t, t, size, t, Colors.GREEN);
        }

        if (debugIndex == STENCIL_Z || debugIndex == STENCIL_XZ || debugIndex == STENCIL_ZY)
        {
            Draw.fillBox(builder, stack, -t, -t, -size, t, t, size, Colors.BLUE);
        }

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        { com.mojang.blaze3d.vertex.MeshData __bbsBuilt = builder.build(); if (__bbsBuilt != null) BufferUploader.drawWithShader(__bbsBuilt); }
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    private static Matrix4f modelView(PoseStack stack)
    {
        return new Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose());
    }

    /**
     * The handle the live edit is grabbing, or {@code null} when nothing should
     * be filtered out: no edit is running, or the hide-inactive-handles setting
     * is off. Both draw passes show only this handle when it is present. A
     * two-axis rotation has no handle of its own, so the primary ring stands in.
     */
    private Handle activeDragHandle()
    {
        TransformGesture transform = this.currentGesture;

        if (!BBSSettings.hideInactiveHandles.get() || transform == null || !transform.isEditing())
        {
            return null;
        }

        TransformOp op = transform.getOp();
        Axis axis = transform.getAxis();

        if (op == TransformOp.ROTATE)
        {
            if (transform.isSphereRotate()) return Handle.TRACKBALL;
            if (transform.isViewRotate()) return Handle.VIEW;
            if (axis == Axis.X) return Handle.ROTATE_X;
            if (axis == Axis.Y) return Handle.ROTATE_Y;
            if (axis == Axis.Z) return Handle.ROTATE_Z;

            return null;
        }

        if (op == TransformOp.TRANSLATE && transform.isScreenTranslate())
        {
            return Handle.SCREEN;
        }

        if (op == TransformOp.SCALE && transform.isScaleAll())
        {
            return Handle.SCALE_ALL;
        }

        Op handleOp = op == TransformOp.SCALE ? Op.SCALE : Op.MOVE;
        Axis axis2 = transform.getAxis2();

        for (Handle handle : Handle.values())
        {
            if (handle.op != handleOp)
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

    /** Washes a ring colour channel toward flat gray for IK-owned rotations. */
    private static float dimmed(float channel, boolean constrained)
    {
        return constrained ? channel * 0.25F + 0.3F : channel;
    }

    /**
     * Receives the gizmo's box-shaped elements — bars, plane quads, the centre cubes and the
     * scale cubes. The geometry is written once, in {@link Gizmo#collectHandles}, and painted
     * twice: the visual pass by the colour it is handed, the pick pass by the handle's id.
     */
    private interface HandleSink
    {
        void box(Handle handle, float x1, float y1, float z1, float x2, float y2, float z2, int color);

        /**
         * The centre cube the pick pass masks the bars with, so a click in the middle of the
         * gizmo lands on nothing rather than on whichever bar runs through it. Comes right
         * after the bars and before anything that overlays the centre, which is why it is a
         * step of the walk and not something the pass draws on its own. The visual pass has
         * no use for it — its own centre cube is decoration, drawn last.
         */
        default void centreMask(float half)
        {
        }
    }

    /** The same, for the rotation rings: the three axis ones and the camera-facing view ring. */
    private interface RingSink
    {
        void ring(Handle handle, Axis axis, float radius, float thickness, int color);

        void viewRing(Handle handle, int color);
    }

    /**
     * Walks the rotation rings that should be on screen right now, in draw order.
     *
     * <p>Shared by the visual pass and the pick pass so a ring's radius and thickness — and
     * which rings exist at all — are decided in ONE place. They used to be two copies kept in
     * step by hand, with a comment in the pick pass reminding whoever changed one to change
     * the other; a slip there means clicking a ring that is not where it is drawn.
     */
    private void collectRings(Layout layout, RingSink sink)
    {
        Handle active = layout.active;

        /* The 3D sphere itself is invisible — it only acts as the trackball grab area. Hover
         * feedback is a screen-space glow composited in {@link #renderSphereHighlight}. */

        if (layout.showRings)
        {
            float radius = layout.ringRadius;
            float ringThickness = layout.ringThickness;

            /* A target may own only some of the three rotation axes (a replay's root turns
             * about Y and pitches about X, but has nowhere to put roll), so each ring is
             * filtered on its own axis rather than the group as a whole. */
            if (layout.mask.allows(Handle.ROTATE_Z) && (active == null || active == Handle.ROTATE_Z)) sink.ring(Handle.ROTATE_Z, Axis.Z, radius, ringThickness, Colors.BLUE);
            if (layout.mask.allows(Handle.ROTATE_X) && (active == null || active == Handle.ROTATE_X)) sink.ring(Handle.ROTATE_X, Axis.X, radius, ringThickness, Colors.RED);
            if (layout.mask.allows(Handle.ROTATE_Y) && (active == null || active == Handle.ROTATE_Y)) sink.ring(Handle.ROTATE_Y, Axis.Y, radius, ringThickness, Colors.GREEN);
        }

        /* The screen-space (billboard) view-rotation ring hides on its own element, not with
         * the axis rings — the two are separate settings. */
        if (layout.showViewRing)
        {
            sink.viewRing(Handle.VIEW, Colors.LIGHTEST_GRAY);
        }
    }

    /**
     * Walks the move/scale elements that should be on screen right now, in draw order. Same
     * bargain as {@link #collectRings}: one description of where every handle sits, so the
     * drawn gizmo and its pick hitboxes cannot drift apart.
     */
    private void collectHandles(Layout layout, HandleSink sink)
    {
        Handle active = layout.active;
        boolean showMove = layout.showMove;
        boolean showScale = layout.showScale;
        float axisSize = layout.axisSize;
        float axisOffset = layout.axisOffset;
        float scale = layout.scale;
        float thickness = layout.thickness;
        float planeSize = layout.planeSize;

        /* The bars and planes read as move when move is on screen and as scale only when
         * scale stands alone — so a grab of that element drives what its colour promised,
         * and move and scale never share an id under the cursor. */
        Handle barX = showMove ? Handle.MOVE_X : Handle.SCALE_X;
        Handle barY = showMove ? Handle.MOVE_Y : Handle.SCALE_Y;
        Handle barZ = showMove ? Handle.MOVE_Z : Handle.SCALE_Z;
        Handle planeXZ = showMove ? Handle.MOVE_XZ : Handle.SCALE_XZ;
        Handle planeXY = showMove ? Handle.MOVE_XY : Handle.SCALE_XY;
        Handle planeZY = showMove ? Handle.MOVE_ZY : Handle.SCALE_ZY;

        if (active == null || active == barX) sink.box(barX, 0, -axisOffset, -axisOffset, axisSize, axisOffset, axisOffset, Colors.RED);
        if (active == null || active == barY) sink.box(barY, -axisOffset, 0, -axisOffset, axisOffset, axisSize, axisOffset, Colors.GREEN);
        if (active == null || active == barZ) sink.box(barZ, -axisOffset, -axisOffset, 0, axisOffset, axisOffset, axisSize, Colors.BLUE);

        sink.centreMask(axisOffset);

        /* Screen-space (view-plane) translate handle: a white cube at the centre, twice the
         * bars' thickness. Drawn before the planes so they overlay it, and after the rotation
         * rings so it stays visible when they are on screen too. */
        if (showMove && layout.mask.allows(Op.SCREEN) && (active == null || active == Handle.SCREEN))
        {
            float screenHalf = SCREEN_CUBE_HALF * scale * thickness;

            sink.box(Handle.SCREEN, -screenHalf, -screenHalf, -screenHalf, screenHalf, screenHalf, screenHalf, Colors.WHITE);
        }

        /* Uniform-scale handle: the same centre cube, shown only when move isn't (with
         * both on screen the centre is the translate handle), so the pick is never
         * ambiguous between the two. */
        if (showScale && !showMove && layout.mask.allows(Op.SCALE_ALL) && (active == null || active == Handle.SCALE_ALL))
        {
            float scaleAllHalf = SCREEN_CUBE_HALF * scale * thickness;

            sink.box(Handle.SCALE_ALL, -scaleAllHalf, -scaleAllHalf, -scaleAllHalf, scaleAllHalf, scaleAllHalf, scaleAllHalf, Colors.WHITE);
        }

        /* The plane quad's footprint is a fraction of the axis length, independent of
         * axesThickness — thickness only fattens the bars and the flat slab depth, not how
         * big the two-axis plane reads. Its own setting grows it outwards from a fixed start,
         * so a bigger plane is easier to grab without walking away from the origin. */
        float planeStart = axisSize * 0.2F;
        float planeEnd = planeStart + axisSize * 0.2F * planeSize;
        float planeThickness = axisOffset * 0.5F;

        if (active == null || active == planeXZ) sink.box(planeXZ, planeStart, -planeThickness, planeStart, planeEnd, planeThickness, planeEnd, Colors.PLANE_XZ);
        if (active == null || active == planeXY) sink.box(planeXY, planeStart, planeStart, -planeThickness, planeEnd, planeEnd, planeThickness, Colors.PLANE_XY);
        if (active == null || active == planeZY) sink.box(planeZY, -planeThickness, planeStart, planeStart, planeThickness, planeEnd, planeEnd, Colors.PLANE_ZY);

        if (showScale)
        {
            float cubeHalf = SCALE_CUBE_HALF * scale * thickness;

            if (active == null || active == Handle.SCALE_X) sink.box(Handle.SCALE_X, axisSize - cubeHalf, -cubeHalf, -cubeHalf, axisSize + cubeHalf, cubeHalf, cubeHalf, Colors.RED);
            if (active == null || active == Handle.SCALE_Y) sink.box(Handle.SCALE_Y, -cubeHalf, axisSize - cubeHalf, -cubeHalf, cubeHalf, axisSize + cubeHalf, cubeHalf, Colors.GREEN);
            if (active == null || active == Handle.SCALE_Z) sink.box(Handle.SCALE_Z, -cubeHalf, -cubeHalf, axisSize - cubeHalf, cubeHalf, cubeHalf, axisSize + cubeHalf, Colors.BLUE);
        }
    }

    /** Settings and target capabilities resolved for both geometry producers. */
    private final class Layout
    {
        final Handle active = Gizmo.this.activeDragHandle();
        final HandleMask mask = Gizmo.this.handleMask;

        final boolean showMove = BBSSettings.gizmoShowTranslate.get() && this.mask.allows(Op.MOVE) && Gizmo.this.mode.shows(Op.MOVE)
            && (this.active == null || this.active.op == Op.MOVE || this.active.op == Op.SCREEN);
        final boolean showScale = BBSSettings.gizmoShowScale.get() && this.mask.allows(Op.SCALE) && Gizmo.this.mode.shows(Op.SCALE)
            && (this.active == null || this.active.op == Op.SCALE || this.active.op == Op.SCALE_ALL);
        final boolean rotateVisible = BBSSettings.gizmoShowRotate.get() && this.mask.allows(Op.ROTATE) && Gizmo.this.mode.shows(Op.ROTATE);
        final boolean viewRotateVisible = BBSSettings.gizmoShowViewRotate.get() && this.mask.allows(Op.VIEW) && Gizmo.this.mode.shows(Op.VIEW);
        final boolean showRings = this.rotateVisible && !BBSSettings.rotateHideRings.get()
            && (this.active == null || this.active.op == Op.ROTATE);
        final boolean showViewRing = this.viewRotateVisible && (this.active == null || this.active.op == Op.VIEW);
        final boolean showRotate = (this.rotateVisible || this.viewRotateVisible)
            && (this.active == null || this.active.op == Op.ROTATE || this.active.op == Op.VIEW || this.active.op == Op.TRACKBALL);

        final float scale = BBSSettings.axesScale.get();
        final float thickness = BBSSettings.axesThickness.get();
        final float planeSize = BBSSettings.gizmoPlaneSize.get();
        final float opacity = BBSSettings.gizmoOpacity.get();
        final boolean constrained = Gizmo.this.currentGesture != null && Gizmo.this.currentGesture.rotationConstrained();

        final float innerScale = Gizmo.this.mode == Mode.COMBINED && this.rotateVisible ? COMBINED_INNER_SCALE : 1F;
        final float axisSize = AXIS_SIZE * this.scale * this.innerScale;
        final float axisOffset = AXIS_OFFSET * this.scale * this.thickness;
        final float ringRadius = 0.22F * this.scale;
        final float ringThickness = 0.02F * this.scale * this.thickness;

        boolean showsBoxes()
        {
            return this.showMove || this.showScale;
        }
    }

    /** Draws the gizmo for the eye: the walks above, painted in the handles' own colours. */
    private void drawAxes(PoseStack stack, Layout layout)
    {
        Handle active = layout.active;
        float axisOffset = layout.axisOffset;

        boolean building = false;
        BufferBuilder builder = null;

        if (layout.showRotate)
        {
            /* IK owns this bone's rotation: the rings render washed-out as the visible "not
             * yours to turn" cue, matching the rotation strategies' refusal to start there
             * (the pads still edit the FK channels). */
            boolean constrained = layout.constrained;

            /* Depth state is owned by the caller ({@link #drawOccludedGizmo}) so the handles
             * sort against each other. */
            this.collectRings(layout, new RingSink()
            {
                @Override
                public void ring(Handle handle, Axis axis, float radius, float ringThickness, int color)
                {
                    Gizmo.this.rings.drawOccluded(stack, axis, radius, ringThickness,
                        dimmed(Colors.getR(color), constrained),
                        dimmed(Colors.getG(color), constrained),
                        dimmed(Colors.getB(color), constrained));
                }

                @Override
                public void viewRing(Handle handle, int color)
                {
                    /* This VBO ring sets the shader colour itself, so the opacity modulator
                     * doesn't reach it — fold it into the alpha here instead. */
                    float alpha = Colors.getA(color) * layout.opacity * (constrained ? 0.35F : 1F);

                    Gizmo.this.rings.drawBillboard(stack, Colors.getR(color), Colors.getG(color), Colors.getB(color), alpha);
                }
            });
        }

        if (layout.showsBoxes())
        {
            builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            building = true;

            BufferBuilder boxes = builder;

            this.collectHandles(layout, (handle, x1, y1, z1, x2, y2, z2, color) ->
                Draw.fillBox(boxes, stack, x1, y1, z1, x2, y2, z2, color));
        }

        /* The centre cube is decoration, so a filtered drag hides it. */
        if (active == null && (layout.showsBoxes() || layout.showRotate))
        {
            if (!building)
            {
                builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                building = true;
            }

            Draw.fillBox(builder, stack, -axisOffset, -axisOffset, -axisOffset, axisOffset, axisOffset, axisOffset, Colors.WHITE);
        }

        if (building)
        {
            /* Depth func/mask is owned by {@link #drawOccludedGizmo} so bars, planes and cubes
             * depth-sort against the rings and each other. Re-assert the opacity modulator:
             * the billboard view ring above sets the shader colour itself and leaves it opaque. */
            RenderSystem.setShaderColor(1F, 1F, 1F, layout.opacity);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            MeshData mesh = builder.build();

            if (mesh != null)
            {
                BufferUploader.drawWithShader(mesh);
            }
        }
    }

    /**
     * Draws the same gizmo for the picker: the same walks, painted with each handle's stencil
     * id instead of its colour, so what the cursor lands on is by construction what the eye
     * sees. Ids go in the red channel, the way the pick buffer is read back.
     */
    private void drawStencilHandles(PoseStack stack)
    {
        Layout layout = new Layout();

        RenderSystem.disableDepthTest();

        if (layout.showRotate)
        {
            this.collectRings(layout, new RingSink()
            {
                @Override
                public void ring(Handle handle, Axis axis, float radius, float ringThickness, int color)
                {
                    Gizmo.this.rings.drawOccluded(stack, axis, radius, ringThickness, handle.index / 255F, 0F, 0F);
                }

                @Override
                public void viewRing(Handle handle, int color)
                {
                    Gizmo.this.rings.drawBillboard(stack, handle.index / 255F, 0F, 0F, 1F);
                }
            });
        }

        if (layout.showsBoxes())
        {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

            this.collectHandles(layout, new HandleSink()
            {
                @Override
                public void box(Handle handle, float x1, float y1, float z1, float x2, float y2, float z2, int color)
                {
                    Draw.fillBox(builder, stack, x1, y1, z1, x2, y2, z2, handle.index / 255F, 0F, 0F);
                }

                @Override
                public void centreMask(float half)
                {
                    Draw.fillBox(builder, stack, -half, -half, -half, half, half, half, 0F, 0F, 0F);
                }
            });

            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            MeshData mesh = builder.build();

            if (mesh != null)
            {
                BufferUploader.drawWithShader(mesh);
            }
        }

        RenderSystem.enableDepthTest();
    }

    /**
     * Draw the pick IDs without changing the visual pass's drag placement.
     * The picking pass is cursor-gated, so it must not become the owner of
     * {@link #lastRenderMatrix}.
     */
    public void renderStencil(PoseStack stack, StencilMap map)
    {
        this.renderStencil(stack, map, HandleMask.ALL);
    }

    public void renderStencil(PoseStack stack, StencilMap map, HandleMask mask)
    {
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        if (!BBSSettings.gizmos.get())
        {
            return;
        }

        stack.pushPose();
        MatrixStackUtils.scaleBack(stack);
        HandleMask previous = this.handleMask;
        this.handleMask = mask;
        try
        {
            this.drawStencilAxes(stack);
        }
        finally
        {
            this.handleMask = previous;
            stack.popPose();
        }
    }

    /**
     * Draw the gizmo handles as stencil IDs into the currently bound picking
     * framebuffer, from a stack already positioned at the gizmo origin. Shared by
     * the world-pass {@link #renderStencil} and the UI-pass
     * {@link #renderStencilInterface}.
     */
    private void drawStencilAxes(PoseStack stack)
    {
        this.applyBakedRotation(stack);

        float distanceScale = this.getAxesDistanceScale(stack);

        stack.pushPose();
        this.applyViewShear(stack);
        stack.scale(distanceScale, distanceScale, distanceScale);
        this.drawStencilHandles(stack);
        stack.popPose();
    }

    /**
     * Draw the gizmo's pick stencil over a {@link GizmoViewport} in the UI pass,
     * from the model-view captured this frame ({@link #lastRenderMatrix}, set by
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

        this.setViewport(area);

        MatrixStackUtils.cacheMatrices();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);

        /* Map the UI area to a framebuffer-pixel viewport, exactly as
         * renderInterface does, so the stencil matches the drawn visual pixel for
         * pixel. The pick framebuffer is sized to the window, so the same mapping
         * applies. */
        int[] previousViewport = UIUtils.currentViewport();

        UIUtils.viewportArea(area);

        PoseStack stack = new PoseStack();
        MatrixStackUtils.multiply(stack, this.lastRenderMatrix);

        this.drawStencilAxes(stack);

        UIUtils.restoreViewport(previousViewport);
        MatrixStackUtils.restoreMatrices();
    }

    private void captureRenderMatrix(PoseStack stack)
    {
        /* The drag/pick math (computeWorldOrigin, computeWorldAxes, computeScreenCenter)
         * needs the FULL modelview — view * translate(-cam) * gizmoChain. Since 1.21.1 the
         * world render keeps the camera view in RenderSystem's global model-view and hands
         * the stack an identity base, so stack.peek() alone no longer carries the camera
         * rotation. modelView() folds the global model-view back in (and is a no-op in the
         * form editor, where the camera already lives in the stack), giving the same
         * coordinate frame the gizmo is actually drawn in. */
        this.lastRenderMatrix.set(modelView(stack));
        this.hasLastRenderMatrix = true;
    }

    /**
     * Bake a transform-space reorientation into the gizmo's drawing frame, in
     * place, BEFORE it is captured &mdash; so the visual ({@link #renderInterface})
     * and the pick stencil, both rebuilt from the captured frame, stay in
     * lockstep. The frame's origin (its translation) is kept and only its axes
     * are replaced with {@link GizmoDrag#stackBasisForSpace} &mdash; the drawn
     * twin of the frame the drags slide/turn in ({@link GizmoDrag#frameBasis}).
     * {@link TransformSpace#LOCAL} leaves the bone's own axes untouched, and
     * {@link TransformSpace#PARENT} keeps the placed frame too: the non-local
     * placement matrix is the cache's origin flavour &mdash; the bone's frame
     * BEFORE its own rotation, which is exactly the parent frame. Call right
     * after the gizmo origin is multiplied onto the stack and before
     * {@link #render}/{@link #renderStencil}/{@link #captureVisual}.
     *
     * <p>{@code globalAxes} is what {@link TransformSpace#GLOBAL} aligns to
     * (see {@link GizmoDrag#globalWorldAxes}); pass the same axes the drag gets
     * or the handles would be drawn off the frame they slide in. {@code null}
     * is the plain world axes, which is what the hosts without a scene use.
     */
    public void reorientForSpace(PoseStack stack, TransformSpace space, Matrix4f cameraView, Matrix3f globalAxes)
    {
        this.lastSpace = cameraView == null ? null : space;

        if (space == null || space == TransformSpace.LOCAL || space == TransformSpace.PARENT || cameraView == null)
        {
            return;
        }

        Matrix4f matrix = stack.last().pose();
        Vector3f translation = matrix.getTranslation(new Vector3f());

        matrix.set(new Matrix4f(GizmoDrag.stackBasisForSpace(space, cameraView, globalAxes)).setTranslation(translation));
    }

    /**
     * Freeze the gizmo orientation while an axis ring is dragged. The first
     * draw of a gesture snapshots the full model-view — still at the grab orientation,
     * since the drag hasn't written anything by then — and every later draw
     * (visual and stencil alike) rewinds to that snapshot. Keying the snapshot
     * to the gesture makes the freeze self-maintaining: no edit entry point
     * has to remember to bake, and switching the axis mid-edit re-freezes at
     * the restored orientation. The live {@link #lastRenderMatrix} is left
     * untouched for pick/projection helpers. Each pass removes its current global
     * model-view before drawing so visual and stencil passes apply the camera once.
     */
    private void applyBakedRotation(PoseStack stack)
    {
        DragStrategy gesture = this.ringDragGesture();

        if (gesture == null)
        {
            this.bakedGesture = null;

            return;
        }

        if (this.bakedGesture != gesture)
        {
            this.bakedRotationMatrix.set(modelView(stack));
            this.bakedGesture = gesture;
        }

        stack.last().pose().set(RenderSystem.getModelViewMatrix()).invert().mul(this.bakedRotationMatrix);
    }

    /**
     * The live rotation gesture the gizmo should freeze its rings for, or
     * {@code null} when none: the sphere and the view ring own their whole
     * orientation and want the live frame instead.
     */
    private DragStrategy ringDragGesture()
    {
        TransformGesture transform = this.currentGesture;

        if (transform == null
            || !transform.isEditing()
            || transform.getOp() != TransformOp.ROTATE
            || transform.isSphereRotate()
            || transform.isViewRotate())
        {
            return null;
        }

        return transform.getStrategy();
    }

    public static enum Mode
    {
        TRANSLATE, SCALE, ROTATE, COMBINED;

        public boolean shows(Op op)
        {
            switch (this)
            {
                case TRANSLATE:
                    return op == Op.MOVE || op == Op.SCREEN;
                case SCALE:
                    return op == Op.SCALE;
                case ROTATE:
                    return op == Op.ROTATE || op == Op.VIEW || op == Op.TRACKBALL;
                case COMBINED:
                    return op == Op.MOVE || op == Op.SCALE || op == Op.ROTATE || op == Op.VIEW || op == Op.SCREEN;
                default:
                    return false;
            }
        }
    }

    /**
     * Kind of transform a handle drives. {@link #transformOp} is the operation
     * {@link UIPropTransform#enableMode(TransformOp, Axis, Axis, GizmoDrag)}
     * expects; VIEW and TRACKBALL are rotate variants routed through their own
     * enable* calls, and SCALE_ALL is the uniform (three-axis) scale variant
     * routed through its own enable call.
     */
    public static enum Op
    {
        MOVE(TransformOp.TRANSLATE),
        SCALE(TransformOp.SCALE),
        SCALE_ALL(TransformOp.SCALE),
        ROTATE(TransformOp.ROTATE),
        VIEW(TransformOp.ROTATE),
        TRACKBALL(TransformOp.ROTATE),
        SCREEN(TransformOp.TRANSLATE);

        public final TransformOp transformOp;

        Op(TransformOp transformOp)
        {
            this.transformOp = transformOp;
        }
    }

    /**
     * A single pickable handle: its stencil id plus the operation and axes it
     * stands for. {@link #start} resolves a picked stencil id straight to one
     * of these and dispatches the matching transform — no dependence on the
     * active display {@link Mode}.
     */
    public static enum Handle
    {
        MOVE_X(STENCIL_X, Op.MOVE, Axis.X, null),
        MOVE_Y(STENCIL_Y, Op.MOVE, Axis.Y, null),
        MOVE_Z(STENCIL_Z, Op.MOVE, Axis.Z, null),
        MOVE_XZ(STENCIL_XZ, Op.MOVE, Axis.X, Axis.Z),
        MOVE_XY(STENCIL_XY, Op.MOVE, Axis.X, Axis.Y),
        MOVE_ZY(STENCIL_ZY, Op.MOVE, Axis.Z, Axis.Y),
        SCALE_X(STENCIL_SCALE_X, Op.SCALE, Axis.X, null),
        SCALE_Y(STENCIL_SCALE_Y, Op.SCALE, Axis.Y, null),
        SCALE_Z(STENCIL_SCALE_Z, Op.SCALE, Axis.Z, null),
        SCALE_XZ(STENCIL_SCALE_XZ, Op.SCALE, Axis.X, Axis.Z),
        SCALE_XY(STENCIL_SCALE_XY, Op.SCALE, Axis.X, Axis.Y),
        SCALE_ZY(STENCIL_SCALE_ZY, Op.SCALE, Axis.Z, Axis.Y),
        ROTATE_X(STENCIL_ROTATE_X, Op.ROTATE, Axis.X, null),
        ROTATE_Y(STENCIL_ROTATE_Y, Op.ROTATE, Axis.Y, null),
        ROTATE_Z(STENCIL_ROTATE_Z, Op.ROTATE, Axis.Z, null),
        TRACKBALL(STENCIL_TRACKBALL, Op.TRACKBALL, null, null),
        VIEW(STENCIL_VIEW, Op.VIEW, null, null),
        SCREEN(STENCIL_SCREEN, Op.SCREEN, null, null),
        SCALE_ALL(STENCIL_SCALE_ALL, Op.SCALE_ALL, null, null);

        public final int index;
        public final Op op;
        public final Axis axis;
        public final Axis axis2;

        Handle(int index, Op op, Axis axis, Axis axis2)
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

    /** Capabilities of the target, shared by visual handles, picking, and hotkeys. */
    public static final class HandleMask
    {
        public static final HandleMask ALL = new HandleMask(EnumSet.allOf(Op.class), EnumSet.allOf(Axis.class));
        private final EnumSet<Op> ops;
        private final EnumSet<Axis> rotateAxes;

        public static HandleMask of(EnumSet<Op> ops, EnumSet<Axis> rotateAxes)
        {
            return new HandleMask(ops, rotateAxes);
        }

        private HandleMask(EnumSet<Op> ops, EnumSet<Axis> rotateAxes)
        {
            this.ops = EnumSet.copyOf(ops);
            this.rotateAxes = EnumSet.copyOf(rotateAxes);
        }

        public boolean allows(Op op)
        {
            return this.ops.contains(op);
        }

        public boolean allows(Handle handle)
        {
            return handle != null && this.allows(handle.op)
                && (handle.op != Op.ROTATE || this.rotateAxes.contains(handle.axis));
        }
    }

    public static final class VisualState
    {
        private final Matrix4f renderMatrix = new Matrix4f();
        private final Matrix4f sphereMatrix = new Matrix4f();
        private boolean hasRenderMatrix;
        private boolean hasSphereMatrix;
        private float sphereLocalRadius;
        private float viewportScale = 1F;
        private float viewportHeight;
        private TransformSpace space;
        private HandleMask handleMask = HandleMask.ALL;
        private boolean sphereHovered;
    }

    public void render(PoseStack stack, HandleMask mask)
    {
        HandleMask previous = this.handleMask;
        this.handleMask = mask;
        try
        {
            this.render(stack);
        }
        finally
        {
            this.handleMask = previous;
        }
    }
}
