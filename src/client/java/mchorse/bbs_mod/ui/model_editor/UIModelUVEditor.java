package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.CubeFace;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelUV;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.UICanvasEditor;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * The texture with the picked cube's sides drawn on it, the way an outliner's UV view shows them:
 * every side of the cube is a rectangle over the sheet, the one being worked on is lit and carries
 * four corner handles. Clicking a rectangle picks that side, dragging inside it slides the side
 * over the texture, dragging a handle stretches it — all in whole pixels of the sheet. What a click
 * would take lights up under the cursor first.
 *
 * <p>Which side is picked, and what a drag writes, belong to the panel around it
 * ({@link UIModelCubeUV}): this is the picture and the mouse, and every change goes back out
 * through it so it lands in the model's undo like a typed number does.</p>
 *
 * <p>A side is kept as its two CORNERS, not as a box: the format says a mirrored side with the
 * second corner before the first, and a drag must carry that through rather than quietly
 * straightening it. So handle 0 is the corner {@code (x1, y1)} wherever it happens to be on
 * screen, and dragging it always moves those two numbers.</p>
 */
public class UIModelUVEditor extends UICanvasEditor
{
    /** How near a corner the cursor counts as grabbing it — the crop editor's reach. */
    private static final int HANDLE = 5;

    /** Dragging the inside of a side rather than one of its corners. */
    private static final int MOVE = 4;

    private final UIModelCubeUV host;

    private ModelInstance instance;
    private Model model;
    private ModelCube cube;

    /** The sheet {@link #setSize} was last given, so the view isn't reset under a zoom every frame. */
    private int sheetWidth = -1;
    private int sheetHeight = -1;

    private int handle = -1;
    private float startX1;
    private float startY1;
    private float startX2;
    private float startY2;

    public UIModelUVEditor(UIModelCubeUV host)
    {
        this.host = host;

        /* The canvas's own column of fields belongs to the crop editor it was written for; here the
         * rows live under the picture, where there is room for them. */
        this.removeAll();
    }

    /** Bind to what is picked; the view is only reset when the sheet it shows actually changed. */
    public void fill(ModelInstance instance, Model model, ModelCube cube)
    {
        this.instance = instance;
        this.model = model;
        this.cube = cube;

        int width = model == null ? 0 : model.textureWidth;
        int height = model == null ? 0 : model.textureHeight;

        if (width > 0 && height > 0 && (width != this.sheetWidth || height != this.sheetHeight))
        {
            this.sheetWidth = width;
            this.sheetHeight = height;

            this.setSize(width, height);
        }
    }

    /* Interaction */

    /** The wheel zooms the sheet and stops there; the rows under it must not scroll away with it. */
    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        super.subMouseScrolled(context);

