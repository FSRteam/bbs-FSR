package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A list of a model config's entries (held item slots, welds): one row per entry, named by the
 * host, painted red when the host says the entry is broken. Picking a row is how the host knows
 * which entry's settings to show — the one list shape the editor's "list + settings" blocks share.
 */
public class UIEntryList<T> extends UIList<T>
{
    private final Function<T, String> names;
    private Predicate<T> broken = (entry) -> false;

    public UIEntryList(Consumer<List<T>> callback, Function<T, String> names)
    {
        super(callback);

        this.names = names;
        this.scroll.scrollItemSize = UIConstants.LIST_ITEM_HEIGHT;
        this.background();
    }

    /** Entry values compare their contents; the list must select the exact object, including identical copies. */
    public int indexOf(T element)
    {
        for (int i = 0; i < this.list.size(); i++)
        {
            if (this.list.get(i) == element)
            {
                return i;
            }
        }

        return -1;
    }

    @Override
    public void setCurrent(T element)
    {
        this.setIndex(this.indexOf(element));
    }

    /** Keep a live entry by identity, or its old row when undo/reload replaced the value objects. */
    public void refill(List<T> entries)
    {
        T picked = this.getCurrentFirst();
        int previous = this.indexOf(picked);

        this.setList(new ArrayList<>(entries));
        int index = this.indexOf(picked);

        if (index < 0 && previous >= 0 && !entries.isEmpty())
        {
            index = Math.min(previous, entries.size() - 1);
        }

        this.setIndex(index);
    }

    /** Which entries can't work as they are; they read red. */
    public UIEntryList<T> broken(Predicate<T> broken)
    {
        this.broken = broken;

        return this;
    }

    /** The entry under the cursor, for its context menu; null over nothing. */
    public T getAtCursor(UIContext context)
    {
        int index = this.getIndexAtCursor(context);

        return index < 0 ? null : this.list.get(index);
    }

    @Override
    protected String elementToString(UIContext context, int i, T element)
    {
        return this.names.apply(element);
    }

    /** What a row is painted in: red when the entry is broken, else the list's usual hover/idle pair. */
    protected int rowColor(T element, boolean lit)
    {
        return this.broken.test(element) ? RowStyle.textColor(lit, BBSSettings.negativeColor()) : RowStyle.textColor(lit);
    }

    /** Where a row's content has to stop: the scrollbar and a margin aren't the row's to draw in. */
    protected int rowContentEnd(int x)
    {
        return x + this.area.w - 4 - this.scroll.getScrollbarArea().w;
    }

    @Override
    protected void renderElementPart(UIContext context, T element, int i, int x, int y, boolean hover, boolean selected)
    {
        FontRenderer font = context.batcher.getFont();
        int textX = x + 4;
        String label = font.limitToWidth(this.elementToString(context, i, element), this.rowContentEnd(x) - textX);

        context.batcher.textShadow(label, textX, y + (this.scroll.scrollItemSize - font.getHeight()) / 2, this.rowColor(element, hover || selected));
    }
}
