package mchorse.bbs_mod.ui.framework.elements.input.grid;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generic scrollable card grid.
 *
 * <p>The grid owns card plates (fill, border, selection and hover overlays,
 * rounded through {@link BBSSettings#cornerWidget()}); the actual card content
 * is delegated to {@link ICardRenderer}. Selection semantics mirror
 * {@link mchorse.bbs_mod.ui.framework.elements.input.list.UIList}: plain click
 * selects, Ctrl toggles, Shift range-selects. Clicking the already selected
 * card (without modifiers) activates it — the same second-click-opens idiom
 * {@link mchorse.bbs_mod.ui.dashboard.list.UIDataPathList} uses with
 * {@code openOnSingleClick(false)}. Arrow keys move the selection across the
 * grid when the mouse hovers the grid or a card is selected.
 */
public class UICardGrid<T> extends UIElement
{
    /** Horizontal/vertical spacing between cards and against the edges. */
    public static final int GAP = 8;

    /**
     * Renders card content on top of the plate drawn by this grid. All
     * callbacks run during rendering: implementations must not allocate
     * or perform IO.
     */
    public interface ICardRenderer<T>
    {
        /** Full card height including the thumbnail and text areas. */
        int cardHeight();

        /** Preferred card width; also acts as the column count driver/cap. */
        int preferredCardWidth();

        /**
         * Draw the card content into the given area (the card rect minus
         * nothing — plate, border and overlays are already drawn).
         */
        void render(UIContext context, Area area, T item, boolean selected, boolean hovered);
    }

    protected List<T> list = new ArrayList<>();
    private List<T> copy = new ArrayList<>();
    protected List<Integer> current = new ArrayList<>();
    public boolean multi;

    public Scroll scroll;
    public Consumer<List<T>> callback;
    private final ICardRenderer<T> renderer;

    /* Cached geometry, recomputed in resize() */
    private int columns = 1;
    private int cardWidth;
    private int rowHeight;

    private final Area cardArea = new Area();

    public UICardGrid(Consumer<List<T>> callback, ICardRenderer<T> renderer)
    {
        super();

        this.callback = callback;
        this.renderer = renderer;
        this.scroll = new Scroll(this.area, 1);
    }

    /* Selection */

    public boolean isSelected()
    {
        return !this.current.isEmpty();
    }

    public List<Integer> getCurrentIndices()
    {
        return this.current;
    }

    public List<T> getCurrent()
    {
        this.copy.clear();

        for (Integer index : this.current)
        {
            if (this.exists(index))
            {
                this.copy.add(this.list.get(index));
            }
        }

        return this.copy;
    }

    public T getCurrentFirst()
    {
        if (!this.current.isEmpty())
        {
            int index = this.current.get(0);

            if (this.exists(index))
            {
                return this.list.get(index);
            }
        }

        return null;
    }

    public void deselect()
    {
        this.current.clear();
    }

    public void setIndex(int index)
    {
        this.current.clear();
        this.addIndex(index);
    }

    public void addIndex(int index)
    {
        if (this.exists(index) && !this.current.contains(index))
        {
            this.current.add(index);
        }
    }

    public void toggleIndex(int index)
    {
        if (this.exists(index))
        {
            int i = this.current.indexOf(index);

            if (i == -1)
            {
                this.current.add(index);
            }
            else
            {
                this.current.remove(i);
            }
        }
    }

    public void selectAll()
    {
        if (!this.multi)
        {
            return;
        }

        this.current.clear();

        for (int i = 0; i < this.list.size(); i++)
        {
            this.current.add(i);
        }
    }

    public UICardGrid<T> multi()
    {
        this.multi = true;

        return this;
    }

    /* Content */

    public List<T> getList()
    {
        return this.list;
    }

    public void fill(Collection<T> elements)
    {
        this.list.clear();
        this.list.addAll(elements);
        this.current.clear();
        this.update();
    }

    public void clear()
    {
        this.fill(new ArrayList<>());
    }

    public boolean exists(int index)
    {
        return index >= 0 && index < this.list.size();
    }

    public void update()
    {
        this.scroll.scrollItemSize = Math.max(1, this.rowHeight);
        this.scroll.setSize(this.getRowCount());
        this.scroll.clamp();
    }

    private int getRowCount()
    {
        int columns = Math.max(1, this.columns);

        return (this.list.size() + columns - 1) / columns;
    }

    /* Layout */

    @Override
    public void resize()
    {
        super.resize();

        int cardHeight = this.renderer.cardHeight();

        this.rowHeight = cardHeight + GAP;

        int usable = Math.max(0, this.area.w - GAP * 2);
        int preferred = Math.max(1, this.renderer.preferredCardWidth());

        this.columns = Math.max(1, usable / preferred);
        this.cardWidth = this.columns == 1 ? usable : (usable - (this.columns - 1) * GAP) / this.columns;

        this.update();
    }

    /* Hit testing */

    private int getIndexAtCursor(int mouseX, int mouseY)
    {
        int px = mouseX - this.area.x - GAP;
        int py = mouseY - this.area.y + (int) this.scroll.getScroll();

        if (px < 0 || py < 0 || this.cardWidth <= 0 || this.rowHeight <= 0)
        {
            return -1;
        }

        int col = px / (this.cardWidth + GAP);

        if (col >= this.columns || px - col * (this.cardWidth + GAP) >= this.cardWidth)
        {
            return -1;
        }

        int row = py / this.rowHeight;

        if (py - row * this.rowHeight >= this.renderer.cardHeight())
        {
            return -1;
        }

        int index = row * this.columns + col;

        return this.exists(index) ? index : -1;
    }

    /* Input */

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.scroll.mouseClicked(context))
        {
            return true;
        }

        if (this.area.isInside(context) && context.mouseButton == 0)
        {
            int index = this.getIndexAtCursor(context.mouseX, context.mouseY);

            if (this.exists(index))
            {
                boolean activate = this.current.size() == 1
                    && this.current.get(0) == index
                    && !Window.isCtrlPressed()
                    && !Window.isShiftPressed();

                this.applySelectionOnClick(index);

                if (activate && this.callback != null)
                {
                    this.callback.accept(this.getCurrent());
                }

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    /** Mirrors UIList: Shift range-selects, Ctrl toggles, plain click replaces. */
    protected void applySelectionOnClick(int index)
    {
        if (this.multi && Window.isShiftPressed() && this.isSelected())
        {
            int first = this.current.get(0);
            int increment = first > index ? -1 : 1;

            for (int i = first + increment; i != index + increment; i += increment)
            {
                this.addIndex(i);
            }
        }
        else if (this.multi && Window.isCtrlPressed())
        {
            this.toggleIndex(index);
        }
        else
        {
            this.setIndex(index);
        }
    }

    @Override
    protected boolean mouseClickedContextMenu(UIContext context)
    {
        if (this.area.isInside(context) && context.mouseButton == 1 && !context.hasContextMenu())
        {
            int index = this.getIndexAtCursor(context.mouseX, context.mouseY);

            if (this.exists(index) && !this.current.contains(index))
            {
                this.setIndex(index);
            }
        }

        return super.mouseClickedContextMenu(context);
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        return this.scroll.mouseScroll(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        return this.scroll.tryMouseReleased(context) || super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.scroll.cancelDragging(context.mouseButton);

        super.subMouseCanceled(context);
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (this.moveSelectionWithArrows(context))
        {
            return true;
        }

        return super.subKeyPressed(context);
    }

    /**
     * Arrow keys move the selection while the cursor hovers the grid or a card
     * is selected. Left/right step within a row, up/down jump by a full row.
     */
    private boolean moveSelectionWithArrows(UIContext context)
    {
        if (!this.isSelected() && !this.area.isInside(context))
        {
            return false;
        }

        int index = this.getCurrentFirst() == null ? -1 : this.current.get(this.current.size() - 1);
        int target = -1;

        if (context.isPressed(GLFW.GLFW_KEY_LEFT))
        {
            target = index > 0 ? index - 1 : index;
        }
        else if (context.isPressed(GLFW.GLFW_KEY_RIGHT))
        {
            int col = index % this.columns;

            target = col < this.columns - 1 && this.exists(index + 1) ? index + 1 : index;
        }
        else if (context.isPressed(GLFW.GLFW_KEY_UP))
        {
            target = index - this.columns;
        }
        else if (context.isPressed(GLFW.GLFW_KEY_DOWN))
        {
            target = index + this.columns;
        }

        if (target == -1 || !this.exists(target) || target == index)
        {
            return false;
        }

        this.setIndex(target);
        this.scroll.scrollIntoView((target / this.columns) * this.rowHeight, this.rowHeight, 0);

        return true;
    }

    /** Fires the activate callback for the current selection (bind to Enter by the host). */
    public void activateSelection()
    {
        T first = this.getCurrentFirst();

        if (first != null && this.callback != null)
        {
            this.callback.accept(this.getCurrent());
        }
    }

    /* Rendering */

    @Override
    public void render(UIContext context)
    {
        this.scroll.drag(context);

        context.batcher.clip(this.area, context);
        this.renderCards(context);
        this.scroll.renderScrollbar(context.batcher, context.mouseX, context.mouseY);
        context.batcher.unclip(context);

        super.render(context);
    }

    private void renderCards(UIContext context)
    {
        int count = this.list.size();
        int cardHeight = this.renderer.cardHeight();
        int scroll = (int) this.scroll.getScroll();
        int low = this.area.y;
        int high = this.area.ey();

        for (int i = 0; i < count; i++)
        {
            int row = i / this.columns;
            int col = i % this.columns;
            int x = this.area.x + GAP + col * (this.cardWidth + GAP);
            int y = this.area.y + row * this.rowHeight - scroll;

            if (y + cardHeight < low)
            {
                continue;
            }

            if (y >= high)
            {
                break;
            }

            boolean hovered = context.mouseX >= x
                && context.mouseY >= y
                && context.mouseX < x + this.cardWidth
                && context.mouseY < y + cardHeight;
            boolean selected = this.current.contains(i);

            this.renderPlate(context, x, y, this.cardWidth, cardHeight, selected, hovered);
            this.cardArea.set(x, y, this.cardWidth, cardHeight);
            this.renderer.render(context, this.cardArea, this.list.get(i), selected, hovered);
        }
    }

    /**
     * Card plate: raised fill + divider border, primary border and tinted
     * overlay when selected, softer overlay on hover. Rounded paths only run
     * when the theme configures corner radii; radius 0 must stay bit-equal to
     * the legacy square path.
     */
    private void renderPlate(UIContext context, int x, int y, int w, int h, boolean selected, boolean hovered)
    {
        int radius = BBSSettings.cornerWidget();
        int fill = BBSSettings.raisedSurface();
        int border = selected ? BBSSettings.primaryColor() : BBSSettings.dividerColor();

        if (radius > 0)
        {
            context.batcher.roundedFrame(x, y, w, h, radius, 1, border, fill);
        }
        else
        {
            context.batcher.box(x, y, x + w, y + h, fill);
            context.batcher.outline(x, y, x + w, y + h, border);
        }

        int overlay = selected ? BBSSettings.primaryColor(Colors.A12) : (hovered ? BBSSettings.primaryColor(Colors.A25) : 0);

        if (overlay != 0)
        {
            if (radius > 0)
            {
                context.batcher.roundedBox(x, y, w, h, radius, overlay);
            }
            else
            {
                context.batcher.box(x, y, x + w, y + h, overlay);
            }
        }
    }
}
