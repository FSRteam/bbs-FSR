package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.cubic.data.model.CubeFace;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelUV;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcons;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragEndEvent;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The unwrap pane of the model editor: the texture with the picked cube's sides drawn over it, and
 * under the picture everything those sides are made of — which one is being worked on, whether it
 * is drawn at all, the two corners it covers, its mirrors and its quarter turn, the box unwrap that
 * lays all six out at once, and the size of the sheet they are all measured against.
 *
 * <p>It is one pane rather than a row here and a row there: the picture is the subject and the rows
 * under it say the same thing in numbers, so a side can be dragged into place or typed into place
 * and the other half follows either way.</p>
 *
 * <p>A side is shown as its two CORNERS rather than as a corner and a size, because a mirrored side
 * is exactly what the format calls a NEGATIVE size: carrying the corners through an edit keeps the
 * mirror, where carrying a width would quietly straighten it out. The eye in the side's icons takes
 * the side's drawing away and gives it back, not its unwrap: a side taken away keeps its place on the
 * sheet, shows it in the rows, dimmed, and comes back to it.</p>
 *
 * <p>The sheet's size is the model's, not the cube's — the {@code texture} of the file rather than
 * the size of the PNG — so changing it re-reads every cube's unwrap against the new one.</p>
 */
public class UIModelCubeUV extends UIElement
{
    /** Which side the rows are on. Kept across picks and models: it is a mode of working. */
    private static CubeFace picked = CubeFace.FRONT;

    /** How tall the strip of sides over the picture stands: a row's height, like every strip of icons. */
    private static final int FACES_HEIGHT = UIConstants.CONTROL_HEIGHT;

    private final UIModelGeometryEditor editor;

    private final UIModelUVEditor canvas;
    private final UIScrollView rows;

    private final UIIcons faces;
    private final UITrackpad[] corners = new UITrackpad[4];
    private final UIElement cornerRows;

    /** What can be done to a drawn side — mirrored, turned, fitted, spread; they go dead on a side that isn't drawn. */
    private final UIIcon[] sideActions;
    private final UITrackpad boxU;
    private final UITrackpad boxV;
    private final UITrackpad sheetWidth;
    private final UITrackpad sheetHeight;

    /** Whether the box unwrap lays the sides out mirrored — a switch on the box row, not a live edit. */
    private boolean boxMirror;

