package mchorse.bbs_mod.ui.textures;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.tooltips.LabelTooltip;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.GridLayout;
import mchorse.bbs_mod.ui.utils.cells.CellState;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * The grid of a texture browser: the entries of the current folder (or the search hits) as
 * square cells, zoomed with Ctrl + wheel. It paints and hit-tests its cells and hands every
 * decision — what a click chooses, what a drop does — to the {@link UITextureBrowser browser}.
 */
public class UITextureGrid extends UIScrollView
{
    public static final int ZOOM_STEP = 8;
    public static final int MIN_CELL = 40;
    public static final int MAX_CELL = 200;

    private final UITextureBrowser browser;
    private final GridLayout layout = new GridLayout(0, 6, 3, 6, 6, 1F);
    private final CellState state = new CellState();

    private int hoverIndex = -1;

    public UITextureGrid(UITextureBrowser browser)
    {
        this.browser = browser;

        this.scroll.cancelScrolling();
        this.scroll.scrollSpeed = 40;
    }

    public GridLayout getLayout()
    {
        return this.layout;
    }

    public int getCellSize()
    {
        return BBSSettings.textureCellSize.get();
    }

    /** The cells whose rectangles overlap an area in content coordinates. */
    public List<TextureEntry> getEntriesIn(Area area)
    {
        List<TextureEntry> entries = this.browser.getEntries();
        List<TextureEntry> hit = new ArrayList<>();

        for (int index : this.layout.getIndicesIn(area))
        {
            hit.add(entries.get(index));
        }

        return hit;
    }

    /** Cursor position in content coordinates (the grid's own space, scroll included). */
    public int contentY(UIContext context)
    {
        return context.mouseY - this.area.y + (int) this.scroll.getScroll();
    }

    public int contentX(UIContext context)
    {
        return context.mouseX - this.area.x;
    }

    /** Lay the entries out for the current width and size, and tell the scroll how tall that is. */
    public void relayout()
    {
        this.layout.set(this.area.w, this.getCellSize(), this.browser.getEntries().size());
        this.scroll.scrollSize = this.layout.getContentHeight(true);
        this.scroll.clamp();
    }

    @Override
    public void resize()
    {
        super.resize();

        this.relayout();
    }

    public void scrollTo(int index)
    {
        if (index >= 0 && index < this.layout.getCount())
        {
            this.scroll.scrollIntoView(this.layout.getY(index), this.layout.getCellHeight() + 6, 6);
        }
    }

    /* Input */

    @Override
    protected IUIElement childrenMouseScrolled(UIContext context)
    {
        if (Window.isCtrlPressed() && context.mouseWheel != 0 && this.area.isInside(context))
        {
            this.zoom(context, context.mouseWheel > 0 ? ZOOM_STEP : -ZOOM_STEP);

            return this;
        }

        return super.childrenMouseScrolled(context);
    }

    /**
     * Zoom around the cursor: the content point under it, and the row it belongs to, stay
     * where they are on the screen while the cells grow or shrink around them.
     */
    private void zoom(UIContext context, int delta)
    {
        int old = this.getCellSize();
        int size = MathUtils.clamp(old + delta, MIN_CELL, MAX_CELL);

        if (size == old)
        {
            return;
        }

        int cursorY = context.mouseY - this.area.y;
        int row = this.layout.getRowAt(this.contentY(context));
        float rowOffset = 0F;

        if (row >= 0)
        {
            float pitch = this.layout.getCellHeight() + this.layout.getGap();

            rowOffset = (this.contentY(context) - this.layout.getRowY(row)) / pitch;
        }

        int scroll = (int) this.scroll.getScroll();

        BBSSettings.textureCellSize.set(size);
        this.relayout();

        if (row >= 0)
        {
            float pitch = this.layout.getCellHeight() + this.layout.getGap();

            scroll = Math.round(this.layout.getRowY(row) + rowOffset * pitch - cursorY);
        }

        this.scroll.setScroll(scroll);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            return false;
        }

        if (this.scroll.mouseClicked(context))
        {
            return true;
        }

        int x = this.contentX(context);
        int y = this.contentY(context);
        int index = this.layout.getIndex(x, y);
        List<TextureEntry> entries = this.browser.getEntries();
        TextureEntry entry = index == -1 || index >= entries.size() ? null : entries.get(index);

        if (context.mouseButton == 1)
        {
            this.browser.setContextEntry(entry);

            return false;
        }

