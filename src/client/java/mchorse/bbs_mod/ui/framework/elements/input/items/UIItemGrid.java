package mchorse.bbs_mod.ui.framework.elements.input.items;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.GridLayout;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Items as cells of a grid laid out by a {@link GridLayout}: a caret between cells for a
 * drop, a stack of cards for the ghost.
 *
 * <p>Two ways of running: down the usual rows, or {@link #horizontal()} — one row of cells
 * that scrolls sideways, for a strip of frames under a canvas. The geometry stays the layout's;
 * only which axis is the long one changes.</p>
 *
 * <p>FSR note: this is the minimal wave the frame strip needed (upstream aebdf23b1 + the
 * horizontal mode of 1f51d0e25). Deliberately left out until B7's UI-infra wave adopts it for
 * the browser: the embedded-in-a-parent-scroll-view mode (needs UIContext.invalidateLayout),
 * the hover caption card (needs TooltipPlacement) and per-cell quick actions.</p>
 *
 * @param <T> what's in the cells
 */
public abstract class UIItemGrid<T> extends UIItems<T>
{
    public static final int GHOST_SIZE = 48;

    protected final GridLayout layout;
    protected final CellState state = new CellState();

    private int cellSize = 60;
    private boolean horizontal;

    /* What's under the cursor, refreshed every frame */
    protected int hoverIndex = -1;

    public UIItemGrid(Consumer<List<T>> callback, GridLayout layout)
    {
        this(callback, null, layout);
    }

    public UIItemGrid(Consumer<List<T>> callback, BiPredicate<T, T> same, GridLayout layout)
    {
        this(callback, same, null, null, layout);
    }

    /** See {@link UIItems#UIItems(Consumer, BiPredicate, Selection, ItemDrag)} for the shared selection and drag. */
    public UIItemGrid(Consumer<List<T>> callback, BiPredicate<T, T> same, Selection<T> selection, ItemDrag<T> drag, GridLayout layout)
    {
        super(callback, same, selection, drag);

        this.layout = layout;
        this.scroll.scrollSpeed = 40;
    }

    /* Settings */

    /** One row of cells scrolling sideways, instead of rows scrolling down. */
    public UIItemGrid<T> horizontal()
    {
        this.horizontal = true;
        this.layout.strip();
        this.scroll.direction = ScrollDirection.HORIZONTAL;

        return this;
    }

    public boolean isHorizontal()
    {
        return this.horizontal;
    }

    public GridLayout getLayout()
    {
        return this.layout;
    }

    public int getCellSize()
    {
        return this.cellSize;
    }

    public void setCellSize(int size)
    {
        this.cellSize = Math.max(1, size);

        this.relayout();
    }

    /* Hooks */

    /** Paint one cell; {@code state} says what overlays it gets. */
    protected abstract void renderCell(UIContext context, T item, int x, int y, int w, int h, CellState state);

    /**
     * Something under a point that takes a drop of its own (a folder cell), instead of a
     * slot between cells; null when the drop would only reorder.
     */
    protected Object dropTargetAt(int x, int y)
    {
        return null;
    }

    /**
     * Whether the cells are shown at all — a collapsed category keeps only its band.
     * Everything shows here; the collapsible-category grids of a later wave override it.
     */
    protected boolean isExpanded()
    {
        return true;
    }

    /* Layout */

    /** Lay the cells out for the current width and count, and tell the scrollbar how long that is. */
    public void relayout()
    {
        int width = Math.max(this.cellSize, this.area.w);

        this.layout.set(width, this.cellSize, this.visible().size());

        this.scroll.scrollSize = this.contentSize();
        this.scroll.clamp();
    }

    @Override
    public void resize()
    {
        super.resize();

        this.relayout();
    }

    /** Sideways, the scrolling is taken out of X rather than Y. */
    @Override
    protected int originX()
    {
        return this.horizontal ? this.area.x - (int) this.scroll.getScroll() : super.originX();
    }

    @Override
    protected int originY()
    {
        return this.horizontal ? this.area.y : super.originY();
    }

    @Override
    protected boolean clearsOnEmpty()
    {
        return true;
    }

    /* Geometry */

    @Override
    protected int indexAt(int x, int y)
    {
        if (!this.isExpanded())
        {
            return -1;
        }

        int index = this.layout.getIndex(x, y);

        return index < this.visible().size() ? index : -1;
    }

    @Override
    protected void areaOf(int index, Area out)
    {
        out.set(this.layout.getX(index), this.layout.getY(index), this.layout.getCellWidth(), this.layout.getCellHeight());
    }

    @Override
    protected int insertionAt(int x, int y)
    {
        return this.isExpanded() ? this.layout.getInsertion(x, y) : this.visible().size();
    }

    @Override
    protected int contentSize()
    {
        return this.horizontal ? this.layout.getContentWidth() : this.layout.getContentHeight(this.isExpanded());
    }

    @Override
    protected int step(int index, int dx, int dy)
    {
        int size = this.visible().size();

        if (dx != 0)
        {
            return MathUtils.clamp(index + dx, 0, size - 1);
        }

        int target = index + dy * this.layout.getPerRow();

        /* Stepping past the first or last row stays put rather than wrapping */
        return target < 0 || target >= size ? index : target;
    }

    @Override
    protected void scrollIntoView(int index)
    {
        if (index >= 0 && index < this.layout.getCount())
        {
            if (this.horizontal)
            {
                this.scroll.scrollIntoView(this.layout.getX(index), this.layout.getCellWidth() + this.layout.getGap(), this.layout.getGap());
            }
            else
            {
                this.scroll.scrollIntoView(this.layout.getY(index), this.layout.getCellHeight() + this.layout.getGap(), this.layout.getGap());
            }
        }
    }

    /* Input */

    @Override
    protected void reportDropTarget(int x, int y)
    {
        Object target = this.dropTargetAt(x, y);

        if (target != null)
        {
            this.drag.setTarget(target);
        }
        else
        {
            super.reportDropTarget(x, y);
        }
    }

    protected void updateHover(UIContext context)
    {
        boolean inside = this.area.isInside(context) && !this.drag.isActive() && !context.hasContextMenu();
        int x = this.contentX(context);
        int y = this.contentY(context);

        this.hoverIndex = inside ? this.indexAt(x, y) : -1;
    }

    /* Rendering */

    @Override
    public void render(UIContext context)
    {
        /* The count or the width may have changed under us since the last frame */
        this.relayout();

        super.render(context);
    }

    @Override
    protected void renderContent(UIContext context)
    {
        this.updateHover(context);

        if (this.isExpanded())
        {
            this.renderCells(context);
        }
    }

    /** The area the cells must fall into to be worth painting. */
    protected Area visibleWindow()
    {
        return this.area;
    }

    protected void renderCells(UIContext context)
    {
        List<T> visible = this.visible();
        Area window = this.visibleWindow();
        int cellW = this.layout.getCellWidth();
        int cellH = this.layout.getCellHeight();
        int ox = this.originX();
        int oy = this.originY();

        for (int i = 0; i < visible.size(); i++)
        {
            int cx = ox + this.layout.getX(i);
            int cy = oy + this.layout.getY(i);

            /* Only what's in view is worth painting: along whichever axis the grid runs */
            if (window != null && (this.horizontal
                ? cx + cellW < window.x || cx > window.ex()
                : cy + cellH < window.y || cy > window.ey()))
            {
                continue;
            }

            T item = visible.get(i);

            this.state.reset();
            this.state.hover = i == this.hoverIndex;
            this.state.picked = this.selection.contains(item);
            this.state.selected = this.state.picked && !this.selection.isGroup();
            this.state.dragged = this.drag.isDragging(item);
            this.state.dropTarget = this.drag.isTarget(item);

            this.renderCell(context, item, cx, cy, cellW, cellH, this.state);
        }
    }

    /** The caret between cells where the drop lands. */
    @Override
    protected void renderInsertion(UIContext context, int insertion)
    {
        int count = this.visible().size();
        int primary = BBSSettings.primaryColor.get();

        if (insertion < 0)
        {
            return;
        }

        if (!this.isExpanded() || count == 0)
        {
            context.batcher.outline(this.area.x, this.area.y, this.area.ex(), this.area.y + Math.max(2, this.layout.getHeader()), Colors.A100 | primary, 1);

            return;
        }

        int gap = this.layout.getGap();
        int x;
        int y;

        if (insertion < count)
        {
            x = this.layout.getX(insertion) - gap / 2 - 1;
            y = this.layout.getY(insertion);
        }
        else
        {
            x = this.layout.getX(count - 1) + this.layout.getCellWidth() + gap / 2 - 1;
            y = this.layout.getY(count - 1);
        }

        x += this.originX();
        y += this.originY();

        context.batcher.box(x, y, x + 2, y + this.layout.getCellHeight(), Colors.A100 | primary);
    }

    /** A small stack of the carried cells; the front one is painted like a cell with no overlays. */
    @Override
    protected void renderDragGhost(UIContext context)
    {
        if (this.drag.getItems().isEmpty())
        {
            return;
        }

        int size = Math.min(this.cellSize, GHOST_SIZE);
        int h = this.layout.heightFor(size);
        T front = this.drag.getItems().get(0);

        this.drag.renderGhost(context, size, h, this.drag.hasTarget(), (ctx, x, y, w, gh) ->
        {
            this.renderCell(ctx, front, x, y, w, gh, this.state.reset());
        });
    }
}
