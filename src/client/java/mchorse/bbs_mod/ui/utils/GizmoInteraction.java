package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.colors.Colors;
import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * Shared gizmo picking helper for viewports.
 */
public class GizmoInteraction
{
    private static final int SPHERE_PICK_MIN_RADIUS_PX = 12;
    private static final int BONE_VS_SPHERE_DRAG_THRESHOLD_PX = 4;

    private final GizmoViewport viewport;
    private final Vector2f sphereScreenCenter = new Vector2f();
    private final MouseGestureOwnership gestureOwnership = new MouseGestureOwnership();
    private long gestureGeneration;

    private boolean sphereHovered;
    private boolean gizmoActive;
    private int pendingDownX;
    private int pendingDownY;
    private int pendingButton = -1;
    private Form pendingPickForm;
    private String pendingPickBone;

    public GizmoInteraction(GizmoViewport viewport)
    {
        this.viewport = viewport;
    }

    public boolean isSphereHovered()
    {
        return this.sphereHovered;
    }

    /** Return the generation which owns the current viewport gesture, or zero. */
    public long gestureGeneration()
    {
        return this.gestureGeneration;
    }

    public boolean mouseClicked(UIContext context)
    {
        return this.mouseClickedHandle(context) || this.mouseClickedSphere(context);
    }

    public boolean mouseClickedHandle(UIContext context)
    {
        if (context.mouseButton != 0)
        {
            return false;
        }

        StencilFormFramebuffer stencil = this.viewport.getGizmoStencil();

        if (stencil.hasPicked())
        {
            int index = stencil.getIndex();

            if (index >= Gizmo.STENCIL_X && index <= Gizmo.STENCIL_MAX)
            {
                return this.startGizmo(context, index);
            }
        }

        return false;
    }

    public boolean mouseClickedSphere(UIContext context)
    {
        if (context.mouseButton != 0 || !this.sphereHovered || !Gizmo.INSTANCE.isSphereInteractive())
        {
            return false;
        }

        StencilFormFramebuffer stencil = this.viewport.getGizmoStencil();

        if (stencil.hasPicked())
        {
            Pair<Form, String> pair = stencil.getPicked();

            if (pair != null && pair.a != null)
            {
                long generation = this.gestureOwnership.acquireToken(context.mouseButton);

                if (generation == 0L)
                {
                    return false;
                }

                this.gestureGeneration = generation;
                this.pendingDownX = context.mouseX;
                this.pendingDownY = context.mouseY;
                this.pendingButton = context.mouseButton;
                this.pendingPickForm = pair.a;
                this.pendingPickBone = pair.b == null ? "" : pair.b;

                return true;
            }

            return false;
        }

        return this.startGizmo(context, Gizmo.STENCIL_TRACKBALL);
    }

    public boolean mouseReleased(UIContext context)
    {
        boolean handled = false;
        long generation = this.gestureGeneration;

        if (this.pendingPickForm != null
            && context.mouseButton == this.pendingButton
            && this.retireGesture(context.mouseButton, generation))
        {
            Form form = this.pendingPickForm;
            String bone = this.pendingPickBone;

            this.clearPending();
            this.viewport.pickGizmoForm(context, form, bone);
            handled = true;
        }

        if (this.gizmoActive && this.retireGesture(context.mouseButton, generation))
        {
            this.gizmoActive = false;
            Gizmo.INSTANCE.stop();
            handled = true;
        }

        return handled;
    }

    public void update(UIContext context)
    {
        this.promotePendingPick(context);
        this.updateSphereHover(context);
    }

    /**
     * Draw the gizmo's visual over this viewport in the host's UI pass, straight
     * through the UI pipeline, so its translucent parts (rotation sphere, sweep
     * pie, view ring) blend correctly — the world shaders that used to draw it
     * mangled their transparency. It pairs with the model-view captured in the
     * viewport's world / 3D pass ({@link Gizmo#captureVisual} or
     * {@link Gizmo#renderStencil}). Call before {@link #update} so the sphere's
     * on-screen radius is current for the hover check.
     */
    public void renderGizmo(UIContext context)
    {
        Gizmo.INSTANCE.renderInterface(context, this.viewport.getGizmoProjection(), this.viewport.getGizmoArea());
    }

    public void renderSphereHighlight(UIContext context)
    {
        Gizmo.INSTANCE.renderSphereHighlight(context, this.viewport.getGizmoProjection(), this.viewport.getGizmoArea());
    }

    public void renderReadout(UIContext context)
    {
        String readout = Gizmo.INSTANCE.getDragReadout();
        Area area = this.viewport.getGizmoArea();
        Matrix4f projection = this.viewport.getGizmoProjection();

        if (readout == null || area == null || projection == null)
        {
            return;
        }

        Vector2f center = new Vector2f();

        if (!Gizmo.INSTANCE.computeScreenCenter(projection, area.x, area.y, area.w, area.h, center))
        {
            return;
        }

        FontRenderer font = context.batcher.getFont();
        int x = (int) (center.x - font.getWidth(readout) / 2F);
        int y = (int) (center.y - 28);

        context.batcher.textCard(readout, x, y, Colors.WHITE, Colors.A75);
    }