        if (context.mouseButton != 0)
        {
            return false;
        }

        if (Window.isShiftPressed())
        {
            /* Shift + drag stretches a band; a Shift-click that goes nowhere still extends the pick */
            this.browser.pressMarquee(entry, x, y);

            return true;
        }

        if (entry == null)
        {
            this.browser.clickEmpty();

            return true;
        }

        this.browser.pressEntry(entry, context);

        return true;
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        this.browser.release();

        return false;
    }

    /* Rendering */

    @Override
    public void render(UIContext context)
    {
        this.scroll.drag(context.mouseX, context.mouseY);

        this.updateHover(context);

        context.batcher.clip(this.area, context);
        this.renderCells(context);
        this.browser.marquee.render(context, this.area.x, this.area.y - (int) this.scroll.getScroll());
        context.batcher.unclip(context);

        this.scroll.renderScrollbar(context.batcher);

        if (this.browser.getEntries().isEmpty())
        {
            FontRenderer font = context.batcher.getFont();
            String label = (this.browser.isSearching() ? UIKeys.TEXTURES_BROWSER_NO_RESULTS : UIKeys.TEXTURE_NO_DATA).get();

            context.batcher.text(label, this.area.mx(font.getWidth(label)), this.area.my() - 4, Colors.GRAY);
        }

        super.render(context);
    }

    /**
     * Who the cursor is on, and what the drag would do: a hovered folder is where the drop
     * goes; anywhere else on the grid it's the folder on show — where Ctrl makes a copy beside
     * the originals. Search hits come from many folders, so between them there is nowhere to
     * drop. Reported here so the tree, painted after this grid, can override it with a folder
     * of its own.
     */
    private void updateHover(UIContext context)
    {
        boolean inside = this.area.isInside(context) && !context.hasContextMenu();
        int x = this.contentX(context);
        int y = this.contentY(context);
        List<TextureEntry> entries = this.browser.getEntries();
        TextureDrag drag = this.browser.drag;

        this.hoverIndex = inside ? this.layout.getIndex(x, y) : -1;

        if (this.hoverIndex >= entries.size())
        {
            this.hoverIndex = -1;
        }

        if (drag.isActive())
        {
            TextureEntry entry = this.hoverIndex == -1 ? null : entries.get(this.hoverIndex);

            if (entry != null && entry.folder() && !drag.isDragging(entry.link()))
            {
                drag.setTarget(entry.link());
            }
            else if (inside && !this.browser.isSearching())
            {
                drag.setTarget(this.browser.getPath());
            }
        }

        this.updateTooltip();
    }

    /** Zoomed out past the name strip (or with a name longer than it), the cell says its name by the cursor. */
    private void updateTooltip()
    {
        LabelTooltip tooltip = null;

        if (this.hoverIndex != -1)
        {
            TextureEntry entry = this.browser.getEntries().get(this.hoverIndex);

            if (!TextureCellRenderer.showsWholeName(this.getContext(), entry, this.layout.getCellWidth()))
            {
                tooltip = new LabelTooltip(IKey.raw(entry.caption()), Direction.BOTTOM);
            }
        }

        this.tooltip = tooltip;
    }

    private void renderCells(UIContext context)
    {
        List<TextureEntry> entries = this.browser.getEntries();
        TextureDrag drag = this.browser.drag;
        Link current = this.browser.getCurrent();
        int scroll = (int) this.scroll.getScroll();
        int cellW = this.layout.getCellWidth();
        int cellH = this.layout.getCellHeight();

        for (int i = 0; i < entries.size(); i++)
        {
            int cy = this.area.y + this.layout.getY(i) - scroll;

            if (cy + cellH < this.area.y || cy > this.area.ey())
            {
                continue;
            }

            TextureEntry entry = entries.get(i);
            int cx = this.area.x + this.layout.getX(i);

            this.state.reset();
            this.state.hover = i == this.hoverIndex && !drag.isActive();
            this.state.selected = entry.link().equals(current) || (entry.folder() && this.browser.isCurrentFolder(entry.link()));
            this.state.picked = this.browser.selection.contains(entry.link());
            this.state.dragged = drag.isDragging(entry.link());
            this.state.dropTarget = entry.folder() && drag.isTarget(entry.link());

            TextureCellRenderer.render(context, entry, cx, cy, cellW, cellH, this.state);
        }
    }
}