        return this.area.isInside(context.mouseX, context.mouseY);
    }

    @Override
    protected void startDragging(UIContext context)
    {
        super.startDragging(context);

        this.handle = -1;

        if (this.mouse != 0 || this.cube == null)
        {
            return;
        }

        ModelUV uv = this.cube.getUV(this.host.face());
        int corner = this.handleAt(uv, context.mouseX, context.mouseY);

        if (corner >= 0)
        {
            this.grab(uv, corner);

            return;
        }

        CubeFace found = this.faceAt(context.mouseX, context.mouseY);

        if (found != null)
        {
            this.host.pickFace(found);
            this.grab(this.cube.getUV(found), MOVE);
        }
    }

    /**
     * Which corner of the picked side the cursor is on, or -1. The corners come first, before any
     * side: their handles sit on the rectangle's edge, where the rectangle itself would otherwise
     * take the click.
     */
    private int handleAt(ModelUV uv, int mouseX, int mouseY)
    {
        if (uv == null)
        {
            return -1;
        }

        for (int i = 0; i < 4; i++)
        {
            Area at = this.corner(uv, i);

            if (Math.abs(at.x - mouseX) <= HANDLE && Math.abs(at.y - mouseY) <= HANDLE)
            {
                return i;
            }
        }

        return -1;
    }

    /** The drawn side under the cursor, the smallest of them where they overlap; null over none. */
    private CubeFace faceAt(int mouseX, int mouseY)
    {
        CubeFace found = null;
        int smallest = Integer.MAX_VALUE;

        for (CubeFace face : ModelFaces.FACES)
        {
            ModelUV uv = this.cube.getUV(face);
            Area box = uv == null ? null : this.box(uv);

            if (box == null || !box.isInside(mouseX, mouseY))
            {
                continue;
            }

            int size = box.w * box.h;

            if (size < smallest)
            {
                smallest = size;
                found = face;
            }
        }

        return found;
    }

    private void grab(ModelUV uv, int handle)
    {
        this.host.beginFaceDrag();
        this.handle = handle;
        this.startX1 = uv.sx();
        this.startY1 = uv.sy();
        this.startX2 = uv.ex();
        this.startY2 = uv.ey();
    }

    @Override
    protected void dragging(UIContext context)
    {
        super.dragging(context);

        if (!this.dragging || this.mouse != 0 || this.handle < 0)
        {
            return;
        }

        float dx = (context.mouseX - this.lastX) / (float) this.scaleX.getZoom();
        float dy = (context.mouseY - this.lastY) / (float) this.scaleY.getZoom();

        /* Shift keeps the drag on the axis it has gone furthest along, so a side slides straight. */
        if (Window.isShiftPressed())
        {
            if (Math.abs(dx) > Math.abs(dy))
            {
                dy = 0F;
            }
            else
            {
                dx = 0F;
            }
        }

        float x1 = this.startX1;
        float y1 = this.startY1;
        float x2 = this.startX2;
        float y2 = this.startY2;

        if (this.handle == MOVE)
        {
            x1 += dx;
            x2 += dx;
            y1 += dy;
            y2 += dy;
        }
        else
        {
            if (this.handle == 0 || this.handle == 3)
            {
                x1 += dx;
            }
            else
            {
                x2 += dx;
            }

            if (this.handle == 0 || this.handle == 1)
            {
                y1 += dy;
            }
            else
            {
                y2 += dy;
            }
        }

        this.host.dragFace(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (!this.isDragOwnedBy(context.mouseButton))
        {
            return super.subMouseReleased(context);
        }

        boolean applied = false;
        try
        {
            this.dragging(context);
            applied = true;
        }
        finally
        {
            this.retireOwnedDrag(context.mouseButton);
            if (this.handle >= 0)
            {
                this.handle = -1;
                if (applied) this.host.endFaceDrag();
                else this.host.cancelFaceDrag();
            }
        }

        return true;
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        if (!this.retireOwnedDrag(context.mouseButton)) return;

        if (this.handle >= 0)
        {
            this.handle = -1;
            this.host.cancelFaceDrag();
        }
    }

    /* Rendering */

    /** Darker than the panel around it, the way every canvas of the editor sits in its page. */
    @Override
    protected void renderBackground(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.deepSurface());
    }

    @Override
    protected boolean shouldDrawCanvas(UIContext context)
    {
        return this.model != null && this.sheetWidth > 0;
    }

    @Override
    protected void renderCanvasFrame(UIContext context)
    {
        Area sheet = this.calculate(-this.w / 2, -this.h / 2, this.w / 2, this.h / 2);

        if (this.instance != null)
        {
            context.batcher.fullTexturedBox(context.render.getTextures().getTexture(this.instance.getTexture()), sheet.x, sheet.y, sheet.w, sheet.h);
        }

        if (this.cube == null)
        {
            return;
        }

        int accent = BBSSettings.accentColorRGB();
        CubeFace picked = this.host.face();
        ModelUV uv = this.cube.getUV(picked);

        /* What a click would take, lit under the cursor before it's pressed: a corner of the picked
         * side, else the side itself — worked out the way the click works it out. Nothing is lit
         * while a drag is on; the dragged side already is. */
        boolean hovering = !this.dragging && this.area.isInside(context);
        int hoveredCorner = hovering ? this.handleAt(uv, context.mouseX, context.mouseY) : -1;
        CubeFace hovered = !hovering ? null : hoveredCorner >= 0 ? picked : this.faceAt(context.mouseX, context.mouseY);

        for (CubeFace face : ModelFaces.FACES)
        {
            ModelUV other = this.cube.getUV(face);

            if (other == null || face == picked)
            {
                continue;
            }

            Area box = this.box(other);
            boolean lit = face == hovered;

            context.batcher.normalizedBox(box.x, box.y, box.ex(), box.ey(), Colors.setA(Colors.WHITE, lit ? 0.3F : 0.15F));
            context.batcher.outline(box.x, box.y, box.ex(), box.ey(), Colors.setA(Colors.WHITE, lit ? 0.85F : 0.35F));
        }

        if (uv == null)
        {
            return;
        }

        Area box = this.box(uv);

        context.batcher.normalizedBox(box.x, box.y, box.ex(), box.ey(), Colors.setA(accent, hovered == picked && hoveredCorner < 0 ? 0.4F : 0.25F));
        context.batcher.outline(box.x, box.y, box.ex(), box.ey(), Colors.A100 | accent);

        for (int i = 0; i < 4; i++)
        {
            Area at = this.corner(uv, i);
            int size = i == hoveredCorner ? 4 : 3;

            context.batcher.box(at.x - size, at.y - size, at.x + size, at.y + size, Colors.WHITE);
            context.batcher.box(at.x - size + 1, at.y - size + 1, at.x + size - 1, at.y + size - 1, i == hoveredCorner ? Colors.WHITE : Colors.A100 | accent);
        }
    }

    /* Where a side is on screen */

    /** The side's rectangle, straightened out — a mirrored side covers the same pixels either way. */
    private Area box(ModelUV uv)
    {
        return new Area(this.calculate(
            Math.round(Math.min(uv.sx(), uv.ex())) - this.w / 2,
            Math.round(Math.min(uv.sy(), uv.ey())) - this.h / 2,
            Math.round(Math.max(uv.sx(), uv.ex())) - this.w / 2,
            Math.round(Math.max(uv.sy(), uv.ey())) - this.h / 2
        ));
    }

    /** One of the side's corners, as the numbers put it: 0 is (x1, y1), then clockwise. */
    private Area corner(ModelUV uv, int index)
    {
        float x = index == 0 || index == 3 ? uv.sx() : uv.ex();
        float y = index == 0 || index == 1 ? uv.sy() : uv.ey();

        return new Area(this.calculate(Math.round(x) - this.w / 2, Math.round(y) - this.h / 2, Math.round(x) - this.w / 2, Math.round(y) - this.h / 2));
    }
}
