package mchorse.bbs_mod.ui.framework.elements.buttons;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.tooltips.ITooltip;
import mchorse.bbs_mod.ui.framework.tooltips.LabelTooltip;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The shared body of the icon strips: the items, where their cells sit, what the cursor lands on
 * and how a cell is painted. Which cells count as active is the only thing the strips disagree
 * on, so it is the only thing they carry themselves — see {@link #isActive(int)} and
 * {@link #pick(int)}.
 *
 * <p>Cells are a fixed {@link #CELL} wide rather than an even split of whatever width the strip
 * was handed: a strip of two in a full-width column used to draw two cells a hundred pixels
 * across with the icon stranded in the middle of each. Spare width is now left empty beside the
 * run ({@link #align(float)} says on which side, the far end by default), and the run is what
 * the cursor is tested against, so that emptiness doesn't take clicks either. A strip given less
 * than it needs shrinks its cells instead of overflowing.
 */
public abstract class UIIconStrip <T> extends UIClickable<T>
{
    /** Cell width: the icon's 16 with two pixels of air on either side, like an icon button's square. */
    public static final int CELL = 20;

    /** Icon tint of a cell that isn't active — readable, but plainly not the one that is. */
    protected static final int INACTIVE = Colors.setA(Colors.WHITE, 0.35F);

    protected final List<Item> items = new ArrayList<>();

    /** Where the run sits in a slot wider than it needs: 0 left, 0.5 centre, 1 right. */
    protected float align = 1F;

    /** Whether the cells share the whole width instead of keeping to {@link #CELL} — see {@link #stretch()}. */
    protected boolean stretch;
    private int pendingIndex = -1;

    public UIIconStrip(Consumer<T> callback)
    {
        super(callback);

        this.h(UIConstants.CONTROL_HEIGHT);
    }

    public int getCount()
    {
        return this.items.size();
    }

    /**
     * The width the run of cells wants. Callers that place the strip in a slot of their own
     * size it by this, rather than repeating the cell arithmetic.
     */
    public int getPreferredWidth()
    {
        return this.items.size() * CELL;
    }

    protected void addItem(Icon icon, IKey tooltip)
    {
        this.items.add(new Item(icon, tooltip));
    }

    /** Whether the cell at this index wears the active mark. */
    protected abstract boolean isActive(int index);

    /**
     * Act on a click that landed on this cell. Returns whether anything changed — a no-op press
     * (picking what is already picked) neither clicks nor calls back.
     */
    protected abstract boolean pick(int index);

    protected boolean canPick(int index)
    {
        return true;
    }

    /**
     * Drawn cell width. {@link #CELL} normally; less when the strip was given a narrower area
     * than its cells add up to, so a cramped strip stays inside its bounds.
     */
    protected int getCellWidth()
    {
        if (this.items.isEmpty())
        {
            return CELL;
        }

        return Math.max(1, Math.min(CELL, this.area.w / this.items.size()));
    }

    /**
     * The strip in a named row, sized to its cells and pinned to the same divider column as the
     * label rows around it, so a panel of properties keeps one edge. A strip is one question, so
     * it gets one name rather than a name per cell — which is the whole reason to reach for a
     * strip instead of a column of toggles.
     */
    public UIElement labelRow(IKey label)
    {
        return UI.labelRow(label, this.getPreferredWidth(), this);
    }

    /**
     * Which end of a too-wide slot the run is pinned to. The default is the right, so a strip
     * ends flush with the fields above and below it the way every other control in a row does;
     * a strip sitting under its own left-aligned label wants {@code 0} instead.
     */
    public T align(float align)
    {
        this.align = align;

        return this.get();
    }

    /**
     * Let the cells share the strip's whole width rather than keep to {@link #CELL} — for a strip
     * that is a row's one control and the main thing to pick in its panel (the sides of a cube in
     * the model editor's unwrap pane), where a run of narrow cells pinned to one end reads as an
     * afterthought. The strips that sit among other controls keep their fixed cells.
     */
    public T stretch()
    {
        this.stretch = true;

        return this.get();
    }

    /** X the run of cells starts at, once the spare width has been handed to {@link #align(float)}. */
    protected int getContentX()
    {
        int spare = Math.max(0, this.area.w - this.getCellWidth() * this.items.size());

        return this.area.x + (int) (spare * this.align);
    }

    /**
     * Where cell {@code index} starts; {@code index == count} is where the run ends. A stretched
     * strip spreads the width in whole pixels from the left edge, so the cells cover it exactly
     * and none is short by what an even division drops.
     */
    protected int cellX(int index)
    {
        if (this.stretch)
        {
            return this.area.x + this.area.w * index / Math.max(1, this.items.size());
        }

        return this.getContentX() + index * this.getCellWidth();
    }

    /** The cell under the cursor, or {@code -1} when the cursor is beside the run. */
    protected int indexAt(int mouseX)
    {
        if (this.stretch)
        {
            if (this.items.isEmpty() || mouseX < this.area.x || mouseX >= this.area.ex())
            {
                return -1;
            }

            return Math.min(this.items.size() - 1, (mouseX - this.area.x) * this.items.size() / Math.max(1, this.area.w));
        }

        int offset = mouseX - this.getContentX();

        /* Not a plain division: integer division rounds toward zero, so the cell-width of empty
         * space just left of the run would come out as 0 and pick the first cell. */
        if (offset < 0)
        {
            return -1;
        }

        int index = offset / this.getCellWidth();

        return index < this.items.size() ? index : -1;
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.isEnabled() && context.mouseButton == 0 && this.area.isInside(context))
        {
            int index = this.indexAt(context.mouseX);

            if (index >= 0)
            {
                if (this.canPick(index))
                {
                    this.pendingIndex = index;

                    try
                    {
                        return super.subMouseClicked(context);
                    }
                    finally
                    {
                        this.pendingIndex = -1;
                    }
                }

                return true;
            }
        }

        /* Deliberately not {@link UIClickable#subMouseClicked}: its blanket handler swallows any
         * press inside the area and fires the callback for it, which here would mean the spare
         * width beside the run — and a disabled strip — picking an item. A strip has nothing to
         * take beyond its own cells. */
        return false;
    }

    @Override
    protected void click(int mouseButton)
    {
        if (this.pendingIndex < 0 || this.pick(this.pendingIndex))
        {
            super.click(mouseButton);
        }
    }

    @Override
    protected void renderSkin(UIContext context)
    {
        this.tooltip = null;

        int count = this.items.size();

        if (count == 0)
        {
            this.renderLockedArea(context);

            return;
        }

        int start = this.cellX(0);
        int width = this.cellX(count) - start;
        int hovered = this.hover ? this.indexAt(context.mouseX) : -1;
        int radius = BBSSettings.cornerWidget();

        if (radius > 0)
        {
            int fill = BBSSettings.fieldFillColor();
            int border = BBSSettings.fieldBorderColor();

            context.batcher.roundedFrame(
                start,
                this.area.y,
                width,
                this.area.h,
                radius,
                1F,
                border == 0 ? BBSSettings.dividerColor() : border,
                fill == 0 ? BBSSettings.deepSurface() : fill
            );
        }
        else
        {
            Area.SHARED.set(start, this.area.y, width, this.area.h);
            Area.SHARED.render(context.batcher, BBSSettings.deepSurface());
        }

        for (int i = 0; i < count; i++)
        {
            int x1 = this.cellX(i);
            int x2 = this.cellX(i + 1);
            boolean active = this.isActive(i);
            boolean cellHover = i == hovered;

            if (active)
            {
                Area.SHARED.set(x1, this.area.y, x2 - x1, this.area.h);
                UIDashboardPanels.renderHighlight(context.batcher, Area.SHARED, Direction.BOTTOM);
            }
            else if (cellHover)
            {
                RowStyle.hover(context.batcher, x1, this.area.y, x2 - x1, this.area.h, 0);
            }

            int stateFill = 0;

            if (!this.isEnabled())
            {
                stateFill = BBSSettings.iconDisabledColor();
            }
            else if (this.pressed && cellHover)
            {
                stateFill = BBSSettings.iconPressedColor();
            }

            if (stateFill != 0)
            {
                if (radius > 0)
                {
                    context.batcher.roundedBox(x1, this.area.y, x2 - x1, this.area.h, radius, stateFill);
                }
                else
                {
                    context.batcher.box(x1, this.area.y, x2, this.area.ey(), stateFill);
                }
            }

            int iconColor = !this.isEnabled()
                ? Colors.setA(Colors.WHITE, 0.35F)
                : (active ? Colors.WHITE : (cellHover ? Colors.LIGHTEST_GRAY : INACTIVE));

            context.batcher.icon(this.items.get(i).icon, iconColor, (x1 + x2) / 2, this.area.my(), 0.5F, 0.5F);
        }

        if (hovered >= 0)
        {
            this.tooltip = this.items.get(hovered).tooltip;
        }

        this.renderLockedArea(context);
    }

    protected static class Item
    {
        public final Icon icon;
        public final ITooltip tooltip;

        public Item(Icon icon, IKey tooltip)
        {
            this.icon = icon;
            /* A cell whose icon says it all takes no tooltip - null, rather than an empty one that
             * would still open a box under the cursor. */
            this.tooltip = tooltip == null ? null : new LabelTooltip(tooltip, Direction.TOP);
        }
    }
}
