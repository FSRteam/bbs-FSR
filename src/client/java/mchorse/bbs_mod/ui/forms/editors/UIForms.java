package mchorse.bbs_mod.ui.forms.editors;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.CollectionUtils;

import java.util.function.BiConsumer;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

public class UIForms extends UIList<UIForms.FormEntry>
{
    public static final int INDENT = 12;

    public BiConsumer<BodyPart, Integer> onReorder;
    private BiConsumer<BodyPart, Integer> reorder;

    public void reorder(BiConsumer<BodyPart, Integer> reorder)
    {
        this.reorder = reorder;
        this.sorting();
    }

    @Override
    protected boolean canDrag(int index)
    {
        return this.exists(index) && this.list.get(index).part != null
            && (this.reorder != null || this.onReorder != null);
    }

    /** Valid insertion positions skip the descendants of every sibling. */
    private List<Integer> siblingSlots(Form parent)
    {
        List<Integer> slots = new ArrayList<>();
        int last = -1;
        int depth = 0;

        for (int i = 0; i < this.list.size(); i++)
        {
            FormEntry entry = this.list.get(i);

            if (entry.part != null && entry.form == parent)
            {
                slots.add(i);
                last = i;
                depth = entry.depth;
            }
        }

        if (last != -1)
        {
            int end = last + 1;

            while (end < this.list.size() && this.list.get(end).depth > depth)
            {
                end++;
            }

            slots.add(end);
        }

        return slots;
    }

    protected int dropInsertion(int from, int x, int y)
    {
        if (!this.canDrag(from) || !this.area.isInside(x, y))
        {
            return -1;
        }

        List<Integer> slots = this.siblingSlots(this.list.get(from).form);
        int raw = (int) Math.round((y - this.area.y + this.scroll.getScroll()) / this.scroll.scrollItemSize);
        int nearest = -1;

        for (int slot : slots)
        {
            if (nearest == -1 || Math.abs(slot - raw) < Math.abs(nearest - raw))
            {
                nearest = slot;
            }
        }

        return nearest;
    }

    @Override
    protected List<FormEntry> dragPayload(FormEntry item)
    {
        List<FormEntry> payload = super.dragPayload(item);

        return payload == null ? null : List.of(item);
    }

    @Override
    protected void reportDropTarget(int x, int y)
    {
        List<FormEntry> items = this.drag.getItems();
        int from = items.isEmpty() ? -1 : this.indexOfItem(items.get(0));
        int insertion = this.dropInsertion(from, x + this.originX(), y + this.originY());

        if (insertion >= 0)
        {
            this.drag.setTarget(this, insertion);
        }
    }

    @Override
    protected void reorder(List<FormEntry> items, int insertion)
    {
        if (items.isEmpty())
        {
            return;
        }

        FormEntry moving = items.get(0);

        List<BodyPart> siblings = moving.part.getManager().getAllTyped();
        int oldIndex = CollectionUtils.getIndex(siblings, moving.part);
        int index = this.siblingSlots(moving.form).indexOf(insertion);

        /* The slot is measured before removal; the manager accepts the final item index. */
        if (index > oldIndex)
        {
            index--;
        }

        if (oldIndex >= 0 && index >= 0 && index != oldIndex)
        {
            this.reorderPart(moving.part, index);
        }
    }

    @Override
    protected void handleSwap(int from, int to)
    {
        FormEntry moving = this.list.get(from);
        FormEntry target = this.list.get(to);

        if (moving.part != null && target.part != null
            && moving.part.getManager() == target.part.getManager())
        {
            this.reorderPart(moving.part, CollectionUtils.getIndex(target.part.getManager().getAllTyped(), target.part));
        }
    }

    private void reorderPart(BodyPart part, int index)
    {
        BiConsumer<BodyPart, Integer> callback = this.reorder == null ? this.onReorder : this.reorder;

        if (callback != null)
        {
            callback.accept(part, index);
        }
    }

    @Override
    protected int insertionInset(int insertion)
    {
        int index = this.getDraggingIndex();

        return this.exists(index) ? this.list.get(index).depth * INDENT : super.insertionInset(insertion);
    }