    public void stop()
    {
        this.cancel();
    }

    /**
     * Cancel a viewport lifecycle gesture. Unlike {@link #mouseReleased}, this
     * rolls the transform back and never commits the preview values.
     */
    public void cancel()
    {
        long generation = this.gestureGeneration;
        boolean cancelTransform = this.gizmoActive;

        /* Retire the owner before invoking rejectChanges(). A reject callback
         * may synchronously start a replacement gesture; that replacement must
         * acquire a fresh generation and survive this cancellation. */
        this.gestureOwnership.cancel();

        if (this.gestureGeneration == generation)
        {
            this.gizmoActive = false;
            this.gestureGeneration = 0L;
            this.clearPending();
            this.sphereHovered = false;
            Gizmo.INSTANCE.setSphereHovered(false);
        }

        if (cancelTransform)
        {
            Gizmo.INSTANCE.cancel();
        }
    }

    /**
     * Cancel only the gesture owned by the initiating button and generation.
     * A cancellation for another button or a stale generation is ignored.
     */
    public boolean cancel(int button, long generation)
    {
        if (!this.gestureOwnership.release(button, generation))
        {
            return false;
        }

        boolean currentGeneration = this.gestureGeneration == generation;
        boolean cancelTransform = currentGeneration && this.gizmoActive;

        /* Clear this generation before rejecting the transform so a callback
         * can install a new owner without being mistaken for stale state. */
        if (currentGeneration)
        {
            this.gizmoActive = false;
            this.gestureGeneration = 0L;
            this.clearPending();
            this.sphereHovered = false;
            Gizmo.INSTANCE.setSphereHovered(false);
        }

        if (cancelTransform)
        {
            Gizmo.INSTANCE.cancel();
        }

        return true;
    }

    private boolean startGizmo(UIContext context, int index)
    {
        int ownerButton = context.mouseButton;

        long generation = this.gestureOwnership.acquireToken(ownerButton);

        if (generation == 0L)
        {
            return false;
        }

        this.gestureGeneration = generation;

        return this.startOwnedGizmo(context, index, ownerButton, generation);
    }

    private boolean startOwnedGizmo(UIContext context, int index, int ownerButton, long generation)
    {
        if (!this.gestureOwnership.isOwnedBy(ownerButton, generation))
        {
            return false;
        }

        boolean started = false;

        try
        {
            started = this.viewport.startGizmo(context, index);

            if (!this.gestureOwnership.isOwnedBy(ownerButton, generation))
            {
                return false;
            }

            this.gizmoActive = started;

            return started;
        }
        finally
        {
            if (!started)
            {
                this.retireGesture(ownerButton, generation);
            }
        }
    }

    private void promotePendingPick(UIContext context)
    {
        if (this.pendingPickForm == null)
        {
            return;
        }

        int dx = context.mouseX - this.pendingDownX;
        int dy = context.mouseY - this.pendingDownY;

        if (dx * dx + dy * dy > BONE_VS_SPHERE_DRAG_THRESHOLD_PX * BONE_VS_SPHERE_DRAG_THRESHOLD_PX)
        {
            int ownerButton = this.pendingButton;
            long generation = this.gestureGeneration;

            this.clearPending();
            this.startOwnedGizmo(context, Gizmo.STENCIL_TRACKBALL, ownerButton, generation);
        }
    }

    private boolean retireGesture(int button, long generation)
    {
        if (!this.gestureOwnership.release(button, generation))
        {
            return false;
        }

        if (this.gestureGeneration == generation)
        {
            this.gestureGeneration = 0L;
        }

        return true;
    }

    private void updateSphereHover(UIContext context)
    {
        boolean hover = false;

        if (Gizmo.INSTANCE.isSphereInteractive())
        {
            if (Gizmo.INSTANCE.isTrackballDragging())
            {
                hover = true;
            }
            else if (!this.stencilWouldWinSpherePick())
            {
                Matrix4f projection = this.viewport.getGizmoProjection();
                Area area = this.viewport.getGizmoArea();

                if (projection != null && area != null && area.isInside(context)
                    && Gizmo.INSTANCE.computeScreenCenter(projection, area.x, area.y, area.w, area.h, this.sphereScreenCenter))
                {
                    float radius = Math.max(SPHERE_PICK_MIN_RADIUS_PX, Gizmo.INSTANCE.computeScreenRadius(projection, area.x, area.y, area.w, area.h));
                    float dx = context.mouseX - this.sphereScreenCenter.x;
                    float dy = context.mouseY - this.sphereScreenCenter.y;

                    hover = dx * dx + dy * dy <= radius * radius;
                }
            }
        }

        this.sphereHovered = hover;
        Gizmo.INSTANCE.setSphereHovered(hover);
    }

    private boolean stencilWouldWinSpherePick()
    {
        StencilFormFramebuffer stencil = this.viewport.getGizmoStencil();

        if (!stencil.hasPicked())
        {
            return false;
        }

        int index = stencil.getIndex();

        return index >= Gizmo.STENCIL_X && index <= Gizmo.STENCIL_MAX;
    }

    private void clearPending()
    {
        this.pendingButton = -1;
        this.pendingPickForm = null;
        this.pendingPickBone = null;
    }
}