    public UIModelCubeUV(UIModelGeometryEditor editor)
    {
        this.editor = editor;
        this.canvas = new UIModelUVEditor(this);
        this.canvas.relative(this).x(0).w(1F);

        /* Six sides as six arrows, the way a weld names one: they all fit, and the lit one says
         * which side is being worked on without a dropdown to open. It heads the pane, over the
         * picture as a tab bar would, since the picture and the rows under it both follow it. */
        this.faces = new UIIcons((b) -> this.pickFace(ModelFaces.FACES.get(b.getValue())));

        for (CubeFace face : ModelFaces.FACES)
        {
            this.faces.add(ModelFaces.icon(face), ModelFaces.label(face));
        }

        this.faces.stretch();
        this.faces.relative(this).x(0).y(0).w(1F).h(FACES_HEIGHT);
        this.faces.setValue(picked.ordinal());

        IKey[] labels = {
            UIKeys.MODEL_EDITOR_MODEL_UV_X1, UIKeys.MODEL_EDITOR_MODEL_UV_Y1,
            UIKeys.MODEL_EDITOR_MODEL_UV_X2, UIKeys.MODEL_EDITOR_MODEL_UV_Y2
        };

        for (int i = 0; i < this.corners.length; i++)
        {
            int index = i;
            UITrackpad pad = new UITrackpad((v) -> this.setCorner(index, v.floatValue()));

            pad.tooltip(labels[i]);
            pad.getEvents().register(UITrackpadDragEndEvent.class, (e) -> this.editor.closeCubeEdit());
            this.corners[i] = pad;
        }

        /* Whether the side is drawn leads the side's own icons: the eye the rest of the editor shows
         * visibility with, open or shut as the side is. It stays live on a side that isn't drawn — it
         * is how that side comes back. */
        UIIcon drawn = new UIIcon(() -> this.uv() != null ? Icons.VISIBLE : Icons.INVISIBLE, (b) -> this.setDrawn(this.uv() == null));
        UIIcon flipX = new UIIcon(Icons.FLIP_HORIZONTAL, (b) -> this.change(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_FLIP, ModelUV::flipX));
        UIIcon flipY = new UIIcon(Icons.FLIP_VERTICAL, (b) -> this.change(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_FLIP, ModelUV::flipY));
        UIIcon rotate = new UIIcon(Icons.REFRESH, (b) -> this.change(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_ROTATE, ModelUV::rotate90));

        /* Blockbench's shortcuts for one side's unwrap, after the mirrors and the turn: as big as the
         * side itself, over the whole sheet, and this side's unwrap on every side. */
        UIIcon fit = new UIIcon(Icons.SCALE, (b) -> this.fitToSide());
        UIIcon maximize = new UIIcon(Icons.FULLSCREEN, (b) -> this.maximize());
        UIIcon applyAll = new UIIcon(Icons.GALLERY, (b) -> this.applyToAll());

        drawn.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_DRAWN);
        flipX.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_FLIP_X);
        flipY.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_FLIP_Y);
        rotate.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_ROTATE);
        fit.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_FIT);
        maximize.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_MAXIMIZE);
        applyAll.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_APPLY_ALL);

        this.cornerRows = UI.row(this.corners[0], this.corners[1], this.corners[2], this.corners[3]);
        this.sideActions = new UIIcon[]{flipX, flipY, rotate, fit, maximize, applyAll};

        /* The box unwrap: its name on a line of its own, and under it one row says the rest — from
         * where, mirrored or not, go. Going is a press rather than a live field: it throws all six
         * sides away, which is not something a stray scroll over a pad should do. */
        this.boxU = new UITrackpad((v) -> {}).integer();
        this.boxV = new UITrackpad((v) -> {}).integer();
        this.boxU.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_BOX_U);
        this.boxV.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_BOX_V);

        UIIcon mirror = new UIIcon(Icons.EXCHANGE, (b) -> this.boxMirror = !this.boxMirror);
        UIIcon apply = new UIIcon(Icons.CHECKMARK, (b) -> this.applyBoxUV());

        mirror.highlight(() -> this.boxMirror, Direction.BOTTOM);
        mirror.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_BOX_MIRROR);
        apply.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_BOX_TIP);

        UIElement box = UI.row(
            this.boxU,
            this.boxV,
            mirror.wh(UIConstants.CONTROL_HEIGHT, UIConstants.CONTROL_HEIGHT),
            apply.wh(UIConstants.CONTROL_HEIGHT, UIConstants.CONTROL_HEIGHT)
        );

        this.sheetWidth = new UITrackpad((v) -> this.setSheet()).integer().limit(1);
        this.sheetHeight = new UITrackpad((v) -> this.setSheet()).integer().limit(1);
        this.sheetWidth.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_SHEET_WIDTH);
        this.sheetHeight.tooltip(UIKeys.MODEL_EDITOR_MODEL_UV_SHEET_HEIGHT);
        this.sheetWidth.getEvents().register(UITrackpadDragEndEvent.class, (e) -> this.editor.closeCubeEdit());
        this.sheetHeight.getEvents().register(UITrackpadDragEndEvent.class, (e) -> this.editor.closeCubeEdit());

        /* Share the pane's width evenly while keeping the icons at their normal height. */
        UIElement actions = UI.row(0, 0, UIConstants.ICON_SIZE,
            drawn.w(0), flipX.w(0), flipY.w(0), rotate.w(0), fit.w(0), maximize.w(0), applyAll.w(0));

        this.rows = UI.scrollView(UIConstants.MARGIN, UIConstants.SCROLL_PADDING,
            this.cornerRows,
            actions,
            UI.label(UIKeys.MODEL_EDITOR_MODEL_UV_BOX),
            box,
            UI.label(UIKeys.MODEL_EDITOR_MODEL_UV_SHEET),
            UI.row(this.sheetWidth, this.sheetHeight)
        );
        this.rows.relative(this).x(0).w(1F);

        this.add(this.faces, this.canvas, this.rows);
    }

    /**
     * The sides head the pane and the picture sits under them. The picture is square — a sheet
     * reads as the sheet it is — and takes up to half of what is left, so the rows under it are
     * never squeezed out on a short window.
     */
    @Override
    protected void afterResizeApplied()
    {
        super.afterResizeApplied();

        int side = Math.max(0, Math.min(this.area.w, (this.area.h - FACES_HEIGHT) / 2));

        this.canvas.y(FACES_HEIGHT).h(side);
        this.rows.y(FACES_HEIGHT + side).h(1F, -FACES_HEIGHT - side);
    }

    /* Filling */

    /** The rows take the picked cube, and the sheet row the model. */
    public void fill()
    {
        Model model = this.editor.pickedModel();

        this.faces.setValue(picked.ordinal());
        this.sheetWidth.setValue(model == null ? 0D : model.textureWidth);
        this.sheetHeight.setValue(model == null ? 0D : model.textureHeight);

        UIUtils.setEnabledDeep(this.rows, this.editor.pickedCube() != null);
        this.faces.setEnabled(this.editor.pickedCube() != null);

        this.fillFace();
    }

    /** The side's own rows; a side that isn't drawn shows the corners it will come back to, dead. */
    private void fillFace()
    {
        ModelUV uv = this.uv();
        ModelCube cube = this.editor.pickedCube();
        ModelUV shown = uv != null || cube == null ? uv : cube.getHiddenUV(picked);

        for (int i = 0; i < this.corners.length; i++)
        {
            this.corners[i].setValue(shown == null ? 0D : this.corner(shown, i));
        }

        UIUtils.setEnabledDeep(this.cornerRows, uv != null);

        for (UIIcon action : this.sideActions)
        {
            action.setEnabled(uv != null);
        }
    }

    /** Whether one of the pads is being dragged — the model settles when it is let go. */
    public boolean dragging()
    {
        for (UITrackpad pad : new UITrackpad[]{this.corners[0], this.corners[1], this.corners[2], this.corners[3], this.sheetWidth, this.sheetHeight})
        {
            if (pad.isDragging())
            {
                return true;
            }
        }

        return this.canvas.dragging;
    }

    /** The picture follows the pick every frame: a sheet resized elsewhere has to reach it too. */
    @Override
    public void render(UIContext context)
    {
        this.canvas.fill(this.editor.pickedInstance(), this.editor.pickedModel(), this.editor.pickedCube());

        super.render(context);
    }

    /* What the picture asks of the model */

    /** The side the rows and the picture are on. */
    CubeFace face()
    {
        return picked;
    }

    /** A side clicked on the picture, or picked from the arrows. */
    void pickFace(CubeFace face)
    {
        picked = face;

        this.faces.setValue(face.ordinal());
        this.fillFace();
    }

    /** A drag on the picture: the side's four numbers at once, merging into one undo step. */
    void dragFace(float x1, float y1, float x2, float y2)
    {
        ModelUV uv = this.uv();

        if (uv == null || (uv.sx() == x1 && uv.sy() == y1 && uv.ex() == x2 && uv.ey() == y2))
        {
            return;
        }

        this.editor.previewCubeGesture(() -> uv.from(x1, y1, x2, y2));
        this.fillFace();
    }

    void beginFaceDrag()
    {
        this.editor.beginCubeGesture();
    }

    /** The drag is over: its undo step closes and the model settles. */
    void endFaceDrag()
    {
        this.editor.finishCubeGesture(this.faceLabel(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV), true);
    }

    void cancelFaceDrag()
    {
        this.editor.finishCubeGesture(this.faceLabel(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV), false);
    }

    /* Editing from the rows */

    /** The unwrap of the side the rows are on, or null with no cube picked or the side not drawn. */
    private ModelUV uv()
    {
        ModelCube cube = this.editor.pickedCube();

        return cube == null ? null : cube.getUV(picked);
    }

    private double corner(ModelUV uv, int index)
    {
        return switch (index)
        {
            case 0 -> uv.sx();
            case 1 -> uv.sy();
            case 2 -> uv.ex();
            default -> uv.ey();
        };
    }

    /** One corner typed or dragged; the other three are written back as they stand, mirror and all. */
    private void setCorner(int index, float value)
    {
        ModelUV uv = this.uv();

        if (uv == null || this.corner(uv, index) == value)
        {
            return;
        }

        float[] c = {uv.sx(), uv.sy(), uv.ex(), uv.ey()};

        c[index] = value;

        this.editor.editCube(this.faceLabel(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV), "uv:" + picked.name(), () -> uv.from(c[0], c[1], c[2], c[3]));
    }

    /**
     * Whether the side is drawn at all. Off keeps its unwrap on the cube, on brings it back there —
     * or, for a side that was never drawn, gives it the side's own size from the sheet's corner.
     */
    private void setDrawn(boolean on)
    {
        ModelCube cube = this.editor.pickedCube();

        if (cube == null || (cube.getUV(picked) != null) == on)
        {
            return;
        }

        CubeFace face = picked;

        this.editor.editCube(this.faceLabel(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_DRAWN), null, () ->
        {
            if (on)
            {
                cube.showUV(face);
            }
            else
            {
                cube.hideUV(face);
            }
        });
        this.fillFace();
        this.editor.closeCubeEdit();
    }

    /** A mirror or a quarter turn of the side, each its own undo step. */
    private void change(IKey label, Consumer<ModelUV> change)
    {
        ModelUV uv = this.uv();

        if (uv == null)
        {
            return;
        }

        this.editor.editCube(this.faceLabel(label), null, () -> change.accept(uv));
        this.fillFace();
        this.editor.closeCubeEdit();
    }

    /**
     * Blockbench's auto UV: the side's unwrap as big as the side itself — a pixel of the sheet per
     * pixel of the model — from the same first corner and mirrored the same way. A side turned a
     * quarter lies across the sheet the other way round, so its width and height swap.
     */
    private void fitToSide()
    {
        ModelCube cube = this.editor.pickedCube();
        ModelUV uv = this.uv();

        if (uv == null)
        {
            return;
        }

        Vector2f size = cube.faceSize(picked);
        boolean turned = uv.rotation % 180F != 0F;
        float width = turned ? size.y : size.x;
        float height = turned ? size.x : size.y;
        float x2 = uv.sx() + (uv.size.x < 0 ? -width : width);
        float y2 = uv.sy() + (uv.size.y < 0 ? -height : height);

        if (!same(uv, uv.sx(), uv.sy(), x2, y2))
        {
            this.change(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_FIT, (side) -> side.from(side.sx(), side.sy(), x2, y2));
        }
    }

    /** Blockbench's maximize: the side's unwrap over the whole sheet, mirrored the same way. */
    private void maximize()
    {
        Model model = this.editor.pickedModel();
        ModelUV uv = this.uv();

        if (uv == null || model == null)
        {
            return;
        }

        float width = model.textureWidth;
        float height = model.textureHeight;
        float x1 = uv.size.x < 0 ? width : 0F;
        float y1 = uv.size.y < 0 ? height : 0F;
        float x2 = width - x1;
        float y2 = height - y1;

        if (!same(uv, x1, y1, x2, y2))
        {
            this.change(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_MAXIMIZE, (side) -> side.from(x1, y1, x2, y2));
        }
    }

    /**
     * Blockbench's apply to all faces: every other drawn side takes this side's unwrap — its corners
     * and its turn — for a cube that wears one patch of the sheet all over. A side that isn't drawn
     * keeps the place it will come back to.
     */
    private void applyToAll()
    {
        ModelCube cube = this.editor.pickedCube();
        ModelUV uv = this.uv();

        if (uv == null)
        {
            return;
        }

        float x1 = uv.sx();
        float y1 = uv.sy();
        float x2 = uv.ex();
        float y2 = uv.ey();
        float rotation = uv.rotation;
        List<ModelUV> others = new ArrayList<>();

        for (CubeFace face : ModelFaces.FACES)
        {
            ModelUV other = cube.getUV(face);

            if (face != picked && other != null && (!same(other, x1, y1, x2, y2) || other.rotation != rotation))
            {
                others.add(other);
            }
        }

        if (others.isEmpty())
        {
            return;
        }

        this.editor.editCube(this.faceLabel(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_APPLY_ALL), null, () ->
        {
            for (ModelUV other : others)
            {
                other.from(x1, y1, x2, y2);
                other.rotation = rotation;
            }
        });
        this.fillFace();
        this.editor.closeCubeEdit();
    }

    /** Whether a side's unwrap already has these two corners. */
    private static boolean same(ModelUV uv, float x1, float y1, float x2, float y2)
    {
        return uv.sx() == x1 && uv.sy() == y1 && uv.ex() == x2 && uv.ey() == y2;
    }

    /**
     * All six sides laid out as a box from one corner of the sheet — what a new cube is given. The box
     * places every side, but whether a side is drawn stays the eye's to say: one taken away stays
     * away and keeps its new place for when it comes back.
     */
    private void applyBoxUV()
    {
        ModelCube cube = this.editor.pickedCube();

        if (cube == null)
        {
            return;
        }

        Vector2f at = new Vector2f((float) this.boxU.getValue(), (float) this.boxV.getValue());
        boolean mirror = this.boxMirror;

        this.editor.editCube(UIKeys.MODEL_EDITOR_MODEL_UNDO_UV_BOX, null, () ->
        {
            List<CubeFace> hidden = new ArrayList<>();

            for (CubeFace face : ModelFaces.FACES)
            {
                if (cube.getUV(face) == null)
                {
                    hidden.add(face);
                }
            }

            cube.setupBoxUV(at, mirror);
            hidden.forEach(cube::hideUV);
        });
        this.fillFace();
        this.editor.closeCubeEdit();
    }

    private void setSheet()
    {
        this.editor.setTextureSize((int) this.sheetWidth.getValue(), (int) this.sheetHeight.getValue());
    }

    private IKey faceLabel(IKey label)
    {
        return label.format(ModelFaces.label(picked));
    }
}