    public UIForms(Consumer<List<FormEntry>> callback)
    {
        super(callback);

        this.sorting();
        this.scroll.cancelScrolling();
    }

    /** Resolve a persisted stable part address; fall back to the root after removal. */
    public String setCurrentPath(String path)
    {
        FormEntry root = this.list.isEmpty() ? null : this.list.get(0);
        FormEntry selected = root;
        for (FormEntry entry : this.list)
            if (entry.getPath().equals(path)) { selected = entry; break; }
        if (selected != null) this.setCurrentScroll(selected);
        return selected == null ? "" : selected.getPath();
    }

    public void setCurrentForm(Form form)
    {
        FormEntry toSelect = null;

        for (FormEntry entry : this.list)
        {
            if (entry.getForm() == form)
            {
                toSelect = entry;

                break;
            }
        }

        if (toSelect != null)
        {
            this.setCurrentScroll(toSelect);
        }
    }

    public void setForm(Form form)
    {
        this.clear();

        this.add(new FormEntry(form, null, 0));

        if (form != null)
        {
            this.setupRecursively(form, 1, 0);
        }
    }

    private void setupRecursively(Form parent, int depth, int lines)
    {
        List<BodyPart> parts = parent.parts.getAllTyped();

        for (int i = 0; i < parts.size(); i++)
        {
            BodyPart part = parts.get(i);
            boolean last = i == parts.size() - 1;
            this.add(new FormEntry(parent, part, depth, lines, last));

            if (part.getForm() != null)
            {
                this.setupRecursively(part.getForm(), depth + 1, childGuideLines(lines, depth, last));
            }
        }
    }

    @Override
    protected int indent(FormEntry element)
    {
        return element.depth * INDENT;
    }

    @Override
    protected int indentStep()
    {
        return INDENT;
    }

    @Override
    protected void renderElementPart(UIContext context, FormEntry element, int i, int x, int y, boolean hover, boolean selected)
    {
        this.renderTreeGuides(context, x, y, element.depth, element.lines, element.last, x + this.rowContentX(element));
        super.renderElementPart(context, element, i, x, y, hover, selected);

        Form form = element.getForm();

        if (form != null && BBSSettings.listModelPreview.get())
        {
            x += this.area.w - 40;

            context.batcher.clip(x, y, 40, 20, context);

            y -= 10;

            FormUtilsClient.renderUI(form, context, x, y, x + 40, y + 40, selected);

            context.batcher.unclip(context);
        }
    }

    @Override
    protected String elementToString(UIContext context, int i, FormEntry element)
    {
        return element.toString();
    }

    public static class FormEntry
    {
        public Form form;
        public BodyPart part;
        public int depth;
        public final int lines;
        public final boolean last;

        public FormEntry(Form form, BodyPart part, int depth)
        {
            this(form, part, depth, 0, true);
        }

        public FormEntry(Form form, BodyPart part, int depth, int lines, boolean last)
        {
            this.form = form;
            this.part = part;
            this.depth = depth;
            this.lines = lines;
            this.last = last;
        }

        public Form getForm()
        {
            return this.part == null ? this.form : this.part.getForm();
        }

        public String getPath()
        {
            Form value = this.getForm();
            return value != null ? FormUtils.getPath(value)
                : this.part == null ? "" : StringUtils.combinePaths(FormUtils.getPath(this.form), this.part.getId());
        }

        @Override
        public boolean equals(Object obj)
        {
            if (super.equals(obj))
            {
                return true;
            }

            if (obj instanceof FormEntry)
            {
                FormEntry entry = (FormEntry) obj;

                return this.form == entry.form
                    && this.part == entry.part
                    && this.depth == entry.depth;
            }

            return false;
        }

        @Override
        public int hashCode()
        {
            return 31 * (31 * System.identityHashCode(this.form) + System.identityHashCode(this.part)) + this.depth;
        }

        @Override
        public String toString()
        {
            if (this.part == null)
            {
                return this.form == null ? "-" : this.form.getDisplayName();
            }
            else if (this.part.getForm() == null)
            {
                return "-";
            }

            return this.part.getForm().getDisplayName();
        }
    }
}
