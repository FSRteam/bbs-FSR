package mchorse.bbs_mod.forms.categories;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.forms.UIFormList;
import mchorse.bbs_mod.ui.forms.categories.UIFormCategory;
import mchorse.bbs_mod.ui.forms.categories.UIRecentFormCategory;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class RecentFormCategory extends FormCategory
{
    private static final int MAX_FORMS = 128;

    /* FormCategory intentionally exposes its backing list for category-specific
     * editing. Keep that API mutable, but make every mutating path use the same
     * recent-queue policy. */
    private List<Form> directForms;

    public RecentFormCategory(ValueBoolean visibility)
    {
        super(UIKeys.FORMS_CATEGORIES_RECENT, visibility);
    }

    @Override
    public void addForm(Form form)
    {
        this.getDirectForms().add(form);
    }

    @Override
    public List<Form> getDirectForms()
    {
        if (this.directForms == null)
        {
            this.directForms = new RecentFormsList(super.getDirectForms());
        }

        return this.directForms;
    }

    @Override
    public void replaceForm(int index, Form form)
    {
        if (form != null && index >= 0 && index < this.getDirectForms().size())
        {
            this.getDirectForms().set(index, form);
        }
    }

    @Override
    public void fromData(MapType data)
    {
        if (data.has("title"))
        {
            this.title = IKey.constant(data.getString("title"));
        }

        if (data.has("id"))
        {
            this.visible.setId(data.getString("id"));
        }

        /* Do not let repeated settings reloads retain old entries. Decode one
         * form at a time so a large or malformed list never grows the queue
         * beyond the bounded policy. */
        List<Form> forms = this.getDirectForms();
        forms.clear();

        for (BaseType formData : data.getList("forms"))
        {
            if (formData.isMap())
            {
                Form form = FormUtils.fromData(formData.asMap());

                if (form != null)
                {
                    forms.add(form);
                }
            }
        }
    }

    static <T> void addRecent(List<T> entries, T entry)
    {
        if (entry == null)
        {
            return;
        }

        entries.removeIf(existing -> existing == null || entry.equals(existing));
        entries.add(entry);

        int overflow = entries.size() - MAX_FORMS;

        if (overflow > 0)
        {
            entries.subList(0, overflow).clear();
        }
    }

    private static final class RecentFormsList extends AbstractList<Form>
    {
        private final List<Form> delegate;

        private RecentFormsList(List<Form> delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Form get(int index)
        {
            return this.delegate.get(index);
        }

        @Override
        public int size()
        {
            return this.delegate.size();
        }

        @Override
        public Form set(int index, Form form)
        {
            Form previous = this.delegate.get(index);

            if (form != null)
            {
                this.delegate.remove(index);
                addRecent(this.delegate, form);
                this.modCount++;
            }

            return previous;
        }

        @Override
        public void add(int index, Form form)
        {
            if (index < 0 || index > this.delegate.size())
            {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + this.delegate.size());
            }

            if (form != null)
            {
                addRecent(this.delegate, form);
                this.modCount++;
            }
        }

        @Override
        public boolean add(Form form)
        {
            if (form == null)
            {
                return false;
            }

            addRecent(this.delegate, form);
            this.modCount++;

            return true;
        }

        @Override
        public boolean addAll(Collection<? extends Form> forms)
        {
            return this.addAll(this.delegate.size(), forms);
        }

        @Override
        public boolean addAll(int index, Collection<? extends Form> forms)
        {
            if (index < 0 || index > this.delegate.size())
            {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + this.delegate.size());
            }

            if (forms == null || forms.isEmpty())
            {
                return false;
            }

            List<Form> copy = new ArrayList<>(forms);
            boolean changed = false;

            for (Form form : copy)
            {
                changed |= this.add(form);
            }

            return changed;
        }

        @Override
        public Form remove(int index)
        {
            Form removed = this.delegate.remove(index);
            this.modCount++;

            return removed;
        }

        @Override
        public boolean remove(Object object)
        {
            boolean removed = this.delegate.remove(object);

            if (removed)
            {
                this.modCount++;
            }

            return removed;
        }

        @Override
        public void clear()
        {
            if (!this.delegate.isEmpty())
            {
                this.delegate.clear();
                this.modCount++;
            }
        }
    }

    @Override
    public boolean canModify(Form form)
    {
        return true;
    }

    @Override
    public UIFormCategory createUI(UIFormList list)
    {
        return new UIRecentFormCategory(this, list);
    }
}
